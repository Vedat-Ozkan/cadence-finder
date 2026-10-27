locals {
  search_zip = "${path.module}/../../search-lambda/build/distributions/search-lambda.zip"
}

resource "aws_iam_role" "search" {
  count = local.live
  name  = "${local.name}-search"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "search" {
  count = local.live
  name  = "${local.name}-search"
  role  = aws_iam_role.search[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["dynamodb:Query", "dynamodb:GetItem", "dynamodb:Scan"]
        Resource = [aws_dynamodb_table.cadence.arn, "${aws_dynamodb_table.cadence.arn}/index/*"]
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.search[0].arn}:*"
      }
    ]
  })
}

resource "aws_cloudwatch_log_group" "search" {
  count             = local.live
  name              = "/aws/lambda/${local.name}-search"
  retention_in_days = 7
}

resource "aws_lambda_function" "search" {
  count            = local.live
  function_name    = "${local.name}-search"
  role             = aws_iam_role.search[0].arn
  runtime          = "java25"
  architectures    = ["arm64"]
  handler          = "cadencefinder.lambda.SearchHandler::handleRequest"
  filename         = local.search_zip
  source_code_hash = filebase64sha256(local.search_zip)
  timeout          = 10
  memory_size      = 512
  publish          = true

  snap_start {
    apply_on = "PublishedVersions"
  }

  environment {
    variables = {
      CADENCE_TABLE = aws_dynamodb_table.cadence.name
    }
  }

  depends_on = [aws_cloudwatch_log_group.search]
}

resource "aws_lambda_alias" "search" {
  count            = local.live
  name             = "live"
  function_name    = aws_lambda_function.search[0].function_name
  function_version = aws_lambda_function.search[0].version
}
