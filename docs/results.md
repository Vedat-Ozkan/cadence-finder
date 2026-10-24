# Results

All figures were measured on 2026-10-24 against the live deployment: one t4g.medium (2 arm64 vCPUs, 4 GB) running k3s in us-east-1, unless a row says otherwise.

## Latency under load (`/search`)

k6 `ramping-arrival-rate` from a laptop on the US east coast, over HTTPS through Traefik (`loadtest/ramp.js`). Each stage is 60 s at a fixed request rate, cycling through the 10 golden queries with `limit=50`. search-api ran 3 replicas (HPA at 70 % CPU).

| Target rate | Achieved | p50 | p95 | p99 | Errors |
|---|---|---|---|---|---|
| 5 RPS | 5.0 | 40 ms | 50 ms | 61 ms | 0 % |
| 10 RPS | 10.0 | 37 ms | 47 ms | 53 ms | 0 % |
| 20 RPS | 20.0 | 35 ms | 46 ms | 53 ms | 0 % |
| 40 RPS | 40.0 | 35 ms | 48 ms | 80 ms | 0 % |
| 80 RPS | 80.0 | 34 ms | 57 ms | 216 ms | 0 % |
| 120 RPS | collapsed | 5.3 s | 7.0 s | 7.4 s | 2.9 % (aborted) |

- **Maximum rate with p99 under 200 ms:** about 80 RPS; p99 reaches 216 ms at 80. At 120 RPS the two vCPUs saturate and the test aborts on its p99 > 1 s guard.
- **Server side:** `http.server.request.duration` for `GET /search`, exported by the ADOT Java agent as an exponential histogram through EMF. In normal traffic the server-side p50 is a few milliseconds below the client figure. Right after a deploy or a wake, server-side p99 runs at about 2.3 s for the first ~100 requests while the JVMs warm up. That is why the p99 alarm only counts 5-minute windows with at least 50 requests.
- **Cold JVMs:** a first ramp started 2 minutes after a deploy aborted at 5 RPS (p99 4.5 s). Warm-up matters on this node size.

## `/nl-search` (Claude)

0.2 RPS for 60 s alongside 5 RPS of `/search` (`NL=1 loadtest/ramp.js`). This uses `claude-opus-5-5` at effort `low` with structured output.

| p50 | p95 | p99 | Errors |
|---|---|---|---|
| 1.64 s | 3.02 s | 3.64 s | 0 % |

The model call is almost all of it. Parsing and the structured search behind it add tens of milliseconds.

## Ingestion

Uploading all 188 movement files to S3 → SQS → worker (parse, embed through the embedder with `voyage-4-large`, write DynamoDB, write the passages file):

- **14,788 passages in 164 s,** from the first to the last completion marker: **about 90 passages/s, or 69 movements/min**, with one worker replica.
- The worker embeds before writing to DynamoDB, so a Voyage failure retries cheaply.
- A malformed file reaches the DLQ after 3 receives, as the e2e test checks.

## Cold start (scale to zero)

The node is stopped when idle. Opening the wake link (a Lambda function URL) starts it:

| Step | Time |
|---|---|
| Wake page served (Lambda cold) | 2.2 s |
| Instance running | ~16 s |
| k3s, pods, DuckDNS and HTTPS ready; page redirects | ~80–85 s |
| First query after the redirect | 0.8 s |

The node powers itself off after 30 minutes without API traffic. This was checked: the last request was at 14:56 and the node stopped at 15:31 UTC.

## Tracing

`POST /reindex/bps:01-1`, sent from inside the cluster, produced **one X-Ray trace** (`1-6adcd155-884d96771cce0890f9fb3583`, 8.0 s). It covers:
- search-api: a DynamoDB `GetItem` and SQS `SendMessage`;
- the worker's SQS consumer segment, with S3 `GetObject`/`PutObject` and DynamoDB `BatchWriteItem`;
- the embedder's `POST /passages`;
- Postgres.

The ADOT Java agent carries trace context across SQS (`OTEL_INSTRUMENTATION_AWS_SDK_EXPERIMENTAL_USE_PROPAGATOR_FOR_MESSAGING`), so no manual `traceparent` handling was needed.

## Retrieval quality (self-labelled)

**These labels are self-made.** Each case pairs an English description, a source passage, or a sentence with a hand-written structured query. The relevant set is that query's hits from `/search`, and a result counts as relevant when it covers a hit's bars in the same movement. Definitions are in `e2e/recall.yaml` under `metric:`.
- **Hit rate@5:** the share of cases with at least one relevant result in the top 5. It is what the plan called recall@5.
- **Precision@5:** relevant results in the top 5, divided by 5.
- **Case sets:** 30 original cases, plus 10 held-out cases written and frozen before the passage-text change below was measured.

**Passage-text change.** Passages used to start with composer, title and key. They now lead with the chords and an English gloss of the notable ones ("German augmented sixth", "applied dominant seventh of the dominant"), then the cadences, with the header last. This was measured on compose with `voyage-4-lite`:

| Run | Original 30, before | Original 30, after | Held-out 10, before | Held-out 10, after |
|---|---|---|---|---|
| text → passage | 0.31 | 0.62 | 0.17 | 0.50 |
| text + rerank-3 | 0.44 | 0.69 | 0.33 | 0.50 |
| similar passage | 0.29 | 0.43 | 0.25 | 0.75 |
| similar + rerank-3 | 0.36 | 0.43 | 0.75 | 1.00 |

Live, with `voyage-4-large`, after the change. Precision@5 is in brackets.

| Run | Original 30 | Held-out 10 |
|---|---|---|
| text → passage | 0.62 (0.39) | 0.67 (0.40) |
| text + rerank-3 | 0.69 (0.45) | 1.00 (0.50) |
| similar passage | 0.36 (0.10) | 0.50 (0.15) |
| similar + rerank-3 | 0.43 (0.17) | 1.00 (0.30) |
| `/nl-search` (16 / 6 sentences) | 1.00 (0.85), recall 0.97–1.00 | 1.00 (0.70), recall 0.97 |

Takeaways:
- Turning a sentence into a validated structured query (`/nl-search`) is far more reliable than embedding search for harmonic questions.
- Embeddings work for cadence types and named chromatic chords. They still miss progressions made only of plain triads, such as the Phrygian half cadence, `ii65 V7 I`, or `bVI It6 V`.
- The held-out sets are small, 6 text and 4 similar cases, so one case moves the hit rate by 0.17–0.25.

## Cost

- **Stopped node:** about $0.80 a month, for the 10 GB gp3 disk.
- **Running node:** about $0.039 an hour (instance plus public IPv4).
- **Wake Lambda:** within the free tier.
- **CloudWatch:** two alarms at about $0.20 a month. Custom metrics are prorated by the hour, so they cost almost nothing while the node is off.
- **One full live re-ingest:** about $0.55 of Voyage `voyage-4-large`.
