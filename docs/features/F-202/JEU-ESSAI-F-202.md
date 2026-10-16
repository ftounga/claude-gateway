# Jeu d'essai F-202 — Plume avant / après, 5 phrases réelles

**Date** : 2026-10-16. **Modèle** : `claude-opus-5-5` (celui de la prod, `APP_ATELIER_MODEL`).
**Phrases** : 5 messages réels du PO tirés de la prod (lecture seule), un par sujet, + 1 cas écarté.
**Avant** = consigne JSON F-193, 1 200 jetons, 6 messages, fin des réponses de l'agent, rendu recomposé.
**Après** = consigne Markdown F-202, 6 000 jetons (10 000 au nouvel essai), 12 messages sous budget,
début + fin des réponses, passation du fil.

> Limites de l'essai : `STATE.md`, carte du poste, chantier guidé et fichiers touchés ne sont pas
> reproduits hors runner (identiques ou absents des deux côtés). La passation lue est la passation
> **actuelle** du sujet, pas celle de l'instant du message. Les sorties complètes restent hors du dépôt :
> elles citent des données client (adresses, noms de buckets, personnes). Seul l'exemple 5, interne au
> poste, est reproduit en entier.

## Résultats

| # | Sujet | Phrase (début) | Avant | Après |
|---|---|---|---|---|
| 1 | data-platform | « Avant que je n'envoie ça je veux que tu refasses une analyse approfondie… » | **tronqué** (`max_tokens` à 1 200) ⇒ JSON brut, accolades dans la saisie | complet (`end_turn`, 4 508 jetons), 6 sections, contexte daté et sourcé |
| 2 | alarm4tech | « Non ton message fait référence plusieurs fois à un enregistrement… » | JSON composé, puces d'une ligne | complet (4 133 jetons), 6 sections, 3 points à confirmer |
| 3 | spider-mfa | « Maintenant tu as tout sur le MFA spider. GO pour l'analyse » | **tronqué** ⇒ JSON brut | complet (4 024 jetons), 6 sections, 4 points à confirmer |
| 4 | waf | « Du coup cet accès on est ok. Quels sont les autres ? » | JSON composé, puces d'une ligne | complet (3 500 jetons), 6 sections |
| 5 | Terminal du poste | « Republie la page cr-daily-explique.html… » | JSON composé (ci-dessous) | complet (1 839 jetons), 6 sections (ci-dessous) |
| — | pentest-opera | « Du coup tu es d'accord pour la passer en high ? » | refus du fournisseur (`refusal`) | **idem** : refus, message d'échec générique, texte intact |

**Bilan** : avant, 2 sorties sur 5 étaient des fragments JSON injectés tels quels ; après, 5 sur 5
complètes, sans nouvel essai, toutes au format attendu. Coût : 2 000 à 4 500 jetons de sortie au lieu
de ~1 100 (justesse avant coût), sur geste explicite seulement. Matière : 10 à 20 k caractères au lieu
de 3 à 5 k. Durée mesurée : 42 s pour 4 406 jetons (~105 jetons/s) — d'où un nouvel essai plafonné à
10 000 jetons, sous le délai d'appel du fournisseur (120 s).

## Exemple 5 en entier

**Avant (F-193)**

```text
Objectif : Republier la page cr-daily-explique.html sous le titre « Skill cr-daily expliquée » ; je clique sur Autoriser tout de suite.
Contexte :
- La page cr-daily-explique.html a été générée à la racine du poste pour expliquer la skill /cr-daily.
- Elle contient le schéma du déroulé, les trois terminaux, les tickets trouvés aujourd'hui, le format du CR et les limites.
- Elle décrit les terminaux data-platform (board LDIC), agenor (board FCAAL3) et le terminal principal (dossier dev, absent de boards.json).
- L'amendement « parent OU étiquette » de cr-daily est appliqué (fichier modifié à 11:18).
Fichiers concernés :
- cr-daily-explique.html
- boards.json
Contraintes :
- Garder le titre exact « Skill cr-daily expliquée ».
- Contenu de la page inchangé, seule la publication est refaite (à confirmer).
Critère de fin : Après mon clic sur Autoriser, la page est publiée avec le titre « Skill cr-daily expliquée » et s'affiche correctement (à confirmer).
```

