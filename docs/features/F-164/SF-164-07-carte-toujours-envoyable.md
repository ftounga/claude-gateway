# Mini-spec — [F-164 / SF-164-07] Carte de question toujours envoyable + délai propre aux questions

---

## Identifiant

`F-164 / SF-164-07`

## Feature parente

`F-164` — Questions structurées à l'utilisateur (parité Claude Code `AskUserQuestion`)

## Statut

`in-review`

## Date de création

2026-10-10

## Branche Git

`feat/SF-164-07-carte-toujours-envoyable`

---

## Objectif

Qu'une carte de question à plusieurs questions ne puisse plus expirer « faute de bouton » : l'option recommandée est cochée d'avance, le bouton d'envoi reste visible avec le décompte de ce qui manque, et le délai d'une question passe à 10 minutes (les autorisations gardent 2 minutes).

---

## Constat (mesuré en prod le 2026-10-07)

- 55 questions depuis le 29/09, **12 expirées**, presque toutes sur des cartes à **plusieurs** questions ; aucune tentative d'envoi refusée côté serveur (le clic n'est jamais parti).
- Cause : `canSend` exige une réponse à **chaque** question ; le bouton, grisé, est **en bas** de la carte, souvent hors de l'écran. L'utilisateur coche la 1re question, rien ne s'active, les **2 minutes** (délai partagé avec les autorisations) passent.

---

## Comportement attendu

### Cas nominal

1. À l'arrivée d'une **nouvelle** question (nouveau `callId`), chaque question pré-coche son ou ses options `recommended` (radio : la première recommandée ; cases : toutes les recommandées). La reprise de la même question (`question_state`) conserve les choix en cours (inchangé).
2. Si toutes les questions ont une option recommandée, le bouton « Répondre et continuer » est **actif d'emblée** ; l'utilisateur valide ou change.
3. La barre d'action (compte à rebours + bouton) est **collante en bas** du fil (`position: sticky`) tant que la carte est à l'écran : jamais cachée sous la ligne de flottaison.
4. Tant qu'il manque des réponses, la barre affiche « N question(s) sur M sans réponse » et un lien « Aller à la première » qui fait défiler jusqu'à la première question sans réponse et y place le focus.
5. Backend : la porte tient un **délai propre aux questions**, `app.runner.question.timeout-ms` (défaut **600 000 ms**, env `APP_RUNNER_QUESTION_TIMEOUT_MS`). `awaitAnswer` l'utilise ; l'événement `question_request` annonce ce délai (le compte à rebours de la carte suit). Les autorisations gardent `app.runner.confirmation.timeout-ms` (2 min).

### Cas d'erreur / variantes

| Situation | Comportement attendu |
|-----------|---------------------|
| Question sans option recommandée | Rien de pré-coché pour elle ; elle compte dans « sans réponse » ; bouton grisé jusqu'à réponse |
| Délai question ≤ 0 ou absent | Repli sur 600 000 ms |
| Expiration à 10 min | Inchangé (SF-164-06 : défaut recommandé, signalé) |
| Interruption du tour | Inchangé (libère la question) |
| Lecture seule / carte verrouillée | Inchangé : ni pré-cochage visible, ni barre d'action |

---

## Critères d'acceptation

- [ ] CA1 — Nouvelle question : les options `recommended` sont cochées (radio et cases) ; `canSend` vrai si chaque question a une recommandée.
- [ ] CA2 — Même `callId` rejoué : les choix de l'utilisateur ne sont pas réinitialisés.
- [ ] CA3 — Question sans recommandée : non pré-cochée ; « 1 question sur 2 sans réponse » affiché ; bouton grisé.
- [ ] CA4 — « Aller à la première » fait défiler jusqu'à la première question sans réponse et y met le focus.
- [ ] CA5 — La barre d'action est collante (sticky bottom) et reste dans la charte (`--cg-*` uniquement) ; pleine largeur sous 819 px.
- [ ] CA6 — Backend : `awaitAnswer` attend `questionTimeoutMs` (défaut 600 000), `question_request.timeoutMs` = ce délai ; `await` (autorisation) garde `timeoutMs`.
- [ ] CA7 — Le payload envoyé est inchangé (même contrat `POST /chat/answer`).

---

## Périmètre

### Hors scope (explicite)

- Envoi partiel (questions vides → défaut) : écarté par le PO au profit du pré-cochage.
- Délai prolongé à chaque geste (aller-retour serveur) : écarté.
- Délai des autorisations : inchangé (2 min).
- Rendu mosaïque / lecture seule.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `app.runner.question.timeout-ms` | 600000 | ≤ 0 → repli 600000 |
| choix d'une question | option(s) `recommended` | à la réception d'un nouveau `callId` seulement |

---

## Contraintes de validation

Sans objet (aucune entrée nouvelle ; contrat de réponse inchangé).

---

## Technique

### Endpoint(s)

Aucun nouveau. `question_request.timeoutMs` change de valeur (10 min), pas de forme.

### Tables impactées

Aucune. Pas de migration.

### Composants backend

- `RunnerConfirmationGate` — 2e paramètre de constructeur `questionTimeoutMs` (constructeur unique, pas de 2e constructeur) + `questionTimeoutMs()` ; `awaitAnswer` l'utilise.
- `AtelierChatService.askQuestion` — annonce `questionTimeoutMs()`.
- `application.yml` — clé `app.runner.question.timeout-ms`.

### Composants Angular

- `AtelierTerminalDemandeComponent` (ts/html/scss) — pré-cochage, décompte, lien « Aller à la première », barre collante.

### Préoccupations transversales

Aucune cochée (ni auth, ni tenant, ni plans/limites, ni routing).

---

## Plan de test

### Tests unitaires

- `RunnerConfirmationGateTest` : délai question distinct du délai autorisation ; repli ≤ 0.
- `AtelierChatServiceQuestionToolTest` : `question_request.timeoutMs` = délai question.
- `atelier-terminal-demande.component.spec` : pré-cochage radio/cases ; `canSend` d'emblée ; pas de réarmement au même `callId` ; décompte « N sur M » ; « Aller à la première » appelle `scrollIntoView` + focus ; sans recommandée → grisé.

### Tests d'intégration

Suite backend complète (le contexte Spring démarre avec la nouvelle clé) + `npm test`.

### Isolation workspace

Inchangée : `awaitAnswer`/`answerQuestions` vérifient toujours propriétaire + workspace.

---

## Dépendances

### Subfeatures bloquantes

- SF-164-02 (carte), SF-164-06 (défaut au timeout) — done.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- Décisions PO du 2026-10-10 : « Pré-cocher + bouton visible » et « 10 minutes ».
- Une attente de 10 min dépasse la durée de vie courte du cache de prompt : la reprise peut réécrire le cache. Coût accepté (justesse et réponse de l'utilisateur d'abord) ; la plupart des réponses arrivent bien avant.
