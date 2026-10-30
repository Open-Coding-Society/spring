#!/usr/bin/env python3
"""Toolchain pairing test (LOCAL ONLY).
Each step takes values out of the previous response (session id, pairing code,
agent token) and feeds them into the next request, so nothing is copied by hand.
Pairing codes and tokens are never printed."""
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.request

# The base URL comes from JAVA_URI (same name as javaURI in the pages config). Default: local Spring.
BASE = os.environ.get("JAVA_URI", "http://127.0.0.1:8585").rstrip("/")
IS_LOCAL = BASE.startswith(("http://127.0.0.1", "http://localhost"))
PW = os.environ.get("TOOLCHAIN_TEST_PASSWORD", "LocalTest123!" if IS_LOCAL else "")
STUDENT_UID = os.environ.get("TOOLCHAIN_STUDENT_UID", "hop")
TEACHER_UID = os.environ.get("TOOLCHAIN_TEACHER_UID", "niko")
STATION = "terminal-town-gate"
if not IS_LOCAL and os.environ.get("TOOLCHAIN_TEST_ALLOW_REMOTE") != "1":
    sys.exit("Refusing to run against " + BASE + ": this test creates sessions. "
             "Set TOOLCHAIN_TEST_ALLOW_REMOTE=1 to allow a non-local server.")
if not PW:
    sys.exit("Set TOOLCHAIN_TEST_PASSWORD for a non-local server.")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None           # show a 302 instead of following it to /login


