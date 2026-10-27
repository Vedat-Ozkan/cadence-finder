import base64
import json
import os
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

import boto3

ec2 = boto3.client("ec2")
INSTANCE = os.environ["INSTANCE_ID"]
APP = os.environ["APP_URL"]
DEFAULT_PATH = "/search?q=Ger+...+%40PAC&corpus=bps"
EXPECTED_SECONDS = 90
PROTOCOL_VERSIONS = ["2025-11-25", "2025-06-18", "2025-03-26"]
ATTRIBUTION = ("Data: DCML corpora (Annotated Beethoven Corpus; Beethoven Piano Sonatas; Annotated Mozart Sonatas), "
               "CC BY-NC-SA 4.0, https://github.com/DCMLab")
INSTRUCTIONS = ("Cadence Finder finds real passages in Beethoven and Mozart (piano sonatas, Beethoven string quartets) "
                "by cadence type (searchable directly, e.g. @HC) and chord progression. When the user wants more examples of a progression or cadence, "
                "for instance one found in a harmonic analysis of their score, call search rather than listing examples from memory.")
SEARCH_TOOL = {
    "name": "search",
    "description": """Find real passages in Beethoven and Mozart by cadence type and/or chord progression, from expert \
Roman-numeral analyses (DCML standard) of Beethoven's string quartets and Beethoven's and Mozart's piano sonatas. \
Returns movements with bar ranges and the matching chords. Use it whenever the user asks for more examples, other passages \
or similar cadences or progressions in Beethoven or Mozart, instead of answering from memory.

Cadences are labelled in the corpus, so search them directly: "@HC" finds every half cadence, "@PAC" every perfect \
authentic cadence. Add the approach to narrow it: "ii% V@HC" is a half-diminished ii arriving at a half cadence, \
"Ger ... @PAC" a German sixth leading to a perfect authentic cadence. To find cadences like one in the user's score, \
use its cadence label (PAC, IAC, HC, DC) with the chords before it, without inversion figures: a figure makes every \
chord match exactly as written and often finds nothing. If a narrow search is empty, widen it (drop figures, use " ... ", \
or search the cadence alone).

Query grammar:
- Steps are separated by a single space (the next chord follows immediately) or by " ... " (zero to four other chords may come in between).
- A step is a chord, a chord with a cadence (V@HC), or a cadence alone (@PAC = any chord carrying that cadence).
- Cadences: PAC (perfect authentic), IAC (imperfect authentic), HC (half), DC (deceptive), EC (evaded), PC (plagal).
- Chords are relative to the local key. Upper case is major, lower case is minor: I, i, ii, IV, V, vi, bII, #iv. \
Qualities: o (diminished), % (half-diminished), + (augmented), M (major seventh), e.g. viio7, ii%7.
- Inversion figures: 6, 64 (triads), 7, 65, 43, 2 (sevenths), e.g. V65, ii6, I6.
- Applied chords use a slash: V/V, V65/V, viio7/ii.
- Augmented sixths are Ger, It, Fr, optionally with a figure: Ger6, It6, Fr6, Ger43.
- The cadential six-four is written V, not I64: write "V V7 I@PAC", never "I64 V".
- If any chord has an inversion figure, every chord must match exactly as written (V means root position). \
If no chord has a figure, inversions are ignored (V matches V, V6, V65, V7...). Prefer the figure-free form.
- A minor-key tonic is i.

More examples: "It V" in mps; "V7 vi@DC" in all corpora; "@HC" in bps.

If the server is asleep, the result says it is starting; tell the user and call again after about 90 seconds.""",
    "inputSchema": {
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "The progression in the query grammar"},
            "corpus": {"type": "string", "enum": ["abc", "bps", "mps"],
                       "description": "abc: Beethoven string quartets (no cadence labels, so never with a cadence); "
                                      "bps: Beethoven piano sonatas; mps: Mozart piano sonatas. Omit for all."},
            "limit": {"type": "integer", "minimum": 1, "maximum": 200, "description": "Results per page (default 50)"},
            "cursor": {"type": "string", "description": "nextCursor from a previous result, for the next page"},
        },
        "required": ["query"],
    },
}
PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Cadence Finder</title>
<style>
:root { color-scheme: light dark; --fg: #1d1d1f; --muted: #6e6e73; --bg: #fafafa; --track: #e3e3e8; --bar: #3b6fd8; }
@media (prefers-color-scheme: dark) { :root { --fg: #f2f2f5; --muted: #a1a1a6; --bg: #141416; --track: #2c2c30; --bar: #6f9bff; } }
body { margin: 0; background: var(--bg); color: var(--fg); font: 16px/1.5 system-ui, sans-serif; }
main { max-width: 30rem; margin: 15vh auto; padding: 0 16px; }
h1 { font-size: 1.4rem; margin: 0 0 .5rem; }
p { color: var(--muted); margin: 0 0 1.5rem; }
#timer { font: 600 3rem/1 ui-monospace, monospace; font-variant-numeric: tabular-nums; }
.track { height: 8px; background: var(--track); border-radius: 4px; overflow: hidden; margin: 1rem 0 .5rem; }
#bar { height: 100%; width: 0; background: var(--bar); transition: width 1s linear; }
#status { color: var(--muted); font-size: .9rem; }
</style></head>
<body><main>
<h1>Cold start: waking the demo server</h1>
<p>Cadence Finder sleeps when nobody is using it, to keep hosting costs near zero.
Waking it usually takes about a minute and a half. This page opens the app by itself when it is ready.</p>
<div id="timer">0:00</div>
<div class="track"><div id="bar"></div></div>
<div id="status">Starting the server…</div>
</main>
<script>
const expected = __EXPECTED__;
const labels = { pending: "Booting the machine…", running: "Starting Kubernetes and the services…",
  stopping: "The server is finishing shutting down; it will start again right after…", stopped: "Starting the server…" };
let elapsed = __ELAPSED__;
const timer = document.getElementById("timer"), bar = document.getElementById("bar"), status = document.getElementById("status");
function render() {
  timer.textContent = Math.floor(elapsed / 60) + ":" + String(Math.floor(elapsed % 60)).padStart(2, "0");
  bar.style.width = Math.min(95, elapsed / expected * 100) + "%";
  if (elapsed > expected * 2) status.textContent = "Taking longer than usual. Keep this page open, it is still trying.";
}
setInterval(() => { elapsed += 1; render(); }, 1000);
render();
async function poll() {
  try {
    const response = await fetch(location.href, { headers: { accept: "application/json" }, cache: "no-store" });
    const body = await response.json();
    if (body.ready) {
      bar.style.width = "100%";
      status.textContent = "Ready. Opening the app…";
      location.replace(body.target);
      return;
    }
    elapsed = Math.max(elapsed, body.elapsed);
    if (elapsed <= expected * 2) status.textContent = labels[body.state] || body.state;
  } catch (error) {
    status.textContent = "Lost contact, retrying…";
  }
  setTimeout(poll, 3000);
}
setTimeout(poll, 3000);
</script>
</body></html>"""


def app_ready():
    try:
        with urllib.request.urlopen(f"{APP}/search?q=I", timeout=3) as response:
            return response.status == 200
    except OSError:
        return False


def wake():
    instance = ec2.describe_instances(InstanceIds=[INSTANCE])["Reservations"][0]["Instances"][0]
    state = instance["State"]["Name"]
    elapsed = 0
    if state == "stopped":
        ec2.start_instances(InstanceIds=[INSTANCE])
        state = "pending"
    elif state in ("pending", "running"):
        elapsed = int((datetime.now(timezone.utc) - instance["LaunchTime"]).total_seconds())
    return state, state == "running" and app_ready(), elapsed


def search(arguments):
    state, ready, elapsed = wake()
    if not ready:
        return f"Cadence Finder is starting (server {state}, {elapsed} s so far; it usually takes about {EXPECTED_SECONDS} s). Ask again in about a minute and a half.", False
    params = {"q": arguments.get("query", "")} | {k: arguments[k] for k in ("corpus", "limit", "cursor") if arguments.get(k)}
    try:
        with urllib.request.urlopen(f"{APP}/search?{urllib.parse.urlencode(params)}", timeout=5) as response:
            return response.read().decode() + "\n\n" + ATTRIBUTION, False
    except urllib.error.HTTPError as e:
        if e.code != 400:
            return f"Cadence Finder failed with HTTP {e.code}; try again shortly.", True
        return f"Invalid query: {json.load(e)['detail']}", True
    except OSError:
        return "Cadence Finder is still warming up; try again shortly.", True


def rpc(message):
    method, params = message.get("method"), message.get("params") or {}
    if method == "initialize":
        version = params.get("protocolVersion")
        return {"protocolVersion": version if version in PROTOCOL_VERSIONS else PROTOCOL_VERSIONS[0],
                "capabilities": {"tools": {}}, "serverInfo": {"name": "cadence-finder", "version": "1"},
                "instructions": INSTRUCTIONS}
    if method == "ping":
        return {}
    if method == "tools/list":
        return {"tools": [SEARCH_TOOL]}
    if method == "tools/call" and params.get("name") == "search":
        text, error = search(params.get("arguments") or {})
        return {"content": [{"type": "text", "text": text}], "isError": error}
    raise LookupError(method if method != "tools/call" else f"unknown tool {params.get('name')}")


def mcp(event):
    if event["requestContext"]["http"]["method"] != "POST":
        return {"statusCode": 405, "headers": {"allow": "POST"}}
    body = event.get("body") or ""
    message = json.loads(base64.b64decode(body) if event.get("isBase64Encoded") else body)
    if "id" not in message:
        return {"statusCode": 202}
    try:
        reply = {"jsonrpc": "2.0", "id": message["id"], "result": rpc(message)}
    except LookupError as e:
        reply = {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601 if message.get("method") != "tools/call" else -32602, "message": str(e)}}
    return {"statusCode": 200, "headers": {"content-type": "application/json"}, "body": json.dumps(reply)}


def handler(event, context):
    path = event.get("rawPath", "/")
    if path == "/mcp":
        return mcp(event)
    if path.startswith("/.well-known/"):
        return {"statusCode": 404}
    state, ready, elapsed = wake()
    query = event.get("rawQueryString", "")
    target = APP + (DEFAULT_PATH if path == "/" else path + (f"?{query}" if query else ""))
    if "application/json" in event.get("headers", {}).get("accept", ""):
        body = {"state": state, "ready": ready, "elapsed": elapsed, "target": target}
        return {"statusCode": 200, "headers": {"content-type": "application/json", "cache-control": "no-store"}, "body": json.dumps(body)}
    if ready:
        return {"statusCode": 302, "headers": {"location": target}}
    page = PAGE.replace("__EXPECTED__", str(EXPECTED_SECONDS)).replace("__ELAPSED__", str(elapsed))
    return {"statusCode": 200, "headers": {"content-type": "text/html; charset=utf-8", "cache-control": "no-store"}, "body": page}
