locals {
  execution_secret_arns = [for arn in [var.app_db_secret_arn, var.migration_db_secret_arn] : arn if arn != null && arn != ""]
  db_url                = "jdbc:postgresql://${aws_rds_cluster.db.endpoint}:5432/job_agent?sslmode=verify-full&sslrootcert=/opt/job-agent/certs/us-east-1-bundle.pem&connectTimeout=45&socketTimeout=120"
  common_environment = [
    { name = "DB_URL", value = local.db_url },
    { name = "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT", value = "60000" },
    { name = "SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE", value = "2" }
  ]
  log_configuration = {
    logDriver = "awslogs"
    options = {
      awslogs-group         = aws_cloudwatch_log_group.task.name
      awslogs-region        = var.aws_region
      awslogs-stream-prefix = "batch"
    }
  }
}

resource "aws_cloudwatch_log_group" "task" {
  name              = "/ecs/${var.name_prefix}"
  retention_in_days = 14
}

resource "aws_ecs_cluster" "batch" {
  name = "${var.name_prefix}-batch"
  setting {
    name  = "containerInsights"
    value = "disabled"
  }
}

resource "aws_iam_role" "execution" {
  name = "${var.name_prefix}-execution"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "execution" {
  name = "${var.name_prefix}-pull-logs-secrets"
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
        Resource = aws_ecr_repository.app.arn
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.task.arn}:*"
      }
      ], length(local.execution_secret_arns) == 0 ? [] : [{
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = local.execution_secret_arns
    }])
  })
}

resource "aws_iam_role" "task" {
  name = "${var.name_prefix}-task"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

resource "aws_ecs_task_definition" "migrate" {
  count                    = var.enable_runtime ? 1 : 0
  family                   = "${var.name_prefix}-migrate"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = "job-agent"
    image     = "${aws_ecr_repository.app.repository_url}@${var.image_digest}"
    essential = true
    environment = concat(local.common_environment, [
      { name = "DB_USER", value = "job_agent_migrator" }
    ])
    secrets          = [{ name = "DB_PASSWORD", valueFrom = "${var.migration_db_secret_arn}:password::" }]
    logConfiguration = local.log_configuration
  }])
  depends_on = [aws_iam_role_policy.execution]
}

resource "aws_ecs_task_definition" "app" {
  count                    = var.enable_runtime ? 1 : 0
  family                   = "${var.name_prefix}-app"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = "job-agent"
    image     = "${aws_ecr_repository.app.repository_url}@${var.image_digest}"
    essential = true
    environment = concat(local.common_environment, [
      { name = "DB_USER", value = "job_agent_app" },
      { name = "SPRING_PROFILES_ACTIVE", value = "cloud-app" }
    ])
    secrets          = [{ name = "DB_PASSWORD", valueFrom = "${var.app_db_secret_arn}:password::" }]
    logConfiguration = local.log_configuration
  }])
  depends_on = [aws_iam_role_policy.execution]
}
