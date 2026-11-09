# Mini-spec — [F-252 / SF-252-02] Répondre sans attendre la retranscription

## Identifiant

`F-252 / SF-252-02`

## Feature parente

`F-252` — Jarvis répond vite (cadrage `docs/features/F-252/CADRAGE-F-252-jarvis-repond-vite.md`, correctif A)

## Statut

`done`

## Date de création

2026-11-08

## Branche Git

`feat/SF-252-02-repondre-sans-attendre`

---

## Objectif

Lancer le cerveau rapide dès la fin de parole avec le texte du flux, **en silence** ; ne dire sa réponse que si la
retranscription complète rend le même texte, sinon la jeter sans aucun effet et relancer avec la retranscription.

---

## Comportement attendu

### Cas nominal

1. À la fin d'écoute en flux, dès que le texte du flux est connu et qu'il peut partir au cerveau rapide (cerveau allumé,
   parole réelle, pas d'entretien, pas de confirmation attendue, pas de correction vocale ni de changement de sujet),
   l'écran appelle `POST /api/jarvis/converse` avec un `speculationId` (UUID). **Rien n'est dit ni montré** : tous les
   événements reçus sont gardés dans l'ordre.
2. La passerelle décide comme d'habitude (mêmes lectures, même modèle, mêmes gardes) et relaie les fragments, mais
   **aucun effet n'a lieu** : ni routage (registre, ligne-lien, fil Jarvis), ni mémoire de session, ni trace au fil, ni
   relance du portrait, ni journal des allusions (D2). Le coût de l'appel est compté normalement.
3. Quand la retranscription répond, l'écran compare (`sameUtterance` : casse, espaces, ponctuation ignorés, **sauf
   « ? »**, chiffres et accents gardés) :
   - **même texte** : verdict `adopted=true` + le texte de référence (`POST /api/jarvis/converse/verdict`) ; les
     événements gardés sont rejoués dans l'ordre puis relayés au fil de l'eau (`speculation = adoptee`) ;
   - **texte différent**, ou écran / sujet / parole coupée différents au moment d'envoyer : verdict `adopted=false`,
     l'appel est interrompu, un appel normal part avec la retranscription (`speculation = relancee`), comme aujourd'hui.
4. Retranscription absente (éteinte, échec, plus de 2,5 s) : le texte du flux reste la référence, comme aujourd'hui ;
   la réponse est adoptée.
5. Le verdict transite par la base (D3 : deux pods, sans affinité) : la passerelle attend au plus **8 s** la ligne du
   `user_id` du principal, la lit, la supprime, puis applique les effets avec **le texte de référence**.
