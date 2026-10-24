locals {
  eks          = var.eks && local.env == "dev" ? 1 : 0
  eks_services = toset(["ingest-worker", "search-api", "embedder"])
  pod_identity_trust = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "pods.eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
}

data "aws_subnets" "eks" {
  filter {
    name   = "vpc-id"
    values = [data.aws_vpc.default.id]
  }
  filter {
    name   = "default-for-az"
    values = ["true"]
  }
  filter {
    name   = "availability-zone"
    values = ["${var.region}a", "${var.region}b", "${var.region}c"]
  }
}

resource "aws_iam_role" "eks_cluster" {
  count = local.eks
  name  = "${local.name}-eks-cluster"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action    = ["sts:AssumeRole", "sts:TagSession"]
    }]
  })
}

resource "aws_iam_role_policy_attachment" "eks_cluster" {
  count      = local.eks
  role       = aws_iam_role.eks_cluster[0].name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

resource "aws_eks_cluster" "main" {
  count    = local.eks
  name     = "cadence-finder"
  version  = var.eks_version
  role_arn = aws_iam_role.eks_cluster[0].arn

  vpc_config {
    subnet_ids = data.aws_subnets.eks.ids
  }

  access_config {
    authentication_mode                         = "API"
    bootstrap_cluster_creator_admin_permissions = true
  }

  depends_on = [aws_iam_role_policy_attachment.eks_cluster]
}

resource "aws_iam_role" "eks_node" {
  count = local.eks
  name  = "${local.name}-eks-node"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

resource "aws_iam_role_policy_attachment" "eks_node" {
  for_each = local.eks == 1 ? toset([
    "AmazonEKSWorkerNodePolicy",
    "AmazonEKS_CNI_Policy",
    "AmazonEC2ContainerRegistryPullOnly",
  ]) : toset([])
  role       = aws_iam_role.eks_node[0].name
  policy_arn = "arn:aws:iam::aws:policy/${each.key}"
}

resource "aws_eks_node_group" "main" {
  count           = local.eks
  cluster_name    = aws_eks_cluster.main[0].name
  node_group_name = "main"
  node_role_arn   = aws_iam_role.eks_node[0].arn
  subnet_ids      = data.aws_subnets.eks.ids
  ami_type        = "AL2023_ARM_64_STANDARD"
  instance_types  = ["t4g.medium"]
  disk_size       = 20

  scaling_config {
    desired_size = 2
    min_size     = 2
    max_size     = 2
  }

  depends_on = [aws_iam_role_policy_attachment.eks_node]
}

resource "aws_eks_addon" "pod_identity" {
  count        = local.eks
  cluster_name = aws_eks_cluster.main[0].name
  addon_name   = "eks-pod-identity-agent"
  depends_on   = [aws_eks_node_group.main]
}

resource "aws_eks_addon" "metrics_server" {
  count        = local.eks
  cluster_name = aws_eks_cluster.main[0].name
  addon_name   = "metrics-server"
  depends_on   = [aws_eks_node_group.main]
}

resource "aws_iam_role" "ebs_csi" {
  count              = local.eks
  name               = "${local.name}-eks-ebs-csi"
  assume_role_policy = local.pod_identity_trust
}

resource "aws_iam_role_policy_attachment" "ebs_csi" {
  count      = local.eks
  role       = aws_iam_role.ebs_csi[0].name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

resource "aws_eks_addon" "ebs_csi" {
  count        = local.eks
  cluster_name = aws_eks_cluster.main[0].name
  addon_name   = "aws-ebs-csi-driver"

  pod_identity_association {
    role_arn        = aws_iam_role.ebs_csi[0].arn
    service_account = "ebs-csi-controller-sa"
  }

  depends_on = [aws_eks_addon.pod_identity, aws_iam_role_policy_attachment.ebs_csi]
}

resource "aws_iam_role" "eks_app" {
  count              = local.eks
  name               = "${local.name}-eks-app"
  assume_role_policy = local.pod_identity_trust
}

resource "aws_iam_role_policy" "eks_app" {
  count  = local.eks
  name   = "${local.name}-app"
  role   = aws_iam_role.eks_app[0].id
  policy = local.app_policy
}

resource "aws_eks_pod_identity_association" "app" {
  count           = local.eks
  cluster_name    = aws_eks_cluster.main[0].name
  namespace       = "cadence"
  service_account = "default"
  role_arn        = aws_iam_role.eks_app[0].arn
}

resource "aws_ecr_repository" "eks" {
  for_each     = local.eks == 1 ? local.eks_services : toset([])
  name         = "cadence-finder/${each.key}"
  force_delete = true
}
