resource "aws_iam_role" "gmail_cutover_execution" {
  count = var.prepare_gmail_cutover ? 1 : 0
  name  = "${var.name_prefix}-gmail-cutover-exec"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Action = "sts:AssumeRole", Principal = { Service = "ecs-tasks.amazonaws.com" } }]
  })
}

resource "aws_iam_role_policy" "gmail_cutover_execution" {
  count = var.prepare_gmail_cutover ? 1 : 0
  role  = aws_iam_role.gmail_cutover_execution[0].id
  name  = "${var.name_prefix}-linkedin-pull-logs-secrets"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = aws_ecr_repository.app.arn },
      { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.task.arn}:*" },
      { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = [var.app_db_secret_arn, var.gmail_modify_secret_arn] }
    ]
  })
}

resource "aws_ecs_task_definition" "gmail_cutover" {
  count                    = var.prepare_gmail_cutover ? 1 : 0
  family                   = "${var.name_prefix}-gmail-cutover"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.gmail_cutover_execution[0].arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = "job-agent"
    image     = "${aws_ecr_repository.app.repository_url}@${var.image_digest}"
    essential = true
    command   = ["--mode=gmail-cutover"]
    environment = concat(local.common_environment, [
      { name = "JOBAGENT_GMAIL_CUTOVER", value = "true" },
      { name = "JOBAGENT_GMAIL_OVERLAP_SECONDS", value = tostring(var.gmail_overlap_seconds) },
      { name = "DB_USER", value = "job_agent_app" },
      { name = "SPRING_PROFILES_ACTIVE", value = "cloud-app" }
    ])
    secrets = [
      { name = "DB_PASSWORD", valueFrom = "${var.app_db_secret_arn}:password::" },
      { name = "JOBAGENT_GMAIL_OAUTH_JSON", valueFrom = var.gmail_modify_secret_arn }
    ]
    logConfiguration = local.log_configuration
  }])
  depends_on = [aws_iam_role_policy.gmail_cutover_execution]
}

resource "aws_iam_role" "gmail_scheduler" {
  count = var.prepare_gmail_cutover ? 1 : 0
  name  = "${var.name_prefix}-gmail-scheduler"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "scheduler.amazonaws.com" }
      Condition = { StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id } }
    }]
  })
}

resource "aws_iam_role_policy" "gmail_scheduler" {
  count = var.prepare_gmail_cutover ? 1 : 0
  name  = "${var.name_prefix}-gmail-launch"
  role  = aws_iam_role.gmail_scheduler[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Action    = ["ecs:RunTask"]
        Resource  = aws_ecs_task_definition.gmail_cutover[0].arn
        Condition = { ArnEquals = { "ecs:cluster" = aws_ecs_cluster.batch.arn } }
      },
      {
        Effect    = "Allow"
        Action    = ["iam:PassRole"]
        Resource  = [aws_iam_role.gmail_cutover_execution[0].arn, aws_iam_role.task.arn]
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      }
    ]
  })
}

resource "aws_scheduler_schedule" "gmail_cutover" {
  count                        = var.prepare_gmail_cutover ? 1 : 0
  name                         = "${var.name_prefix}-gmail-hourly"
  description                  = "Retired standalone schedule; use the unified weekday collection"
  state                        = "DISABLED"
  schedule_expression          = "cron(0 18 * * ? *)"
  schedule_expression_timezone = "Europe/Berlin"
  flexible_time_window { mode = "OFF" }
  target {
    arn      = aws_ecs_cluster.batch.arn
    role_arn = aws_iam_role.gmail_scheduler[0].arn
    input    = jsonencode({ containerOverrides = [{ name = "job-agent", command = ["--mode=gmail-cutover"] }] })
    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.gmail_cutover[0].arn
      launch_type         = "FARGATE"
      task_count          = 1
      group               = "${var.name_prefix}-gmail-cutover"
      network_configuration {
        assign_public_ip = true
        subnets          = aws_subnet.task[*].id
        security_groups  = [aws_security_group.task.id]
      }
    }
    retry_policy {
      maximum_retry_attempts       = 1
      maximum_event_age_in_seconds = 300
    }
  }
  depends_on = [aws_iam_role_policy.gmail_scheduler]
}
