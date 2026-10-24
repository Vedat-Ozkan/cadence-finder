#!/bin/bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
instance="$(TF_WORKSPACE=live terraform -chdir="$root/infra/terraform" output -raw node_instance_id)"
url="https://cadence-finder.duckdns.org"

case "${1:-}" in
  start)
    aws ec2 start-instances --instance-ids "$instance" --query 'StartingInstances[0].CurrentState.Name' --output text
    aws ec2 wait instance-running --instance-ids "$instance"
    for _ in $(seq 60); do
      if curl -fsS -m 5 -o /dev/null "$url/search?q=I" 2>/dev/null; then
        echo "up: $url"
        exit 0
      fi
      sleep 5
    done
    echo "instance running, but $url did not answer within 5 minutes" >&2
    exit 1
    ;;
  stop)
    aws ec2 stop-instances --instance-ids "$instance" --query 'StoppingInstances[0].CurrentState.Name' --output text
    aws ec2 wait instance-stopped --instance-ids "$instance"
    echo stopped
    ;;
  status)
    aws ec2 describe-instances --instance-ids "$instance" \
      --query 'Reservations[0].Instances[0].[State.Name,PublicIpAddress]' --output text
    ;;
  *)
    echo "usage: $0 start|stop|status" >&2
    exit 2
    ;;
esac
