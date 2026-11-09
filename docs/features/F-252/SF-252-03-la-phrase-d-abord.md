# Mini-spec — [F-252 / SF-252-03] La phrase d'abord

## Identifiant

`F-252 / SF-252-03`

## Feature parente

`F-252` — Jarvis répond vite (cadrage §2, correctif D)

## Statut

`in-progress`

## Date de création

2026-11-08

## Branche Git

`feat/SF-252-03-la-phrase-d-abord`

---

## Objectif

Une réponse qui montre quelque chose commence par sa phrase, dans le même message que l'ordre d'écran : plus de second
appel au modèle pour « trouver une phrase ».

---

## Comportement attendu

### Cas nominal

1. Consigne du cerveau rapide (version suivante libre) : l'appel à `afficher` n'est jamais seul ; la phrase s'écrit
   d'abord, l'outil vient ensuite, dans le même message. Une lecture préalable (`pieces`, `agenda`, `etat`) reste un
   second appel légitime : seule l'ordre parole → outil change, jamais ce que Jarvis dit ni montre.
2. Le moment où « Voici. » est dit (vérification serveur de l'affichage, F-253) n'est pas touché.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Le modèle appelle encore `afficher` sans phrase | Filet actuel inchangé : relance pour obtenir la phrase | 200 (SSE) |
| Bancs Jarvis rouges | Pas de merge, retour à la consigne précédente ou correction | — |

---

## Critères d'acceptation

- [ ] CA1 — `JarvisFastBrainPrompt.VERSION` passe au numéro libre suivant ; le préfixe reste stable d'un appel à l'autre.
- [ ] CA2 — La règle figure dans la consigne (test) ; le modèle reste Sonnet.
- [ ] CA3 — Bancs CONVERSATION, PIÈCES, ORAL : **2 passages verts** sur le code fusionné ; résultats dans la PR.
- [ ] CA4 — Les filets de relance existants ne changent pas (tests `JarvisFastBrain*` verts).

---

## Périmètre

### Hors scope (explicite)

- Tout changement de modèle ; toute réduction du message ; les gardes ; le moment de « Voici. » (F-253).

---

## Valeurs initiales

Aucune.

---

## Contraintes de validation

Aucun champ nouveau.

---

## Technique

### Endpoint(s)

Aucun. ### Tables impactées : aucune. ### Migration Liquibase : non.

### Composants touchés

- Backend : `JarvisFastBrainPrompt` (consigne, `VERSION`).

### Préoccupations transversales

Aucune cochée.

---

## Plan de test

### Tests unitaires

- [ ] `JarvisFastBrainPromptTest` (ou test de consigne existant) — règle présente, version bumpée.

### Tests d'intégration

- [ ] Pré-bancs live `JarvisConversationBenchLiveTest`, `JarvisPiecesBenchLiveTest`, banc oral — 2 passages.

### Isolation utilisateur

- [ ] Non applicable (aucune donnée).

---

## Dépendances

- SF-252-02 mergée (pas de dépendance fonctionnelle). F-253 prépare aussi une version de consigne : rebaser, prendre le
  numéro libre, repasser les bancs sur le résultat fusionné.

## Notes et décisions

- Mesure post-déploiement : part des tours `afficher` sans phrase (second appel) dans `usage_turns` / premier son.
