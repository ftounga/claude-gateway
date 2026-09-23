#!/usr/bin/env python3
"""
Le rendu d'une architecture cloud avec les ICÔNES OFFICIELLES (F-142 / SF-142-07).

Ce programme ne reçoit JAMAIS de code : il lit une description (JSON) sur son entrée standard,
la valide, puis construit le diagramme lui-même. C'est la décision qui structure la subfeature —
la bibliothèque `diagrams` se pilote en écrivant du Python, et exécuter le Python d'un modèle sur
notre infrastructure serait une porte qu'on n'ouvre pas.

Entrée  : {"title": "...", "direction": "LR", "groups": [...], "nodes": [...], "edges": [...]}
Sortie  : le chemin du PNG écrit, sur la sortie standard. Toute erreur part en sortie d'erreur
          avec un message destiné à être LU (il dit quoi corriger), et un code de retour non nul.
"""
import json
import os
import sys

MAX_NODES = 60
MAX_GROUPS = 12
MAX_EDGES = 120
MAX_LABEL = 120

# Le catalogue des icônes servies. Volontairement FERMÉ : un type inconnu est refusé avec des
# suggestions, plutôt que rendu avec une icône approchante — un schéma d'architecture faux est pire
# qu'un schéma absent.
CATALOG = {
    # --- AWS
    "aws.alb": ("diagrams.aws.network", "ELB"),
    "aws.apigateway": ("diagrams.aws.network", "APIGateway"),
    "aws.cloudfront": ("diagrams.aws.network", "CloudFront"),
    "aws.route53": ("diagrams.aws.network", "Route53"),
    "aws.vpc": ("diagrams.aws.network", "VPC"),
    "aws.ec2": ("diagrams.aws.compute", "EC2"),
    "aws.ecs": ("diagrams.aws.compute", "ECS"),
    "aws.eks": ("diagrams.aws.compute", "EKS"),
    "aws.lambda": ("diagrams.aws.compute", "Lambda"),
    "aws.fargate": ("diagrams.aws.compute", "Fargate"),
    "aws.rds": ("diagrams.aws.database", "RDS"),
    "aws.aurora": ("diagrams.aws.database", "Aurora"),
    "aws.dynamodb": ("diagrams.aws.database", "Dynamodb"),
    "aws.elasticache": ("diagrams.aws.database", "ElastiCache"),
    "aws.s3": ("diagrams.aws.storage", "S3"),
    "aws.efs": ("diagrams.aws.storage", "EFS"),
    "aws.sqs": ("diagrams.aws.integration", "SQS"),
    "aws.sns": ("diagrams.aws.integration", "SNS"),
    "aws.eventbridge": ("diagrams.aws.integration", "Eventbridge"),
    "aws.cloudwatch": ("diagrams.aws.management", "Cloudwatch"),
    "aws.iam": ("diagrams.aws.security", "IAM"),
    "aws.secretsmanager": ("diagrams.aws.security", "SecretsManager"),
    "aws.waf": ("diagrams.aws.security", "WAF"),
    "aws.cognito": ("diagrams.aws.security", "Cognito"),
    "aws.ecr": ("diagrams.aws.compute", "ECR"),
    # --- Azure
    "azure.aks": ("diagrams.azure.compute", "AKS"),
    "azure.vm": ("diagrams.azure.compute", "VM"),
    "azure.functions": ("diagrams.azure.compute", "FunctionApps"),
    "azure.appservice": ("diagrams.azure.compute", "AppServices"),
    "azure.sql": ("diagrams.azure.database", "SQLDatabases"),
    "azure.postgresql": ("diagrams.azure.database", "DatabaseForPostgresqlServers"),
    "azure.cosmosdb": ("diagrams.azure.database", "CosmosDb"),
    "azure.storage": ("diagrams.azure.storage", "StorageAccounts"),
    "azure.loadbalancer": ("diagrams.azure.network", "LoadBalancers"),
    "azure.appgateway": ("diagrams.azure.network", "ApplicationGateway"),
    "azure.vnet": ("diagrams.azure.network", "VirtualNetworks"),
    "azure.keyvault": ("diagrams.azure.security", "KeyVaults"),
    "azure.monitor": ("diagrams.azure.analytics", "AnalysisServices"),
    # --- GCP
    "gcp.gke": ("diagrams.gcp.compute", "GKE"),
    "gcp.gce": ("diagrams.gcp.compute", "ComputeEngine"),
    "gcp.functions": ("diagrams.gcp.compute", "Functions"),
    "gcp.run": ("diagrams.gcp.compute", "Run"),
    "gcp.sql": ("diagrams.gcp.database", "SQL"),
    "gcp.spanner": ("diagrams.gcp.database", "Spanner"),
    "gcp.bigquery": ("diagrams.gcp.analytics", "BigQuery"),
    "gcp.storage": ("diagrams.gcp.storage", "Storage"),
    "gcp.pubsub": ("diagrams.gcp.analytics", "PubSub"),
    "gcp.lb": ("diagrams.gcp.network", "LoadBalancing"),
    # --- on-prem / générique
    "onprem.server": ("diagrams.onprem.compute", "Server"),
    "onprem.docker": ("diagrams.onprem.container", "Docker"),
    "onprem.postgresql": ("diagrams.onprem.database", "PostgreSQL"),
    "onprem.mysql": ("diagrams.onprem.database", "MySQL"),
    "onprem.oracle": ("diagrams.onprem.database", "Oracle"),
    "onprem.mongodb": ("diagrams.onprem.database", "MongoDB"),
    "onprem.redis": ("diagrams.onprem.inmemory", "Redis"),
    "onprem.kafka": ("diagrams.onprem.queue", "Kafka"),
    "onprem.rabbitmq": ("diagrams.onprem.queue", "Rabbitmq"),
    "onprem.nginx": ("diagrams.onprem.network", "Nginx"),
    "onprem.haproxy": ("diagrams.onprem.network", "Haproxy"),
    "onprem.vault": ("diagrams.onprem.security", "Vault"),
    "onprem.jenkins": ("diagrams.onprem.ci", "Jenkins"),
    "onprem.gitlab": ("diagrams.onprem.vcs", "Gitlab"),
    "onprem.grafana": ("diagrams.onprem.monitoring", "Grafana"),
    "onprem.prometheus": ("diagrams.onprem.monitoring", "Prometheus"),
    "onprem.elasticsearch": ("diagrams.elastic.elasticsearch", "Elasticsearch"),
    "onprem.user": ("diagrams.onprem.client", "User"),
    "onprem.users": ("diagrams.onprem.client", "Users"),
    "onprem.client": ("diagrams.onprem.client", "Client"),
    "onprem.switch": ("diagrams.generic.network", "Switch"),
    "onprem.firewall": ("diagrams.generic.network", "Firewall"),
    "k8s.pod": ("diagrams.k8s.compute", "Pod"),
    "k8s.deployment": ("diagrams.k8s.compute", "Deploy"),
    "k8s.service": ("diagrams.k8s.network", "SVC"),
    "k8s.ingress": ("diagrams.k8s.network", "Ing"),
}


