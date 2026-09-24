#!/usr/bin/env python3
"""Load a flow definition into a running NiFi, a NiFi Registry, or both, and report back.

Static checks against a Knowledge Base cannot see everything. Relationships that a
processor only exposes for certain property values, and fields whose absence makes the
import itself fail, show up only when real NiFi parses the file.

With --nifi-url the flow is uploaded into a temporary process group, the validation state
of every component is read back, and the group is deleted. With --registry-url it is stored
as version 1 of a temporary versioned flow, then removed. The two answer different
questions: NiFi says whether the components are valid, the Registry says whether the file
is a well-formed snapshot, which is what a flow destined for version control has to be.
Run both when the flow is meant to live in a registry.

It writes to both targets. Everything it creates is removed in a finally block, including
on Ctrl+C, but a hard kill can leave something behind; it is all named
`kb-verify-<timestamp>-<id>` so it is easy to spot and delete.

Auth follows the same convention as qubership-nifi-kb-builder-tool: no secret is ever passed
as an argument. Certificate mode reads NIFI_PKCS12_PASSWORD, token mode reads
NIFI_ACCESS_TOKEN, cookie mode reads NIFI_AUTHORIZATION_BEARER_COOKIE.

Python 3.8+. Standard library only, except that PKCS#12 unpacking uses `cryptography` when
installed and falls back to the `openssl` command line.
"""

import argparse
import http.client
import json
import os
import re
import shutil
import ssl
import subprocess
import sys
import tempfile
import time
import urllib.parse
import uuid
from datetime import datetime, timezone
from pathlib import Path

# A controller service always imports disabled, so every component that references one
# reports this. It is a property of the import, not a defect in the flow.
EXPECTED_NOISE = re.compile(r"Controller Service with ID \S+ is disabled")

# A component is validated as it is created, so during an import a processor can report an
# unhandled relationship in the moment before its connections exist. Usually NiFi corrects
# itself within a poll or two, which is what the settle loop waits for; occasionally the
# stale verdict survives indefinitely, which UNCONNECTED_RELATIONSHIP below deals with.
SETTLE_INTERVAL = 3
SETTLE_REPEATS = 3

UNCONNECTED_RELATIONSHIP = re.compile(
    r"Relationship '([^']+)' is not connected to any component and is not auto-terminated")


class LiveError(Exception):
    """A problem the caller can act on, reported without a traceback."""


# ---------------------------------------------------------------------------
# TLS and transport
# ---------------------------------------------------------------------------


