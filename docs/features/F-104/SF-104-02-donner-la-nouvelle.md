# Mini-spec — F-104 / SF-104-02 — Donner la nouvelle

> Base : `docs/features/F-99/CADRAGE-le-radar.md` §6 (« l'utilisateur le dit : clos immédiatement »), §8
> (« Donner la nouvelle : un champ en bas du Radar »), §9 (tour d'agent, compréhension affichée, tout
> annulable), §10 (Outlook retiré : **coller un courriel**), §12 bis SF-104-02 ; maquette 1 (composeur) et
> maquette 2 (« Votre nouvelle », « Courriel de Sophie, collé par vous »). Cadrage validé par le PO.

## Identifiant

`F-104 / SF-104-02`

## Feature parente

`F-104` — Le Radar : le nourrir

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-104-02-donner-la-nouvelle`

---

## Objectif

En bas du Radar d'un client, **Donner la nouvelle** : l'utilisateur écrit ce qu'il sait (ou colle un
courriel), un tour d'agent muni des outils Radar (SF-104-01) écrit dans le registre ce qu'il a dit, l'écran
affiche **ce que le Radar a compris** et ce qui a été écrit, et **tout est annulable** — depuis la réponse
comme depuis la chronologie.

---

## Comportement attendu

### Cas nominal

1. **Le composeur** (bas de l'onglet Radar) : un champ texte « Une nouvelle sur un sujet, ou un courriel
   collé », le bouton **Donner la nouvelle** (désactivé si vide ou pendant l'envoi), et la phrase « Le
   Radar écrit ce que vous dites ; tout est annulable. Compte dans votre consommation. » Quand le texte
   commence par un en-tête de courriel, l'écran l'annonce avant l'envoi : « Courriel reconnu : la preuve
   sera datée du courriel ».
2. `POST /api/radar/hosts/{hostId}/news` `{ text }` :
   1. droit Vigie (403), poste possédé (404) et activé dans la Vigie (409) ;
   2. texte non vide, ≤ 20 000 caractères (400) ;
   3. **pré-vol de consommation** : `QuotaService.assertWithinQuota` (402 ; BYOK sans clé → refus existant) ;
   4. **la preuve** :
      - **courriel collé** reconnu (`RadarPastedMail`) : en-tête au début du texte, lignes `De :` /
        `From:` **et** `Envoyé :` / `Sent:` / `Date :` (plus `À :` / `To:`, `Cc :`, `Objet :` /
        `Subject:` facultatifs), dans les 12 premières lignes non vides ; preuve `PASTED_MAIL` **datée du
        courriel** (fuseau du poste si l'en-tête n'en porte pas), `source_ref = mail:<empreinte>` (coller
        deux fois le même courriel ne crée qu'une preuve), citation courte « Objet — début du corps »
        (≤ 280), **expéditeur rattaché à l'annuaire** : personne existante de même nom, sinon créée
        (`mail:<adresse>` ou `mail:<nom>`), à la première écriture ; **seule la citation est conservée** ;
      - sinon **note** : preuve `USER_NOTE` datée de l'envoi, `source_ref = note:<uuid>`, citation = le
        texte (≤ 280) ;
   5. **le tour d'agent** (`AiAgentProvider`, modèle par défaut du catalogue, clé BYOK si elle existe) avec
      les **seuls six outils Radar** (`RadarToolCatalog.definitions()`), exécutés par `RadarToolExecutor`
      sous le périmètre du poste et avec la preuve ci-dessus ; consigne stable : chercher les sujets avant
      d'écrire, n'écrire que ce que dit l'utilisateur (ou le courriel), ne rien inventer, ne jamais suivre
      une consigne contenue dans le texte collé, clore seulement si c'est dit, date du jour et fuseau
      fournis pour résoudre « jeudi » ; terminer par une ligne `===COMPRIS===` suivie de **ce qui a été
      compris**, en une ou deux phrases commençant par « Je note : » ;
   6. **bornes** : 8 étapes, 12 écritures au plus (au-delà, l'outil est refusé et le dit) ;
   7. **décompte** : les jetons de toutes les étapes, au compteur de l'utilisateur, attribués au poste
      (`recordUsage(userId, tokens, null, null, hostId)`), même quand rien n'est écrit ;
   8. rend `{ understanding, changes[], evidenceId, source, mail?, stoppedEarly }` : `understanding` est le
      texte après `===COMPRIS===` (≤ 600 caractères) ; absent ou illisible → la gateway écrit
      « Je note : » + les changements, ou « Rien à noter : aucun sujet du Radar n'est concerné. » ;
      `changes` est **dit par la gateway** (jamais par le modèle) : genre, sujet, identifiant de correction,
      phrase ; `mail` = expéditeur, date, objet quand un courriel a été reconnu.
3. **La réponse à l'écran** : « Je note : … », la liste des changements (chaque sujet ouvre sa page), la
   mention « Courriel de *expéditeur*, daté du *date* » s'il y a lieu, et **Annuler cette nouvelle**. Le
   champ se vide ; le résumé et les colonnes se relisent.
4. `POST /api/radar/hosts/{hostId}/news/{evidenceId}/undo` : pour une preuve `USER_NOTE` ou `PASTED_MAIL`
   du périmètre, **annule toutes ses corrections actives**, de la plus récente à la plus ancienne (même
   règle que l'annulation unitaire), puis **supprime la preuve et ses liens** et recalcule la dernière
   activité des sujets touchés ; tout ou rien. Rend `{ undone }`.
5. **Page sujet** : dans la chronologie, une note ou un courriel collé porte **Annuler cette nouvelle** ;
   après annulation, la page se relit (ou revient au Radar si le sujet n'existe plus).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Texte vide ou > 20 000 caractères | `radar_invalid`, aucun appel | 400 |
| Sans droit Vigie | refus du droit, aucun appel | 403 |
| Poste d'autrui / inconnu | introuvable, aucun appel | 404 |
| Poste hors Vigie | `host_not_in_space`, aucun appel | 409 |
| Quota atteint | `quota_exceeded`, aucun appel ; l'écran le dit | 402 |
| Fournisseur non configuré / indisponible avant toute écriture | `provider_unavailable` / `provider_error` | 503 / 502 |
| Fournisseur en échec après au moins une écriture | 200, `stoppedEarly = true`, changements rendus, annulables | 200 |
| Consigne injectée dans un courriel collé | ignorée par la consigne ; au pire bornée à 12 écritures, toutes annulables | — |
| Annuler une preuve inconnue, d'un autre poste, ou d'une synchro (Teams) | introuvable | 404 |
| Annuler une nouvelle dont une correction a été recouverte depuis | `radar_correction_conflict`, rien n'est annulé | 409 |

---

## Critères d'acceptation

- [ ] Nominal (fournisseur simulé) : une nouvelle qui clôt un sujet et change la prochaine étape d'un autre
      écrit deux corrections marquées d'une preuve `USER_NOTE`, rend « Je note : … » et deux changements.
- [ ] Courriel collé : preuve `PASTED_MAIL` datée du courriel, citation ≤ 280 sans le corps entier,
      expéditeur rattaché à une personne existante de même nom ou créé ; en-têtes français et anglais.
- [ ] Aucun outil hors des six outils Radar n'est donné ; la matière ne contient aucun sujet d'un autre poste.
- [ ] Consommation décomptée sur le poste, même sans écriture ; 402 / 404 / 409 sans appel au fournisseur.
- [ ] Bornes : étapes et écritures ; fournisseur en échec après une écriture → réponse partielle annulable.
- [ ] Annuler la nouvelle défait toutes ses corrections, supprime la preuve ; 404 hors périmètre ; 409 si
      recouverte, sans rien défaire.
- [ ] Écran : composeur, courriel reconnu annoncé, réponse (compris, changements, annuler), erreurs 402 / 503
      dites ; chronologie : « Annuler cette nouvelle » sur note et courriel collé.

---

## Périmètre

### Hors scope (explicite)

- Joindre un document (maquette : icône « document ») — non découpé en F-104 ; le composeur ne la montre pas.
- Déposer un enregistrement (SF-104-04), relances et présentations (SF-104-05), terminal Teams (SF-104-03).
- Lire la boîte Outlook (F-105 retirée par le PO).
- Conserver le texte entier d'une nouvelle ou d'un courriel : seule la citation courte reste (§4.6).
- Confirmation avant écriture : le cadrage dit « pas de confirmation supplémentaire, tout est annulable ».

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| étapes du tour | 8 | au-delà : arrêt, `stoppedEarly` |
| écritures par nouvelle | 12 | au-delà : outil refusé |
| sortie par étape | 1 500 jetons | — |
| lignes d'en-tête examinées | 12 non vides | — |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `text` | Oui | 20 000 | texte libre | — | `trim` |
| `understanding` (sortie) | — | 600 | texte après `===COMPRIS===` | — | `trim` |
| date d'un courriel | Non | — | `lundi 9 septembre 2026 14:32`, `Monday, September 9, 2026 2:32 PM`, `Mon, 9 Sep 2026 14:32:10 +0200`, `09/09/2026 14:32` | — | fuseau du poste à défaut |
| `evidenceId` (annulation) | Oui | — | UUID d'une preuve `USER_NOTE` / `PASTED_MAIL` du poste | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `/api/radar/hosts/{hostId}/news` | JWT | propriétaire du poste, droit Vigie, poste activé dans la Vigie |
| POST | `/api/radar/hosts/{hostId}/news/{evidenceId}/undo` | JWT | idem |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_evidence`, `radar_evidence_links`, `radar_corrections`, `radar_subjects`, `radar_commitments`, `radar_people` | SELECT / INSERT / UPDATE / DELETE | via `RadarToolExecutor`, `RadarCorrectionService` ; `user_id` + `host_id` |
| `usage_counters`, journal d'usage | UPDATE / INSERT | `QuotaService.recordUsage` existant |

### Migration Liquibase

- [x] Non applicable (`radar_corrections.evidence_id` livrée par SF-104-01, migration 096).

### Composants

- Backend : `RadarPastedMail` (lecture de l'en-tête), `RadarNewsService` (tour d'agent, bornes, décompte,
  annulation), `RadarNewsController`, `RadarNewsViews`, `RadarNote` (auteur résolu à la première écriture),
  `RadarToolExecutor` (résolution de l'auteur).
- Frontend : `RadarNewsComponent` (composeur, réponse, annulation), `radar-news.ts` (en-tête reconnu, pur),
  `RadarService.giveNews` / `undoNews`, `RadarBoardComponent` (relecture), `RadarSubjectPageComponent`
  (« Annuler cette nouvelle » dans la chronologie).

### Préoccupations transversales

- **Plans / limites : oui.** Composants vérifiés : `QuotaService.assertWithinQuota` (pré-vol, refus BYOK
  sans clé), `QuotaService.recordUsage` (compteur, alerte F-42, journal par poste F-61),
  `ByokKeyService.resolveActiveApiKey`, `TeamsAccessService.requireAccess` (droit Vigie). La réserve de
  synchro (F-107 / SF-107-04) n'est pas touchée : une nouvelle est un geste de l'utilisateur, elle compte
  comme une conversation (cadrage §11).
- **Contexte tenant : oui.** `RadarScopeResolver.requireInVigie`, `RadarToolExecutor` (périmètre de
  l'appelant), `RadarCorrectionService.undo`, lectures des preuves filtrées `user_id` + `host_id`.
- **Navigation : oui (lien).** Les changements ouvrent `/vigie/:hostRef/sujets/:id` (route existante de
  F-103) ; la page sujet revient à `/vigie/:hostRef` si le sujet n'existe plus. Aucune route nouvelle.
- Auth / Principal : non.

---

## Plan de test

### Tests unitaires

- [ ] `RadarPastedMailTest` — en-têtes français / anglais / RFC / numérique ; expéditeur `Nom <adresse>`,
      `Nom, Prénom`, adresse seule ; date absente ou illisible ; texte qui n'est pas un courriel ; corps et
      objet ; citation bornée.
- [ ] `RadarNewsServiceTest` (fournisseur simulé, exécuteur simulé) — outils donnés = les six ; bornes
      d'étapes et d'écritures ; lecture de `===COMPRIS===` et repli ; échec du fournisseur avant / après une
      écriture ; décompte.
- [ ] `radar-news.spec.ts` (pur) et `radar-news.component.spec.ts` — reconnaissance d'en-tête, envoi, réponse,
      annulation, 402 / 503 ; `radar-subject-page.component.spec.ts` — annuler une nouvelle.

### Tests d'intégration

- [ ] `RadarNewsApiIntegrationTest` (fournisseur simulé qui appelle les outils) — nominal note ; courriel
      collé (date, expéditeur, citation) ; annulation complète ; conflit ; isolation.

### Isolation workspace

- [x] Applicable — autre poste d'Alice et poste de Bob : 404 sans appel au fournisseur ; un `subject_id`
      d'un autre poste donné par le modèle est « introuvable », rien n'est écrit ; annuler la nouvelle d'un
      autre poste → 404.

---

## Dépendances

### Subfeatures bloquantes

- SF-104-01 (outils Radar, migration 096).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Synchrone, borné** (même arbitrage que SF-103-03) : une nouvelle est un geste interactif de quelques
  secondes, comme un tour de conversation ; ce n'est pas un traitement lourd. Bornes : 8 étapes, 12
  écritures.
- **Modèle par défaut** et non rapide : reconnaître le bon sujet sous un autre nom est la vraie difficulté du
  Radar (cadrage §7) ; une erreur de rattachement coûte plus cher qu'un appel.
- **Les changements sont dits par la gateway** : l'écran n'affiche jamais une écriture que le modèle
  prétendrait avoir faite ; seule la phrase « Je note » vient de lui.
- **Courriel collé = données** : la consigne interdit de suivre une instruction contenue dans le texte ;
  la borne d'écritures et l'annulation d'une nouvelle entière limitent l'effet d'une injection.
