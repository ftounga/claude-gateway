#!/usr/bin/env python3
"""
F-142 / SF-142-18 — l'archi cloud en SVG NET et AUTO-CONTENU.

Le défaut : le moteur cloud ne rendait que du PNG (résolution fixe). Posé en `width:100%` dans une
colonne étroite, un grand PNG était réduit à ~26 % et ses libellés tombaient à 6-7 px — flous,
illisibles. Le SVG est vectoriel : il reste net à toute échelle.

Le piège : `diagrams` dessine les icônes officielles avec des PNG posés sur le disque du service.
Graphviz les référence par leur chemin (« <image xlink:href="/…/s3.png"> »). Servi dans le navigateur
d'un poste client, ce chemin ne mène à rien : l'icône serait cassée. Ces tests VÉRIFIENT donc que le
SVG est auto-contenu — plus aucune référence fichier, les icônes inlinées en `data:` URI.

Ils tournent sans réseau ; ils ont besoin de `diagrams` et de graphviz (`dot`). Pas de Pillow : on lit
le SVG, du texte.

Usage : python3 tests/test_cloud_svg.py
"""
import io
import json
import os
import re
import sys
import tempfile
import unittest
from contextlib import redirect_stdout

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import cloud  # noqa: E402

TOUT_RESOLU = {
    "title": "Cible AWS", "direction": "LR",
    "groups": [{"id": "vpc", "label": "VPC"}],
    "nodes": [
        {"id": "u", "type": "onprem.users", "label": "Utilisateurs"},
        {"id": "s3", "type": "aws.s3", "label": "Bucket", "group": "vpc"},
        {"id": "db", "type": "aws.rds", "label": "Base", "group": "vpc"},
    ],
    "edges": [{"from": "u", "to": "s3", "label": "https"}, {"from": "s3", "to": "db"}],
}


def rendu_svg(spec):
    """Construit le schéma en SVG (le défaut de production) et rend son texte."""
    sortie = os.path.join(tempfile.mkdtemp(), "schema")
    inconnus = cloud.build(spec, sortie)
    with open(sortie + ".svg", encoding="utf-8") as handle:
        return inconnus, handle.read()


def images(svg):
    return re.findall(r"<image\b[^>]*>", svg)


def hrefs(svg):
    return re.findall(r'(?:xlink:)?href="([^"]+)"', svg)


class LeSvgEstAutoContenu(unittest.TestCase):
    """Le cœur de SF-142-18 : le SVG voyage seul, icônes comprises."""

    def test_la_sortie_est_bien_du_svg(self):
        _, svg = rendu_svg(TOUT_RESOLU)
        self.assertIn("<svg", svg)

    def test_plus_aucune_reference_fichier(self):
        # Le défaut visé : un href vers le disque du service, cassé une fois servi dans une page.
        _, svg = rendu_svg(TOUT_RESOLU)
        self.assertNotIn("file://", svg)
        for cible in hrefs(svg):
            self.assertFalse(cible.startswith("/"),
                             f"une icône pointe encore vers un fichier du service : {cible[:80]}")

    def test_les_icones_officielles_sont_inlinees_en_data(self):
        _, svg = rendu_svg(TOUT_RESOLU)
        self.assertIn("data:image/png;base64,", svg,
                      "aucune icône n'a été inlinée : le SVG aurait des icônes cassées dans une page")

    def test_chaque_image_porte_un_data_uri(self):
        # Trois nœuds tous résolus → au moins trois icônes, et TOUTES doivent être inlinées.
        _, svg = rendu_svg(TOUT_RESOLU)
        balises = images(svg)
        self.assertGreaterEqual(len(balises), 3, "les icônes officielles ne sont pas dessinées")
        for balise in balises:
            cible = re.search(r'(?:xlink:)?href="([^"]+)"', balise)
            self.assertIsNotNone(cible, balise)
            self.assertTrue(cible.group(1).startswith("data:"),
                            f"une <image> n'est pas inlinée : {balise[:100]}")

    def test_aucun_script_dans_le_svg(self):
        # Sécurité : le SVG servi est du contenu. Graphviz n'émet pas de script ; on le garde vrai.
        _, svg = rendu_svg(TOUT_RESOLU)
        self.assertNotIn("<script", svg.lower())

    def test_un_type_inconnu_rend_quand_meme_un_svg_auto_contenu(self):
        # La boîte de repli (SF-142-12) est dessinée par graphviz, sans <image> : le SVG reste valide,
        # et rien ne pointe vers le disque.
        inconnus, svg = rendu_svg({
            "title": "Mixte",
            "nodes": [{"id": "a", "type": "aws.machinqui-nexiste-pas", "label": "Inconnu"},
                      {"id": "b", "type": "aws.s3", "label": "Bucket"}],
            "edges": [{"from": "a", "to": "b"}],
        })
        self.assertEqual(["aws.machinqui-nexiste-pas"], inconnus)
        self.assertIn("<svg", svg)
        self.assertNotIn("file://", svg)
        for cible in hrefs(svg):
            self.assertFalse(cible.startswith("/"), cible[:80])


