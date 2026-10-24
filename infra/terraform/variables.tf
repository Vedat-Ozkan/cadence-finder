variable "region" {
  type    = string
  default = "us-east-1"
}

data "aws_caller_identity" "current" {}

locals {
  env  = terraform.workspace
  name = "cadence-finder-${local.env}"
}

variable "k3s_version" {
  type    = string
  default = "v1.36.5+k3s1"
}
