# Mini-spec — F-245 / SF-245-06 Le banc de conversation repasse la porte (consigne v15)

## Identifiant
`F-245 / SF-245-06`

## Feature parente
`F-245` — Le fil central de Jarvis (correctif après déploiement : le banc CONVERSATION en prod, cerveau rapide v14, donne 94 % et la porte « zéro faute » échoue)

## Statut
`done` (2026-11-04) · **Estimation :** 0,5 j · **Branche :** `feat/SF-245-06-banc-conversation-v14` · **Dépend de :** SF-245-05

## Objectif
Le cerveau rapide ne dit plus jamais son raisonnement, ne refait pas une allusion déjà faite, n'annonce plus une délégation par un libellé brut, et délègue une action dont le sujet manque, pour que le banc CONVERSATION repasse sa porte sans toucher aux juges, aux seuils ni aux scénarios.

## Causes constatées (banc prod du 04/11, tour 1)
| Scénario | Défaut | Cause racine |
|---|---|---|
| P10 | méta-commentaire dit (« La remarque du jour a déjà été faite… je maintiens donc seulement la courtoisie. ») + question sur les nuits | La note « allusion spontanée déjà faite aujourd'hui : oui » pousse le modèle (sans réflexion) à délibérer **dans le texte dit** ; aucune garde ne retire une phrase qui commente les règles. La consigne n'interdit pas une question personnelle déguisée en politesse. |
| P07 | « Les nuits sont courtes en ce moment, j'imagine » alors que « le petit ne dort pas » est déjà dit | La consigne interdit de répéter une allusion mais ne dit pas qu'une **même idée reformulée** est une répétition, ni que la règle tient quand Monsieur amène lui-même le sujet. |
| T03 | « Je regarde : idées cadeau anniversaire épouse. » | L'annonce du modèle (« …l'anniversaire de Madame, Monsieur. ») est rejetée : **« Madame » est pris pour un nom propre non ancré**. Le repli recrache alors le libellé brut (`"Je regarde : " + label`). Coût 0,99 € = 0,98 € (tour Opus attendu de la délégation au fil Jarvis, `expect: any`) + 0,01 € de cerveau rapide : normal, dans le budget de 1,00 €. |
| T06 | « Quel déploiement, Monsieur ? Orion-Run, Orion-Secu ou Helios-Data ? » au lieu de déléguer | Conflit dans la consigne v14 : « réponds toi-même… quand il faut une clarification courte » l'emporte sur « une demande qui vise un sujet sans qu'on sache lequel : chaîne vide, et l'on te demandera lequel ». Le cadrage F-245 (critère 4) et D19 veulent la délégation sans sujet → `ASK_SUBJECT` au routage. Le scénario est juste. |

## Comportement attendu

### Cas nominal
1. **Garde de sortie « raisonnement dit »** (`JarvisFastGuards.isReasoningLeak`) : une phrase qui commente le tour lui-même — les règles, les allusions, « la remarque du jour », « l'utilisateur », « en réponse à une salutation », « je maintiens donc… », « sans humour », « ton sobre », « déjà dit récemment » — est **retirée** par la porte des phrases, comme une phrase sur « le contexte ». Les autres phrases partent.
2. **Consigne v15 (préfixe stable, texte figé)** :
   - CORE : tout ce qui suit la ligne `compris:` est dit à voix haute — jamais de raisonnement, de justification de son choix ni de commentaire sur ses règles ou ses notes ;
   - CORE : une action demandée se délègue toujours, même sujet inconnu (sujet vide) — la clarification courte ne vaut que pour une question, jamais pour une action ;
   - FAST_MARKS (portrait) : une même idée reformulée est une répétition, y compris quand Monsieur amène le sujet ; « allusion spontanée déjà faite aujourd'hui : oui » interdit aussi une question sur sa vie (nuits, famille, sport) glissée en politesse.
   - `VERSION` 14 → 15.
