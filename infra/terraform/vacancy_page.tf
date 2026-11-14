data "archive_file" "vacancy_page" {
  count       = var.enable_vacancy_page ? 1 : 0
  type        = "zip"
  output_path = "${path.module}/.vacancy-page.zip"
  source {
    content  = file("${path.module}/../page/lambda/handler.py")
    filename = "handler.py"
  }
  source {
    content  = file("${path.module}/../page/lambda/index.html")
    filename = "index.html"
  }
  source {
    content  = file("${path.module}/../page/lambda/app.js")
    filename = "app.js"
  }
  source {
    content  = file("${path.module}/../page/lambda/style.css")
    filename = "style.css"
  }
}

resource "aws_cognito_user_pool" "page" {
  count                    = var.enable_vacancy_page ? 1 : 0
  name                     = "${var.name_prefix}-owner-page"
  user_pool_tier           = "LITE"
  mfa_configuration        = "ON"
  auto_verified_attributes = ["email"]
  username_attributes      = ["email"]
  deletion_protection      = "ACTIVE"

  software_token_mfa_configuration {
    enabled = true
  }
  admin_create_user_config {
    allow_admin_create_user_only = true
  }
  password_policy {
    minimum_length    = 14
    require_lowercase = true
    require_uppercase = true
    require_numbers   = true
    require_symbols   = true
  }
}

resource "aws_cognito_user_group" "owner" {
  count        = var.enable_vacancy_page ? 1 : 0
  name         = "job-agent-owner"
  user_pool_id = aws_cognito_user_pool.page[0].id
}

resource "aws_cognito_user_pool_domain" "page" {
  count        = var.enable_vacancy_page ? 1 : 0
  domain       = var.page_auth_domain_prefix
  user_pool_id = aws_cognito_user_pool.page[0].id
}

resource "aws_apigatewayv2_api" "page" {
  count         = var.enable_vacancy_page ? 1 : 0
  name          = "${var.name_prefix}-vacancies"
  protocol_type = "HTTP"
}

resource "aws_cognito_user_pool_client" "page" {
  count                                = var.enable_vacancy_page ? 1 : 0
  name                                 = "${var.name_prefix}-browser"
  user_pool_id                         = aws_cognito_user_pool.page[0].id
  generate_secret                      = false
  prevent_user_existence_errors        = "ENABLED"
  supported_identity_providers         = ["COGNITO"]
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid"]
  callback_urls                        = ["${aws_apigatewayv2_api.page[0].api_endpoint}/"]
  logout_urls                          = ["${aws_apigatewayv2_api.page[0].api_endpoint}/"]
  access_token_validity                = 1
  id_token_validity                    = 1
  refresh_token_validity               = 1
  token_validity_units {
    access_token  = "hours"
    id_token      = "hours"
    refresh_token = "days"
  }
}

resource "aws_cloudwatch_log_group" "vacancy_page" {
  count             = var.enable_vacancy_page ? 1 : 0
  name              = "/aws/lambda/${var.name_prefix}-vacancies"
  retention_in_days = 7
}

resource "aws_iam_role" "vacancy_page" {
  count = var.enable_vacancy_page ? 1 : 0
  name  = "${var.name_prefix}-vacancy-reader"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "lambda.amazonaws.com" } }]
  })
}

resource "aws_iam_role_policy" "vacancy_page" {
  count = var.enable_vacancy_page ? 1 : 0
  name  = "read-current-vacancies"
  role  = aws_iam_role.vacancy_page[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["rds-data:ExecuteStatement"], Resource = aws_rds_cluster.db.arn },
      { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = var.page_reader_secret_arn },
      { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.vacancy_page[0].arn}:*" }
    ]
  })
}

resource "aws_lambda_function" "vacancy_page" {
  count            = var.enable_vacancy_page ? 1 : 0
  function_name    = "${var.name_prefix}-vacancies"
  role             = aws_iam_role.vacancy_page[0].arn
  filename         = data.archive_file.vacancy_page[0].output_path
  source_code_hash = data.archive_file.vacancy_page[0].output_base64sha256
  handler          = "handler.handler"
  runtime          = "python3.12"
  timeout          = 30
  memory_size      = 128
  environment {
    variables = {
      DB_CLUSTER_ARN       = aws_rds_cluster.db.arn
      DB_READER_SECRET_ARN = var.page_reader_secret_arn
      COGNITO_CLIENT_ID    = aws_cognito_user_pool_client.page[0].id
      COGNITO_DOMAIN       = "https://${aws_cognito_user_pool_domain.page[0].domain}.auth.${var.aws_region}.amazoncognito.com"
      PAGE_URL             = "${aws_apigatewayv2_api.page[0].api_endpoint}/"
    }
  }
  depends_on = [aws_iam_role_policy.vacancy_page, aws_cloudwatch_log_group.vacancy_page]
}

resource "aws_apigatewayv2_integration" "page" {
  count                  = var.enable_vacancy_page ? 1 : 0
  api_id                 = aws_apigatewayv2_api.page[0].id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.vacancy_page[0].invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_authorizer" "page" {
  count            = var.enable_vacancy_page ? 1 : 0
  api_id           = aws_apigatewayv2_api.page[0].id
  authorizer_type  = "JWT"
  name             = "owner-pool"
  identity_sources = ["$request.header.Authorization"]
  jwt_configuration {
    audience = [aws_cognito_user_pool_client.page[0].id]
    issuer   = "https://cognito-idp.${var.aws_region}.amazonaws.com/${aws_cognito_user_pool.page[0].id}"
  }
}

resource "aws_apigatewayv2_route" "page_assets" {
  for_each  = var.enable_vacancy_page ? toset(["/", "/app.js", "/style.css", "/config"]) : toset([])
  api_id    = aws_apigatewayv2_api.page[0].id
  route_key = "GET ${each.value}"
  target    = "integrations/${aws_apigatewayv2_integration.page[0].id}"
}

resource "aws_apigatewayv2_route" "vacancies" {
  count                = var.enable_vacancy_page && var.page_access_enabled ? 1 : 0
  api_id               = aws_apigatewayv2_api.page[0].id
  route_key            = "GET /vacancies"
  target               = "integrations/${aws_apigatewayv2_integration.page[0].id}"
  authorization_type   = "JWT"
  authorizer_id        = aws_apigatewayv2_authorizer.page[0].id
  authorization_scopes = ["openid"]
}

resource "aws_apigatewayv2_route" "vacancy_yield" {
  count                = var.enable_vacancy_page && var.page_access_enabled ? 1 : 0
  api_id               = aws_apigatewayv2_api.page[0].id
  route_key            = "GET /yield"
  target               = "integrations/${aws_apigatewayv2_integration.page[0].id}"
  authorization_type   = "JWT"
  authorizer_id        = aws_apigatewayv2_authorizer.page[0].id
  authorization_scopes = ["openid"]
}

resource "aws_apigatewayv2_stage" "page" {
  count       = var.enable_vacancy_page ? 1 : 0
  api_id      = aws_apigatewayv2_api.page[0].id
  name        = "$default"
  auto_deploy = true
  default_route_settings {
    throttling_burst_limit = 10
    throttling_rate_limit  = 2
  }
}

resource "aws_lambda_permission" "page" {
  count         = var.enable_vacancy_page ? 1 : 0
  statement_id  = "AllowVacancyHttpApi"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.vacancy_page[0].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.page[0].execution_arn}/*/*"
}
