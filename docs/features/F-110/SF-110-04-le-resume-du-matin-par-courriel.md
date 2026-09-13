# Mini-spec — F-110 / SF-110-04 — Le résumé du matin par courriel

> Base : `docs/features/F-110/CADRAGE-F-110-le-courriel-du-client.md` §5, §6 (cadrage validé par le PO, non
> rediscuté ici). S'appuie sur SF-110-01 (`resolveRecipient`), SF-110-02 (file `client_emails`), F-102 (résumé du
> matin, `RadarBriefService`) et SF-100-07 (le réglage de la synchro du soir).

## Identifiant

`F-110 / SF-110-04`

## Feature parente

`F-110` — Le courriel du client : l'application m'envoie des courriels

## Statut

`done` — livrée le 2026-09-13 (PR #571)

## Date de création

2026-09-13

## Branche Git

`feat/SF-110-04-resume-matin-courriel`

---

## Objectif

Une option par client, dans le réglage de la synchro du soir, « recevoir le résumé du matin par courriel » :
après chaque synchro du soir analysée, la gateway met en file un courriel sobre — phrases du résumé, compteurs,
relances dues, lien vers la Vigie — vers l'adresse de réception du client.

---

## Comportement attendu

### Cas nominal

1. **Le réglage** — `GET /api/radar/hosts/{hostId}/schedule` rend en plus `morningEmail` (booléen) ;
   `PUT …/schedule` accepte `morningEmail` (absent = inchangé). Activer l'option **ne renvoie pas** le résumé
   d'une synchro déjà passée : la dernière synchro du soir terminée est notée comme traitée.
2. **L'écran** — le dialogue *Synchro du soir* (SF-100-07) porte une case « Recevoir le résumé du matin par
   courriel », avec la phrase : « Envoyé après la synchro du soir, à l'adresse de réception du client (réglée dans
   son en-tête), sinon à l'adresse de votre compte. » La ligne d'en-tête ajoute « · résumé par courriel » quand
   l'option est active. Enregistrer l'envoie avec le reste ; snackbar inchangé.
