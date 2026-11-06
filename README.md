# Cadence Finder

[![ci](https://github.com/Vedat-Ozkan/cadence-finder/actions/workflows/ci.yml/badge.svg)](https://github.com/Vedat-Ozkan/cadence-finder/actions/workflows/ci.yml)
[![images](https://github.com/Vedat-Ozkan/cadence-finder/actions/workflows/images.yml/badge.svg)](https://github.com/Vedat-Ozkan/cadence-finder/actions/workflows/images.yml)

Search for chord progressions and cadences in the 40 annotated corpora of DCML's Distant Listening Corpus: 1,284 movements by 36 composers, from Monteverdi to Poulenc (CC BY-NC-SA 4.0; see [NOTICE](NOTICE)).
- Structured queries like `Ger ... @PAC` ("a German sixth leading, within four chords, to a perfect authentic cadence").
- Similar-passage search over embeddings.
- Plain-English questions, turned into validated structured queries by Claude.

**Live demo:** the wake link (`terraform output wake_url` in the `live` workspace) starts the server on demand. Allow about 2 minutes for a cold start; after that the app is at https://cadence-finder.duckdns.org.

**In Claude:** add the wake link followed by `mcp` (for example `https://<id>.lambda-url.us-east-1.on.aws/mcp`) as a custom connector, under Settings > Connectors. It has one tool, `search`, which answers at once whether or not the server is running: the wake Lambda calls a Java search Lambda (SnapStart) that reads the index in DynamoDB. Labels as Sheet Music Analysis writes them (`Cad64`, `Ger65`, `viiø7`) are accepted.

## Why this stack

A search over 1,284 movements would fit in one process with SQLite. This project is deliberately built as a practice lab for running a production backend: an event-driven ingest pipeline with a dead-letter queue, services on Kubernetes (one k3s node, and the same manifests on EKS), infrastructure in Terraform, end-to-end tests against LocalStack, load tests in CI, one trace across every service, alarms, and a game day with injected failures. The results below are measured on it, and it costs about $0.80 a month while stopped and about $0.04 an hour while running (details in [docs/results.md](docs/results.md#cost)).

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
| `/search` at 120 RPS on 2 vCPUs, warm | p50 31 ms, p95 42 ms, p99 101 ms, 0 errors |
| Same ramp on a cold replica | aborts at 40 RPS (p99 3.2 s) while new replicas warm up |
| `/search` across all 40 corpora at 20 RPS, warm | p50 36 ms, p99 557 ms, 0 errors |
| `/nl-search` (Claude) | p50 1.9 s, p99 4.2 s, with a limit of 5 a minute |
| Ingestion | 53,654 passages in total; ~77/s while adding 1,093 movements |
| Cold start from stopped | ~110 s to first result |
| Retrieval, self-labelled hit rate@5 | `/nl-search` 1.00; text search 0.62–0.69, rerank 0.69–1.00 |
| One trace across the API, SQS, worker and embedder | yes, a single X-Ray trace |
| `/ask` (RAG with validated citations) | 8/8 grounded; 8/8 prompt-injection cases pass; p50 9 s |
| In-cluster `/search`, after warm-up | one k3s node: 320 RPS at p99 152 ms; EKS with 2 nodes: 240 RPS at p99 19 ms |
| Game day | stalled worker detected in 6.5 min, bad file in 9 min, Postgres kill self-healed with no failed requests |

## Layout

| Path | Contents |
|---|---|
| `corpus-core/` | TSV parser, tokenizer, query grammar, matcher, passage text |
| `cadence-store/` | DynamoDB table access |
| `search-core/` | Search over the DynamoDB index, shared by `search-api` and `search-lambda` |
| `search-lambda/` | The connector's search: a Java 25 Lambda with SnapStart (`./gradlew :search-lambda:build` writes the zip Terraform deploys) |
| `ingest-worker/`, `search-api/` | Spring Boot 4.1 on Java 25 |
| `embedder/` | FastAPI on Python 3.14, pgvector |
| `wake/` | Lambda that wakes the node |
| `deploy/k8s/` | Kustomize base plus `kind`, `k3s` and `eks` overlays |
| `infra/terraform/` | Workspaces `dev` and `live` |
| `e2e/` | docker compose with LocalStack; golden, NL, DLQ, reindex and recall tests |
| `loadtest/` | k6 smoke test (in CI) and ramp |
| `scripts/` | `fetch-corpus.sh` pins every DCML corpus; `corpus-metadata.py` writes the corpus list and movement titles (`corpus-core` resources, `wake/corpora.tsv`) from the fetched corpus and DCML's metadata |
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

Against real AWS (`AWS_ENDPOINT=`), the suite reuses the data already there. `FULL_INGEST=1` uploads all 1,284 movements again, which costs about $1–2 in DynamoDB writes.

The `kind` overlay runs the same LocalStack inside the cluster, so kind never touches AWS. It needs a `localstack` secret holding `LOCALSTACK_AUTH_TOKEN`, next to `api-keys` and `postgres`. LocalStack's data resets whenever its pod restarts, and the kind collector only logs to its own output.
