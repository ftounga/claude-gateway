#!/usr/bin/env python3
"""
F-142 / SF-142-17 — le placement lisible.

Le défaut constaté sur le rendu réel du 2026-09-27 : **3979 × 3131 pixels** pour une quinzaine de
composants, un immense vide au centre, et des arêtes horizontales interminables dont les étiquettes
— « objets », « images », « files », « Snowflake » — flottaient au milieu de nulle part.

Ces tests MESURENT, sur la vue réseau reconstituée : la surface rendue, et la plus longue arête
horizontale d'un seul tenant. Un test qui se contenterait d'un code de retour aurait été vert ce
jour-là.

Ils tournent sans réseau ; ils ont besoin de `diagrams`, de graphviz (`dot`) et de Pillow.

Usage : python3 tests/test_cloud_placement.py
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cloud  # noqa: E402
from PIL import Image  # noqa: E402

# Ce que le PO a vu le 2026-09-27, en pixels. La mini-spec en fait la borne : au plus la moitié.
SURFACE_CONSTATEE = 3979 * 3131

# Les attributs d'avant SF-142-17, gardés pour mesurer l'écart au lieu de l'affirmer.
GRAPH_ATTR_AVANT = {"pad": "0.4", "dpi": "144"}
EDGE_ATTR_AVANT = {}

# La couleur des arêtes dans la bibliothèque : c'est elle qu'on suit pour mesurer une traversée.
COULEUR_ARETE = (0x7B, 0x88, 0x94)

NOEUDS = [
    {"id": "dns", "type": "aws.route53", "label": "Route 53 — zone privée", "group": "vpc"},
    {"id": "tgw", "type": "aws.tgw", "label": "Transit Gateway", "group": "vpc"},
    {"id": "link", "type": "aws.privatelink", "label": "PrivateLink", "group": "vpc"},
    {"id": "eks", "type": "aws.eks", "label": "EKS — cluster data", "group": "prive"},
    {"id": "mwaa", "type": "aws.mwaa", "label": "MWAA — orchestration", "group": "prive"},
    {"id": "lambda", "type": "aws.lambda", "label": "Lambda — ingestion", "group": "prive"},
    {"id": "ec2", "type": "aws.ec2", "label": "EC2 — webserver", "group": "prive"},
    {"id": "rds", "type": "aws.rds", "label": "RDS — métadonnées", "group": "prive"},
    {"id": "nat", "type": "aws.natgw", "label": "NAT Gateway", "group": "public"},
    {"id": "alb", "type": "aws.alb", "label": "ALB interne", "group": "public"},
    {"id": "bastion", "type": "aws.ec2", "label": "Bastion", "group": "public"},
    {"id": "s3objets", "type": "aws.s3", "label": "S3 — objets", "group": "stockage"},
    {"id": "s3images", "type": "aws.s3", "label": "S3 — images", "group": "stockage"},
    {"id": "snow", "type": "saas.snowflake", "label": "Snowflake", "group": "externe"},
    {"id": "users", "type": "onprem.users", "label": "Analystes", "group": "externe"},
]
ARETES = [
    {"from": "users", "to": "tgw", "label": "webserver PRIVATE_ONLY"},
    {"from": "tgw", "to": "alb"},
    {"from": "alb", "to": "ec2"},
    {"from": "bastion", "to": "ec2", "label": "ssh"},
    {"from": "dns", "to": "eks"},
    {"from": "mwaa", "to": "lambda", "label": "déclenche"},
    {"from": "lambda", "to": "s3objets", "label": "objets"},
    {"from": "lambda", "to": "s3images", "label": "images"},
    {"from": "eks", "to": "s3objets", "label": "files"},
    {"from": "eks", "to": "rds"},
    {"from": "eks", "to": "snow", "label": "Snowflake"},
    {"from": "link", "to": "snow"},
    {"from": "nat", "to": "tgw"},
]

PETIT = {"title": "Trois nœuds", "nodes": [
    {"id": "a", "type": "onprem.users", "label": "Utilisateurs"},
    {"id": "b", "type": "aws.alb", "label": "ALB"},
    {"id": "c", "type": "aws.rds", "label": "Base"}],
    "edges": [{"from": "a", "to": "b"}, {"from": "b", "to": "c", "label": "sql"}]}


def vue_reseau():
    """La vue réseau du 2026-09-27, reconstituée, AVEC l'imbrication apportée par SF-142-15."""
    return {
        "title": "data-ingestion — vue réseau", "direction": "LR",
        "groups": [
            {"id": "vpc", "label": "VPC — 10.180.165.0/24"},
            {"id": "prive", "label": "Sous-réseaux privés", "parent": "vpc"},
            {"id": "public", "label": "Sous-réseaux publics", "parent": "vpc"},
            {"id": "stockage", "label": "Stockage"},
            {"id": "externe", "label": "Hors VPC"},
        ],
        "nodes": NOEUDS, "edges": ARETES,
    }


def rendu(spec, graph_attr=None, edge_attr=None):
    """L'image rendue, éventuellement avec d'autres attributs — pour mesurer AVANT et APRÈS."""
    vrais = (cloud.GRAPH_ATTR, cloud.EDGE_ATTR)
    if graph_attr is not None:
        cloud.GRAPH_ATTR = graph_attr
    if edge_attr is not None:
        cloud.EDGE_ATTR = edge_attr
    try:
        sortie = os.path.join(tempfile.mkdtemp(), "vue")
        cloud.build(spec, sortie)
    finally:
        cloud.GRAPH_ATTR, cloud.EDGE_ATTR = vrais
    return Image.open(sortie + ".png").convert("RGB")


