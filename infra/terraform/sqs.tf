resource "aws_sqs_queue" "vacancy_dlq" {
  count                     = var.enable_sqs ? 1 : 0
  name                      = "${var.name_prefix}-vacancy-dlq"
  message_retention_seconds = 604800
  sqs_managed_sse_enabled   = true
  tags                      = { Name = "${var.name_prefix}-vacancy-dlq" }
}

resource "aws_sqs_queue" "vacancy" {
  count                      = var.enable_sqs ? 1 : 0
  name                       = "${var.name_prefix}-vacancy"
  message_retention_seconds  = 345600
  visibility_timeout_seconds = 120
  receive_wait_time_seconds  = 10
  sqs_managed_sse_enabled    = true
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.vacancy_dlq[0].arn
    maxReceiveCount     = 3
  })
  tags = { Name = "${var.name_prefix}-vacancy" }
}

resource "aws_sqs_queue_redrive_allow_policy" "vacancy" {
  count     = var.enable_sqs ? 1 : 0
  queue_url = aws_sqs_queue.vacancy_dlq[0].id
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.vacancy[0].arn]
  })
}

resource "aws_sqs_queue_policy" "vacancy_tls" {
  count     = var.enable_sqs ? 1 : 0
  queue_url = aws_sqs_queue.vacancy[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "sqs:*"
      Resource  = aws_sqs_queue.vacancy[0].arn
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}

resource "aws_sqs_queue_policy" "vacancy_dlq_tls" {
  count     = var.enable_sqs ? 1 : 0
  queue_url = aws_sqs_queue.vacancy_dlq[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "sqs:*"
      Resource  = aws_sqs_queue.vacancy_dlq[0].arn
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }]
  })
}

resource "aws_iam_role" "sqs_producer" {
  count = var.enable_sqs ? 1 : 0
  name  = "${var.name_prefix}-sqs-producer"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRole"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy" "sqs_producer" {
  count = var.enable_sqs ? 1 : 0
  name  = "${var.name_prefix}-send-vacancy"
  role  = aws_iam_role.sqs_producer[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sqs:SendMessage"]
      Resource = aws_sqs_queue.vacancy[0].arn
    }]
  })
}

resource "aws_iam_role" "sqs_consumer" {
  count              = var.enable_sqs ? 1 : 0
  name               = "${var.name_prefix}-sqs-consumer"
  assume_role_policy = aws_iam_role.sqs_producer[0].assume_role_policy
}

resource "aws_iam_role_policy" "sqs_consumer" {
  count = var.enable_sqs ? 1 : 0
  name  = "${var.name_prefix}-receive-vacancy"
  role  = aws_iam_role.sqs_consumer[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage"]
      Resource = aws_sqs_queue.vacancy[0].arn
    }]
  })
}

resource "aws_ecs_task_definition" "sqs_producer" {
  count                    = var.enable_sqs ? 1 : 0
  family                   = "${var.name_prefix}-sqs-produce"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.sqs_producer[0].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = "job-agent"
    image     = "${aws_ecr_repository.app.repository_url}@${var.image_digest}"
    essential = true
    command   = ["--mode=sqs-produce", "--limit=20"]
    environment = concat(local.common_environment, [
      { name = "DB_USER", value = "job_agent_app" },
      { name = "SPRING_PROFILES_ACTIVE", value = "cloud-app" },
      { name = "JOBAGENT_SQS_ENABLED", value = "true" },
      { name = "JOBAGENT_SQS_QUEUEURL", value = aws_sqs_queue.vacancy[0].id }
    ])
    secrets          = [{ name = "DB_PASSWORD", valueFrom = "${var.app_db_secret_arn}:password::" }]
    logConfiguration = local.log_configuration
    stopTimeout      = 120
  }])
  depends_on = [aws_iam_role_policy.execution, aws_iam_role_policy.sqs_producer]
}

resource "aws_ecs_task_definition" "sqs_consumer" {
  count                    = var.enable_sqs ? 1 : 0
  family                   = "${var.name_prefix}-sqs-consume"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.sqs_consumer[0].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = "job-agent"
    image     = "${aws_ecr_repository.app.repository_url}@${var.image_digest}"
    essential = true
    command   = ["--mode=sqs-consume", "--limit=20"]
    environment = concat(local.common_environment, [
      { name = "DB_USER", value = "job_agent_app" },
      { name = "SPRING_PROFILES_ACTIVE", value = "cloud-app" },
      { name = "JOBAGENT_SQS_ENABLED", value = "true" },
      { name = "JOBAGENT_SQS_QUEUEURL", value = aws_sqs_queue.vacancy[0].id }
    ])
    secrets          = [{ name = "DB_PASSWORD", valueFrom = "${var.app_db_secret_arn}:password::" }]
    logConfiguration = local.log_configuration
    stopTimeout      = 120
  }])
  depends_on = [aws_iam_role_policy.execution, aws_iam_role_policy.sqs_consumer]
}

