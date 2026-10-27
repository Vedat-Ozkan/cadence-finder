# Runbook

Live environment: one t4g.medium running k3s in us-east-1, namespace `cadence`.

| Thing | Where |
|---|---|
| App | https://cadence-finder.duckdns.org (`/search`, `/passages/{id}/similar`, `/nl-search`, `/ask`; the last two are rate-limited to 5 a minute per IP) |
| Public link that wakes the node | `terraform -chdir=infra/terraform output -raw wake_url` (workspace `live`) |
| MCP connector (Claude) | the wake link plus `mcp`; stateless JSON-RPC in `wake/handler.py`, which invokes the search Lambda (`cadence-finder-live-search`, alias `live`, SnapStart) and never starts the node; checked by `MCP_URL=<wake link>mcp uv run pytest e2e/test_mcp.py` |
| Dashboard | CloudWatch → Dashboards → `cadence-finder-live` |
| Traces | CloudWatch → X-Ray traces → Trace map; filter `service("search-api")`. Only 10 % of requests and ingest messages are traced (`OTEL_TRACES_SAMPLER_ARG`) |
| App logs | Log group `/cadence-finder/live/app` (7 days) |
| Metrics (EMF) | Namespace `CadenceFinder/live`, log group `/cadence-finder/live/metrics` |
| Alarms | `cadence-finder-live-dlq-not-empty`, `-search-p99`, `-ingest-stalled`, `-search-api-5xx`, all to SNS topic `cadence-finder-live-alarms` |
| Secrets | SSM Parameter Store `/cadence/live/*` (SecureString) |

## Getting a shell on the node

There is no SSH. Use Session Manager (install the AWS `session-manager-plugin` locally once):

```
aws ssm start-session --target "$(TF_WORKSPACE=live terraform -chdir=infra/terraform output -raw node_instance_id)"
sudo -i
alias k='k3s kubectl -n cadence'
```

For one-off commands without a session, `aws ssm send-command --document-name AWS-RunShellScript` works too (that is what `scripts/deploy-live.sh` does).

## Starting and stopping the node

The node is stopped by default to keep the bill under $5 a month.

- **A visitor** opens the wake link. The Lambda starts the instance and shows a cold-start page with a timer and progress bar. It redirects once `/search` answers, about 80–90 s after a stopped start.
- **You:** `scripts/node.sh start`, `scripts/node.sh stop` or `scripts/node.sh status`.
- **Automatic stop:** `idle-stop.timer` on the node powers it off once it has been up for 30 min and Traefik has logged no `/search`, `/passages` or `/nl-search` request for 30 min.
  - To keep it up while working without traffic, run `systemctl stop idle-stop.timer` on the node. A reboot brings the timer back.
- **On every boot,** `duckdns-update.service` points `cadence-finder.duckdns.org` at the new public IP. If the name points at an old IP:
  - check `systemctl status duckdns-update` and `journalctl -u duckdns-update`;
  - the token comes from `/cadence/live/DUCKDNS_TOKEN`.

**The wake link shows "Taking longer than usual":**
1. Read the Lambda's log group `/aws/lambda/cadence-finder-live-wake`.
2. Check `scripts/node.sh status`.
3. On the node, check `k get pods`. A pod stuck `ContainerCreating` after a fresh instance usually means the GHCR pull secret is wrong. Re-run `scripts/deploy-live.sh`, which rebuilds it from `/cadence/live/GHCR_TOKEN`.

## Alarms

<a id="dlq-not-empty"></a>
### DLQ not empty (`cadence-finder-live-dlq-not-empty`)

A message failed ingestion 3 times (`maxReceiveCount`) and moved to `ingest-live-dlq`.

The e2e suite deliberately sends a malformed `raw/bps/99-9.harmonies.tsv` and deletes its DLQ message afterwards. An alarm right after an e2e run against live is expected and clears by itself.

1. Look at the message:
   ```
   DLQ=$(aws sqs get-queue-url --queue-name ingest-live-dlq --query QueueUrl --output text)
   aws sqs receive-message --queue-url "$DLQ" --max-number-of-messages 10 --visibility-timeout 30 --attribute-names All
   ```
   An S3 event names the object key under `Records[].s3.object.key`, and a reindex request is `{"movementId": …}`.
