# Mini-spec — F-212 / SF-212-05 « Copier » = le message avec ses captures

## Identifiant
`F-212 / SF-212-05` — Feature parente : F-212 « Les captures de preuve » — Statut : `in-review` — 2026-10-28
Branche : `feat/SF-212-05-copier-avec-captures`

## Objectif
Quand un encart message a des captures de preuve jointes, son bouton principal **« Copier »** copie le message mis en forme **et** les images, en un seul collage dans Outlook ou Teams.

## Origine (PO, 2026-10-28, sujet data-platform chez CAGIP)
Encart ```` ```message preuves=<id1>,<id2> ```` avec deux captures jointes : rendu correct, mais « Copier » de l'encart ne copiait que le texte (« Contenu copié »). Le geste qui copiait aussi les images (« Copier le message avec les captures », SF-212-04) était un **second** bouton, sous les vignettes, moins visible. Le PO a cliqué le plus naturel.

## Comportement attendu
### Cas nominal
- `app-copy-block` reçoit une entrée `images` (`ClipboardImage[]` : `alt`, `width`, `load()`), fournie par le terminal à partir des captures jointes (`attachedProof`) ; le chargeur passe par la route isolée `GET /api/workspaces/{id}/proofs/{captureId}`. Le composant de copie ne connaît aucune URL.
- Encart riche (message / mail / doc) **avec** images : « Copier » (libellé accessible « Copier le message et ses N captures » / « … et sa capture ») écrit **une** entrée `ClipboardItem` :
  - `text/html` : le corps mis en forme (même HTML propre que F-203, sans l'objet) suivi d'un `<p><img src="data:image/png;base64,…" width="<largeur/2>" alt="Capture n"></p>` par capture ;
  - `text/plain` : le corps seul.
  Le HTML est passé en **promesse** au `ClipboardItem` (le geste de l'utilisateur reste valable pendant le chargement). Retour : « Message et N captures copiés. », bouton « Copié ».
- Geste secondaire **« Texte seul »** dans l'en-tête de l'encart : la copie F-203 inchangée (HTML propre + texte brut, sans image).
- Le bouton redondant « Copier le message avec les captures » sous les vignettes est **supprimé** ; les vignettes gardent « Copier l'image » et « Télécharger » (repli image par image).
- Sans images (cas général, chat, code) : rendu et copie strictement inchangés.
- Factorisation : `clipboardHtml` et la mécanique d'écriture (`writeMessageWithImages`) vivent dans `shared/message-clipboard.ts`, seule implémentation (plus de copie dans `proof-captures`).

### Choix du format (vérifié en local, voir « Vérifications »)
| Variante | Décision |
|---|---|
| `text/html` (images `data:`) + `text/plain` | **Retenue.** Chrome conserve les `data:` à l'écriture ; un collage natif les garde, dans un `contenteditable` comme dans RoosterJS (l'éditeur d'Outlook web et de Teams). |
| + `image/png` dans la même entrée | **Écartée.** Possible (une seule image), mais l'événement de collage expose alors un **fichier** (`types: [..., 'Files']`) : un hôte qui traite les fichiers d'abord (téléversement d'image de Teams) collerait l'image seule et perdrait le texte. Gain nul quand le HTML est lu. |
| URL https de l'application dans le HTML | **Écartée.** La route est authentifiée par jeton (le client de messagerie ne peut pas la lire) et un lien public exposerait la preuve. |
| Plusieurs entrées / plusieurs `image/png` | Impossible (Chrome : une seule entrée, un seul `image/png`). |
| Une image composée de toutes les captures | Non nécessaire tant que le HTML passe ; repli existant : « Texte seul » puis « Copier l'image » par capture. |

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Une image ne se charge pas | omise ; « Message et 1 capture copiés (1 indisponible). » |
| Presse-papiers riche indisponible (`write` / `ClipboardItem` absents) | copie du texte seul ; « Texte copié ; les captures ne se copient pas ici : copiez chaque image. » |
| Écriture refusée par le navigateur | repli texte seul ; « Texte copié ; les captures n'ont pas pu l'être : copiez chaque image. » |
| Bloc de code avec images (ne devrait pas arriver) | ignorées : copie texte brut inchangée |

## Contraintes
- L'image ne trahit rien : aucun pied, `alt` = « Capture n », aucune mention « générée » dans ce qui est copié (la ligne d'origine reste dans l'interface, jamais dans le presse-papiers).
- Les images ne repartent **jamais** vers le modèle : elles ne vont que du navigateur au presse-papiers.
- Charte : tokens existants (`--cg-space-*`, `--cg-divider`) ; « Texte seul » reprend le style des boutons d'en-tête ; en-tête en `flex-wrap` pour les écrans étroits.

## Critères d'acceptation
- [x] Encart avec captures : « Copier » écrit une entrée `text/html` + `text/plain` (sans `image/png`), le HTML porte le corps mis en forme et une image `data:image/png` par capture.
- [x] Retour « Message et N captures copiés. » ; image manquante omise et signalée.
- [x] « Texte seul » copie le message sans image ; refus / absence d'API → repli texte signalé.
- [x] Le second bouton « Copier le message avec les captures » n'existe plus ; « Copier l'image » et « Télécharger » restent.
- [x] Encart sans captures, chat, code : « Copier le bloc », aucun « Texte seul », comportement inchangé.
- [x] Rien de « généré » dans le HTML copié.

## Plan de test
- Unitaires Karma : `copy-block.component.spec.ts` (+5 : types MIME et contenu, image omise, Texte seul, refus → repli, sans images inchangé) ; `proof-captures.component.spec.ts` (bouton retiré, `proofClipboardImages` via la route isolée) ; `terminal-proof-attached.spec.ts` (libellé du « Copier » de l'encart, « Texte seul » seulement sur l'encart à captures).
- Intégration navigateur réel (Chrome 131 headless, puppeteer, hors dépôt) : le **code de production** `writeMessageWithImages` empaqueté, presse-papiers réel, relecture `navigator.clipboard.read()`, puis **collage natif** (Ctrl+V) dans un `contenteditable` et dans un éditeur **RoosterJS 9.61** muni de son `PastePlugin`.
- Isolation utilisateur : inchangée — images chargées par la route du propriétaire (testée côté serveur en SF-212-03).
- Suite Karma complète : aucun échec ajouté aux 48 préexistants.

## Vérifications
| Cible | Statut |
|---|---|
| Chrome 131 : contenu du presse-papiers après « Copier » | **Vérifié** : `text/plain` (corps) + `text/html` (corps + 2 `<img src="data:image/png…">`), 2 captures de 1,4 Mo chacune (pire cas) |
| Collage natif Chromium dans un `contenteditable` | **Vérifié** : texte, gras, liste et les 2 images |
| Collage natif dans RoosterJS 9.61 (éditeur d'Outlook web / Teams) | **Vérifié** : texte, gras, liste et les 2 images (`data:`) |
| Variante avec `image/png` | **Mesuré** : l'événement de collage expose un fichier en plus — d'où le rejet |
| Teams réel (web, bureau), Outlook web réel, Outlook bureau (Windows), Edge | **Non vérifié** (pas d'accès ici). Points de risque : téléversement des `data:` par Teams à l'envoi ; moteur Word d'Outlook bureau lisant le `CF_HTML` Windows. À confirmer par le PO au premier usage ; repli : « Texte seul » + « Copier l'image ». |

## Préoccupations transversales
Aucune (Auth, tenant, plans, navigation : non). Route d'image existante réutilisée telle quelle.

## Impacts
`shared/message-clipboard.ts` (nouveau : `clipboardHtml` déplacé, `writeMessageWithImages`), `chat/copy-block/copy-block.component.{ts,html,scss}`, `atelier/terminal/proof-captures.component.ts`, `atelier/terminal/atelier-terminal.component.{ts,html}`, specs associées. Aucune table, aucun endpoint, aucun backend.

## Hors périmètre
Image composée unique ; URL publiques signées ; copie avec captures dans le chat classique (pas de captures) ; vérification instrumentée sur Teams / Outlook réels.
