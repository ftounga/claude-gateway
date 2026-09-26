#!/usr/bin/env python3
"""
F-129 / SF-129-04 — la charte du deck, vérifiée sur un VRAI .pptx.

Ces tests construisent le fichier et le relisent : un deck « à la charte » qui ne s'ouvre pas ne vaut
rien. Ils tournent sans réseau ; ils ont besoin de `python-pptx` (présent dans l'image du service).

Usage : python3 tests/test_deck.py
"""
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import deck  # noqa: E402
from pptx import Presentation  # noqa: E402
from pptx.util import Inches  # noqa: E402


def spec(**extra):
    base = {
        "title": "Cible AWS",
        "slides": [
            {"type": "title", "title": "Cible AWS", "subtitle": "Revue d'architecture"},
            {"type": "bullets", "title": "Ce qui change", "bullets": ["Un", "Deux"], "notes": "dire"},
            {"type": "table", "title": "Coûts", "rows": [["Poste", "€"], ["S3", "12"]]},
        ],
    }
    base.update(extra)
    return base


def built(description):
    directory = tempfile.mkdtemp(prefix="cg-deck-test-")
    path = deck.build(description, os.path.join(directory, "deck"))
    return Presentation(path), path


class ThemeTest(unittest.TestCase):

    def test_la_charte_est_le_defaut(self):
        """CA1 : sans « theme », le deck sort à la charte — fond clair, titre navy, filet orange."""
        prs, path = built(spec())
        self.assertTrue(path.endswith(".pptx"))
        self.assertEqual(3, len(prs.slides))
        content = prs.slides[1]
        self.assertEqual(deck.rgb(deck.BG), content.background.fill.fore_color.rgb)
        title = content.shapes.title
        self.assertEqual(deck.rgb(deck.NAVY), title.text_frame.paragraphs[0].runs[0].font.color.rgb)
        rules = [s for s in content.shapes if getattr(s, "fill", None) is not None
                 and s.fill.type == 1 and s.fill.fore_color.rgb == deck.rgb(deck.ORANGE)]
        self.assertTrue(rules, "le filet d'accent orange doit être posé")

    def test_la_slide_de_titre_est_navy(self):
        """CA2 : la slide de titre est inversée — fond navy, titre blanc."""
        prs, _ = built(spec())
        cover = prs.slides[0]
        self.assertEqual(deck.rgb(deck.NAVY), cover.background.fill.fore_color.rgb)
        self.assertEqual(deck.rgb(deck.WHITE),
                         cover.shapes.title.text_frame.paragraphs[0].runs[0].font.color.rgb)

    def test_le_pied_de_page_numerote(self):
        """CA3 : chaque slide de contenu porte le titre du deck et son numéro."""
        prs, _ = built(spec())
        texts = [s.text_frame.text for s in prs.slides[1].shapes if s.has_text_frame]
        self.assertIn("Cible AWS", texts)
        self.assertIn("2", texts)
        texts3 = [s.text_frame.text for s in prs.slides[2].shapes if s.has_text_frame]
        self.assertIn("3", texts3)

    def test_plain_rend_le_gabarit_office(self):
        """CA4 : « plain » ne pose ni fond, ni filet, ni pied de page, et reste en 4:3."""
        prs, _ = built(spec(theme="plain"))
        self.assertEqual(Inches(10), prs.slide_width)
        content = prs.slides[1]
        self.assertNotEqual(1, content.background.fill.type, "aucun fond posé sous « plain »")
        self.assertFalse([s for s in content.shapes if getattr(s, "fill", None) is not None
                          and s.fill.type == 1], "aucun filet d'accent sous « plain »")
        self.assertEqual(["Ce qui change", "Un\nDeux"],
                         [s.text_frame.text for s in content.shapes if s.has_text_frame])

    def test_seize_neuvieme_sous_la_charte(self):
        """La charte projette en 16:9 ; « plain » garde le 4:3 d'origine."""
        prs, _ = built(spec())
        self.assertEqual(Inches(13.333), prs.slide_width)
        self.assertEqual(Inches(7.5), prs.slide_height)

    def test_theme_inconnu_refuse_nomme(self):
        """CA5 : un thème inconnu est refusé avec son nom et la liste des thèmes connus."""
        with self.assertRaises(deck.Refused) as refused:
            built(spec(theme="corporate"))
        message = str(refused.exception)
        self.assertIn("corporate", message)
        self.assertIn("cg", message)
        self.assertIn("plain", message)

    def test_le_tableau_porte_len_tete_navy(self):
        """La première ligne du tableau est l'en-tête : navy plein, texte blanc."""
        prs, _ = built(spec())
        table = [s for s in prs.slides[2].shapes if s.has_table][0].table
        self.assertEqual(deck.rgb(deck.NAVY), table.cell(0, 0).fill.fore_color.rgb)
        self.assertEqual(deck.rgb(deck.WHITE), table.cell(1, 0).fill.fore_color.rgb)

    def test_les_notes_et_les_bornes_ne_bougent_pas(self):
        """Non-régression : les notes restent posées, les refus existants restent nommés."""
        prs, _ = built(spec())
        self.assertIn("dire", prs.slides[1].notes_slide.notes_text_frame.text)
        with self.assertRaises(deck.Refused):
            built({"title": "vide", "slides": []})
        with self.assertRaises(deck.Refused):
            built({"title": "x", "slides": [{"type": "martien", "title": "?"}]})


class ImageTest(unittest.TestCase):

    PNG = ("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ"
           "AAAABJRU5ErkJggg==")

    def test_une_image_est_contenue_dans_la_slide(self):
        """Une image trop haute est réduite, jamais laissée à déborder."""
        description = {"title": "Archi", "images": {"a.png": self.PNG},
                       "slides": [{"type": "image", "title": "Cible", "image": "a.png",
                                   "caption": "la cible"}]}
        prs, _ = built(description)
        picture = [s for s in prs.slides[0].shapes if s.shape_type == 13][0]
        self.assertLessEqual(picture.left + picture.width, prs.slide_width)
        self.assertLessEqual(picture.top + picture.height, prs.slide_height)

    def test_une_image_absente_est_refusee(self):
        with self.assertRaises(deck.Refused) as refused:
            built({"title": "Archi", "slides": [{"type": "image", "title": "x", "image": "b.png"}]})
        self.assertIn("b.png", str(refused.exception))


if __name__ == "__main__":
    unittest.main(verbosity=2)
