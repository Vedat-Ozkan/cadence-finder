data "archive_file" "wake" {
  type        = "zip"
  source_file = "${path.module}/../../wake/handler.py"
  output_path = "${path.module}/build/wake.zip"
}

resource "aws_iam_role" "wake" {
  count = local.live
  name  = "${local.name}-wake"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "wake" {
  count = local.live
  name  = "${local.name}-wake"
  role  = aws_iam_role.wake[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "ec2:StartInstances"
        Resource = aws_instance.node[0].arn
      },
      {
        Effect   = "Allow"
        Action   = "ec2:DescribeInstances"
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = "lambda:InvokeFunction"
        Resource = aws_lambda_alias.search[0].arn
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.wake[0].arn}:*"
      }
    ]
  })
}

resource "aws_cloudwatch_log_group" "wake" {
  count             = local.live
  name              = "/aws/lambda/${local.name}-wake"
  retention_in_days = 7
}

resource "aws_lambda_function" "wake" {
  count            = local.live
  function_name    = "${local.name}-wake"
  role             = aws_iam_role.wake[0].arn
  runtime          = "python3.14"
  architectures    = ["arm64"]
  handler          = "handler.handler"
  filename         = data.archive_file.wake.output_path
  source_code_hash = data.archive_file.wake.output_base64sha256
  timeout          = 10
  memory_size      = 128

  environment {
    variables = {
      INSTANCE_ID     = aws_instance.node[0].id
      APP_URL         = "https://${var.duckdns_domain}.duckdns.org"
      SEARCH_FUNCTION = aws_lambda_alias.search[0].arn
    }
  }

  depends_on = [aws_cloudwatch_log_group.wake]
}

resource "aws_lambda_function_url" "wake" {
  count              = local.live
  function_name      = aws_lambda_function.wake[0].function_name
  authorization_type = "NONE"
}

resource "aws_lambda_permission" "wake_url" {
  count                  = local.live
  statement_id           = "FunctionURLAllowPublicAccess"
  action                 = "lambda:InvokeFunctionUrl"
  function_name          = aws_lambda_function.wake[0].function_name
  principal              = "*"
  function_url_auth_type = "NONE"
}

resource "aws_lambda_permission" "wake_invoke" {
  count                    = local.live
  statement_id             = "FunctionURLAllowInvokeAction"
  action                   = "lambda:InvokeFunction"
  function_name            = aws_lambda_function.wake[0].function_name
  principal                = "*"
  invoked_via_function_url = true
}
