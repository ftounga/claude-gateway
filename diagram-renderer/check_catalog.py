#!/usr/bin/env python3
"""
Vérifie que la RÉSOLUTION des icônes fonctionne (F-142 / SF-142-09).

Il n'y a plus de catalogue à tenir : les icônes sont résolues dans la bibliothèque. Ce contrôle, joué
AU BUILD, s'assure que la résolution trouve bien ce qu'un schéma d'architecture réclame — y compris
ce qui manquait le 2026-09-24 sur un schéma réel (NAT privée, transit gateway, sous-réseaux).
"""
import sys

from cloud import ALIASES, FAMILIES, resolve, _index_of

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
    # F-142 / SF-142-16 : les sigles que les architectes écrivent sans y penser.
    "aws.mwaa", "aws.sm", "aws.tgw", "aws.asg", "aws.igw", "aws.nlb", "onprem.k8s",
]


def alias_morts():
    """
    Les alias qui ne désignent aucune classe réelle (F-142 / SF-142-16).

    « onprem.k8s » pointait sur une classe « kubernetes » QUI N'EXISTE PAS : l'alias était mort
    depuis le premier jour, et personne ne l'a vu — il rendait simplement une boîte sans icône. Un
    alias mort est pire qu'une absence d'alias : il promet et ne rend rien. On le refuse ICI, au
    build, pas chez le premier client.
    """
    morts = []
    for alias, cible in ALIASES.items():
        famille = alias.partition(".")[0]
        if famille not in FAMILIES:
            morts.append(f"{alias} (famille inconnue)")
        elif resolve(alias) is None:
            morts.append(f"{alias} -> {cible}")
    return morts


def main():
    manquants = [kind for kind in ATTENDUS if resolve(kind) is None]
    if manquants:
        print("Types NON résolus : " + ", ".join(manquants))
        return 1
    morts = alias_morts()
    if morts:
        print("Alias MORTS (ils ne désignent aucune classe) : " + ", ".join(morts))
        return 1
    total = sum(len(_index_of(family)) for family in FAMILIES)
    print(f"Résolution valide : {len(ATTENDUS)} types de contrôle, {len(ALIASES)} alias vivants, "
          f"{total} icônes atteignables.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
