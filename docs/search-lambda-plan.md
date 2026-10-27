# Instant search in the connector, and Sheet Music Analysis labels (plan, 2026-10-27)

## Context

The MCP connector (`wake/handler.py`, `/mcp`) answers `search` only when the k3s node is running. When it's stopped, the first call starts it and tells the chat to ask again in about 90 s. In the intended hand-off (analyse a score with Sheet Music Analysis, then "find more cadences like this"), that means a wait and a second request in the middle of the conversation.

Search doesn't need the node: the n-gram index and movement metadata are in DynamoDB (on demand, always available), and the matching is plain Java in `corpus-core`. The node, the Spring services, pgvector, SQS and the observability stack stay as they are; they still serve the web app, similar-passage search, natural-language search and ingest.

A second problem: the chat passes labels from Sheet Music Analysis (`Cad64`, `Ger65`, `viiø7`, `N6`) into a query, and the query grammar either rejects them or matches nothing, because the corpus spells them differently (`V`, `Ger6`, `vii%7`, `bII6`). Today only the tool description bridges this.

## Changes

1. **Labels (`corpus-core`, `Query.parse`).** Before validation, rewrite Sheet Music Analysis spellings to the corpus's: `Cad64` → `V`, `Ger65` → `Ger6`, `Fr43` → `Fr6`, `N` → `bII`, `N6` → `bII6`, `ø` and `/o` → `%`, `°` → `o`. Targets follow what the corpus contains (Ger6 249, It6 181, Fr6 29 occurrences; cadential six-fours are `V`). Both the web app and the connector get it.
2. **Shared search (`search-core`, new module).** Move the search logic out of `search-api`'s `SearchService` into a plain-Java module that depends on `corpus-core` and `cadence-store`. `search-api` keeps a thin Spring wrapper, unchanged in behaviour.
3. **Search Lambda (`search-lambda`, new module).** A Java 25 Lambda with SnapStart that runs `search-core` against the live DynamoDB table. It loads every movement's metadata during init, so the snapshot holds it and a call makes only the index query. Published version plus an alias; the wake Lambda invokes the alias.
4. **Wake Lambda.** `search` in `/mcp` invokes the search Lambda instead of calling the node. The node is no longer started by `search`. The tool description drops the "asleep, ask again" line.
5. **Terraform (`infra/terraform/search.tf`).** The function, its alias, an execution role with `dynamodb:Query`/`GetItem` on the table and its GSI, and `lambda:InvokeFunction` on the alias for the wake Lambda.

## Verification

- `./gradlew build`, and the golden end-to-end cases in `e2e/golden.yaml` (three new ones with Sheet Music Analysis spellings), which CI runs against the LocalStack stack.
- Labels on the real corpus with the corpus CLI (`./gradlew :corpus-core:run --args="search <corpus> '<query>' bps"`): each plugin spelling returns what the corpus spelling returns, and labels from a real draft of Beethoven Op. 2 No. 1, bars 1–8 (half cadence at bar 8), are tried as a query.
- After deploy (owner's go): `e2e/test_mcp.py` against the MCP URL with the node stopped: hits come back and the instance stays stopped. Cold and warm latency recorded; both must fit the wake Lambda's 10 s timeout.
- `README.md`, `docs/runbook.md` and `e2e/test_mcp.py` updated where they describe the retry.

## Findings while building (2026-10-27)

- The aliases return exactly what the corpus spelling returns (`Ger65 ... @PAC` 25 = `Ger6 ... @PAC` 25; `Cad64 V7 I@PAC` 160 = `V V7 I@PAC` 160; `N6 V` 11 = `bII6 V` 11; `viiø7 I` 5 = `vii%7 I` 5; `#vii°7 i` 9 = `#viio7 i` 9, all in bps).
- The real draft labels for Op. 2 No. 1 bars 7–8 (`i6 iiø65 V@HC`) find nothing, and not because of spelling: the draft hears a half-diminished seventh (`iiø65`) where the expert annotation has a diminished triad (`iio6`). Exact matching can't bridge a disagreement about the chord; the tool description's widening advice (drop figures, then the cadence alone) does reach the passage through `V@HC`. A looser match (by root and cadence) would be a separate feature.
- In minor keys Sheet Music Analysis writes `viio7` where the corpus writes `#viio7`. A fixed rewrite can't know the key, so the tool description says to add the sharp in minor.
- The movements loaded before the snapshot go stale when a movement is reindexed (`search-api`'s in-memory cache has the same limit until its pod restarts). The corpus changes rarely, so the runbook says to republish the search Lambda after an ingest or reindex rather than adding a refresh to every call.
