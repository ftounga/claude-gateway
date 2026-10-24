# Mini-spec — F-210 / SF-210-02 — Une réponse par question (backend)

## Identifiant
`F-210 / SF-210-02` — feature parente `F-210` *Deux questions, deux fils* (cadrage
`CADRAGE-F-210-deux-questions-deux-fils.md`). Branche : `feat/SF-210-02-reponse-par-question`.
Statut : `in-progress`. Date : 2026-10-24. Dépend de SF-210-01 (mergée). Aucune migration.

## Objectif
Quand l'agent a découpé le tour (sans fusion), sa réponse finale est une suite de blocs
`<<question id="N">>`, chacun avec son essentiel, son détail et ses suites ; la gateway les lit et range
`terminal_json.answers[]`, chaque bloc de suites rattaché au chantier comme aujourd'hui.

## Comportement attendu

### Cas nominal
1. **Consigne stable** `QUESTION_ANSWER_DOCTRINE` (littéral, toutes cibles, juste après
   `NEXT_STEPS_DOCTRINE`) : quand annoncer (`decouper_demande` seulement pour ≥ 2 demandes distinctes),
   la fusion (une seule réponse normale), et le format de la réponse découpée :
   ```
   <<question id="1">>
   <<essentiel>> … <</essentiel>>
   … détail …
   <<suites>> … (format de NEXT_STEPS_DOCTRINE) … <</suites>>
   <</question>>
   ```
   Dans ce cas **seulement**, l'essentiel et le bloc de suites s'emploient une fois **par question** ; le
   reste des consignes vaut pour chaque bloc. La consigne ne redéfinit pas le bloc de suites : elle renvoie
   au format de `NEXT_STEPS_DOCTRINE`. Elle n'affaiblit ni `MULTI_SUBJECT_DOCTRINE` (toutes les demandes
   couvertes) ni `ESSENTIAL_ANSWER_DOCTRINE` (l'essentiel en tête, court).
2. **Parseur** `QuestionAnswers.parse(texte, instantané)` : lit les blocs (ouverture tolérante
   `<<question id="N">>`, `<<question id=N>>`, casse et espaces ; fermeture `<</question>>` ; ouverture sans
   fermeture = jusqu'à la fin). Pour chaque bloc d'une question annoncée : `essential` (entre les marqueurs
   d'essentiel), `body` (le reste, sans suites), `nextSteps` (lus par `NextStepsBlock.extract` puis
   rattachés par `ChantierAnchor.apply` au parcours relu en fin de tour), `chantier`, `libelle` et
   `status` (`TERMINEE`, ou `NON_ABOUTIE` si l'agent l'a dit). Un id non annoncé est ignoré ; un doublon :
   le premier compte.
3. **Question sans bloc** alors que d'autres en ont : `answers[]` porte une entrée `NON_ABOUTIE` (corps
   vide) et l'instantané `questions` aussi — jamais une réponse qui disparaît sans trace.
4. **Rangement** : `terminal_json.answers = [{id, libelle, status, essential, body, nextSteps, chantier}]`
   (dans l'ordre des questions) ; le dernier instantané SSE `questions` porte aussi `answers`.
5. **Rétro-compatibilité** : le texte persisté (`content`) ne garde **aucun** marqueur de question : chaque
   bloc devient « **N. libellé** » suivi de son essentiel et de son détail en clair (les marqueurs
   d'essentiel internes sont retirés quand il y en a plusieurs) — un écran d'avant SF-210-04, l'historique
   rejoué au modèle et le terminal Teams lisent une réponse propre. Les suites globales (`nextSteps`) restent
   celles du **dernier** bloc lisible, comme aujourd'hui.
6. **Sans découpage, avec fusion, ou sans aucun bloc** : rien ne change (pas de clé `answers`).

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Bloc ouvert sans fermeture (réponse tronquée) | lu jusqu'à la fin, marqueur jamais montré |
| Bloc d'un id non annoncé | ignoré pour `answers` ; texte gardé en clair |
| Suites illisibles dans un bloc | `nextSteps` vide pour ce bloc (règles F-192) |
| Tour interrompu / coupé au plafond | pas de suites (règle existante) ; blocs présents lus |

## Critères d'acceptation
- [ ] Réponse découpée en deux blocs ⇒ `answers` de 2 entrées avec leur essentiel, corps et suites propres.
- [ ] Les suites de chaque bloc passent par `ChantierAnchor.apply` (étape rattachée en mode guidé).
- [ ] Une question annoncée sans bloc ⇒ entrée `NON_ABOUTIE`.
- [ ] Le `content` persisté ne contient ni `<<question`, ni `<</question>>`.
- [ ] Tour sans découpage : `terminal_json` et `content` identiques à avant.
- [ ] La consigne système est identique d'un tour à l'autre et contient toujours `MULTI_SUBJECT_DOCTRINE` et
      `ESSENTIAL_ANSWER_DOCTRINE` inchangées.

## Plan de test
- **Unitaires** : `QuestionAnswersTest` (deux blocs, tolérance des marqueurs, tronqué, id inconnu, doublon,
  sans bloc ⇒ NON_ABOUTIE, texte lisible sans marqueurs, sans découpage = identité).
- **Service** : `AtelierChatServiceQuestionsTest` (tour découpé ⇒ `answers` rangé et relayé ; suites par
  question ; `content` propre), `AtelierChatServiceSystemPromptTest` (doctrine présente, après
  `NEXT_STEPS_DOCTRINE`, stable).
- **Isolation** : aucun accès données nouveau (le parcours est relu par le chemin existant `user_id`).
- Suite complète `./mvnw test`.

## Impacts
- **Tables** : aucune (`terminal_json.answers`, clé additive).
- **Endpoints** : aucun ; champ additif `answers` dans l'événement `questions`.
- **Composants** : `atelier.questions.QuestionAnswers`, `TurnQuestions` (instantané avec réponses, questions
  sans bloc), `AtelierChatService` (doctrine, parse, rangement).

### Préoccupations transversales
Aucune.

## Hors périmètre
L'affichage des cartes (SF-210-04) ; les pistes à l'écran (SF-210-03).