class Actor:
    """One cookie jar per actor, so student, teacher, agent and anonymous stay separate."""
    def __init__(self):
        jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect)

    def call(self, method, path, body=None, token=None):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(BASE + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with self.opener.open(req, timeout=15) as resp:
                status, raw = resp.status, resp.read().decode()
        except urllib.error.HTTPError as err:
            status, raw = err.code, err.read().decode()
        try:
            out = json.loads(raw) if raw else {}
        except ValueError:
            out = {}
        return status, (out if isinstance(out, dict) else {})


passed = failed = 0


def check(label, ok, detail=""):
    global passed, failed
    passed, failed = passed + bool(ok), failed + (not ok)
    print("PASS" if ok else "FAIL", "-", label, ("| " + detail) if detail else "")


def evidence(exit_code=0, trail="/home/test/toolchain-trail", station=True):
    body = {"os": "linux", "stationId": STATION, "checks": [
        {"id": "working-folder", "exitCode": 0, "output": "/home/test"},
        {"id": "trail-folder", "exitCode": exit_code, "output": trail}]}
    if not station:
        del body["stationId"]
    return body


def main():
    student, teacher, agent, nobody = Actor(), Actor(), Actor(), Actor()
    try:
        s, _ = student.call("POST", "/authenticate", {"uid": STUDENT_UID, "password": PW})
    except urllib.error.URLError:
        print("Spring is not running on", BASE, "- start it first.")
        return
    check("student logs in", s == 200, f"status {s}")
    s, _ = teacher.call("POST", "/authenticate", {"uid": TEACHER_UID, "password": PW})
    check("teacher logs in", s == 200, f"status {s}")

    # 1. start: the student's response carries the session id and the one-time code
    s, _ = nobody.call("POST", "/api/toolchain/sessions", {"stationId": STATION})
    check("start without login is refused", s in (401, 403), f"status {s}")
    s, _ = student.call("POST", "/api/toolchain/sessions", {"stationId": ""})
    check("start with a blank station is a 400", s == 400, f"status {s}")
    s, _ = student.call("POST", "/api/toolchain/sessions", {"stationId": "not-a-station"})
    check("start with an unknown station is a 400", s == 400, f"status {s}")
    s, start = student.call("POST", "/api/toolchain/sessions", {"stationId": STATION})
    session_id, code = start.get("sessionId"), start.get("pairingCode")
    check("start returns a session id and a pairing code", s == 200 and bool(session_id and code), f"status {s}")
    if not (session_id and code):
        return
    s, boss = student.call("POST", "/api/toolchain/sessions", {"stationId": "integration-summit"})
    print("INFO - starting directly at the boss station:", s)
    s, other = student.call("POST", "/api/toolchain/sessions", {"stationId": STATION})   # a second, unpaired session
    other_id = other.get("sessionId")
    s, _ = student.call("POST", f"/api/toolchain/sessions/{other_id}/check", {"stationId": STATION})
    check("a check before any agent is paired is a 409", s == 409, f"status {s}")

    # 2. pair: the agent (no cookie) trades the code for a token
    s, _ = agent.call("POST", "/api/toolchain/pair", {"pairingCode": "NOTAREALCODE"})
    check("a bogus pairing code is a 401", s == 401, f"status {s}")
    s, paired = agent.call("POST", "/api/toolchain/pair", {"pairingCode": code})
    token = paired.get("agentToken")
    check("the real code returns a token for the same session",
          s == 200 and bool(token) and str(paired.get("sessionId")) == str(session_id), f"status {s}")
    if not token:
        return
    s, _ = Actor().call("POST", "/api/toolchain/pair", {"pairingCode": code})
    check("the same code cannot be used twice", s == 401, f"status {s}")

    # 3. check and poll
    s, view = student.call("POST", f"/api/toolchain/sessions/{session_id}/check", {"stationId": STATION})
    check("the owner requests a station check", s == 200 and view.get("status") == "CHECKING", f"status {s}")
    s, _ = teacher.call("POST", f"/api/toolchain/sessions/{session_id}/check", {"stationId": STATION})
    check("another user gets a 404 on this session", s == 404, f"status {s}")
    s, view = agent.call("GET", f"/api/toolchain/sessions/{session_id}/next", token=token)
    check("the agent polls with its token", s == 200 and view.get("stationId") == STATION, f"status {s}")
    s, _ = Actor().call("GET", f"/api/toolchain/sessions/{session_id}/next")
    check("polling with no token is a 401", s == 401, f"status {s}")
    s, _ = Actor().call("GET", f"/api/toolchain/sessions/{session_id}/next", token="garbage")
    check("polling with a garbage token is a 401", s == 401, f"status {s}")
    s, _ = agent.call("GET", f"/api/toolchain/sessions/{other_id}/next", token=token)
    check("the token does not work on another session", s == 401, f"status {s}")

    # 4. results: the server, not the agent, decides pass or fail
    results = f"/api/toolchain/sessions/{session_id}/results"
    s, _ = agent.call("POST", results, {"os": "linux", "stationId": "build-bridge",
                                         "checks": [{"id": "x", "exitCode": 0, "output": "x"}]}, token=token)
    check("results for a different station are a 409", s == 409, f"status {s}")
    s, _ = agent.call("POST", results, {"os": "linux", "stationId": STATION, "checks": []}, token=token)
    check("an empty list of checks is a 400", s == 400, f"status {s}")
    s, view = agent.call("POST", results, evidence(exit_code=1, station=False), token=token)
    print("INFO - results with no stationId:", s, view.get("status"))
    only_one = evidence()
    only_one["checks"] = only_one["checks"][:1]
    s, view = agent.call("POST", results, only_one, token=token)
    check("a check the agent did not send fails the station", view.get("status") == "FAILED", f"status {s}")
    s, view = agent.call("POST", results, evidence(exit_code=1), token=token)
    check("matching output with a non-zero exit code fails", view.get("status") == "FAILED", f"status {s}")
    s, view = agent.call("POST", results, evidence(trail="/home/test/other-folder"), token=token)
    check("a zero exit code with the wrong output fails", view.get("status") == "FAILED", f"status {s}")
    s, view = agent.call("POST", results, evidence(), token=token)
    check("passing evidence verifies the station", view.get("status") == "VERIFIED", f"status {s}")
    s, view = agent.call("POST", results, evidence(exit_code=1), token=token)
    check("a verified station stays verified", view.get("status") == "VERIFIED", f"status {s}")

    # 5. status: only the owner can read it
    s, view = student.call("GET", f"/api/toolchain/sessions/{session_id}")
    check("the owner reads VERIFIED", s == 200 and view.get("status") == "VERIFIED", f"status {s}")
    check("the view leaves out hashes and tokens",
          not any(k in view for k in ("pairingCodeHash", "agentTokenHash", "agentToken", "pairingCode")),
          "keys: " + ",".join(sorted(view)))
    s, _ = teacher.call("GET", f"/api/toolchain/sessions/{session_id}")
    check("the teacher gets a 404 on the student's session", s == 404, f"status {s}")
    s, _ = student.call("GET", "/api/toolchain/sessions/not-a-uuid")
    check("a malformed session id is a 404", s == 404, f"status {s}")


if __name__ == "__main__":
    main()
    print(f"\n{passed} passed, {failed} failed")
