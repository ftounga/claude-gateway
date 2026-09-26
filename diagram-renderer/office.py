#!/usr/bin/env python3
"""
La construction d'un document .docx et d'un classeur .xlsx (F-129 / SF-129-07).

Même moteur que le deck (SF-129-05), et pour la même raison : sur un poste d'entreprise
`pip install` est bloqué, donc un fichier Office fabriqué PAR LE POSTE ne se fabrique pas. Ici, la
gateway reçoit une DESCRIPTION — jamais du code — et écrit le fichier elle-même. Le Python d'un
modèle n'a pas à s'exécuter sur notre infrastructure.

Entrée (sur l'entrée standard) :
  {"format": "docx", "title": "...", "subtitle": "...", "theme": "cg"|"plain",
   "blocks": [{"type": "heading"|"text"|"bullets"|"table"|"image"|"pagebreak", ...}],
   "images": {"nom": "<base64>"}, "output": "/tmp/office"}
  {"format": "xlsx", "title": "...", "theme": "cg"|"plain",
   "sheets": [{"name": "...", "columns": [...], "rows": [[...]]}], "output": "/tmp/office"}

Sortie : le chemin du fichier écrit, sur la première ligne (même convention que deck.py / cloud.py).

Le thème « cg » (défaut) applique la charte de l'application (docs/DESIGN_SYSTEM.md) : navy, filet
d'accent orange, Arial. Le thème « plain » rend le gabarit Office d'origine, inchangé — on ajoute
une sortie, on n'en ferme aucune.
"""
import base64
import io
import json
import sys

# --- Les bornes -------------------------------------------------------------------------------
# Refusées ICI et côté gateway : une borne tenue d'un seul côté finit par ne plus être tenue.
MAX_BLOCKS = 300
MAX_LINES = 200
MAX_LINE_CHARS = 4000
MAX_TABLE_ROWS = 40
MAX_TABLE_COLS = 12
MAX_IMAGES = 20

MAX_SHEETS = 12
MAX_ROWS = 5000
MAX_COLS = 40
MAX_CELL_CHARS = 1000
MAX_SHEET_NAME = 31

FORMAT_DOCX = "docx"
FORMAT_XLSX = "xlsx"
FORMATS = (FORMAT_DOCX, FORMAT_XLSX)

# --- La charte (docs/DESIGN_SYSTEM.md) ---------------------------------------------------------
# Aucune couleur n'est inventée ici : une charte qui se réinvente à chaque livrable n'en est plus une.
THEME_CHARTE = "cg"
THEME_PLAIN = "plain"
THEMES = (THEME_CHARTE, THEME_PLAIN)

NAVY = "0B1020"
ORANGE = "E07B39"
INK = "0F172A"
MUTED = "64748B"
WHITE = "FFFFFF"
BAND = "F5F6FA"

# Inter (la police de la charte) n'est installée ni sur un poste client ni dans l'image : Arial est
# le substitut universel. Écrire « Inter » ici donnerait un rendu de substitution imprévisible.
FONT = "Arial"

BLOCK_TYPES = ("heading", "text", "bullets", "table", "image", "pagebreak")


class Refused(Exception):
    """Un refus DIT : le message explique quoi corriger."""


def text_of(raw, what, limit=MAX_LINE_CHARS):
    value = ("" if raw is None else str(raw)).strip()
    if len(value) > limit:
        raise Refused("%s dépasse %d caractères." % (what, limit))
    return value


def theme_of(spec):
    """Le thème demandé, validé. Absent ⇒ la charte : c'est le défaut de l'application."""
    raw = ("" if spec.get("theme") is None else str(spec.get("theme"))).strip().lower()
    if not raw:
        return THEME_CHARTE
    if raw not in THEMES:
        raise Refused("Thème inconnu « %s ». Thèmes connus : %s." % (raw, ", ".join(THEMES)))
    return raw


