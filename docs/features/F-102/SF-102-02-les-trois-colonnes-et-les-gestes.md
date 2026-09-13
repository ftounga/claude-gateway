# Mini-spec — F-102 / SF-102-02 — Les trois colonnes et les gestes

## Identifiant

`F-102 / SF-102-02`

## Feature parente

`F-102` — Le Radar et le résumé du matin, **dans la Vigie** (cadrage commun :
`docs/features/F-99/CADRAGE-le-radar.md` §2, §4, §6, §8 ; maquette `docs/features/F-99/maquette-radar.html`)

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-102-02-trois-colonnes`

---

## Objectif

Sous le résumé du matin, montrer les **trois colonnes** *À faire par moi* · *Sujets en cours* · *J'attends des
autres*, avec leurs seuls boutons — *fait*, *pas moi*, *reporter*, *clore* — et les `probable` posés comme
des questions (*C'est moi* / *Pas moi*), chaque geste étant **annulable**.

---

## Comportement attendu

### Cas nominal

**Gateway — `GET /api/radar/hosts/{hostId}/board`** (nouveau `RadarBoardService`, lecture seule) :

1. **`toDo`** — engagements *moi → autre* et *mises en relation*, ouverts ou reportés, non désavoués ;
   rangés : dus ou en retard (échéance ≤ aujourd'hui) d'abord, puis les questions, puis par échéance
   (sans échéance à la fin).
2. **`waiting`** — engagements *autre → moi*, ouverts ou reportés, non désavoués ; rangés : relance due
   ou échéance passée d'abord, puis questions, puis par échéance.
3. Chaque engagement porte, en plus de sa vue (`CommitmentView` de F-99) : la **source de sa preuve la
   plus récente** (`source`, `sourceAt`, `deepLink`), `question` (`probable` non tranché), `due`
   (à ma charge : échéance ≤ aujourd'hui ; attendu : relance due ou échéance ≤ aujourd'hui) et
   `overdueDays` (jours de retard, 0 sinon).
4. **`subjects`** — les sujets de la liste par défaut (sujets clos exclus, **sauf réveillés**), chacun
   avec : sa ligne (**la première phrase de son résumé**, sinon sa prochaine étape), le **nombre de
   preuves** de sa chronologie, les **noms des personnes** qui y ont un rôle (trois au plus, par ordre
   alphabétique) et le nombre d'engagements ouverts.

**Écran — `RadarColumnsComponent`** sous le résumé, dans `RadarBoardComponent` :

5. **Trois panneaux** (surface blanche, filet `--cg-divider`, rayon 8 px), en-tête « À faire par moi · 3 »,
   « Sujets en cours · 12 », « J'attends des autres · 4 ». Largeur : 1 · 1,35 · 1 ; sous 1020 px, deux
   colonnes avec *Sujets en cours* en premier sur toute la largeur ; sous 640 px, une seule colonne, *À faire
   par moi* d'abord.
6. **Un engagement** : sa description (question : « Rédiger la note DSI ? »), puis une ligne de méta —
   icône de la source (Teams `forum`, réunion `videocam`, enregistrement `mic`, note `edit_note`,
   courriel collé `mail`), « dans « *sujet* » » (lien vers le sujet), le moment de la preuve (« hier
   14:32 », « 12 sept. ») —, et une pastille §5 : « en retard de 3 j » (`badge--error`), « relance due »
   (`badge--error`), « pour aujourd'hui » / « pour vendredi » dans les 6 jours (`badge--warning`),
   « pour le 3 oct. » (`badge--neutral`), « probable » (`badge--neutral`). La personne : « Julie Robert —
   *description* » pour un attendu, « avec Sophie et Karim » pour une mise en relation.
   **Filet gauche orange de 4 px** sur un élément `due`.
7. **Gestes d'un engagement** (`POST /commitments/{id}/corrections`) :
   - à ma charge, certain : **Fait** (`DONE`), **Reporter** (menu : *Demain*, *Lundi prochain*, *Dans une
     semaine*, *Dans deux semaines* → `POSTPONE` + `dueDate`), **Pas moi** (`NOT_MINE`) ;
   - attendu, certain : **Reçu** (`DONE`), **Reporter** (même menu) ;
   - question (`probable`) : **C'est moi** / **C'est bien attendu** (`CONFIRM`) et **Pas moi** (`NOT_MINE`) ;
   - *Ouvrir la source* (lien profond, nouvel onglet, `rel="noopener noreferrer"`) quand la preuve en a un.
8. **Un sujet** : nom (lien vers `/vigie/<client>/sujets/<id>`), pastille d'état (§17), sa ligne, puis
   « *n* preuves · il y a 18 h · Paul Martin, Sophie Laurent » ; « silencieux 9 j » (`badge--warning`) à
   la place de l'état pour un sujet ouvert sans activité depuis 7 jours ou plus.
9. **Gestes d'un sujet** :
   - ouvert : **Clore** (`POST /subjects/{id}/close`) ; s'il reste des engagements ouverts, un dialogue
     le dit (« 1 engagement encore ouvert : le fermer aussi ? ») avec **Les marquer faits** (`DONE`
     chacun), **Les abandonner** (`ABANDON` chacun) ou **Les laisser ouverts** ;
   - « clos ? » : sa ligne dit « Clôture proposée » et **Clore** (`close-proposal/confirm`) / **Garder
     ouvert** (`close-proposal/reject`) ;
   - réveillé : « Se réveille » et **Rouvrir** (`SET_STATE` `ADVANCING`) / **Laisser clos** (`wake/dismiss`).
10. **Tri des sujets** : bouton segmenté *Plus récents* (défaut, ordre de la gateway) · *À traiter d'abord*
    (réveillés, « clos ? », bloqués, en sommeil, en attente, nouveaux, qui avancent).
11. **Tout est annulable** : chaque geste réussi ouvre une snackbar « Marqué fait. » avec **Annuler**
    (`POST /corrections/{id}/undo`) ; puis colonnes **et résumé** sont relus (les compteurs changent).
12. Vide : « Rien à faire pour vous. », « Aucun sujet suivi : ils apparaissent à la première synchro. »,
    « Rien n'est attendu des autres. ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Poste d'un autre compte ou inconnu | introuvable | 404 |
| Client non activé dans la Vigie | `host_not_in_space` | 409 |
| Sans droit Teams | refus | 403 |
| Geste sur un engagement ou sujet d'un autre poste | introuvable | 404 |
| Sujet fusionné / état incompatible (déjà clos, pas de proposition) | snackbar avec le message de la gateway ; colonnes relues | 409 |
| Annuler une correction recouverte par une plus récente | snackbar « Une correction plus récente… » | 409 |
| Colonnes illisibles | encart « Les colonnes n'ont pas pu être lues. » + *Réessayer* | — |

---

## Critères d'acceptation

- [ ] CA1 — `GET /board` range les engagements à ma charge et attendus comme décrit, sans désavoués ni tenus.
- [ ] CA2 — Chaque engagement porte la source, l'instant et le lien de sa preuve la plus récente, `question`, `due`, `overdueDays`.
- [ ] CA3 — Chaque sujet porte sa première phrase de résumé (ou sa prochaine étape), son nombre de preuves, ses personnes (3 au plus).
- [ ] CA4 — Isolation : rien d'un autre poste ni d'un autre utilisateur ; 404 pour autrui.
- [ ] CA5 — Les trois colonnes s'affichent avec compte, pastilles et filet orange sur ce qui est dû.
- [ ] CA6 — Un `probable` est écrit comme une question et n'offre que *C'est moi* / *Pas moi*.
- [ ] CA7 — *Fait*, *Pas moi*, *Reporter* (date calculée), *Clore*, *Clore ?* confirmer/refuser, *Rouvrir* / *Laisser clos* appellent la bonne route ; chaque geste propose *Annuler*.
- [ ] CA8 — Clore un sujet avec des engagements ouverts propose de les fermer aussi.
- [ ] CA9 — Après un geste, colonnes et résumé sont relus.
- [ ] CA10 — Aucune couleur hors charte ; téléphone : une colonne.

---

## Périmètre

### Hors scope (explicite)

- *Préparer le message / la relance* (F-104 / SF-104-05) et *Donner la nouvelle* (F-104).
- La page sujet et sa chronologie (F-103) : le lien y mène.
- Fusionner / séparer / renommer un sujet depuis la colonne (API F-99 existante, écrans F-103).
- Un sélecteur de date libre pour *Reporter* (quatre choix proposés, réversible).
- Compteur « à traiter » de l'onglet et §17 (SF-102-03).

---

## Valeurs initiales

Non applicable — aucune entité créée ; les gestes passent par les corrections souveraines de F-99.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `action` (geste) | Oui | — | `DONE`, `NOT_MINE`, `POSTPONE`, `CONFIRM`, `ABANDON` (existants) | — | — |
| `dueDate` (reporter) | Oui pour `POSTPONE` | — | date ISO calculée par l'écran, > aujourd'hui | — | — |

Notes : « silencieux » à partir de 7 jours sans activité ; « pour vendredi » dans les 6 jours ; personnes : 3 au plus.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/board` | JWT | droit Teams + client dans la Vigie |
| POST | `/api/radar/hosts/{hostId}/commitments/{id}/corrections` | existant (F-99) | idem |
| POST | `/api/radar/hosts/{hostId}/subjects/{id}/close` · `close-proposal/confirm` · `close-proposal/reject` · `wake/dismiss` · `corrections` | existants (F-99) | idem |
| POST | `/api/radar/hosts/{hostId}/corrections/{id}/undo` | existant (F-99) | idem |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_subjects`, `radar_subject_facts`, `radar_subject_roles`, `radar_people`, `radar_commitments`, `radar_evidence`, `radar_evidence_links` | SELECT | filtrées `user_id` + `host_id` |
| `radar_commitments`, `radar_subjects`, `radar_corrections` | UPDATE / INSERT | par les services existants de F-99 |

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

- `RadarColumnsComponent` (`vigie/radar/`) — les trois colonnes, leurs gestes et l'annulation.
- `CloseSubjectDialogComponent` (`vigie/radar/`) — « N engagements encore ouverts : les fermer aussi ? ».
- `radar-columns.ts` — fonctions pures (pastille d'échéance, date de report, moment de la preuve, tri « à traiter d'abord », silence).
- `RadarBoardComponent` — porte résumé et colonnes ; un geste relit les deux.
- `RadarService` — `board`, `correctCommitment`, `closeSubject`, `confirmClosure`, `rejectClosure`, `dismissWake`, `setSubjectState`, `undo`.

### Préoccupations transversales

- **Navigation : non** — le lien de sujet vise `/vigie/:hostRef/sujets/:id`, route existante (F-106 / SF-106-04), rendue par F-103.
- **Contexte tenant : non** — même résolution que `RadarController`.
- **Plans / limites : non** — même garde ; aucun appel au modèle.
- **Auth / Principal : non.**

---

## Plan de test

### Tests unitaires

- [ ] `RadarBoardServiceTest` — tri des deux colonnes d'engagements ; `due`, `overdueDays`, `question` ; preuve la plus récente ; sujets : ligne, preuves, personnes (3 au plus).
- [ ] `radar-columns.spec.ts` — pastille d'échéance, date de report (demain, lundi prochain…), moment de la preuve, tri « à traiter d'abord », silence.
- [ ] `radar-columns.component.spec.ts` — rendu des colonnes et pastilles ; question ; chaque geste appelle sa route et propose *Annuler* ; clôture avec engagements ouverts ; erreur 409 ; relecture après geste.

### Tests d'intégration

- [ ] `GET /board` → 200 avec colonnes rangées et enrichies.
- [ ] `GET /board` → 404 pour autrui.

### Isolation utilisateur

- [x] Applicable — deux postes d'Alice et le poste de Bob : la colonne du poste A ne contient rien de B ni de Bob.

---

## Dépendances

### Subfeatures bloquantes

- SF-102-01 — `done` (résumé, `RadarBoardComponent`, `RadarService`).
- F-99 (corrections, clôture) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **« Reporter » un attendu** passe l'engagement `POSTPONED` avec une nouvelle échéance : la relance due
  s'éteint (règle de F-101 : seule un engagement ouvert appelle une relance) et l'élément redevient dû
  quand la nouvelle échéance est atteinte. Réversible.
- **Fermer les engagements d'un sujet clos** : le cadrage dit « le fermer aussi ? » sans dire comment ;
  l'écran propose *faits* ou *abandonnés*, au choix de l'utilisateur. Réversible (annulables un à un).
- Les personnes d'un sujet sont écrites en toutes lettres, **sans pastille de couleur** : les tons du §9
  restent réservés aux machines (§16, ajout F-106) — écart assumé avec la maquette.
