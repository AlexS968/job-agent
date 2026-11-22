data "aws_caller_identity" "current" {}

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
}
