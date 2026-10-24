variable "region" {
  type    = string
  default = "us-east-1"
}

data "aws_caller_identity" "current" {}

locals {
  env  = terraform.workspace
  name = "cadence-finder-${local.env}"
}
