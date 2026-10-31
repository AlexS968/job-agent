data "aws_caller_identity" "current" {}

resource "aws_sns_topic" "aurora_activity" {
  name = "${var.name_prefix}-aurora-activity"
}

data "aws_iam_policy_document" "aurora_activity_topic" {
  statement {
    sid = "AllowAccountTopicManagement"
    actions = [
      "SNS:GetTopicAttributes",
      "SNS:SetTopicAttributes",
      "SNS:DeleteTopic",
      "SNS:Subscribe",
      "SNS:ListSubscriptionsByTopic"
    ]
    resources = [aws_sns_topic.aurora_activity.arn]
    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }

  statement {
    sid       = "AllowThisAlarmToPublish"
    actions   = ["SNS:Publish"]
    resources = [aws_sns_topic.aurora_activity.arn]
    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }
    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [aws_cloudwatch_metric_alarm.aurora_writer_prolonged_activity.arn]
    }
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "aurora_activity" {
  arn    = aws_sns_topic.aurora_activity.arn
  policy = data.aws_iam_policy_document.aurora_activity_topic.json
}

resource "aws_sns_topic_subscription" "aurora_activity_email" {
  topic_arn = aws_sns_topic.aurora_activity.arn
  protocol  = "email"
  endpoint  = var.aurora_activity_alert_email
}

resource "aws_cloudwatch_metric_alarm" "aurora_writer_prolonged_activity" {
  alarm_name          = "${var.name_prefix}-writer-prolonged-activity"
  alarm_description   = "Aurora writer capacity remained above zero for approximately one hour; investigate possible prolonged activity and cost."
  namespace           = "AWS/RDS"
  metric_name         = "ServerlessDatabaseCapacity"
  dimensions          = { DBInstanceIdentifier = aws_rds_cluster_instance.writer.identifier }
  statistic           = "Minimum"
  period              = 300
  evaluation_periods  = 12
  datapoints_to_alarm = 12
  comparison_operator = "GreaterThanThreshold"
  threshold           = 0
  treat_missing_data  = "missing"
  alarm_actions       = [aws_sns_topic.aurora_activity.arn]
  ok_actions          = [aws_sns_topic.aurora_activity.arn]
}
