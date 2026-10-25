#!/usr/bin/env python3
"""F-215 / SF-215-06 — Imite, en local et pour les essais seulement, les routes de la gateway qui servent la chaîne vidéo.

    python3 tools/video-toolchain/serve-local.py <dossier out/<version>> <port> <jeton>

Routes (préfixe /api, comme la gateway) :
    GET /api/runner/toolchain/video/<version>/manifest    -> manifest.json (octets exacts)
    GET /api/runner/toolchain/video/<version>/signature   -> manifest.json.sig
    GET /api/runner/toolchain/video/<version>/<platform>  -> <platform>.tar.gz, Range accepté (206 / 416)
Jeton exigé dans X-Runner-Token (401 sinon), comme VideoToolchainController. Chaque requête est journalisée.
Jamais utilisé en production : la vraie route est servie par la gateway depuis le bucket S3.
"""
import os
import re
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ROOT, PORT, TOKEN = sys.argv[1], int(sys.argv[2]), sys.argv[3]
VERSION = os.path.basename(os.path.normpath(ROOT))
PLATFORMS = {"darwin-arm64", "darwin-x64", "linux-x64", "win-x64"}
PREFIX = "/api/runner/toolchain/video/" + VERSION + "/"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        sys.stdout.write("[portail local] " + (fmt % args) + " Range=" + str(self.headers.get("Range")) + "\n")
        sys.stdout.flush()

    def send_plain(self, status, body, content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.headers.get("X-Runner-Token", "").strip() != TOKEN:
            return self.send_plain(401, b'{"error":"Jeton runner refus\xc3\xa9."}')
        if not self.path.startswith(PREFIX):
            return self.send_plain(404, b'{"error":"video_toolchain_unavailable"}')
        name = self.path[len(PREFIX):]
        if name == "manifest":
            return self.send_file(os.path.join(ROOT, "manifest.json"), "application/json", ranged=False)
        if name == "signature":
            return self.send_file(os.path.join(ROOT, "manifest.json.sig"), "text/plain", ranged=False)
        if name in PLATFORMS:
            return self.send_file(os.path.join(ROOT, name + ".tar.gz"), "application/gzip", ranged=True)
        return self.send_plain(404, b'{"error":"video_toolchain_unavailable"}')

    def send_file(self, path, content_type, ranged):
        if not os.path.isfile(path):
            return self.send_plain(404, b'{"error":"video_toolchain_unavailable"}')
        size = os.path.getsize(path)
        start, end, status = 0, size - 1, 200
        match = re.fullmatch(r"bytes=(\d+)-(\d*)", self.headers.get("Range", "") or "") if ranged else None
        if match:
            start = int(match.group(1))
            end = int(match.group(2)) if match.group(2) else size - 1
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", "bytes */%d" % size)
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            end = min(end, size - 1)
            status = 206
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(end - start + 1))
        if status == 206:
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        self.end_headers()
        with open(path, "rb") as f:
            f.seek(start)
            remaining = end - start + 1
            while remaining > 0:
                chunk = f.read(min(65536, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)


print("[portail local] chaîne vidéo %s servie depuis %s sur http://127.0.0.1:%d/api" % (VERSION, ROOT, PORT), flush=True)
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
