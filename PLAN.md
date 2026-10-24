# Cadence Finder: build plan

A search service for chord progressions over an expert-annotated corpus. You ask for a progression or a natural-language query ("German augmented sixth before a perfect authentic cadence in Beethoven quartets") and get matching passages with bar ranges.

Purpose: one small, coherent project that honestly demonstrates the backend skills job postings keep asking for and the CV lacks: Java, AWS, Kubernetes, microservices, NoSQL, observability, RAG and vector search, performance measurement, on-call/reliability. It fits the music portfolio, needs no PyTorch, and could later be called as a tool by Reflets or the Sheet Music plugin.

Framing for the CV: this backs "hands-on experience" with these tools from one 2026 project. It is not years of experience, and interviewers will check. Add each line to `cv.md` only after it runs, worded from what the repo shows.

## Corpus

- Three DCML corpora in the same TSV format: ABC (Beethoven string quartets, 70 movements), Beethoven piano sonatas (64) and Mozart piano sonatas (54).
- ABC has no cadence labels, only phrase endings. The two sonata corpora have expert cadence labels (PAC, IAC, HC, DC, EC, PC). Cadence filters apply only to those two, so the headline query is answered from the Beethoven sonatas.
- Licence: all three are CC BY-NC-SA 4.0. Decided: a free, attributed, non-commercial public demo (see `NOTICE`). Derived data stays under the same licence. When in Rome was not an escape route, because its Beethoven quartets are converted from ABC.

## Architecture

