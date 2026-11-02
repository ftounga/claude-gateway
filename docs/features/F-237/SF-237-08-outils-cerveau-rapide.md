# Mini-spec — F-237 / SF-237-08 — Les outils `pieces` et `afficher` du cerveau rapide

## Identifiant
`F-237 / SF-237-08` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-08-outils-cerveau-rapide`
Cadrage : §5.2 (`## Écran`), §6.1-6.2 (outils), §7 (garde-fous), §17 D3/D8, amendement F-235 D8 (§1.2).
Contrats importés : SF-237-01 (`JarvisPieceService.list`, `Piece`), SF-237-04 (`screenBlock`, réducteur `PieceNav`),
SF-237-05 (`JarvisPieceStore`).

## Objectif
> Le cerveau rapide **montre et navigue seul** : il lit l'index des pièces (`pieces`) et pilote l'écran (`afficher`),
> sans coût Opus et sans aucun outil d'action, en sachant ce que l'écran montre (`## Écran`) et ce qui existe
> (`## Pièces`).

## Comportement attendu

### Passerelle
- `JarvisFastBrainPrompt` **version 7** (une seule réécriture du cache) : la consigne gagne une section fixe
  « # Montrer les pièces » (les 7 situations §6.2, ≤ 3 pièces d'initiative, jamais en présentation, dire la pièce
  montrée avec sa description, n'inventer jamais son contenu, « la deuxième » = index du bloc `## Écran`, sur un
  « oui » à une proposition de preuve ⇒ `deleguer`). `tools()` = **[`deleguer`, `pieces`, `afficher`]**, liste fixe,
  identique octet pour octet d'un appel à l'autre (pré-chauffage compris).
- **`pieces`** (lecture, aucun effet) — `{ sujet, chantier, categorie (entrees|preuves|productions|""), type
  (image|video|audio|pdf|page|architecture|schema|diaporama|texte|fichier|""), recherche }` : exécuté **par la
  passerelle** (`JarvisFastPieces`) sur `JarvisPieceService.list` (filtre `user_id`, `requireOwned` du sujet ⇒ un
  sujet d'un autre compte rend « sujet inconnu ») ; résultat texte ≤ 12 lignes `id | type | « titre » | description ≤
  200 | date | chantier N (lien) | disponibilité`. **Une seule lecture par réponse** : une 2ᵉ demande reçoit « lecture
  déjà faite ».
- **`afficher`** (écran, aucun effet en base) — `{ action, pieces[], portee {sujet, chantier, categorie, type, serie,
  depuis}, index, temps, chapitre, page, zoom, cible, raison (demande|appui) }`, `action` ∈ `galerie, piece,
  suivante, precedente, selection, retour, remonter, fermer, lecture, pause, aller, page, zoom, comparer, raconter,
  planche` (les trois dernières sont servies par SF-237-10/11 ; l'écran les accepte dès maintenant en repli
  « galerie »). Validé par la passerelle (`JarvisShowCommand`) puis relayé au navigateur par l'événement SSE **`show`**.
  Le résultat d'outil rendu au modèle est `ok` (ou la raison du refus passerelle).
- **Boucle d'outil** (≤ 3 appels modèle par parole) : `pieces` ⇒ lecture puis 2ᵉ appel avec le résultat ;
  `afficher` sans phrase ⇒ 2ᵉ appel avec `ok` pour obtenir la phrase ; `deleguer` ⇒ chemin existant (les `afficher`
  du même tour partent quand même). Chaque appel est compté (journal annexe + journée Jarvis). Le résultat de
  `pieces` **ancre** les phrases (garde D9a) : un titre ou une description lue peut être dit.
- **Garde-fous passerelle** (§7) : ≤ 8 identifiants par `afficher` ; ≤ 3 pièces `raison=appui` **par réponse**
  (au-delà tronqué, compté `showTruncated`) ; identifiants de forme inconnue retirés ; action inconnue ⇒ refus
  `UNKNOWN_ACTION` rendu au modèle, rien n'est relayé.
- **Contexte joint au dernier message** (jamais à la consigne) : le bloc `## Écran` envoyé par l'écran (≤ 1 500 car.,
  coupé au-delà) et le bloc **`## Pièces`** calculé par la passerelle pour le sujet courant (« chantier 3 : besoin
  initial, 4 entrées, 0 preuve, 1 page, 2 vidéos », ≤ 600 car.).