**Après (F-202)**

```markdown
## Objectif
Republie la page `cr-daily-explique.html` sous le titre « Skill cr-daily expliquée ». La publication précédente a expiré faute de clic sur [Autoriser]. L'utilisateur est prêt à cliquer tout de suite : lance la publication sans délai et annonce-la clairement pour qu'il ne la manque pas.

## Contexte établi
- La page explique la skill `/cr-daily` (portée poste, fichier `.claude/skills/cr-daily.md`). Elle détaille :
  - le déroulé ;
  - le comportement selon le terminal (`data-platform`, `agenor`, terminal principal) ;
  - les tickets trouvés aujourd'hui ;
  - le format du CR ;
  - les limites, dont le travail sans ticket hors du sujet et DECPB-196 qui n'entre dans aucun CR.
- Le fichier `cr-daily-explique.html` a été écrit à la racine du poste au tour précédent.
- La publication a expiré sans le clic de l'utilisateur sur [Autoriser]. La page n'est donc pas publiée.
- Un amendement de `cr-daily` a été proposé : la recherche DECPB passe à `parent in (...) OR labels in (...)`. La page décrit déjà ce comportement (« les tickets qui ont le bon parent ou la bonne étiquette »).
- Les clics [Appliquer] ont déjà échoué plusieurs fois sans message sur des cartes anciennes. Une seule demande neuve, sur laquelle l'utilisateur clique immédiatement, a fonctionné.

## Ce qu'il faut faire
1. Vérifie que `cr-daily-explique.html` existe toujours à la racine du poste et qu'il n'est pas vide. Note sa taille et son heure de modification.
2. Vérifie que le titre de la page est bien « Skill cr-daily expliquée ». Si le titre affiché dans le fichier diffère, signale-le avant de publier. Ne réécris rien sans accord.
3. Lance une seule demande de publication, neuve, avec ce titre. Ne relance pas l'ancienne demande expirée.
4. Dans le même message, dis à l'utilisateur de cliquer sur [Autoriser] tout de suite, sur cette demande-là. Décris-lui à quoi la reconnaître : le titre et le nom du fichier.
5. Après son clic, vérifie que la publication a abouti et récupère ce que l'outil renvoie, par exemple un lien ou un statut.
6. Si la publication expire ou échoue de nouveau, ne boucle pas. Rapporte l'erreur exacte et propose une seule alternative.

## Contraintes et garde-fous
- Ne modifie ni la skill `.claude/skills/cr-daily.md`, ni `boards.json`, ni aucun ticket Jira. Cette demande porte uniquement sur la publication de la page.
- Ne mets pas à jour le contenu de la page, même si les chiffres du jour ont pu bouger depuis sa rédaction, sauf demande explicite (à confirmer).
- Ne contourne pas l'étape [Autoriser] : la publication reste soumise au clic de l'utilisateur.
- Garde des réponses courtes. L'utilisateur attend la demande pour cliquer.

## Vérification attendue
- La publication est confirmée par l'outil après le clic, avec le titre « Skill cr-daily expliquée ».
- À la fin, rapporte :
  - le fichier publié, avec sa taille et son heure de modification ;
  - le titre utilisé ;
  - le résultat renvoyé (lien ou statut) ;
  - tout écart constaté sur le fichier.

## Points à confirmer
- Faut-il garder la page telle qu'écrite au tour précédent, ou rafraîchir les tickets et compteurs du jour avant de publier ?
- Le titre « Skill cr-daily expliquée » doit-il aussi figurer dans la balise `<title>` du fichier, si ce n'est pas déjà le cas ?
```
