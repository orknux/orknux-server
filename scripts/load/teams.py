"""
Three teams using Orknux for a working day, compressed - the load test of #587.

Production runs on one core and two gigabytes and restarted under the traffic
of three teams. This is that traffic, imitated from what the interface sends,
so the question "does it still restart" has an answer that does not depend on
somebody's afternoon:

  * every person keeps tabs open, and a run page or a session page refreshes
    itself every second until it is closed (the interface's default since
    0.9.9.8), so open tabs are most of the requests
  * workflows run: a manual start, a JavaScript function in the sandbox, then
    an agent answering through a model
  * people chat with an agent, which streams its answer over SSE
  * issues are opened and commented on, files are attached
  * the notification bell asks every minute

The model is a stub served from this same process, OpenAI-shaped and
streaming, so the test needs no network and no key. The server has to reach it,
so run this on a network the server can resolve this container on, and say how
with STUB_URL.

Standard library only, because it runs in a bare python image:

    docker run --rm --name orknux-load-gen --network <net> \\
      -v "$PWD/scripts/load:/load:ro" -e BASE=http://<server>:8080 \\
      -e STUB_URL=http://orknux-load-gen:8199 -e ADMIN_PASSWORD=... \\
      python:3.12-slim python /load/teams.py

The defaults are an ordinary day. What restarted production was a busy one, and
this is the profile that reproduced it in two minutes on 0.9.9.8 and ran an
hour without a restart once #587 was fixed:

    PEOPLE=10 TABS=4 ROWS=20000 SAMPLE=5000 ATTACH_KB=10240 LEGACY_POLLING=1

Every thirty seconds it prints what it sent and how long the answers took. It
exits non-zero if the server stopped answering for longer than a restart takes,
which is what this test exists to catch; scripts/load/watch.sh beside it is the
other half, and records the container's memory and every restart.
"""

import http.client
import http.cookiejar
import json
import os
import random
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BASE = os.environ.get("BASE", "http://localhost:8080").rstrip("/")
ADMIN = os.environ.get("ADMIN_USER", "admin")
PASSWORD = os.environ.get("ADMIN_PASSWORD", "password")
STUB_PORT = int(os.environ.get("STUB_PORT", "8199"))
STUB_URL = os.environ.get("STUB_URL", "http://localhost:%d" % STUB_PORT)
TEAMS = int(os.environ.get("TEAMS", "3"))
PEOPLE = int(os.environ.get("PEOPLE", "5"))
DURATION = int(os.environ.get("DURATION_SECONDS", "3600"))
# How many tabs a person keeps refreshing at once. The interface refreshes a run
# or session page every second while it is open, and people leave them open.
TABS = int(os.environ.get("TABS", "2"))
# How long the stub takes to answer, in seconds, and in how many pieces.
ANSWER_SECONDS = float(os.environ.get("ANSWER_SECONDS", "3"))
ANSWER_PIECES = int(os.environ.get("ANSWER_PIECES", "30"))
# Size of the files people attach, in kilobytes.
ATTACH_KB = int(os.environ.get("ATTACH_KB", "2048"))
# The interface before #587 kept refreshing a run's page after the run had
# ended; since, it stops. 1 sends what the old interface sent, which is what the
# before-and-after comparison of the server's memory is run with.
LEGACY_POLLING = os.environ.get("LEGACY_POLLING", "0") == "1"
REPORT_SECONDS = 30
STAMP = uuid.uuid4().hex[:6]


# --------------------------------------------------------------------- stub ---

ANSWER = (
    "Here is what I found. The last three runs of the nightly export failed on "
    "the same step, because the upstream API answered with a timeout. I would "
    "retry with a longer timeout and alert the owner if it fails again. "
) * 3