2. Find the error. Search `/cadence-finder/live/app` in Logs Insights:
   ```
   fields @timestamp, @message | filter @message like /ERROR|Exception/ | sort @timestamp desc | limit 50
   ```
   or, on the node, `k logs deploy/ingest-worker --since=1h | grep -B2 -A20 Exception`. X-Ray shows the failing `ingest-worker` spans with the exception attached.
3. Fix the cause:
   - **Malformed TSV:** fix or delete the object in S3.
   - **Embedder or Voyage down:** check `k logs deploy/embedder` and the Voyage dashboard.
   - **DynamoDB throttled:** shows up as a capacity spike on the dashboard.
4. Redrive the message back to the main queue:
   ```
   aws sqs start-message-move-task --source-arn "$(aws sqs get-queue-attributes --queue-url "$DLQ" --attribute-names QueueArn --query Attributes.QueueArn --output text)"
   ```
   If the message is junk, delete it instead with `aws sqs delete-message`.
5. The alarm returns to OK within 5 minutes of the queue emptying.

<a id="search-p99"></a>
### Search p99 above 500 ms (`cadence-finder-live-search-p99`)

p99 of `GET /search` stayed over 0.5 s for two 5-minute periods.

1. Check the dashboard to see whether request volume rose with the latency, which points to load, or stayed flat, which points to a regression or a dependency.
2. In X-Ray, open the slowest `GET /search` traces. The spans show whether the time goes to DynamoDB (`Query` on `gsi1`, `GetItem`) or to the matcher inside search-api.
   - Anchor-heavy queries such as a single common chord (`V`) fan out over many movements, which is expected to be slower.
3. On the node:
   - `k top pods` and `k get hpa search-api`. The HPA scales search-api up to 3 replicas at 70 % CPU, but each replica needs 576Mi, and the node has about 1.5 GB of headroom.
   - `k describe pod -l app=search-api | grep -A5 'Last State'` shows recent OOM kills or restarts.
4. **After a cold start,** the first minute of JIT warm-up can push p99 up. If the alarm fires right after a wake and clears, no action is needed.
5. **If DynamoDB is the bottleneck,** it is on-demand, so look for throttling events in the table's CloudWatch metrics.

<a id="ingest-stalled"></a>
### Ingest stalled (`cadence-finder-live-ingest-stalled`)

The oldest message in `ingest-live` is more than 3 minutes old: messages arrive but nothing consumes them.

1. Run `k get deploy ingest-worker` and `k get pods -l app=ingest-worker`.
   - If the worker is at 0 replicas or crash-looping, scale it back up or fix it. See "Pod crash loop" below.
2. If the worker is running, look at `k logs deploy/ingest-worker --since=15m`:
   - `queue-not-found` or credential errors point to config or IAM;
   - long pauses point to slow Voyage or embedder calls. Check the embedder logs.
3. The alarm clears once the backlog drains, at about 90 passages/s with one replica.

<a id="search-api-5xx"></a>
### search-api 5xx (`cadence-finder-live-search-api-5xx`)

More than five 500 responses in a minute.

1. In X-Ray, filter `service("search-api") AND http.status = 500` to see which route and downstream call fails.
2. Usual causes:
   - **`/passages/{id}/similar` and `/ask`:** the embedder or Postgres is down. Check `k get pods`. The StatefulSet restarts `postgres-0` by itself, and the embedder's pool reconnects.
   - **`/nl-search` and `/ask`:** Claude API errors. Check status.anthropic.com and the `ANTHROPIC_API_KEY` secret.
   - **`/search`:** DynamoDB errors or throttling. Check the dashboard.

## Pod crash loop

```
k get pods
k describe pod <pod> | sed -n '/Last State/,/Ready/p'
k logs <pod> --previous --tail=100
```

