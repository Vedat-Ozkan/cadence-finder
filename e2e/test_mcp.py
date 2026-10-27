import json
import os
import urllib.request

import pytest

MCP = os.environ.get("MCP_URL")

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


def test_initialize_and_list_tools():
    status, body = rpc("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "e2e", "version": "0"}})
    assert status == 200
    assert json.loads(body)["result"]["protocolVersion"] == "2025-06-18"
    assert rpc("notifications/initialized", id=None)[0] == 202
    tools = json.loads(rpc("tools/list")[1])["result"]["tools"]
    assert [tool["name"] for tool in tools] == ["search"]


@pytest.mark.parametrize("query", ["Ger ... @PAC", "Ger65 ... @PAC"])
def test_search_answers_at_once(query):
    text, error = search({"query": query, "corpus": "bps"})
    assert not error
    page = json.loads(text.split("\n\n")[0])
    assert any((hit["movementId"], hit["firstBar"], hit["lastBar"]) == ("bps:01-1", 140, 142) for hit in page["results"])
    assert "CC BY-NC-SA 4.0" in text


def test_invalid_query_returns_the_reason():
    text, error = search({"query": "V@XYZ"})
    assert error
    assert text.startswith("Invalid query: unknown cadence")
