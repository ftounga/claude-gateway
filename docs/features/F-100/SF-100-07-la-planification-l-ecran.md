# Mini-spec — F-100 / SF-100-07 — La planification, l'écran

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §5 (« chaque jour, à une heure choisie par poste, 22 h 00 par
> défaut ») et §14 (prérequis contractuel rappelé à l'activation) ; API livrée en SF-100-02
> (`docs/features/F-100/SF-100-02-la-planification.md`). Constat de clôture F-104 (`PRODUCT_SPEC.md`, ligne F-100).

## Identifiant

`F-100 / SF-100-07`

## Feature parente

`F-100` — Le Radar : la synchro du soir

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-100-07-planification`

---

## Objectif

Dans l'en-tête d'un client de la Vigie, l'utilisateur voit l'état de la synchro du soir (activée ou non,
heure, prochaine exécution) et la règle : activer / désactiver — avec la confirmation que son client
autorise la conservation d'extraits — et choisir l'heure (22 h par défaut).

---

## Comportement attendu

### Cas nominal

1. **La ligne d'en-tête** `app-radar-schedule` (sous le nom du client, à côté de sa présence), lue par
   `GET /api/radar/hosts/{hostId}/schedule` une fois par client ouvert (et relue après réglage) :
   - activée : « Synchro du soir à 22:00 · prochaine : ce soir 22:00 » (ou « demain 22:00 », « 15 sept.
     22:00 ») ; si `missedSlotAt` : « · synchro de 22:00 manquée, rattrapée à la prochaine connexion » (ambre
     §12) ; si `running` : « · synchro en cours » ;
   - désactivée : « Synchro du soir désactivée » ;
   - un bouton compact *Régler* (icône `schedule`) ouvre le dialogue ;
   - illisible (droit retiré, gateway antérieure) : la ligne ne s'affiche pas (l'en-tête n'est pas un
     diagnostic).
2. **Le dialogue** `RadarScheduleDialogComponent` :
   - case *Synchroniser chaque soir* (état actuel) ;
   - champ *Heure* `mat-form-field` outline, `type="time"`, valeur actuelle (22:00 par défaut) ;
   - le fuseau, écrit : « heure de Europe/Paris » ; à la **première activation** (jamais autorisée), le
     fuseau proposé est celui du navigateur ;
   - à la **première activation** seulement (`clientAuthorizedAt` nul et case cochée) : case obligatoire
     « Mon client autorise la conservation d'extraits de ses échanges dans l'application (citations
     courtes et liens, jamais d'archives) » ; *Enregistrer* reste désactivé tant qu'elle n'est pas cochée ;
     une fois autorisé : « Autorisation du client confirmée le 13 septembre 2026 » ;
   - *Enregistrer* → `PUT …/schedule` `{ enabled, syncTime, timeZone, clientAuthorizationConfirmed }` ; la
     réponse remplace la ligne d'en-tête ; snackbar « Synchro du soir activée à 22:00. » / « désactivée. » ;
   - rappel : « Régler l'heure ne lance pas de synchro : la première partira au prochain créneau. »
3. **Aucune synchro** n'est lancée par ce geste (règle de la gateway) ; *Synchroniser maintenant* reste
   dans l'onglet Radar.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Première activation sans confirmation (écran contourné) | message de la gateway dans le dialogue, rien n'est enregistré | 400 `radar_invalid` |
| Heure illisible / fuseau inconnu | message de la gateway dans le dialogue | 400 `radar_invalid` |
| Client hors Vigie, droit retiré | « Le réglage n'est pas disponible pour ce client. » | 403 / 404 / 409 |
| Gateway injoignable à l'enregistrement | « Le réglage n'a pas pu être enregistré. Rien n'a changé. » ; le dialogue reste ouvert | 0 / 5xx |
| Lecture illisible | ligne absente de l'en-tête | — |

---

## Critères d'acceptation

- [ ] L'en-tête d'un client de la Vigie lit `GET …/schedule` une fois et écrit : activée à *heure*, prochaine exécution, créneau manqué, synchro en cours — ou désactivée.
- [ ] *Régler* ouvre le dialogue avec l'état actuel ; *Enregistrer* envoie `PUT …/schedule` avec `enabled`, `syncTime`, `timeZone`, `clientAuthorizationConfirmed`, puis la ligne suit la réponse.
- [ ] Première activation : la confirmation de l'autorisation du client est exigée à l'écran ; ensuite, la date de confirmation est dite et la case n'est plus demandée.
- [ ] Désactiver n'exige aucune confirmation.
- [ ] Un refus 400 reste dans le dialogue avec le message de la gateway.
- [ ] DESIGN_SYSTEM : aucune couleur nouvelle (ambre §12 pour le créneau manqué), formulaire outline, `MatDialog`, `MatSnackBar`.

---

## Périmètre

### Hors scope (explicite)

- Toute modification de l'API ou du planificateur.
- Un sélecteur de fuseau libre (le fuseau est écrit ; il vient du réglage existant ou, à la première
  activation, du navigateur).
- *Synchroniser maintenant*, progression, annulation (déjà dans l'onglet Radar, F-102).
- Retirer l'autorisation du client une fois donnée (la gateway ne l'expose pas).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `syncTime` | `22:00` | rendu par la gateway |
| `timeZone` | réglage existant ; à la première activation, celui du navigateur (repli `Europe/Paris`) | IANA |
| Case d'autorisation | décochée | exigée à la première activation |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `enabled` | Oui | — | booléen | — | — |
| `syncTime` | Oui | 5 | `HH:mm`, 00:00 → 23:59 | — | champ `time` du navigateur |
| `timeZone` | Oui | — | identifiant IANA | — | `Intl` du navigateur |
| `clientAuthorizationConfirmed` | À la 1ʳᵉ activation | — | `true` | — | — |

---

## Technique

### Endpoint(s) (consommés, inchangés)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/schedule` | JWT | droit Radar |
| PUT | `/api/radar/hosts/{hostId}/schedule` | JWT | droit Radar |

### Tables impactées

- Aucune (API existante, `radar_host_settings`).

### Migration Liquibase

- [x] Non.

### Composants Angular

| Composant | Rôle |
|-----------|------|
| `radar.models.ts` | `RadarSchedule`, `RadarScheduleRequest` |
| `RadarService` | `schedule`, `updateSchedule` |
| `vigie/radar-schedule/radar-schedule.ts` | phrase d'en-tête, dates, validation, fuseau du navigateur (pur) |
| `RadarScheduleComponent` | ligne d'en-tête, lecture, *Régler* |
| `RadarScheduleDialogComponent` | activer, heure, autorisation, enregistrement |
| `VigieComponent` | la ligne dans l'en-tête du client ouvert |

---

## Plan de test

### Tests unitaires

- [ ] `radar-schedule.spec.ts` — phrase activée / désactivée / manquée / en cours ; « ce soir », « demain », date ; autorisation exigée ou non ; heure valide ; fuseau du navigateur et repli.
- [ ] `radar-schedule.component.spec.ts` — la ligne : lecture, rendu, illisible → rien ; *Régler* ouvre le dialogue ; la réponse remplace la ligne. Le dialogue : première activation : *Enregistrer* désactivé sans la case, corps envoyé ; déjà autorisé : pas de case ; désactiver ; refus 400 dit.
- [ ] `vigie.component.spec.ts` (étendu) — la ligne est présente pour le client ouvert.
- [ ] `radar.service.spec.ts` (étendu) — URLs, méthode, corps.

### Tests d'intégration

- [x] Backend inchangé : `RadarScheduleApiIntegrationTest` (SF-100-02 : confirmation exigée, validation, créneau passé non déclenché, isolation) rejoué.

### Isolation utilisateur

- [x] Applicable côté gateway (inchangée, rejouée) ; l'écran n'envoie aucun identifiant de compte.

---

## Dépendances

### Subfeatures bloquantes

- SF-100-02 (API) — `done` ; F-106 — `done` ; SF-100-06 — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : non.** **Auth / Principal : non.** **Navigation / routing : non.**
- **Plans / limites : non** — l'écran ne crée aucun gate ; la réserve de synchro (F-107) reste appliquée
  par la gateway au lancement.

---

## Notes et décisions

- **Fuseau du navigateur à la première activation** : l'utilisateur règle « 22 h » là où il est ; ensuite,
  le fuseau enregistré est conservé (un changement de navigateur ne déplace pas le créneau en silence).
- **L'autorisation du client est demandée par l'écran avant l'appel** (et reste exigée par la gateway) :
  §14, prérequis de mise en service, rappelé au moment d'activer.
