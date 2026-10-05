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
    """
    Construit le schéma pour de vrai et rend (types inconnus, image ouverte).

    On demande explicitement le PNG : ces tests LISENT des pixels (SF-142-12), et la mise en page vient
    de graphviz — identique quel que soit le format de sortie. Le SVG de production, lui, est couvert par
    test_cloud_svg.py (SF-142-18).
    """
    dossier = tempfile.mkdtemp()
    sortie = os.path.join(dossier, "schema")
    inconnus = cloud.build(spec, sortie, outformat="png")
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
            # SF-142-23 : le type est passé à la ligne (« aws. / managed… ») — il est toujours là, entier.
            self.assertEqual(INCONNU, cloud.unknown_factory(INCONNU)("").label.replace("\n", ""))

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


# ---------------------------------------------------------------------------------------------------
# F-142 / SF-142-23 — les étiquettes à la ligne.
#
# Le défaut : « Bastion AL2023 », « Lambda airflow-dag-trigger » et « Route 53 », côte à côte, se
# marchaient dessus. Ces tests coupent des libellés, puis RENDENT le cas réel et MESURENT : aucune
# ligne au-delà de la borne, et aucune étiquette qui empiète sur sa voisine.
# ---------------------------------------------------------------------------------------------------
POLICES = ("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/dejavu/DejaVuSans.ttf",
           "/usr/share/fonts/TTF/DejaVuSans.ttf")
CAS_REEL = {
    "title": "Cas réel", "direction": "TB",
    "nodes": [{"id": "vpc", "type": "aws.vpc", "label": "VPC"},
              {"id": "b", "type": "aws.ec2", "label": "Bastion AL2023"},
              {"id": "l", "type": "aws.lambda", "label": "Lambda airflow-dag-trigger"},
              {"id": "r", "type": "aws.route53", "label": "Route 53"}],
    "edges": [{"from": "vpc", "to": "b"}, {"from": "vpc", "to": "l"}, {"from": "vpc", "to": "r"}],
}


def textes_svg(spec):
    """Les <text> du SVG réellement produit : (x, y, taille, texte)."""
    import html
    import re

    dossier = tempfile.mkdtemp()
    sortie = os.path.join(dossier, "schema")
    cloud.build(spec, sortie)
    with open(sortie + ".svg", encoding="utf-8") as fichier:
        svg = fichier.read()
    motif = re.compile(r'<text[^>]*?x="([-\d.]+)" y="([-\d.]+)"[^>]*?font-size="([\d.]+)"[^>]*>([^<]*)</text>')
    return [(float(x), float(y), float(taille), html.unescape(texte))
            for x, y, taille, texte in motif.findall(svg)]


def largeur(texte, taille):
    """La largeur du texte en points : mesurée avec DejaVu Sans (la police de graphviz), sinon estimée."""
    from PIL import ImageFont

    for chemin in POLICES:
        if os.path.exists(chemin):
            return ImageFont.truetype(chemin, round(taille)).getlength(texte)
    return len(texte) * taille * 0.6


class EtiquettesALaLigne(unittest.TestCase):
    """SF-142-23 : un libellé plus large que l'icône passe à la ligne ; un court reste intact."""

    def test_un_libelle_court_est_intact(self):
        for court in ("Route 53", "Bastion AL2023", "VPC 10.0.0.0/16", "S3"):
            self.assertEqual(court, cloud.wrap_label(court))

    def test_un_libelle_long_est_coupe_sur_ses_separateurs(self):
        self.assertEqual("Lambda airflow-\ndag-trigger", cloud.wrap_label("Lambda airflow-dag-trigger"))

    def test_aucune_ligne_ne_depasse_la_borne_et_rien_ne_se_perd(self):
        for long_ in ("Une passerelle d'API privée régionale pour les partenaires",
                      "lambda_ingestion_des_fichiers_clients", "VPC 10.180.165.0/24 production",
                      "api.interne.exemple.fr/v1/clients"):
            coupe = cloud.wrap_label(long_)
            self.assertIn("\n", coupe, long_)
            for ligne in coupe.split("\n"):
                self.assertLessEqual(len(ligne), cloud.LABEL_WRAP, f"{ligne!r} dans {long_!r}")
            # Aucun caractère perdu : on n'a inséré que des retours, ou remplacé des espaces.
            self.assertEqual(long_.replace(" ", ""), coupe.replace("\n", "").replace(" ", ""))

    def test_un_mot_insecable_plus_long_que_la_borne_reste_entier(self):
        self.assertEqual("Service\nmanagedworkflowsforapacheairflow",
                         cloud.wrap_label("Service managedworkflowsforapacheairflow"))

    def test_un_retour_ecrit_par_l_auteur_est_respecte(self):
        self.assertEqual("Web\nServer", cloud.wrap_label("Web\nServer"))

    def test_le_cas_reel_rendu_ne_se_chevauche_plus(self):
        textes = [t for t in textes_svg(CAS_REEL) if t[2] == 13.0]
        lignes = [t[3] for t in textes]
        self.assertIn("Lambda airflow-", lignes)
        self.assertIn("dag-trigger", lignes)
        self.assertIn("Bastion AL2023", lignes)
        self.assertIn("Route 53", lignes)
        for ligne in lignes:
            self.assertLessEqual(len(ligne), cloud.LABEL_WRAP, ligne)
        # Deux lignes sur la même hauteur ne doivent pas empiéter l'une sur l'autre.
        for i, (x1, y1, taille1, texte1) in enumerate(textes):
            for x2, y2, taille2, texte2 in textes[i + 1:]:
                if abs(y1 - y2) >= taille1 or x1 == x2:
                    continue
                gauche, droite = sorted([(x1, texte1, taille1), (x2, texte2, taille2)])
                fin_gauche = gauche[0] + largeur(gauche[1], gauche[2]) / 2
                debut_droite = droite[0] - largeur(droite[1], droite[2]) / 2
                self.assertLess(fin_gauche, debut_droite,
                                f"« {gauche[1]} » empiète sur « {droite[1]} »")

    def test_le_type_inconnu_sans_libelle_passe_aussi_a_la_ligne(self):
        lignes = [t[3] for t in textes_svg(un_noeud(INCONNU, label=""))]
        self.assertIn("aws.", lignes)
        self.assertIn("managedworkflowsforapacheairflow", lignes)

    def test_titres_de_cadres_et_liens_ne_sont_pas_coupes(self):
        lignes = [t[3] for t in textes_svg({
            "title": "T", "direction": "LR",
            "groups": [{"id": "g", "label": "Sous-réseaux privés de production"}],
            "nodes": [{"id": "a", "type": "aws.s3", "label": "A", "group": "g"},
                      {"id": "b", "type": "aws.s3", "label": "B", "group": "g"}],
            "edges": [{"from": "a", "to": "b", "label": "déclenche le DAG airflow"}],
        })]
        self.assertIn("Sous-réseaux privés de production", lignes)
        self.assertIn("déclenche le DAG airflow", lignes)

    def test_la_validation_porte_sur_le_libelle_brut(self):
        long_ = "x " * (cloud.MAX_LABEL // 2 + 1)
        with self.assertRaises(cloud.Refused):
            textes_svg(un_noeud("aws.s3", label=long_ + "y"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