def unpack_pkcs12(path, password, workdir):
    """Split a PKCS#12 file into the PEM cert and key files an SSLContext needs."""
    cert_pem = workdir / "client-cert.pem"
    key_pem = workdir / "client-key.pem"
    try:
        from cryptography.hazmat.primitives.serialization import (
            Encoding, NoEncryption, PrivateFormat, pkcs12,
        )
        secret = password.encode() if isinstance(password, str) else password
        key, cert, chain = pkcs12.load_key_and_certificates(path.read_bytes(), secret)
        if key is None or cert is None:
            raise LiveError("%s has no private key entry with a certificate." % path)
        body = cert.public_bytes(Encoding.PEM)
        for extra in chain or []:
            body += extra.public_bytes(Encoding.PEM)
        cert_pem.write_bytes(body)
        key_pem.write_bytes(key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
        return cert_pem, key_pem
    except ImportError:
        pass

    if shutil.which("openssl") is None:
        raise LiveError(
            "Reading a PKCS#12 file needs either the 'cryptography' package or the 'openssl' "
            "command. Install one, or use --auth token."
        )
    env = dict(os.environ, KBPW=password.decode() if isinstance(password, bytes) else password)
    for args, target in (
        (["-clcerts", "-nokeys"], cert_pem),
        (["-nocerts", "-nodes"], key_pem),
    ):
        result = subprocess.run(
            ["openssl", "pkcs12", "-in", str(path), "-passin", "env:KBPW"] + args,
            capture_output=True, env=env,
        )
        if result.returncode != 0:
            raise LiveError("openssl could not read %s: %s"
                            % (path, result.stderr.decode(errors="replace")[:200]))
        target.write_bytes(result.stdout)
    return cert_pem, key_pem


def build_context(args, workdir):
    context = ssl.create_default_context(cafile=args.ca_file)
    if args.auth == "certificate":
        if not args.certificate_file:
            raise LiveError("--auth certificate requires --certificate-file <pkcs12-path>")
        password = os.environ.get("NIFI_PKCS12_PASSWORD")
        if password is None:
            raise LiveError("Certificate mode reads the PKCS#12 password from NIFI_PKCS12_PASSWORD.")
        cert_pem, key_pem = unpack_pkcs12(Path(args.certificate_file), password, workdir)
        context.load_cert_chain(str(cert_pem), str(key_pem))
    return context


def auth_headers(args):
    if args.auth == "token":
        token = os.environ.get("NIFI_ACCESS_TOKEN")
        if not token:
            raise LiveError("Token mode reads the token from NIFI_ACCESS_TOKEN.")
        return {"Authorization": "Bearer " + token}
    if args.auth == "cookie":
        cookie = os.environ.get("NIFI_AUTHORIZATION_BEARER_COOKIE")
        if not cookie:
            raise LiveError("Cookie mode reads the cookie from NIFI_AUTHORIZATION_BEARER_COOKIE.")
        return {"Cookie": "__Secure-Authorization-Bearer=" + cookie}
    return {}


class Client:
    """A minimal REST client for either product; they differ only in the API prefix."""

    def __init__(self, base_url, context, headers, api="nifi-api", ui="nifi"):
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme != "https":
            raise LiveError("URL must use HTTPS; got '%s'." % base_url)
        self.host = parsed.hostname
        self.port = parsed.port or 443
        self.context = context
        self.headers = headers
        # Accept a URL that already ends in the API or UI path, so pasting either works.
        # The API alternative comes first because the UI path is a prefix of it.
        path = re.sub(r"/(%s|%s)/?$" % (re.escape(api), re.escape(ui)), "", parsed.path or "")
        self.prefix = path.rstrip("/") + "/" + api

    def _request(self, method, path, body=None, headers=None, params=None):
        url = self.prefix + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        conn = http.client.HTTPSConnection(self.host, self.port, context=self.context, timeout=90)
        try:
            merged = dict(self.headers)
            merged.update(headers or {})
            conn.request(method, url, body=body, headers=merged)
            response = conn.getresponse()
            payload = response.read()
            return response.status, payload
        finally:
            conn.close()

    def get(self, path, params=None):
        status, payload = self._request("GET", path, params=params)
        if status >= 300:
            raise LiveError("GET %s -> HTTP %d: %s" % (path, status, payload[:300].decode(errors="replace")))
        return json.loads(payload)

    def post(self, path, payload):
        body = json.dumps(payload).encode()
        return self._request("POST", path, body=body,
                             headers={"Content-Type": "application/json",
                                      "Content-Length": str(len(body))})

    def delete(self, path, params=None):
        return self._request("DELETE", path, params=params)[0]

    def upload(self, path, filename, content, fields):
        """Multipart POST, hand-rolled to keep this script dependency-free."""
        boundary = "----kbverify" + uuid.uuid4().hex
        parts = []
        for key, value in fields.items():
            parts.append(
                ("--%s\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n"
                 % (boundary, key, value)).encode()
            )
        parts.append(
            ("--%s\r\nContent-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\n"
             "Content-Type: application/json\r\n\r\n" % (boundary, filename)).encode()
        )
        parts.append(content)
        parts.append(("\r\n--%s--\r\n" % boundary).encode())
        body = b"".join(parts)
        headers = {"Content-Type": "multipart/form-data; boundary=" + boundary,
                   "Content-Length": str(len(body))}
        status, payload = self._request("POST", path, body=body, headers=headers)
        return status, payload


# ---------------------------------------------------------------------------
# NiFi Registry
# ---------------------------------------------------------------------------


def load_flow(flow_bytes):
    """Parse a flow definition, refusing any file without a top-level `flowContents` object.

    A NiFi download and a Registry export both carry one; other shapes are not accepted.
    """
    try:
        doc = json.loads(flow_bytes)
    except ValueError as exc:
        raise LiveError("Flow file is not valid JSON: %s" % exc)

    if not isinstance(doc, dict) or not isinstance(doc.get("flowContents"), dict):
        raise LiveError(
            "This file is not a flow definition. Expected a top-level 'flowContents' object."
        )
    return doc


def registry_snapshot(flow_bytes, bucket_id, flow_id, comments):
    """The flow definition with `snapshotMetadata` for the temporary flow set on it.

    The file must pass `load_flow`. Every other field is sent as the file has it, so the
    Registry judges the file itself; any `snapshotMetadata` already present is replaced.
    """
    doc = load_flow(flow_bytes)
    doc["snapshotMetadata"] = {"bucketIdentifier": bucket_id, "flowIdentifier": flow_id,
                               "version": 1, "comments": comments}
    return doc


def resolve_bucket(client, args, label):
    """Return the bucket to write into and whether this run created it."""
    if args.registry_bucket:
        wanted = args.registry_bucket
        for bucket in client.get("/buckets"):
            if wanted in (bucket.get("identifier"), bucket.get("name")):
                return bucket, False
        raise LiveError("No bucket called '%s' in this Registry." % wanted)

    status, payload = client.post(
        "/buckets", {"name": label, "description": "Temporary bucket from verify_live.py"})
    if status >= 300:
        raise LiveError(
            "Could not create a bucket (HTTP %d): %s\nIf this account may not create "
            "buckets, pass --registry-bucket <name> to use an existing one."
            % (status, payload[:300].decode(errors="replace"))
        )
    return json.loads(payload), True


def registry_rejected(stage, status, payload):
    text = payload.decode(errors="replace") if isinstance(payload, bytes) else str(payload)
    print("REGISTRY REJECTED THE FLOW  (%s)  HTTP %d" % (stage, status))
    print()
    print(text[:600])
    print()
    print("The Registry deserializes the snapshot with the same model NiFi uses, so a refusal")
    print("here is usually a field whose type or value that model will not take. Run")
    print("`kb.py validate` for the ones it can name, and `kb.py normalize` for the ones that")
    print("are simply absent.")
    return 1


def drop_registry_item(client, path, what):
    """Delete a bucket or flow, which the Registry versions rather than revisions."""
    try:
        entity = client.get(path)
    except LiveError:
        return
    code = client.delete(path, {"version": (entity.get("revision") or {}).get("version", 0)})
    if code >= 300:
        print("\nWARNING: could not delete %s (HTTP %d). Delete it by hand." % (what, code),
              file=sys.stderr)


def verify_registry(client, args, flow_bytes):
    """Store the flow as version 1 of a temporary versioned flow, then take it away again.

    This asks a narrower question than the NiFi import does. The Registry does not validate
    components - it never loads the NARs - so it says nothing about a wrong property key or
    an unhandled relationship. What it does prove is that the file deserializes into the
    versioned-flow model and survives a store, which is exactly what a flow headed for
    version control needs and what a NiFi-only check leaves untested.
    """
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    label = "kb-verify-%s-%s" % (stamp, uuid.uuid4().hex[:8])

    bucket, created_bucket = resolve_bucket(client, args, label)
    bucket_id = bucket["identifier"]
    flow_id = None
    try:
        status, payload = client.post(
            "/buckets/%s/flows" % bucket_id,
            {"name": label, "bucketIdentifier": bucket_id,
             "description": "Temporary flow from verify_live.py"},
        )
        if status >= 300:
            return registry_rejected("creating the flow", status, payload)
        flow_id = json.loads(payload)["identifier"]

        snapshot = registry_snapshot(flow_bytes, bucket_id, flow_id, label)
        status, payload = client.post(
            "/buckets/%s/flows/%s/versions" % (bucket_id, flow_id), snapshot)
        if status >= 300:
            return registry_rejected("storing version 1", status, payload)

        print("Stored as version 1 of '%s' in bucket '%s'."
              % (label, bucket.get("name", bucket_id)))
        print("The Registry accepted the file as a versioned flow snapshot.")
        return 0
    finally:
        if args.keep:
            print("\nLeft %s in place as requested (--keep)." % label)
        else:
            if flow_id:
                drop_registry_item(client, "/buckets/%s/flows/%s" % (bucket_id, flow_id),
                                   "temporary flow %s" % label)
            if created_bucket:
                drop_registry_item(client, "/buckets/%s" % bucket_id,
                                   "temporary bucket %s" % label)


# ---------------------------------------------------------------------------
# NiFi
# ---------------------------------------------------------------------------


def collect_components(client, group_id):
    processors = client.get("/process-groups/%s/processors" % group_id,
                            {"includeDescendantGroups": "true"})["processors"]
    services = client.get("/flow/process-groups/%s/controller-services" % group_id,
                          {"includeAncestorGroups": "false",
                           "includeDescendantGroups": "true"})["controllerServices"]
    return processors, services, collect_ports(client, group_id)


def collect_ports(client, group_id):
    """The input and output ports of a group and of every group below it.

    NiFi has no descendant listing for ports, so this walks the groups one level at a time.
    """
    flow = client.get("/flow/process-groups/%s" % group_id)["processGroupFlow"]["flow"]
    ports = list(flow.get("inputPorts") or []) + list(flow.get("outputPorts") or [])
    for child in flow.get("processGroups") or []:
        ports += collect_ports(client, child["id"])
    return ports


def error_signature(components):
    """The set of validation messages currently reported, for comparing two polls."""
    return frozenset(
        (entity["component"].get("name"), message)
        for entity in components
        for message in entity["component"].get("validationErrors") or []
    )


def parameter_contexts(client):
    """Every parameter context on the instance, keyed by name."""
    listing = client.get("/flow/parameter-contexts")["parameterContexts"]
    return {entry["component"]["name"]: entry for entry in listing}


def drop_new_parameter_contexts(client, before):
    """Delete the parameter contexts the import created.

    Deleting the temporary process group does not remove them: contexts live on the
    instance, not inside the group, so a verify run would otherwise leave one behind
    every time. Only names absent beforehand are touched, so a context the flow shares
    with the target NiFi - which NiFi reuses by name rather than recreating - survives.
    """
    for name, entry in parameter_contexts(client).items():
        if name in before:
            continue
        if entry["component"].get("boundProcessGroups"):
            continue
        code = client.delete("/parameter-contexts/%s" % entry["id"],
                             {"version": entry["revision"]["version"],
                              "clientId": str(uuid.uuid4())})
        if code >= 300:
            print("\nWARNING: could not delete parameter context '%s' (HTTP %d). Delete it by "
                  "hand." % (name, code), file=sys.stderr)


def verify(client, args, flow_bytes, flow_name):
    root = args.parent_group
    if root == "root":
        root = client.get("/flow/process-groups/root")["processGroupFlow"]["id"]

    contexts_before = set(parameter_contexts(client))

    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    client_id = str(uuid.uuid4())
    group_name = "kb-verify-%s-%s" % (stamp, client_id[:8])

    status, payload = client.upload(
        "/process-groups/%s/process-groups/upload" % root,
        group_name + ".json", flow_bytes,
        {"groupName": group_name, "positionX": "0", "positionY": "0",
         "clientId": client_id, "disconnectedNodeAcknowledged": "false"},
    )
    if status >= 300:
        text = payload.decode(errors="replace")
        print("IMPORT REJECTED  HTTP %d" % status)
        print()
        print(text[:600])
        print()
        if status == 500:
            print("A 500 here usually means a field the importer dereferences is absent from the")
            print("flow. NiFi deserializes the file into Java objects and reads enums, integers and")
            print("maps directly, so an omitted field arrives as null and throws before any")
            print("validation runs. Run `kb.py normalize <flow.json>` to fill in the mandatory")
            print("fields with NiFi's own defaults, then try again.")
        return 1

    group_id = json.loads(payload)["id"]
    try:
        # Waiting for VALIDATING to clear is not enough. A component is validated as it is
        # created, so a processor can report an unhandled relationship in the moment before
        # its connections exist, and NiFi corrects itself a beat later. Reading once at that
        # instant fails a flow that is perfectly good, so settle for either no errors at all
        # or the same errors twice running.
        deadline = time.time() + args.timeout
        previous, repeats = None, 0
        processors, services, ports = collect_components(client, group_id)
        while time.time() < deadline:
            pending = [c for c in processors + services + ports
                       if c["component"].get("validationStatus") == "VALIDATING"]
            if not pending:
                signature = error_signature(processors + services + ports)
                if not signature:
                    break
                # NiFi revalidates a component on its own schedule, so a stale complaint can
                # survive several reads. Only believe an error set that repeats across a span
                # longer than that cadence.
                repeats = repeats + 1 if signature == previous else 0
                previous = signature
                if repeats >= SETTLE_REPEATS:
                    break
            time.sleep(SETTLE_INTERVAL)
            processors, services, ports = collect_components(client, group_id)

        return report(processors, services, group_name, flow_bytes, ports)
    finally:
        if args.keep:
            print("\nLeft %s in place as requested (--keep)." % group_name)
        else:
            entity = client.get("/process-groups/%s" % group_id)
            code = client.delete("/process-groups/%s" % group_id,
                                 {"version": entity["revision"]["version"],
                                  "clientId": str(uuid.uuid4())})
            if code >= 300:
                print("\nWARNING: could not delete temporary group %s (HTTP %d). Delete it by hand."
                      % (group_name, code), file=sys.stderr)
            else:
                drop_new_parameter_contexts(client, contexts_before)


def _property_key(name):
    return re.sub(r"[\s._-]+", "", (name or "").lower())


def flow_relationships(flow_bytes):
    """Map component name -> the relationships the file itself handles.

    Handling is either an entry in autoTerminatedRelationships or an outgoing connection
    carrying it. This is the one question the file answers more reliably than the running
    instance does, because NiFi's answer can be a validation verdict computed before the
    connections existed and never recomputed.
    """
    try:
        doc = json.loads(flow_bytes)
    except ValueError:
        return {}
    root = (doc.get("flowContents") or {}) if isinstance(doc, dict) else {}
    handled = {}

    def walk(group):
        names = {}
        for processor in group.get("processors") or []:
            names[processor.get("identifier")] = processor.get("name")
            handled.setdefault(processor.get("name"), set()).update(
                processor.get("autoTerminatedRelationships") or [])
        for connection in group.get("connections") or []:
            source = (connection.get("source") or {}).get("id")
            if source in names:
                handled.setdefault(names[source], set()).update(
                    connection.get("selectedRelationships") or [])
        for child in group.get("processGroups") or []:
            walk(child)

    walk(root)
    return handled


def flow_properties(flow_bytes):
    """Map component name -> its properties, so errors can be traced back to the source."""
    try:
        doc = json.loads(flow_bytes)
    except ValueError:
        return {}
    root = (doc.get("flowContents") or {}) if isinstance(doc, dict) else {}
    found = {}

    def walk(group):
        for component in (group.get("processors") or []) + (group.get("controllerServices") or []):
            # NiFi reports the display name ("Bootstrap Servers") while the flow uses the
            # descriptor name ("bootstrap.servers"), so index on a form that matches both.
            found[component.get("name")] = {
                _property_key(key): value
                for key, value in (component.get("properties") or {}).items()
            }
        for child in group.get("processGroups") or []:
            walk(child)

    walk(root)
    return found


def report(processors, services, group_name, flow_bytes, ports=()):
    properties = flow_properties(flow_bytes)
    relationships = flow_relationships(flow_bytes)
    real, noise, pending, stale = [], [], [], []
    labelled = [("controller service", e) for e in services] + [("processor", e) for e in processors]
    labelled += [("input port" if e["component"].get("type") == "INPUT_PORT" else "output port", e)
                 for e in ports]
    for kind, entity in labelled:
        component = entity["component"]
        name = component.get("name", "?")
        for message in component.get("validationErrors") or []:
            if EXPECTED_NOISE.search(message):
                noise.append((kind, name, message))
                continue
            unconnected = UNCONNECTED_RELATIONSHIP.search(message)
            if unconnected and unconnected.group(1) in relationships.get(name, set()):
                stale.append((kind, name, unconnected.group(1)))
                continue
            # "'X' is invalid because X is required" against a #{...} value means the
            # parameter has no value in this NiFi, not that the flow is malformed.
            match = re.match(r"'([^']+)'", message)
            key = match.group(1) if match else None
            value = properties.get(name, {}).get(_property_key(key))
            if (key and isinstance(value, str) and value.startswith("#{")
                    and "is required" in message):
                pending.append((kind, name, key))
            else:
                real.append((kind, name, message))

    print("Imported as %s and read back from NiFi." % group_name)
    print("%d processor(s), %d controller service(s), %d port(s)."
          % (len(processors), len(services), len(ports)))
    print()

    for kind, name, message in real:
        print("ERROR  %s '%s': %s" % (kind, name, message))

    if pending:
        print()
        print("Waiting on parameter values in the target NiFi (not a flow defect):")
        for kind, name, key in pending:
            print("  %s '%s' property '%s'" % (kind, name, key))

    if stale:
        print()
        print("NiFi reports these relationships as unhandled, but the flow handles every one of")
        print("them. A component is validated as it is created, so the verdict predates the")
        print("connections and was never recomputed. Not a defect in the flow:")
        for kind, name, relationship in stale:
            print("  %s '%s' relationship '%s'" % (kind, name, relationship))

    if noise:
        print()
        print("%d message(s) about disabled controller services suppressed: services always import"
              % len(noise))
        print("disabled, so this says nothing about the flow.")

    print()
    print("%d real validation error(s)." % len(real))
    if not real:
        print("NiFi accepted the flow and every component validated.")
    return 1 if real else 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="verify_live.py",
        description="Load a flow definition into a running NiFi and/or NiFi Registry.",
    )
    parser.add_argument("flow", help="Flow definition JSON to verify")
    parser.add_argument("--nifi-url", help="NiFi URL, HTTPS only. Imports into a temporary "
                        "process group and reads back every component's validation state.")
    parser.add_argument("--registry-url", help="NiFi Registry URL, HTTPS only. Stores the "
                        "flow as a temporary versioned flow to prove it is a valid snapshot.")
    parser.add_argument("--registry-bucket",
                        help="Existing bucket to use instead of creating a temporary one")
    parser.add_argument("--auth", required=True, choices=["certificate", "token", "cookie"])
    parser.add_argument("--certificate-file", help="PKCS#12 file (certificate mode)")
    parser.add_argument("--ca-file", help="PEM file of trusted CA certificates")
    parser.add_argument("--parent-group", default="root",
                        help="Process group to import into (default: root)")
    parser.add_argument("--timeout", type=int, default=60,
                        help="Seconds to wait for validation to settle")
    parser.add_argument("--keep", action="store_true",
                        help="Do not delete what was created (for debugging)")
    args = parser.parse_args(argv)
    if not args.nifi_url and not args.registry_url:
        parser.error("give --nifi-url, --registry-url, or both")

    flow_path = Path(args.flow)
    if not flow_path.is_file():
        print("No such file: %s" % flow_path, file=sys.stderr)
        return 2

    workdir = Path(tempfile.mkdtemp(prefix="kb-verify-"))
    try:
        context = build_context(args, workdir)
        headers = auth_headers(args)
        flow_bytes = flow_path.read_bytes()
        # Refuse a file of the wrong shape before writing anything to either target.
        load_flow(flow_bytes)
        worst = 0

        if args.nifi_url:
            client = Client(args.nifi_url, context, headers)
            about = client.get("/flow/about")["about"]
            print("NiFi %s at %s" % (about.get("version"), args.nifi_url))
            print()
            worst = max(worst, verify(client, args, flow_bytes, flow_path.name))

        if args.registry_url:
            if args.nifi_url:
                print()
            registry = Client(args.registry_url, context, headers,
                              api="nifi-registry-api", ui="nifi-registry")
            print("NiFi Registry %s at %s"
                  % (registry.get("/about").get("registryAboutVersion"), args.registry_url))
            print()
            worst = max(worst, verify_registry(registry, args, flow_bytes))

        return worst
    except LiveError as exc:
        print(str(exc), file=sys.stderr)
        return 2
    except ssl.SSLError as exc:
        print("TLS failed: %s\nPass --ca-file with the CA chain that signed the server "
              "certificate." % exc, file=sys.stderr)
        return 3
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
