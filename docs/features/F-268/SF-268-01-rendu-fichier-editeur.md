# Mini-spec — F-268 / SF-268-01 Rendu : `/file-shot` (un fichier au rendu éditeur)

## Identifiant
`F-268 / SF-268-01` — Feature parente : F-268 « La preuve fichier, jointe au message » — Statut : `done` — 2026-11-18
Branche : `feat/SF-268-01-rendu-fichier-editeur`

## Objectif
Le service `diagram-renderer` transforme un extrait RÉEL de fichier (lu sur le poste par la gateway, SF-268-02) en image PNG d'un éditeur sombre : fond navy `#0B1020`, coloration syntaxique, numéros de ligne, ligne(s) en cause surlignée(s), annotation courte.

## Comportement attendu
### Cas nominal
- `POST /file-shot` `{files:[{path, firstLine, lines[], highlights[], note, totalLines}]}` → `200 {images:[{png(base64), width, height}]}` — **une image par fichier**, dans l'ordre.
- Fenêtre d'éditeur : barre à onglet (nom du fichier) + fil d'Ariane (chemin), gouttière de numéros de ligne (à partir de `firstLine`), texte en police mono libre (Cascadia Mono / DejaVu Sans Mono), tabulations rendues sur 2 espaces.
- Coloration par extension (`.tf/.tfvars/.hcl`, `.yaml/.yml`, `.json`, `.js/.ts/.mjs`, `.java`, `.py`, `.sh/.bash`, `.sql`, `.xml/.html`, `.properties/.ini/.conf/.env`, `Dockerfile`, `.go`, `.kt`, `.cs`, `.rb`, `.php`, autres = texte brut) : commentaires, chaînes, nombres, mots-clés, constantes, attributs/clés, types/blocs.
- Lignes de `highlights` : fond orange translucide + liseré orange à gauche + numéro en orange vif.
- `note` (≤ 200 car.) : bulle d'annotation sous la dernière ligne surlignée (ou sous l'extrait s'il n'y en a pas), liseré orange, flèche vers la ligne.
- Coupures visibles : `⋯` en tête/pied si l'extrait ne commence pas à 1 / ne va pas jusqu'à `totalLines`.
- Hors ligne (chromium refuse toute requête), échelle ×2, fond transparent autour de la fenêtre — même photographe que `/terminal-shot`.
- Rien dans l'image ne dit qu'elle est générée (pas de pied, pas de mention).

### Cas d'erreur
| Situation | Comportement | HTTP |
|---|---|---|
| corps non JSON | `Corps JSON invalide.` | 400 |
| 0 ou > 6 fichiers, chemin vide, `lines` absent | raison explicite | 400 |
| extrait > 80 lignes | tronqué à 80 (pas d'erreur) | 200 |
| ligne > 400 caractères | coupée avec `…` | 200 |
| corps > 2 Mo | `Extrait trop volumineux` | 413 |
| chromium > 30 s | `Capture trop longue` | 504 |

## Contraintes de validation
fichiers 1..6 ; chemin ≤ 512 ; 80 lignes par image ; 400 caractères par ligne ; note ≤ 200 ; `highlights` entiers hors fenêtre ignorés ; `firstLine` entier ≥ 1 (défaut 1).

## Critères d'acceptation
- [x] Langage détecté par extension / nom (`Dockerfile`), texte brut sinon.
- [x] HCL : `resource "aws_s3_bucket" "x" {` → mot-clé de bloc + chaînes ; `acl = "public-read"` → attribut + chaîne ; `# commentaire` et `//` en commentaire ; `true/false/null` en constante.
- [x] YAML : clé `nom:` en attribut ; JSON : clé `"nom":` en attribut, valeur chaîne en chaîne.
- [x] Commentaire de bloc `/* … */` sur plusieurs lignes reste coloré d'une ligne à l'autre.
- [x] Numéros de ligne à partir de `firstLine` ; lignes surlignées marquées (classe `hl`) ; note présente une fois, échappée.
- [x] Texte échappé partout (aucune injection HTML par le contenu, le chemin ou la note).
- [x] Fond `#0B1020` ; une `data-shot` par fichier.
- [x] Erreurs 400 / 413 conformes.
- [x] Image construite et regardée à l'œil (fichier Terraform) sur le conteneur ou Chromium local.

## Plan de test
- **Unitaires** (`node --test diagram-renderer/tests/test_editor.js`) : détection de langage, jetons HCL/YAML/JSON/Java, commentaire de bloc multi-lignes, échappement, validation (`parseFiles`), numéros/surlignage/note dans le HTML, coupures `⋯`.
- **Intégration** : photographie réelle par chromium local d'un `main.tf` (3 lignes surlignées + note) — PNG regardé.
- **Isolation utilisateur** : sans objet ici (service sans état, aucune donnée de compte) ; l'isolation `user_id` est portée par SF-268-02 (lecture et stockage).

## Impact
- Fichiers : `diagram-renderer/editor.js` (nouveau, pur), `server.js` (route `/file-shot`), `Dockerfile` (COPY), `tests/test_editor.js`.
- Aucune table, aucun endpoint gateway. **Image `diagram-renderer` à redéployer à part.**
- Préoccupations transversales : aucune (Auth/tenant/plans/navigation non touchés).

## Hors périmètre
- Lecture du fichier, masquage des secrets, stockage, outil `capturer_preuves` (SF-268-02).
- Affichage dans le fil et l'encart (SF-268-03).
- Thème clair, choix de thème par client.
