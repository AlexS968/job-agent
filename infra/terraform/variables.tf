variable "aws_region" {
  description = "Approved deployment region."
  type        = string
  default     = "us-east-1"
  validation {
    condition     = var.aws_region == "us-east-1"
    error_message = "Phase 5A is reviewed only for us-east-1."
  }
}

variable "availability_zones" {
  description = "Two account-mapped us-east-1 AZ names that support Aurora PostgreSQL 17.11 db.serverless."
  type        = list(string)
  validation {
    condition     = length(var.availability_zones) == 2 && length(distinct(var.availability_zones)) == 2 && alltrue([for az in var.availability_zones : startswith(az, "us-east-1")])
    error_message = "Supply two distinct supported us-east-1 availability zones."
  }
}

variable "name_prefix" {
  description = "Short deployment name; must not identify a person."
  type        = string
  default     = "job-agent-p5a"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,20}$", var.name_prefix))
    error_message = "Use a short lowercase resource prefix."
  }
}

variable "aurora_activity_alert_email" {
  description = "Confirmed owner email for Aurora prolonged-activity notifications. Supply only through an ignored local tfvars file."
  type        = string
  sensitive   = true
  validation {
    condition     = can(regex("^[^[:space:]@]+@[^[:space:]@]+\\.[^[:space:]@]+$", var.aurora_activity_alert_email))
    error_message = "Provide one valid email address in an ignored local tfvars file."
  }
}

variable "enable_runtime" {
  description = "Create one-off ECS task definitions only after image, database roles and secrets are ready."
  type        = bool
  default     = false
}

variable "enable_bootstrap" {
  description = "Temporarily register the one-off DB bootstrap task and narrowly scoped IAM role; disable after grant finalization."
  type        = bool
  default     = false
}

variable "bootstrap_image_digest" {
  description = "Reviewed linux/amd64 bootstrap image manifest digest in the existing ECR repository."
  type        = string
  default     = null
  validation {
    condition     = !var.enable_bootstrap || (var.bootstrap_image_digest != null && can(regex("^sha256:[0-9a-f]{64}$", var.bootstrap_image_digest)))
    error_message = "Bootstrap requires a reviewed ECR sha256 image digest."
  }
}

variable "image_digest" {
  description = "Immutable linux/amd64 ECR image digest, including sha256: prefix."
  type        = string
  default     = null
  validation {
    condition     = !var.enable_runtime || (var.image_digest != null && can(regex("^sha256:[0-9a-f]{64}$", var.image_digest)))
    error_message = "Runtime requires a reviewed ECR sha256 image digest."
  }
}

variable "app_db_secret_arn" {
  description = "ARN of an existing Secrets Manager JSON secret with password for job_agent_app. No value enters Terraform."
  type        = string
  default     = null
  validation {
    condition     = !(var.enable_runtime || var.enable_restore_drill) || (var.app_db_secret_arn != null && can(regex("^arn:aws:secretsmanager:us-east-1:[0-9]{12}:secret:", var.app_db_secret_arn)))
    error_message = "Runtime or restore drill requires an existing us-east-1 application DB secret ARN."
  }
}

variable "migration_db_secret_arn" {
  description = "ARN of an existing Secrets Manager JSON secret with password for job_agent_migrator."
  type        = string
  default     = null
  validation {
    condition     = !var.enable_runtime || (var.migration_db_secret_arn != null && can(regex("^arn:aws:secretsmanager:us-east-1:[0-9]{12}:secret:", var.migration_db_secret_arn)))
    error_message = "Runtime requires an existing us-east-1 migration DB secret ARN."
  }
}

variable "enable_restore_drill" {
  description = "Temporarily restore a separate cluster for a reviewed, time-bounded PITR drill."
  type        = bool
  default     = false
}

variable "enable_sqs" {
  description = "Register the gated standard queue, DLQ and one-off producer/consumer tasks after review."
  type        = bool
  default     = false
  validation {
    condition     = !var.enable_sqs || var.enable_runtime
    error_message = "SQS tasks require the reviewed runtime image and application DB secret."
  }
}