6. Parole qui ne part pas au cerveau (rejet, entretien, commande locale, fin d'écran) : la réponse provisoire est jetée
   (verdict `adopted=false`, `speculation = perdue` si la mesure part encore).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Verdict jamais reçu sous 8 s | Réponse jetée, aucun effet ; événement `dropped` à l'écran, qui relance si rien n'a été dit | 200 (SSE) |
| Verdict d'un autre compte (même `speculationId`) | Sans effet : la lecture filtre sur le `user_id` du principal | 204 |
| Verdict en double | Le premier fait foi | 204 |
| `speculationId` absent du verdict, `text` vide alors que `adopted=true`, ou texte > 4 000 caractères | Refusé | 400 |
| Échec de l'appel provisoire (réseau, 5xx) | L'appel normal part avec la retranscription | — |
| Coupe-circuit `APP_JARVIS_FAST_BRAIN_SPECULATION=false` (ou réponse sans `speculationId` traité) | Comportement d'avant : la retranscription d'abord | — |

---

## Critères d'acceptation

- [x] CA1 — Sans verdict ou avec verdict `adopted=false`, aucun effet : `exchanges.write`, `memory.remember`,
      `delegations.started/offer`, `links.open`, `portraits.markCheckin`, `allusions.record` ne sont pas appelés.
- [x] CA2 — Avec `adopted=true`, tous ces effets ont lieu, une fois, avec le texte de référence, et le résultat est
      identique à un appel normal (message au modèle, phrases, `done`).
- [x] CA3 — Un verdict d'un autre compte n'adopte jamais la réponse (isolation `user_id`).
- [x] CA4 — Ligne de verdict consommée puis supprimée ; lignes orphelines purgées (> 1 h).
- [x] CA5 — `sameUtterance` : casse/espaces/ponctuation ignorés ; « ? » significatif ; accents et chiffres significatifs.
- [x] CA6 — Écran : aucun événement n'atteint les gestionnaires avant adoption ; à l'adoption, rejeu dans l'ordre puis
      flux direct ; à l'abandon, appel interrompu et rien de joué.
- [x] CA7 — Écran : texte identique ⇒ pas de second appel ; texte différent ⇒ un appel normal avec la retranscription,
      la voix ne dit jamais le texte provisoire.
- [x] CA8 — `jarvis_turn_timings.speculation` reçoit `adoptee`, `relancee` ou `perdue`.
- [x] CA9 — Aucun changement de consigne ni de modèle (`JarvisFastBrainPrompt.VERSION` inchangé).

---

## Périmètre

### Hors scope (explicite)

- L'anticipation avant la fin de tour (SF-252-04) ; toute modification de la consigne (SF-252-03).
- La retranscription elle-même (fournisseur, vocabulaire, délai 2,5 s).
- La dictée (repli sans flux) : elle garde la retranscription d'abord.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `speculation` | `NULL` | rempli par les écrans à partir de SF-252-02 |

---

## Contraintes de validation

| Champ | Obligatoire | Bornes | Valeurs autorisées | Normalisation |
|-------|-------------|--------|--------------------|---------------|
| `speculationId` | Oui (verdict) | UUID | — | — |
| `adopted` | Oui | booléen | — | — |
| `text` | Si `adopted=true` | 1 – 4 000 | — | `strip` |
| `app.jarvis.fast-brain.speculation` | Non | — | booléen | défaut `true` |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---------|-----|------|------------|
| POST | `/api/jarvis/converse` | Oui | `speculationId` facultatif ; événement `dropped` |
| POST | `/api/jarvis/converse/verdict` | Oui (compte courant) | nouveau, 204 |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `jarvis_fast_verdicts` | CREATE, INSERT, SELECT, DELETE | ligne courte, unique `(user_id, speculation_id)`, purge > 1 h |

### Migration Liquibase

- [x] Oui — `252-02-jarvis-fast-verdicts.xml` (rollback : table supprimée)

### Composants touchés

- Backend : `JarvisFastBrainService` (effets retenus, `converseProvisional`), `JarvisFastVerdictService` / entité /
  dépôt / purge, `JarvisFastBrainController` (`speculationId`, `verdict`, `dropped`).
- Frontend : `jarvis-speculation.ts` (nouveau : tampon, adoption, `sameUtterance`), `jarvis-fast-brain.ts` (abandon,
  `dropped`, verdict), `jarvis.component.ts` (lancement et réclamation).

### Préoccupations transversales

- Auth / Principal : non touché. Contexte tenant : verdict lu et écrit sous le `user_id` du principal (composant :
  `JarvisFastVerdictService`, `POST /jarvis/converse/verdict`). Plans / limites : budget et plafond inchangés ; une
  réponse jetée est comptée (composants : `JarvisUsageService`, `budgetState`, `JarvisBudgetService.hardStop`).
  Navigation : non touchée.

---

## Plan de test

### Tests unitaires

- [x] `JarvisFastBrainProvisionalTest` — CA1, CA2 (effets, avec et sans adoption), texte de référence dans la mémoire.
- [x] `JarvisFastVerdictServiceTest` — lecture/suppression, attente bornée, isolation (CA3, CA4).
- [x] Frontend `jarvis-speculation.spec.ts` — CA5, CA6.

### Tests d'intégration

- [x] `JarvisFastBrainApiIntegrationTest` (étendu) — verdict : 204, 400, double envoi, autre compte.

### Isolation utilisateur

- [x] Applicable — verdict d'un autre compte ignoré (CA3).

---

## Dépendances

- SF-252-01 (colonne `speculation`). Questions ouvertes : aucune.

## Notes et décisions

- Le coupe-circuit (`APP_JARVIS_FAST_BRAIN_SPECULATION=false`) répond 409 à l'appel provisoire ; l'écran part alors sur
  l'appel normal avec la retranscription.
- Aucun banc Jarvis : ni la consigne ni le modèle ne changent (V8).
- Mesure post-déploiement :
  `select speculation, count(*), percentile_cont(0.5) within group (order by first_sound_ms) from jarvis_turn_timings where created_at > now() - interval '7 days' and speculation is not null group by 1;`
