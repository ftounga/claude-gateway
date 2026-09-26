#!/usr/bin/env python3
"""
La construction d'une présentation .pptx (F-129 / SF-129-05), à la charte (F-129 / SF-129-04).

Comme pour les schémas cloud, ce programme ne reçoit JAMAIS de code : il lit une DESCRIPTION de deck
sur son entrée standard et construit le fichier lui-même. La raison est la même — le Python d'un
modèle n'a pas à s'exécuter sur notre infrastructure.

Entrée : {"title": "...", "theme": "cg"|"plain", "slides": [ {...}, ... ], "output": "/tmp/deck"}
Sortie : le chemin du .pptx écrit, sur la sortie standard.

Types de slides : "title" (titre + sous-titre), "bullets" (titre + puces), "image" (titre + image),
"table" (titre + tableau), "text" (titre + paragraphe). Chacun accepte "notes".

Le thème « cg » (défaut) applique la charte de l'application (docs/DESIGN_SYSTEM.md) : navy, accent
orange, pied de page numéroté, format 16:9. Le thème « plain » rend le gabarit Office d'origine,
inchangé — on ajoute une sortie, on n'en ferme aucune.
"""
import base64
import json
import os
import sys

MAX_SLIDES = 60
MAX_LINES = 40
MAX_LINE_CHARS = 400
MAX_IMAGES = 20
MAX_TABLE_ROWS = 20
MAX_TABLE_COLS = 8

# --- La charte (F-129 / SF-129-04) -------------------------------------------------------------
# Les couleurs viennent de docs/DESIGN_SYSTEM.md. Aucune couleur n'est inventée ici : une charte
# qui se réinvente à chaque livrable n'est plus une charte.
THEME_CHARTE = "cg"
THEME_PLAIN = "plain"
THEMES = (THEME_CHARTE, THEME_PLAIN)

NAVY = (0x0B, 0x10, 0x20)
ORANGE = (0xE0, 0x7B, 0x39)
ORANGE_LIGHT = (0xF0, 0x95, 0x4F)
BG = (0xF5, 0xF6, 0xFA)
INK = (0x0F, 0x17, 0x2A)
MUTED = (0x64, 0x74, 0x8B)
WHITE = (0xFF, 0xFF, 0xFF)

# Inter (la police de la charte) n'est installée ni sur un poste client ni dans l'image : Arial est
# le substitut universel. Écrire « Inter » ici donnerait un rendu de substitution imprévisible.
FONT = "Arial"


class Refused(Exception):
    """Un refus DIT : le message explique quoi corriger."""


def text_of(raw, what, limit=MAX_LINE_CHARS):
    value = ("" if raw is None else str(raw)).strip()
    if len(value) > limit:
        raise Refused(f"{what} dépasse {limit} caractères.")
    return value


def theme_of(spec):
    """Le thème demandé, validé. Absent ⇒ la charte : c'est le défaut de l'application."""
    raw = ("" if spec.get("theme") is None else str(spec.get("theme"))).strip().lower()
    if not raw:
        return THEME_CHARTE
    if raw not in THEMES:
        raise Refused(f"Thème inconnu « {raw} ». Thèmes connus : " + ", ".join(THEMES) + ".")
    return raw


def rgb(color):
    from pptx.dml.color import RGBColor
    return RGBColor(*color)


def paint_background(slide, color):
    fill = slide.background.fill
    fill.solid()
    fill.fore_color.rgb = rgb(color)


def style_frame(frame, color, size=None, bold=None):
    """Applique police, couleur et taille à tout un cadre de texte (les runs déjà posés)."""
    from pptx.util import Pt
    for paragraph in frame.paragraphs:
        if size is not None:
            paragraph.font.size = Pt(size)
        paragraph.font.name = FONT
        paragraph.font.color.rgb = rgb(color)
        if bold is not None:
            paragraph.font.bold = bold
        for run in paragraph.runs:
            run.font.name = FONT
            run.font.color.rgb = rgb(color)
            if size is not None:
                run.font.size = Pt(size)
            if bold is not None:
                run.font.bold = bold


def place(shape, left, top, width, height):
    shape.left, shape.top, shape.width, shape.height = left, top, width, height


def add_rule(slide, left, top, width):
    """Le filet d'accent : le repère de marque le moins bavard qui soit."""
    from pptx.enum.shapes import MSO_SHAPE
    from pptx.util import Pt
    bar = slide.shapes.add_shape(MSO_SHAPE.RECTANGLE, left, top, width, Pt(6))
    bar.fill.solid()
    bar.fill.fore_color.rgb = rgb(ORANGE)
    bar.line.fill.background()
    bar.shadow.inherit = False
    return bar


