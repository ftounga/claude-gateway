# Mini-spec — [F-252 / SF-252-04] Anticiper la fin de parole

## Identifiant

`F-252 / SF-252-04`

## Feature parente

`F-252` — Jarvis répond vite (cadrage §2, correctif F)

## Statut

`done`

## Date de création

2026-11-08

## Branche Git

`feat/SF-252-04-anticiper-fin-de-parole`

---

## Objectif

Dès ≈ 0,6 s de silence sur une phrase close, lancer la réponse provisoire (SF-252-02) sans attendre la fin de tour ;
l'abandonner si la parole reprend ; ne jamais rien dire avant la fin de tour confirmée (seuil 1,2 s et délai de grâce
F-251 inchangés).

---

## Comportement attendu

### Cas nominal

1. Pendant l'écoute en flux, l'écran suit le silence (même détecteur de voix que le filet actuel). Silence ≥
   `ANTICIPATE_SILENCE_MS` (600 ms) **et** texte de flux non vide, phrase close (`isSuspended` faux), parole destinée au
   cerveau rapide (mêmes conditions que SF-252-02) : une réponse provisoire part avec le texte du moment.
2. La parole reprend (nouveau texte provisoire différent, voix) : la réponse provisoire est jetée,
   `anticipations_dropped + 1`, et une nouvelle anticipation reste possible sur le silence suivant.
3. Fin de tour confirmée (fournisseur + délai de grâce F-251, sans changement) : si le texte du flux final est le même
   (`sameUtterance`), l'anticipation devient la réponse provisoire du tour (`speculation = anticipee` si adoptée) ;
   sinon jetée et remplacée par une réponse provisoire sur le texte final (comportement SF-252-02).
4. Le silence réel (dernier instant de voix → fin de tour) part dans `end_silence_ms`.
5. Rien n'est dit ni montré avant la fin de tour **et** le verdict de la retranscription : mêmes règles de libération
   que SF-252-02 (D1, D5).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Phrase en suspens | Pas d'anticipation | — |
| Passerelle refuse (coupe-circuit, 409) | Tout se passe comme sans anticipation | — |
| Fin d'écoute sans parole (`perdue`) | Jetée | — |

---

## Critères d'acceptation

- [x] CA1 — Aucune anticipation avant 600 ms de silence, ni sur une phrase en suspens, ni si la parole n'est pas
      destinée au cerveau rapide.
- [x] CA2 — Parole qui reprend : anticipation jetée, compteur incrémenté, nouvelle anticipation possible.
- [x] CA3 — Le seuil de fin de tour (1,2 s) et le délai de grâce F-251 sont inchangés ; rien n'est dit avant la fin de
      tour confirmée.
- [x] CA4 — `speculation = anticipee` si l'anticipation est adoptée ; `end_silence_ms` et `anticipations_dropped`
      renseignés.
- [x] CA5 — Aucun changement de consigne ni de modèle.

---

## Périmètre

### Hors scope (explicite)

- Baisser le seuil de 1,2 s ; le délai de grâce ; la dictée sans flux.

---

## Valeurs initiales

Aucune (colonnes de SF-252-01).

---

## Contraintes de validation

Aucun champ nouveau (`anticipationsDropped` 0–1 000, `endSilenceMs` 0–600 000 : SF-252-01).

---

## Technique

### Endpoint(s) : aucun. ### Tables : aucune. ### Migration : non.

### Composants touchés

- Frontend : `jarvis-speculation.ts` (`AnticipationWatch`, pur), `jarvis.component.ts` (boucle du micro, fin d'écoute).

### Préoccupations transversales

Aucune cochée. Navigation : non. Coordination F-251 : le point de décision reste `finishListening`.

---

## Plan de test

### Tests unitaires

- [x] `jarvis-speculation.spec.ts` — `AnticipationWatch` : seuil, phrase en suspens, reprise, nouvelle tentative (CA1, CA2).
- [x] Mesure `speed` (`jarvis.component.spec.ts`) (CA4).

### Tests d'intégration

- [ ] Non applicable (aucun endpoint).

### Isolation utilisateur

- [ ] Non applicable (verdict : SF-252-02).

---

## Dépendances

- SF-252-02. Coordination F-251 (délai de grâce).

## Notes et décisions

- Décision de dev : l'abandon se fait sur le texte du flux (partiel différent de la phrase anticipée), plus fiable que le niveau du micro seul ; le niveau réarme l'anticipation.

- Mesure post-déploiement : `select percentile_cont(0.5) within group (order by end_silence_ms), sum(anticipations_dropped), count(*) filter (where speculation = 'anticipee') from jarvis_turn_timings where created_at > now() - interval '7 days';`
