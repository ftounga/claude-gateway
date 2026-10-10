# Mini-spec — [F-185 / SF-185-07] Récapitulatif quotidien des attentes à relancer

## Identifiant

`F-185 / SF-185-07`

## Feature parente

`F-185` — Rien ne vous attend en silence

## Statut

`in-progress`

## Date de création

2026-10-10

## Branche Git

`feat/SF-185-07-recapitulatif-attentes`

---

## Objectif

Prévenir, au plus une fois par jour ouvré, qu'il y a des attentes à relancer (F-175), au lieu de ne les montrer qu'à qui ouvre le terminal.

---

## Comportement attendu

### Cas nominal

1. **Planification** : chaque jour ouvré à **9 h, heure de Paris** (cron `app.notifications.digest.cron`, par défaut `0 0 9 * * MON-FRI`, zone `Europe/Paris`), `AttentesDigestWorker` lance le récapitulatif. Il peut être désactivé (`app.notifications.digest.enabled=false`).
2. **Qui** : chaque compte qui a au moins une attente au statut « Demandé » dont la relance est due, selon la règle existante (`TerminalActionFollowUp.isDue` : 3 jours ouvrés depuis la demande).
3. **Quoi** : `PushEvent.ATTENTES_TO_FOLLOW_UP`, « Des attentes sont à relancer » / « Ouvrez la Forge pour les voir. ».
   - La charge est neutre : ni nombre ni contenu.
   - Le lien mène à `/forge`.
   - Le récapitulatif passe par l'émetteur comme tout événement : il entre dans le centre de notifications, il respecte la sourdine et les heures calmes (il n'est pas critique), et il est retenu si le terminal est regardé (sans terminal, il ne l'est jamais).
4. **Une fois par compte et par jour** : avant d'émettre, une ligne `(user_id, digest_day)` est insérée dans `notification_digests`, avec une clé primaire composite. Si elle existe déjà (second pod, relance du worker), rien n'est émis. Les deux pods peuvent donc lancer la tâche sans doublon.
5. **Rétention** : les lignes de plus de 30 jours sont purgées par le worker. La suppression du compte purge celles du compte.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Aucune attente due | Rien n'est émis, aucune ligne écrite | — |
| Deux pods en même temps | Une seule émission (conflit de clé absorbé) | — |
| Échec pour un compte | Journal d'avertissement ; les autres comptes continuent | — |
| Worker désactivé | Rien | — |
| Récapitulatif coupé dans les préférences | Inscrit dans la cloche, ne sonne pas (SF-185-06) | — |

---

## Critères d'acceptation

- [ ] Un compte avec une attente due reçoit `ATTENTES_TO_FOLLOW_UP` une fois ; un compte sans attente due ne reçoit rien.
- [ ] Un second passage le même jour (ou un second pod) n'émet rien de plus.
- [ ] Le lendemain, une attente toujours due est de nouveau rappelée.
- [ ] Les attentes d'un compte ne déclenchent jamais le récapitulatif d'un autre.
- [ ] L'événement est neutre, n'est pas critique et figure dans le catalogue des préférences.

---

## Plan de test

- **Intégration** (`AttentesDigestIntegrationTest`, base de test) :
  - deux comptes, l'un avec une attente due, l'autre avec une attente récente → une seule émission, pour le bon compte ;
  - second passage le même jour → aucune émission ;
  - passage le lendemain → nouvelle émission ;
  - purge des lignes de plus de 30 jours.
- **Unitaires** : `PushEvent` (neutre, non critique, lien `/forge` sans terminal) ; le worker désactivable (`@ConditionalOnProperty`).
- **Isolation** : test croisé ci-dessus. Les émissions portent le `user_id` de l'attente.

---

## Composants impactés

- **Table** `notification_digests` (migration `152-notification-digests.xml`, rollback = suppression de la table) :
  - colonnes : `user_id uuid`, `digest_day date`, `created_at timestamptz`, clé primaire `(user_id, digest_day)`.
- Backend :
  - `push/PushEvent.ATTENTES_TO_FOLLOW_UP` ;
  - `notifications/NotificationDigest(Id)`, son dépôt, `AttentesDigestService` et `AttentesDigestWorker` ;
  - `TerminalActionRepository.findByStatus(DEMANDE)` : lecture transverse **du worker seulement**, dont chaque émission reste rattachée au `user_id` de la ligne ;
  - `AccountService` : purge.
- `application.yml` : `app.notifications.digest.*`.
- `docs/ARCHITECTURE_CANONIQUE.md` : ajouter la table.
- Frontend : aucun changement. L'événement apparaît de lui-même dans la cloche et dans les préférences (catalogue servi par l'API).

## Préoccupations transversales

- **Contexte tenant** : oui. Composants impliqués :
  - le worker lit les attentes « Demandé » de tous les comptes ;
  - **chaque** émission et chaque ligne de récapitulatif portent le `user_id` de l'attente ;
  - aucune donnée d'un compte ne part vers un autre (test croisé) ;
  - `AccountService` purge les lignes du compte.
- Auth, plans / limites, navigation : non.

---

## Périmètre

### Hors scope (explicite)

- Récapitulatif par courriel.
- Heure d'envoi réglable par utilisateur (9 h, heure de Paris, pour tous ; les heures calmes de chacun s'appliquent).
- Contenu du récapitulatif dans la notification (D1).
