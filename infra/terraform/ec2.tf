locals {
  live = local.env == "live" ? 1 : 0
}

data "aws_vpc" "default" {
  default = true
}

data "aws_subnet" "node" {
  vpc_id            = data.aws_vpc.default.id
  availability_zone = "${var.region}a"
  default_for_az    = true
}

data "aws_ssm_parameter" "ubuntu_arm64" {
  name = "/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id"
}

resource "aws_iam_role" "node" {
  count = local.live
  name  = "${local.name}-node"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy" "node_app" {
  count  = local.live
  name   = "${local.name}-app"
  role   = aws_iam_role.node[0].id
  policy = local.app_policy
}

resource "aws_iam_role_policy" "node_parameters" {
  count = local.live
  name  = "${local.name}-parameters"
  role  = aws_iam_role.node[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["ssm:GetParameter", "ssm:GetParametersByPath"]
      Resource = "arn:aws:ssm:${var.region}:${data.aws_caller_identity.current.account_id}:parameter/cadence/${local.env}/*"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "node_ssm" {
  count      = local.live
  role       = aws_iam_role.node[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "node" {
  count = local.live
  name  = "${local.name}-node"
  role  = aws_iam_role.node[0].name
}

resource "aws_security_group" "node" {
  count  = local.live
  name   = "${local.name}-node"
  vpc_id = data.aws_vpc.default.id

  ingress {
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_instance" "node" {
  count                  = local.live
  ami                    = data.aws_ssm_parameter.ubuntu_arm64.value
  instance_type          = "t4g.medium"
  subnet_id              = data.aws_subnet.node.id
  vpc_security_group_ids = [aws_security_group.node[0].id]
  iam_instance_profile   = aws_iam_instance_profile.node[0].name

  associate_public_ip_address = true

  metadata_options {
    http_tokens                 = "required"
    http_put_response_hop_limit = 2
  }

  root_block_device {
    volume_type = "gp3"
    volume_size = 10
    encrypted   = true
  }

  user_data = <<-EOT
    #!/bin/bash
    set -euo pipefail
    snap install aws-cli --classic
    cat > /usr/local/bin/duckdns-update <<'SCRIPT'
    #!/bin/bash
    set -euo pipefail
    token="$(/snap/bin/aws ssm get-parameter --region ${var.region} --name /cadence/${local.env}/DUCKDNS_TOKEN --with-decryption --query Parameter.Value --output text)"
    curl -fsS --retry 5 "https://www.duckdns.org/update?domains=${var.duckdns_domain}&token=$token&ip="
    SCRIPT
    chmod 755 /usr/local/bin/duckdns-update
    cat > /etc/systemd/system/duckdns-update.service <<'UNIT'
    [Unit]
    Wants=network-online.target
    After=network-online.target snapd.seeded.service
    [Service]
    Type=oneshot
    ExecStart=/usr/local/bin/duckdns-update
    [Install]
    WantedBy=multi-user.target
    UNIT
    systemctl enable --now duckdns-update.service
    cat > /usr/local/bin/idle-stop <<'SCRIPT'
    #!/bin/bash
    set -uo pipefail
    [ "$(cut -d. -f1 /proc/uptime)" -lt 1800 ] && exit 0
    requests="$(k3s kubectl -n kube-system logs deploy/traefik --since=30m 2>/dev/null | grep -cE '"(GET|POST) /(search|passages|nl-search)')"
    [ "$${requests:-0}" -gt 0 ] && exit 0
    systemctl poweroff
    SCRIPT
    chmod 755 /usr/local/bin/idle-stop
    cat > /etc/systemd/system/idle-stop.service <<'UNIT'
    [Service]
    Type=oneshot
    ExecStart=/usr/local/bin/idle-stop
    UNIT
    cat > /etc/systemd/system/idle-stop.timer <<'UNIT'
    [Timer]
    OnBootSec=5min
    OnUnitActiveSec=5min
    [Install]
    WantedBy=timers.target
    UNIT
    systemctl enable --now idle-stop.timer
    curl -sfL https://get.k3s.io | INSTALL_K3S_VERSION="${var.k3s_version}" sh -s - --write-kubeconfig-mode 600
  EOT

  tags = {
    Name = "${local.name}-node"
  }

  lifecycle {
    ignore_changes = [ami, associate_public_ip_address]
  }
}
