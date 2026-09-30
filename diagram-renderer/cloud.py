#!/usr/bin/env python3
"""
Le rendu d'une architecture cloud avec les ICÔNES OFFICIELLES (F-142 / SF-142-07).

Ce programme ne reçoit JAMAIS de code : il lit une description (JSON) sur son entrée standard,
la valide, puis construit le diagramme lui-même. C'est la décision qui structure la subfeature —
la bibliothèque `diagrams` se pilote en écrivant du Python, et exécuter le Python d'un modèle sur
notre infrastructure serait une porte qu'on n'ouvre pas.

Entrée  : {"title": "...", "direction": "LR", "groups": [...], "nodes": [...], "edges": [...]}
Sortie  : le chemin du SVG écrit, sur la sortie standard. Toute erreur part en sortie d'erreur
          avec un message destiné à être LU (il dit quoi corriger), et un code de retour non nul.

Le SVG (F-142 / SF-142-18) est AUTO-CONTENU : les icônes officielles, que `diagrams` embarque comme
des PNG sur le disque du service, sont inlinées en `data:` URI. Sans cela, le SVG servi dans une page
pointerait sur des chemins fichier inaccessibles depuis le navigateur, et ses icônes seraient cassées.
"""
import base64
import difflib
import json
import re
import os
import sys

MAX_NODES = 60
# L'imbrication (SF-142-15) multiplie mécaniquement les cadres : un VPC, ses zones, leurs
# sous-réseaux. La borne d'avant (12) était taillée pour des cadres frères ; elle refusait désormais
# des topologies légitimes. Elle monte à 24 — elle reste une borne, pas une permission d'étaler.
MAX_GROUPS = 24
# Au-delà, le schéma n'est plus lisible : VPC > zone > sous-réseau > ressource suffit à décrire un
# réseau réel, et une profondeur non bornée ouvre la porte à une descente sans fin.
MAX_GROUP_DEPTH = 4
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
# rien, une forme visible et nommée (SF-142-12), jamais une icône approchante.
# ---------------------------------------------------------------------------------------------------

# Les familles ouvertes à la résolution. Fermées volontairement : on dessine des architectures.
FAMILIES = ("aws", "azure", "gcp", "k8s", "onprem", "generic", "elastic", "saas", "oci", "digitalocean")

