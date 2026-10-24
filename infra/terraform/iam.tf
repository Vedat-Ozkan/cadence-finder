locals {
  app_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject"]
        Resource = "${aws_s3_bucket.corpus.arn}/*"
      },
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.corpus.arn
      },
      {
        Effect = "Allow"
        Action = [
          "sqs:ReceiveMessage",
          "sqs:DeleteMessage",
          "sqs:SendMessage",
          "sqs:ChangeMessageVisibility",
          "sqs:GetQueueAttributes",
          "sqs:GetQueueUrl",
        ]
        Resource = [aws_sqs_queue.ingest.arn, aws_sqs_queue.ingest_dlq.arn]
      },
      {
        Effect = "Allow"
        Action = [
          "dynamodb:GetItem",
          "dynamodb:BatchGetItem",
          "dynamodb:BatchWriteItem",
          "dynamodb:PutItem",
          "dynamodb:Query",
        ]
        Resource = [aws_dynamodb_table.cadence.arn, "${aws_dynamodb_table.cadence.arn}/index/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["xray:PutTraceSegments", "xray:PutTelemetryRecords", "xray:GetSamplingRules", "xray:GetSamplingTargets"]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents", "logs:DescribeLogStreams"]
        Resource = ["${aws_cloudwatch_log_group.app.arn}:*", "${aws_cloudwatch_log_group.metrics.arn}:*"]
      },
    ]
  })
}

resource "aws_iam_user" "app" {
  count = local.env == "dev" ? 1 : 0
  name  = "${local.name}-app"
}

resource "aws_iam_user_policy" "app" {
  count  = local.env == "dev" ? 1 : 0
  name   = "${local.name}-app"
  user   = aws_iam_user.app[0].name
  policy = local.app_policy
}
