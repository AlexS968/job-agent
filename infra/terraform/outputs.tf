output "ecr_repository_url" {
  description = "Push a tagged linux/amd64 image here, then supply its immutable digest."
  value       = aws_ecr_repository.app.repository_url
}

output "vacancy_page_url" {
  description = "Public sign-in shell; data route requires Cognito access token and owner group."
  value       = var.enable_vacancy_page ? "${aws_apigatewayv2_api.page[0].api_endpoint}/" : null
}

output "database_cluster_arn" {
  description = "Cluster identifier for reviewed Data API reader bootstrap; contains no credential value."
  value       = aws_rds_cluster.db.arn
}

output "migration_role_secret_arn" {
  description = "Existing migrator secret ARN, for reviewed column grants; contains no credential value."
  value       = var.migration_db_secret_arn
}

output "ecs_cluster_name" {
  value = aws_ecs_cluster.batch.name
}

output "task_subnet_ids" {
  value = aws_subnet.task[*].id
}

output "task_security_group_id" {
  value = aws_security_group.task.id
}

output "database_endpoint" {
  value = aws_rds_cluster.db.endpoint
}

output "managed_master_secret_arn" {
  description = "For controlled bootstrap only; not for normal application tasks."
  value       = aws_rds_cluster.db.master_user_secret[0].secret_arn
}

output "private_bucket_name" {
  value = aws_s3_bucket.private.id
}

output "application_task_definition_arn" {
  value = var.enable_runtime ? aws_ecs_task_definition.app[0].arn : null
}

output "migration_task_definition_arn" {
  value = var.enable_runtime ? aws_ecs_task_definition.migrate[0].arn : null
}

output "bootstrap_task_definition_arn" {
  value = var.enable_bootstrap ? aws_ecs_task_definition.bootstrap[0].arn : null
}

output "restore_verifier_task_definition_arn" {
  value = var.enable_restore_drill ? aws_ecs_task_definition.restore_verifier[0].arn : null
}

output "restore_verifier_security_group_id" {
  value = var.enable_restore_drill ? aws_security_group.restore_verifier[0].id : null
}

output "restore_cluster_identifier" {
  value = var.enable_restore_drill ? aws_rds_cluster.restore_drill[0].cluster_identifier : null
}

output "sqs_queue_url" {
  value = var.enable_sqs ? aws_sqs_queue.vacancy[0].id : null
}

output "sqs_dlq_url" {
  value = var.enable_sqs ? aws_sqs_queue.vacancy_dlq[0].id : null
}

output "sqs_producer_task_definition_arn" {
  value = var.enable_sqs ? aws_ecs_task_definition.sqs_producer[0].arn : null
}

output "sqs_consumer_task_definition_arn" {
  value = var.enable_sqs ? aws_ecs_task_definition.sqs_consumer[0].arn : null
}

output "sqs_evaluation_task_definition_arn" {
  value = var.enable_sqs_evaluation ? aws_ecs_task_definition.sqs_evaluation[0].arn : null
}

output "batch_pipeline_task_definition_arn" {
  value = var.enable_batch_pipeline ? aws_ecs_task_definition.batch_pipeline[0].arn : null
}

output "batch_schedule_name" {
  value = var.enable_scheduled_batch ? aws_scheduler_schedule.batch[0].name : null
}

output "batch_report_topic_arn" {
  value = var.enable_scheduled_batch ? aws_sns_topic.batch_report[0].arn : null
}

output "scheduled_batch_group" {
  value = var.enable_scheduled_batch ? "${var.name_prefix}-scheduled-batch" : null
}
