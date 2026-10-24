# Cadrage F-210 — Deux questions, deux fils

**Demande PO du 2026-10-24** — besoin `cd67af2f` « Deux questions dans le même message : deux fils visibles pendant la réflexion et deux réponses distinctes, sauf remplacement ou lien fort ». Complète F-208 et F-209 (badges de chantier, suites par question).

Justesse avant coût (règle absolue) : le découpage **n'ajoute aucun appel modèle séparé** et ne retire rien au raisonnement ; préfixe stable ; charte.

## Besoin (mots du PO)
« Gérer 2 questions à la fois dans le même terminal. Visuellement ça ne se voit pas clairement, ni durant le processus de réflexion, ni au moment de donner la réponse : on doit voir 2 réponses quand c'est 2 questions, et une uniquement si la 2e remplace la première ou est vraiment très liée à elle. »

## Existant vérifié
- `MULTI_SUBJECT_DOCTRINE` : la réponse finale couvre toutes les demandes « sujet par sujet » — mais **un seul** essentiel (`<<essentiel>>`), **un seul** bloc `<<suites>>`, **une** réflexion affichée.
- Interjections (F-195) fusionnées en un message `[Interjection de l'utilisateur — …]`, `requestsOfTurn`, `unfinishedRequestsNote`.
- Rendu : `essential.ts` (`splitEssential`, `stripNextSteps`), blocs de tour `chat-steps.ts`, SSE `action/text/output/plan/…`.
- Le parallélisme réel des outils existe déjà (F-195 : lectures en parallèle).

## Décisions
1. **Découpage annoncé par l'agent** via un outil serveur léger `decouper_demande(questions[{id, libelle ≤ 80, depend_de?}], fusion?{raison})`, appelé en début de tour **seulement si** le message (ou une interjection) contient ≥ 2 demandes distinctes. Résultat immédiat, aucun effet de bord ; SSE `questions`. Une interjection ajoute des questions par un nouvel appel (ids croissants).
2. **Pistes pendant la réflexion** : outil `suivre_question(id, etat EN_COURS|EN_ATTENTE|TERMINEE|NON_ABOUTIE)` ; les appels d'outils émis pendant qu'une question est EN_COURS lui sont rattachés (attribution par la question courante — assumée approximative pour des lectures parallèles, dite dans la doc). Écran : une piste par question (libellé, état, « en attente de Q1 », ses actions).
3. **Réponse par question** : la consigne demande, quand le tour est découpé, un bloc `<<question id="N">>` par question, contenant **son** `<<essentiel>>`, son détail et **son** `<<suites>>`. Parseur serveur ⇒ `terminal_json.answers[] {id, libelle, status, essential, body, nextSteps, chantier}` ; chaque bloc de suites passe par le rattachement chantier (F-196/F-209). Sans découpage : rendu actuel inchangé (rétro-compatible, historique intact).
4. **Fusion** : une seule carte quand la 2e remplace la 1re ou en dépend fortement ; la carte affiche en tête « Fusionnées : raison ». Bouton **« Séparer » / « Fusionner »** : envoie une demande visible (interjection si un tour tourne) — correction par l'utilisateur, jamais silencieuse.
5. **Carte non aboutie** : marquée « non aboutie » sans masquer la réponse de l'autre.
6. **Badges** : chaque carte porte le badge du chantier concerné ou « Hors chantier ».
7. Critère : message à deux questions indépendantes ⇒ deux pistes pendant le tour et deux cartes ; reformulation/question liée ⇒ une carte avec mention de fusion.

## Préoccupations transversales
Aucune (pas d'auth, tenant, plan, navigation). Nouveaux outils ajoutés à la liste stable des outils (préfixe stable : définitions fixes).

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-210-01 | Backend : outils `decouper_demande` / `suivre_question`, SSE `questions` + rattachement des actions, état du tour persisté dans `terminal_json.questions` ; tests | 🟠 |
| SF-210-02 | Backend : consigne par question, parseur `<<question>>`, `answers[]` avec suites rattachées par question, rétro-compatibilité ; tests | 🟠 |
| SF-210-03 | Front : pistes par question pendant le tour (état, dépendance, actions) + bandeau de découpage avec « Séparer / Fusionner » | 🟠 |
| SF-210-04 | Front : une carte de réponse par question (essentiel, détail, suites deux colonnes F-209, badge chantier, non aboutie, mention de fusion), relecture de l'historique | 🟠 |

## Hors périmètre
Lancer des sous-agents distincts par question ; plafond de budget par question.
