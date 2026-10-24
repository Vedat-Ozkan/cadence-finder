import json
import os
import urllib.request
from datetime import datetime, timezone

import boto3

ec2 = boto3.client("ec2")
INSTANCE = os.environ["INSTANCE_ID"]
APP = os.environ["APP_URL"]
DEFAULT_PATH = "/search?q=Ger+...+%40PAC&corpus=bps"
EXPECTED_SECONDS = 90
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


def handler(event, context):
    instance = ec2.describe_instances(InstanceIds=[INSTANCE])["Reservations"][0]["Instances"][0]
    state = instance["State"]["Name"]
    elapsed = 0
    if state == "stopped":
        ec2.start_instances(InstanceIds=[INSTANCE])
        state = "pending"
    elif state in ("pending", "running"):
        elapsed = int((datetime.now(timezone.utc) - instance["LaunchTime"]).total_seconds())
    ready = state == "running" and app_ready()
    path = event.get("rawPath", "/")
    query = event.get("rawQueryString", "")
    target = APP + (DEFAULT_PATH if path == "/" else path + (f"?{query}" if query else ""))
    if "application/json" in event.get("headers", {}).get("accept", ""):
        body = {"state": state, "ready": ready, "elapsed": elapsed, "target": target}
        return {"statusCode": 200, "headers": {"content-type": "application/json", "cache-control": "no-store"}, "body": json.dumps(body)}
    if ready:
        return {"statusCode": 302, "headers": {"location": target}}
    page = PAGE.replace("__EXPECTED__", str(EXPECTED_SECONDS)).replace("__ELAPSED__", str(elapsed))
    return {"statusCode": 200, "headers": {"content-type": "text/html; charset=utf-8", "cache-control": "no-store"}, "body": page}
