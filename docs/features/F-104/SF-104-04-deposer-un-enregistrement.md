# Mini-spec — F-104 / SF-104-04 — Déposer un enregistrement

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §9 (« depuis l'écran : *Déposer un enregistrement* envoie le
> fichier **au runner** du poste (relais découpé, taille bornée — plafond à fixer en mini-spec), jamais stocké
> par la gateway ; la date et le titre de la réunion sont demandés au dépôt »), §12 bis SF-104-04 ; SF-100-05
> (le dossier de dépôt `<racine>/radar/depot/`, relevé et transcrit sur la machine). Cadrage validé par le PO.

## Identifiant

`F-104 / SF-104-04`

## Feature parente

`F-104` — Le Radar : le nourrir

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-104-04-deposer-enregistrement`

---

## Objectif

Depuis l'onglet Radar, **déposer un enregistrement hors Teams** (téléphone, salle, autre visio) : le fichier
part **par morceaux** vers le runner du poste, qui l'écrit dans `<racine>/radar/depot/` avec son **titre** et sa
**date** ; la gateway ne le stocke jamais ; la synchro suivante le transcrit sur la machine (SF-100-05).

---

## Comportement attendu

### Cas nominal

1. Le composeur du Radar (SF-104-02) gagne le bouton **Déposer un enregistrement** (icône micro). Il ouvre un
   dialogue : **fichier** (audio ou vidéo : `.mp3 .m4a .wav .ogg .aac .flac .mp4 .mov .mkv .webm`, 500 Mio au
   plus), **titre** (obligatoire, prérempli du nom du fichier), **date et heure** de la réunion (obligatoires,
   préremplies de la date de modification du fichier), et la phrase « Le fichier va sur la machine du client ;
   il y est transcrit à la prochaine synchro, seul le texte remonte. »
2. `POST /api/radar/hosts/{hostId}/recordings` `{ fileName, sizeBytes, title, recordedAt }` : droit Vigie, poste
   possédé et activé dans la Vigie ; validation ; runner **en ligne** ; appel runner `teams_radar_deposit`
   `op=open` avec un `upload_id` fabriqué par la gateway. Le runner vérifie extension, taille, place disque
   (taille + 64 Mio), purge les dépôts inachevés de plus de 24 h, crée `.upload-<id>.part` et
   `.upload-<id>.json` (caché : le relevé les ignore). Rend `201 { uploadId, chunkBytes, maxBytes }`.
3. `PUT /api/radar/hosts/{hostId}/recordings/{uploadId}/chunks?offset=N` (corps `application/octet-stream`,
   **512 Kio au plus**) : la gateway lit le corps **borné en mémoire**, l'encode et le relaie (`op=chunk`,
   `offset`, `data` en base64) ; **rien n'est écrit par la gateway**. Le runner exige `offset` = taille déjà
   reçue (un morceau déjà reçu, même offset et même longueur, est **accepté sans être réécrit** : reprise sûre)
   et refuse de dépasser la taille annoncée. Rend `{ received }`.
4. `POST …/recordings/{uploadId}/finish` : le runner vérifie que la taille reçue égale la taille annoncée, écrit
   le compagnon `<nom>.json` `{ title, date }` (forme de SF-100-05), puis renomme le fichier en `<nom>.<ext>`
   dans le dossier de dépôt (nom nettoyé ; « (2) », « (3) »… si le nom existe). Rend
   `{ fileName, title, recordedAt, sizeBytes }`.
5. `DELETE …/recordings/{uploadId}` : abandon, le runner supprime le fichier partiel et sa description. 204.
6. **L'écran** envoie les morceaux **l'un après l'autre**, affiche la progression (barre déterminée, pourcentage),
   **retente une fois** un morceau en échec réseau, permet **Annuler** (abandon envoyé), puis dit « Déposé sur le
   poste : « titre ». Il sera transcrit sur la machine à la prochaine synchro. »
7. Appels `open`, `finish`, `abort` **journalisés** dans l'audit runner (sans contenu) ; les morceaux, non (un
   dépôt de 500 Mio compterait mille lignes sans rien apprendre de plus).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Sans droit / poste d'autrui / hors Vigie | refus, rien n'est envoyé au runner | 403 / 404 / 409 |
| Nom, titre ou date manquant ; extension refusée ; taille nulle ou > 500 Mio | `radar_invalid` | 400 |
| Morceau vide ou > 512 Kio ; `offset` négatif | `radar_invalid` | 400 |
| Poste hors ligne / runner muet | `radar_runner_unavailable` | 409 |
| Volet Teams désactivé sur le poste (pas de dossier de dépôt) | `radar_teams_disabled` | 409 |
| Place disque insuffisante sur le poste | `radar_state_conflict` avec le motif | 409 |
| `offset` différent de la taille reçue, taille annoncée dépassée, fin avec taille incomplète | `radar_state_conflict` avec la taille reçue | 409 |
| Dépôt inconnu (jamais ouvert, abandonné, purgé) | `not_found` | 404 |
| Échec réseau d'un morceau à l'écran | une nouvelle tentative, puis message et abandon | — |

---

## Critères d'acceptation

- [ ] Nominal : ouvrir, trois morceaux, finir → le runner a écrit `<nom>.m4a` à l'identique et `<nom>.json`
      `{ title, date }` dans `radar/depot/` ; aucun fichier caché ne reste.
- [ ] La gateway ne stocke rien : aucun fichier, aucune table ; le morceau est relayé encodé en base64.
- [ ] Reprise : le même morceau renvoyé est accepté sans duplication ; un `offset` faux est refusé.
- [ ] Bornes : 500 Mio (gateway et runner), 512 Kio par morceau, place disque ; extension refusée.
- [ ] Nom nettoyé et collision gérée ; dépôts inachevés de plus de 24 h purgés à l'ouverture.
- [ ] Hors ligne → 409 sans appel ; 404 / 409 hors périmètre ; droit requis.
- [ ] Écran : dialogue (fichier, titre, date préremplis), progression, une nouvelle tentative, Annuler, fin dite.
- [ ] Le fichier déposé est relevé par le collecteur de SF-100-05 (compagnon lu).

---

## Périmètre

### Hors scope (explicite)

- La transcription (SF-100-05, à la synchro) ; lancer une synchro au dépôt (bouton *Synchroniser maintenant*
  existant).
- Un dépôt reprenable après fermeture de l'onglet (l'abandon est purgé à 24 h).
- Stocker le fichier dans la gateway ou un stockage objet : jamais.
- Joindre un document.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| taille maximale d'un enregistrement | 500 Mio | gateway et runner |
| taille d'un morceau | 512 Kio | trame runner ≤ 1 Mio une fois encodée |
| marge disque exigée | 64 Mio | au-delà de la taille annoncée |
| purge des dépôts inachevés | 24 h | à chaque ouverture |
| délais runner | ouverture 20 s, morceau 60 s, fin 30 s | — |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `fileName` | Oui | 255 | extension de la liste | — | nom seul, caractères interdits → `_`, 120 caractères |
| `sizeBytes` | Oui | — | 1 … 524 288 000 | — | — |
| `title` | Oui | 200 | texte | — | `trim` |
| `recordedAt` | Oui | — | ISO-8601 avec décalage | — | — |
| `uploadId` | Oui | — | UUID | — | — |
| `offset` | Oui | — | ≥ 0, égal à la taille reçue | — | — |
| corps d'un morceau | Oui | 524 288 octets | binaire | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/radar/hosts/{hostId}/recordings` | JWT | propriétaire du poste, droit Vigie, poste activé dans la Vigie |
| PUT | `/api/radar/hosts/{hostId}/recordings/{uploadId}/chunks?offset=` | JWT | idem |
| POST | `/api/radar/hosts/{hostId}/recordings/{uploadId}/finish` | JWT | idem |
| DELETE | `/api/radar/hosts/{hostId}/recordings/{uploadId}` | JWT | idem |

