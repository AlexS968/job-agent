output "ecr_repository_url" {
  description = "Push a tagged linux/amd64 image here, then supply its immutable digest."
  value       = aws_ecr_repository.app.repository_url
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