- **`OOMKilled`:** the JVM's off-heap memory, plus the OTel agent, outgrew the limit.
  - Limits: worker 640Mi, search-api 576Mi, embedder 256Mi, collector 192Mi, postgres 384Mi.
  - Raise the limit in `deploy/k8s/base/*.yaml`, or lower `-Xmx` in `JAVA_TOOL_OPTIONS`, then redeploy.
  - Check `free -m` on the node first: 3.8 GB total, with no swap.
- **Embedder exits with `ConnectionRefusedError`:** Postgres wasn't ready yet. This is normal once at first boot.
- **Repeated `Connection refused`:** check `k get pods -l app=postgres`.
- **Startup probe fails:**
  - Spring takes longer with the agent attached; the startup probe allows 120 s.
  - Look for `APPLICATION FAILED TO START` in the previous logs. A missing queue fails fast because of `queue-not-found-strategy: fail`.
- **`ImagePullBackOff`:** the GHCR token (`/cadence/live/GHCR_TOKEN`, a classic PAT with `read:packages`) expired. Store a new one, then re-run `scripts/deploy-live.sh`.
- **otel-collector restarting:** traces and metrics stop, but the app keeps working, because agents drop data they can't export.
  - Usual causes: the IAM role is missing `xray:PutTraceSegments` or `logs:PutLogEvents`, or the log groups were deleted.
  - `k logs deploy/otel-collector` names the failing exporter.

## pgvector disk full

Postgres lives on a 5Gi `local-path` PVC on the node's 10 GB root disk. The corpus uses well under 1 GB.

1. Check usage:
   ```
   df -h /
   k exec postgres-0 -- psql -U postgres -c "select pg_size_pretty(pg_database_size('postgres'))"
   k exec postgres-0 -- du -sh /var/lib/postgresql/data
   ```
2. If the root disk is full, the usual cause is old container images. Run `k3s crictl rmi --prune`.
3. If Postgres itself grew, from repeated re-ingests leaving dead tuples, run `k exec postgres-0 -- psql -U postgres -c 'VACUUM FULL passages'`.
4. **Last resort:** the embeddings can always be rebuilt. Delete the PVC and pod, then re-ingest by uploading the corpus again or running the e2e suite against live. That costs about $0.55 of Voyage.
5. To grow the disk, raise `volume_size` in `infra/terraform/ec2.tf`. Growing works in place; shrinking needs a new instance.

## Deploying

1. Push to `main`. `images.yml` publishes `ghcr.io/vedat-ozkan/cadence-finder/*:<sha>` and `:main`.
   - **If GitHub Actions can't run** (the private repo's minutes or spending limit ran out), sideload instead:
     1. Build arm64 images locally with `docker buildx build --platform linux/arm64 -t ghcr.io/vedat-ozkan/cadence-finder/<svc>:<sha> --output type=docker,dest=<svc>.tar <svc>`.
     2. Gzip each one and copy it to `s3://cadence-finder-live-961136197755/images/`.
     3. On the node: `aws s3 cp s3://…/images/<svc>.tar.gz - | gunzip | k3s ctr images import -`.
     4. Deploy that tag. The pods use the imported image, because pulls are `IfNotPresent` for non-`latest` tags.
2. Run `scripts/deploy-live.sh [sha]`. It defaults to `HEAD`. The node has to be running, so start it first with `scripts/node.sh start`.
3. Check with `curl -s 'https://cadence-finder.duckdns.org/search?q=V7+vi@DC&corpus=bps' | head -c 300`.

To reindex one movement, which also produces one end-to-end trace, run from the node:
```
curl -s -X POST "http://$(k get svc search-api -o jsonpath='{.spec.clusterIP}')/reindex/bps:01-1"
```
`/reindex` is not exposed through the public ingress.

## Rotating secrets

1. Update the SecureString under `/cadence/live/`:
   - `ANTHROPIC_API_KEY`
   - `VOYAGE_API_KEY`
   - `GHCR_TOKEN`
   - `DUCKDNS_TOKEN`
2. Re-run `scripts/deploy-live.sh`, which rebuilds the Kubernetes secrets.
3. For `DUCKDNS_TOKEN`, run `systemctl restart duckdns-update` on the node instead.
