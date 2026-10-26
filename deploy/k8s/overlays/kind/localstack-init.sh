#!/bin/bash
set -euo pipefail

awslocal s3 mb s3://cadence-e2e
awslocal sqs create-queue --queue-name ingest-e2e-dlq
dlq_arn=$(awslocal sqs get-queue-attributes --queue-url http://localhost:4566/000000000000/ingest-e2e-dlq \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal sqs create-queue --queue-name ingest-e2e --attributes "{
  \"VisibilityTimeout\": \"30\",
  \"RedrivePolicy\": \"{\\\"deadLetterTargetArn\\\":\\\"$dlq_arn\\\",\\\"maxReceiveCount\\\":\\\"3\\\"}\"
}"
queue_arn=$(awslocal sqs get-queue-attributes --queue-url http://localhost:4566/000000000000/ingest-e2e \
  --attribute-names QueueArn --query Attributes.QueueArn --output text)
awslocal s3api put-bucket-notification-configuration --bucket cadence-e2e --notification-configuration "{
  \"QueueConfigurations\": [{
    \"QueueArn\": \"$queue_arn\",
    \"Events\": [\"s3:ObjectCreated:*\"],
    \"Filter\": {\"Key\": {\"FilterRules\": [
      {\"Name\": \"prefix\", \"Value\": \"raw/\"},
      {\"Name\": \"suffix\", \"Value\": \".harmonies.tsv\"}
    ]}}
  }]
}"
awslocal dynamodb create-table --table-name cadence-e2e --billing-mode PAY_PER_REQUEST \
  --attribute-definitions AttributeName=pk,AttributeType=S AttributeName=sk,AttributeType=S \
    AttributeName=gram,AttributeType=S AttributeName=gsi1sk,AttributeType=S \
  --key-schema AttributeName=pk,KeyType=HASH AttributeName=sk,KeyType=RANGE \
  --global-secondary-indexes '[{
    "IndexName": "gsi1",
    "KeySchema": [{"AttributeName": "gram", "KeyType": "HASH"}, {"AttributeName": "gsi1sk", "KeyType": "RANGE"}],
    "Projection": {"ProjectionType": "ALL"}
  }]' > /dev/null
