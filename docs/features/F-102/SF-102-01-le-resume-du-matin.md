# Mini-spec — F-102 / SF-102-01 — Le résumé du matin

## Identifiant

`F-102 / SF-102-01`

## Feature parente

`F-102` — Le Radar et le résumé du matin, **dans la Vigie** (cadrage commun :
`docs/features/F-99/CADRAGE-le-radar.md` §4, §5, §8, §12 bis ; maquette `docs/features/F-99/maquette-radar.html`)

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-102-01-resume-du-matin`

---

## Objectif

Remplacer l'état vide de l'onglet **Radar** d'un client de la Vigie par **le résumé du matin** : ce qui a
bougé depuis hier en trois phrases au plus, les compteurs, **la couverture** de la dernière synchro (avec
ses manques et leurs gestes) et *Synchroniser maintenant* — une synchro partielle le disant **en tête**.

---

## Comportement attendu

### Cas nominal

**Gateway — `GET /api/radar/hosts/{hostId}/brief`** (nouveau `RadarBriefService`, lecture seule, aucun
appel au modèle) :

1. **Ce qui a bougé depuis hier** (`since = maintenant − 24 h`, horloge injectée), **3 phrases au plus**,
   composées depuis le registre — jamais inventées : chaque phrase désigne l'objet qui la fonde
   (`subjectId` ou `commitmentId`), lui-même prouvé (règle §4.1). Ordre de priorité, les suivantes étant
   écartées au-delà de trois :
   1. un **sujet clos qui se réveille** : « Le sujet « LDAP » se réveille : rouvrir ou laisser clos ? » ;
   2. un **engagement à ma charge en retard** (moi → autre, mise en relation ; ouvert ou reporté ; non
      désavoué ; échéance passée) : « Vous deviez « X » pour le 12 septembre : en retard de 3 jours. » ;
   3. les **relances dues** : « Relance due : Julie Robert — « retour de l'éditeur SSO ». » ou, à
      plusieurs, « 2 relances dues : Julie Robert, Thomas Dubois. » ;
   4. une **clôture proposée** depuis hier : « « Migration LDAP » peut être clos : à confirmer. » ;
   5. les **nouveaux sujets** depuis hier : « Nouveau sujet : « Accès réseau ». » / « 2 nouveaux sujets :
      A, B. » ;
   6. un **engagement probable** apparu depuis hier : « À confirmer : « Rédiger la note DSI » vous
      revient-il ? » ;
   7. un **sujet qui a bougé** depuis hier (hors nouveaux) : « « MFA prestataires » avance ; prochaine
      étape : note DSI. » (état en mots : avance, est en attente, est bloqué) ;
   8. un **sujet passé en sommeil** depuis hier : « « Bascule SSO » n'a plus bougé depuis 21 jours : qu'en
      est-il ? ».
   Rien de tout cela, avec une synchro déjà faite : « Rien n'a bougé depuis hier. » ; aucune synchro
   encore : aucune phrase.
2. **Compteurs** : `toDoByMe` (moi → autre en cours), `followUpsDue` (relances échues, toutes directions),
   `introductions` (mises en relation en cours), `subjectsFollowed` (sujets vivants non clos),
   `blockedSubjects`, et **`toHandle`** — ce qui réclame un geste : engagements à ma charge dus ou en
   retard, relances dues, engagements `probable` non tranchés (comptés une fois chacun), sujets « clos ? »
   et sujets réveillés. Tous les compteurs excluent les engagements désavoués et les sujets fusionnés.
3. **Synchro** : `running` (la synchro en cours, vue `SyncView` avec sa progression, ou `null`) et
   `lastSync` (la dernière synchro terminée, avec sa phrase de tête et ses manques — `RadarCoverageSummary`
   de F-100 — ou `null`).
4. **« Il dit ce qu'il n'a pas lu »** (§4.4) : `coverageComplete` est vrai **seulement** si la dernière
   synchro terminée est `SUCCEEDED` et que sa phrase de tête dit « Synchro complète » ; sinon
   `coverageWarning` porte la phrase à afficher **en tête du résumé** : la phrase de tête de la synchro
   (partielle, échouée, annulée, rattrapée, faite avec manques), ou « Dernière synchro le 10 septembre : ce
   qui a bougé depuis n'a pas été lu. » si la dernière synchro date de plus de 36 h, ou « Aucune synchro
   encore : le Radar se remplira à la première synchro du soir. ».
5. **Lignes de couverture** (`coverageLines`) de la dernière synchro terminée, une par source lue, avec
   `ok` : Teams (« 23 fils lus sur 25 actifs, 214 messages »), réunions (« 3 réunions transcrites ») si des
   réunions ont été vues, enregistrements déposés (« 1 enregistrement déposé transcrit ») si le dossier en
   contenait. `ok` est faux dès qu'un manque touche la source.

**Écran — onglet Radar d'un client de la Vigie** (nouveau `RadarBriefComponent`, porté par un
`RadarBoardComponent` qui remplace l'état vide de F-106) :

6. Carte **résumé** à deux zones (maquette) : à gauche le titre « *Mardi 15 septembre* — ce qui a bougé
   depuis hier », **l'avertissement de couverture en tête** s'il y en a un (texte ambre §12, jamais
   repliable), les phrases, puis les **compteurs** en tuiles (« à faire par moi », « relances dues »,
   « mises en relation », « sujets suivis » ; les deux premiers en encre orange quand non nuls) ; à droite
   la **couverture** : « Synchro d'hier, 22 h 00 — ce qui a été lu », *Synchroniser maintenant*, les lignes
   par source (✓ vert ou ! ambre, icône de source) et **les manques** listés sans repli (fil, statut,
   détail) avec leurs gestes *Ignorer ce fil* / *Lire ce canal* — ou « Règle posée : ignoré · Annuler ».
7. ***Synchroniser maintenant*** : `POST /syncs` (202) ⇒ le résumé est relu et affiche **la progression**
   (phrase de tête « Synchro en cours : 12 conversations sur 40. ») et ***Annuler*** (`POST
   /syncs/{id}/cancel`). Tant qu'une synchro tourne, le résumé est relu toutes les 10 s (arrêté dès
   qu'elle est terminée, ou le composant détruit).
8. Téléphone (< 860 px) : les deux zones s'empilent, le résumé d'abord.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Poste d'un autre compte ou inconnu | « introuvable », rien n'est dit du poste | 404 |
| Client non activé dans la Vigie | refus `host_not_in_space` | 409 |
| Sans droit Teams (bypass administrateur compris) | refus | 403 |
| *Synchroniser* : poste hors ligne | snackbar « Poste hors ligne : lancez le runner, puis recommencez. » ; rien créé | 409 |
| *Synchroniser* : une synchro tourne déjà | snackbar avec le message de la gateway ; résumé relu | 409 |
| *Annuler* : synchro déjà close | snackbar ; résumé relu | 409 |
| Résumé illisible (réseau, gateway antérieure) | encart « Le résumé n'a pas pu être lu. » + *Réessayer* ; jamais un résumé vide présenté comme calme | — |
| Couverture sans JSON (synchro ancienne) | aucune ligne de source ; la phrase de tête reste | 200 |

---

## Critères d'acceptation

- [ ] CA1 — `GET /brief` rend au plus 3 phrases, dans l'ordre de priorité, chacune avec son `subjectId` ou `commitmentId`.
- [ ] CA2 — Un engagement désavoué, tenu ou abandonné ne produit ni phrase ni compteur ; un sujet fusionné non plus.
- [ ] CA3 — `toHandle` compte une seule fois un engagement à la fois dû et `probable`.
- [ ] CA4 — Dernière synchro `PARTIAL` : `coverageComplete=false` et `coverageWarning` = sa phrase de tête ; `SUCCEEDED` complète : `coverageWarning=null`.
- [ ] CA5 — Aucune synchro : aucune phrase, `coverageWarning` « Aucune synchro encore… » ; dernière synchro de plus de 36 h : l'avertissement le dit.
- [ ] CA6 — Une synchro en cours est rendue dans `running`, la dernière terminée reste dans `lastSync`.
- [ ] CA7 — Isolation : le résumé d'un poste ne compte rien d'un autre poste du même utilisateur ni d'un autre utilisateur ; 404 pour autrui, 409 hors Vigie.
- [ ] CA8 — L'écran affiche l'avertissement **avant** les phrases, les compteurs, la couverture avec ses manques non repliés et leurs gestes.
- [ ] CA9 — *Synchroniser maintenant* lance la synchro, affiche la progression et *Annuler* ; un 409 s'affiche en snackbar.
- [ ] CA10 — *Ignorer ce fil* / *Lire ce canal* posent la règle (`POST /thread-rules`) et l'état « règle posée » se lit, annulable (`DELETE /thread-rules/{id}`).
- [ ] CA11 — Aucune couleur hors charte ; téléphone : zones empilées, résumé d'abord.

---

## Périmètre

### Hors scope (explicite)

- Les trois colonnes et les gestes *fait / pas moi / reporter / clore* (SF-102-02).
- Le compteur « à traiter » dans l'onglet et le bandeau, et `DESIGN_SYSTEM.md` §17 (SF-102-03).
- La page sujet et l'annuaire (F-103) ; *Donner la nouvelle* et les relances préparées (F-104).
- Un résumé rédigé par le modèle : le résumé du matin est **composé** depuis le registre (arbitrage ci-dessous).
- Le résumé envoyé par courriel ou notification (cadrage §15).
- La planification de l'heure du soir (écran à venir ; l'API de F-100 existe).

---

## Valeurs initiales

Non applicable — aucune entité créée (lecture seule, gestes = API existantes de F-100).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `hostId` (chemin) | Oui | — | UUID d'un poste possédé, activé dans la Vigie | — | — |
| Phrases | — | 3 phrases | citation d'un nom de sujet ou d'une description tronquée à 80 caractères (« … ») | — | — |

Notes : `since` = maintenant − 24 h ; synchro « ancienne » au-delà de 36 h ; relecture pendant une synchro :
10 s. Valeurs tranchées ici, réversibles.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/brief` | JWT | droit Teams (bypass admin) + client dans la Vigie |
| POST | `/api/radar/hosts/{hostId}/syncs` | existant (F-100) | idem |
| POST | `/api/radar/hosts/{hostId}/syncs/{syncId}/cancel` | existant (F-100) | idem |
| POST / DELETE | `/api/radar/hosts/{hostId}/thread-rules[/{ruleId}]` | existant (F-100) | idem |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_subjects`, `radar_commitments`, `radar_people`, `radar_syncs`, `radar_thread_rules`, `radar_host_settings` | SELECT | toujours filtrées `user_id` **et** `host_id` |

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

- `RadarBoardComponent` (`vigie/radar/`) — l'onglet Radar d'un client ; porte le résumé (et les colonnes en SF-102-02).
- `RadarBriefComponent` (`vigie/radar/`) — le résumé du matin, la couverture, *Synchroniser maintenant*.
- `RadarService` (`core/services/radar.service.ts`) + `radar.models.ts` — lectures et gestes du Radar.
- `radar-brief.ts` — fonctions pures (titre du jour, libellés de synchro, gestes d'un manque).
- `VigieComponent` — l'état vide de l'onglet Radar est remplacé par `app-radar-board`.

### Préoccupations transversales

- **Navigation : non** — aucune route ajoutée ni modifiée (onglet `?onglet=radar` existant).
- **Contexte tenant : non** — même résolution du périmètre que `RadarController` (`RadarScopeResolver.requireInVigie`).
- **Plans / limites : non** — même garde (`TeamsAccessService.requireAccess`) ; *Synchroniser maintenant* passe par le lanceur existant (réserve F-101 inchangée).
- **Auth / Principal : non.**

---

## Plan de test

### Tests unitaires

- [ ] `RadarBriefServiceTest` — priorité et plafond de 3 phrases ; relances groupées ; « rien n'a bougé ».
- [ ] `RadarBriefServiceTest` — compteurs : désavoué / tenu exclus, `toHandle` sans double compte.
- [ ] `RadarBriefServiceTest` — couverture : partielle en tête, complète sans avertissement, aucune synchro, synchro ancienne ; lignes par source.
- [ ] `radar-brief.spec.ts` — titre du jour, libellé « Synchro d'hier, 22 h 00 », gestes d'un manque.
- [ ] `radar-brief.component.spec.ts` — avertissement avant les phrases ; compteurs ; manques et gestes ; *Synchroniser* (202 puis progression + *Annuler*) ; 409 en snackbar ; erreur de lecture.

### Tests d'intégration

- [ ] `GET /brief` → 200 avec phrases, compteurs, synchro partielle en tête.
- [ ] `GET /brief` → 404 pour Bob sur le poste d'Alice ; 409 poste hors Vigie.

### Isolation utilisateur

- [x] Applicable — deux postes d'Alice et le poste de Bob : le résumé du poste A ne compte rien de B ni de Bob.

---

## Dépendances

### Subfeatures bloquantes

- F-99 (registre), F-100 (synchros, couverture, règles de fil), F-101 (relances dues), F-106 (Vigie) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Résumé composé, pas rédigé** : les phrases sont assemblées depuis le registre (règles §4.1 et §4.3 :
  aucun fait sans preuve, rien d'interprété à nouveau). Pas d'appel au modèle à l'ouverture de l'écran
  (aucune dépense, aucune latence, rien de synchrone côté IA). Réversible : un résumé rédigé par
  `AIProvider` en tâche de fond à la fin de l'analyse pourra remplacer les phrases sans changer l'écran.
- **« Depuis hier » = 24 h glissantes** plutôt que « depuis la synchro précédente » : lisible et stable,
  indépendant d'une synchro manquée (que l'avertissement de couverture dit déjà).
- Un nouvel endpoint dans un **nouveau contrôleur** (`RadarBoardController`) plutôt qu'une extension de
  `RadarController` / `RadarViews` : la page sujet (F-103) est livrée en parallèle sur ces fichiers.
