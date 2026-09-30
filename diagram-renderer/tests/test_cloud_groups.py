#!/usr/bin/env python3
"""
F-142 / SF-142-15 — les groupes IMBRIQUÉS.

Le défaut constaté sur le rendu réel du 2026-09-27 : le cadre « Sous-réseaux privés » et le cadre
« VPC … 10.180.165.0/24 » étaient dessinés CÔTE À CÔTE. Un VPC contient ses sous-réseaux : la
topologie affichée était fausse — pas approximative, fausse.

Ces tests regardent donc DEUX choses, et pas le code de retour :
  * la STRUCTURE réellement construite (quel cadre est dans quel cadre) — on espionne `Cluster` ;
  * les PIXELS — le cadre du sous-réseau doit être contenu dans celui du VPC sur l'image.

Ils tournent sans réseau ; ils ont besoin de `diagrams`, de graphviz (`dot`) et de Pillow.

Usage : python3 tests/test_cloud_groups.py
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import diagrams  # noqa: E402
import cloud  # noqa: E402
from PIL import Image  # noqa: E402

# Les fonds que `diagrams` donne aux cadres selon leur profondeur : le premier niveau, puis le second.
FOND_NIVEAU_0 = (0xE5, 0xF5, 0xFD)
FOND_NIVEAU_1 = (0xEB, 0xF3, 0xE7)


def rendu(spec):
    """Construit le schéma pour de vrai et rend (structure des cadres, image ouverte)."""
    trace = []
    vrai_cluster = diagrams.Cluster

    class ClusterEspion(vrai_cluster):
        def __init__(self, label="cluster", **kwargs):
            super().__init__(label, **kwargs)
            trace.append({"label": label, "profondeur": self.depth,
                          "parent": self._parent.label if self._parent else None})

    dossier = tempfile.mkdtemp()
    sortie = os.path.join(dossier, "schema")
    diagrams.Cluster = ClusterEspion
    try:
        # PNG : ces tests LISENT des pixels et comparent des octets. La mise en page (l'objet de
        # SF-142-15) vient de graphviz et ne dépend pas du format de sortie ; le SVG est couvert par
        # test_cloud_svg.py (SF-142-18).
        cloud.build(spec, sortie, outformat="png")
    finally:
        diagrams.Cluster = vrai_cluster
    return trace, Image.open(sortie + ".png").convert("RGB")


def octets(spec):
    dossier = tempfile.mkdtemp()
    sortie = os.path.join(dossier, "schema")
    cloud.build(spec, sortie, outformat="png")
    with open(sortie + ".png", "rb") as fichier:
        return fichier.read()


def cadre(image, couleur, tolerance=0):
    """
    La boîte englobante des pixels de cette couleur : (gauche, haut, droite, bas).

    Tolérance NULLE par défaut : le fond d'un cadre est un aplat, et tolérer l'à-peu-près ramasse
    l'anticrénelage des icônes voisines — la boîte n'est alors plus celle du cadre.
    """
    largeur, hauteur = image.size
    pixels = image.load()
    gauche, haut, droite, bas = largeur, hauteur, -1, -1
    for y in range(hauteur):
        for x in range(largeur):
            pixel = pixels[x, y]
            if all(abs(pixel[i] - couleur[i]) <= tolerance for i in range(3)):
                gauche, haut = min(gauche, x), min(haut, y)
                droite, bas = max(droite, x), max(bas, y)
    return None if droite < 0 else (gauche, haut, droite, bas)


def vue_reseau(imbriquee):
    """La vue réseau du défaut : un VPC, ses sous-réseaux, leurs ressources."""
    groups = [
        {"id": "vpc", "label": "VPC 10.180.165.0/24"},
        {"id": "prive", "label": "Sous-réseaux privés"},
        {"id": "public", "label": "Sous-réseaux publics"},
    ]
    if imbriquee:
        groups[1]["parent"] = "vpc"
        groups[2]["parent"] = "vpc"
    return {
        "title": "Réseau", "direction": "LR", "groups": groups,
        "nodes": [
            {"id": "dns", "type": "aws.route53", "label": "Route 53", "group": "vpc"},
            {"id": "eks", "type": "aws.eks", "label": "EKS", "group": "prive"},
            {"id": "lambda", "type": "aws.lambda", "label": "Lambda", "group": "prive"},
            {"id": "ec2", "type": "aws.ec2", "label": "Bastion", "group": "public"},
            {"id": "tgw", "type": "aws.transitgateway", "label": "Transit Gateway"},
        ],
        "edges": [{"from": "dns", "to": "eks"}, {"from": "ec2", "to": "eks", "label": "ssh"},
                  {"from": "tgw", "to": "ec2"}],
    }


class LeVpcContientSesSousReseaux(unittest.TestCase):
    """Le cœur de SF-142-15 : l'imbrication existe, dans la structure ET sur l'image."""

    def test_sans_parent_les_cadres_sont_freres(self):
        # L'état d'avant, gardé comme témoin : c'est exactement ce que le PO a vu le 2026-09-27.
        trace, _ = rendu(vue_reseau(imbriquee=False))
        self.assertEqual({None}, {cadre["parent"] for cadre in trace},
                         "sans « parent », tous les cadres doivent rester des racines")

    def test_avec_parent_le_sous_reseau_vit_dans_le_vpc(self):
        trace, _ = rendu(vue_reseau(imbriquee=True))
        par_label = {cadre["label"]: cadre for cadre in trace}
        self.assertEqual("VPC 10.180.165.0/24", par_label["Sous-réseaux privés"]["parent"])
        self.assertEqual("VPC 10.180.165.0/24", par_label["Sous-réseaux publics"]["parent"])
        self.assertEqual(0, par_label["VPC 10.180.165.0/24"]["profondeur"])

    def test_sur_l_image_le_cadre_fils_est_dans_le_cadre_parent(self):
        # Les pixels, pas la structure : un cadre fils déclaré mais dessiné à côté serait un mensonge.
        _, image = rendu(vue_reseau(imbriquee=True))
        parent = cadre(image, FOND_NIVEAU_0)
        fils = cadre(image, FOND_NIVEAU_1)
        self.assertIsNotNone(parent, "le cadre du VPC n'est pas dessiné")
        self.assertIsNotNone(fils, "le cadre du sous-réseau n'est pas dessiné")
        self.assertLessEqual(parent[0], fils[0])
        self.assertLessEqual(parent[1], fils[1])
        self.assertGreaterEqual(parent[2], fils[2])
        self.assertGreaterEqual(parent[3], fils[3])

    def test_trois_niveaux_d_imbrication(self):
        trace, _ = rendu({
            "title": "Trois niveaux",
            "groups": [{"id": "vpc", "label": "VPC"},
                       {"id": "az", "label": "Zone A", "parent": "vpc"},
                       {"id": "sous", "label": "Sous-réseau", "parent": "az"}],
            "nodes": [{"id": "a", "type": "aws.ec2", "label": "App", "group": "sous"}],
        })
        self.assertEqual([0, 1, 2], sorted(cadre["profondeur"] for cadre in trace))
        par_label = {cadre["label"]: cadre for cadre in trace}
        self.assertEqual("VPC", par_label["Zone A"]["parent"])
        self.assertEqual("Zone A", par_label["Sous-réseau"]["parent"])

    def test_un_cadre_intermediaire_sans_noeud_est_quand_meme_dessine(self):
        # Un VPC ne porte souvent aucune ressource en propre : il ne doit pas disparaître pour autant.
        trace, _ = rendu({
            "title": "VPC vide",
            "groups": [{"id": "vpc", "label": "VPC"},
                       {"id": "sous", "label": "Sous-réseau", "parent": "vpc"}],
            "nodes": [{"id": "a", "type": "aws.ec2", "label": "App", "group": "sous"}],
        })
        self.assertEqual({"VPC", "Sous-réseau"}, {cadre["label"] for cadre in trace})


class LOrdreDeDeclarationNImportePas(unittest.TestCase):
    def test_un_enfant_declare_avant_son_parent_s_imbrique_pareil(self):
        avant = {"title": "T", "groups": [{"id": "vpc", "label": "VPC"},
                                          {"id": "sous", "label": "Sous", "parent": "vpc"}],
                 "nodes": [{"id": "a", "type": "aws.ec2", "label": "App", "group": "sous"}]}
        apres = dict(avant, groups=list(reversed(avant["groups"])))
        self.assertEqual(octets(avant), octets(apres))


class UnCadreFauxEstRefuse(unittest.TestCase):
    """Un cadre orphelin ou un cycle dessineraient une topologie fausse : on refuse, en le disant."""

    def test_parent_inconnu(self):
        with self.assertRaises(cloud.Refused) as refus:
            octets({"title": "T", "groups": [{"id": "sous", "label": "Sous", "parent": "vpc"}],
                    "nodes": [{"id": "a", "type": "aws.ec2", "label": "App", "group": "sous"}]})
        self.assertIn("vpc", str(refus.exception))
        self.assertIn("sous", str(refus.exception))

    def test_cycle(self):
        with self.assertRaises(cloud.Refused) as refus:
            octets({"title": "T",
                    "groups": [{"id": "a", "label": "A", "parent": "b"},
                               {"id": "b", "label": "B", "parent": "a"}],
                    "nodes": [{"id": "n", "type": "aws.ec2", "label": "App", "group": "a"}]})
        self.assertIn("cycle", str(refus.exception).lower())

    def test_un_groupe_son_propre_parent(self):
        with self.assertRaises(cloud.Refused):
            octets({"title": "T", "groups": [{"id": "a", "label": "A", "parent": "a"}],
                    "nodes": [{"id": "n", "type": "aws.ec2", "label": "App", "group": "a"}]})

    def test_profondeur_excessive(self):
        groups = [{"id": "g0", "label": "G0"}]
        for niveau in range(1, cloud.MAX_GROUP_DEPTH + 1):
            groups.append({"id": f"g{niveau}", "label": f"G{niveau}", "parent": f"g{niveau - 1}"})
        with self.assertRaises(cloud.Refused) as refus:
            octets({"title": "T", "groups": groups,
                    "nodes": [{"id": "n", "type": "aws.ec2", "label": "App",
                               "group": f"g{cloud.MAX_GROUP_DEPTH}"}]})
        self.assertIn(str(cloud.MAX_GROUP_DEPTH), str(refus.exception))

    def test_deux_groupes_de_meme_identifiant(self):
        with self.assertRaises(cloud.Refused):
            octets({"title": "T", "groups": [{"id": "a", "label": "A"}, {"id": "a", "label": "B"}],
                    "nodes": [{"id": "n", "type": "aws.ec2", "label": "App", "group": "a"}]})

    def test_un_groupe_sans_identifiant(self):
        with self.assertRaises(cloud.Refused):
            octets({"title": "T", "groups": [{"label": "A"}],
                    "nodes": [{"id": "n", "type": "aws.ec2", "label": "App"}]})


class NonRegression(unittest.TestCase):
    """Une description PLATE doit rendre exactement ce qu'elle rendait avant l'imbrication."""

    PLATE = {
        "title": "Plate", "direction": "LR",
        "groups": [{"id": "vpc", "label": "VPC"}, {"id": "onprem", "label": "Entreprise"}],
        "nodes": [{"id": "u", "type": "onprem.users", "label": "Utilisateurs", "group": "onprem"},
                  {"id": "lb", "type": "aws.alb", "label": "ALB", "group": "vpc"},
                  {"id": "db", "type": "aws.rds", "label": "Base", "group": "vpc"},
                  {"id": "cdn", "type": "aws.cloudfront", "label": "CDN"}],
        "edges": [{"from": "u", "to": "cdn", "label": "https"}, {"from": "cdn", "to": "lb"},
                  {"from": "lb", "to": "db", "label": "sql"}],
    }

    def test_un_parent_absent_ou_nul_ne_change_rien(self):
        explicite = dict(self.PLATE,
                         groups=[dict(groupe, parent=None) for groupe in self.PLATE["groups"]])
        self.assertEqual(octets(self.PLATE), octets(explicite))

    def test_les_cadres_restent_freres_et_dans_l_ordre_des_noeuds(self):
        trace, _ = rendu(self.PLATE)
        self.assertEqual(["Entreprise", "VPC"], [cadre["label"] for cadre in trace],
                         "l'ordre des cadres doit suivre l'ordre des nœuds, comme avant")
        self.assertEqual({None}, {cadre["parent"] for cadre in trace})

    def test_un_groupe_declare_mais_sans_noeud_ne_dessine_aucun_cadre(self):
        # C'était déjà le comportement : un cadre vide n'apprend rien, et le faire apparaître aurait
        # changé toutes les images existantes.
        avec_inutile = dict(self.PLATE,
                            groups=self.PLATE["groups"] + [{"id": "vide", "label": "Jamais utilisé"}])
        self.assertEqual(octets(self.PLATE), octets(avec_inutile))

    def test_un_groupe_jamais_declare_reste_accepte(self):
        trace, _ = rendu({"title": "T",
                          "nodes": [{"id": "a", "type": "aws.ec2", "label": "App", "group": "zone"}]})
        self.assertEqual([{"label": "zone", "profondeur": 0, "parent": None}], trace)

    def test_le_rendu_est_deterministe(self):
        self.assertEqual(octets(self.PLATE), octets(self.PLATE))


if __name__ == "__main__":
    unittest.main(verbosity=2)