# ---------------------------------------------------------------------------------------------------
# LE VOCABULAIRE QUI PARDONNE (F-142 / SF-142-16)
#
# Défaut constaté sur le rendu réel du 2026-09-27 : « MWAA » était dessiné en pointillés rouges. Le
# type n'avait pas été résolu. L'agent avait écrit le nom que TOUT LE MONDE emploie — « aws.mwaa » —
# et le catalogue, lui, ne connaît que le nom de classe de la bibliothèque.
#
# Un alias est une ÉGALITÉ, jamais une ressemblance : « aws.mwaa » EST Managed Workflows for Apache
# Airflow. Un rapprochement calculé finirait par mettre une icône FAUSSE dans un livrable client —
# c'est la règle de fond de F-142, et elle ne bouge pas. La SUGGESTION, elle, a le droit d'approcher :
# proposer n'engage à rien, c'est l'agent qui tranche.
#
# La cible est écrite ENTIÈREMENT (« famille.nom ») : un alias peut désigner une autre famille quand
# c'est le même produit — MWAA est Apache Airflow managé, et son logo est celui d'Airflow.
#
# Chaque cible est vérifiée AU BUILD (`check_catalog.py`) : « onprem.k8s » pointait sur une classe
# « kubernetes » QUI N'EXISTE PAS, et ne résolvait donc rien depuis le premier jour. Un alias mort
# est pire qu'une absence d'alias : il promet et ne rend rien.
# ---------------------------------------------------------------------------------------------------
ALIASES = {
    # Les répartiteurs de charge, dont le nom de classe n'est employé par personne.
    "aws.alb": "aws.elbapplicationloadbalancer",
    "aws.nlb": "aws.elbnetworkloadbalancer",
    "aws.clb": "aws.elbclassicloadbalancer",
    "aws.elb": "aws.elasticloadbalancing",
    # Les sigles que les architectes écrivent sans y penser.
    "aws.sm": "aws.secretsmanager",
    "aws.tgw": "aws.transitgateway",
    "aws.igw": "aws.internetgateway",
    "aws.natgw": "aws.natgateway",
    # « ASG » désigne le groupe d'auto-scaling EC2 ; « autoscaling » tout court tombe, dans la
    # bibliothèque, sur Application Auto Scaling — un autre service.
    "aws.asg": "aws.ec2autoscaling",
    "aws.apigw": "aws.apigateway",
    "aws.cw": "aws.cloudwatch",
    "aws.r53": "aws.route53",
    "aws.ddb": "aws.dynamodb",
    "aws.sfn": "aws.stepfunctions",
    "aws.msk": "aws.managedstreamingforkafka",
    "aws.eni": "aws.vpcelasticnetworkinterface",
    "aws.vpcendpoint": "aws.endpoint",
    "aws.kubernetes": "aws.elastickubernetesservice",
    # Le service renommé : « Amazon Elasticsearch Service » est devenu « Amazon OpenSearch Service ».
    # Même service, même icône — une égalité, pas une ressemblance.
    "aws.opensearch": "aws.elasticsearchservice",
    # MWAA est Apache Airflow managé : son icône est celle d'Airflow. La bibliothèque n'a pas d'icône
    # AWS pour ce service, et prendre « ce qui ressemble » dans la famille aws serait un composant faux.
    "aws.mwaa": "onprem.airflow",
    "aws.airflow": "onprem.airflow",
    "aws.snowflake": "saas.snowflake",
    "onprem.snowflake": "saas.snowflake",
    "aws.users": "aws.user",
    "onprem.postgres": "onprem.postgresql",
    # « kubernetes » n'existe pas dans onprem : l'icône Kubernetes de la bibliothèque est dans k8s.
    "onprem.k8s": "k8s.master",
    "onprem.kubernetes": "k8s.master",
    "azure.k8s": "azure.kubernetesservices",
    "gcp.k8s": "gcp.kubernetesengine",
}

# Au-delà, la piste n'aide plus : elle se lit comme une liste.
MAX_SUGGESTIONS = 8
# ---------------------------------------------------------------------------------------------------
# LE PLACEMENT LISIBLE (F-142 / SF-142-17)
#
# Défaut constaté sur le rendu réel du 2026-09-27 : 3979 × 3131 pixels pour une quinzaine de
# composants, un immense vide au centre, et des arêtes horizontales interminables dont les étiquettes
# — « objets », « images », « files », « Snowflake » — flottaient au milieu de nulle part.
#
# L'arbitrage : RÉGLER graphviz, ne pas placer à sa place. Écrire notre propre moteur de placement
# serait refaire ce que graphviz fait bien, et ajouter un second moteur à maintenir à côté de celui
# de `drawio`. On agit sur les ATTRIBUTS, jamais sur les coordonnées.
#
#   * `nodesep` / `ranksep` : les séparations par défaut de la bibliothèque (0,60 et 0,75 pouce) sont
#     taillées pour de petits schémas ; à quinze composants elles étalent.
#   * `splines` : la valeur par défaut de la bibliothèque est « ortho ». C'est elle qui produisait les
#     longues traversées horizontales — le routage à angle droit contourne par le bord. En « spline »,
#     la plus longue arête horizontale de la vue réelle passe de 31 % à 10 % de la largeur.
#   * `concentrate` a été ÉCARTÉ : il fusionne des arêtes parallèles, et deux liens de libellés
#     différents (« objets » et « images ») y perdraient une étiquette. Un schéma faux est pire qu'un
#     schéma étalé — et la mesure ne montrait AUCUN gain de surface.
#   * On ne touche PAS au `dpi` : rapetisser n'est pas ranger, et une image illisible n'apprend rien.
#
# Ces attributs vivent ici, nommés, pour qu'un test puisse mesurer AVANT et APRÈS.
# ---------------------------------------------------------------------------------------------------
GRAPH_ATTR = {
    "pad": "0.3",
    "dpi": "144",
    "nodesep": "0.30",
    "ranksep": "0.45",
    "splines": "spline",
}
EDGE_ATTR = {
    # L'étiquette d'un lien est une précision, pas un titre : plus compacte, elle reste près de son
    # arête au lieu de pousser les nœuds pour se faire de la place.
    "fontsize": "12",
}
# Au-delà, le schéma ne se lit plus à l'écran : on le DIT à l'agent plutôt que de produire une image
# que personne n'affiche. Une borne, pas un recadrage — rogner mentirait sur le contenu.
MAX_DIMENSION = 4000
# Un fragment plus court rapproche n'importe quoi de n'importe quoi (« sf » dans « workflowsfor »).
MIN_FRAGMENT = 4

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
    # Un alias désigne sa cible ENTIÈREMENT : il peut changer de famille quand c'est le même produit.
    family, _, name = ALIASES.get(raw, raw).partition(".")
    if family not in FAMILIES:
        return None
    return _index_of(family).get(_normalize(name))


