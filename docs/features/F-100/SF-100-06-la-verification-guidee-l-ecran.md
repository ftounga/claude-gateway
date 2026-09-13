# Mini-spec — F-100 / SF-100-06 — La vérification guidée, l'écran

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §5 et §12 bis ; API livrée en SF-100-01
> (`docs/features/F-100/SF-100-01-la-verification-guidee.md`), dont l'écran était planifié avec l'activation
> F-106. Constat de clôture F-104 (`PRODUCT_SPEC.md`, ligne F-100).

## Identifiant

`F-100 / SF-100-06`

## Feature parente

`F-100` — Le Radar : la synchro du soir

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-100-06-verification-guidee`

---

## Objectif

À l'activation d'un client dans la Vigie, et à la demande depuis son en-tête, un dialogue guide
l'utilisateur (ouvrir un fil, une réunion passée, sa transcription dans la fenêtre Chrome de Teams),
**coche ce que le runner a vu** et donne **le remède exact** de chaque case restée vide.

---

## Comportement attendu

### Cas nominal

1. **Ouverture** — `RadarVerificationDialogComponent` s'ouvre :
   - juste après *Activer* un client de la Forge dans la Vigie (`AddClientDialog` → `activated`) ;
   - depuis l'en-tête du client dans la Vigie : bouton *Vérifier ce que voit le runner* (relançable).
2. **Les étapes** — le dialogue affiche quatre lignes, chacune avec son état (✓ vu, ○ en attente,
   ! remède) et la phrase rendue par la gateway :
   - *Session Microsoft* — la fenêtre Chrome de Teams est reliée ;
   - *1. Ouvrez un fil de conversation* dans la fenêtre Chrome de Teams → `conversations` ;
   - *2. Ouvrez une réunion passée* dans le calendrier → `meetings` ;
   - *3. Ouvrez l'onglet Transcription de cette réunion* → `transcripts`.
3. **La vérification tourne** — à l'ouverture, `POST /api/radar/hosts/{hostId}/verification` ; puis, tant
   que le dialogue est ouvert, que tout n'est pas coché et qu'aucun refus bloquant n'est tombé, un
   nouvel appel **5 s après la fin du précédent** (jamais deux en même temps). Une case cochée reste
   cochée (règle de la gateway). « En cours » est dit pendant l'appel.
4. **Tout est vu** (`complete`) — la vérification s'arrête, « Le runner voit tout ce que le Radar lira. »,
   bouton *Fermer*.
5. **Recommencer** — `DELETE …/verification` puis reprise des appels (cases décochées).
6. **Les remèdes** (fonctions pures, `radar-verification.ts`) :
   - `session` ✗ `BROWSER_NOT_DETECTED` → « La fenêtre Chrome de Teams n'est pas reliée au runner. Lancez-la
     avec la commande donnée par le runner : » + la phrase du runner (qui porte la commande exacte) en
     bloc copiable ;
   - `session` ✗ `NOT_SIGNED_IN` / `TEAMS_NOT_OPEN` → la phrase du runner (connexion, onglet Teams) ;
   - `session` ✗ `TEAMS_CHANGED` → « Teams a changé : mettez le runner à jour. » + commandes de
     téléchargement ;
   - `session` ✗ `TEAMS_DISABLED` → « Le volet Teams est désactivé sur ce poste : relancez le runner sans
     `--no-teams`. » ;
   - refus 409 `radar_teams_disabled` (outil inconnu : runner trop ancien ou volet désactivé) → « Mettez le
     runner à jour » + commandes de téléchargement, et la relance sans `--no-teams` ;
   - refus 409 `radar_runner_unavailable` dont le message dit « mettez le runner à jour » (réponse
     illisible) → « Mettez le runner à jour » + commandes ; sinon (hors ligne, délai) → le message, et
     *Réessayer* ;
   - `transcripts` ✗ `DISABLED_OR_NOT_PRODUCED` → « La transcription est désactivée par votre client, ou
     n'a pas été produite : le Radar le dira au lieu de présenter ces réunions comme vides. Rien à faire de
     votre côté ; l'organisateur peut l'activer. » ;
   - `transcripts` ✗ `ACCESS_DENIED` → « Votre rôle dans cette réunion ne donne pas accès à sa
     transcription : essayez une réunion que vous avez organisée, ou demandez l'accès à l'organisateur. ».
7. **Commandes de téléchargement** du runner, pour le système du navigateur (`RUNNER_HOST_PLATFORM`) et
   l'adresse de la gateway (`location.origin`) — routes publiques existantes :
   - Windows : `curl.exe -fL -o claude-runner-windows-x64.zip <origin>/api/runner/download/windows` ;
   - macOS : `curl -fL -o claude-runner-macos-aarch64.tar.gz <origin>/api/runner/download/macos-aarch64`
     (Apple Silicon) et la variante `macos-x64` ;
   - toujours : `curl -fL -o claude-runner.jar <origin>/api/runner/download` (`curl.exe` sous Windows) ;
   - « puis relancez le runner comme d'habitude ».

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Runner hors ligne, délai dépassé | le message de la gateway, les appels s'arrêtent, *Réessayer* | 409 `radar_runner_unavailable` |
| Réponse illisible (runner ancien) | « Mettez le runner à jour » + commandes ; arrêt ; *Réessayer* | 409 `radar_runner_unavailable` |
| Volet Teams absent / outil inconnu | « Mettez le runner à jour » + commandes, ou relance sans `--no-teams` ; arrêt | 409 `radar_teams_disabled` |
| Client hors Vigie / droit retiré | « La vérification n'est pas disponible pour ce client. » ; arrêt | 403 / 404 / 409 |
| Gateway injoignable | « La gateway n'a pas répondu. » ; arrêt ; *Réessayer* | 0 / 5xx |
| Session non reliée | 200 : case session avec remède ; les appels continuent (l'utilisateur lance Chrome) | 200 |

---

## Critères d'acceptation

- [ ] Activer un client de la Forge dans la Vigie ouvre la vérification guidée sur ce client ; le bouton de l'en-tête la rouvre.
- [ ] Le dialogue appelle `POST …/verification` à l'ouverture, puis 5 s après chaque réponse tant que rien ne bloque et que tout n'est pas vu ; jamais deux appels simultanés ; aucun appel après fermeture.
- [ ] Chaque case affiche ✓ / en attente / remède et la phrase de la gateway ; `complete` arrête les appels et le dit.
- [ ] Remèdes : liaison Chrome (phrase du runner copiable), runner trop ancien (commandes de téléchargement selon le système), volet désactivé (`--no-teams`), transcription désactivée par le client, accès refusé.
- [ ] *Recommencer* appelle `DELETE …/verification` puis reprend.
- [ ] Un refus 409 arrête les appels, dit le message ou le remède, propose *Réessayer*.
- [ ] DESIGN_SYSTEM : ✓ `--cg-success`, remède en ambre §12 (filet), commandes en `app-copy-block` (JetBrains Mono) ; aucune couleur nouvelle.

---

## Périmètre

### Hors scope (explicite)

- Toute modification de l'API ou du runner (`teams_radar_verify` inchangé).
- Ouvrir la vérification automatiquement après un **nouvel appairage** (le parcours d'appairage ne rend pas
  le poste créé) : le bouton de l'en-tête la propose.
- La planification (SF-100-07).
- Lancer Chrome ou naviguer à la place de l'utilisateur (le runner observe seulement).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| Intervalle entre deux appels | 5 s après la fin du précédent | `VERIFICATION_POLL_MS` |
| Cases | celles de la gateway au premier appel | cochées collantes (SF-100-01) |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `hostId` | Oui | — | UUID du client ouvert | — | — |
| `session.state` | — | 32 | `LINKED`, `BROWSER_NOT_DETECTED`, `TEAMS_NOT_OPEN`, `NOT_SIGNED_IN`, `TEAMS_CHANGED`, `TEAMS_DISABLED`, inconnu → phrase seule | — | — |
| `transcripts.state` | — | 32 | `SEEN`, `ACCESS_DENIED`, `DISABLED_OR_NOT_PRODUCED`, `NOT_SEEN` | — | — |

---

## Technique

### Endpoint(s) (consommés, inchangés)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/radar/hosts/{hostId}/verification` | JWT | droit Radar |
| DELETE | `/api/radar/hosts/{hostId}/verification` | JWT | droit Radar |

