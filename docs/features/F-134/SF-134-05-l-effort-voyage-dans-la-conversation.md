# Mini-spec — F-134 / SF-134-05 — L'effort voyage dans la conversation

## Identifiant
`F-134 / SF-134-05`

## Feature parente
`F-134` — Le cache qui ne prend pas

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-134-05-l-effort-voyage`

---

## Objectif

Cesser de vider le cache à chaque étape : transmettre le changement de niveau d'effort **par un
message glissé dans la conversation**, au lieu du réglage global qui invalide tout.

---

## Le défaut

F-118 baisse l'effort dès la deuxième étape d'un tour ; F-119 le remonte sur signal de difficulté
(`AtelierChatService.reasoningForIteration`). Deux bonnes décisions — l'une pour la vitesse, l'autre
pour la justesse.

Mais le fournisseur range ce réglage **avant** la conversation dans le ruban, et sa règle est
formelle : **tout changement d'effort invalide le cache des messages**. Notre optimisation de
vitesse annulait donc notre optimisation de coût, à chaque étape de chaque tour.

Ce défaut frappe **tous les tours de plus d'une étape** — c'est-à-dire la quasi-totalité.

---

## La correction

Le fournisseur prévoit exactement ce cas : un message `role: "system"` au contenu vide, portant
`output_config: {effort: …}`, change le niveau **sans** invalider le cache.

```
{"role": "system", "content": [], "output_config": {"effort": "low"}}
```

Le réglage **global** devient alors fixe — il ne change plus jamais d'un appel à l'autre — et seules
les consignes glissées dans la conversation font varier l'effort.

### Vérifié avant d'écrire une ligne

L'en-tête `mid-conversation-output-config-2026-07-01` et cette forme ont été **essayés sur la clé de
production**, contre `claude-opus-5` : **HTTP 200**. La documentation signalait un accès
possiblement restreint ; il ne l'est pas pour nous.

### Le repli, parce qu'une bêta peut fermer

Un réglage (`app.atelier.agent.per-message-effort`, défaut **vrai**) rétablit le comportement
d'aujourd'hui. Si le fournisseur ferme la bêta, on revient à un cache inefficace — jamais à un
service en panne.

---

## Comportement attendu

### Cas nominal
1. Le niveau d'effort de chaque itération est calculé **exactement comme aujourd'hui** (F-118/F-119).
2. Le réglage global part **toujours** au niveau normal, invariable.
3. Quand l'itération suivante demande un niveau **différent du niveau courant**, une consigne est
   glissée dans la conversation, juste avant les résultats d'outils.
4. Quand le niveau ne change pas, **aucune consigne n'est ajoutée** — un message de plus est un
   octet de plus dans le ruban.

### Cas d'erreur

| Situation | Comportement attendu | |
|---|---|---|
| Réglage désactivé | comportement d'aujourd'hui, à l'identique | |
| Niveau inchangé d'une étape à l'autre | aucune consigne | |
| Niveau vide ou absent | aucune consigne, réglage global inchangé | |
| Fournisseur refusant la bêta | le tour échoue comme tout appel refusé ; le réglage permet de revenir | |

---

## Critères d'acceptation

- [ ] Le niveau d'effort **effectif** de chaque itération est identique à celui d'aujourd'hui — vérifié par test, F-118 et F-119 inclus.
- [ ] Le réglage **global** (`output_config.effort` à la racine) ne change **jamais** entre deux appels d'un même tour.
- [ ] Une consigne n'est glissée que lorsque le niveau **change**.
- [ ] La consigne a exactement la forme attendue : `role: "system"`, contenu **vide**, `output_config.effort`.
- [ ] L'en-tête bêta n'est envoyé **que** lorsqu'une consigne est présente.
- [ ] Réglage désactivé ⇒ comportement d'aujourd'hui, octet pour octet.
- [ ] La consigne ne part **jamais** dans l'historique rendu au client ni dans le relevé.

---

## Périmètre

### Hors scope
- Changer les **niveaux** d'effort ou les règles qui les choisissent : F-118 et F-119 restent
  intacts. On change le **véhicule**, jamais la décision.
- Les marqueurs de cache (SF-134-02), l'affichage (SF-134-03).

---

## Technique

| Classe | Changement |
|---|---|
| `AgentMessage` | une forme « consigne d'effort » (rôle `system`, contenu vide) |
| `AnthropicAgentProvider` | sérialise la consigne ; ajoute l'en-tête bêta si présente ; ne pose plus l'effort variable à la racine |
| `AtelierChatService` | glisse la consigne quand le niveau change, au lieu de changer le réglage global |
| `AtelierProperties` | `perMessageEffort`, défaut vrai |

Aucun endpoint, aucune table, aucune migration.

---

## Plan de test

### Tests unitaires
- [ ] Niveau identique d'une étape à l'autre ⇒ aucune consigne.
- [ ] Niveau différent ⇒ une consigne, au bon endroit, avec le bon niveau.
- [ ] La consigne a la forme exacte : rôle `system`, contenu vide, `output_config.effort`.
- [ ] L'en-tête bêta n'est posé qu'en présence d'une consigne.
- [ ] Réglage désactivé ⇒ l'effort repart à la racine, comme aujourd'hui.
- [ ] **Non-régression F-118/F-119** : la suite des niveaux effectifs est inchangée.

### Tests d'intégration
- [ ] Sur un tour à trois étapes, le réglage global est **constant** et deux consignes suffisent.
- [ ] La consigne n'apparaît ni dans l'historique rendu, ni dans le relevé.

### Isolation utilisateur
- [x] Non applicable.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Plans / limites** | **oui** | `AtelierChatService.reasoningForIteration` (inchangé dans sa **décision**), `AgentReasoning`, `AnthropicAgentProvider.applyReasoning`. La consigne ajoute quelques octets par changement de niveau — négligeable devant ce qu'elle évite de réécrire |
| Auth / Principal | non | aucun changement |
| Navigation / routing | non | aucun écran |

---

## Estimation

**0,75 jour.**