def _spellings(family):
    """
    Les orthographes proposables d'une famille : ses types, ET les alias qui la visent.

    Un alias est cherché sur DEUX mots : le sigle lui-même (« mwaa ») et le nom de sa cible
    (« airflow »). C'est ce second mot qui permet à « managedworkflowsforapacheairflow » de retrouver
    « aws.mwaa » — la piste que le refus ne donnait pas.
    """
    found = [(family + "." + name, name) for name in _index_of(family)]
    for alias, target in ALIASES.items():
        alias_family, _, alias_name = alias.partition(".")
        if alias_family != family:
            continue
        found.append((alias, alias_name))
        found.append((alias, _normalize(target.partition(".")[2])))
    return found


def _ranked(wanted, spellings):
    """Les orthographes pertinentes, les plus parlantes d'abord. Jamais l'ordre alphabétique."""
    scored = {}
    for spelling, searchable in spellings:
        if not searchable or not wanted:
            continue
        if len(searchable) >= MIN_FRAGMENT and searchable in wanted:
            score = (0, -len(searchable))          # le nom demandé CONTIENT ce type : le plus parlant
        elif len(wanted) >= MIN_FRAGMENT and wanted in searchable:
            score = (1, len(searchable))           # ce type contient le nom demandé
        else:
            ratio = difflib.SequenceMatcher(None, wanted, searchable).ratio()
            if ratio < 0.7:
                continue
            score = (2, -ratio)                    # une faute de frappe, rien de plus
        if spelling not in scored or score < scored[spelling]:
            scored[spelling] = score
    return [spelling for spelling, _ in sorted(scored.items(), key=lambda item: (item[1], item[0]))]


def suggestions(kind):
    """
    Les types proches : un refus sans piste ne sert à rien (F-142 / SF-142-16).

    Suggérer a le droit d'approcher — c'est l'agent qui tranche. RÉSOUDRE ne l'a pas : une icône
    approchante dans un livrable client est un composant faux.
    """
    raw = str(kind or "").strip().lower()
    family, _, name = raw.partition(".")
    if family not in FAMILIES:
        return "Familles connues : " + ", ".join(FAMILIES) + "."
    wanted = _normalize(name)
    near = _ranked(wanted, _spellings(family))
    if len(near) < 3:
        # Le bon composant vit parfois dans une autre famille (Airflow pour MWAA, Snowflake en saas) :
        # on élargit plutôt que de rendre une liste alphabétique qui n'apprend rien.
        ailleurs = []
        for other in FAMILIES:
            if other != family:
                ailleurs.extend(_spellings(other))
        near = near + [spelling for spelling in _ranked(wanted, ailleurs) if spelling not in near]
    if not near:
        # Rien ne ressemble : une liste vaut mieux qu'un silence, mais elle reste le DERNIER recours.
        near = sorted({spelling for spelling, _ in _spellings(family)})[:MAX_SUGGESTIONS]
    return "Types proches : " + ", ".join(near[:MAX_SUGGESTIONS]) + "."


class Refused(Exception):
    """Un refus DIT : le message explique quoi corriger."""