def surface(image):
    return image.size[0] * image.size[1]


def plus_longue_traversee(image):
    """La plus longue suite horizontale de pixels d'arête : une traversée de bord à bord se voit là."""
    largeur, hauteur = image.size
    pixels = image.load()
    record = 0
    for y in range(hauteur):
        courant = 0
        for x in range(largeur):
            pixel = pixels[x, y]
            if all(abs(pixel[i] - COULEUR_ARETE[i]) <= 30 for i in range(3)):
                courant += 1
                record = max(record, courant)
            else:
                courant = 0
    return record


class LaVueReseauTientDansLaMoitie(unittest.TestCase):
    """Le critère écrit dans la mini-spec, mesuré sur les pixels."""

    def test_au_plus_la_moitie_de_la_surface_constatee(self):
        rendue = surface(rendu(vue_reseau()))
        self.assertLessEqual(rendue, SURFACE_CONSTATEE // 2,
                             f"{rendue} pixels² pour une moitié de {SURFACE_CONSTATEE // 2}")

    def test_les_attributs_reduisent_vraiment_la_surface(self):
        # Sans ce test, la borne pourrait être tenue par l'imbrication seule et SF-142-17 ne rien faire.
        avant = surface(rendu(vue_reseau(), GRAPH_ATTR_AVANT, EDGE_ATTR_AVANT))
        apres = surface(rendu(vue_reseau()))
        self.assertLess(apres, avant, f"{apres} n'est pas plus compact que {avant}")


class PlusAucuneTraverseeDuVide(unittest.TestCase):
    """« Des arêtes horizontales interminables » : le routage à angle droit contournait par le bord."""

    def test_aucune_arete_ne_traverse_le_schema(self):
        image = rendu(vue_reseau())
        self.assertLess(plus_longue_traversee(image), image.size[0] * 0.2,
                        "une arête court encore sur plus d'un cinquième de la largeur")

    def test_c_est_bien_mieux_qu_avant(self):
        avant = rendu(vue_reseau(), GRAPH_ATTR_AVANT, EDGE_ATTR_AVANT)
        apres = rendu(vue_reseau())
        self.assertLess(plus_longue_traversee(apres) / apres.size[0],
                        plus_longue_traversee(avant) / avant.size[0])


class UnPetitSchemaNeChangePasDeTaille(unittest.TestCase):
    """« Un schéma simple rend au moins aussi bien qu'avant » : compacter ne doit pas tout bouleverser."""

    def test_trois_noeuds_rendent_une_image_comparable(self):
        avant = surface(rendu(PETIT, GRAPH_ATTR_AVANT, EDGE_ATTR_AVANT))
        apres = surface(rendu(PETIT))
        self.assertLessEqual(apres, avant, "un petit schéma a grossi")
        self.assertGreater(apres, avant * 0.5, "un petit schéma a fondu de moitié : il rapetisse")


class LaDensiteSeDit(unittest.TestCase):
    """Au-delà de la borne, on DIT que le schéma est trop dense — on ne recadre pas."""

    def test_un_schema_ordinaire_ne_dit_rien(self):
        sortie = os.path.join(tempfile.mkdtemp(), "petit")
        cloud.build(PETIT, sortie)
        self.assertEqual("", cloud.density_notice(sortie + ".png"))

    def test_au_dela_de_la_borne_l_avertissement_nomme_les_dimensions(self):
        sortie = os.path.join(tempfile.mkdtemp(), "vue")
        cloud.build(vue_reseau(), sortie)
        largeur, hauteur = cloud.png_size(sortie + ".png")
        vraie_borne = cloud.MAX_DIMENSION
        cloud.MAX_DIMENSION = min(largeur, hauteur) - 1
        try:
            note = cloud.density_notice(sortie + ".png")
        finally:
            cloud.MAX_DIMENSION = vraie_borne
        self.assertIn(str(largeur), note)
        self.assertIn(str(hauteur), note)
        self.assertIn("dense", note)
        note.encode("ascii")  # il finit en en-tête HTTP

    def test_png_size_lit_l_entete(self):
        sortie = os.path.join(tempfile.mkdtemp(), "petit")
        cloud.build(PETIT, sortie)
        with Image.open(sortie + ".png") as image:
            self.assertEqual(image.size, cloud.png_size(sortie + ".png"))

    def test_png_size_ne_ment_pas_sur_un_fichier_qui_n_en_est_pas_un(self):
        faux = os.path.join(tempfile.mkdtemp(), "faux.png")
        with open(faux, "wb") as fichier:
            fichier.write(b"ceci n'est pas une image")
        self.assertIsNone(cloud.png_size(faux))
        self.assertIsNone(cloud.png_size(faux + ".absent"))
        self.assertEqual("", cloud.density_notice(faux))


class LesAttributsRestentCeQuIlsSont(unittest.TestCase):
    """Des gardes : deux réglages sont interdits, et pour une raison écrite."""

    def test_les_aretes_ne_sont_jamais_fusionnees(self):
        # `concentrate` fusionne des arêtes parallèles : « objets » et « images » y perdraient une
        # étiquette. Un schéma faux est pire qu'un schéma étalé.
        self.assertNotIn("concentrate", cloud.GRAPH_ATTR)

    def test_le_dpi_ne_bouge_pas(self):
        # Rapetisser n'est pas ranger : une image illisible n'apprend rien.
        self.assertEqual("144", cloud.GRAPH_ATTR["dpi"])

    def test_le_routage_n_est_plus_a_angle_droit(self):
        self.assertNotEqual("ortho", cloud.GRAPH_ATTR.get("splines"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
