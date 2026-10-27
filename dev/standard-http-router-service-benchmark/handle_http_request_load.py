#!/usr/bin/env python3
"""Sends requests to the 20 HTTP chains of a NiFi test group.

Two groups are supported:
  - "HandleHttpRequest test": each chain has its own HandleHttpRequest on its own port
    (11001-11020 by default). This is the default mode.
  - "HandleRoutedHttpRequest test": one StandardHttpRouterService on one port routes the requests
    to 20 HandleRoutedHttpRequest processors by path (/chain01-/chain20). Enabled by --router-port.

In both modes, every interval one POST request goes to each of the chains, in parallel, so the number of
requests per interval is the same. The body is JSON with "status": "new"; ReplaceText in the chain rewrites
it to "status": "processed by chain N", and the script checks that N matches the chain it called.
Each request is logged as a CSV row: chain, URL, send time, response time, duration in ms, response code,
and the result of that check.

Usage:
    python handle_http_request_load.py <interval_ms> <duration_min> [--router-port P] [--host H]
                                       [--first-port P] [--count N] [--timeout S] [--payload-size N]
                                       [--log FILE]
"""
import argparse
import csv
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from pathlib import Path


RESULTS_DIR = Path(__file__).resolve().parent / "results"


def now_str():
    return datetime.now().isoformat(timespec="milliseconds")


def send(url, chain, payload, timeout, writer, lock):
    body = json.dumps({"chain": chain, "status": "new", "payload": payload}).encode("utf-8")
    expected = f'"status": "processed by chain {chain}"'
    req = urllib.request.Request(url, data=body, method="POST", headers={"Content-Type": "application/json"})
    check = ""
    sent_at = now_str()
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            answer = resp.read().decode("utf-8", errors="replace")
            code = str(resp.status)
        check = "ok" if expected in answer else "mismatch"
    except urllib.error.HTTPError as e:
        code = str(e.code)
    except Exception as e:  # timeout, connection refused, reset
        code = "ERROR " + type(e).__name__ + ": " + str(e)
    duration_ms = (time.perf_counter() - start) * 1000
    received_at = now_str()
    row = [chain, url, sent_at, received_at, f"{duration_ms:.1f}", code, check]
    with lock:
        writer.writerow(row)
        print(";".join(map(str, row)), flush=True)


def main():
    parser = argparse.ArgumentParser(description="Load test for the HTTP chains of a NiFi test group.")
    parser.add_argument("interval_ms", type=int, help="interval between request batches, in milliseconds")
    parser.add_argument("duration_min", type=float, help="total run time, in minutes")
    parser.add_argument("--router-port", type=int,
                        help="port of StandardHttpRouterService; when set, chain N is called at "
                             "http://HOST:ROUTER_PORT/chainNN instead of its own port")
    # Not "localhost": on Windows it resolves to ::1 first, and Docker publishes the ports on 127.0.0.1 only,
    # so every request waits about 2 s for the IPv6 attempt to fail.
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--first-port", type=int, default=11001,
                        help="port of chain 1 without --router-port; chain N listens on FIRST_PORT + N - 1")
    parser.add_argument("--count", type=int, default=20, help="number of chains")
    parser.add_argument("--timeout", type=float, default=70.0,
                        help="response timeout, in seconds (StandardHttpContextMap expires requests after 1 min)")
    parser.add_argument("--payload-size", type=int, default=1024,
                        help="length of the payload text in the request body, in characters")
    parser.add_argument("--log", help="CSV log file (default: results/ next to this script)")
    args = parser.parse_args()
    if args.interval_ms <= 0 or args.duration_min <= 0:
        parser.error("interval_ms and duration_min must be positive")
    if args.payload_size < 0:
        parser.error("--payload-size must not be negative")
    payload = "x" * args.payload_size

    # UpdateAttribute of chain N sets test.chain = N.
    chains = list(range(1, args.count + 1))
    if args.router_port:
        mode = "router"
        urls = {n: f"http://{args.host}:{args.router_port}/chain{n:02d}" for n in chains}
    else:
        mode = "ports"
        urls = {n: f"http://{args.host}:{args.first_port + n - 1}/" for n in chains}
    log = args.log or str(RESULTS_DIR / f"http_load_{mode}_{args.interval_ms}ms_{datetime.now():%Y%m%d_%H%M%S}.csv")

    # Enough workers that slow responses do not delay the next batches.
    batches_in_flight = max(1, int(args.timeout * 1000 / args.interval_ms) + 1)
    workers = min(args.count * batches_in_flight, 500)

    Path(log).parent.mkdir(parents=True, exist_ok=True)
    lock = threading.Lock()
    with open(log, "w", newline="", encoding="utf-8") as f, ThreadPoolExecutor(max_workers=workers) as pool:
        writer = csv.writer(f, delimiter=";")
        header = ["chain", "url", "sent_at", "received_at", "duration_ms", "response_code", "check"]
        writer.writerow(header)
        print(";".join(header), flush=True)

        print(f"Started at {now_str()}: mode {mode}, interval {args.interval_ms} ms, duration {args.duration_min} min, "
              f"{args.count} requests per interval, {urls[chains[0]]} ... {urls[chains[-1]]}",
              file=sys.stderr, flush=True)
        interval = args.interval_ms / 1000
        end = time.monotonic() + args.duration_min * 60
        next_tick = time.monotonic()
        try:
            while next_tick < end:
                for n in chains:
                    pool.submit(send, urls[n], n, payload, args.timeout, writer, lock)
                next_tick += interval
                delay = next_tick - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
        except KeyboardInterrupt:
            print("Interrupted, waiting for requests in flight...", file=sys.stderr)
        print(f"Sending stopped at {now_str()}, waiting for requests in flight", file=sys.stderr, flush=True)
        # Leaving the "with" block waits for the requests already sent.
    print(f"Finished at {now_str()}", file=sys.stderr)
    print(f"Log written to {log}", file=sys.stderr)


if __name__ == "__main__":
    main()