### Tables impactées

Aucune (`runner_audit` via le journal existant, sans contenu).

### Migration Liquibase

- [x] Non applicable

### Composants

- Backend : `RadarRecordingDepositService` (validation, relais, traduction des refus), `RadarRecordingController`,
  `RadarRunnerCalls.relay` (appel sans ligne d'audit, pour les morceaux).
- Runner : `RadarDepositReceiver` (ouvrir, morceau, finir, abandonner, purge), `RadarTools.DEPOSIT`,
  `TeamsTools` (routage hors du verrou de lecture : un dépôt n'attend pas une étape de synchro).
- Frontend : `RadarDepositDialogComponent`, `radar-deposit.ts` (validation, envoi par morceaux, pur),
  `RadarService.openDeposit/sendChunk/finishDeposit/abortDeposit`, `RadarNewsComponent` (bouton).

### Préoccupations transversales

- **Contexte tenant : oui.** `RadarScopeResolver.requireInVigie` ; la cible runner est **le poste du
  périmètre** (`RadarRunnerCalls`), jamais un identifiant de requête ; `RunnerLiveness.isAlive`.
- **Plans / limites : oui (droit seul).** `TeamsAccessService.requireAccess` ; aucun quota consommé (aucun appel
  au fournisseur ; la transcription est locale).
- Navigation : non (dialogue). Auth / Principal : non.

---

## Plan de test

### Tests unitaires

- [ ] Runner `RadarDepositReceiverTest` — nominal (fichier et compagnon identiques), reprise du même morceau,
      offset faux, dépassement, fin incomplète, extension, place disque, nom nettoyé et collision, abandon,
      purge à 24 h, dépôt inconnu, relevé par `RadarDepositCollector` du fichier déposé.
- [ ] Validation, borne du morceau, encodage base64 et traduction des refus runner (hors ligne, désactivé,
      conflit, inconnu) : couverts de bout en bout par `RadarRecordingApiIntegrationTest` (runner simulé).
- [ ] `radar-deposit.spec.ts` — validation, découpage, nouvelle tentative, abandon ; composant dialogue.

### Tests d'intégration

- [ ] `RadarRecordingApiIntegrationTest` (runner simulé) — ouvrir / morceau / finir / abandonner, 400, 404 autre
      poste et autre compte, 409 hors Vigie et hors ligne.

### Isolation workspace

- [x] Applicable — poste de Bob et poste CAGIP d'Alice non activé : aucun appel au runner.

---

## Dépendances

### Subfeatures bloquantes

- SF-100-05 (dossier de dépôt, PR #531) — `done` ; SF-104-02 (composeur, PR #545) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Gateway sans état** : l'identifiant de dépôt est fabriqué par la gateway, l'état (fichier partiel, taille
  reçue, description) vit **sur le runner** ; n'importe quel pod peut relayer n'importe quel morceau.
- **Plafond 500 Mio** : une heure de réunion enregistrée au téléphone pèse 30 à 60 Mio, une heure de vidéo
  720p 300 à 600 Mio ; au-delà, déposer directement dans le dossier du poste (SF-100-05) reste possible.
- **Morceaux de 512 Kio** : encodés en base64 (≈ 700 Kio), ils tiennent dans la trame runner de 1 Mio.
