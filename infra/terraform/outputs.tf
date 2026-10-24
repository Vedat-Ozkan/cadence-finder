output "bucket" {
  value = aws_s3_bucket.corpus.bucket
}

output "ingest_queue_url" {
  value = aws_sqs_queue.ingest.id
}

output "ingest_dlq_url" {
  value = aws_sqs_queue.ingest_dlq.id
}

output "table" {
  value = aws_dynamodb_table.cadence.name
}

output "app_user" {
  value = local.env == "dev" ? aws_iam_user.app[0].name : null
}
