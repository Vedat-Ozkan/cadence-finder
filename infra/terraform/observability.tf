resource "aws_cloudwatch_log_group" "app" {
  name              = "/cadence-finder/${local.env}/app"
  retention_in_days = 7
}

resource "aws_cloudwatch_log_group" "metrics" {
  name              = "/cadence-finder/${local.env}/metrics"
  retention_in_days = 7
}

resource "aws_sns_topic" "alarms" {
  count = local.live
  name  = "${local.name}-alarms"
}

resource "aws_sns_topic_subscription" "alarms_email" {
  count     = var.alarm_email == "" ? 0 : local.live
  topic_arn = aws_sns_topic.alarms[0].arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

resource "aws_cloudwatch_metric_alarm" "dlq" {
  count               = local.live
  alarm_name          = "${local.name}-dlq-not-empty"
  alarm_description   = "Messages in ingest-${local.env}-dlq. See docs/runbook.md#dlq-not-empty."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.ingest_dlq.name }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms[0].arn]
  ok_actions          = [aws_sns_topic.alarms[0].arn]
}

resource "aws_cloudwatch_metric_alarm" "search_p99" {
  count               = local.live
  alarm_name          = "${local.name}-search-p99"
  alarm_description   = "p99 latency of GET /search above 500 ms over at least 50 requests. See docs/runbook.md#search-p99."
  evaluation_periods  = 2
  threshold           = 0.5
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms[0].arn]
  ok_actions          = [aws_sns_topic.alarms[0].arn]

  metric_query {
    id          = "p99_with_traffic"
    expression  = "IF(requests >= 50, p99, 0)"
    label       = "p99 when at least 50 requests"
    return_data = true
  }

  metric_query {
    id = "p99"
    metric {
      namespace   = "CadenceFinder"
      metric_name = "http.server.request.duration"
      dimensions  = { "service.name" = "search-api", "http.route" = "/search" }
      stat        = "p99"
      period      = 300
    }
  }

  metric_query {
    id = "requests"
    metric {
      namespace   = "CadenceFinder"
      metric_name = "http.server.request.duration"
      dimensions  = { "service.name" = "search-api", "http.route" = "/search" }
      stat        = "SampleCount"
      period      = 300
    }
  }
}

resource "aws_cloudwatch_dashboard" "main" {
  count          = local.live
  dashboard_name = local.name
  dashboard_body = jsonencode({
    widgets = [
      {
        type   = "metric"
        x      = 0
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "GET /search latency (s)"
          region = var.region
          view   = "timeSeries"
          period = 60
          metrics = [for p in ["p50", "p95", "p99"] : [
            "CadenceFinder", "http.server.request.duration", "service.name", "search-api", "http.route", "/search",
            { stat = p, label = p }
          ]]
          annotations = { horizontal = [{ value = 0.5, label = "p99 alarm" }] }
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 0
        width  = 12
        height = 6
        properties = {
          title  = "Requests per minute by route"
          region = var.region
          view   = "timeSeries"
          period = 60
          metrics = [for route in ["/search", "/passages/{id}/similar", "/nl-search", "/reindex/{movementId}"] : [
            "CadenceFinder", "http.server.request.duration", "service.name", "search-api", "http.route", route,
            { stat = "SampleCount", label = route }
          ]]
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 6
        width  = 12
        height = 6
        properties = {
          title  = "Ingest queue and dead-letter queue"
          region = var.region
          view   = "timeSeries"
          period = 60
          metrics = [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.ingest.name, { label = "ingest visible" }],
            ["AWS/SQS", "ApproximateNumberOfMessagesNotVisible", "QueueName", aws_sqs_queue.ingest.name, { label = "ingest in flight" }],
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.ingest_dlq.name, { label = "DLQ" }],
          ]
        }
      },
      {
        type   = "metric"
        x      = 12
        y      = 6
        width  = 12
        height = 6
        properties = {
          title  = "DynamoDB consumed capacity"
          region = var.region
          view   = "timeSeries"
          period = 60
          stat   = "Sum"
          metrics = [
            ["AWS/DynamoDB", "ConsumedReadCapacityUnits", "TableName", aws_dynamodb_table.cadence.name],
            ["AWS/DynamoDB", "ConsumedWriteCapacityUnits", "TableName", aws_dynamodb_table.cadence.name],
          ]
        }
      },
      {
        type   = "metric"
        x      = 0
        y      = 12
        width  = 12
        height = 6
        properties = {
          title  = "Embedder and worker latency p99 (s)"
          region = var.region
          view   = "timeSeries"
          period = 300
          metrics = [
            ["CadenceFinder", "http.server.request.duration", "service.name", "search-api", "http.route", "/passages/{id}/similar", { stat = "p99", label = "similar (via embedder)" }],
            ["CadenceFinder", "http.server.request.duration", "service.name", "search-api", "http.route", "/nl-search", { stat = "p99", label = "nl-search (Claude)" }],
          ]
        }
      },
      {
        type   = "alarm"
        x      = 12
        y      = 12
        width  = 12
        height = 6
        properties = {
          title  = "Alarms"
          alarms = [aws_cloudwatch_metric_alarm.dlq[0].arn, aws_cloudwatch_metric_alarm.search_p99[0].arn]
        }
      },
    ]
  })
}
