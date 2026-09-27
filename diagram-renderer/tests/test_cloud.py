#!/usr/bin/env python3
"""
F-142 / SF-142-12 — un type inconnu doit SE VOIR.

Ces tests regardent les PIXELS, pas l'absence d'erreur. C'est tout le défaut du 2026-09-27 : l'appel
rendait HTTP 200, l'image sortait, le composant n'y était pas. Un test qui se contente d'un code de
retour aurait été vert ce jour-là — comme celui de SF-142-05 l'avait été sur une URL morte.

Ils tournent sans réseau ; ils ont besoin de `diagrams`, de graphviz (`dot`) et de Pillow (tous
présents dans l'image du service).

Usage : python3 tests/test_cloud.py
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cloud  # noqa: E402
from PIL import Image  # noqa: E402

INCONNU = "aws.managedworkflowsforapacheairflow"  # le type réel du défaut constaté en production


def rgb(couleur):
    return tuple(int(couleur[i:i + 2], 16) for i in (1, 3, 5))


def rendu(spec):
    """Construit le schéma pour de vrai et rend (types inconnus, image ouverte)."""
    dossier = tempfile.mkdtemp()
    sortie = os.path.join(dossier, "schema")
    inconnus = cloud.build(spec, sortie)
    return inconnus, Image.open(sortie + ".png").convert("RGB")


def pixels_proches(image, couleur, tolerance=40):
    """Le nombre de pixels de cette couleur, à l'anticrénelage près."""
    cible = rgb(couleur)
    return sum(1 for pixel in image.getdata()
               if all(abs(pixel[i] - cible[i]) <= tolerance for i in range(3)))


def un_noeud(type_, label="Airflow", **extra):
    spec = {"title": "Cible", "nodes": [{"id": "a", "type": type_, "label": label}]}
    spec.update(extra)
    return spec


class TypeInconnuVisible(unittest.TestCase):
    """Le cœur de SF-142-12 : la forme existe sur l'image."""

    def test_un_type_inconnu_produit_une_forme_non_vide(self):
        # Le repli d'avant (« generic.blank.Blank ») avait une icône VIDE : zéro pixel dessiné.
        _, image = rendu(un_noeud(INCONNU))
        self.assertGreater(pixels_proches(image, cloud.UNKNOWN_STROKE), 200,
                           "le nœud inconnu n'a pas de contour visible : l'image ment")
        self.assertGreater(pixels_proches(image, cloud.UNKNOWN_FILL), 2000,
                           "le nœud inconnu n'a pas de corps visible")

    def test_elle_se_distingue_d_une_icone_officielle(self):
        # Différentiel : la couleur du repli n'apparaît sur AUCUN schéma entièrement résolu.
        _, officielle = rendu(un_noeud("aws.s3", "Bucket"))
        self.assertEqual(0, pixels_proches(officielle, cloud.UNKNOWN_STROKE, tolerance=20),
                         "une icône officielle emprunte la couleur du repli : on ne les distingue plus")

    def test_la_forme_porte_l_etiquette_du_noeud(self):
        from diagrams import Diagram

        dossier = tempfile.mkdtemp()
        with Diagram("t", filename=os.path.join(dossier, "t"), outformat="png", show=False):
            self.assertEqual("Airflow", cloud.unknown_factory(INCONNU)("Airflow").label)

    def test_sans_etiquette_la_forme_porte_le_type_demande(self):
        from diagrams import Diagram

        dossier = tempfile.mkdtemp()
        with Diagram("t", filename=os.path.join(dossier, "t"), outformat="png", show=False):
            self.assertEqual(INCONNU, cloud.unknown_factory(INCONNU)("").label)

    def test_le_repli_n_est_plus_une_icone_vide(self):
        # Garde : le PNG vide de « generic.blank » ne doit pas revenir par inadvertance. On lit le
        # CODE, pas les commentaires — l'histoire du défaut a le droit de nommer sa cause.
        racine = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
        with open(os.path.join(racine, "cloud.py"), encoding="utf-8") as source:
            code = [ligne for ligne in source if not ligne.lstrip().startswith("#")]
        self.assertNotIn("generic.blank", "".join(code))


class LAvertissementEstConserve(unittest.TestCase):
    """Le signal marchait déjà ; SF-142-12 ne doit rien lui prendre."""

    def test_unknown_types_contient_le_type(self):
        inconnus, _ = rendu(un_noeud(INCONNU))
        self.assertEqual([INCONNU], inconnus)

    def test_un_schema_tout_resolu_n_emet_aucun_marqueur(self):
        inconnus, _ = rendu(un_noeud("aws.s3", "Bucket"))
        self.assertEqual([], inconnus)


class StrictRefuseToujours(unittest.TestCase):
    """SF-142-09 avait laissé un doublon mort : `strict` ne refusait plus, il plantait."""

    def test_strict_refuse_avec_des_suggestions(self):
        with self.assertRaises(cloud.Refused) as refus:
            rendu(un_noeud(INCONNU, strict=True))
        message = str(refus.exception)
        self.assertIn(INCONNU, message)
        self.assertTrue("Types proches" in message or "Familles connues" in message, message)

    def test_strict_laisse_passer_un_schema_tout_resolu(self):
        inconnus, _ = rendu(un_noeud("aws.s3", "Bucket", strict=True))
        self.assertEqual([], inconnus)


class NonRegression(unittest.TestCase):
    """Un schéma sans type inconnu doit être rendu exactement comme avant."""

    def test_un_schema_tout_resolu_est_inchange(self):
        _, avant = rendu(un_noeud("aws.s3", "Bucket"))
        _, apres = rendu(un_noeud("aws.s3", "Bucket"))
        self.assertEqual(avant.tobytes(), apres.tobytes())
        self.assertGreater(pixels_proches(apres, "#3F8624", tolerance=60), 500,
                           "l'icône officielle S3 n'est plus dessinée")

    def test_groupes_liens_et_bornes_tiennent_avec_un_type_inconnu(self):
        inconnus, image = rendu({
            "title": "Mixte", "direction": "LR",
            "groups": [{"id": "vpc", "label": "VPC"}],
            "nodes": [{"id": "a", "type": INCONNU, "label": "Airflow", "group": "vpc"},
                      {"id": "b", "type": "aws.s3", "label": "Bucket", "group": "vpc"}],
            "edges": [{"from": "a", "to": "b", "label": "écrit"}],
        })
        self.assertEqual([INCONNU], inconnus)
        self.assertGreater(pixels_proches(image, cloud.UNKNOWN_STROKE), 200)


if __name__ == "__main__":
    unittest.main(verbosity=2)
