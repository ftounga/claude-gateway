# Mini-spec — [F-270 / SF-270-04] Précision ou nouvelle commande

## Identifiant
`F-270 / SF-270-04`

## Feature parente
`F-270` — Parler à Jarvis pendant qu'il travaille

## Statut
`in-progress`

## Date de création
2026-11-18

## Branche Git
`feat/SF-270-04-precision-ou-nouvelle`

---

## Objectif
Une deuxième commande dite sur un fil où un travail tourne est traitée comme une commande (enchaînée, une au plus par
fil) et non fondue dans le travail en cours ; « arrête <nom> » arrête le travail nommé.

## Cause traitée (cadrage C3, D4, D7)
- Délégation vers un fil vivant ⇒ toujours précision (`ADDED_TO_RUNNING`) : « et relance le pipeline » devient une note
  dans un tour qui fait autre chose.
- « Arrête » n'arrête que la dernière délégation, sans chantier (`interruptChat(sujet)`).

## Comportement attendu

### Cas nominal
1. **D4** — l'outil `deleguer` du cerveau rapide porte `relation` : `precision` (complète CE travail) ou `nouvelle`
   (autre travail) ; doute ou aucun travail en cours : `precision`. Description d'outil seulement : consigne système
   du cerveau rapide et préfixe Opus intacts.
2. Fil vivant + `precision` ⇒ inchangé (« J'ajoute ça à la recherche en cours : … »).
3. Fil vivant + `nouvelle` ⇒ **enchaînée** : rangée `QUEUED` dans le registre partagé (SF-270-01), annonce « Je le lance
   dès que <travail en cours> est fini. » ; au plus **une** par fil : une deuxième est refusée et dite (« Une commande
   attend déjà la fin de ce travail ; je ne peux pas en garder une deuxième. »).
   Une commande vers un **autre chantier** du sujet n'est pas sur le fil vivant : elle part en parallèle (inchangé).
4. Fin du travail devant elle (fin locale de la délégation, ou travaux relus sans travail vivant sur le fil) ⇒ l'écran
   la **libère** (`POST /api/jarvis/works/{id}/release`) puis ouvre son tour (chemin des délégations).
5. **S7** — à la libération, poste éteint ⇒ abandonnée (`CANCELLED`) et dite : « Le poste <nom> est éteint : je ne lance
   pas <libellé>. » Jamais gardée. Jamais libérée au-delà de 30 min ⇒ abandonnée par le balayage.
6. **D7** — « arrête / stop / annule <nom> » : le travail dont le nom court, le libellé ou le sujet correspond — délégation
   ou tour en fond (interruption de **son fil** : sujet + chantier), commande enchaînée (annulée,
   `DELETE /api/jarvis/works/{id}`), course autonome (« Reprendre la main » F-219 de son chantier) ; « J'arrête <nom>. ».
   Sans nom : le dernier (inchangé, mais sur son chantier). Nom introuvable : « Je ne trouve pas <nom> parmi les
   travaux en cours. »
7. L'écran relit `GET /api/jarvis/works` (toutes les 4 s en conversation ou tant qu'un travail tourne, 16 s sinon).
8. Une fin de travail n'est jamais annoncée **pendant que le PO parle** : en plus de la transcription en cours, la voix
   détectée (détection de voix, ou voix entendue par l'écoute) retient l'annonce jusqu'à la pause suivante (banc du
   18/11 : l'annonce fermait l'écoute 0,9 s après le début de « Arrête facturation », phrase perdue).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| `relation` absente ou invalide | traitée comme `precision` |
| Deuxième commande enchaînée sur le même fil | refusée et dite, rien n'est gardé |
| Libération : poste éteint | abandonnée et dite, aucun tour |
| Libération : 404 (déjà annulée, balayée) | rien n'est lancé |
| Lecture des travaux en échec | la liste garde sa dernière valeur |
| Arrêt d'un nom inconnu | dit, rien n'est arrêté |

## Critères d'acceptation
- [ ] `deleguer` porte `relation` (enum, requis) dans sa description d'outil ; consigne système inchangée (test d'égalité existant vert).
- [ ] Fil vivant + `nouvelle` ⇒ `queued`, annonce « Je le lance dès que … est fini. », ligne `QUEUED` ; jamais prise avant libération.
- [ ] Deuxième `nouvelle` sur le même fil ⇒ refus dit, rien de rangé.
- [ ] Libération ⇒ offre fraîche prise par le tour ; poste éteint ⇒ `CANCELLED` + phrase ; > 30 min ⇒ abandonnée.
- [ ] Écran : la commande enchaînée part à la fin du travail de son fil ; « arrête facturation » arrête ce travail-là.
- [ ] Isolation : libérer / annuler la commande d'un autre compte ⇒ 404.
- [ ] Banc : relation correcte ≥ 95 % sur le corpus du scénario « précision ou nouvelle » ; aucune régression.

## Périmètre

### Hors scope (explicite)
- Satellites et teintes : SF-270-05.
- Interrompre une course autonome par une précision (jamais injectée dans une planification — cadrage §6).

## Valeurs initiales
| Paramètre | Valeur |
|---|---|
| Commandes enchaînées par fil | 1 |
| Attente max d'une commande enchaînée | 30 min |
| Relecture des travaux | 4 s actif, 15 s sinon |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `relation` | `precision` \| `nouvelle` |
| `{id}` | UUID d'un travail du compte |

## Technique

### Endpoint(s)
- `POST /api/jarvis/works/{id}/release` — 200 `{status: RELEASED}` ; 409 `{status: HOST_OFFLINE, host}` ; 404.
- `DELETE /api/jarvis/works/{id}` — 204 ; 404.
- `POST /api/jarvis/converse` : l'événement `delegated` porte `relation` et `queued` (additifs).

### Tables impactées
`jarvis_delegations` : phase `QUEUED` (colonne existante, SF-270-01). Aucune migration.

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
- `jarvis-works.ts` : `list`, `release`, `cancel`, `stopTarget`.
- `jarvis.component.ts` : relecture des travaux, commandes enchaînées, arrêt ciblé.

## Plan de test

### Tests unitaires
- `JarvisFastBrainServiceTest` : nouvelle ⇒ enchaînée ; une par fil ; précision inchangée ; fil libre ⇒ normal.
- `jarvis-works.spec.ts` : `stopTarget`. `jarvis.component.spec.ts` : enchaînée lancée à la fin ; poste éteint dit ;
  arrêt ciblé (délégation sur son chantier, enchaînée annulée, course arrêtée, nom inconnu).

### Tests d'intégration
- `JarvisWorksIntegrationTest` : jamais prise ni balayée avant libération ; libérée puis prise ; poste éteint ;
  > 30 min abandonnée ; API release/cancel + isolation.
- Banc à vraie voix : scénario « précision ou nouvelle » (poste simulé).

### Isolation workspace
Libérer/annuler sous `user_id` (404 pour un autre compte), testé.

## Dépendances

### Subfeatures bloquantes
SF-270-01, SF-270-02 (mergées).

### Questions ouvertes impactées
Aucune.

## Notes et décisions
- **D1** La commande enchaînée est libérée par l'écran (qui ouvre les tours) ; la passerelle garde l'état et décide du
  poste éteint ; le balayage garantit qu'aucune commande n'est gardée au-delà de 30 min.
- **D2** Le cadrage dit « départ dans la minute » : 30 min est le filet du balayage, pas une attente voulue.
- **D3** Description de `relation` resserrée après le premier passage du banc (« et relance aussi le pipeline » qualifié
  `precision`) : une AUTRE action, même introduite par « et » ou « aussi », est `nouvelle`.
