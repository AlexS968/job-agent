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
    condition     = !var.enable_runtime || (var.app_db_secret_arn != null && can(regex("^arn:aws:secretsmanager:us-east-1:[0-9]{12}:secret:", var.app_db_secret_arn)))
    error_message = "Runtime requires an existing us-east-1 application DB secret ARN."
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