def add_footer(prs, slide, deck_title, number):
    """Pied de page : d'où vient la slide (à gauche), où l'on en est (à droite)."""
    from pptx.enum.text import PP_ALIGN
    from pptx.util import Inches
    top = prs.slide_height - Inches(0.62)
    half = (prs.slide_width - Inches(1.6)) // 2
    left_box = slide.shapes.add_textbox(Inches(0.8), top, half, Inches(0.35))
    left_box.text_frame.word_wrap = False
    left_box.text_frame.text = deck_title
    style_frame(left_box.text_frame, MUTED, size=10)
    right_box = slide.shapes.add_textbox(Inches(0.8) + half, top, half, Inches(0.35))
    right_box.text_frame.word_wrap = False
    right_box.text_frame.text = str(number)
    style_frame(right_box.text_frame, MUTED, size=10)
    right_box.text_frame.paragraphs[0].alignment = PP_ALIGN.RIGHT


def fit_picture(picture, left, top, max_width, max_height):
    """Une image qui déborde de la slide est pire qu'une image plus petite : on la contient."""
    width, height = picture.width, picture.height
    if width <= 0 or height <= 0:
        return
    scale = min(max_width / width, max_height / height)
    picture.width = int(width * scale)
    picture.height = int(height * scale)
    picture.left = left + (max_width - picture.width) // 2
    picture.top = top + (max_height - picture.height) // 2


