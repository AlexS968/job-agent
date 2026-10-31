locals {
  bootstrap_migration_secret_name = "${var.name_prefix}-migration-db"
  bootstrap_app_secret_name       = "${var.name_prefix}-app-db"
  bootstrap_role_secret_arns = [
    "arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:${local.bootstrap_migration_secret_name}-??????",
    "arn:aws:secretsmanager:${var.aws_region}:${data.aws_caller_identity.current.account_id}:secret:${local.bootstrap_app_secret_name}-??????"
  ]
}

resource "aws_iam_role" "bootstrap" {
  count = var.enable_bootstrap ? 1 : 0
  name  = "${var.name_prefix}-bootstrap"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "bootstrap" {
  count = var.enable_bootstrap ? 1 : 0
  name  = "${var.name_prefix}-bootstrap-secrets"
  role  = aws_iam_role.bootstrap[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = concat([aws_rds_cluster.db.master_user_secret[0].secret_arn], local.bootstrap_role_secret_arns)
      },
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:DescribeSecret", "secretsmanager:CreateSecret"]
        Resource = local.bootstrap_role_secret_arns
      }
    ]
  })
}

resource "aws_ecs_task_definition" "bootstrap" {
  count                    = var.enable_bootstrap ? 1 : 0
  family                   = "${var.name_prefix}-bootstrap"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.bootstrap[0].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name                   = "bootstrap"
    image                  = "${aws_ecr_repository.app.repository_url}@${var.bootstrap_image_digest}"
    essential              = true
    command                = ["prepare"]
    readonlyRootFilesystem = true
    environment = [
      { name = "AWS_REGION", value = var.aws_region },
      { name = "DB_HOST", value = aws_rds_cluster.db.endpoint },
      { name = "MASTER_SECRET_ARN", value = aws_rds_cluster.db.master_user_secret[0].secret_arn },
      { name = "MIGRATION_SECRET_NAME", value = local.bootstrap_migration_secret_name },
      { name = "APP_SECRET_NAME", value = local.bootstrap_app_secret_name }
    ]
    logConfiguration = local.log_configuration
    stopTimeout      = 120
  }])
  depends_on = [aws_iam_role_policy.bootstrap, aws_iam_role_policy.execution]
}
