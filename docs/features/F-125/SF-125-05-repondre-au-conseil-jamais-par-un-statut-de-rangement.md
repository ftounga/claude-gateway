# SF-125-05 — Répondre au conseil ; jamais par un statut de rangement

> Cadrage du 2026-09-17 (PO), après un cas réel CAGIP. **Cadrage seul : livraison sur go.**
> Renforcement de F-125 (réponds d'abord, carte silencieuse) et F-126 (baliser l'essentiel).

## 1. Constat (cas réel, terminal racine CAGIP, 12:56)
Question du PO : *« Tu me conseilles de configurer ce qu'il y a dans cette doc, ou ce qu'on a nous
suffira ? »* (une **question de conseil/décision**).
Réponse de l'agent : *« Ce tour n'était qu'un conseil : aucun fait nouveau, rien à ranger… déjà rangés
dans `acces.md` et `reseau.md`. »*
→ **Ce n'est pas une réponse** : il donne un **statut de rangement de la carte** au lieu de **trancher**,
et **sans bloc `<<essentiel>>`**. F-125/F-126 fonctionnent la plupart du temps (au tour précédent, 12:37,
l'essentiel était parfait) mais **glissent sur un tour court** : le vieux réflexe « carte » reprend.

## 2. Objectif (une phrase)
Sur une question de **conseil/décision**, l'agent **tranche et répond directement**, **balise l'essentiel
même sur un tour court**, et **ne répond jamais par un statut de rangement** de la carte.

## 3. Comportement attendu
1. **Question de conseil/décision** (« dois-je… ? », « est-ce que X suffit ? », « tu conseilles quoi ? »,
   « A ou B ? ») → l'agent **prend position** (recommandation nette) + justification courte + la réserve
   éventuelle. Pas de renvoi, pas de « ça dépend » sans trancher.
2. **Baliser `<<essentiel>>`** la réponse directe **même sur les tours courts** (une phrase de conseil
   est justement le cas où l'essentiel doit ressortir).
3. **Interdiction absolue** : ne **jamais** faire d'un **statut de rangement** la réponse — « rien à
   ranger », « aucun fait nouveau », « déjà rangé dans `X.md` », « ce tour n'était qu'un conseil » sont
   du **silence sur la carte**, **jamais** une réponse. Si rien n'est à ranger, **ne pas le mentionner**
   du tout — juste répondre à la question. (Renforce la doctrine F-125-01.)

## 4. Cas d'erreur / limites
- Question **ambiguë** → l'agent peut demander **une** précision, mais **après** avoir donné son meilleur
  conseil par défaut (il ne se défile pas).
- Manque d'info pour trancher → il dit **ce qui manque** pour décider et donne une reco conditionnelle —
  pas un statut de rangement.

## 5. Portée & implémentation
- **Prompt only, déterministe côté consigne** : durcir `buildSystemPrompt` (RUNNER + SANDBOX) en
  prolongeant les doctrines F-125-01 / F-126-01 : ajouter la règle « conseil → tranche + essentiel, même
  court » et l'**exemple négatif interdit** (« ne réponds jamais “rien à ranger / déjà rangé / ce tour
  n'était qu'un conseil” — réponds à la question »).
- **Pas de classifieur d'intention lourd** (on reste au niveau de la consigne ; F-120 gère déjà la
  distinction question/action). Additif, faible risque, cohérent avec le cache de prompt.

## 6. Critères d'acceptation
- Le prompt contient, sur les deux cibles, la règle « conseil → trancher + baliser l'essentiel même
  court » **et** l'interdiction explicite des formules de statut de rangement (substrings vérifiés).
- Non-régression : les doctrines F-125-01 (carte silencieuse), F-126-01 (marqueur essentiel) et le strip
  `fin-de-tour` restent intacts ; les tests de prompt existants passent (substrings verrouillés
  préservés).

## 7. Hors périmètre
- Un détecteur d'intention automatique (F-120 suffit ; on ne re-devine pas).
- Toute UI (F-126 rend déjà l'essentiel ; ici on garantit qu'il est **présent** et **pertinent**).
- Le raisonnement de fond (F-119) : non concerné.
