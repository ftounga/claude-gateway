# Mini-spec — [F-99 / SF-99-07] Exporter le Radar

---

## Identifiant

`F-99 / SF-99-07`

## Feature parente

`F-99` — Le Radar : le registre de l'organisation
(cadrage validé : `CADRAGE-le-radar.md`, §4.6, §14 ; API livrée en SF-99-05)

## Statut

`done` — livrée le 2026-09-13 (PR #557)

## Date de création

2026-09-13

## Branche Git

`feat/SF-99-07-exporter-radar`

---

## Objectif

L'utilisateur télécharge le Radar d'un client en Markdown depuis la Vigie, et cet export lui est
**proposé avant toute purge** : retrait d'un client de la Vigie, clôture de mission, suppression du poste.

---

## Contexte

Constat de clôture F-104 (`PRODUCT_SPEC.md`, ligne F-99) : `GET …/export` existe (SF-99-05) mais aucun
écran ne l'appelle ; le retrait de la Vigie propose d'effacer le Radar **sans** proposer l'export ; la
clôture de mission n'offre ni export ni purge (SF-99-05 l'avait différé : « le branchement se fait avec
l'écran qui propose l'export ») ; la suppression d'un poste purge son Radar côté gateway sans le dire.

---

## Comportement attendu

### Cas nominal

1. **Le composant d'export** `app-radar-export-offer` (réemployé partout) : un bouton *Exporter le
   Radar (Markdown)* ; au clic, `GET /api/radar/hosts/{hostId}/export` (blob), téléchargement sous le
   nom rendu par `Content-Disposition` (repli `radar-<client>.md`) ; pendant l'appel, un indicateur ;
   après, « Exporté : *fichier* » ; en échec, la phrase et *Réessayer*. Rien n'est purgé par ce geste.
2. **Dans la Vigie** — menu « ··· » de l'en-tête du client : *Exporter le Radar (Markdown)* ;
   snackbar « Radar exporté : *fichier* » ou l'erreur.
3. **Retrait de la Vigie** (`RemoveClientDialogComponent`) — quand la case *Effacer aussi son Radar* est
   cochée, le dialogue affiche **avant** le bouton de confirmation : « Avant d'effacer, gardez-en une
   copie » et `app-radar-export-offer`. La purge reste `VIGIE_REMOVED`, décochée par défaut.