def pieces(text, into):
    size = max(1, len(text) // into)
    return [text[at:at + size] for at in range(0, len(text), size)]


class Stub(BaseHTTPRequestHandler):
    """An OpenAI-compatible model: streams when asked to, answers whole when not."""

    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_GET(self):
        self.reply(200, {"data": [{"id": "stub-1"}]})

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        usage = {"prompt_tokens": 400, "completion_tokens": 60, "total_tokens": 460}
        if not body.get("stream"):
            time.sleep(ANSWER_SECONDS)
            self.reply(200, {
                "id": "stub", "object": "chat.completion", "model": "stub-1",
                "choices": [{"index": 0, "message": {"role": "assistant", "content": ANSWER},
                             "finish_reason": "stop"}],
                "usage": usage,
            })
            return
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        try:
            for piece in pieces(ANSWER, ANSWER_PIECES):
                self.frame({"choices": [{"index": 0, "delta": {"content": piece}}]})
                time.sleep(ANSWER_SECONDS / ANSWER_PIECES)
            self.frame({"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}], "usage": usage})
            self.chunk(b"data: [DONE]\n\n")
            self.chunk(b"")
        except (BrokenPipeError, ConnectionResetError):
            pass

    def frame(self, payload):
        self.chunk(("data: " + json.dumps(payload) + "\n\n").encode())

    def chunk(self, data):
        self.wfile.write(b"%x\r\n%s\r\n" % (len(data), data))
        self.wfile.flush()

    def reply(self, status, payload):
        data = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)


# ------------------------------------------------------------------ metrics ---

class Metrics:
    def __init__(self):
        self.lock = threading.Lock()
        self.samples = {}
        self.errors = {}
        self.last_ok = time.time()
        self.longest_silence = 0.0

    def record(self, name, seconds, ok, why=None):
        with self.lock:
            self.samples.setdefault(name, []).append(seconds)
            if ok:
                now = time.time()
                self.longest_silence = max(self.longest_silence, now - self.last_ok)
                self.last_ok = now
            else:
                key = "%s: %s" % (name, (why or "?")[:80])
                self.errors[key] = self.errors.get(key, 0) + 1

    def report(self, final=False):
        with self.lock:
            samples, self.samples = self.samples, {}
            errors, self.errors = self.errors, {}
            silence = max(self.longest_silence, time.time() - self.last_ok)
        total = sum(len(v) for v in samples.values())
        lines = ["%s %d requests in the last %ds, longest silence %.0fs" % (
            time.strftime("%H:%M:%S"), total, REPORT_SECONDS, silence)]
        for name in sorted(samples):
            values = sorted(samples[name])
            p = lambda q: values[min(len(values) - 1, int(q * len(values)))]
            lines.append("  %-22s n=%-5d p50=%6.0fms p95=%6.0fms max=%6.0fms" % (
                name, len(values), p(0.5) * 1000, p(0.95) * 1000, values[-1] * 1000))
        for key, count in sorted(errors.items()):
            lines.append("  ERROR %dx %s" % (count, key))
        print("\n".join(lines), flush=True)


METRICS = Metrics()


# ------------------------------------------------------------------- client ---

class Person:
    """One signed-in browser: its own cookie, its own tabs."""

    def __init__(self, user, password):
        self.user, self.password = user, password
        self.cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies))

    def sign_in(self):
        self.request("sign-in", "POST", "/api/session", {"username": self.user, "password": self.password})

    def request(self, name, method, path, payload=None, raw=None, headers=None, timeout=60):
        data = raw if raw is not None else (json.dumps(payload).encode() if payload is not None else None)
        request = urllib.request.Request(BASE + path, data=data, method=method)
        if payload is not None:
            request.add_header("Content-Type", "application/json")
        for key, value in (headers or {}).items():
            request.add_header(key, value)
        began = time.time()
        try:
            with self.opener.open(request, timeout=timeout) as answer:
                body = answer.read()
            METRICS.record(name, time.time() - began, True)
            return body
        except urllib.error.HTTPError as failure:
            METRICS.record(name, time.time() - began, False, "HTTP %d" % failure.code)
            if failure.code == 401 and name != "sign-in":
                self.safely(self.sign_in)
            return None
        except Exception as failure:  # noqa: BLE001 - every way of not answering is the finding
            METRICS.record(name, time.time() - began, False, type(failure).__name__)
            return None

    def gql(self, name, query, variables=None):
        body = self.request(name, "POST", "/graphql", {"query": query, "variables": variables or {}})
        if body is None:
            return None
        answer = json.loads(body)
        if answer.get("errors"):
            METRICS.record(name + " (graphql)", 0, False, answer["errors"][0].get("message"))
            return None
        return answer.get("data")

    def must(self, name, query, variables=None):
        data = self.gql(name, query, variables)
        if data is None:
            raise SystemExit("setup failed at %s" % name)
        return data

    @staticmethod
    def safely(action, *args):
        try:
            return action(*args)
        except Exception:  # noqa: BLE001
            return None


# -------------------------------------------------------------------- setup ---