def build(spec, output):
    from pptx import Presentation
    from pptx.util import Inches, Pt

    slides = spec.get("slides") or []
    if not slides:
        raise Refused("Aucune slide : un deck vide n'apprend rien.")
    if len(slides) > MAX_SLIDES:
        raise Refused(f"Trop de slides ({len(slides)}, maximum {MAX_SLIDES}).")

    theme = theme_of(spec)
    charte = theme == THEME_CHARTE
    deck_title = text_of(spec.get("title"), "Le titre du deck")

    images = spec.get("images") or {}
    if len(images) > MAX_IMAGES:
        raise Refused(f"Trop d'images ({len(images)}, maximum {MAX_IMAGES}).")

    # Les images arrivent encodées : la gateway les a lues DANS LE PROJET, sous l'isolation du tour.
    decoded = {}
    for name, payload in images.items():
        try:
            decoded[name] = base64.b64decode(payload)
        except Exception as error:  # noqa: BLE001
            raise Refused(f"Image « {name} » illisible : {error}") from error

    prs = Presentation()
    if charte:
        # Un livrable de 2026 se projette en 16:9. Le gabarit d'origine (4:3) reste sous « plain ».
        prs.slide_width = Inches(13.333)
        prs.slide_height = Inches(7.5)
    blank = prs.slide_layouts[6]
    title_only = prs.slide_layouts[5]
    title_content = prs.slide_layouts[1]
    title_slide = prs.slide_layouts[0]

    margin = Inches(0.8)
    content_width = prs.slide_width - 2 * margin
    body_top = Inches(1.95)
    body_height = prs.slide_height - body_top - Inches(0.9)

    for index, slide in enumerate(slides, start=1):
        kind = str(slide.get("type") or "bullets").strip().lower()
        heading = text_of(slide.get("title"), f"Le titre de la slide {index}")

        if kind == "title":
            made = prs.slides.add_slide(title_slide)
            made.shapes.title.text = heading
            subtitle = text_of(slide.get("subtitle"), f"Le sous-titre de la slide {index}")
            if subtitle and len(made.placeholders) > 1:
                made.placeholders[1].text = subtitle
            if charte:
                paint_background(made, NAVY)
                place(made.shapes.title, margin, Inches(2.4), content_width, Inches(1.5))
                style_frame(made.shapes.title.text_frame, WHITE, size=40, bold=True)
                add_rule(made, margin, Inches(4.0), Inches(2.0))
                if subtitle and len(made.placeholders) > 1:
                    place(made.placeholders[1], margin, Inches(4.35), content_width, Inches(1.0))
                    style_frame(made.placeholders[1].text_frame, ORANGE_LIGHT, size=20)

        elif kind in {"bullets", "text"}:
            made = prs.slides.add_slide(title_content)
            made.shapes.title.text = heading
            lines = slide.get("bullets") if kind == "bullets" else [slide.get("text")]
            lines = [line for line in (lines or []) if line is not None]
            if len(lines) > MAX_LINES:
                raise Refused(f"Slide {index} : trop de lignes ({len(lines)}, maximum {MAX_LINES}).")
            body = made.placeholders[1].text_frame
            body.clear()
            for position, line in enumerate(lines):
                content = text_of(line, f"Une ligne de la slide {index}")
                paragraph = body.paragraphs[0] if position == 0 else body.add_paragraph()
                paragraph.text = content
                paragraph.level = min(int(slide.get("level", 0) or 0), 4) if kind == "bullets" else 0
            if charte:
                dress(prs, made, deck_title, index, margin, content_width)
                place(made.placeholders[1], margin, body_top, content_width, body_height)
                style_frame(body, INK, size=18)

        elif kind == "image":
            made = prs.slides.add_slide(title_only)
            made.shapes.title.text = heading
            name = str(slide.get("image") or "")
            if name not in decoded:
                raise Refused(f"Slide {index} : image « {name} » absente du projet. "
                              "Un deck avec une image manquante est pire qu'un deck sans image.")
            path = f"/tmp/deck-image-{index}"
            with open(path, "wb") as handle:
                handle.write(decoded[name])
            if charte:
                dress(prs, made, deck_title, index, margin, content_width)
                picture = made.shapes.add_picture(path, margin, body_top, width=content_width)
                caption = text_of(slide.get("caption"), f"La légende de la slide {index}")
                room = body_height - (Inches(0.5) if caption else 0)
                fit_picture(picture, margin, body_top, content_width, room)
                if caption:
                    box = made.shapes.add_textbox(margin, body_top + room, content_width, Inches(0.4))
                    box.text_frame.text = caption
                    style_frame(box.text_frame, MUTED, size=12)
            else:
                made.shapes.add_picture(path, Inches(0.8), Inches(1.7), width=Inches(8.4))
                caption = text_of(slide.get("caption"), f"La légende de la slide {index}")
                if caption:
                    box = made.shapes.add_textbox(Inches(0.8), Inches(6.4), Inches(8.4), Inches(0.6))
                    box.text_frame.text = caption
                    box.text_frame.paragraphs[0].runs[0].font.size = Pt(12)

        elif kind == "table":
            made = prs.slides.add_slide(title_only)
            made.shapes.title.text = heading
            rows = slide.get("rows") or []
            if not rows:
                raise Refused(f"Slide {index} : un tableau sans ligne.")
            if len(rows) > MAX_TABLE_ROWS or max(len(r or []) for r in rows) > MAX_TABLE_COLS:
                raise Refused(f"Slide {index} : tableau trop grand "
                              f"(maximum {MAX_TABLE_ROWS} lignes x {MAX_TABLE_COLS} colonnes).")
            columns = max(len(r or []) for r in rows)
            if charte:
                dress(prs, made, deck_title, index, margin, content_width)
                height = min(body_height, Inches(0.5) * len(rows))
                shape = made.shapes.add_table(len(rows), columns, margin, body_top,
                                              content_width, height)
            else:
                shape = made.shapes.add_table(len(rows), columns, Inches(0.6), Inches(1.7),
                                              Inches(8.8), Inches(0.8 * len(rows)))
            for r, row in enumerate(rows):
                for c in range(columns):
                    cell_value = (row or [])[c] if c < len(row or []) else ""
                    cell = shape.table.cell(r, c)
                    cell.text = text_of(cell_value, f"Une cellule de la slide {index}")
                    if charte:
                        head = r == 0
                        cell.fill.solid()
                        cell.fill.fore_color.rgb = rgb(NAVY if head else WHITE)
                        style_frame(cell.text_frame, WHITE if head else INK, size=12, bold=head)

        else:
            raise Refused(f"Slide {index} : type inconnu « {kind} ». "
                          "Types connus : title, bullets, text, image, table.")

        notes = text_of(slide.get("notes"), f"Les notes de la slide {index}", 4000)
        if notes:
            made.notes_slide.notes_text_frame.text = notes

    target = output if output.endswith(".pptx") else output + ".pptx"
    prs.save(target)
    return target


def dress(prs, slide, deck_title, index, margin, content_width):
    """L'habillage commun d'une slide de contenu : fond, titre, filet, pied de page."""
    from pptx.util import Inches
    paint_background(slide, BG)
    place(slide.shapes.title, margin, Inches(0.55), content_width, Inches(1.0))
    style_frame(slide.shapes.title.text_frame, NAVY, size=28, bold=True)
    add_rule(slide, margin, Inches(1.62), Inches(1.8))
    add_footer(prs, slide, deck_title, index)


def main():
    try:
        spec = json.loads(sys.stdin.read() or "{}")
        if not isinstance(spec, dict):
            raise Refused("La description doit être un objet.")
        print(build(spec, spec.get("output") or "/tmp/deck"))
        return 0
    except Refused as refused:
        sys.stderr.write(str(refused))
        return 2
    except Exception as error:  # noqa: BLE001
        sys.stderr.write(f"{type(error).__name__}: {error}")
        return 3


if __name__ == "__main__":
    sys.exit(main())