3. **L'envoi** — un travailleur (`RadarMorningMailWorker`, toutes les 5 min, désactivable) parcourt les postes
   dont l'option est active. Pour chacun :
   - le droit Vigie est ouvert (ADMIN d'office) et le client est activé dans la Vigie ; sinon rien ;
   - la **dernière synchro du soir** (`SCHEDULED` ou `CATCH_UP`) est terminée (`SUCCEEDED` ou `PARTIAL`), n'a pas
     déjà donné lieu à un résumé, et son **analyse est posée** (aucun lot `PENDING`/`PROCESSING`) — ou terminée
     depuis plus de 2 h (on n'attend pas indéfiniment une analyse) ;
   - terminée depuis plus de 18 h : elle est notée traitée **sans envoi** (pas de résumé d'avant-hier) ;
   - la prise est **atomique** (mise à jour conditionnelle du marqueur) et, dans la même transaction, un courriel
     `MORNING_SUMMARY` est mis en file vers `resolveRecipient` ; la file de SF-110-02 l'envoie (reprise, délais
     bornés, corps effacé à l'état final).
4. **Le courriel** — objet « Résumé du matin — CAGIP » ; corps Markdown rendu sobre : les phrases du résumé
   (F-102, au plus trois) ou « Rien n'a bougé », l'avertissement de couverture s'il y en a un, un tableau des
   compteurs (à faire, relances dues, mises en relation, sujets suivis, bloqués, à traiter), la liste des
   **relances dues** (personne — engagement, au plus 10), le repli dit (« Aucune adresse vérifiée pour CAGIP :
   ce résumé arrive à l'adresse de votre compte. ») et un lien **Ouvrir la Vigie** (`{app.frontend-url}/vigie/{hostId}`).
5. **La limite quotidienne** de `email_me` (50) ne compte que les courriels `AGENT` : un résumé n'en consomme pas
   et n'est pas bloqué par une boucle de l'agent ; il part au plus une fois par synchro du soir.
6. **Le cadrage du Radar** — `docs/features/F-99/CADRAGE-le-radar.md` §15 : retrait du hors-périmètre « résumé
   du matin envoyé par courriel », remplacé par le renvoi vers F-110 (la notification reste hors périmètre).

### Cas d'erreur

| Situation | Comportement attendu | Code / effet |
|-----------|---------------------|-----------|
| `morningEmail` non booléen | refus du réglage | 400 |
| Droit Vigie retiré, client retiré de la Vigie | aucun résumé ; réexaminé au passage suivant | — |
| Synchro du soir `FAILED` / `CANCELLED`, ou seulement manuelle | aucun résumé | — |
| Analyse encore en cours | on attend (au plus 2 h après la fin de la synchro) | — |
| Deux pods au même passage | un seul courriel (marqueur conditionnel) | — |
| Relais en échec | reprise de la file SF-110-02, puis `FAILED` | journal `client_emails` |
| Poste inaccessible (droits `GET/PUT …/schedule`) | inchangé (SF-100-02) | 403 / 404 / 409 |

---

## Critères d'acceptation

- [ ] `GET …/schedule` rend `morningEmail` ; `PUT` avec `morningEmail` l'enregistre ; `PUT` sans le champ ne le change pas.
- [ ] Activer l'option ne renvoie pas le résumé de la synchro du soir déjà terminée.
- [ ] Après une synchro du soir terminée et analysée, un et un seul courriel `MORNING_SUMMARY` est mis en file vers le destinataire résolu, avec phrases, compteurs, relances dues et lien vers la Vigie.
- [ ] Rien ne part : sans droit Vigie, client hors Vigie, synchro manuelle ou en échec, analyse en cours (< 2 h), synchro de plus de 18 h (marquée traitée), option désactivée.
- [ ] Le repli sur l'adresse du compte est dit dans le courriel.
- [ ] Les résumés ne comptent pas dans la limite de 50 courriels de l'agent.
- [ ] Le dialogue porte la case et la phrase ; l'en-tête dit « · résumé par courriel ».
- [ ] `CADRAGE-le-radar.md` §15 ne porte plus « résumé du matin envoyé par courriel » en hors-périmètre.
- [ ] Isolation : tout accès filtre `user_id` + `host_id` ; le travailleur n'envoie qu'au destinataire résolu du propriétaire du poste.
- [ ] DESIGN_SYSTEM : `mat-checkbox`, aucune couleur nouvelle.

---

## Périmètre

### Hors scope (explicite)

- Une heure d'envoi choisie (le résumé part après la synchro, pas à heure fixe).
- Une notification (push, Teams) du résumé.
- Un résumé multi-clients (un courriel par client).
- Les pièces jointes (SF-110-03), l'export Markdown du Radar joint.
- Un écran de journal des envois.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `radar_host_settings.morning_email` | `false` | option par client |
| `radar_host_settings.morning_email_sync_id` | nul | dernière synchro du soir traitée (envoyée ou écartée) ; posée à l'activation |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `morningEmail` | Non | — | booléen ; absent = inchangé | — | — |

---

## Technique

### Endpoint(s) (étendus)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/schedule` | JWT | droit Vigie (inchangé) |
| PUT | `/api/radar/hosts/{hostId}/schedule` | JWT | droit Vigie (inchangé) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_host_settings` | UPDATE (2 colonnes) | `morning_email`, `morning_email_sync_id` |
| `client_emails` | INSERT | genre `MORNING_SUMMARY` |
| `radar_syncs`, `radar_analysis_batches`, registre du Radar | SELECT | via `RadarReadService` / `RadarBriefService` |

### Migration Liquibase

- [x] Oui — `103-radar-morning-email.xml` (premier numéro libre au-dessus du dernier sur `main`, revérifié après rebase).

### Composants

| Composant | Rôle |
|-----------|------|
| `radar/sync/RadarHostSettings` (+ repository) | les deux colonnes ; page des postes à option ; marqueur conditionnel |
| `radar/sync/RadarScheduleService` | `morningEmail` dans la vue et la requête ; marqueur posé à l'activation |
| `radar/RadarBriefService` | `dueFollowUps(scope, max)` : les relances dues en lignes lisibles |
| `radar/sync/RadarMorningMail` + `RadarMorningMailWorker` | éligibilité, prise atomique, composition, mise en file |
| `mail/ClientMailOutbox` | réutilisée (`Kind.MORNING_SUMMARY`) |
| Angular : `radar.models.ts`, `radar-schedule.ts`, `RadarScheduleDialogComponent`, `RadarScheduleComponent` | la case, la phrase, l'en-tête |
| `docs/features/F-99/CADRAGE-le-radar.md` | §15 |

---

## Plan de test

### Tests unitaires

- [ ] `RadarMorningMailTest` — composition (phrases, compteurs, relances, repli, lien) ; éligibilité : envoyé une fois ; pas de droit / hors Vigie / manuelle / en échec / analyse en cours / > 18 h (marquée) / > 2 h malgré l'analyse ; marqueur pris par un autre pod → rien.
- [ ] `RadarScheduleServiceTest` (ou intégration) — `morningEmail` lu, écrit, inchangé si absent, marqueur posé à l'activation.
- [ ] `radar-schedule.spec.ts` (étendu) — « · résumé par courriel » ; requête avec `morningEmail`.
- [ ] `radar-schedule.component.spec.ts` (étendu) — la case est lue et envoyée.

### Tests d'intégration

- [ ] `RadarMorningMailIntegrationTest` — base réelle : réglage via `PUT …/schedule`, synchro du soir terminée et analysée, passage du travailleur → une ligne `client_emails` `MORNING_SUMMARY` vers l'adresse vérifiée ; second passage → aucune ; isolation (le poste d'autrui n'est pas touché, `PUT` d'autrui refusé).

### Isolation utilisateur

- [x] Applicable — le travailleur part de chaque ligne `radar_host_settings` (`user_id`, `host_id`) et ne lit que ce périmètre ; les endpoints restent ceux de SF-100-02.

---

## Dépendances

### Subfeatures bloquantes

- SF-110-01 — `done` (PR #562) ; SF-110-02 — `done` (PR #565) ; F-102, SF-100-07 — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Plans / limites : oui** — l'envoi vérifie `TeamsAccessService.hasAccess` (droit Vigie via `SpaceEntitlementService`,
  ADMIN d'office) et `HostSpaceService.isActiveForOwner(VIGIE)`, exactement comme `RadarSyncPlanner` ; la limite
  quotidienne `ClientMailTool.DAILY_LIMIT` ne compte que `Kind.AGENT` (inchangée). Aucun quota de jetons lu (le
  résumé ne fait aucun appel modèle).
- **Contexte tenant : oui** — le destinataire vient de `HostMailAddressService.resolveRecipient(userId, hostId)` avec
  le propriétaire de la ligne de réglage ; `RadarBriefService.brief(scope)` et `RadarReadService.syncs(scope)` filtrent
  `user_id` + `host_id`.
- **Auth / Principal : non.** **Navigation / routing : non** (lien sortant vers `/vigie/{hostId}`, route existante).

---

## Notes et décisions

- **Après l'analyse, pas à heure fixe** : le résumé part quand il a quelque chose à dire ; au plus 2 h d'attente de
  l'analyse, pour ne pas le perdre si un lot reste en file.
- **Repli sur l'adresse du compte** : comme pour `email_me`, on s'écrit à soi ; le courriel le dit.
- **Hors limite quotidienne** : la limite protège d'une boucle de l'agent ; le résumé est borné par construction
  (un par synchro du soir).
