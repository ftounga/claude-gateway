# Mini-spec — F-235 / SF-235-05 — L'écran Jarvis à deux cerveaux (D8, D11, D13, D17, D19)

## Identifiant
`F-235 / SF-235-05` · Feature parente `F-235` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-235-05-ecran-deux-cerveaux`

## Objectif
> L'écran `/jarvis` converse avec le cerveau rapide, dit ses phrases aussitôt, lance les délégations en tours Opus
> parallèles et annonce leurs fins dans l'ordre, à la pause — en retombant exactement sur le mode actuel si le
> cerveau rapide est éteint.

## Comportement attendu
### Cas nominal
- **Ouverture** : `POST /api/jarvis/converse/warm` (pré-chauffage) dit si le cerveau rapide est allumé (204) ou non
  (503 ⇒ mode actuel, rien ne change). Le mot d'éveil pré-chauffe aussi.
- **D19** : aucun sujet présélectionné (plus de « premier sujet ») ; le sélecteur indique le dernier sujet engagé
  (demandé par `?sujet=`, mémorisé, ouvert par une alerte, ou cible d'une délégation).
- **Une parole** (`handle`) : confirmation d'outil / « Vous confirmez ? » en attente ⇒ **directement** au tour Opus
  (D8) ; « redis-moi » ⇒ dernière annonce ; « pause », « c'est bon », « stop/annule », « passe sur X » inchangés ;
  sinon, cerveau rapide allumé ⇒ `converse` ; éteint ⇒ mode actuel (sans sujet : « Pour quel client ou quel sujet,
  Monsieur ? »).
- **`converse`** : chaque phrase `text` (déjà contrôlée par le serveur) est mise en file de voix aussitôt ; une
  `delegated` dit son annonce puis ouvre le **tour Opus** du fil cible (`streamChat`, `via=JARVIS`, chantier cible) ;
  `fallback` ou 503 ou échec ⇒ `send()` (mode actuel). Formule d'attente seulement si rien n'est parti à 1,2 s
  (clips variés au vouvoiement de SF-233-11).
- **Délégation** : tourne en parallèle ; visible sous l'orbe (« Recherches en cours ») ; ses lignes 🎙 intermédiaires
  sont **muettes** (seule la scène joue) ; sa fin rejoint la **file d'annonces**.
- **File d'annonces (D13)** : FIFO par heure de fin ; dite seulement quand le moment est libre (ni parole de Jarvis, ni
  réflexion, ni parole du PO en cours, ni confirmation en attente), sinon réessayée toutes les 0,8 s ; chaque annonce
  commence par son `label` (« Pour tickets bloqués d'Edenred : … ») ; une fin sur « Vous confirmez ? » arme la
  confirmation, dont la réponse ira directement à ce tour Opus.
- **« annule »** arrête la **dernière** délégation vivante (`interruptChat`) et le dit.
- **`heardBeforeCut`** transmis au cerveau rapide et au tour délégué.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Cerveau rapide éteint (503 à l'ouverture ou en route) | mode actuel, strictement |
| Repli serveur (plafond, quota) / flux en échec | la parole part au tour Opus (jamais muet) |
| Tour délégué en erreur ou coupé | annonce « La recherche n'a pas abouti ; le détail est au terminal. » |

## Critères d'acceptation
- [x] Réponse rapide dite phrase par phrase, aucun tour Opus.
- [x] Délégation : annonce dite, tour Opus dans le fil cible `via=JARVIS`, 🎙 intermédiaires muettes, fin annoncée avec
  son label.
- [x] Deux fins proches dites dans l'ordre de fin, jamais pendant une autre phrase.
- [x] 503 / repli ⇒ mode actuel.
- [x] Confirmation attendue ⇒ directement à Opus, sans cerveau rapide.
- [x] « annule » ⇒ dernière délégation interrompue et dite.
- [x] Aucun sujet présélectionné ; sans sujet, Jarvis demande lequel.

## Plan de test
- `jarvis.component.spec.ts` : 7 cas SF-235-05 + les 35 existants (mode actuel inchangé quand le cerveau rapide est
  éteint).
- `jarvis-fast-brain.spec.ts` : événements SSE, file FIFO, texte d'annonce, « redis-moi ».
- Suite front complète : 3 914 tests verts ; `ng build` OK.

## Tables / endpoints / composants
Aucune table. Consomme `POST /api/jarvis/converse` et `/converse/warm`. Front : `jarvis-fast-brain.ts` (nouveau),
`jarvis.component.{ts,html,scss}`.

## Préoccupations transversales
- **Navigation / routing** : aucune route ajoutée ; `/jarvis`, `/jarvis?sujet=`, `/jarvis?alerte=`,
  `/jarvis?source=compagnon` vérifiés (tests existants) ; seul le choix par défaut du sujet change (D19).

## Hors périmètre
Badge du fil côté terminal (SF-235-06) ; mesure `brain` dans les chronos (SF-235-07). « Montrer » reste porté par les
tours Opus (la scène lit leurs lignes `🖥 montrer:`), le cerveau rapide ne l'émet pas.

## Arbitrages (par défaut, réversibles)
- Jeu de clips : celui de SF-233-11 (≥ 6 formules d'attente vouvoyées, sans répétition, conditionnelles à 1,2 s) est
  réutilisé tel quel — aucun nouveau clip à générer.
- Le cerveau rapide n'émet pas « Montrer » (D7 le permettait) : il resterait sans contrôle d'ancrage du filtre ;
  Opus le fait déjà.
- Réessai de la file toutes les 0,8 s (pause minimale D13) plutôt qu'une détection fine de silence.
