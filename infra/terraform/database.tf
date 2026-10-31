# Account-backed planning fails if this region cannot order the selected combination.
data "aws_rds_orderable_db_instance" "serverless" {
  engine                 = "aurora-postgresql"
  engine_version         = "17.11"
  instance_class         = "db.serverless"
  storage_type           = "aurora"
  supported_engine_modes = ["provisioned"]
  vpc                    = true
}

resource "aws_db_subnet_group" "db" {
  name       = "${var.name_prefix}-private"
  subnet_ids = aws_subnet.db[*].id
  tags       = { Name = "${var.name_prefix}-private-db" }
}

resource "aws_rds_cluster_parameter_group" "db" {
  name   = "${var.name_prefix}-pg17"
  family = "aurora-postgresql17"

  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }
}

resource "aws_rds_cluster" "db" {
  cluster_identifier              = "${var.name_prefix}-db"
  engine                          = "aurora-postgresql"
  engine_mode                     = "provisioned"
  engine_version                  = "17.11"
  database_name                   = "job_agent"
  master_username                 = "job_agent_admin"
  manage_master_user_password     = true
  db_subnet_group_name            = aws_db_subnet_group.db.name
  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.db.name
  vpc_security_group_ids          = [aws_security_group.db.id]
  storage_encrypted               = true
  storage_type                    = "aurora"
  backup_retention_period         = 7
  preferred_backup_window         = "08:00-09:00"
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${var.name_prefix}-final"
  copy_tags_to_snapshot           = true
  apply_immediately               = false

  serverlessv2_scaling_configuration {
    min_capacity             = 0
    max_capacity             = 2
    seconds_until_auto_pause = 300
  }

  lifecycle {
    prevent_destroy = true
    precondition {
      condition     = alltrue([for az in var.availability_zones : contains(data.aws_rds_orderable_db_instance.serverless.availability_zones, az)])
      error_message = "Selected AZs must support Aurora PostgreSQL 17.11 db.serverless in us-east-1."
    }
  }
}

resource "aws_rds_cluster_instance" "writer" {
  identifier           = "${var.name_prefix}-writer"
  cluster_identifier   = aws_rds_cluster.db.id
  instance_class       = "db.serverless"
  engine               = aws_rds_cluster.db.engine
  engine_version       = aws_rds_cluster.db.engine_version
  db_subnet_group_name = aws_db_subnet_group.db.name
  publicly_accessible  = false
  promotion_tier       = 0
  monitoring_interval  = 0

  lifecycle {
    prevent_destroy = true
  }
}