- `POST /api/jarvis/converse` : champ facultatif `screen` (texte). Événement SSE `show` (`ShowEvent`). `DoneEvent`
  inchangé.

### Écran
- `ConverseBody.screen` = `screenBlock(nav, presentation)` à chaque parole.
- `dispatch` lit `show` ⇒ `handlers.onShow(cmd)`.
- `jarvis-piece-show.ts` (pur) : `showPlan(cmd, ctx)` ⇒ ce qu'il faut faire (ouvrir une galerie avec une requête
  d'index, ou une action du réducteur), avec les garde-fous écran : présentation ⇒ `PRESENTATION` ; pièce d'initiative
  hors du sujet courant ⇒ `NOT_IN_SCOPE` ; même pièce d'initiative montrée < 10 min ⇒ ignorée (`RECENT`) ; mode sobre ⇒
  rien de plus que la galerie (aucun zoom d'initiative).
- `JarvisPieceStore.applyShow(cmd, ctx)` applique le plan (index lu par `JarvisPieceClient`, filtré par ids).

### Cas d'erreur
- Lecture d'index en panne ⇒ résultat d'outil « lecture impossible », la réponse continue (jamais muet).
- Délai / refus / erreur du 2ᵉ appel ⇒ délégation d'office, comme le 1ᵉʳ (D9b) ; les `show` déjà partis restent.
- `afficher` avec une action inconnue ou sans rien à montrer ⇒ refus rendu au modèle, aucun `show`.
- Écran : index injoignable ⇒ `refusalPhrase('UNAVAILABLE')` dite.

## Contraintes de validation
| Champ | Règle |
|---|---|
| `screen` | ≤ 1 500 caractères gardés (coupé au-delà, jamais 400) |
| `afficher.pieces` | ≤ 8 ids, forme `^(dep|proof|page|video|img|slides|send|mail|moment|need|diag):[A-Za-z0-9@/_.-]{1,120}$` |
| appui | ≤ 3 pièces par réponse |
| `pieces` | 1 lecture par réponse, ≤ 12 lignes, description ≤ 200 |
| appels modèle | ≤ 3 par parole |

## Critères d'acceptation
- [ ] Consigne et définitions d'outils **identiques octet pour octet** d'un appel à l'autre (et au pré-chauffage).
- [ ] `afficher` ⇒ un événement `show` relayé ; `pieces` ⇒ un 2ᵉ appel portant le résultat ; jamais plus de 3 appels.
- [ ] ≤ 3 pièces d'initiative relayées par réponse ; ids invalides retirés.
- [ ] `pieces` sur le sujet d'un autre compte ⇒ « sujet inconnu », aucune pièce lue (isolation).
- [ ] Le bloc `## Pièces` compte par chantier ; `## Écran` est joint au dernier message, jamais à la consigne.
- [ ] Écran : `show` ouvre la galerie demandée ; refus présentation / hors sujet / récent appliqués (tests purs).

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Contexte tenant | **Oui** | `JarvisFastPieces` → `JarvisPieceService.list(userId, …)` (`requireOwned`) ; test A/B |
| Plans / limites | **Oui** | `JarvisFastBrainService` : ≤ 3 appels comptés chacun (`account` → `recordAnnex`, `addFastBrain`), budget 50 €/mois et plafond dur inchangés |
| Préfixe de cache | **Oui** | `JarvisFastBrainPrompt` v7 (consigne + 3 outils fixes), `warm` ; test octet pour octet |
| Auth / Navigation | Non | — |

## Plan de test
- **Unitaires back** : `JarvisFastBrainServiceTest` (outils fixes ×2 appels, `show` relayé, boucle `pieces`,
  1 lecture max, ≤ 3 appels, phrase après `afficher` seul) ; `JarvisShowCommandTest` (validation, ≤ 8, ≤ 3 appui,
  action inconnue) ; `JarvisFastPiecesTest` (format, recherche, sujet d'un autre compte ⇒ inconnu, bloc `## Pièces`).
- **Front pur** : `jarvis-piece-show.spec.ts` (plan par action, présentation, hors sujet, 10 min) ;
  `jarvis-fast-brain.spec.ts` (`show` relayé, `screen` envoyé).
- **Non-régression** : suite backend complète, suites jarvis.

## Hors périmètre
Ligne `🖥 montrer:` côté Opus, preuves au fil de l'eau (SF-237-09) ; diaporama (10) ; avant/après et planche (11) ;
bancs SHOW/NAVIGATE/PROOF (13).