# What the workflow's function works through, and how many of those rows it
# hands on - which is what a run page then carries on every refresh.
ROWS = int(os.environ.get("ROWS", "4000"))
SAMPLE = int(os.environ.get("SAMPLE", "40"))
FUNCTION = """export default async function summarise() {
  const rows = [];
  for (let i = 0; i < %d; i++) rows.push({ id: i, name: 'order ' + i, total: Math.round(Math.sin(i) * 1000) });
  rows.sort((a, b) => a.total - b.total);
  return { count: rows.length, lowest: rows[0].id, sample: JSON.stringify(rows.slice(0, %d)) };
}
""" % (ROWS, SAMPLE)


def set_up_team(admin, n):
    """A workspace as a team would have it: a function, a workflow and an agent."""
    w = admin.must("setup", "mutation($i: CreateWorkspaceInput!) { createWorkspace(input: $i) { id } }",
                   {"i": {"name": "Load %s team %d" % (STAMP, n), "description": "scripts/load/teams.py"}})["createWorkspace"]["id"]
    f = admin.must("setup", "mutation($i: CreateFunctionInput!) { createFunction(input: $i) { id } }",
                   {"i": {"workspaceId": w, "name": "summarise", "description": "load", "returnType": "MAP",
                          "params": [], "source": FUNCTION, "typescript": FUNCTION}})["createFunction"]["id"]
    a = admin.must("setup", "mutation($i: CreateActionInput!) { createAction(input: $i) { id } }",
                   {"i": {"workspaceId": w, "name": "Summarise", "type": "EXECUTE", "subtype": "FUNCTION",
                          "functionId": f}})["createAction"]["id"]
    p = admin.must("setup", "mutation($i: CreateModelProviderInput!) { createModelProvider(input: $i) { id } }",
                   {"i": {"workspaceId": w, "name": "stub", "endpoint": STUB_URL, "secret": "sk-load"}})["createModelProvider"]["id"]
    m = admin.must("setup", "mutation($i: CreateModelInput!) { createModel(input: $i) { id } }",
                   {"i": {"providerId": p, "name": "stub model", "modelId": "stub-1", "kind": "CHAT"}})["createModel"]["id"]
    agent = admin.must("setup", "mutation($m: ID!) { createAgentForModel(modelId: $m) { id } }",
                       {"m": m})["createAgentForModel"]["id"]
    flow = admin.must("setup", "mutation($i: CreateWorkflowInput!) { createWorkflow(input: $i) { workflowId } }",
                      {"i": {"workspaceId": w, "name": "Load %s nightly %d" % (STAMP, n), "description": "load"}}
                      )["createWorkflow"]["workflowId"]
    graph = admin.must("setup", """mutation($w: ID!, $f: ID!, $i: WorkflowGraphInput!) {
        saveWorkflowGraph(workspaceId: $w, workflowId: $f, input: $i) { status problems { severity message } } }""",
                       {"w": w, "f": flow, "i": {
                           "nodes": [
                               {"key": "start", "kind": "TRIGGER", "name": "Start", "x": 40, "y": 40},
                               {"key": "sum", "kind": "ACTION", "name": "Summarise", "actionId": a, "x": 320, "y": 40},
                               {"key": "ask", "kind": "AGENT", "name": "Explain", "agentId": agent, "x": 600, "y": 40},
                           ],
                           "edges": [{"source": "start", "target": "sum"}, {"source": "sum", "target": "ask"}],
                       }})
    errors = [p for p in graph["saveWorkflowGraph"]["problems"] if p["severity"] == "ERROR"]
    if errors:
        raise SystemExit("the workflow cannot run: %s" % errors)
    return {"workspace": w, "workflow": flow, "agent": agent}


# ------------------------------------------------------------------ traffic ---

EXECUTION = """query Execution($id: ID!) { execution(id: $id) {
  id workspaceId workflowId workflowName status trigger source { connectionType action } startedAt finishedAt durationSeconds error workflowAssigned
  stoppedAtNodeKey stoppedReason startedFrom
  steps { key kind name description status startedAt finishedAt durationSeconds input output error actionId conditionId agentId sessionId branch branchOption attempts carriedOver x y }
  edges { source target branch }
  logs { id nodeKey at level message }
  pictures { id nodeKey url prompt filename contentType }
  speeches { id nodeKey url said filename contentType }
  temporalUrl
} }"""