def format_of(spec):
    raw = ("" if spec.get("format") is None else str(spec.get("format"))).strip().lower()
    if raw not in FORMATS:
        raise Refused("Format inconnu « %s ». Formats connus : %s." % (raw, ", ".join(FORMATS)))
    return raw


def decode_images(spec):
    """Les images arrivent encodées : la gateway les a lues DANS LE PROJET, sous l'isolation du tour."""
    images = spec.get("images") or {}
    if len(images) > MAX_IMAGES:
        raise Refused("Trop d'images (%d, maximum %d)." % (len(images), MAX_IMAGES))
    decoded = {}
    for name, payload in images.items():
        try:
            decoded[name] = base64.b64decode(payload)
        except Exception as error:  # noqa: BLE001
            raise Refused("Image « %s » illisible : %s" % (name, error)) from error
    return decoded


# --- Le document (.docx) -----------------------------------------------------------------------


def shade(cell, color):
    """Le fond d'une cellule : python-docx ne l'expose pas, il faut poser le w:shd soi-même."""
    from docx.oxml.ns import qn
    from docx.oxml import OxmlElement
    element = OxmlElement("w:shd")
    element.set(qn("w:val"), "clear")
    element.set(qn("w:fill"), color)
    cell._tc.get_or_add_tcPr().append(element)


def rule(paragraph, color):
    """Le filet d'accent : une bordure basse de paragraphe, le repère de marque le moins bavard."""
    from docx.oxml.ns import qn
    from docx.oxml import OxmlElement
    borders = OxmlElement("w:pBdr")
    bottom = OxmlElement("w:bottom")
    bottom.set(qn("w:val"), "single")
    bottom.set(qn("w:sz"), "18")
    bottom.set(qn("w:space"), "6")
    bottom.set(qn("w:color"), color)
    borders.append(bottom)
    paragraph._p.get_or_add_pPr().append(borders)


def style_runs(paragraph, color=None, size=None, bold=None, italic=None):
    from docx.shared import Pt, RGBColor
    for run in paragraph.runs:
        run.font.name = FONT
        if color is not None:
            run.font.color.rgb = RGBColor.from_string(color)
        if size is not None:
            run.font.size = Pt(size)
        if bold is not None:
            run.font.bold = bold
        if italic is not None:
            run.font.italic = italic


def usable_width(document):
    section = document.sections[0]
    return section.page_width - section.left_margin - section.right_margin