# ---------------------------------------------------------------------------------------------------
# LE REPLI VISIBLE (F-142 / SF-142-12)
#
# Défaut constaté en production le 2026-09-27 : un nœud « aws.managedworkflowsforapacheairflow »
# rendait HTTP 200, l'image sortait — et le composant n'y était pas. Seule son étiquette flottait dans
# le vide, avec une flèche pointant sur rien.
#
# La cause tenait en une ligne : le repli était « diagrams.generic.blank.Blank », dont l'icône est un
# PNG VIDE. Le commentaire annonçait « une boîte NEUTRE, et on le DIT » ; l'implémentation rendait
# l'inverse. C'est pire qu'un refus : le lecteur d'un livrable ne voit pas un trou, il voit un schéma
# qu'il croit complet.
#
# Le repli est désormais une FORME DESSINÉE : un cadre en pointillé, d'une couleur qu'aucune icône
# officielle n'emploie, portant l'étiquette du nœud. On voit qu'il manque une icône SANS lire le texte.
# Ce n'est JAMAIS une icône approchante — la règle de fond de F-142 ne bouge pas d'un pouce, et
# « strict » reste là pour qui préfère le refus au schéma partiel.
# ---------------------------------------------------------------------------------------------------
UNKNOWN_STROKE = "#B03A2E"
UNKNOWN_FILL = "#FDECEA"


def unknown_component(label):
    """
    Le nœud d'un type sans icône : une forme VISIBLE et nommée (F-142 / SF-142-12).

    Un `Node` sans icône est dessiné par graphviz, pas par une image : c'est précisément ce qu'il
    faut ici. Le pointillé et la couleur disent « il manque une icône » d'un coup d'œil, sans mentir
    sur ce qu'est le composant.
    """
    from diagrams import Node

    return Node(label, shape="box", style="dashed,filled", fillcolor=UNKNOWN_FILL,
                color=UNKNOWN_STROKE, penwidth="2", fontcolor=UNKNOWN_STROKE, labelloc="c",
                fixedsize="false", width="1.8", height="1.0", margin="0.25")


def unknown_factory(kind):
    """Sans étiquette, la forme porte le type demandé : une forme muette ne vaut guère mieux qu'un trou."""
    def make(label):
        return unknown_component(label or kind)
    return make


def label_of(raw, what):
    text = ("" if raw is None else str(raw)).strip()
    if len(text) > MAX_LABEL:
        raise Refused(f"{what} trop long ({len(text)} caractères, maximum {MAX_LABEL}).")
    return text


# ---------------------------------------------------------------------------------------------------
# LES GROUPES IMBRIQUÉS (F-142 / SF-142-15)
#
# Défaut constaté sur le rendu réel du 2026-09-27 : le cadre « Sous-réseaux privés » et le cadre
# « VPC 10.180.165.0/24 » étaient dessinés CÔTE À CÔTE, en frères. Or un VPC contient ses
# sous-réseaux : la topologie affichée était FAUSSE.
#
# La cause tenait dans notre code, pas dans le modèle : la description n'avait qu'UN SEUL niveau —
# chaque nœud portait un « group », et la carte plate rendait un Cluster par clé. L'imbrication était
# IMPOSSIBLE À EXPRIMER. Les arêtes qui traversaient le vide en étaient la conséquence : sans
# imbrication, graphviz étale tout et relie de loin.
#
# L'arbitrage : un « parent » sur le GROUPE, jamais un chemin sur le nœud. Le nœud continue de
# déclarer un seul groupe ; c'est le groupe qui dit où il vit. Faire porter la hiérarchie par le nœud
# obligerait à répéter le chemin sur chaque ressource, et deux nœuds du même sous-réseau pourraient
# le décrire différemment.
#
# Rétrocompatible par construction : un « groups » sans « parent » rend exactement ce qu'il rendait.
# ---------------------------------------------------------------------------------------------------


