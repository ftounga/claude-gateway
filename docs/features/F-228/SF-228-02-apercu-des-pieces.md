# SF-228-02 — L'aperçu d'une pièce jointe, au clic

**Feature :** F-228 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-228-02-apercu-des-pieces` · **Dépend de :** SF-228-01 (mergée)

## Objectif
Un clic sur une pièce posée dans le fil (capture, PDF, vidéo…) l'ouvre en aperçu, sans quitter le terminal.

## Comportement
1. **La puce devient un bouton** quand la pièce a un identifiant de dépôt (historique ou envoi courant) ; icône selon le type (image, PDF, vidéo, audio, tableur, fichier). Une pièce d'avant (sans identifiant) reste une puce inerte. **Au clic, jamais au survol** (le survol lirait le poste, et rien au toucher).
2. **Boîte d'aperçu** (`MatDialog`) qui lit `GET …/deposits/{id}/content` en blob :
   - **image** : ajustée à la fenêtre, **zoom** au clic (taille réelle), **[Copier]** (presse-papiers ; repli téléchargement) et **[Télécharger]** — même geste que les captures F-212 ;
   - **PDF** : le lecteur du navigateur (`<object>` sur le blob), repli [Télécharger] ;
   - **vidéo, audio** : `<video>` / `<audio>` natifs ;
   - **SVG** : uniquement en `<img>` (aucun script) ;
   - **autres** (HTML, docx, zip…) : « Pas d'aperçu » + [Télécharger]. Jamais de HTML brut.
3. L'envoi courant porte l'identifiant de dépôt des pièces jointes (la puce s'ouvre aussi avant rechargement).

## Erreurs
| Situation | Comportement |
|---|---|
| Poste hors ligne / pièce disparue / > 20 Mo | message de la passerelle dans la boîte |
| Pièce inconnue | « Pièce introuvable. » |
| Copie refusée par le navigateur | téléchargement en repli |

## Critères d'acceptation
- [x] Puce avec identifiant = bouton qui ouvre l'aperçu ; sans = puce inerte.
- [x] Image en blob, zoom, [Copier], [Télécharger].
- [x] Vidéo : lecteur natif ; SVG : `<img>`, jamais inséré dans la page.
- [x] Erreur de la passerelle affichée.

## Plan de test
`deposit-preview.spec` (6 tests : vues par extension, image, vidéo, SVG, erreur, puce cliquable) ; suite front complète, build.

## Impact
`deposit-preview.ts` (nouveau, pur), `deposit-preview-dialog.component` (nouveau), `atelier-terminal` (puce, ouverture, icône), `atelier.component` (identifiant à l'envoi), `atelier.models` (`DepositedFileRef.id`), `atelier-terminal-deposit.component.scss`. Préoccupations transversales : aucune.

## Hors périmètre
Tableurs csv/xlsx (SF-228-03) ; docx.
