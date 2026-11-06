#!/usr/bin/env python3
"""Exercise a real Android process restart against a local, controlled auth server."""

import json
import os
import re
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Server(ThreadingHTTPServer):
    available = False
    refreshes = 0


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def respond(self):
        path = self.path.split("?", 1)[0]
        if path == "/api/auth/refresh":
            size = int(self.headers.get("Content-Length", "0"))
            request = json.loads(self.rfile.read(size) or b"{}")
            if request.get("refreshToken") not in ("cold-old-refresh", "cold-new-refresh"):
                return self.send_json(401, {})
            self.server.refreshes += 1
            if not self.server.available:
                return self.send_json(503, {})
            now = datetime.now(timezone.utc)
            return self.send_json(200, {
                "token": "cold-new-access", "refreshToken": "cold-new-refresh",
                "expiresAt": (now + timedelta(hours=1)).isoformat(),
                "refreshExpiresAt": (now + timedelta(days=1)).isoformat(),
                "user": {"id": "cold-account", "username": "Cold Account"},
            })
        if self.headers.get("Authorization") != "Bearer cold-new-access":
            return self.send_json(401, {})
        if path in ("/api/auth/bootstrap", "/api/auth/me"):
            return self.send_json(200, {"user": {"id": "cold-account", "username": "Cold Account"}})
        if path == "/api/catalog/libraries":
            return self.send_json(200, {"libraries": [{"id": "movies", "name": "Cold Movies", "type": "movies"}]})
        return self.send_json(200, {})

    def send_json(self, status, body):
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    do_GET = respond
    do_POST = respond
    do_PUT = respond
    do_DELETE = respond


def main():
    suffix = os.environ.get("ORG_GRADLE_PROJECT_zenstreamApplicationIdSuffix", ".debug")
    package = "com.zenstream.zenstreammobile" + suffix
    serial = os.environ.get("ANDROID_SERIAL")
    adb_prefix = ["adb"] + (["-s", serial] if serial else [])

    def adb(*args, check=True):
        return subprocess.run(adb_prefix + list(args), check=check, capture_output=True, text=True, timeout=90).stdout

    def screen():
        dump = adb("exec-out", "uiautomator", "dump", "/dev/tty")
        start, end = dump.find("<hierarchy"), dump.find("</hierarchy>")
        if start < 0 or end < 0:
            return []
        root = ET.fromstring(dump[start:end + len("</hierarchy>")])
        return list(root.iter("node"))

    def wait_text(label, timeout=60):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            nodes = screen()
            for node in nodes:
                if node.get("text") == label or node.get("content-desc") == label:
                    return node
            time.sleep(0.5)
        raise AssertionError(f"Timed out waiting for {label}")

    def click(label):
        node = wait_text(label)
        bounds = re.fullmatch(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if not bounds:
            raise AssertionError(f"No bounds for {label}")
        x1, y1, x2, y2 = map(int, bounds.groups())
        adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def start():
        adb("shell", "am", "start", "-n", package + "/com.zenstream.zenstreammobile.MainActivity")

    server = Server(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    seeded = False
    url = f"http://10.0.2.2:{server.server_port}"
    try:
        seed = adb("shell", "am", "instrument", "-w", "-e", "class",
                   "com.zenstream.zenstreammobile.SessionColdStartSeedTest#seed", "-e", "coldStartServer",
                   url, package + ".test/androidx.test.runner.AndroidJUnitRunner")
        if "OK (1 test)" not in seed or "FAILURES" in seed:
            raise AssertionError("Cold-start seed instrumentation failed or was skipped")
        seeded = True
        adb("shell", "pm", "grant", package, "android.permission.ACCESS_LOCAL_NETWORK", check=False)
        adb("shell", "pm", "grant", package, "android.permission.POST_NOTIFICATIONS", check=False)
        adb("shell", "am", "force-stop", package)
        start()
        wait_text("Could not load your library")
        first_pid = adb("shell", "pidof", package).strip()
        if not first_pid or server.refreshes == 0:
            raise AssertionError("The first process did not attempt refresh")
        adb("shell", "am", "force-stop", package)
        if adb("shell", "pidof", package, check=False).strip():
            raise AssertionError("The original app process is still alive")
        start()
        wait_text("Could not load your library")
        second_pid = adb("shell", "pidof", package).strip()
        if not second_pid or second_pid == first_pid:
            raise AssertionError("The app did not start in a new process")
        server.available = True
        click("Retry")
        click("Library")
        wait_text("Cold Movies")
        print("Cold process restart, temporary refresh failure, and library recovery passed")
    finally:
        adb("shell", "am", "force-stop", package, check=False)
        if seeded:
            cleanup = adb("shell", "am", "instrument", "-w", "-e", "class",
                          "com.zenstream.zenstreammobile.SessionColdStartSeedTest#seed", "-e", "coldStartServer", url,
                          "-e", "coldStartCleanup", "true", package + ".test/androidx.test.runner.AndroidJUnitRunner")
            if "OK (1 test)" not in cleanup or "FAILURES" in cleanup:
                raise AssertionError("Cold-start fixture cleanup failed")
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    main()