class Refused(Exception):
    """Un refus DIT : le message explique quoi corriger."""


def suggestions(kind):
    """Les types proches d'un type inconnu : un refus sans piste ne sert à rien."""
    family = kind.split(".")[0] if "." in kind else kind
    near = [k for k in CATALOG if k.startswith(family + ".")]
    if not near:
        near = sorted({k.split(".")[0] for k in CATALOG})
        return "Familles connues : " + ", ".join(near) + "."
    return "Types proches : " + ", ".join(sorted(near)[:12]) + "."


def label_of(raw, what):
    text = ("" if raw is None else str(raw)).strip()
    if len(text) > MAX_LABEL:
        raise Refused(f"{what} trop long ({len(text)} caractères, maximum {MAX_LABEL}).")
    return text


def build(spec, output):
    from diagrams import Cluster, Diagram, Edge

    nodes = spec.get("nodes") or []
    groups = spec.get("groups") or []
    edges = spec.get("edges") or []
    if not nodes:
        raise Refused("Aucun nœud : un schéma vide n'apprend rien.")
    if len(nodes) > MAX_NODES:
        raise Refused(f"Trop de nœuds ({len(nodes)}, maximum {MAX_NODES}).")
    if len(groups) > MAX_GROUPS:
        raise Refused(f"Trop de groupes ({len(groups)}, maximum {MAX_GROUPS}).")
    if len(edges) > MAX_EDGES:
        raise Refused(f"Trop de liens ({len(edges)}, maximum {MAX_EDGES}).")

    classes = {}
    for node in nodes:
        kind = str(node.get("type") or "").strip().lower()
        if kind not in CATALOG:
            raise Refused(f"Type de nœud inconnu : « {kind} ». " + suggestions(kind))
        module, name = CATALOG[kind]
        if kind not in classes:
            imported = __import__(module, fromlist=[name])
            classes[kind] = getattr(imported, name)

    title = label_of(spec.get("title"), "Le titre")
    direction = str(spec.get("direction") or "LR").upper()
    if direction not in {"LR", "RL", "TB", "BT"}:
        direction = "LR"

    by_group = {}
    for node in nodes:
        by_group.setdefault(str(node.get("group") or ""), []).append(node)
    group_labels = {str(g.get("id")): label_of(g.get("label"), "Le nom d'un groupe") for g in groups}

    created = {}
    with Diagram(title, filename=output, outformat="png", show=False,
                 direction=direction, graph_attr={"pad": "0.4", "dpi": "144"}):
        for node in by_group.get("", []):
            created[str(node.get("id"))] = classes[str(node["type"]).lower()](
                label_of(node.get("label"), "Le nom d'un nœud"))
        for group_id, members in by_group.items():
            if not group_id:
                continue
            with Cluster(group_labels.get(group_id, group_id)):
                for node in members:
                    created[str(node.get("id"))] = classes[str(node["type"]).lower()](
                        label_of(node.get("label"), "Le nom d'un nœud"))
        for edge in edges:
            source = str(edge.get("from") or "")
            target = str(edge.get("to") or "")
            if source not in created or target not in created:
                missing = source if source not in created else target
                raise Refused(f"Lien vers un nœud non déclaré : « {missing} ». "
                              "Un schéma faux est pire qu'un schéma absent.")
            text = label_of(edge.get("label"), "Le nom d'un lien")
            created[source] >> Edge(label=text) >> created[target]


def main():
    try:
        spec = json.loads(sys.stdin.read() or "{}")
        if not isinstance(spec, dict):
            raise Refused("La description doit être un objet.")
        output = spec.get("output") or "/tmp/cloud-diagram"
        build(spec, output)
        print(output + ".png")
        return 0
    except Refused as refused:
        sys.stderr.write(str(refused))
        return 2
    except Exception as error:  # noqa: BLE001 — tout le reste est un échec technique, dit tel quel.
        sys.stderr.write(f"{type(error).__name__}: {error}")
        return 3


if __name__ == "__main__":
    sys.exit(main())
