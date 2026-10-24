import os
import urllib.request

import boto3

ec2 = boto3.client("ec2")
INSTANCE = os.environ["INSTANCE_ID"]
APP = os.environ["APP_URL"]
DEFAULT_PATH = "/search?q=Ger+...+%40PAC&corpus=bps"
PAGE = """<!doctype html>
<html><head><meta charset="utf-8"><meta http-equiv="refresh" content="10">
<meta name="viewport" content="width=device-width"><title>Cadence Finder</title></head>
<body style="font-family:sans-serif;max-width:32rem;margin:4rem auto;padding:0 1rem">
<h1>Cadence Finder is waking up</h1>
<p>The server is {state}. Starting takes about 90 seconds, and this page reloads by itself.</p>
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
    if state == "stopped":
        ec2.start_instances(InstanceIds=[INSTANCE])
        state = "starting"
    elif state == "running" and app_ready():
        path = event.get("rawPath", "/")
        query = event.get("rawQueryString", "")
        target = DEFAULT_PATH if path == "/" else path + (f"?{query}" if query else "")
        return {"statusCode": 302, "headers": {"location": APP + target}}
    return {"statusCode": 200, "headers": {"content-type": "text/html; charset=utf-8"}, "body": PAGE.format(state=state)}
