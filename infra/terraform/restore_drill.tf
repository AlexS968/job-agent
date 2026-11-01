locals {
  restore_drill_name = var.enable_restore_drill ? "${var.name_prefix}-restore-${var.restore_drill_id}" : null
}

resource "aws_security_group" "restore_verifier" {
  count                  = var.enable_restore_drill ? 1 : 0
  name_prefix            = "${local.restore_drill_name}-task-"
  description            = "Temporary restore verifier: no inbound access"
  vpc_id                 = aws_vpc.app.id
  revoke_rules_on_delete = true
  tags                   = { Name = "${local.restore_drill_name}-task", RestoreDrill = var.restore_drill_id }
}

resource "aws_security_group" "restore_db" {
  count                  = var.enable_restore_drill ? 1 : 0
  name_prefix            = "${local.restore_drill_name}-db-"
  description            = "Temporary restored Aurora: verifier-only ingress"
  vpc_id                 = aws_vpc.app.id
  revoke_rules_on_delete = true
  tags                   = { Name = "${local.restore_drill_name}-db", RestoreDrill = var.restore_drill_id }
}

resource "aws_vpc_security_group_egress_rule" "restore_https" {
  count             = var.enable_restore_drill ? 1 : 0
  security_group_id = aws_security_group.restore_verifier[0].id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "ECR, Secrets Manager and CloudWatch Logs"
}

resource "aws_vpc_security_group_egress_rule" "restore_db" {
  count                        = var.enable_restore_drill ? 1 : 0
  security_group_id            = aws_security_group.restore_verifier[0].id
  referenced_security_group_id = aws_security_group.restore_db[0].id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "Only temporary restored Aurora"
}

resource "aws_vpc_security_group_ingress_rule" "restore_verifier" {
  count                        = var.enable_restore_drill ? 1 : 0
  security_group_id            = aws_security_group.restore_db[0].id
  referenced_security_group_id = aws_security_group.restore_verifier[0].id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "Only temporary verifier task"
}

resource "aws_rds_cluster" "restore_drill" {
  count                           = var.enable_restore_drill ? 1 : 0
  cluster_identifier              = local.restore_drill_name
  engine                          = "aurora-postgresql"
  engine_mode                     = "provisioned"
  engine_version                  = aws_rds_cluster.db.engine_version
  db_subnet_group_name            = aws_db_subnet_group.db.name
  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.db.name
  vpc_security_group_ids          = [aws_security_group.restore_db[0].id]
  storage_type                    = "aurora"
  backup_retention_period         = 1
  deletion_protection             = false
  skip_final_snapshot             = true
  copy_tags_to_snapshot           = false
  tags                            = { Name = local.restore_drill_name, RestoreDrill = var.restore_drill_id, Temporary = "true" }

  restore_to_point_in_time {
    source_cluster_identifier = aws_rds_cluster.db.cluster_identifier
    restore_to_time           = var.restore_time_utc
    restore_type              = "full-copy"
  }

  serverlessv2_scaling_configuration {
    min_capacity             = 0
    max_capacity             = 2
    seconds_until_auto_pause = 300
  }
}

resource "aws_rds_cluster_instance" "restore_writer" {
  count                = var.enable_restore_drill ? 1 : 0
  identifier           = "${local.restore_drill_name}-writer"
  cluster_identifier   = aws_rds_cluster.restore_drill[0].id
  instance_class       = "db.serverless"
  engine               = aws_rds_cluster.restore_drill[0].engine
  engine_version       = aws_rds_cluster.restore_drill[0].engine_version
  db_subnet_group_name = aws_db_subnet_group.db.name
  publicly_accessible  = false
  promotion_tier       = 0
  monitoring_interval  = 0
  tags                 = { Name = "${local.restore_drill_name}-writer", RestoreDrill = var.restore_drill_id, Temporary = "true" }
}

resource "aws_ecs_task_definition" "restore_verifier" {
  count                    = var.enable_restore_drill ? 1 : 0
  family                   = "${local.restore_drill_name}-verify"
  network_mode             = "awsvpc"
  requires_compatibilities = ["FARGATE"]
  cpu                      = "256"
  memory                   = "512"
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name                   = "restore-verifier"
    image                  = "${aws_ecr_repository.app.repository_url}@${var.restore_verifier_image_digest}"
    essential              = true
    readonlyRootFilesystem = true
    environment = [
      { name = "DB_HOST", value = aws_rds_cluster.restore_drill[0].endpoint },
      { name = "DB_NAME", value = "job_agent" },
      { name = "DB_USER", value = "job_agent_app" },
      { name = "EXPECTED_AGENT_RUNS", value = tostring(var.restore_expected_agent_runs) }
    ]
    secrets          = [{ name = "DB_PASSWORD", valueFrom = "${var.app_db_secret_arn}:password::" }]
    logConfiguration = local.log_configuration
    stopTimeout      = 120
  }])
  depends_on = [aws_iam_role_policy.execution, aws_rds_cluster_instance.restore_writer]
}
