# Mini-spec — F-175 / SF-175-06 — Relancer et partout

## Identifiant
`F-175 / SF-175-06` — feature parente `F-175` *Le fil des attentes* — dépend de SF-175-01→05 (mergées).
Branche : `feat/SF-175-06-relancer-et-partout`. Statut : `in-progress`. Date : 2026-10-05.

## Objectif
Qu'une demande qui s'enlise appelle une **relance** d'un geste, et que les attentes se voient
**partout** : rail de la Forge, mosaïque.

## Comportement attendu

### Cas nominal (D6, D8)
1. **Relance due** (`TerminalActionFollowUp`) : une attente `DEMANDE` depuis ≥
   `APP_ATELIER_FOLLOWUP_DAYS` jours **ouvrés** (défaut 3, lundi–vendredi) — à partir de
   `requested_at` (à défaut `created_at`). Exposé :
   - `followUpDue` sur chaque attente du tableau, et `aRelancer` au tableau ;
   - « · RELANCE DUE » dans la ligne jointe au tour (l'agent peut le proposer) ;
   - « · N à relancer » dans la bande.
2. **Relancer** (panneau, sur une attente « à relancer ») : dépose dans la zone de saisie « Relance
   Zahi au sujet de « … » (demandé le 30/09 par Teams, toujours sans réponse). Rédige-moi le message
   de relance. » et ferme le panneau — **rien n'est envoyé** (mécanisme `draftChange`, équivalent
   in-terminal de `radarDraft`).
3. **Compteurs partout** : `GET /api/terminal-actions/summary` →
   `{ hosts: [{id, aFaire, demande, aRelancer, oldestOpenAt}], terminals: [{id, aFaire, demande, aRelancer}] }`
   (attentes ouvertes du compte seulement). Rail de la Forge : « 3 attentes · 1 à relancer » sous le
   poste ; mosaïque : « 2 attentes » dans l'en-tête de tuile. Lus à l'ouverture et au geste
   « Rafraîchir / Réessayer » (pas au battement de 5 s).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Compteurs illisibles | rail / mosaïque inchangés (enrichissement) |
| Pas de jeton | 401 |
| Seuil ≤ 0 | ramené à 1 |

## Critères d'acceptation
- [ ] 3 jours ouvrés, week-end exclu ; jamais pour « À faire ».
- [ ] `followUpDue` / `aRelancer` au tableau, « RELANCE DUE » au tour, « à relancer » dans la bande.
- [ ] « Relancer » pré-remplit sans envoyer, et ne change pas l'état.
- [ ] `summary` ne compte que les attentes du compte (isolation testée), par poste et par terminal.
- [ ] Rail et mosaïque affichent leurs compteurs, rien sans attente.

## Plan de test
- **Backend** : `TerminalActionFollowUpTest` (jours ouvrés, seuil, tableau + compteurs, ligne du
  tour) ; intégration `summaryCountsOnlyMine` (Bob sur le même poste forgé n'est pas compté).
- **Front** : bande « à relancer », bouton Relancer + brouillon, terminal `onFollowUp` ; rail
  (compteur / rien) ; mosaïque (compteur sur la seule tuile concernée).

## Impacts
Backend : `TerminalActionFollowUp`, `TerminalActionSummaryResponse` (nouveaux), réponse
(`followUpDue`), tableau (`aRelancer`), `TerminalActionQueryService.summary`, route `/summary`,
liste du tour ; config `app.atelier.followup-days` (`APP_ATELIER_FOLLOWUP_DAYS`) et
`app.atelier.actions.dedup-max-distance` (`APP_ATELIER_ACTIONS_DEDUP_MAX_DISTANCE`, de SF-175-03).
Front : modèles, service, bande, panneau, terminal, rail, Forge (postes), mosaïque. Aucune table.

### Préoccupations transversales
Contexte tenant : nouvelle lecture transverse (`summary`) — composant : `TerminalActionQueryService.summary`
(`user_id` seul, identité du JWT). Navigation : non.

## Hors périmètre
Envoi de la relance depuis la liste (le message part toujours de l'agent, à la demande) ; fériés.

## Arbitrages (réversibles)
- Jours ouvrés sans calendrier de fériés.
- La relance se pré-remplit dans le terminal **courant** (la liste jointe au tour couvre tout le poste).
- Compteurs du rail/mosaïque non rafraîchis au battement (une attente ne bouge pas en 5 s).
