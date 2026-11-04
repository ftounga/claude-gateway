# Mini-spec — F-243 / SF-243-06 Un accusé de réception n'est pas une demande ; plus d'accusé administratif

## Identifiant
`F-243 / SF-243-06`

## Feature parente
`F-243` — Jarvis et vos rendez-vous (correctif du banc CONVERSATION passé en prod après SF-243-05)

## Statut
`in-progress` · **Estimation :** 0,5 j · **Branche :** `feat/SF-243-06-accuse-et-formules` · **Dépend de :** SF-243-05

## Objectif
Qu'un accusé de réception de Monsieur (« C'est noté », « je vois », « entendu ») reçoive une courte réponse au lieu
d'une délégation muette, et que Jarvis ne dise plus d'accusé administratif creux (« J'en prends bonne note »).

## Constat (banc CONVERSATION en prod, `claude-sonnet-5-5`, 2 fois sur 2)
| Cas | Obtenu | Cause établie |
|---|---|---|
| **P08#5** — après « le compte rendu de recette à envoyer à Claire Dumas », Monsieur dit « C'est noté. » | route `deleguer`, rien dit | Deux chemins mènent à la délégation : (a) le modèle répond « C'est noté, Monsieur. » ou « Entendu. », formules retirées **seules** par la porte (`FILLERS`) ⇒ réponse vide ⇒ **délégation d'office muette** (`REASON_EMPTY`, annonce vide) ; (b) le modèle lit l'accusé comme un « oui » à l'envoi évoqué, aidé par la règle « une décision à retenir : délègue pour qu'elle soit notée ». Aucune garde ne dit qu'un accusé n'est pas un travail (seules les salutations/politesses sont gardées, `isSmallTalk`). |
| **C08#1** — « Je suis un peu fatigué aujourd'hui. » | « Un lundi qui demande un peu d'efforts, Monsieur. J'en prends bonne note. » | La famille des accusés administratifs (« j'en prends note », « bien noté », « je note ») n'est ni dans la liste des formules interdites de la consigne v17, ni dans les gardes. |

## Comportement attendu
1. **Garde déterministe (racine)** `JarvisFastGuards.isAcknowledgement(said, context)` : la parole n'est **qu'un**
   accusé de réception (liste fermée, normalisée, « Jarvis »/« Monsieur » ignorés, « merci » toléré) :
   - toujours : « c'est noté », « noté », « bien noté », « je note », « je vois », « ah je vois », « compris »,
     « bien compris », « j'ai compris », « je comprends », « c'est compris », « pris note » ;
   - « entendu », « ça marche », « ça me va », « c'est entendu » : accusé **sauf** si la dernière réplique de
     Jarvis du contexte est une question (une proposition : ces mots peuvent alors valoir « oui ») — le modèle décide.
   - « ok », « d'accord », « très bien », « parfait » restent gérés par la garde de politesse existante (inchangée).
   Une délégation (outil appelé, délégation d'office, réponse vide) sur un accusé devient une **réponse** : ce que le
   modèle a déjà dit de sûr, sinon la réponse fixe « Parfait, Monsieur. ». Rien n'est délégué, rien n'est promis.
2. **Consigne v18** (texte figé, préfixe stable) :
   - « Un accusé de réception (« c'est noté », « ok », « entendu », « je vois », « d'accord ») n'est jamais une demande
     d'action, même juste après qu'une action a été évoquée : réponds d'un mot bref, sans déléguer. Seul un
     acquiescement explicite à une proposition que tu viens de faire (« oui, vas-y », « fais-le », « envoie-le »)
     se délègue. »
   - formules interdites : ajout de la famille des accusés administratifs « J'en prends note », « J'en prends
     bonne note », « Bien noté », « Je note », « Je prends note » (« C'est noté : <fait> » reste la réponse d'un
     « retiens que… », F-241 inchangé).
3. **Garde de porte** `JarvisFastGuards.isAdministrativeAck(sentence)` : une phrase qui n'est qu'un accusé
   administratif est retirée **en conclusion** (après une autre phrase dite), comme les relances de conclusion ; seule,
   elle est gardée (jamais de réponse vidée ⇒ jamais de délégation d'office).

## Cas d'erreur
| Cas | Comportement |
|---|---|
| « C'est noté. » après une mention du compte rendu à envoyer | réponse brève, aucune délégation |
| « C'est noté. » et le modèle répond « C'est noté, Monsieur. » (retirée) | « Parfait, Monsieur. » au lieu d'une délégation d'office muette |
| « Je vous propose de l'envoyer ? » puis « Oui, vas-y. » / « Envoie-le. » / « Fais-le. » | délégation inchangée (pas un accusé) |
| « Je le prépare ? » puis « Entendu. » | décision du modèle (peut valoir « oui ») |
| « Retiens que je pars le 20 décembre. » | « C'est noté : … » + 📌 noter (F-241 inchangé) |
| « …, Monsieur. J'en prends bonne note. » | la seconde phrase est retirée |
| « C'est noté, vérifie quand même » | délégation forcée inchangée (pas un accusé seul) |

## Contraintes de validation
| Champ | Règle |
|---|---|
| accusé de réception | liste fermée, normalisée (accents, ponctuation, casse), parole entière ; aucun autre mot que « jarvis », « monsieur », « merci », « bien », « alors », « ah », « bon », « oui » en tête |
| réponse fixe | « Parfait, Monsieur. » |

## Critères d'acceptation
- [ ] Une délégation (outil ou d'office) sur « C'est noté. » / « Je vois. » / « Compris. » devient une réponse.
- [ ] « Oui, vas-y », « Envoie-le », « Fais-le » ne sont pas des accusés ; « Entendu » après une question de Jarvis
      n'est pas forcé.
- [ ] « J'en prends bonne note. » en conclusion est retirée ; seule, elle n'entraîne aucune délégation.
- [ ] 📌 noter de F-241 inchangé (tests existants verts).
- [ ] Consigne v18, préfixes système constants (tests).
- [ ] Pré-banc CONVERSATION réel complet : 2 passages consécutifs porte vraie ; PIÈCES, ORAL, MAIL : porte vraie une fois.
- [ ] Seuils, juges et scénarios inchangés ; aucune escalade Opus.

## Plan de test
- **Unitaires** `JarvisFastGuardsTest` : `isAcknowledgement` (accusés vrais, variantes avec Jarvis/Monsieur/merci,
  « entendu » après une question ⇒ faux, « oui vas-y », « envoie-le », « fais-le », « c'est noté, vérifie »,
  « retiens que… » ⇒ faux) ; `isAdministrativeAck` (vrais/faux, « C'est noté : départ le 20 » ⇒ faux).
- **Service** `JarvisFastBrainServiceTest` : modèle qui appelle `deleguer` sur « C'est noté. » ⇒ réponse ; modèle qui
  répond « C'est noté, Monsieur. » ⇒ « Parfait, Monsieur. », aucune délégation ; « Envoie-le. » ⇒ délégation ;
  « …. J'en prends bonne note. » ⇒ seconde phrase retirée.
- **Isolation utilisateur** : aucun accès nouveau aux données (la garde lit le contexte déjà construit sous `user_id`).
- **Bancs réels** (clé lue à la volée) : CONVERSATION ×2 complet, PIÈCES ×1, ORAL ×1, MAIL ×1 ; budget ≤ 20 €.
- Suite complète backend.

## Impact
- **Tables / endpoints** : aucun. **Frontend** : aucun.
- **Backend** : `JarvisFastBrainPrompt` (v18), `JarvisFastGuards` (`isAcknowledgement`, `isAdministrativeAck`),
  `JarvisFastBrainService` (`decide` : garde d'accusé ; porte : accusé administratif en conclusion) ; tests.
- Préoccupations transversales : aucune (ni auth, ni tenant, ni plans, ni navigation).

## Arbitrages
- La garde agit **après** la décision du modèle (comme la garde de politesse) : la consigne reste la première ligne,
  la garde ferme le chemin de délégation d'office muette, qui ne dépend pas du modèle.
- « Entendu »/« ça marche » ne sont pas forcés après une question de Jarvis : on ne casse jamais un « oui » réel.
- « C'est noté » reste dans `FILLERS` (formule creuse dite seule par Jarvis) : la réponse fixe remplace le vide.

## Hors périmètre
- Juges, seuils, scénarios, modèle ; garde de politesse existante (« ok », « oui », « d'accord ») ; déploiement.
