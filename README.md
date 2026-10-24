# Cadence Finder

Search for chord progressions and cadences in three annotated corpora by Beethoven and Mozart (DCML, CC BY-NC-SA 4.0; see [NOTICE](NOTICE)).
- Structured queries like `Ger ... @PAC` ("a German sixth leading, within four chords, to a perfect authentic cadence").
- Similar-passage search over embeddings.
- Plain-English questions, turned into validated structured queries by Claude.

**Live demo:** the wake link (`terraform output wake_url` in the `live` workspace) starts the server on demand. Allow about 85 s for a cold start; after that the app is at https://cadence-finder.duckdns.org.

## How it works

- **Upload:** a TSV goes to S3.
- **SQS:** the S3 event lands on SQS, with a dead-letter queue after 3 receives.
- **`ingest-worker` (Spring Boot):**
  - parses the file into chord tokens;
  - sends 8-chord passages to the `embedder` (FastAPI, Voyage, pgvector);
  - writes the movement plus an n-gram index to DynamoDB.
- **`search-api` (Spring Boot):**
  - answers `/search` from the DynamoDB index plus a matcher;
  - answers `/passages/{id}/similar` from pgvector, with optional rerank;
  - answers `/nl-search` by having Claude write the structured query, which goes through the same validator;
  - answers `/ask` from up to 8 retrieved passages, with citations checked against them.
- **Hosting:** everything runs on one k3s node on EC2, built with Terraform, with Traefik and Let's Encrypt.
- **Tracing:** an ADOT collector sends traces to X-Ray, metrics as EMF, and logs to CloudWatch.
- **Scale to zero:** the node stops itself after 30 idle minutes, and a Lambda function URL wakes it.

## Measured (details in [docs/results.md](docs/results.md))

| | |
|---|---|
| `/search` at 80 RPS on 2 vCPUs | p50 34 ms, p95 57 ms, p99 216 ms, 0 errors |
| Max rate with p99 < 200 ms | ~80 RPS |
| `/nl-search` (Claude) | p50 1.6 s, p99 3.6 s |
| Ingestion | 14,788 passages in 164 s (~90/s) |
| Cold start from stopped | ~85 s to first result |
| Retrieval, self-labelled hit rate@5 | `/nl-search` 1.00; text search 0.62–0.69, rerank 0.69–1.00 |
| One trace across the API, SQS, worker and embedder | yes, a single X-Ray trace |
| `/ask` (RAG with validated citations) | 8/8 grounded; 8/8 prompt-injection cases pass; p50 9 s |
| Game day | stalled worker detected in 6.5 min, bad file in 9 min, Postgres kill self-healed with no failed requests |

## Layout

| Path | Contents |
|---|---|
| `corpus-core/` | TSV parser, tokenizer, query grammar, matcher, passage text |
| `cadence-store/` | DynamoDB table access |
| `ingest-worker/`, `search-api/` | Spring Boot 4.1 on Java 25 |
| `embedder/` | FastAPI on Python 3.14, pgvector |
| `wake/` | Lambda that wakes the node |
| `deploy/k8s/` | Kustomize base plus `kind` and `k3s` overlays |
| `infra/terraform/` | Workspaces `dev` and `live` |
| `e2e/` | docker compose with LocalStack; golden, NL, DLQ, reindex and recall tests |
| `loadtest/` | k6 smoke test (in CI) and ramp |
| `docs/` | [build plan](docs/build-plan.md), [runbook](docs/runbook.md), [results](docs/results.md) |

## Running the tests locally

```
scripts/fetch-corpus.sh
./gradlew build :ingest-worker:bootJar :search-api:bootJar
set -a; . ./.env; set +a
docker compose -f e2e/compose.yaml up -d --build --wait
uv run --project e2e pytest e2e -q
```

`.env` needs `LOCALSTACK_AUTH_TOKEN` and `VOYAGE_API_KEY`. `ANTHROPIC_API_KEY` is only needed with `RUN_NL=1`.