resource "aws_iam_role" "sqs_evaluation_execution" {
  count              = var.enable_sqs_evaluation ? 1 : 0
  name               = "${var.name_prefix}-sqs-evaluation-execution"
  assume_role_policy = aws_iam_role.execution.assume_role_policy
}

resource "aws_iam_role_policy" "sqs_evaluation_execution" {
  count = var.enable_sqs_evaluation ? 1 : 0
  name  = "${var.name_prefix}-sqs-evaluation-pull-logs-secrets"
  role  = aws_iam_role.sqs_evaluation_execution[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*"
      },
      {
        Effect   = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
        Resource = aws_ecr_repository.app.arn
      },
      {
        Effect   = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "${aws_cloudwatch_log_group.task.arn}:*"
      },
      {
        Effect   = "Allow", Action = ["secretsmanager:GetSecretValue"]
        Resource = [var.app_db_secret_arn, var.evaluation_profile_secret_arn]
      }
    ]
  })
}

resource "aws_ecs_task_definition" "sqs_evaluation" {
  count                    = var.enable_sqs_evaluation ? 1 : 0
  family                   = "${var.name_prefix}-sqs-evaluate"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.sqs_evaluation_execution[0].arn
  task_role_arn            = aws_iam_role.sqs_consumer[0].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = "job-agent"
    image     = "${aws_ecr_repository.app.repository_url}@${var.image_digest}"
    essential = true
    command   = ["--mode=sqs-evaluate", "--limit=1"]
    environment = concat(local.common_environment, [
      { name = "DB_USER", value = "job_agent_app" },
      { name = "SPRING_PROFILES_ACTIVE", value = "cloud-app" },
      { name = "JOBAGENT_SQS_ENABLED", value = "true" },
      { name = "JOBAGENT_SQS_QUEUEURL", value = aws_sqs_queue.vacancy[0].id }
    ])
    secrets = [
      { name = "DB_PASSWORD", valueFrom = "${var.app_db_secret_arn}:password::" },
      { name = "JOBAGENT_EVALUATION_PROFILE_JSON", valueFrom = var.evaluation_profile_secret_arn }
    ]
    logConfiguration = local.log_configuration
    stopTimeout      = 120
  }])
  depends_on = [aws_iam_role_policy.sqs_evaluation_execution, aws_iam_role_policy.sqs_consumer]
}

resource "aws_iam_role" "batch_pipeline" {
  count              = var.enable_batch_pipeline ? 1 : 0
  name               = "${var.name_prefix}-batch-pipeline"
  assume_role_policy = aws_iam_role.execution.assume_role_policy
}

resource "aws_iam_role_policy" "batch_pipeline" {
  count = var.enable_batch_pipeline ? 1 : 0
  name  = "${var.name_prefix}-batch-sqs"
  role  = aws_iam_role.batch_pipeline[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sqs:SendMessage", "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
      Resource = aws_sqs_queue.vacancy[0].arn
    }]
  })
}

resource "aws_iam_role_policy" "batch_report_publish" {
  count = var.enable_scheduled_batch ? 1 : 0
  name  = "${var.name_prefix}-batch-report-publish"
  role  = aws_iam_role.batch_pipeline[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["sns:Publish"]
      Resource = aws_sns_topic.batch_report[0].arn
    }]
  })
}

resource "aws_ecs_task_definition" "batch_pipeline" {
  count                    = var.enable_batch_pipeline ? 1 : 0
  family                   = "${var.name_prefix}-batch-pipeline"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "1024"
  memory                   = "2048"
  execution_role_arn       = aws_iam_role.sqs_evaluation_execution[0].arn
  task_role_arn            = aws_iam_role.batch_pipeline[0].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name      = "job-agent"
    image     = "${aws_ecr_repository.app.repository_url}@${var.image_digest}"
    essential = true
    command   = ["--mode=batch-pipeline", "--max-events=50", "--duration-seconds=900", "--shortlist-limit=20"]
    environment = concat(local.common_environment, [
      { name = "DB_USER", value = "job_agent_app" },
      { name = "SPRING_PROFILES_ACTIVE", value = "cloud-app" },
      { name = "JOBAGENT_SQS_ENABLED", value = "true" },
      { name = "JOBAGENT_SQS_QUEUEURL", value = aws_sqs_queue.vacancy[0].id }
      ], var.enable_scheduled_batch ? [
      { name = "JOBAGENT_REPORT_ENABLED", value = "true" },
      { name = "JOBAGENT_REPORT_TOPICARN", value = aws_sns_topic.batch_report[0].arn }
    ] : [])
    secrets = [
      { name = "DB_PASSWORD", valueFrom = "${var.app_db_secret_arn}:password::" },
      { name = "JOBAGENT_EVALUATION_PROFILE_JSON", valueFrom = var.evaluation_profile_secret_arn }
    ]
    logConfiguration = local.log_configuration
    stopTimeout      = 120
  }])
  depends_on = [aws_iam_role_policy.sqs_evaluation_execution, aws_iam_role_policy.batch_pipeline,
  aws_iam_role_policy.batch_report_publish]
}