def group_plan(groups, by_group):
    """
    L'arbre des cadres : (étiquettes, enfants, racines, groupes peuplés).

    Un parent inconnu ou un cycle sont REFUSÉS : un cadre orphelin dessinerait une topologie fausse,
    et un schéma faux est pire qu'un schéma absent.
    """
    labels = {}
    parent_of = {}
    declared = []
    for group in groups:
        group_id = str(group.get("id") or "").strip()
        if not group_id:
            raise Refused("Un groupe sans identifiant : « id » est obligatoire sur chaque groupe.")
        if group_id in parent_of:
            raise Refused(f"Deux groupes portent le même identifiant : « {group_id} ».")
        declared.append(group_id)
        labels[group_id] = label_of(group.get("label"), "Le nom d'un groupe")
        raw = group.get("parent")
        parent_of[group_id] = "" if raw is None else str(raw).strip()

    # Un groupe nommé par un nœud mais jamais déclaré reste accepté — c'est le comportement d'avant.
    # Il est alors une racine, et porte son identifiant pour étiquette.
    for group_id in by_group:
        if group_id and group_id not in parent_of:
            parent_of[group_id] = ""
            labels.setdefault(group_id, group_id)

    for group_id, parent in parent_of.items():
        if not parent:
            continue
        if parent == group_id:
            raise Refused(f"Le groupe « {group_id} » se déclare son propre parent.")
        if parent not in parent_of:
            raise Refused(f"Groupe parent inconnu : « {parent} », déclaré par le groupe "
                          f"« {group_id} ». Un cadre orphelin dessinerait une topologie fausse.")

    for group_id in parent_of:
        seen = {group_id}
        current, depth = group_id, 1
        while parent_of[current]:
            current = parent_of[current]
            if current in seen:
                raise Refused(f"Les groupes forment un cycle : « {group_id} » est son propre "
                              "ancêtre. Un cadre ne peut pas se contenir lui-même.")
            seen.add(current)
            depth += 1
            if depth > MAX_GROUP_DEPTH:
                raise Refused(f"Groupes imbriqués trop profondément depuis « {group_id} » "
                              f"(maximum {MAX_GROUP_DEPTH} niveaux).")

    # L'ordre de rendu suit l'ordre des NŒUDS, comme avant l'imbrication : une description plate doit
    # rendre le même fichier, octet pour octet. Les groupes déclarés sans nœud viennent ensuite.
    order = [group_id for group_id in by_group if group_id]
    for group_id in declared:
        if group_id not in order:
            order.append(group_id)

    children = {group_id: [] for group_id in parent_of}
    roots = []
    for group_id in order:
        if parent_of[group_id]:
            children[parent_of[group_id]].append(group_id)
        else:
            roots.append(group_id)

    # Un cadre vide ne se dessine pas : c'était déjà le cas avant (un groupe déclaré sans nœud
    # n'existait pas sur l'image), et un cadre vide n'apprend rien. Un cadre qui ne porte aucun nœud
    # mais dont un descendant en porte, lui, se dessine — c'est tout l'objet de l'imbrication.
    populated = set()
    for group_id in order:
        if not by_group.get(group_id):
            continue
        current = group_id
        while current and current not in populated:
            populated.add(current)
            current = parent_of[current]
    return labels, children, roots, populated


