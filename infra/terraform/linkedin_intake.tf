resource "aws_iam_role" "linkedin_intake_execution" {
  count = var.enable_linkedin_intake ? 1 : 0
  name  = "${var.name_prefix}-linkedin-execution"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ecs-tasks.amazonaws.com" } }]
  })
}

resource "aws_iam_role_policy" "linkedin_intake_execution" {
  count = var.enable_linkedin_intake ? 1 : 0
  role  = aws_iam_role.linkedin_intake_execution[0].id
  name  = "${var.name_prefix}-linkedin-pull-logs-secrets"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = aws_ecr_repository.app.arn },
      { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.task.arn}:*" },
      { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [var.app_db_secret_arn, var.gmail_oauth_secret_arn] }
    ]
  })
}

resource "aws_ecs_task_definition" "linkedin_intake" {
  count                    = var.enable_linkedin_intake ? 1 : 0
  family                   = "${var.name_prefix}-linkedin-intake"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.linkedin_intake_execution[0].arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = "job-agent"
    image     = "${aws_ecr_repository.app.repository_url}@${var.image_digest}"
    essential = true
    command   = ["--mode=linkedin-intake", "--max-messages=10"]
    environment = concat(local.common_environment, [
      { name = "DB_USER", value = "job_agent_app" },
      { name = "SPRING_PROFILES_ACTIVE", value = "cloud-app" }
    ])
    secrets = [
      { name = "DB_PASSWORD", valueFrom = "${var.app_db_secret_arn}:password::" },
      { name = "JOBAGENT_GMAIL_OAUTH_JSON", valueFrom = var.gmail_oauth_secret_arn }
    ]
    logConfiguration = local.log_configuration
  }])
  depends_on = [aws_iam_role_policy.linkedin_intake_execution]
}
