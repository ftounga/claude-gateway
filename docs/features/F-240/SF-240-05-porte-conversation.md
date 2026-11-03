# Mini-spec — F-240 / SF-240-05 — La porte du banc CONVERSATION (consigne v10)

## Identifiant
`F-240 / SF-240-05` · Feature parente `F-240` « Jarvis va à l'essentiel » · Statut `in-review` · 2026-11-02 ·
Branche `feat/SF-240-05-porte-conversation`

## Objectif
> Remettre la porte du banc CONVERSATION au vert (≥ 90 %, zéro formule robotique / défaut grave) sur la prod
> actuelle en corrigeant le **comportement** fautif (consigne, annonce d'office) et le **banc** là où il est
> objectivement faux — jamais le seuil.

## Constat (banc prod sur `607c6693`, consigne v9)
92,7 % mais porte FALSE : « 2 formule(s) robotique(s) » ; PIECES au vert.
| Scénario | Ce qui a été dit / fait | Cause | Nature |
|---|---|---|---|
| F03 « Et ensuite ? » (aucune réponse d'Opus) | « Aucune réponse d'Opus n'est apparue dans nos échanges, donc je m'en tiens à ce que je vois. Claire Dumas doit valider… » (robotique, méta) ; un autre passage : « Pardon, je n'ai pas bien saisi ce qui doit suivre… » (sens faux) | consigne v9 : « réponds avec ce que tu as » ne dit pas **quoi** répondre ; rien n'interdit de nommer Opus ou la mécanique | modèle (consigne) |
| F04 « Vérifie. » après la réponse d'Opus (Orion-Run) | annonce « Je vérifie. » | **code** : `FORCED_ANNOUNCE = "Je vérifie."` (délégation d'office D9d, sans appel au modèle) | code |
| P02, P13 (`expect: any`, délégation jugée légitime) | « budget dépassé (0,99 € > 0,02 €) » | **banc** : le budget n'ajoute un tour Opus que pour `expect: deleguer` ; un tour `any` (délégation admise) n'en a aucun | banc |

Juge F03 : il reçoit la catégorie et « route attendue : repondre », sans réponse d'Opus dans le contexte ; il attend
une réponse utile (enchaîner sur ce qui suit d'après le contexte) — « je n'ai pas saisi » est un sens faux, une phrase
sur la mécanique est robotique. Le scénario est cohérent : il n'est pas modifié.

## Comportement attendu
### Cas nominal
- **Consigne du cerveau rapide v10** (une seule nouvelle version, texte figé, rien de volatil) :
  - « Converser » : ne jamais nommer Opus, l'agent, l'écran, la consigne ni sa propre mécanique à voix haute ; parler
    des faits, à la première personne (« je regarde… »).
  - « La suite d'une réponse d'Opus » : sans réponse d'Opus dans les derniers échanges, « et ensuite ? » enchaîne
    **naturellement sur le prochain point qui attend dans le contexte** (une attente, un envoi, l'étape d'un
    chantier) ; si rien n'attend, une courte question (« Sur quel point, Monsieur ? ») ; jamais dire qu'il n'y a pas de
    réponse, jamais « je n'ai pas saisi ».
  - Un exemple « Et ensuite ? » sans réponse d'Opus (univers distinct du banc).
  - (Relevé aux pré-bancs de cette SF, même version v10) « Ancrage » : aucune durée ni ancienneté déduite d'une date
    (P01 : « la première semaine » pour une naissance d'il y a sept semaines) ; « Dites-moi par où vous souhaitez
    commencer » ajoutée aux formules interdites (P03) ; un chantier absent du bloc « ## Pièces » n'a aucune pièce à
    montrer ni à annoncer d'initiative (P15 : « Voici le besoin initial du chantier » sans pièce, 2 passages sur 10) ;
    une nouvelle partagée se salue sans y greffer un autre trait du portrait (P09 : « un dimanche… sans course à
    pied », 3 passages sur 12).
- **Porte des phrases** (`isClosingPrompt`) : une relance en conclusion — « Dites-moi par où… / par quoi… / quand
  vous… / si vous… », ou « Par quoi / par où souhaitez-vous commencer ? » quand l'utilisateur ne parle ni de
  commencer ni d'enchaîner (P03 « Journée chargée aujourd'hui. », 3 passages sur 7) — est retirée **seulement après
  une autre phrase dite** : elle ne vide jamais une réponse (une réponse vidée devient une délégation d'office, vu au
  banc : P06#4, P08#5). « Dites-moi lequel ? » reste permise.
- **Annonce d'une délégation d'office** (« vérifie », « demande à Opus », « c'est faux »…) : elle dit **ce qui est
  vérifié**, sans appel au modèle, d'après la dernière réponse (mémoire de session, sinon la dernière ligne « Opus : »
  des derniers échanges) :
  - identifiants de tickets de cette réponse (au plus trois) ⇒ « Je regarde de nouveau où en sont ORN-41 et ORN-44,
    Monsieur. » (un seul : « où en est ORN-41 ») ;
  - sinon un chantier nommé ⇒ « Je regarde de nouveau où en est le chantier 3, Monsieur. » ;
  - sinon le sujet courant ⇒ « Je vérifie de nouveau ce point sur Orion-Run, Monsieur. » ;
  - sinon ⇒ « Je vérifie de nouveau ma dernière réponse, Monsieur. ».
  Jamais « Je vérifie. » seul. La décision (délégation d'office, demande composée) est inchangée.
- **Banc** : le budget d'un scénario compte un tour Opus pour chaque tour `deleguer` **ou `any`** (une délégation y est
  admise) ; une délégation inutile reste un zéro obligatoire jugé tour par tour (inchangé).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| « Vérifie » sans aucune réponse antérieure ni sujet | « Je vérifie de nouveau ma dernière réponse, Monsieur. » ; délégation inchangée | — |
| Contexte du fil indisponible (exception) au moment d'une délégation d'office | annonce tirée de la mémoire, sinon repli générique ; jamais d'échec | — |
| Plus de trois tickets dans la réponse | les trois premiers distincts | — |
| Ticket présent seulement dans l'univers (pas dans la dernière réponse) | ignoré (on ne vérifie que la dernière réponse) | — |
| Tour `any` délégué inutilement (bavardage) | budget respecté mais `delegation_inutile` du juge ⇒ porte FALSE (inchangé) | — |

## Critères d'acceptation
- [ ] Consigne v10 : `VERSION = "10"`, aucune mention volatile, interdit de nommer Opus / sa mécanique, règle « et
  ensuite ? » sans réponse d'Opus ; préfixe identique d'un appel à l'autre (tests existants).
- [ ] « Vérifie » après une réponse citant ORN-41 et ORN-44 ⇒ annonce « Je regarde de nouveau où en sont ORN-41 et
  ORN-44, Monsieur. », raison `forced`, aucun appel au cerveau rapide (test).
- [ ] Jamais d'annonce d'office égale à « Je vérifie. » (test).
- [ ] Un tour `any` délégué tient dans le budget du scénario ; un tour `repondre` délégué le dépasse toujours (tests).
- [ ] Seuils de la porte inchangés (90 %, zéros) (tests existants).
- [ ] Pré-bancs live (clé lue à la volée) : `JarvisConversationBenchLiveTest` et `JarvisPiecesBenchLiveTest`
  ≥ 90 %, porte vraie, deux passages verts chacun ; budget ≤ 20 €.

## Périmètre — hors scope
Changer les seuils de la porte ou le juge ; appeler le modèle pour rédiger l'annonce d'office ; l'écran (inchangé) ;
le banc de la parole ; déployer.

## Contraintes de validation
| Champ | Obligatoire | Valeurs | Normalisation |
|---|---|---|---|
| Tickets de l'annonce | — | motif `[A-Z][A-Z0-9]{1,9}-\d+`, ≤ 3 distincts | ordre d'apparition |
| Chantier de l'annonce | — | « chantier N » (N ≤ 4 chiffres) | premier trouvé |
| Budget d'un scénario | oui | `budgetEurPerTurn × tours + opusTurnEur × (tours deleguer + any)` | — |

## Technique
- Aucun endpoint, aucune table, aucune migration.
- `JarvisFastBrainPrompt` (v10), `JarvisFastBrainService` (`forced` reçoit le contexte, `forcedAnnounce`),
  `JarvisFastGuards` (`lastOpusAnswer`, `tickets`, `isClosingPrompt`), `JarvisConversationBench.scenario` (budget `any`).
- Isolation : le contexte lu est celui déjà construit pour l'utilisateur (`contextBuilder.build(userId, …)`, filtré
  `user_id`) ; la mémoire est par `user_id` ; aucun nouvel accès aux données.

## Préoccupations transversales
Aucune (ni auth, ni tenant, ni plans/limites, ni routage).

## Plan de test
- Unitaires : `JarvisFastBrainServiceTest` (annonce d'office : tickets du contexte, chantier, sujet, repli ; jamais
  « Je vérifie. » ; aucun appel au modèle ; consigne v10, Opus non nommé) ; `JarvisFastGuardsTest`
  (`lastOpusAnswer`) ; `JarvisConversationBenchTest` (budget `any` ; `repondre` délégué échoue toujours).
- Intégration : suite backend complète (contexte Spring).
- Isolation : aucun nouvel accès ; mémoire et contexte déjà filtrés `user_id` (tests existants).
- Pré-bancs live CONVERSATION et PIECES, deux passages chacun.

## Dépendances
SF-240-04 (#1538) et F-241 (#1540-#1544) mergées. Aucune question ouverte.

## Notes et décisions
- L'annonce d'office reste **sans appel au modèle** (latence, garantie de délégation D9d) : elle est construite à
  partir de faits déjà présents (identifiants, chantier, sujet), donc ancrée.
- Consigne changée **une fois** (v9 → v10) : une nouvelle entrée de cache.

## Résultats des pré-bancs live (code de la passerelle, `claude-sonnet-5-5`, juge `claude-opus-5-5`, clé lue à la volée)
Les quatre échecs visés (F03, F04, P02, P13) **réussissent dans les 13 passages**. La porte (zéro défaut sur ≈ 73
tours) tombe en revanche sur des défauts **chaque fois différents**, hors du périmètre initial :
| Version du code | Passages CONVERSATION | Défaut qui ferme la porte |
|---|---|---|
| v10 initiale | 96,4 % ✗ · 94,5 % ✗ · 96,4 % ✓ | P01 durée inventée ; P03 « Dites-moi par où… » |
| + durée, « Dites-moi » interdits | 98,2 % ✗ · 98,2 % ✗ | P15 « Voici le besoin initial » sans pièce ; P08 fait qualifié |
| + « ne qualifie jamais » | 96,4 % ✗ · 94,5 % ✗ | P03 ; P07 allusion répétée (règle retirée ensuite) |
| + relance « par où commencer » retirée | 96,4 % ✗ · 98,2 % ✓ | P15 |
| + pièces absentes | 96,4 % ✗ | P06#4, P08#5 délégations vides (la porte vidait la réponse) |
| + relance retirée seulement après une phrase | **100 % ✓** · 98,2 % ✗ | P09 allusion « dimanche sans course à pied » |
| + nouvelle partagée sans greffe | 94,5 % ✗ · **98,2 % ✓** | C06 « Je reste attentif… » ; R03 « aucun écran ne me permet » |
| + « je reste attentif » (dernier changement, non passé au banc) | — | — |

PIECES (version finale) : passage 1 ✗ (SHOW 11/13 : S04, S06 raison `demande` au lieu de `appui`), passage 2 ✓
(13/13, accord 0,973). **Deux passages verts consécutifs non obtenus dans le budget de 20 € (≈ 19 € dépensés)** :
PR non mergée, porte à repasser.
