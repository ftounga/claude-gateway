# Mini-spec — F-262 / SF-262-02 Les demandes générales ont une réponse

## Identifiant

`F-262 / SF-262-02`

## Feature parente

`F-262` — Jarvis répond même poste éteint (cadrage `CADRAGE-F-262-jarvis-repond-poste-eteint.md`)

## Statut

`in-progress`

## Date de création

2026-11-15

## Branche Git

`feat/SF-262-02-demandes-generales-repondues`

---

## Objectif

Une demande « générale » (sujet `jarvis`, sans poste) reçoit toujours une réponse dite, et une question d'état sur un sujet nommé se répond depuis le contexte au lieu d'être déléguée.

---

## Comportement attendu

### Cas nominal

1. Le cerveau rapide délègue au fil Jarvis (sujet `jarvis` : tour d'horizon, question générale, courrier, agenda). Le fil Jarvis est en cible `SANDBOX`, chemin fermé depuis F-39 : aujourd'hui la délégation part et meurt en silence.
2. Désormais, dans `settle`, une telle délégation n'est **pas** routée : Jarvis dit son annonce (« Je fais le tour de vos clients, Monsieur. »), puis Opus répond **côté passerelle** par le même chemin lecture seule que le poste éteint (SF-262-01 : pièces, agenda, état, conversations), sous le compte de l'appelant, au budget Jarvis. L'échange est tracé au fil Jarvis comme toute réponse (question enregistrée).
3. La consigne lecture seule (`JarvisOfflinePrompt`, figée) couvre les deux situations ; **la situation** (« poste éteint » ou « demande générale, aucun poste ») est dite **dans le message**, jamais dans la consigne système.
4. Consigne du cerveau rapide (v21) : une question d'état qui nomme aussi un sujet (« Allons dans alarm4tech, qu'en est-il ? », « Sur Edenred, où en est-on ? ») se répond depuis ce que le contexte dit de ce sujet ; on ne délègue que si le contexte n'en dit rien.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Opus échoue / texte vide | poste éteint : repli SF-262-01 (faits relevés) ; demande générale : « Je n'ai pas d'élément pour vous répondre d'ici, Monsieur. » (les faits d'un poste ne répondent pas à une demande qui n'en vise aucun) — jamais le silence |
| Action demandée (« réponds au mail de X », « pose un rendez-vous ») | Opus dit qu'il ne peut pas le faire d'ici, puis ce qu'il sait (question PO ouverte sur l'action ; aujourd'hui elle était muette) |
| Service de réponse absent (pas d'`AtelierProperties`) ou sans écouteur (banc) | comportement d'avant (routage vers le fil Jarvis) |

---

## Critères d'acceptation

- [ ] CA1 — Délégation `jarvis` avec écouteur : l'annonce puis la réponse d'Opus sont dites ; issue `ANSWER`, raison `general` ; aucune ligne-lien, aucune offre au registre, fil Jarvis jamais ouvert pour un tour.
- [ ] CA2 — Le message envoyé à Opus porte « Situation : demande générale » ; un poste éteint porte « Situation : poste éteint » ; la consigne système est **identique** dans les deux cas.
- [ ] CA3 — Opus en échec : la phrase de repli est dite (`GENERAL_FAILED` pour une demande générale).
- [ ] CA4 — Délégation vers un sujet de poste connecté : inchangée.
- [ ] CA5 — Consigne v21 : la règle « question d'état + sujet nommé » est présente ; banc CONVERSATION ×2 vert (V8).
- [ ] CA6 — L'échange est tracé (`exchanges.write`) pour une demande générale répondue.

---

## Périmètre

### Inclus

- `JarvisFastBrainService.settle` / nouvelle `generalInstead`, `answerOffline` (ligne de situation).
- `JarvisOfflinePrompt.SYSTEM` (généralisée, figée), `JarvisFastBrainPrompt.CORE` (une règle, `VERSION` 21).

### Hors scope

- Rouvrir la boucle en bac à sable du fil Jarvis ; agir côté passerelle (envoyer un courriel, poser un rendez-vous).
- Le changement de sujet à l'écran (F-263).

---

## Préoccupations transversales

- [ ] Auth / Principal — non
- [ ] Contexte tenant — non : mêmes lectures filtrées par `user_id` que SF-262-01
- [x] Plans / limites — **composants impactés** : `JarvisFastBrainService.account()` (appels Opus d'une demande générale comptés au budget Jarvis, étiquette `JARVIS_OPUS`, comme le poste éteint) ; aucun gate modifié.
- [ ] Navigation / routing — non

## Tables / endpoints / composants impactés

- Tables : aucune. Endpoints : `POST /api/jarvis/converse` (comportement d'une délégation générale). Composants : `JarvisFastBrainService`, `JarvisOfflinePrompt`, `JarvisFastBrainPrompt`.

## Plan de test

- Unitaires `JarvisOfflineTest` : CA1, CA2, CA3, CA4.
- Unitaires `JarvisFastBrainServiceTest` : CA6 (trace), non-régression routage.
- `JarvisFastBrainPromptTest` (version, règle) ; banc CONVERSATION ×2 (`JarvisConversationBenchLiveTest`, `JARVIS_LIVE_BENCH=2`).

### Isolation

- La réponse générale lit sous `request.userId()` uniquement (mêmes lecteurs que SF-262-01).

## Dépendances

SF-262-01 fusionnée.