def build(spec, output, outformat="svg"):
    """
    Construit le schéma et l'écrit sous « output.<outformat> ». Rend la liste des types sans icône.

    Le format de sortie est SVG par défaut (F-142 / SF-142-18) : vectoriel, il reste NET quand la page
    l'affiche en `width:100%`, là où un PNG rapetissé rendait les libellés illisibles. Les tests qui
    MESURENT des pixels demandent « png » — la mise en page vient de graphviz et ne dépend pas du
    format de sortie, seule sa sérialisation change.
    """
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
        # Aucune icône : une forme VISIBLE et nommée, et on le DIT (SF-142-12 — le repli était un PNG
        # vide, donc un composant absent de l'image). Jamais une icône approchante.
        if kind not in unknown:
            unknown.append(kind)
        classes[kind] = unknown_factory(kind)

    title = label_of(spec.get("title"), "Le titre")
    direction = str(spec.get("direction") or "LR").upper()
    if direction not in {"LR", "RL", "TB", "BT"}:
        direction = "LR"

    by_group = {}
    for node in nodes:
        by_group.setdefault(str(node.get("group") or ""), []).append(node)
    labels, children, roots, populated = group_plan(groups, by_group)

    created = {}

    def place(group_id):
        """Les nœuds du cadre, puis ses cadres fils : l'imbrication est portée par la récursion."""
        for node in by_group.get(group_id, []):
            created[str(node.get("id"))] = classes[str(node["type"]).lower()](
                label_of(node.get("label"), "Le nom d'un nœud"))
        for child in children.get(group_id, []):
            if child in populated:
                with Cluster(labels.get(child, child)):
                    place(child)

    with Diagram(title, filename=output, outformat=outformat, show=False,
                 direction=direction, graph_attr=GRAPH_ATTR, edge_attr=EDGE_ATTR):
        place("")
        for group_id in roots:
            if group_id in populated:
                with Cluster(labels.get(group_id, group_id)):
                    place(group_id)
        for edge in edges:
            source = str(edge.get("from") or "")
            target = str(edge.get("to") or "")
            if source not in created or target not in created:
                missing = source if source not in created else target
                raise Refused(f"Lien vers un nœud non déclaré : « {missing} ». "
                              "Un schéma faux est pire qu'un schéma absent.")
            text = label_of(edge.get("label"), "Le nom d'un lien")
            created[source] >> Edge(label=text) >> created[target]
    # F-142 / SF-142-18 : le SVG doit voyager SEUL. Une fois posé dans une page, il n'a plus accès au
    # disque du service : ses icônes, référencées par chemin fichier, seraient cassées. On les inline.
    if outformat == "svg":
        inline_images(output + ".svg")
    return unknown


# ---------------------------------------------------------------------------------------------------
# LE SVG AUTO-CONTENU (F-142 / SF-142-18)
#
# `diagrams` dessine les icônes officielles avec des PNG posés sur le disque du service. En SVG,
# graphviz les référence par leur chemin : <image xlink:href="/…/simple-storage-service-s3.png" …/>.
# Ce chemin n'existe QUE dans le conteneur du renderer ; servi dans le navigateur d'un poste client,
# il ne mène à rien et l'icône est cassée. On remplace donc chaque référence de FICHIER par un
# `data:` URI (le PNG encodé en base64), pour que le SVG se suffise à lui-même.
#
# On ne touche PAS aux href qui sont déjà des `data:`, des URL http(s) ou des ancres « # » (ce sont des
# liens, pas des icônes). Une icône illisible sur le disque est laissée telle quelle plutôt que de
# faire échouer tout le schéma : mieux vaut une icône manquante qu'un livrable sans diagramme.
# ---------------------------------------------------------------------------------------------------
_MIME_BY_EXT = {
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".gif": "image/gif",
    ".svg": "image/svg+xml",
}

# `xlink:href="…"` (les icônes) et `href="…"` (les liens éventuels) : on capture les deux, on ne
# transforme que ceux qui désignent un fichier image local.
_HREF = re.compile(r'((?:xlink:)?href)="([^"]+)"')


def _data_uri(file_path):
    """Le contenu d'un fichier image, encodé en `data:` URI."""
    mime = _MIME_BY_EXT.get(os.path.splitext(file_path)[1].lower(), "image/png")
    with open(file_path, "rb") as handle:
        encoded = base64.b64encode(handle.read()).decode("ascii")
    return f"data:{mime};base64,{encoded}"


def inline_images(svg_path):
    """
    Inline dans le SVG chaque icône référencée par un chemin fichier, en `data:` URI (SF-142-18).

    Après ce passage, plus aucune référence `file://` ni chemin absolu ne subsiste : le SVG est
    portable. Rend le texte du SVG écrit.
    """
    with open(svg_path, encoding="utf-8") as handle:
        svg = handle.read()

    def replace(match):
        attr, value = match.group(1), match.group(2)
        if value.startswith(("data:", "http://", "https://", "#")):
            return match.group(0)
        local = value[len("file://"):] if value.startswith("file://") else value
        if not os.path.isabs(local) or not os.path.isfile(local):
            return match.group(0)
        try:
            return f'{attr}="{_data_uri(local)}"'
        except OSError:
            # Une icône illisible ne doit pas emporter tout le schéma : on la laisse telle quelle.
            return match.group(0)

    inlined = _HREF.sub(replace, svg)
    with open(svg_path, "w", encoding="utf-8") as handle:
        handle.write(inlined)
    return inlined