4. **Clôture de mission** — `CloseMissionDialogComponent` (nouveau), ouvert quand on clôture la mission
   d'un client **activé dans la Vigie** :
   - dans la Forge (menu d'état de mission, `PostesComponent.setMission(…, 'CLOSED')`) ;
   - dans la Vigie (menu « ··· » : *Clôturer la mission…*, absent si déjà close) — un client qui n'est que
     dans la Vigie peut enfin être rangé, comme le dit le dialogue de retrait ;
   - le dialogue dit « le client est rangé, rien n'est coupé », propose l'export, et une case *Effacer
     aussi son Radar* **décochée** ; *Clôturer la mission* → `PUT /api/runner-hosts/{id}/mission`
     `CLOSED`, **puis** si la case est cochée `POST …/purge` `{ reason: MISSION_CLOSED, confirm: true }` ;
   - client absent de la Vigie : la clôture reste immédiate, sans dialogue (inchangé).
5. **Suppression d'un poste** (`DeleteHostDialogComponent`) — pour un poste activé dans la Vigie, « Ce
   qui sera effacé » ajoute « Son Radar : sujets, engagements, annuaire » et le dialogue propose l'export
   avant *Supprimer le poste*.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Export : poste d'autrui / inconnu | « Le Radar n'a pas pu être exporté. » + *Réessayer* ; rien d'autre ne change | 404 |
| Export : gateway injoignable | même message, *Réessayer* | 0 / 5xx |
| Clôture refusée | message, la purge **n'est pas** appelée, l'état ne bouge pas | 4xx / 5xx |
| Clôture réussie, purge en échec | « Mission clôturée, mais son Radar n'a pas pu être effacé. » | 4xx / 5xx |
| Purge `MISSION_CLOSED` sur une mission non close (réponse de la gateway différente) | purge non appelée si la réponse n'est pas `CLOSED` | — |

---

## Critères d'acceptation

- [ ] *Exporter le Radar* appelle `GET /api/radar/hosts/{hostId}/export` en blob et déclenche le téléchargement avec le nom de `Content-Disposition`.
- [ ] Un échec d'export est dit, avec *Réessayer* ; aucun appel de purge n'en découle.
- [ ] Le menu du client dans la Vigie propose l'export.
- [ ] Retrait de la Vigie, case cochée : l'export est proposé avant la confirmation ; décochée : il ne l'est pas.
- [ ] Clôture de mission d'un client de la Vigie (Forge et Vigie) : dialogue avec export et case décochée ; confirmer clôt, puis purge `MISSION_CLOSED` seulement si cochée et si la réponse dit `CLOSED`.
- [ ] Clôture d'un client hors Vigie : inchangée (pas de dialogue).
- [ ] Suppression d'un poste de la Vigie : le Radar figure dans ce qui est effacé, l'export est proposé.
- [ ] DESIGN_SYSTEM : aucune couleur nouvelle ; `MatDialog`, `MatSnackBar`, boutons stroked / flat.

---

## Périmètre

### Hors scope (explicite)

- Export JSON, export du compte entier (F-11).
- Purge « à la demande » hors de ces trois gestes (`USER_REQUEST`).
- Modification de l'API d'export ou de purge.
- Liste des traces de purge (`GET …/purges`).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| Case *Effacer aussi son Radar* (clôture) | décochée | irréversible, jamais présumé |
| Nom de fichier de repli | `radar-<client>.md` | si `Content-Disposition` absent |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| reason (purge) | Oui | — | `VIGIE_REMOVED` (retrait), `MISSION_CLOSED` (clôture) | — | — |
| confirm (purge) | Oui | — | `true` | — | — |
| nom de repli | — | 80 | `[a-z0-9-]` | — | nom du client translittéré |

---

## Technique

### Endpoint(s) (consommés, inchangés)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/export` | JWT | propriétaire du poste |
| POST | `/api/radar/hosts/{hostId}/purge` | JWT | propriétaire du poste |
| PUT | `/api/runner-hosts/{hostId}/mission` | JWT | propriétaire du poste |

### Tables impactées

- Aucune écriture nouvelle (purge existante).

### Migration Liquibase

- [x] Non.

### Composants Angular

| Composant | Rôle |
|-----------|------|
| `VigieService` | `exportRadar(hostId)` ; `purgeRadar(hostId, reason)` |
| `vigie/radar-export/radar-export.ts` | nom de repli (pur) |
| `RadarExportOfferComponent` | bouton, état, téléchargement |
| `CloseMissionDialogComponent` | clôture avec export et purge proposés |
| `RemoveClientDialogComponent` | export proposé quand la purge est cochée |
| `DeleteHostDialogComponent` | Radar dit effacé, export proposé |
| `VigieComponent` | menu : export, clôturer la mission |
| `PostesComponent` | clôture d'un client de la Vigie par le dialogue ; `deleteHost` passe le poste |
| `ExportService.triggerDownload` | réemployé |

---

## Plan de test

### Tests unitaires

- [ ] `radar-export.spec.ts` — nom de repli.
- [ ] `radar-export-offer.component.spec.ts` — appel, téléchargement, état, échec et *Réessayer*.
- [ ] `close-mission-dialog.component.spec.ts` — case décochée par défaut, résultat, export présent.
- [ ] `remove-client-dialog.component.spec.ts` (étendu) — export proposé seulement si purge cochée.
- [ ] `delete-host-dialog.component.spec.ts` (étendu) — Radar dit et export proposé pour un poste de la Vigie.
- [ ] `vigie.component.spec.ts` (étendu) — menu export ; clôture : ordre clôture puis purge, purge absente si décochée ou si clôture refusée.
- [ ] `postes.component.spec.ts` (étendu) — client de la Vigie : dialogue et purge `MISSION_CLOSED` ; client hors Vigie : inchangé.
- [ ] `vigie.service.spec.ts` (étendu) — export en blob, raison de purge.

### Tests d'intégration

- [x] Backend inchangé : `RadarPurgeExportApiIntegrationTest` (SF-99-05) couvre export, purge `MISSION_CLOSED` (409 si non close) et isolation ; rejoué.

### Isolation

- [x] Applicable (côté gateway, inchangée) — aucun identifiant de compte envoyé ; poste d'autrui → 404, dit à l'écran.

---

## Dépendances

### Subfeatures bloquantes

- SF-99-05 (API) — `done` ; F-106 (Vigie) — `done` ; F-60 (mission) — `done` ; F-69 (suppression) — `done`.

### Questions ouvertes impactées

- Aucune. (Risque résiduel de SF-99-05 « clôture de mission sans purge » levé : la purge y devient une option proposée avec l'export.)

---

## Préoccupations transversales

- **Contexte tenant : non** (aucun nouveau moyen de résoudre le poste).
- **Plans / limites : non** (export et purge sans droit d'option, inchangé).
- **Auth / Principal : non.**
- **Navigation / routing : non** (aucune route ; entrées de menu et dialogues).

---

## Notes et décisions

- **Clôturer la mission depuis la Vigie** : le dialogue de retrait dit déjà à un client « n'étant que
  dans la Vigie » de « clôturer sa mission pour le ranger », geste jusque-là introuvable dans la Vigie.
  Même appel que la Forge (`PUT …/mission`), la clôture reste un geste unique (cadrage F-106).
- **Purge après la clôture, jamais avant** : la gateway exige une mission close pour `MISSION_CLOSED` ;
  une clôture refusée ne purge rien.
- **La suppression d'un poste** purge déjà son Radar côté gateway (SF-99-05) : l'écran le dit et propose
  l'export, sans case (la purge n'y est pas optionnelle).