EXECUTIONS = """query WorkspaceExecutions($workspaceId: ID!, $page: Int!, $size: Int!, $status: ExecutionStatus, $workflowId: ID, $days: Int, $search: String, $order: String, $ascending: Boolean) {
  workspaceExecutions(workspaceId: $workspaceId, page: $page, size: $size, status: $status, workflowId: $workflowId, days: $days, search: $search, order: $order, ascending: $ascending) {
    content { id workflowId workflowName status trigger source { connectionType action } startedAt finishedAt durationSeconds workflowAssigned }
    page size totalElements totalPages
  } }"""

SESSION_QUERIES = [
    ("session", "query ($id: ID!) { llmSession(id: $id) { id workspaceId key keyPrefix eventCount createdAt lastEventAt active subagentCount notes { id note writtenBy writtenAt } } }", "id"),
    ("session events", """query ($sessionId: ID!, $page: Int, $size: Int) { llmSessionEvents(sessionId: $sessionId, page: $page, size: $size) {
       totalElements content { id kind actor content result millis at agentDetails { agent agentId model systemPrompt tools findable skills memory connections } } } }""", "sessionId"),
    ("session scratchpads", "query ($sessionId: ID!) { sessionScratchpads(sessionId: $sessionId) { name description bytes shared ownedHere } }", "sessionId"),
    ("session family", "query ($id: ID!) { llmSessionFamily(id: $id) { id key title main depth active lastEventAt } }", "id"),
]


class Tab(threading.Thread):
    """A page left open: refreshed every second, the way the interface does it."""

    def __init__(self, person, kind, target, until):
        super().__init__(daemon=True)
        self.person, self.kind, self.target, self.until = person, kind, target, until

    def run(self):
        while time.time() < self.until:
            began = time.time()
            if self.kind == "run":
                seen = self.person.gql("run page", EXECUTION, {"id": self.target})
                ended = seen and seen["execution"] and seen["execution"]["status"] != "RUNNING"
                if ended and not LEGACY_POLLING:
                    return
            else:
                for name, query, field in SESSION_QUERIES:
                    variables = {field: self.target}
                    if name == "session events":
                        variables.update({"page": 0, "size": 20})
                    self.person.gql(name, query, variables)
            time.sleep(max(0.0, 1.0 - (time.time() - began)))


def stream_chat(person, chat, text, attachments):
    """POST /api/chats/{id}/stream, read to the end as a browser would."""
    began = time.time()
    try:
        connection = http.client.HTTPConnection(BASE.split("//", 1)[1], timeout=120)
        cookie = "; ".join("%s=%s" % (c.name, c.value) for c in person.cookies)
        body = json.dumps({"text": text, "attachmentIds": attachments, "voice": False})
        connection.request("POST", "/api/chats/%s/stream" % chat, body=body, headers={
            "Content-Type": "application/json", "Accept": "text/event-stream", "Cookie": cookie})
        answer = connection.getresponse()
        if answer.status != 200:
            METRICS.record("chat stream", time.time() - began, False, "HTTP %d" % answer.status)
            return
        seen = answer.read().decode("utf-8", "replace")
        connection.close()
        ok = "event:done" in seen.replace(" ", "")
        METRICS.record("chat stream", time.time() - began, ok, None if ok else seen[-120:])
    except Exception as failure:  # noqa: BLE001
        METRICS.record("chat stream", time.time() - began, False, type(failure).__name__)


def attach(person, workspace, kind):
    boundary = uuid.uuid4().hex
    content = os.urandom(ATTACH_KB * 1024)
    body = (("--%s\r\nContent-Disposition: form-data; name=\"files\"; filename=\"report-%s.bin\"\r\n"
             "Content-Type: application/octet-stream\r\n\r\n") % (boundary, uuid.uuid4().hex[:6])).encode() \
        + content + ("\r\n--%s--\r\n" % boundary).encode()
    path = "/api/workspaces/%s/%s" % (workspace, "attachments" if kind == "chat" else "issue-attachments")
    answer = person.request("attach", "POST", path, raw=body,
                            headers={"Content-Type": "multipart/form-data; boundary=%s" % boundary})
    if answer is None:
        return []
    return [a["id"] for a in json.loads(answer).get("attachments", [])]