def png_size(path):
    """Les dimensions d'un PNG, lues dans son en-tête : (largeur, hauteur), ou None."""
    try:
        with open(path, "rb") as image:
            head = image.read(24)
    except OSError:
        return None
    if len(head) < 24 or head[12:16] != b"IHDR":
        return None
    return int.from_bytes(head[16:20], "big"), int.from_bytes(head[20:24], "big")


def svg_size(path):
    """
    Les dimensions d'un SVG graphviz, en pixels-ÉQUIVALENTS : (largeur, hauteur), ou None.

    Le SVG n'a pas de pixels fixes — c'est tout l'intérêt (SF-142-18). Mais graphviz écrit sa taille en
    points (« width="389pt" »), et la borne de densité (SF-142-17) doit garder le même sens qu'avec le
    PNG : on convertit les points en pixels via le `dpi` de GRAPH_ATTR (144 → un point vaut deux pixels),
    exactement le facteur qu'appliquait la rastérisation PNG.
    """
    try:
        with open(path, encoding="utf-8") as handle:
            head = handle.read(8192)
    except OSError:
        return None
    match = re.search(r'<svg[^>]*\bwidth="([\d.]+)pt"[^>]*\bheight="([\d.]+)pt"', head)
    if not match:
        return None
    facteur = float(GRAPH_ATTR.get("dpi", "72") or "72") / 72.0
    return (int(round(float(match.group(1)) * facteur)),
            int(round(float(match.group(2)) * facteur)))


def density_notice(path):
    """
    L'avertissement de densité (F-142 / SF-142-17), en ASCII : il finit en en-tête HTTP.

    On ne recadre pas et on ne rapetisse pas : on DIT que le schéma est trop dense, et l'agent
    décide de le scinder. Une image qu'aucun écran n'affiche n'apprend rien à personne. La mesure suit
    le format du fichier : dimensions du SVG (SF-142-18) ou en-tête du PNG.
    """
    size = svg_size(path) if path.endswith(".svg") else png_size(path)
    if size is None:
        return ""
    largeur, hauteur = size
    if largeur <= MAX_DIMENSION and hauteur <= MAX_DIMENSION:
        return ""
    return (f"Schema tres dense : {largeur} x {hauteur} pixels, au-dela de la borne "
            f"de {MAX_DIMENSION}. Scinde-le en plusieurs vues : a cette taille il ne se lit plus.")


def main():
    try:
        spec = json.loads(sys.stdin.read() or "{}")
        if not isinstance(spec, dict):
            raise Refused("La description doit être un objet.")
        output = spec.get("output") or "/tmp/cloud-diagram"
        unknown = build(spec, output)
        # La première ligne est le fichier ; la seconde, s'il y en a une, nomme les types rendus SANS
        # icône officielle — l'agent doit pouvoir le dire à l'utilisateur.
        print(output + ".svg")
        if unknown:
            # F-142 / SF-142-16 : la PISTE voyage avec l'avertissement. Jusqu'ici seul le refus
            # « strict » la donnait — le mode normal disait « pas d'icône » sans dire quoi écrire.
            # Le marqueur reste une LIGNE ASCII : il finit en en-tête HTTP, qui n'accepte rien d'autre.
            said = " ; ".join(kind + " (" + suggestions(kind) + ")" for kind in unknown)
            print("UNKNOWN_TYPES=" + said.encode("ascii", "replace").decode("ascii"))
        notice = density_notice(output + ".svg")
        if notice:
            print("NOTICE=" + notice)
        return 0
    except Refused as refused:
        sys.stderr.write(str(refused))
        return 2
    except Exception as error:  # noqa: BLE001 — tout le reste est un échec technique, dit tel quel.
        sys.stderr.write(f"{type(error).__name__}: {error}")
        return 3


if __name__ == "__main__":
    sys.exit(main())