variable "enable_sqs_evaluation" {
  description = "Register a separate one-off rules-v1 evaluation consumer for reviewed use."
  type        = bool
  default     = false
  validation {
    condition     = !var.enable_sqs_evaluation || var.enable_sqs
    error_message = "SQS evaluation requires the deployed SQS queue and runtime."
  }
}

variable "enable_batch_pipeline" {
  description = "Register one bounded import, SQS delivery, evaluation and shortlist task after review."
  type        = bool
  default     = false
  validation {
    condition     = !var.enable_batch_pipeline || var.enable_sqs_evaluation
    error_message = "Batch pipeline requires the reviewed SQS evaluation task and profile secret."
  }
}

variable "enable_scheduled_batch" {
  description = "Prepare report SNS, failure alerts and a disabled twice-weekly schedule. Does not enable recurring execution."
  type        = bool
  default     = false
  validation {
    condition     = !var.enable_scheduled_batch || var.enable_batch_pipeline
    error_message = "Scheduled batch preparation requires the existing batch task."
  }
}

variable "enable_recurring_batch" {
  description = "Explicit owner gate after email acceptance and an agreed private selection profile."
  type        = bool
  default     = false
  validation {
    condition     = !var.enable_recurring_batch || var.enable_scheduled_batch
    error_message = "Recurring execution requires prepared schedule resources."
  }
}

variable "batch_report_email" {
  description = "Owner email for the separate batch report/failure topic; supply only through ignored local tfvars."
  type        = string
  sensitive   = true
  default     = null
  validation {
    condition = !var.enable_scheduled_batch || (var.batch_report_email != null &&
    can(regex("^[^[:space:]@]+@[^[:space:]@]+\\.[^[:space:]@]+$", var.batch_report_email)))
    error_message = "Prepared batch reporting requires a valid email in ignored local tfvars."
  }
}

variable "evaluation_profile_secret_arn" {
  description = "ARN of an existing private Secrets Manager secret containing only the profile JSON."
  type        = string
  default     = null
  validation {
    condition = !var.enable_sqs_evaluation || (var.evaluation_profile_secret_arn != null &&
    can(regex("^arn:aws:secretsmanager:us-east-1:[0-9]{12}:secret:", var.evaluation_profile_secret_arn)))
    error_message = "SQS evaluation requires a reviewed us-east-1 profile secret ARN."
  }
}

variable "restore_drill_id" {
  description = "Unique lowercase identifier for one restore drill; never reuse it."
  type        = string
  default     = null
  validation {
    condition     = !var.enable_restore_drill || (var.restore_drill_id != null && can(regex("^[a-z][a-z0-9]{3,11}$", var.restore_drill_id)))
    error_message = "Enabled restore drill requires a unique 4-12 character lowercase alphanumeric ID."
  }
}

variable "restore_time_utc" {
  description = "Explicit UTC point within the source cluster's observed restorable interval."
  type        = string
  default     = null
  validation {
    condition     = !var.enable_restore_drill || (var.restore_time_utc != null && can(regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}Z$", var.restore_time_utc)))
    error_message = "Enabled restore drill requires an explicit UTC timestamp such as 2026-11-01T12:00:00Z."
  }
}

variable "restore_verifier_image_digest" {
  description = "Reviewed linux/amd64 digest of the isolated read-only verifier image."
  type        = string
  default     = null
  validation {
    condition     = !var.enable_restore_drill || (var.restore_verifier_image_digest != null && can(regex("^sha256:[0-9a-f]{64}$", var.restore_verifier_image_digest)))
    error_message = "Enabled restore drill requires a reviewed verifier image digest."
  }
}

variable "restore_expected_agent_runs" {
  description = "Read-only source reference count recorded immediately before the selected restore point."
  type        = number
  default     = null
  validation {
    condition     = !var.enable_restore_drill || (var.restore_expected_agent_runs != null && var.restore_expected_agent_runs >= 0 && floor(var.restore_expected_agent_runs) == var.restore_expected_agent_runs)
    error_message = "Enabled restore drill requires a nonnegative integer source reference count."
  }
}
