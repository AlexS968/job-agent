resource "aws_sns_topic" "batch_report" {
  count = var.enable_scheduled_batch ? 1 : 0
  name  = "${var.name_prefix}-batch-report"
}

resource "aws_sns_topic_subscription" "batch_report_email" {
  count     = var.enable_scheduled_batch ? 1 : 0
  topic_arn = aws_sns_topic.batch_report[0].arn
  protocol  = "email"
  endpoint  = var.batch_report_email
}

data "aws_iam_policy_document" "batch_report_topic" {
  count = var.enable_scheduled_batch ? 1 : 0
  statement {
    sid       = "AllowAccountManagement"
    actions   = ["SNS:GetTopicAttributes", "SNS:SetTopicAttributes", "SNS:DeleteTopic", "SNS:Subscribe", "SNS:ListSubscriptionsByTopic"]
    resources = [aws_sns_topic.batch_report[0].arn]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
  statement {
    sid       = "AllowBatchFailureRule"
    actions   = ["SNS:Publish"]
    resources = [aws_sns_topic.batch_report[0].arn]
    principals {
      type        = "Service"
      identifiers = ["events.amazonaws.com"]
    }
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_cloudwatch_event_rule.batch_task_failed[0].arn]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
  statement {
    sid       = "AllowLaunchFailureAlarm"
    actions   = ["SNS:Publish"]
    resources = [aws_sns_topic.batch_report[0].arn]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_cloudwatch_metric_alarm.batch_launch_failure[0].arn]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "batch_report" {
  count  = var.enable_scheduled_batch ? 1 : 0
  arn    = aws_sns_topic.batch_report[0].arn
  policy = data.aws_iam_policy_document.batch_report_topic[0].json
}

resource "aws_sqs_queue" "batch_launch_dlq" {
  count                     = var.enable_scheduled_batch ? 1 : 0
  name                      = "${var.name_prefix}-batch-launch-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

data "aws_iam_policy_document" "batch_launch_dlq" {
  count = var.enable_scheduled_batch ? 1 : 0
  statement {
    sid       = "AllowThisScheduleOnly"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.batch_launch_dlq[0].arn]
    principals {
      type        = "Service"
      identifiers = ["scheduler.amazonaws.com"]
    }
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_scheduler_schedule.batch[0].arn]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sqs_queue_policy" "batch_launch_dlq" {
  count     = var.enable_scheduled_batch ? 1 : 0
  queue_url = aws_sqs_queue.batch_launch_dlq[0].id
  policy    = data.aws_iam_policy_document.batch_launch_dlq[0].json
}

resource "aws_iam_role" "batch_scheduler" {
  count = var.enable_scheduled_batch ? 1 : 0
  name  = "${var.name_prefix}-batch-scheduler"
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

resource "aws_iam_role_policy" "batch_scheduler" {
  count = var.enable_scheduled_batch ? 1 : 0
  name  = "${var.name_prefix}-batch-launch"
  role  = aws_iam_role.batch_scheduler[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Action    = ["ecs:RunTask"]
        Resource  = aws_ecs_task_definition.batch_pipeline[0].arn
        Condition = { ArnEquals = { "ecs:cluster" = aws_ecs_cluster.batch.arn } }
      },
      {
        Effect    = "Allow"
        Action    = ["iam:PassRole"]
        Resource  = [aws_iam_role.batch_execution[0].arn, aws_iam_role.batch_pipeline[0].arn]
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      },
      {
        Effect   = "Allow"
        Action   = ["sqs:SendMessage"]
        Resource = aws_sqs_queue.batch_launch_dlq[0].arn
      }
    ]
  })
}

resource "aws_scheduler_schedule" "batch" {
  count                        = var.enable_scheduled_batch ? 1 : 0
  name                         = "${var.name_prefix}-batch-tue-thu"
  description                  = "Reviewed batch start at 19:00 Europe/Berlin on Tuesday and Thursday"
  state                        = var.enable_recurring_batch ? "ENABLED" : "DISABLED"
  schedule_expression          = "cron(0 19 ? * TUE,THU *)"
  schedule_expression_timezone = "Europe/Berlin"
  flexible_time_window { mode = "OFF" }
  target {
    arn      = aws_ecs_cluster.batch.arn
    role_arn = aws_iam_role.batch_scheduler[0].arn
    input    = jsonencode({ containerOverrides = [{ name = "job-agent", command = ["--mode=scheduled-batch"] }] })
    ecs_parameters {
      task_definition_arn = aws_ecs_task_definition.batch_pipeline[0].arn
      launch_type         = "FARGATE"
      task_count          = 1
      group               = "${var.name_prefix}-scheduled-batch"
      network_configuration {
        assign_public_ip = true
        subnets          = aws_subnet.task[*].id
        security_groups  = [aws_security_group.task.id]
      }
    }
    dead_letter_config { arn = aws_sqs_queue.batch_launch_dlq[0].arn }
    retry_policy {
      maximum_retry_attempts       = 1
      maximum_event_age_in_seconds = 300
    }
  }
  depends_on = [aws_iam_role_policy.batch_scheduler]
}

resource "aws_cloudwatch_metric_alarm" "batch_launch_failure" {
  count               = var.enable_scheduled_batch ? 1 : 0
  alarm_name          = "${var.name_prefix}-batch-launch-failed"
  alarm_description   = "Scheduled batch could not launch; inspect the dedicated launch DLQ and Scheduler target errors."
  namespace           = "AWS/SQS"
  metric_name         = "ApproximateNumberOfMessagesVisible"
  dimensions          = { QueueName = aws_sqs_queue.batch_launch_dlq[0].name }
  statistic           = "Maximum"
  period              = 60
  evaluation_periods  = 1
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "missing"
  alarm_actions       = [aws_sns_topic.batch_report[0].arn]
}

resource "aws_cloudwatch_event_rule" "batch_task_failed" {
  count       = var.enable_scheduled_batch ? 1 : 0
  name        = "${var.name_prefix}-batch-task-failed"
  description = "Stopped scheduled-batch family tasks with nonzero or missing container exit code"
  event_pattern = jsonencode({
    source        = ["aws.ecs"]
    "detail-type" = ["ECS Task State Change"]
    detail = {
      clusterArn        = [aws_ecs_cluster.batch.arn]
      taskDefinitionArn = [{ prefix = "arn:aws:ecs:${var.aws_region}:${data.aws_caller_identity.current.account_id}:task-definition/${var.name_prefix}-batch-pipeline:" }]
      group             = ["${var.name_prefix}-scheduled-batch"]
      lastStatus        = ["STOPPED"]
      containers        = { exitCode = [{ "anything-but" = 0 }, { exists = false }] }
    }
  })
}

resource "aws_cloudwatch_event_target" "batch_task_failed" {
  count      = var.enable_scheduled_batch ? 1 : 0
  rule       = aws_cloudwatch_event_rule.batch_task_failed[0].name
  arn        = aws_sns_topic.batch_report[0].arn
  input      = jsonencode("Job Agent batch ECS task stopped with a nonzero or missing exit code. Inspect this batch family's ECS task and CloudWatch logs before any retry.")
  depends_on = [aws_sns_topic_policy.batch_report]
}
