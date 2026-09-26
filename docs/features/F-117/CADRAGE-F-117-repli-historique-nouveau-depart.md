# Cadrage — F-117 (extension) : replier l'historique après un nouveau départ

> Extension de **F-117 « Le contexte d'un terminal ne déborde jamais »**. Décision produit
> **déjà prise par le PO** — ce cadrage ne la repose pas, il l'outille.

## 1. La décision produit (PO)

Aujourd'hui, un « **Nouveau départ** » (F-39 / SF-39-04, rendu visible par SF-117-03) pose la
frontière de rejeu **sans rien masquer** : l'ancien fil reste affiché (« la conversation reste
affichée »). Le PO veut désormais : après un Nouveau départ **manuel**, **replier** les tours
d'AVANT la frontière derrière un affordance compact « **Voir l'historique** », **masqué par
défaut**, ré-affichable en un tap et repliable à nouveau. **Non destructif** : rien n'est supprimé
côté serveur — pur changement d'**affichage**.

## 2. Feature d'accueil retenue

**F-117** (et non une nouvelle feature). Justification :

- F-117 possède déjà les deux mécanismes en jeu : la **compaction automatique** (SF-117-01) et le
  **nouveau départ visible** (SF-117-03). Le repli est exactement la conséquence d'affichage du
  geste rendu visible par SF-117-03, et la **nuance** à traiter (ne pas replier sur la compaction
  auto) est interne à F-117.
- `PRODUCT_SPEC.md` porte F-117 « Terminée » ; on la **rouvre** avec deux subfeatures (motif :
  évolution d'affichage du même sujet), on ne crée pas de feature parallèle qui dupliquerait le
  périmètre. F-117 repassera « Terminée » à l'issue.

## 3. La nuance centrale : restart manuel ≠ compaction automatique

Les deux posent la **même** frontière `workspaces.chat_thread_started_at` :

| Geste | `chat_thread_started_at` | `chat_thread_summary` | Attente d'affichage |
|-------|--------------------------|-----------------------|---------------------|
| **Nouveau départ manuel** (`POST .../chat/restart`) | posée = `now()` | **effacé** (`null`) | **replier** l'avant-frontière |
| **Compaction automatique** (SF-117-01, silencieuse) | posée = nouvelle frontière | **renseigné** (le résumé) | **tout garder affiché** (inchangé) |

Le repli **ne peut donc pas** se déclencher sur la seule présence de `chat_thread_started_at` :
il faut un marqueur qui distingue « l'utilisateur a demandé un nouveau départ » de « compaction
auto », **persistant** (tient au rechargement) et **stable dans le temps** (la compaction qui
survient *après* un nouveau départ déplace `chat_thread_started_at` mais **ne doit pas** replier
davantage).

### Mécanisme choisi — colonne dédiée `chat_history_folded_at`

Nouveau champ persistant `workspaces.chat_history_folded_at` (`timestamptz`, nullable) :

- **Posé uniquement** par le restart **manuel** (`AtelierThreadService.restart`), à `now()`.
- **Jamais touché** par la compaction (`AtelierCompactionService` n'écrit que
  `chat_thread_started_at` + `chat_thread_summary`).
- Le repli d'affichage porte sur les messages **créés avant** `chat_history_folded_at` — un point
  **fixe**, indépendant de la frontière de compaction qui, elle, peut ensuite avancer.

Pourquoi une colonne dédiée plutôt que réutiliser `summary == null` : après un nouveau départ
(summary effacé), une compaction ultérieure **repose** un summary et **déplace** la frontière → le
signal « c'était un nouveau départ » et la position d'origine seraient perdus. Une colonne dédiée
est le marqueur non ambigu, durable et le **moins surprenant** demandé par le PO.

Le frontend n'a pas besoin de comparer des timestamps : le backend expose dans l'état de reprise
(`GET .../chat/resume` et la réponse de `POST .../chat/restart`) un entier **`foldedTurns`** = le
nombre de messages créés avant `chat_history_folded_at`. Le fil étant chargé en ordre chronologique
croissant (`GET .../chat`), replier = masquer les **N premiers** messages. Robuste au rechargement
(donnée serveur), robuste aux nouveaux messages (ils s'ajoutent en fin, jamais dans les N premiers).

## 4. Découpage (2 subfeatures, backend + frontend planifiés ensemble)

Le repli exige une **donnée serveur nouvelle** (marqueur + `foldedTurns`) : ce n'est donc pas
frontend-seul. Conformément à CLAUDE.md (pas de backend UI-impactant sans frontend planifié, et
inversement), les deux subfeatures sont **planifiées ensemble ici** :

- **SF-117-05 — le nouveau départ manuel pose un marqueur de repli distinct de la compaction**
  (backend). Colonne `chat_history_folded_at` (migration **122**), posée par `restart` seulement,
  jamais par la compaction ; `foldedTurns` exposé dans `AtelierResumeResponse`. Isolation `user_id`.

- **SF-117-06 — l'historique d'avant un nouveau départ se replie derrière « Voir l'historique »**
  (frontend). Le terminal masque les `foldedTurns` premiers messages, affiche un affordance
  « Voir l'historique (N messages) » repliable/dépliable, desktop + mobile ; corrige la copie qui
  ment (« la conversation reste affichée »). Non-régression rail + auto-scroll.

Numéros SF **libres** vérifiés (existants : SF-117-01→04 ; SF-117-05/06 absents de `docs/`).

## 5. Contraintes portées (rappel)

- **V1 gateway pure**, Provider-First / Gateway-First : aucune capacité IA, pur affichage + un
  marqueur d'état. Isolation `user_id` sur tout accès (via `requireOwned`), migration numérotée à
  la suite (122), tests d'isolation.
- **DESIGN_SYSTEM** : jetons `--cg-*` uniquement, aucune couleur/police nouvelle, cible tactile
  ≥ 44 px, breakpoint 819 px, budget de build 12 ko/feuille (feuille dédiée si besoin).
- **Non-régression terminal** : SF-117-01/02/03, F-39/SF-39-04 (restart/resume), SF-158-16→23
  (mobile + auto-scroll), rail « Vos questions » (SF-158-22). Le repli ne casse ni l'auto-scroll
  au fond (SF-158-23) ni le rail.
