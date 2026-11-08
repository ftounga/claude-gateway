# Cadrage F-252 — Jarvis répond vite

**Demande PO du 2026-11-08** : Jarvis est jugé lent — environ 5 s ressenties entre son dernier mot et la première
syllabe de Jarvis.

**Règle absolue** (PO, 2026-09-30) : la justesse passe avant la vitesse et le coût. Aucun correctif ne doit dégrader ce
que Jarvis comprend ou dit. Pas de changement de modèle (Sonnet reste, pas de Haiku). Toute modification de la consigne
du cerveau rapide passe les bancs Jarvis (conversation, pièces, oral) **2 fois au vert** avant merge.

## 1. Mesure (essai réel du 07/11, 16:13–16:20 UTC, `jarvis_turn_timings` + `usage_turns`)

`listen_end` (fin de parole détectée, t0) → premier son : **médiane 3,73 s**. Décomposition d'un tour type
(16:13:57, mode `CONV_STREAM`) :

| Étape | Durée | Source |
|---|---|---|
| Retranscription complète (batch Scribe v2) attendue avant tout appel | 700–1 060 ms (médiane 887, p90 1 357) | `transcript_ms` |
| Préparation serveur (≈ 12 lectures en base en série : budget, contexte, univers recalculé, état, courrier, portrait, allusions, mémoire) | ≈ 0,4–0,6 s, **jamais mesurée** | code `JarvisFastBrainService.decideRaw/decideCall/callModel` |
| Premier jeton Sonnet | 1,1–1,5 s | `fast_first_token_ms` |
| Relecture « compris: » + première phrase complète | ≈ 0,6 s | `first_oral_sentence_ms − sent_ms − fast_first_token_ms` |
| Voix (premier octet → son) | 0,4–0,65 s | `first_sound_ms − first_oral_sentence_ms` |

Cas pathologiques : 16:14:08, deux appels au modèle (outil `afficher` sans phrase, puis relance), « Voici. » à 6,1 s ;
16:16:54, 372 jetons produits pour 66 caractères dits.

## 2. Correctifs retenus

| # | Correctif | Gain attendu (médiane) | Risque justesse |
|---|---|---|---|
| A | **Ne plus attendre la retranscription** : le cerveau rapide part dès la fin de parole avec le texte du flux, **en silence** (rien n'est dit ni montré). La retranscription complète reste la référence : si elle rend le **même texte normalisé**, la réponse déjà en cours est libérée ; sinon elle est jetée (aucun effet : ni mémoire, ni fil, ni délégation) et l'appel repart avec la retranscription. | 0,6–0,9 s | nul : une réponse n'est dite que si elle a été calculée sur le texte de référence (normalisation : casse, espaces, ponctuation **sauf « ? »**) |
| B | **Préparation serveur parallèle et mesurée** : colonne `prep_ms` ; lectures indépendantes en parallèle (fils virtuels) ; univers mis en cache 30 s par utilisateur (clé `user_id`) ; la préparation démarre dès la fin de parole (elle fait partie de A). | 0,3–0,5 s | univers vieux de 30 s au plus (heures d'état inchangées dans le message) |
| D | **La phrase d'abord, l'ordre d'écran ensuite** (consigne) : toute réponse qui montre commence par sa phrase, dans le même message que l'outil ; plus de second appel pour « trouver une phrase ». | 2–3 s sur les tours concernés, ≈ 0 sur la médiane | consigne touchée ⇒ bancs ×2 |
| F | **Anticiper la fin de parole** : le seuil de 1,2 s reste (banc SF-239-10) ; dès ≈ 0,6 s de silence sur une phrase close, A+B partent par anticipation ; reprise de parole ⇒ annulés ; rien n'est dit avant la fin de tour confirmée. Le silence réel est tracé. | jusqu'à 0,6 s | nul : même règle de libération que A |
| E | TTFT Sonnet (1,1–1,5 s) : **non traité** — réduire la partie variable du message retire du contexte au modèle ; aucun gain sans risque identifié. Rien ne change de modèle. | — | — |

**Objectif** : `listen_end` → premier son ≈ **2,5–2,7 s** en médiane (contre 3,73 s).

## 3. Découpage (chaque subfeature ≤ 2 j)

| SF | Titre | Contenu | Bancs |
|---|---|---|---|
| SF-252-01 | Préparer vite, et le mesurer (B) | migration 252-01 (`prep_ms`, `speculation`, `end_silence_ms`, `anticipations_dropped`), `prepMs` dans `done`, lectures parallèles, cache de l'univers 30 s | non (aucune consigne ni modèle touché) |
| SF-252-02 | Répondre sans attendre la retranscription (A) | réponse provisoire côté passerelle (effets différés, verdict partagé entre pods par la base, table `jarvis_fast_verdicts`), écran : lancement au texte du flux, adoption ou relance, mesure `speculation` | non (même décision, même code) |
| SF-252-03 | La phrase d'abord (D) | consigne v20 (ordre parole → outil), test du nombre d'appels | **oui — CONVERSATION, PIÈCES, ORAL ×2** |
| SF-252-04 | Anticiper la fin de parole (F) | anticipation à 0,6 s de silence sur phrase close, annulation à la reprise, silence réel tracé ; respecte le délai de grâce de F-251 | non |

## 4. Décisions

- **D1** — La retranscription batch reste la référence : un texte de flux n'est jamais dit s'il diffère.
- **D2** — Une réponse provisoire n'a **aucun effet** tant qu'elle n'est pas adoptée : mémoire de session, trace au fil
  Jarvis, registre des délégations, routage, journal des allusions, relance du portrait ne s'écrivent qu'à l'adoption,
  avec le texte de référence. Son coût (appel modèle) est compté normalement.
- **D3** — Deux pods servent la passerelle sans affinité de session : le verdict d'adoption transite par la base
  (ligne courte, `user_id` du principal, consommée puis supprimée) ; sans verdict sous 8 s, la réponse provisoire est
  jetée.
- **D4** — Le cache de l'univers est par `user_id`, 30 s (borne basse demandée), réglable (`APP_JARVIS_FAST_BRAIN_UNIVERSE_TTL`).
- **D5** — Coordination : F-251 (écoute) ajoute un délai de grâce après la fin de tour : l'anticipation (F) ne libère
  jamais une réponse avant la fin de tour confirmée, délai de grâce compris. F-253 (affichage) déplace le moment où
  « Voici. » est dit : F-252 ne touche qu'à l'ordre parole → outil dans la consigne.

## 5. Mesure après déploiement

Requêtes de vérification dans chaque mini-spec et dans le bilan de la feature : médiane `first_sound_ms`, `prep_ms`,
taux `speculation = 'relancee'`, `end_silence_ms`, `anticipations_dropped`.
