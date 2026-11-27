# Cadrage F-278 — Galerie des captures d'écran du sujet, à faire défiler dans le fil

- **Origine** : besoin `a6b6badb` (EVOLUTION, 27/11/2026), sujet data-platform (~110 captures dans `.atelier/entrees`).
- **Mots du PO** : « il faudrait un outil qui puisse me permettre d'afficher des photos comme ça, qui sont dans le chat.
  Là, je t'ai posé la question, je m'attendais à ce que les photos s'affichent et que je puisse les défiler d'un coup
  d'œil. Vu qu'on travaille beaucoup avec les captures d'écran, ça doit être faisable rapidement. »
- **Constat** : interrogé, l'agent n'a pu que **citer des noms de fichiers**. Aucun de ses outils ne montre une image
  du poste dans le fil.
- **Décisions prises par défaut** (le PO veut qu'on tranche seul) : voir §4.

## 1. Ce qui existe déjà (vérifié dans le code le 27/11)

| Brique | Ce qu'elle fait | Pourquoi elle ne suffit pas |
|---|---|---|
| F-212 / F-268 `capturer_preuves` | Rejoue des commandes / photographie un extrait de **texte** ; images PNG rangées (`proof_captures`, stockage `preuves/{user}/{ws}/`), bloc riche `proof` dans le fil (vignettes, zoom, copier). | Ne montre que des rendus terminal / éditeur ; ne lit jamais une image du poste. |
| F-109 `page_publish` (+ SF-109-06, SF-142-22) | Une page HTML ; accepte des images de la machine, inlinées en `data:` (CSP sandbox des pages). | Lourd (une page à composer, iframe sandbox), pas un carrousel ; détourne l'outil. |
| F-237 index des pièces + `PieceThumbnails` | Les **dépôts** (`atelier_deposited_files`) apparaissent dans la galerie **Jarvis** ; vignette 320 px. | Réservé à l'écran Jarvis ; ne couvre que les fichiers déposés par l'application (pas ceux copiés à la main, ni `notes/`) ; l'agent de l'Atelier ne peut pas le déclencher dans le fil. |
| Runner `read_file_bytes` (F-110/F-213) | Lecture binaire par tranches de 384 Kio, plafond 10 Mo hors médias. | Primitive de transport : **réutilisée telle quelle**. |
| Runner `glob` (F-121) | Fichiers d'un motif, **triés par date de modification décroissante**. | Réutilisé pour le mode « dossier » (les plus récentes d'abord). |
| Bloc riche du fil (transcription `AtelierTurnReport.Block`, événement SSE, relecture au rechargement) | Mécanique commune des preuves, vidéos, pages… | Réutilisée : un champ `gallery` de plus, sur le modèle exact de `proof`. |

**Conclusion** : la feature se réduit à **un outil de plus qui branche l'existant** — transport runner existant,
stockage `WorkspaceStorage` existant, mécanique de bloc riche existante — et **un composant d'affichage**
(carrousel). **Aucune modification du runner** : aucun poste à mettre à jour.

## 2. Ce que l'utilisateur verra

L'agent appelle `galerie_images` ; dans le fil apparaît un bloc **« Galerie · <titre> · N images »** :