### Tables impactées

- Aucune (lecture / écriture par l'API existante de `radar_host_settings`).

### Migration Liquibase

- [x] Non.

### Composants Angular

| Composant | Rôle |
|-----------|------|
| `radar.models.ts` | `RadarVerification`, `RadarVerificationCheck` |
| `RadarService` | `verify`, `resetVerification` |
| `vigie/radar-verification/radar-verification.ts` | lignes, remèdes, commandes de mise à jour (pur) |
| `RadarVerificationDialogComponent` | étapes, appels successifs, remèdes |
| `VigieComponent` | ouverture après activation ; bouton de l'en-tête |
| `CopyBlockComponent` | commandes copiables (réemploi) |

---

## Plan de test

### Tests unitaires

- [ ] `radar-verification.spec.ts` — état de chaque case, remède par état de session et de transcription, refus 409 (runner ancien, volet désactivé, hors ligne), commandes Windows / macOS / autre.
- [ ] `radar-verification-dialog.component.spec.ts` — POST à l'ouverture ; nouvel appel après 5 s (`fakeAsync`) ; arrêt sur `complete`, sur 409, à la fermeture ; *Recommencer* (DELETE puis POST) ; *Réessayer*.
- [ ] `vigie.component.spec.ts` (étendu) — activation → dialogue sur le client ; bouton de l'en-tête.
- [ ] `radar.service.spec.ts` (étendu) — URLs et méthodes.

### Tests d'intégration

- [x] Backend inchangé : `RadarVerificationApiIntegrationTest` (SF-100-01 : nominal, fusion, 409, isolation) rejoué.

### Isolation utilisateur

- [x] Applicable côté gateway (inchangée, rejouée) : poste d'autrui → 404 ; l'écran n'envoie aucun identifiant de compte.

---

## Dépendances

### Subfeatures bloquantes

- SF-100-01 (API) — `done` ; F-106 (Vigie, activation) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : non.** **Plans / limites : non** (droit Radar inchangé). **Auth / Principal : non.**
- **Navigation / routing : non** (dialogue, aucune route).

---

## Notes et décisions

- **Appels enchaînés, pas un minuteur fixe** : un appel peut durer jusqu'à 20 s (le runner récolte) ; un
  intervalle fixe empilerait les appels. 5 s après la fin : même cadence que le suivi d'appairage
  (`RUNNER_STATUS_POLL_MS`).
- **La commande de lancement de Chrome vient du runner** (`BrowserLaunchAdvice`, dans la phrase de la case
  session) : l'écran ne la réécrit pas, il la rend copiable.
- **Les commandes de mise à jour** réemploient les routes publiques de téléchargement ; le système est celui
  du navigateur, les variantes restent toutes affichées sous macOS (architecture inconnue).
