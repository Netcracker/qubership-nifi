#!/usr/bin/env python3
"""Collect the external APIs that NiFi flows expose and call.

The script reads the top-level process groups of a NiFi 1.x or 2.x instance, downloads each one as flow JSON into a
temporary directory under the working directory, and finds the REST, Kafka, S3, and RabbitMQ processors in it.
Processors that read data (consumers, listeners, S3 reads) are inbound; processors that write data are outbound.
The report is written to the working directory as Markdown and as JSON.

Authentication is either a PKCS#12 client certificate, whose password is read from NIFI_PKCS12_PASSWORD, or a NiFi
login cookie, whose value is read from NIFI_AUTHORIZATION_BEARER_COOKIE. The script sends GET requests only.
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
import urllib.parse
from datetime import datetime
from pathlib import Path

INBOUND = "inbound"
OUTBOUND = "outbound"

REST = "REST"
KAFKA = "Kafka"
S3 = "S3"
RABBITMQ = "RabbitMQ"


class CollectorError(Exception):
    """A problem the caller can act on, reported without a traceback."""


# ---------------------------------------------------------------------------
# TLS and transport (adapted from the nifi-flow-builder skill, scripts/verify_live.py)
# ---------------------------------------------------------------------------


def unpack_pkcs12(path, password, workdir):
    """Split a PKCS#12 file into the PEM cert and key files an SSLContext needs.

    The certificate file holds the client certificate first and then the CA chain from the
    PKCS#12 file, so a certificate issued by an intermediate CA completes the handshake.
    """
    cert_pem = workdir / "client-cert.pem"
    key_pem = workdir / "client-key.pem"
    try:
        from cryptography.hazmat.primitives.serialization import (
            Encoding, NoEncryption, PrivateFormat, pkcs12,
        )
    except ImportError:
        return _unpack_pkcs12_with_openssl(path, password, cert_pem, key_pem)

    try:
        key, cert, chain = pkcs12.load_key_and_certificates(path.read_bytes(), password.encode())
    except (ValueError, OSError) as exc:
        raise CollectorError("Cannot read the PKCS#12 file %s: %s. Check the path and the password."
                             % (path, exc))
    if key is None or cert is None:
        raise CollectorError("%s has no private key entry with a certificate." % path)
    body = cert.public_bytes(Encoding.PEM)
    for extra in chain or []:
        body += extra.public_bytes(Encoding.PEM)
    cert_pem.write_bytes(body)
    key_pem.write_bytes(key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
    return cert_pem, key_pem


def _unpack_pkcs12_with_openssl(path, password, cert_pem, key_pem):
    if shutil.which("openssl") is None:
        raise CollectorError(
            "Reading a PKCS#12 file needs either the 'cryptography' package or the 'openssl' "
            "command. Install one, or use --auth cookie."
        )
    env = dict(os.environ, NIFIPW=password)
    extracted = []
    for args in (["-clcerts", "-nokeys"], ["-cacerts", "-nokeys"], ["-nocerts", "-nodes"]):
        command = ["openssl", "pkcs12", "-in", str(path), "-passin", "env:NIFIPW"] + args
        result = subprocess.run(command, capture_output=True, env=env)
        if result.returncode != 0:
            raise CollectorError("openssl could not read %s: %s"
                                 % (path, result.stderr.decode(errors="replace").strip()[:200]))
        extracted.append(result.stdout)
    cert_pem.write_bytes(extracted[0] + extracted[1])
    key_pem.write_bytes(extracted[2])
    return cert_pem, key_pem


def build_context(args, workdir):
    context = ssl.create_default_context(cafile=args.ca_cert)
    if args.auth == "cert":
        if not args.client_cert:
            raise CollectorError("--auth cert requires --client-cert <pkcs12-path>.")
        password = os.environ.get("NIFI_PKCS12_PASSWORD")
        if password is None:
            raise CollectorError("Certificate mode reads the PKCS#12 password from NIFI_PKCS12_PASSWORD.")
        cert_pem, key_pem = unpack_pkcs12(Path(args.client_cert), password, workdir)
        context.load_cert_chain(str(cert_pem), str(key_pem))
    return context


def auth_headers(args):
    if args.auth == "cookie":
        cookie = os.environ.get("NIFI_AUTHORIZATION_BEARER_COOKIE")
        if not cookie:
            raise CollectorError("Cookie mode reads the cookie from NIFI_AUTHORIZATION_BEARER_COOKIE.")
        return {"Cookie": "__Secure-Authorization-Bearer=" + cookie}
    return {}


class Client:
    """A minimal read-only NiFi REST client."""

    def __init__(self, base_url, context, headers):
        parsed = urllib.parse.urlparse(base_url)
        if parsed.scheme != "https":
            raise CollectorError("URL must use HTTPS; got '%s'." % base_url)
        self.host = parsed.hostname
        self.port = parsed.port or 443
        self.context = context
        self.headers = headers
        # Accept a URL that already ends in the API or UI path, so pasting either works.
        path = re.sub(r"/(nifi-api|nifi)/?$", "", parsed.path or "")
        self.prefix = path.rstrip("/") + "/nifi-api"

        self._conn = None

    def close(self):
        if self._conn is not None:
            self._conn.close()
            self._conn = None

    def _send(self, url):
        if self._conn is None:
            self._conn = http.client.HTTPSConnection(self.host, self.port, context=self.context, timeout=300)
        try:
            self._conn.request("GET", url, headers=self.headers)
            response = self._conn.getresponse()
            return response, response.read()
        except BaseException:
            self.close()
            raise

    def get_bytes(self, path, params=None):
        """GET a path under the API prefix, reusing one keep-alive connection for every request.

        A server can close an idle keep-alive connection at any time, so a request on a reused
        connection that fails with a dropped connection is sent once more on a new one.
        """
        url = self.prefix + path
        if params:
            url += "?" + urllib.parse.urlencode(params)
        reused = self._conn is not None
        try:
            response, payload = self._send(url)
        except (ConnectionError, ssl.SSLEOFError):
            if not reused:
                raise
            response, payload = self._send(url)
        if response.status >= 300:
            raise CollectorError("GET %s -> HTTP %d: %s"
                                 % (path, response.status, payload[:300].decode(errors="replace")))
        return payload

    def get(self, path, params=None):
        return json.loads(self.get_bytes(path, params))


# ---------------------------------------------------------------------------
# Parameter and variable resolution
# ---------------------------------------------------------------------------

# A parameter reference: #{name}, or #{'name'} with the name quoted. `##{` escapes it.
PARAMETER_REFERENCE = re.compile(r"(?<!#)#\{('[^']*'|[^}]+)\}")

# A bare variable reference such as ${kafka.topic}. Anything with a function call, a quote,
# or a nested expression is not a bare reference and stays as written. `$${` escapes it.
VARIABLE_REFERENCE = re.compile(r"(?<!\$)\$\{\s*([A-Za-z0-9_.\-]+)\s*\}")


def context_parameters(contexts, name, _seen=None):
    """Map each parameter name visible in a context to its value, following inheritance.

    A sensitive parameter maps to None, so its reference stays unresolved. A parameter the
    context declares itself takes precedence over an inherited one.
    """
    _seen = _seen or set()
    if not name or name in _seen or name not in contexts:
        return {}
    _seen.add(name)
    context = contexts[name]
    values = {}
    for parameter in context.get("parameters") or []:
        if parameter.get("name"):
            values[parameter["name"]] = None if parameter.get("sensitive") else parameter.get("value")
    for inherited in context.get("inheritedParameterContexts") or []:
        parent = inherited if isinstance(inherited, str) else inherited.get("name")
        for key, value in context_parameters(contexts, parent, _seen).items():
            values.setdefault(key, value)
    return values


def resolve(value, parameters, variables):
    """Substitute parameter references and, on NiFi 1.x, bare variable references in a value.

    `variables` is None on NiFi 2.x, which has no variable registry. A reference whose value
    is unknown or sensitive is kept as written.
    """
    if not isinstance(value, str):
        return value

    def parameter(match):
        name = match.group(1)
        if name.startswith("'") and name.endswith("'"):
            name = name[1:-1]
        resolved = parameters.get(name)
        return match.group(0) if resolved is None else resolved

    value = PARAMETER_REFERENCE.sub(parameter, value)
    if variables is not None:
        value = VARIABLE_REFERENCE.sub(
            lambda match: variables.get(match.group(1), match.group(0)), value)
    return value


# ---------------------------------------------------------------------------
# Processor table and extractors
# ---------------------------------------------------------------------------


class Properties:
    """Resolved property lookup for one processor, with candidate keys for 1.x and 2.x names."""

    def __init__(self, raw, parameters, variables):
        self.raw = raw or {}
        self.parameters = parameters
        self.variables = variables

    def get(self, *keys):
        """The resolved value of the first key that has a non-empty value, or None."""
        for key in keys:
            value = self.raw.get(key)
            if value not in (None, ""):
                return resolve(value, self.parameters, self.variables)
        return None


def _drop_empty(info):
    return {key: value for key, value in info.items() if value not in (None, "", [])}


# An absolute URL: the scheme and the authority, then the path with any query or fragment.
# The authority stops at the first slash, so a host written as ${host} or #{host} still matches.
ABSOLUTE_URL = re.compile(r"^[A-Za-z][A-Za-z0-9+.\-]*://[^/]*(.*)$")

# The start of a query or a fragment. A `#` that opens a parameter reference does not count.
QUERY_OR_FRAGMENT = re.compile(r"\?|#(?!\{)")


def url_path(url):
    """The path part of a URL, without the query and the fragment.

    An absolute URL with no path has the path "/". Returns None when the value has no scheme,
    for example a URL built entirely by Expression Language, so the path cannot be told apart.
    """
    if not url:
        return None
    match = ABSOLUTE_URL.match(url)
    if match is None:
        return None
    return QUERY_OR_FRAGMENT.split(match.group(1), 1)[0] or "/"


def _rest_client(url, method):
    return {"url": url, "path": url_path(url), "method": [method] if method else []}


def _rest_invoke(props, services):
    return _rest_client(props.get("HTTP URL", "Remote URL"), props.get("HTTP Method"))


def _rest_legacy_client(method):
    def extract(props, services):
        return _rest_client(props.get("URL"), method)
    return extract


HANDLE_REQUEST_METHODS = ("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS")


def _rest_handle_request(props, services):
    methods = [name for name in HANDLE_REQUEST_METHODS
               if (props.get("Allow " + name) or "").lower() == "true"]
    extra = props.get("Additional HTTP Methods")
    if extra:
        methods.extend(item.strip() for item in extra.split(",") if item.strip())
    path = props.get("Allowed Paths")
    return {"url": path, "path": path, "method": methods,
            "port": props.get("Listening Port"), "hostname": props.get("Hostname")}


def _rest_listen(props, services):
    # ListenHTTP accepts POST for data and also answers HEAD and GET for health checks.
    path = props.get("Base Path")
    return {"url": path, "path": path, "method": ["POST"], "port": props.get("Listening Port")}


def _kafka(consumer):
    def extract(props, services):
        info = {
            "topics": props.get("Topics", "Topic Name", "topic"),
            "topicType": props.get("Topic Format", "topic_type") if consumer else None,
            "groupId": props.get("Group ID", "group.id") if consumer else None,
            "bootstrapServers": props.get("bootstrap.servers"),
        }
        if not info["bootstrapServers"]:
            # NiFi 2.x ConsumeKafka and PublishKafka take the brokers from a connection service.
            service = services.get(props.get("Kafka Connection Service") or "")
            if service is not None:
                info["bootstrapServers"] = service.get("bootstrap.servers")
        return info
    return extract


def _s3(key_names):
    def extract(props, services):
        region = props.get("Region")
        if region == "use-custom-region":
            region = props.get("Custom Region") or region
        return {"bucket": props.get("Bucket", "Source Bucket"), key_names[0]: props.get(*key_names[1:]),
                "region": region, "endpointOverride": props.get("Endpoint Override URL")}
    return extract


def _s3_copy(props, services):
    info = _s3(("key", "Source Key"))(props, services)
    info["destinationBucket"] = props.get("Destination Bucket")
    info["destinationKey"] = props.get("Destination Key")
    return info


def _amqp(consumer):
    def extract(props, services):
        info = {"brokers": props.get("Brokers"), "host": props.get("Host Name"), "port": props.get("Port"),
                "virtualHost": props.get("Virtual Host")}
        if consumer:
            info["queue"] = props.get("Queue")
        else:
            info["exchange"] = props.get("Exchange Name")
            info["routingKey"] = props.get("Routing Key")
        return info
    return extract


_S3_OBJECT = _s3(("key", "Object Key"))

# Simple type name -> (API type, direction, extractor). The names cover NiFi 1.x and 2.x.
PROCESSORS = {
    "HandleHttpRequest": (REST, INBOUND, _rest_handle_request),
    "ListenHTTP": (REST, INBOUND, _rest_listen),
    "GetHTTP": (REST, INBOUND, _rest_legacy_client("GET")),
    "InvokeHTTP": (REST, OUTBOUND, _rest_invoke),
    "PostHTTP": (REST, OUTBOUND, _rest_legacy_client("POST")),
    "ListS3": (S3, INBOUND, _s3(("prefix", "Prefix", "prefix"))),
    "FetchS3Object": (S3, INBOUND, _S3_OBJECT),
    "GetS3ObjectMetadata": (S3, INBOUND, _S3_OBJECT),
    "GetS3ObjectTags": (S3, INBOUND, _S3_OBJECT),
    "PutS3Object": (S3, OUTBOUND, _S3_OBJECT),
    "DeleteS3Object": (S3, OUTBOUND, _S3_OBJECT),
    "TagS3Object": (S3, OUTBOUND, _S3_OBJECT),
    "CopyS3Object": (S3, OUTBOUND, _s3_copy),
    "ConsumeAMQP": (RABBITMQ, INBOUND, _amqp(consumer=True)),
    "PublishAMQP": (RABBITMQ, OUTBOUND, _amqp(consumer=False)),
}
for _suffix in ("", "_1_0", "_2_0", "_2_6"):
    PROCESSORS["ConsumeKafka" + _suffix] = (KAFKA, INBOUND, _kafka(consumer=True))
    PROCESSORS["ConsumeKafkaRecord" + _suffix] = (KAFKA, INBOUND, _kafka(consumer=True))
    PROCESSORS["PublishKafka" + _suffix] = (KAFKA, OUTBOUND, _kafka(consumer=False))
    PROCESSORS["PublishKafkaRecord" + _suffix] = (KAFKA, OUTBOUND, _kafka(consumer=False))


def simple_name(type_name):
    return (type_name or "").rsplit(".", 1)[-1]


# ---------------------------------------------------------------------------
# Flow analysis
# ---------------------------------------------------------------------------


def _component_id(component):
    """The id of the component on the NiFi instance; a download keeps it in instanceIdentifier."""
    return component.get("instanceIdentifier") or component.get("identifier")


def _collect_services(group, parameters_of, variables_of, services=None):
    """Resolved properties of every controller service in the download, keyed by its identifier."""
    services = {} if services is None else services
    for service in group.get("controllerServices") or []:
        props = Properties(service.get("properties"), parameters_of(group), variables_of(group))
        services[service.get("identifier")] = {key: props.get(key) for key in props.raw}
    for child in group.get("processGroups") or []:
        _collect_services(child, parameters_of, variables_of, services)
    return services


def analyze_flow(flow, root_variables=None):
    """Find the inbound and outbound APIs in one downloaded process group.

    `root_variables` is the variable registry of the root group on NiFi 1.x, or None on NiFi
    2.x. Returns the entry for the report, without the pgId and pgName keys.
    """
    contexts = flow.get("parameterContexts") or {}
    is_1x = root_variables is not None
    group_parameters = {}
    group_variables = {}

    def index(group, inherited_variables):
        group_parameters[id(group)] = context_parameters(contexts, group.get("parameterContextName"))
        if is_1x:
            scope = dict(inherited_variables)
            scope.update(group.get("variables") or {})
            group_variables[id(group)] = scope
        for child in group.get("processGroups") or []:
            index(child, group_variables.get(id(group)))

    top = flow["flowContents"]
    index(top, root_variables or {})

    def parameters_of(group):
        return group_parameters[id(group)]

    def variables_of(group):
        return group_variables.get(id(group)) if is_1x else None

    services = _collect_services(top, parameters_of, variables_of)
    result = {INBOUND: [], OUTBOUND: []}

    def walk(group, path):
        for processor in group.get("processors") or []:
            entry = PROCESSORS.get(simple_name(processor.get("type")))
            if entry is None:
                continue
            api_type, direction, extract = entry
            props = Properties(processor.get("properties"), parameters_of(group), variables_of(group))
            result[direction].append({
                "type": api_type,
                "processorId": _component_id(processor),
                "processorName": processor.get("name"),
                "state": processor.get("scheduledState"),
                "processorPath": list(path),
                "additionalInfo": _drop_empty(extract(props, services)),
            })
        for child in group.get("processGroups") or []:
            walk(child, path + [{"name": child.get("name"), "id": _component_id(child)}])

    walk(top, [])
    return result


# ---------------------------------------------------------------------------
# Report output
# ---------------------------------------------------------------------------


def _field(name):
    return lambda info: info.get(name)


def _methods(info):
    return ", ".join(info.get("method") or [])


def _with_destination(source, destination):
    def value(info):
        if info.get(destination):
            return "%s -> %s" % (info.get(source) or "", info[destination])
        return info.get(source)
    return value


def _s3_location(info):
    return _with_destination("key", "destinationKey")(info) or info.get("prefix")


def _amqp_broker(info):
    if info.get("brokers"):
        return info["brokers"]
    if info.get("host") and info.get("port"):
        return "%s:%s" % (info["host"], info["port"])
    return info.get("host")


_S3_COLUMNS = [("Bucket", _with_destination("bucket", "destinationBucket")), ("Key or prefix", _s3_location),
               ("Region", _field("region")), ("Endpoint", _field("endpointOverride"))]
_AMQP_SERVER_COLUMNS = [("Broker", _amqp_broker), ("Virtual host", _field("virtualHost"))]

# The API columns of each Markdown table, keyed by API type and direction. Every table also ends
# with the processor identifier and the processor path.
MARKDOWN_COLUMNS = {
    (REST, OUTBOUND): [("Method", _methods), ("URL", _field("url")), ("Path", _field("path"))],
    (REST, INBOUND): [("Method", _methods), ("Path", _field("path")), ("Port", _field("port"))],
    (KAFKA, OUTBOUND): [("Topics", _field("topics")), ("Bootstrap servers", _field("bootstrapServers"))],
    (KAFKA, INBOUND): [("Topics", _field("topics")), ("Topic type", _field("topicType")),
                       ("Group ID", _field("groupId")), ("Bootstrap servers", _field("bootstrapServers"))],
    (S3, OUTBOUND): _S3_COLUMNS,
    (S3, INBOUND): _S3_COLUMNS,
    (RABBITMQ, OUTBOUND): [("Exchange", _field("exchange")), ("Routing key", _field("routingKey"))]
    + _AMQP_SERVER_COLUMNS,
    (RABBITMQ, INBOUND): [("Queue", _field("queue"))] + _AMQP_SERVER_COLUMNS,
}

API_TYPES = (REST, KAFKA, S3, RABBITMQ)


def processor_path(item):
    return "/".join([step["name"] or "" for step in item["processorPath"]] + [item["processorName"] or ""])


def _cell(text):
    return (text or "").replace("\\", "\\\\").replace("|", "\\|").replace("\n", " ")


def _table(api_type, direction, items):
    columns = MARKDOWN_COLUMNS[(api_type, direction)]
    headers = [header for header, _ in columns] + ["Processor identifier", "Processor relative path"]
    lines = ["| %s |" % " | ".join(headers), "|%s" % (" --- |" * len(headers))]
    for item in items:
        cells = [_cell(value(item["additionalInfo"])) for _, value in columns]
        cells += [_cell(item["processorId"]), _cell(processor_path(item))]
        lines.append("| %s |" % " | ".join(cells))
    return lines


def render_markdown(report):
    lines = []
    for group in report:
        lines.append("# %s" % group["pgName"])
        lines.append("")
        for title, direction in (("Outbound", OUTBOUND), ("Inbound", INBOUND)):
            lines.append("## %s" % title)
            lines.append("")
            if not group[direction]:
                lines.append("None found.")
                lines.append("")
                continue
            for api_type in API_TYPES:
                items = [item for item in group[direction] if item["type"] == api_type]
                if items:
                    lines.append("### %s" % api_type)
                    lines.append("")
                    lines.extend(_table(api_type, direction, items))
                    lines.append("")
    if not report:
        lines.append("No process group has inbound or outbound APIs.")
    return "\n".join(lines).rstrip("\n") + "\n"


# ---------------------------------------------------------------------------
# NiFi access
# ---------------------------------------------------------------------------


def root_variable_registry(client):
    """The variables defined on the root group of NiFi 1.x, which a group download leaves out."""
    registry = client.get("/process-groups/root/variable-registry").get("variableRegistry") or {}
    return {item["variable"]["name"]: item["variable"].get("value")
            for item in registry.get("variables") or [] if item.get("variable")}


def top_level_groups(client):
    flow = client.get("/flow/process-groups/root")["processGroupFlow"]["flow"]
    return [(group["id"], group["component"]["name"]) for group in flow.get("processGroups") or []]


def _safe_file_name(text):
    return re.sub(r"[^A-Za-z0-9._-]+", "_", text).strip("_") or "group"


def collect(client, download_dir):
    version = client.get("/flow/about")["about"].get("version") or ""
    print("NiFi %s" % version)
    root_variables = root_variable_registry(client) if version.startswith("1.") else None
    report = []
    for group_id, group_name in top_level_groups(client):
        print("Downloading %s (%s)" % (group_name, group_id))
        payload = client.get_bytes("/process-groups/%s/download" % group_id,
                                   {"includeReferencedServices": "true"})
        target = download_dir / ("%s-%s.json" % (_safe_file_name(group_name), group_id))
        target.write_bytes(payload)
        flow = json.loads(payload)
        apis = analyze_flow(flow, root_variables)
        if not apis[INBOUND] and not apis[OUTBOUND]:
            print("No inbound or outbound APIs in %s (%s); left out of the report" % (group_name, group_id))
            continue
        entry = {"pgId": group_id, "pgName": group_name}
        entry.update(apis)
        report.append(entry)
    return report


# ---------------------------------------------------------------------------
# Command line
# ---------------------------------------------------------------------------


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description="Collect the inbound and outbound APIs used by the flows on a NiFi instance.")
    parser.add_argument("--url", required=True, help="NiFi base URL, for example https://localhost:8443")
    parser.add_argument("--auth", required=True, choices=["cert", "cookie"],
                        help="cert: PKCS#12 client certificate; cookie: NiFi login cookie")
    parser.add_argument("--ca-cert", required=True, help="PEM file with the CA that signed the NiFi certificate")
    parser.add_argument("--client-cert", help="PKCS#12 client certificate, required with --auth cert")
    parser.add_argument("--output-prefix", default="api-usage",
                        help="Report file name without the extension (default: api-usage)")
    parser.add_argument("--keep-temp", action="store_true",
                        help="Keep the downloaded flow JSON files instead of deleting them")
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    download_dir = Path.cwd() / ("api-usage-tmp-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    download_dir.mkdir()
    try:
        with tempfile.TemporaryDirectory() as tls_dir:
            client = Client(args.url, build_context(args, Path(tls_dir)), auth_headers(args))
            try:
                report = collect(client, download_dir)
            finally:
                client.close()
        Path(args.output_prefix + ".json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        Path(args.output_prefix + ".md").write_text(render_markdown(report), encoding="utf-8")
        print("Wrote %s.md and %s.json" % (args.output_prefix, args.output_prefix))
    except (CollectorError, OSError, ssl.SSLError, http.client.HTTPException) as exc:
        print("Error: %s" % exc, file=sys.stderr)
        return 1
    finally:
        if args.keep_temp:
            print("Downloaded flows are in %s" % download_dir)
        else:
            shutil.rmtree(download_dir, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
