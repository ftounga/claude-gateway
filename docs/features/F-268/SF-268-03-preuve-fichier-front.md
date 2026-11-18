# Mini-spec — F-268 / SF-268-03 Front : la preuve fichier dans le fil et dans l'encart

## Identifiant
`F-268 / SF-268-03` — Feature parente : F-268 « La preuve fichier, jointe au message » — Statut : `done` — 2026-11-18
Branche : `feat/SF-268-03-preuve-fichier-front` — Dépend de SF-268-02 (champ `files` du bloc `proof`).

## Objectif
Le bloc « Captures de preuve » montre les extraits de fichiers (chemin, lignes, lignes surlignées) sous leurs vignettes, et l'encart ```message preuves=…``` joint captures terminal et fichiers mêlés, dans l'ordre de la balise, que « Copier » emporte en un geste avec le texte.

## Comportement attendu
### Cas nominal
- Modèle : `AtelierProofFile {captureId, path, firstLine, lastLine, totalLines, highlights}` ; `AtelierProofBlock.files?` (absent sur les blocs antérieurs).
- Sous les vignettes : liste « Fichiers montrés » — icône, `chemin`, « lignes a–b sur N · surlignée(s) x, y ». La liste des commandes n'est plus affichée vide.
- Origine : « … du contenu réel des fichiers du poste » (fichiers seuls) / « … de la sortie et des fichiers réels du poste » (mélange) / inchangée sinon.
- Encart `preuves=` : captures dans l'ordre de la balise (inchangé, mécanisme F-212) ; `files` des captures désignées joints ; repli « dernier encart du tour » joint aussi les fichiers.
- « Copier » de l'encart : inchangé (SF-212-05) — texte + toutes les images (terminal et fichiers) en une entrée `text/html` ; texte alternatif d'une image de fichier = son chemin.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| bloc antérieur sans `files` | rendu identique à avant |
| capture désignée hors du fil chargé | repli par la route isolée (SF-212-06, inchangé) |
| image indisponible | « Image indisponible. » (inchangé) |

## Critères d'acceptation
- [x] Fichier seul : vignette, chemin, lignes et surlignage affichés ; pas de liste de commandes vide ; origine « fichiers ».
- [x] Mélange terminal + fichier : deux relevés, origine commune.
- [x] Bloc antérieur : rendu inchangé.
- [x] `preuves=<fichier>,<t1>,<t2>` : 3 captures dans cet ordre, fichiers joints, `clipboardImagesOf` rend 3 images (alt = chemin pour le fichier).

## Plan de test
- Unitaires/composant : `proof-captures.component.spec.ts` (4 cas F-268) ; `terminal-proof-attached.spec.ts` (mélange dans l'encart).
- Intégration : l'encart relu depuis l'historique (`toThreadItem`) est couvert par SF-212-06, le champ `files` passe tel quel.
- Isolation `user_id` : images servies par la route isolée existante (testée côté backend, SF-268-02).

## Impact
- `core/models/atelier.models.ts`, `atelier/terminal/proof-captures.component.ts`, `atelier/terminal/atelier-terminal.component.ts` (`attachedProof`).
- Design system : couleurs/espacements par tokens existants (`--cg-space-*`), icône Material. Aucune route, aucun guard.
- Préoccupations transversales : aucune.

## Hors périmètre
- Rendu de l'image (SF-268-01), lecture et stockage (SF-268-02).