class InlineImagesNeTouchePasAuxLiens(unittest.TestCase):
    """L'inlining ne vise que les icônes-fichiers ; les liens et les data: sont laissés intacts."""

    def test_data_http_et_ancres_sont_preserves(self):
        dossier = tempfile.mkdtemp()
        chemin = os.path.join(dossier, "t.svg")
        deja = ('<svg xmlns:xlink="x">'
                '<image xlink:href="data:image/png;base64,AAAA"/>'
                '<a xlink:href="https://exemple.test"/>'
                '<use xlink:href="#glyphe"/>'
                '</svg>')
        with open(chemin, "w", encoding="utf-8") as handle:
            handle.write(deja)
        rendu = cloud.inline_images(chemin)
        self.assertIn("data:image/png;base64,AAAA", rendu)
        self.assertIn("https://exemple.test", rendu)
        self.assertIn("#glyphe", rendu)

    def test_une_icone_fichier_est_remplacee_par_un_data_uri(self):
        dossier = tempfile.mkdtemp()
        icone = os.path.join(dossier, "icone.png")
        # Un PNG 1×1 minimal, pour vérifier l'encodage sans dépendre d'une vraie icône.
        with open(icone, "wb") as handle:
            handle.write(bytes.fromhex(
                "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"
                "0000000a49444154789c6360000002000154a24f8f0000000049454e44ae426082"))
        chemin = os.path.join(dossier, "t.svg")
        with open(chemin, "w", encoding="utf-8") as handle:
            handle.write(f'<svg><image xlink:href="{icone}"/></svg>')
        rendu = cloud.inline_images(chemin)
        self.assertNotIn(icone, rendu)
        self.assertIn("data:image/png;base64,", rendu)

    def test_une_icone_illisible_est_laissee_telle_quelle(self):
        # Mieux vaut une icône manquante qu'un livrable sans schéma : l'inlining ne lève jamais.
        dossier = tempfile.mkdtemp()
        chemin = os.path.join(dossier, "t.svg")
        absente = os.path.join(dossier, "absente.png")
        with open(chemin, "w", encoding="utf-8") as handle:
            handle.write(f'<svg><image xlink:href="{absente}"/></svg>')
        rendu = cloud.inline_images(chemin)
        self.assertIn(absente, rendu)


class MainRendUnSvg(unittest.TestCase):
    """De bout en bout : le programme imprime le chemin du SVG, et le fichier est auto-contenu."""

    def test_main_imprime_le_svg_et_le_fichier_est_autonome(self):
        sortie = os.path.join(tempfile.mkdtemp(), "schema")
        capture = io.StringIO()
        vrai_stdin = sys.stdin

        class Entree:
            @staticmethod
            def read():
                return json.dumps(dict(TOUT_RESOLU, output=sortie))

        sys.stdin = Entree()
        try:
            with redirect_stdout(capture):
                code = cloud.main()
        finally:
            sys.stdin = vrai_stdin
        self.assertEqual(0, code, capture.getvalue())
        lignes = capture.getvalue().splitlines()
        self.assertTrue(lignes[0].endswith(".svg"), lignes)
        with open(lignes[0], encoding="utf-8") as handle:
            svg = handle.read()
        self.assertNotIn("file://", svg)
        self.assertIn("data:image/png;base64,", svg)


if __name__ == "__main__":
    unittest.main(verbosity=2)
