#!/bin/bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
tag="${1:-$(git -C "$root" rev-parse HEAD)}"
instance="$(TF_WORKSPACE=live terraform -chdir="$root/infra/terraform" output -raw node_instance_id)"

manifest="$(kubectl kustomize "$root/deploy/k8s/overlays/k3s" \
  | sed "s#\(ghcr.io/vedat-ozkan/cadence-finder/[a-z-]*\):main#\1:$tag#" | gzip | base64 -w0)"

read -r -d '' script <<SCRIPT || true
bash -s <<'REMOTE'
set -euo pipefail
export AWS_REGION=us-east-1
kc() { k3s kubectl "\$@"; }
param() { aws ssm get-parameter --name "/cadence/live/\$1" --with-decryption --query Parameter.Value --output text; }
kc create namespace cadence --dry-run=client -o yaml | kc apply -f -
kc -n cadence create secret generic api-keys \
  --from-literal=ANTHROPIC_API_KEY="\$(param ANTHROPIC_API_KEY)" \
  --from-literal=VOYAGE_API_KEY="\$(param VOYAGE_API_KEY)" \
  --dry-run=client -o yaml | kc apply -f -
kc -n cadence create secret docker-registry ghcr --docker-server=ghcr.io \
  --docker-username=vedat-ozkan --docker-password="\$(param GHCR_TOKEN)" \
  --dry-run=client -o yaml | kc apply -f -
kc -n cadence get secret postgres >/dev/null 2>&1 \
  || kc -n cadence create secret generic postgres --from-literal=password="\$(openssl rand -hex 24)"
echo "$manifest" | base64 -d | gunzip | kc apply -f -
for workload in statefulset/postgres deployment/embedder deployment/ingest-worker deployment/search-api; do
  kc -n cadence rollout status "\$workload" --timeout=300s
done
REMOTE
SCRIPT

command_id="$(aws ssm send-command --instance-ids "$instance" --document-name AWS-RunShellScript \
  --comment "deploy cadence-finder $tag" \
  --parameters "$(python3 -c 'import json, sys; print(json.dumps({"commands": [sys.argv[1]], "executionTimeout": ["900"]}))' "$script")" \
  --query Command.CommandId --output text)"
echo "deploying $tag to $instance (command $command_id)"
while [ "$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance" --query Status --output text)" = InProgress ]; do
  sleep 5
done
aws ssm get-command-invocation --command-id "$command_id" --instance-id "$instance" \
  --query '[Status, StandardOutputContent, StandardErrorContent]' --output text
