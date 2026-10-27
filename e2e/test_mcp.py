import json
import os
import time
import urllib.request

import pytest

MCP = os.environ.get("MCP_URL")
WAKE_TIMEOUT = int(os.environ.get("WAKE_TIMEOUT", "300"))
EXPECT_ASLEEP = os.environ.get("EXPECT_ASLEEP") == "1"

pytestmark = pytest.mark.skipif(not MCP, reason="MCP_URL is not set")


def rpc(method, params=None, id=1):
    body = {"jsonrpc": "2.0", "method": method} | ({"id": id} if id is not None else {}) | ({"params": params} if params else {})
    request = urllib.request.Request(
        MCP,
        data=json.dumps(body).encode(),
        headers={"content-type": "application/json", "accept": "application/json, text/event-stream"},
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        return response.status, response.read()


def search(arguments):
    status, body = rpc("tools/call", {"name": "search", "arguments": arguments})
    assert status == 200
    result = json.loads(body)["result"]
    return result["content"][0]["text"], result["isError"]


def search_awake(arguments):
    text, error = search(arguments)
    deadline = time.monotonic() + WAKE_TIMEOUT
    while text.startswith("Cadence Finder is starting") and time.monotonic() < deadline:
        time.sleep(15)
        text, error = search(arguments)
    return text, error


def test_initialize_and_list_tools():
    status, body = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "e2e", "version": "0"}})
    assert status == 200
    assert json.loads(body)["result"]["protocolVersion"] == "2025-06-18"
    assert rpc("notifications/initialized", id=None)[0] == 202
    tools = json.loads(rpc("tools/list")[1])["result"]["tools"]
    assert [tool["name"] for tool in tools] == ["search"]


@pytest.mark.skipif(not EXPECT_ASLEEP, reason="EXPECT_ASLEEP is not set")
def test_search_while_asleep_starts_the_node():
    text, error = search({"query": "V@HC", "corpus": "bps"})
    assert text.startswith("Cadence Finder is starting")
    assert not error


def test_search_answers_once_awake():
    text, error = search_awake({"query": "Ger ... @PAC", "corpus": "bps"})
    assert not error
    page = json.loads(text.split("\n\n")[0])
    assert any((hit["movementId"], hit["firstBar"], hit["lastBar"]) == ("bps:01-1", 140, 142) for hit in page["results"])
    assert "CC BY-NC-SA 4.0" in text


def test_invalid_query_returns_the_reason():
    text, error = search_awake({"query": "V@XYZ"})
    assert error
    assert text.startswith("Invalid query: unknown cadence")
