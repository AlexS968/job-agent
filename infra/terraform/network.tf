resource "aws_vpc" "app" {
  cidr_block           = "10.42.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags                 = { Name = "${var.name_prefix}-vpc" }
}

resource "aws_internet_gateway" "app" {
  vpc_id = aws_vpc.app.id
  tags   = { Name = "${var.name_prefix}-igw" }
}

resource "aws_subnet" "task" {
  count                   = 2
  vpc_id                  = aws_vpc.app.id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = cidrsubnet(aws_vpc.app.cidr_block, 8, count.index)
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name_prefix}-task-${count.index + 1}" }
}

resource "aws_subnet" "db" {
  count                   = 2
  vpc_id                  = aws_vpc.app.id
  availability_zone       = var.availability_zones[count.index]
  cidr_block              = cidrsubnet(aws_vpc.app.cidr_block, 8, count.index + 10)
  map_public_ip_on_launch = false
  tags                    = { Name = "${var.name_prefix}-db-${count.index + 1}" }
}

resource "aws_route_table" "task" {
  vpc_id = aws_vpc.app.id
  tags   = { Name = "${var.name_prefix}-task-routes" }
}

resource "aws_route" "task_internet" {
  route_table_id         = aws_route_table.task.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.app.id
}

resource "aws_route_table_association" "task" {
  count          = 2
  subnet_id      = aws_subnet.task[count.index].id
  route_table_id = aws_route_table.task.id
}

resource "aws_route_table" "db" {
  vpc_id = aws_vpc.app.id
  tags   = { Name = "${var.name_prefix}-db-private-routes" }
}

resource "aws_route_table_association" "db" {
  count          = 2
  subnet_id      = aws_subnet.db[count.index].id
  route_table_id = aws_route_table.db.id
}

resource "aws_security_group" "task" {
  name_prefix            = "${var.name_prefix}-task-"
  description            = "Batch task: no inbound rules"
  vpc_id                 = aws_vpc.app.id
  revoke_rules_on_delete = true
  tags                   = { Name = "${var.name_prefix}-task" }
}

resource "aws_security_group" "db" {
  name_prefix            = "${var.name_prefix}-db-"
  description            = "Private Aurora: task-only ingress"
  vpc_id                 = aws_vpc.app.id
  revoke_rules_on_delete = true
  tags                   = { Name = "${var.name_prefix}-db" }
}

resource "aws_vpc_security_group_egress_rule" "task_https" {
  security_group_id = aws_security_group.task.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
  description       = "AWS APIs, ECR, and later explicitly approved HTTPS providers"
}

resource "aws_vpc_security_group_egress_rule" "task_db" {
  security_group_id            = aws_security_group.task.id
  referenced_security_group_id = aws_security_group.db.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "Private Aurora writer"
}

resource "aws_vpc_security_group_ingress_rule" "db_task" {
  security_group_id            = aws_security_group.db.id
  referenced_security_group_id = aws_security_group.task.id
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "Only batch tasks"
}
