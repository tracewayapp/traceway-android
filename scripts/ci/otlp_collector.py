"""A stand-in OTLP/HTTP collector for CI: answers every POST with 200 and writes one JSON line per request.

  otlp_collector.py serve --port 18199 --ledger out/collector.jsonl
  otlp_collector.py wait --ledger out/collector.jsonl --paths /api/otel/v1/logs,... --timeout 120
"""

import argparse
import json
import os
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def serve(port, ledger):
    lock = threading.Lock()
    os.makedirs(os.path.dirname(os.path.abspath(ledger)), exist_ok=True)

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
            line = {
                "time": time.time(),
                "path": self.path,
                "bytes": len(body),
                "contentType": self.headers.get("Content-Type"),
                "contentEncoding": self.headers.get("Content-Encoding"),
            }
            with lock, open(ledger, "a", encoding="utf-8") as f:
                f.write(json.dumps(line) + "\n")
            self.send_response(200)
            self.send_header("Content-Type", "application/x-protobuf")
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self):
            self.send_response(200)
            self.send_header("Content-Length", "2")
            self.end_headers()
            self.wfile.write(b"ok")

        def log_message(self, *args):
            pass

    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


def read(ledger):
    if not os.path.exists(ledger):
        return []
    with open(ledger, encoding="utf-8") as f:
        return [json.loads(l) for l in f if l.strip()]


def wait(ledger, paths, timeout):
    deadline = time.time() + timeout
    while True:
        seen = {}
        for r in read(ledger):
            if r["bytes"] > 0:
                seen[r["path"]] = seen.get(r["path"], 0) + 1
        missing = [p for p in paths if p not in seen]
        if not missing or time.time() > deadline:
            for p in paths:
                print(f"{'ok  ' if p in seen else 'FAIL'} {p}: {seen.get(p, 0)} request(s)")
            for p in sorted(set(seen) - set(paths)):
                print(f"     {p}: {seen[p]} request(s)")
            return 1 if missing else 0
        time.sleep(2)


def main():
    p = argparse.ArgumentParser()
    sub = p.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("serve")
    s.add_argument("--port", type=int, default=18199)
    s.add_argument("--ledger", required=True)
    w = sub.add_parser("wait")
    w.add_argument("--ledger", required=True)
    w.add_argument("--paths", required=True)
    w.add_argument("--timeout", type=float, default=120)
    a = p.parse_args()
    if a.cmd == "serve":
        serve(a.port, a.ledger)
    else:
        sys.exit(wait(a.ledger, [x for x in a.paths.split(",") if x], a.timeout))


if __name__ == "__main__":
    main()