- une **grande image** (l'aperçu courant), avec au-dessous **son nom, sa date et la légende posée par l'agent** ;
- **précédent / suivant** (boutons, flèches du clavier ←/→ quand le bloc a le focus), compteur « 3 / 24 » ;
- une **bande de vignettes** cliquables qui défile horizontalement : on parcourt d'un coup d'œil ;
- **Agrandir** (plein écran du fil, Échap pour sortir), **Copier l'image**, **Télécharger** (comme les preuves) ;
- le bloc survit au rechargement, comme le reste du fil.

## 3. L'outil `galerie_images`

```
galerie_images {
  titre*:  string                          // « Captures en attente de réponse »
  images:  [{ chemin*, legende, date }]    // ≤ 30, dans l'ordre voulu
  dossier: string                          // à défaut d'images : les plus récentes du dossier
  nombre:  integer                         // mode dossier, ≤ 30 (défaut 30)
}
```

- **Où** : déclaré sur tout terminal servi par un **poste** (hors terminal Teams), propriété stable ⇒ préfixe de
  cache intact (règle « préfixe stable = rien de volatil »). Terminal hébergé : erreur claire (« exige un poste »).
- **Lecture** : chaque chemin passe la **politique de lecture** comme un `read_file` (refus ⇒ image écartée avec
  sa raison ; invite ⇒ **une seule** demande d'accord pour toute la galerie, sauf « tout autoriser pour ce message ») ;
  chaque lecture est tracée au journal du poste (`read_file`). Porte du parcours guidé : une **lecture**.
- **Formats** : PNG, JPEG, GIF, BMP (décodeurs du JDK). WebP/HEIC/SVG : écartés avec la raison (« format non pris en
  charge »), jamais d'erreur de tout l'appel.
- **Aperçu borné** (pas d'originaux de 5 Mo × 110) : réduit à 1600 px au plus grand côté, JPEG (fond blanc sous la
  transparence), **≤ 400 Kio** (qualité puis taille dégradées jusqu'à tenir). Source ≤ 10 Mo (plafond du runner),
  transfert total ≤ 150 Mo par appel ; au-delà, ou si le temps du tour s'épuise, les images restantes sont dites
  « non chargées » dans le texte rendu au modèle.
- **Date** : celle que donne l'agent (`date`), sinon la date du **dépôt** connu (`atelier_deposited_files`, même
  chemin), sinon une date lisible dans le **nom** (`2026-11-27 093012`, `20261127_093012`…), sinon « date inconnue ».
- **Texte au modèle** : borné, **jamais d'image** (coût) — « Galerie « … » affichée dans le fil : 24 images (noms…).
  Écartées : … ». L'agent n'a pas à les décrire.
- **Stockage** : objets `galeries/{user}/{ws}/{id}.jpg` dans le `WorkspaceStorage` existant ; métadonnées (nom,
  chemin, date, légende, dimensions) **dans le bloc** de la transcription (`terminal_json`). **Aucune table** : la
  clé est **reconstruite** depuis l'utilisateur authentifié et le terminal relu par `requireOwned` — isolation
  `user_id` par construction, comme les preuves. Effacées avec le terminal (`WorkspaceService.delete`).
- **Route** : `GET /api/workspaces/{id}/gallery/{imageId}` → `image/jpeg`, 404 si terminal ou image étrangers
  (indiscernables), cache privé 1 h (une image ne change jamais).
- **Jarvis** : outil déclaré au registre des scènes (`S1`, comme les preuves, libellé « Galerie d'images »).

## 4. Décisions prises par défaut

| # | Décision | Pourquoi |
|---|---|---|
| D1 | Un outil **nouveau** (`galerie_images`) plutôt qu'un détournement de `capturer_preuves` ou `page_publish`. | Sémantique différente (montrer des pièces, pas prouver ; pas de page à composer). La définition reste courte et fixe. |
| D2 | **Aucune table**, métadonnées dans le bloc, objets dans le stockage existant. | Rien à indexer ni à requêter ; évite une migration (et le conflit de numéro avec F-279 en parallèle). |
| D3 | Images servies par une route **same-origin** de l'application (comme les preuves), **pas** en `data:`. | La contrainte CSP de SF-142-22 vaut pour les **pages** (origine opaque) ; le fil de l'application charge déjà les preuves par `/api/…` (`img-src 'self'`). Le `data:` gonflerait le relevé du tour de plusieurs Mo. |
| D4 | Aperçu JPEG ≤ 1600 px / ≤ 400 Kio ; une seule taille (la bande de vignettes réutilise l'aperçu, chargé paresseusement). | Captures d'écran lisibles en grand ; 30 × 400 Kio = 12 Mo au pire par galerie. Une 2e taille doublerait les objets pour un gain faible. |
| D5 | ≤ 30 images par appel ; mode `dossier` (glob, plus récentes d'abord) pour « montre-moi les dernières captures ». | 110 captures : l'agent sélectionne (par ex. celles des demandes en attente), ou enchaîne deux galeries. |
| D6 | Une seule invite d'accord pour toute la galerie si la politique demande l'accord. | 30 invites successives seraient inutilisables ; la galerie est une lecture. |
| D7 | Rien ne revient au modèle en image. | Coût (règle produit), et le besoin est de **montrer à l'utilisateur**. |
| D8 | Pas d'intégration à l'index des pièces Jarvis (F-237) dans cette feature. | Hors besoin ; les dépôts y sont déjà. Extension possible plus tard. |

## 5. Découpage

| SF | Contenu | Estimation |
|---|---|---|
| **SF-278-01** | Backend : outil `galerie_images` (catalogue, lecture par le runner existant, politique de lecture, aperçus bornés, stockage, texte au modèle), bloc `gallery` (transcription + événement SSE `gallery`), route d'image, effacement avec le terminal, porte du parcours (lecture), registre des scènes Jarvis. | 1,5 j |
| **SF-278-02** | Frontend : modèle `AtelierGalleryBlock`, événement SSE, composant `app-gallery-block` (carrousel, vignettes, clavier, agrandir, copier, télécharger), rendu dans le fil (terminal, volet, relecture). | 1 j |

## 6. Hors périmètre

- Les images d'un terminal **hébergé** (sans poste) ; le terminal Teams.
- La description automatique des images (aucune analyse d'image : l'agent pose la légende).
- WebP / HEIC / SVG (écartés avec leur raison).
- Galerie hors du fil (onglet de projet, partage par lien) ; intégration à la galerie Jarvis.
