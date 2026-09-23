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
import re
import os
import sys

MAX_NODES = 60
MAX_GROUPS = 12
MAX_EDGES = 120
MAX_LABEL = 120

# ---------------------------------------------------------------------------------------------------
# LA RÉSOLUTION DES ICÔNES (F-142 / SF-142-09)
#
# Première version : un catalogue de 74 types écrits à la main. Défaut constaté en production le
# 2026-09-24 — « la NAT privée et la transit gateway n'ont pas d'icône », puis, pire, « le groupe
# réseau entreprise est représenté par un poste client » : faute de trouver le bon type, on prenait
# celui qui ressemblait, et un composant FAUX partait dans un livrable client.
#
# La bibliothèque expose des CENTAINES d'icônes. On les résout donc toutes, automatiquement : un type
# « aws.natgateway » cherche la classe dont le nom normalisé vaut « natgateway » dans les modules de
# « diagrams.aws ». Plus de liste à tenir, plus de composant manquant — et quand il n'existe vraiment
# rien, une boîte NEUTRE, jamais une icône approchante.
# ---------------------------------------------------------------------------------------------------

# Les familles ouvertes à la résolution. Fermées volontairement : on dessine des architectures.
FAMILIES = ("aws", "azure", "gcp", "k8s", "onprem", "generic", "elastic", "saas", "oci", "digitalocean")

# Le nœud de repli : une boîte NEUTRE, sans marque.
FALLBACK = ("diagrams.generic.blank", "Blank")

# Quelques noms d'usage qui ne correspondent pas au nom de la classe. Courte, et c'est voulu : tout
# le reste se résout tout seul.
ALIASES = {
    "aws.alb": "elbapplicationloadbalancer",
    "aws.nlb": "elbnetworkloadbalancer",
    "aws.clb": "elbclassicloadbalancer",
    "aws.users": "user",
    "onprem.postgres": "postgresql",
    "onprem.k8s": "kubernetes",
}

_INDEX = {}


def _normalize(name):
    """« NAT Gateway », « nat_gateway », « NATGateway » désignent la même chose."""
    return re.sub(r"[^a-z0-9]", "", str(name).lower())


def _index_of(family):
    """L'index des icônes d'une famille : {nom normalisé -> classe}, construit une fois."""
    if family in _INDEX:
        return _INDEX[family]
    import importlib
    import pkgutil

    from diagrams import Node

    index = {}
    try:
        package = importlib.import_module("diagrams." + family)
    except ImportError:
        _INDEX[family] = index
        return index
    modules = [package]
    for info in pkgutil.iter_modules(package.__path__):
        try:
            modules.append(importlib.import_module("diagrams." + family + "." + info.name))
        except ImportError:
            continue
    for module in modules:
        for attribute in dir(module):
            if attribute.startswith("_"):
                continue
            candidate = getattr(module, attribute)
            if isinstance(candidate, type) and issubclass(candidate, Node) and candidate is not Node:
                index.setdefault(_normalize(attribute), candidate)
    _INDEX[family] = index
    return index


def resolve(kind):
    """
    La classe d'icône d'un type, ou None s'il n'en existe aucune.

    On ne renvoie JAMAIS « ce qui ressemble » : une icône approchante dans un livrable client, c'est
    un composant faux — le défaut signalé par le PO le 2026-09-24.
    """
    raw = str(kind or "").strip().lower()
    if "." not in raw:
        return None
    family, _, name = raw.partition(".")
    if family not in FAMILIES:
        return None
    wanted = _normalize(ALIASES.get(raw, name))
    return _index_of(family).get(wanted)


def suggestions(kind):
    """Les types proches : un refus sans piste ne sert à rien."""
    raw = str(kind or "").strip().lower()
    family = raw.partition(".")[0]
    if family not in FAMILIES:
        return "Familles connues : " + ", ".join(FAMILIES) + "."
    wanted = _normalize(raw.partition(".")[2])
    index = _index_of(family)
    near = [name for name in index if wanted and (wanted in name or name in wanted)]
    if not near:
        near = sorted(index)[:12]
    return "Types proches : " + ", ".join(family + "." + n for n in sorted(near)[:12]) + "."


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

    # F-142 / SF-142-09 : par défaut, un type inconnu ne fait plus échouer TOUT le schéma — il devient
    # un nœud générique, et on dit lesquels. Refuser est juste quand on peut corriger ; ça ne l'est
    # plus quand le composant n'a, réellement, aucune icône : le livrable perdrait son schéma.
    strict = bool(spec.get("strict"))
    unknown = []
    classes = {}
    for node in nodes:
        kind = str(node.get("type") or "").strip().lower()
        if kind in classes:
            continue
        found = resolve(kind)
        if found is not None:
            classes[kind] = found
            continue
        if strict:
            raise Refused(f"Type de nœud inconnu : « {kind} ». " + suggestions(kind))
        # Aucune icône : une boîte NEUTRE, et on le DIT. Jamais une icône approchante.
        if kind not in unknown:
            unknown.append(kind)
        module, name = FALLBACK
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
    return unknown


def main():
    try:
        spec = json.loads(sys.stdin.read() or "{}")
        if not isinstance(spec, dict):
            raise Refused("La description doit être un objet.")
        output = spec.get("output") or "/tmp/cloud-diagram"
        unknown = build(spec, output)
        # La première ligne est le fichier ; la seconde, s'il y en a une, nomme les types rendus SANS
        # icône officielle — l'agent doit pouvoir le dire à l'utilisateur.
        print(output + ".png")
        if unknown:
            print("UNKNOWN_TYPES=" + ",".join(unknown))
        return 0
    except Refused as refused:
        sys.stderr.write(str(refused))
        return 2
    except Exception as error:  # noqa: BLE001 — tout le reste est un échec technique, dit tel quel.
        sys.stderr.write(f"{type(error).__name__}: {error}")
        return 3


if __name__ == "__main__":
    sys.exit(main())