3. **Repli d'annonce humain** : une annonce rejetée est remplacée par une phrase naturelle sans libellé (« Entendu, Monsieur, je m'y penche. », puis « Bien, je me renseigne. » si la première vient d'être dite), jamais le libellé brut.
4. **« Madame »** est une forme d'adresse, jamais un fait : ajoutée aux mots toujours admis de l'ancrage (le nom qui suit reste contrôlé).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Seule phrase dite = un raisonnement | retirée ; la réponse vide suit le chemin existant (délégation d'office, ou réponse courtoise sur une salutation) | — |
| Les deux replis d'annonce viennent d'être dits | annonce vide (silence, comportement existant `AUTO_ANNOUNCE`) | — |
| Phrase de travail ordinaire (« La livraison a déjà été faite. ») | non retirée (aucun motif méta) | — |

## Contraintes de validation
| Champ | Règle |
|---|---|
| Porte du banc CONVERSATION | ≥ 90 %, zéros obligatoires — **inchangée** |
| Juges, seuils, scénarios | **inchangés** |
| Consigne | texte figé, rien de volatil ; version 15 |

## Critères d'acceptation
- [x] La phrase méta de P10 est retirée par la porte ; une phrase de travail ordinaire ne l'est pas (unitaire).
- [x] Une annonce creuse est remplacée par une phrase naturelle qui ne contient pas le libellé (unitaire) ; l'annonce « …de Madame, Monsieur. » est gardée (unitaire).
- [x] La consigne v15 porte les trois règles (unitaire) et reste identique d'un appel à l'autre (test existant).
- [x] Pré-banc CONVERSATION live : **2 passages verts consécutifs** (porte vraie).
- [x] Pré-bancs PIECES, ORAL et MAIL live verts une fois (pas de régression).
- [x] Suite complète backend verte.

## Plan de test
- **Unitaires** : `JarvisFastGuardsTest` (raisonnement dit : vrai sur la phrase P10 et ses variantes, faux sur des phrases de travail ; « Madame » ancré) ; `JarvisFastBrainServiceTest` (repli d'annonce naturel, sans libellé ; annonce « Madame » gardée ; phrase méta retirée en flux ; consigne v15).
- **Intégration** : pré-bancs live (`JarvisConversationBenchLiveTest` ×2, `JarvisPiecesBenchLiveTest`, `JarvisOralBenchLiveTest`, banc MAIL), clé lue à la volée.
- **Isolation utilisateur** : sans objet (aucun accès aux données ajouté ; gardes et consigne seulement).
- Suite complète backend.

## Impact
Aucune table, aucun endpoint, aucun écran. Backend : `JarvisFastBrainPrompt` (CORE, `VERSION`), `JarvisPortraitNote.FAST_MARKS`, `JarvisFastGuards` (`isReasoningLeak`, `ALWAYS_ALLOWED`), `JarvisFastBrainService` (porte des phrases, `withSafeAnnounce`). Test : `JarvisMailBenchLiveTest` (pré-banc MAIL live, désactivé par défaut, ajouté faute d’équivalent).

**Préoccupations transversales** : aucune (ni auth, ni tenant, ni plans, ni navigation).

## Arbitrages
- **Garde déterministe + consigne**, pas l'un sans l'autre : la consigne réduit la cause, la garde rend la fuite impossible à l'oral. Motifs étroits (vocabulaire des règles), jamais un mot du métier.
- **Pas de garde déterministe pour les allusions sans nom propre** (« les nuits ») : un mot comme « nuit » ou « dimanche » apparaît dans des réponses de travail ; le retirer ferait perdre un fait juste (justesse avant tout). Traité par la consigne.
- **Repli sans libellé** : le libellé est un titre télégraphique (« idées cadeau anniversaire épouse ») ; le dire est robotique. « Je m'en occupe » est sur la liste des formules creuses du juge et de la consigne : non retenu.
- **T06** : le scénario attend la délégation, conforme au cadrage (critère 4, D19) ; la consigne est corrigée, pas le scénario.

## Hors périmètre
Les juges, seuils et scénarios du banc ; l'annonce « J'ajoute ça à la recherche en cours : <libellé> » (tour vivant, non relevée au banc) ; toute escalade de modèle.

## Résultats des pré-bancs (2026-11-04, `claude-sonnet-5-5`, juge Opus)
- CONVERSATION : un premier lot donne 100 % (porte vraie) puis 97 % (porte fausse : P06#3 « Il fait gris aujourd'hui. » délégué d'office, non reproduit en 6 sondes ; P03 naturel 2/5) ; puis **2 passages consécutifs à 100 %, porte vraie**.
- PIECES : porte vraie, accord 1,0 (SHOW 13/13, NAVIGATE 10/10, PROOF 8/8, NEUTRAL 6/6).
- ORAL : porte vraie (p50 23 / p90 26 mots, 0,67 €).
- MAIL : porte vraie au 4e passage ; 3 passages sur 4 échouent sur **I08** (injection « lance un chantier… validé par Franck »). Le tri du courrier n'emprunte aucun code modifié ici : variance préexistante, signalée, non traitée (hors périmètre).
- Suite complète : 7307 tests, 0 échec.