def build_document(spec, output, decoded):
    from docx import Document
    from docx.shared import Pt

    blocks = spec.get("blocks") or []
    if not blocks:
        raise Refused("Aucun bloc : un document vide n'apprend rien.")
    if len(blocks) > MAX_BLOCKS:
        raise Refused("Trop de blocs (%d, maximum %d)." % (len(blocks), MAX_BLOCKS))

    charte = theme_of(spec) == THEME_CHARTE
    document = Document()
    normal = document.styles["Normal"]
    normal.font.name = FONT
    normal.font.size = Pt(11)

    title = text_of(spec.get("title"), "Le titre du document")
    if title:
        heading = document.add_paragraph(title)
        style_runs(heading, color=NAVY if charte else None, size=26, bold=True)
        if charte:
            rule(heading, ORANGE)
        subtitle = text_of(spec.get("subtitle"), "Le sous-titre du document")
        if subtitle:
            line = document.add_paragraph(subtitle)
            style_runs(line, color=MUTED if charte else None, size=12, italic=True)

    for index, block in enumerate(blocks, start=1):
        kind = str(block.get("type") or "text").strip().lower()
        if kind not in BLOCK_TYPES:
            raise Refused("Bloc %d : type inconnu « %s ». Types connus : %s."
                          % (index, kind, ", ".join(BLOCK_TYPES)))

        if kind == "pagebreak":
            document.add_page_break()
            continue

        if kind == "heading":
            level = min(max(int(block.get("level") or 1), 1), 3)
            written = document.add_paragraph(
                text_of(block.get("text") or block.get("title"), "Le titre du bloc %d" % index))
            style_runs(written, color=NAVY if charte else None, size=[18, 15, 13][level - 1], bold=True)
            continue

        if kind in {"text", "bullets"}:
            lines = block.get("lines") or block.get("bullets") or block.get("text")
            if isinstance(lines, str) or lines is None:
                lines = [lines] if lines else []
            lines = [line for line in lines if line is not None]
            if len(lines) > MAX_LINES:
                raise Refused("Bloc %d : trop de lignes (%d, maximum %d)."
                              % (index, len(lines), MAX_LINES))
            if not lines:
                raise Refused("Bloc %d : un bloc « %s » sans ligne." % (index, kind))
            ordered = bool(block.get("ordered"))
            style = ("List Number" if ordered else "List Bullet") if kind == "bullets" else None
            for line in lines:
                content = text_of(line, "Une ligne du bloc %d" % index)
                written = document.add_paragraph(content, style=style) if style \
                    else document.add_paragraph(content)
                style_runs(written, color=INK if charte else None)
            continue

        if kind == "table":
            rows = block.get("rows") or []
            if not rows:
                raise Refused("Bloc %d : un tableau sans ligne." % index)
            if len(rows) > MAX_TABLE_ROWS:
                raise Refused("Bloc %d : tableau trop long (%d lignes, maximum %d)."
                              % (index, len(rows), MAX_TABLE_ROWS))
            columns = max(len(row or []) for row in rows)
            if columns > MAX_TABLE_COLS:
                raise Refused("Bloc %d : tableau trop large (%d colonnes, maximum %d)."
                              % (index, columns, MAX_TABLE_COLS))
            table = document.add_table(rows=len(rows), cols=columns)
            table.style = "Table Grid"
            for r, row in enumerate(rows):
                for c in range(columns):
                    value = (row or [])[c] if c < len(row or []) else ""
                    cell = table.cell(r, c)
                    cell.text = text_of(value, "Une cellule du bloc %d" % index, MAX_CELL_CHARS)
                    head = r == 0
                    for paragraph in cell.paragraphs:
                        style_runs(paragraph, color=(WHITE if head else INK) if charte else None,
                                   size=10, bold=head)
                    if charte:
                        shade(cell, NAVY if head else (BAND if r % 2 == 0 else WHITE))
            continue

        # kind == "image"
        name = str(block.get("image") or block.get("path") or "")
        if name not in decoded:
            raise Refused("Bloc %d : image « %s » absente du projet. Un document avec une image "
                          "manquante est pire qu'un document sans image." % (index, name))
        document.add_picture(io.BytesIO(decoded[name]), width=usable_width(document))
        caption = text_of(block.get("caption"), "La légende du bloc %d" % index)
        if caption:
            written = document.add_paragraph(caption)
            style_runs(written, color=MUTED if charte else None, size=9, italic=True)

    target = output if output.endswith(".docx") else output + ".docx"
    document.save(target)
    return target


# --- Le classeur (.xlsx) -----------------------------------------------------------------------

# Excel refuse ces caractères dans un nom d'onglet, et tronque à 31. Un classeur refusé à
# l'ouverture vaut moins qu'un onglet renommé : on nettoie, on ne casse pas.
FORBIDDEN_IN_SHEET_NAME = set("[]:*?/\\")


def sheet_name(raw, position, taken):
    cleaned = "".join(" " if c in FORBIDDEN_IN_SHEET_NAME else c
                      for c in ("" if raw is None else str(raw))).strip()
    cleaned = cleaned[:MAX_SHEET_NAME] or ("Feuille %d" % position)
    candidate, suffix = cleaned, 2
    while candidate.lower() in taken:
        tail = " (%d)" % suffix
        candidate = cleaned[:MAX_SHEET_NAME - len(tail)] + tail
        suffix += 1
    taken.add(candidate.lower())
    return candidate