def person_day(person, team, until):
    """What one person does all day, a few seconds per action."""
    person.sign_in()
    w = team["workspace"]
    tabs = []
    chat = None
    issue = None
    last_bell = 0.0
    while time.time() < until:
        if time.time() - last_bell > 60:
            person.gql("bell", "query { myNotificationCount }")
            last_bell = time.time()
        tabs = [t for t in tabs if t.is_alive()]
        roll = random.random()
        if roll < 0.30:
            run = person.gql("start run", "mutation($w: ID!, $f: ID!) { startExecution(workspaceId: $w, workflowId: $f) { id } }",
                             {"w": w, "f": team["workflow"]})
            if run and len(tabs) < TABS:
                # Left open for a while after it finished, as people do.
                tab = Tab(person, "run", run["startExecution"]["id"], min(until, time.time() + random.uniform(30, 180)))
                tab.start()
                tabs.append(tab)
        elif roll < 0.50:
            if chat is None:
                started = person.gql("start chat", "mutation($i: StartChatInput!) { startChat(input: $i) { id } }",
                                     {"i": {"workspaceId": w, "agentId": team["agent"], "title": "load chat"}})
                chat = started and started["startChat"]["id"]
            if chat:
                files = attach(person, w, "chat") if random.random() < 0.15 else []
                stream_chat(person, chat, "What failed in last night's runs?", files)
                if random.random() < 0.1:
                    chat = None  # a new conversation now and then
        elif roll < 0.62:
            person.gql("runs list", EXECUTIONS, {"workspaceId": w, "page": 0, "size": 10, "status": None, "workflowId": None,
                                                 "days": 1, "search": None, "order": "STARTED", "ascending": False})
            sessions = person.gql("sessions list", "query($w: ID!) { llmSessions(workspaceId: $w, page: 0, size: 20) { content { id } } }", {"w": w})
            if sessions and sessions["llmSessions"]["content"] and len(tabs) < TABS:
                target = random.choice(sessions["llmSessions"]["content"])["id"]
                tab = Tab(person, "session", target, min(until, time.time() + random.uniform(30, 120)))
                tab.start()
                tabs.append(tab)
        elif roll < 0.80:
            if issue is None or random.random() < 0.2:
                made = person.gql("create issue", "mutation($i: IssueInput!) { createIssue(input: $i) { id number } }",
                                  {"i": {"workspaceId": w, "title": "Load issue %s" % uuid.uuid4().hex[:6],
                                         "description": "The nightly export failed again. " * 20}})
                issue = made and made["createIssue"]
            if issue:
                files = attach(person, w, "issue") if random.random() < 0.2 else []
                person.gql("comment", "mutation($id: ID!, $c: String!, $a: [ID!]) { commentOnIssue(id: $id, content: $c, attachmentIds: $a) { id } }",
                           {"id": issue["id"], "c": "Looked into it; it is the timeout again. " * 5, "a": files})
                person.gql("issue page", "query($w: ID!, $n: Int!) { workspaceIssue(workspaceId: $w, number: $n) { id title comments { id content } } }",
                           {"w": w, "n": issue["number"]})
        else:
            person.gql("issues list", "query($w: ID!) { workspaceIssues(workspaceId: $w, page: 0, size: 25) { totalElements content { id number title status } } }",
                       {"w": w})
        time.sleep(random.uniform(3, 10))


def main():
    stub = ThreadingHTTPServer(("0.0.0.0", STUB_PORT), Stub)
    stub.daemon_threads = True
    threading.Thread(target=stub.serve_forever, daemon=True).start()

    admin = Person(ADMIN, PASSWORD)
    admin.sign_in()
    teams = [set_up_team(admin, n + 1) for n in range(TEAMS)]
    print("Set up %d teams of %d, %d tabs each, for %ds against %s" % (TEAMS, PEOPLE, TABS, DURATION, BASE), flush=True)

    until = time.time() + DURATION
    people = []
    for team in teams:
        for _ in range(PEOPLE):
            person = Person(ADMIN, PASSWORD)
            thread = threading.Thread(target=person_day, args=(person, team, until), daemon=True)
            thread.start()
            people.append(thread)
            time.sleep(0.5)

    while time.time() < until:
        time.sleep(REPORT_SECONDS)
        METRICS.report()
    for thread in people:
        thread.join(timeout=30)
    METRICS.report(final=True)
    silence = METRICS.longest_silence
    print("Longest the server went without answering: %.0fs" % silence, flush=True)
    sys.exit(1 if silence > 60 else 0)


if __name__ == "__main__":
    main()
