# Cadence Finder as a chat connector (2026-10-26)

## Context

Step 2 of the suite plan (`~/.claude/plans/i-want-to-make-abstract-adleman.md`). In one Claude chat, a user analyses a score with Sheet Music Analysis, then asks "find more cadences like this in Beethoven". Sheet Music Analysis already labels cadences `PAC`, `IAC`, `HC` and `DC`, which is the label set Cadence Finder's grammar searches on (`@PAC`). What's missing is an MCP endpoint.

Owner's decision (2026-10-26): when the node is asleep, the tool wakes it and replies straight away that the user should ask again in about 90 seconds. The node isn't kept running.

## Design

The node is stopped most of the time, so the endpoint can't run on it. It goes in the always-on wake Lambda (`wake/handler.py`, function URL, `authorization_type = "NONE"`), on a new path, `/mcp`:

- **Protocol:** stateless streamable HTTP. JSON-RPC over POST with plain JSON replies (no SSE, no sessions) handles `initialize`, `notifications/initialized`, `tools/list` and `tools/call`. It is written by hand with no new dependencies, because the Lambda ships one file.
- **Tools:**
  - `search(query, corpus?, limit?, cursor?)` calls `GET {APP_URL}/search` and returns the hits: movement, title, bar range and chords.
  - `similar` is left out: search hits carry no passage id, so a chat has nothing to pass it. The natural-language and `/ask` tools are left out too: the chat model writes the structured query itself, so they would only add Claude API cost on our side.
- **Tool description:** the query grammar and corpus ids, taken from `NaturalLanguageParser.SYSTEM`. It also says that cadence labels from a harmonic analysis (PAC, IAC, HC, DC) can be searched as they are.
- **Asleep:** the existing state check runs first (`describe_instances`, `start_instances` when stopped, `app_ready()`). If the app isn't ready, the tool result says "Cadence Finder is starting; ask again in about 90 seconds" and gives the elapsed time. The call isn't an error.
- **Idle stop:** the Lambda's `/search` calls go through Traefik, so `idle-stop.timer` already counts them as traffic. Nothing changes there.
- **Licence:** each tool result carries the attribution line from `NOTICE` (DCML, CC BY-NC-SA 4.0).

No change to Sheet Music Analysis: Claude reads both connectors' tool descriptions in the same chat. Change Sheet Music Analysis only if the end-to-end check shows Claude doesn't make the handoff.

## Files

- `wake/handler.py`: route `/mcp` POSTs to a new `mcp(event)` function. `/.well-known/*` returns 404, so a connector's sign-in discovery neither gets the wake page nor starts the node. All other paths behave as before.
- `infra/terraform/wake.tf`: no change; `/search` answers in tens of milliseconds, inside the 10 s timeout.
- `e2e/test_mcp.py`: end-to-end test against the deployed function URL (`MCP_URL`), skipped when it isn't set. It covers `tools/list`; a search while the node is asleep (retry message); the same search after waking (hits for `Ger ... @PAC` in `bps`); and an invalid query (the error text from `/search` passed back).
- `README.md` and `docs/runbook.md`: the connector URL and how to add it in Claude.

## Verification

1. `terraform apply` in the `live` workspace. This is a deploy, so it runs only when the owner says so.
2. With the node stopped, `MCP_URL=$(terraform -chdir=infra/terraform output -raw wake_url)mcp EXPECT_ASLEEP=1 uv run pytest e2e/test_mcp.py` covers the asleep case (`EXPECT_ASLEEP=1` asserts the first call gets the retry message) and the woken case.
3. Add the function URL plus `/mcp` as a custom connector in Claude, next to Sheet Music Analysis. Analyse Beethoven Op. 2 No. 1 bars 1–8 and ask for more cadences like the one in bar 8. Pass: Claude calls `search` with a query containing `@HC` (the corpus marks bar 8 as `V` with `HC`), the first call returns the retry message, and the second returns Beethoven passages with bar ranges. ChatGPT: not run in this step.