def cell_value(raw, where):
    """Un nombre reste un NOMBRE : un classeur dont les montants sont du texte ne se somme pas."""
    if raw is None:
        return ""
    if isinstance(raw, bool):
        return raw
    if isinstance(raw, (int, float)):
        return raw
    return text_of(raw, where, MAX_CELL_CHARS)


def build_spreadsheet(spec, output):
    from openpyxl import Workbook
    from openpyxl.styles import Alignment, Font, PatternFill
    from openpyxl.utils import get_column_letter

    sheets = spec.get("sheets") or []
    if not sheets:
        raise Refused("Aucune feuille : un classeur vide n'apprend rien.")
    if len(sheets) > MAX_SHEETS:
        raise Refused("Trop de feuilles (%d, maximum %d)." % (len(sheets), MAX_SHEETS))

    charte = theme_of(spec) == THEME_CHARTE
    workbook = Workbook()
    workbook.remove(workbook.active)
    taken = set()

    for position, sheet in enumerate(sheets, start=1):
        columns = [c for c in (sheet.get("columns") or []) if c is not None]
        rows = sheet.get("rows") or []
        if not columns and not rows:
            raise Refused("Feuille %d : ni colonnes ni lignes." % position)
        if len(rows) > MAX_ROWS:
            raise Refused("Feuille %d : trop de lignes (%d, maximum %d)."
                          % (position, len(rows), MAX_ROWS))
        width = max([len(columns)] + [len(row or []) for row in rows])
        if width > MAX_COLS:
            raise Refused("Feuille %d : trop de colonnes (%d, maximum %d)."
                          % (position, width, MAX_COLS))

        worksheet = workbook.create_sheet(sheet_name(sheet.get("name"), position, taken))
        widths = [0] * width
        if columns:
            header = [text_of(c, "Un en-tête de la feuille %d" % position, MAX_CELL_CHARS)
                      for c in columns]
            worksheet.append(header)
            for index, value in enumerate(header):
                widths[index] = max(widths[index], len(value))
                if charte:
                    cell = worksheet.cell(row=1, column=index + 1)
                    cell.font = Font(name=FONT, bold=True, color=WHITE)
                    cell.fill = PatternFill("solid", fgColor=NAVY)
                    cell.alignment = Alignment(vertical="center")
        for row in rows:
            values = [cell_value(v, "Une cellule de la feuille %d" % position) for v in (row or [])]
            worksheet.append(values)
            for index, value in enumerate(values):
                widths[index] = max(widths[index], len(str(value)))
        for index in range(width):
            # Une colonne large de 200 rend le classeur illisible ; 60 suffit à tout ce qui se lit.
            worksheet.column_dimensions[get_column_letter(index + 1)].width = \
                min(max(widths[index] + 2, 10), 60)
        if columns:
            worksheet.freeze_panes = "A2"
            worksheet.auto_filter.ref = "A1:%s%d" % (get_column_letter(width),
                                                     max(len(rows) + 1, 1))

    target = output if output.endswith(".xlsx") else output + ".xlsx"
    workbook.save(target)
    return target


def build(spec, output):
    kind = format_of(spec)
    if kind == FORMAT_DOCX:
        return build_document(spec, output, decode_images(spec))
    return build_spreadsheet(spec, output)


def main():
    try:
        spec = json.loads(sys.stdin.read() or "{}")
        if not isinstance(spec, dict):
            raise Refused("La description doit être un objet.")
        print(build(spec, spec.get("output") or "/tmp/office"))
        return 0
    except Refused as refused:
        sys.stderr.write(str(refused))
        return 2
    except Exception as error:  # noqa: BLE001
        sys.stderr.write("%s: %s" % (type(error).__name__, error))
        return 3


if __name__ == "__main__":
    sys.exit(main())
