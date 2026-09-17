# Mini-spec — [F-125 / SF-125-04] Alléger le crochet de fin de tour

## Identifiant

`F-125 / SF-125-04`

## Feature parente

`F-125` — La tenue de la carte du poste : silencieuse, robuste, jamais dans la réponse

## Statut

`ready`

## Date de création

2026-09-17

## Branche Git

`feat/SF-125-04-crochet-allege`

---

## Objectif

> En une phrase : la **dette de promotion** (cases `- [ ]` restées non cochées) ne **renvoie plus**
> l'agent au travail — elle devient **non bloquante et silencieuse** ; le crochet de fin de tour ne
> relance plus l'agent pour cette paperasse.

---

## Comportement attendu

### Cas nominal

1. **Décision par défaut** : dans `PromotionDetteBloquanteControl`, le refus déclenché par une
   **dette non nulle** (`marker.dette() > 0`) devient un **`proceed()` silencieux** — plus de blocage,
   plus de relance (le crochet `END_OF_TURN` peut relancer jusqu'à 3×, cf. audit F-119 : c'est ce
   qu'on supprime pour la dette). La dette reste **visible** ailleurs, sans relancer :
   `IntegritePosteControl` la signale toujours en **avertissement** (`DETTE_EN_COURS`, non bloquant).
2. **Ce qui reste** (rituel conservé, borné par F-50) :
   - Une promotion **explicitement déclarée** vers une destination **absente/étrangère**, **sans**
     écriture réelle dans la carte (SF-125-02), reçoit encore **un** rappel (« dis où ») — c'est une
     erreur de déclaration franche, pas de la paperasse de dette.
   - Le **filet sémantique** `JugeFinDeTourControl` (marqueur absent/illisible, ou durable déclaré non
     promu) est **hors périmètre** et inchangé.
   - `IntegritePosteControl` : `DETTE_A_LA_CLOTURE` (sujet déclaré **clos** avec des cases ouvertes)
     reste une **erreur** — c'est une perte de savoir au moment de clore, pas une dette de routine —
     et l'auto-déclaration reste hors périmètre (SF-125-03).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| Marqueur avec `dette=3`, rien d'autre à redire | **Ne bloque plus** (proceed silencieux) |
| Marqueur avec `dette=1` | **Ne bloque plus** |
| Promotion muette sans écriture réelle (destination non dite) | Rappel « dis où » **conservé** (SF-125-02) |
| Machine hors ligne avec dette reportée | Comportement F-93 inchangé (report/`deferred`) |
| `dette=0` | Passe (inchangé) |

---

## Critères d'acceptation

- [ ] Un tour dont le seul défaut est une dette de promotion (`dette>0`) **n'est pas relancé** : le
      contrôle rend `proceed()` (non bloquant), l'agent rend la main normalement.
- [ ] Une promotion déclarée sans destination et **sans** écriture réelle reçoit toujours un rappel
      (non-régression SF-125-02 : le refus « dis où » subsiste).
- [ ] Une promotion réellement écrite dans la carte passe (non-régression SF-125-02).
- [ ] `IntegritePosteControl` continue de signaler la dette en avertissement (non bloquant).
- [ ] La description du contrôle reflète le nouveau comportement (dette non bloquante).

## Plan de test minimal

- **Unitaires** :
  - `EndOfTurnControlsTest` : `dette>0` → **non bloqué** (remplace les anciens « une case bloque ») ;
    `dette=0` → passe (conservé) ; promotion muette sans écriture → toujours bloquée (conservé) ;
    promotion muette + écriture réelle → passe (conservé, SF-125-02).
- **Isolation utilisateur** : inchangée — le contrôle lit la carte du seul couple
  `(userId, workspaceId)` du tour (test `theMapIsReadForTheCurrentProjectOnly` conservé).

## Tables / endpoints / composants impactés

- **Backend** : `PromotionDetteBloquanteControl` (la branche `dette>0` rend `proceed()` ; javadoc +
  `description()` mis à jour). Aucune table, aucune migration, aucun endpoint, aucun frontend.
- `GovernanceEndOfTurnCheckpoint` : **inchangé** (le desserrage vit dans le contrôle, pas dans le
  crochet — le crochet ne fait que déléguer).

## Analyse transversale (préoccupations)

- **Auth / Principal** : non concernée.
- **Contexte tenant** : inchangé (carte lue sur `(userId, workspaceId)`).
- **Plans / limites** : non concernée.
- **Navigation / routing** : non concernée.

## Hors périmètre (explicite)

- `JugeFinDeTourControl` (marqueur absent, durable non promu) — non touché.
- `IntegritePosteControl` `DETTE_A_LA_CLOTURE` (clôture avec cases ouvertes) — reste une erreur
  (perte de savoir à la clôture, pas de la paperasse de routine). Décision tracée dans la PR.
- L'auto-déclaration active d'un fichier de carte (SF-125-03).