1. Annotation files land in S3. An S3 event goes to an SQS queue with a dead-letter queue (DLQ).
2. Ingestion worker (Java, Spring Boot) cuts each file into passage windows, writes metadata and transposition-invariant n-gram keys to DynamoDB, and stores raw passage text in S3.
3. Embedding service (Python, FastAPI, asyncio) turns passage text into embeddings with Voyage AI (`voyage-4-large`; Anthropic has no embeddings API) and stores them in Postgres with pgvector. The worker calls it over HTTP; this is the second service.
4. Search API (Java, Spring Boot) does exact n-gram lookup through a DynamoDB GSI, and similarity search through the embedding service and pgvector. From weekend 2, a natural-language endpoint has Claude turn the sentence into a structured query, which is validated before it runs.
5. RAG endpoint: an LLM answers only from retrieved passages. A validator rejects any passage ID or bar range that was not retrieved (the same rule as the OBD app's number check). A prompt-injection test set checks that a hostile passage cannot change the answer's citations.
6. Runs on k3s on one EC2 instance (t4g.medium, 4 GB) plus kind on the x86 laptop, with the same manifests. Build multi-arch (amd64 + arm64) images from day one. All AWS resources and the instance are defined in Terraform.
7. Observability: OpenTelemetry Java agent and Python instrumentation send to the ADOT collector. JSON logs go to CloudWatch Logs, metrics go out as EMF, traces go to X-Ray. One trace must span API, SQS, worker and embedding service; if the agent does not link them, pass `traceparent` in the SQS message attributes.
8. CloudWatch dashboard plus alarms (p99 latency, DLQ depth above 0) notifying by email through SNS. A runbook lives in the repo.
9. k6 smoke tests run in GitHub Actions. The figures for the CV come from k6 runs against the t4g node.

The detailed build plan, the tool versions and the design decisions are in `docs/build-plan.md`.

Optional stretch (only if the three weekends go well): replace the Java ingestion worker with a Go worker and benchmark the two. Do not include this unless it earns its place.

## What it honestly demonstrates

| Skill | Where |
|---|---|
| Java, Spring Boot | search API, ingestion worker |
| Microservices (service decomposition) | API, worker and embedding service communicating over HTTP and SQS |
| Python FastAPI and asyncio | embedding service |
| AWS | S3, SQS with DLQ, DynamoDB key design, CloudWatch, SNS, EC2, X-Ray |
| NoSQL | DynamoDB table and GSI design |
| Vector search and RAG | pgvector, retrieval with citation validation, recall@5 |
| Kubernetes | deployments, probes, config, HPA on kind and k3s |
| Infrastructure as Code | Terraform |
| Observability | logs, metrics, traces, dashboard, alarms, runbook |
| Performance measurement | k6 load tests, latency percentiles |
| Reliability and on-call (simulated) | DLQ handling, runbook, game day |
| Prompt-injection defence | injection test set against the RAG endpoint |

What it does not demonstrate: Go (unless the stretch happens), real EKS in production, Lambda or ECS, real on-call rotations, microservices at scale (three services is decomposition), Airflow, Spark, GraphQL.

## Plan

### Weekend 1: ingestion on kind
- S3 to SQS to worker to DynamoDB runs end to end on kind, with Terraform for the AWS pieces.
- Exact-match API returns bar ranges.
- A poison message lands in the DLQ.
- CI runs on GitHub Actions.
- Done when: the full corpus is ingested and `curl` returns correct passages for 10 known progressions.

### Weekend 2: search, deploy, observe
- Embedding service and pgvector similarity search.
- Deploy to k3s on EC2 with a live URL.
- OpenTelemetry and CloudWatch with a dashboard and two alarms; write the runbook.
- Run k6.
- Done when: the live URL works, one trace spans the queue and all services, and the README has p50, p95 and p99 figures.
- This is the shippable state if weekend 3 never happens.

### Weekend 3 (stretch): RAG and game day
- RAG endpoint with citation validation and the prompt-injection test set.
- Game day: kill the worker, inject bad data, record detection and recovery time.
- Optionally deploy the same manifests to EKS for one afternoon and tear it down the same day.
- Done when: the README has the RAG evaluation and the game-day write-up.

## Results to record for the CV
- Search API p50, p95 and p99 at a sustained requests-per-second rate, and the maximum RPS before p99 goes over 200 ms, on one node.
- Ingestion throughput in passages per second. On a single 2-vCPU node scaling with replicas will probably stay flat; report that honestly as the node's limit.
- Vector search latency over N passages, and recall@5 on about 30 self-labelled queries (say that they are self-labelled).
- Citation-validity rate of RAG answers and the prompt-injection pass rate.
- Time to detect and time to recover in the game day.

## Cost
- t4g.medium about $24 a month, plus about $2 for EBS and about $3.65 for a public IPv4. Stop the instance when you are not demoing.
- Lambda, DynamoDB (25 GB) and CloudWatch (10 metrics, 10 alarms, 3 dashboards) are in the Always Free tier. SQS should be about 1M requests a month free; check the Always Free page.
- Each unique EMF dimension combination counts as a billed custom metric, so keep dimensions few.
- LLM and embedding calls under $2. The Anthropic and Voyage keys live in `.env`, which is never committed.
- DynamoDB runs on-demand. Always Free covers storage but not on-demand traffic; ingesting about 400k items costs well under $1.
- The LocalStack image used in CI now needs an auth token. The free Hobby (non-commercial) plan provides one.
- EKS for one afternoon about $3 to $5. Do not use a template that creates a NAT gateway (about $32 a month).
- Check whether new AWS accounts get credits that apply to EC2 and EKS.
- Total about $25 to $35 a month while the instance is up, less with credits.

## Risks
- Corpus licence: decided. Parsing risk is low because the TSVs are already parsed. Watch for augmented sixths, which are in `chord_type`, not `numeral`.
- Two JVMs, a Python service, pgvector and the collector need 4 GB of RAM. If that is a problem, merge the API and worker into one Java service.
- Embedding Roman-numeral text with a general sentence model gives weak similarity; the recall numbers will show it, so report them as measured.
- Scope creep: weekend 3 and the Go stretch are optional.

## CV bullets to write once built (draft, fill in measured numbers)
- Built Cadence Finder, a chord-progression search service over an expert-annotated corpus, as three services (Java Spring Boot API and worker, Python FastAPI embedding service) deployed on Kubernetes.
- Ingested annotation files through S3, SQS (with a dead-letter queue) and DynamoDB, with AWS infrastructure defined in Terraform.
- Traced requests across services with OpenTelemetry, with CloudWatch dashboards and alarms; measured p99 latency of [N] ms at [N] requests per second with k6.
- Added a RAG endpoint whose answers cite retrieved passages, with a validator that rejects uncited bar ranges, and a prompt-injection test set.

## Sources
- AWS Free Tier announcement: https://aws.amazon.com/about-aws/whats-new/2025/07/aws-free-tier-credits-month-free-plan/
- AWS Free Tier FAQs: https://aws.amazon.com/free/faqs/
- EKS pricing 2026: https://cloudburn.io/blog/amazon-eks-pricing
- OpenTelemetry with Spring Boot: https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/
