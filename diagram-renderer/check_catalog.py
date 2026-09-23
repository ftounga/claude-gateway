#!/usr/bin/env python3
"""
Vérifie que la RÉSOLUTION des icônes fonctionne (F-142 / SF-142-09).

Il n'y a plus de catalogue à tenir : les icônes sont résolues dans la bibliothèque. Ce contrôle, joué
AU BUILD, s'assure que la résolution trouve bien ce qu'un schéma d'architecture réclame — y compris
ce qui manquait le 2026-09-24 sur un schéma réel (NAT privée, transit gateway, sous-réseaux).
"""
import sys

from cloud import FAMILIES, resolve, _index_of

ATTENDUS = [
    # Ce qui a manqué en production, et qui doit marcher maintenant.
    "aws.natgateway", "aws.transitgateway", "aws.privatesubnet", "aws.publicsubnet",
    "aws.internetgateway", "aws.directconnect", "aws.vpngateway", "aws.vpcpeering",
    # Le socle d'une architecture.
    "aws.rds", "aws.s3", "aws.ecs", "aws.eks", "aws.lambda", "aws.alb", "aws.cloudfront",
    "aws.waf", "aws.secretsmanager", "aws.sqs", "aws.dynamodb", "aws.vpc",
    "azure.aks", "azure.keyvaults", "gcp.gke", "gcp.bigquery",
    "onprem.postgresql", "onprem.kafka", "onprem.nginx", "onprem.users",
    "k8s.pod", "generic.firewall",
]


def main():
    manquants = [kind for kind in ATTENDUS if resolve(kind) is None]
    if manquants:
        print("Types NON résolus : " + ", ".join(manquants))
        return 1
    total = sum(len(_index_of(family)) for family in FAMILIES)
    print(f"Résolution valide : {len(ATTENDUS)} types de contrôle, {total} icônes atteignables.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
