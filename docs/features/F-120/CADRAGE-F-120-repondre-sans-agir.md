# F-120 — Répondre sans agir : distinguer une question d'un ordre

> Cadrage du 2026-09-15, à la demande du PO : *« J'ai l'impression que, contrairement à Claude Code,
> quand je pose une question (« qu'as-tu compris de notre plan ? », « le plan est-il prêt à être
> exécuté ? »), l'agent me répond en l'exécutant. Fais un audit complet et dis-moi si je me trompe. »*
> **Cadrage seul : la livraison attend le go du PO.**

## 0. Verdict de l'audit : le PO a raison. C'est structurel.

Audit trois angles, lecture seule (boucle & outils, prompt & sémantique du « plan », **preuve
empirique** en prod). Résultat convergent.

**Preuve empirique (poste CAGIP, 38 tours-question analysés)** :

| Réponse de l'agent à une question | Part |
|---|---|
| Texte seul (aucun outil) | 24 % |
| A « agi » (≥1 outil) | **76 %** |
| — dont lecture-pour-répondre (légitime) | 24 % |
| — dont **exécution/écriture NON demandée** | **53 %** |
| Questions *pures* (aucun verbe d'ordre) → écriture non demandée | **29 %** |

Cas réels : « Tu as pu lire Teams ? » (oui/non) → **`edit_file` sur 2 fichiers** puis « Non » · « Pourquoi
la promotion est reportée ? » → **réécrit 3 fichiers** · « Alors ? » → **crée `architecture.md`**.
Contre-exemples honnêtes où l'agent fait bien (lit puis répond) : « combien de dossiers dans infra ? »
→ `ls` puis réponse. La **lecture pour fonder une réponse est légitime** ; le problème est
l'**écriture/exécution non demandée**.

> Limite : corpus mono-utilisateur, petit (38 tours), classification heuristique. Signal fort, pas une
> statistique de population. Mais les causes de code sont **structurelles**.

## 1. Causes racines (fichier:ligne)

1. **Aucune distinction question/action, aucun mode.** `runLoop` (`AtelierChatService.java:570`) est
   l'entrée unique ; `buildSystemPrompt` et `buildTools` sont appelés **inconditionnellement**, sans
   regarder le message. `AgentTurnRequest` (`agent/AgentTurnRequest.java:19-21`) ne porte **aucun** champ
   mode/intent. Pas d'équivalent du *plan mode* / lecture seule de Claude Code (aucun `planMode`,
   `readOnly` de tour, `askOnly`).
2. **Toute la panoplie d'action exposée à chaque tour.** `buildTools` (`AtelierChatService.java:2241-2297`)
   expose `write_file`, `edit_file`, `bash`, `set_plan` filtrés seulement par la **cible** et les
   **volets sous licence**, **jamais par le message**. Un modèle à qui on tend des outils d'écriture a
   un biais fort à s'en servir.
3. **Prompt orienté action + gouvernance procédurale injectée.** Le rôle (`:2383-2395`) ne dit que
   « tu **travailles** sur le projet, utilise read_file/write_file… » — **rien** comme « si l'utilisateur
   pose une question, réponds sans agir ». Le `CLAUDE.md` du projet, un manuel impératif
   (« **avant d'écrire la moindre ligne**, produis la mini-spec… », « REFUS si… »), est injecté
   **verbatim** (`:2431-2445`) → toute évocation de « plan/feature » amorce une procédure.
4. **`set_plan` amorcé par le mot « plan ».** Sa description (`:2267-2271`) — « Pose ou met à jour ton
   plan… utile dès que le travail dépasse deux ou trois étapes » — ne dit **pas** « seulement si on te
   le demande ». La nuance « outil d'organisation, pas d'exécution » vit dans un commentaire Java, pas
   dans le texte vu par le modèle.
5. **Crochet de fin de tour** (`:862-891`, `MAX_END_OF_TURN_BLOCKS=3`) : si des checkpoints
   `END_OF_TURN` sont configurés pour le projet, un modèle qui croit avoir fini peut être **renvoyé au
   travail** jusqu'à 3 fois. Inerte sans checkpoint, mais aggrave le biais quand présent.

**Ce qui va bien (à ne pas casser)** : la boucle **sait** s'arrêter sur une réponse texte
(`:855`, arrêt si pas de `tool_use`) — une question *peut* recevoir une simple réponse ; rien ne l'y
pousse, tout l'en décourage. La retenue existe déjà, mais **cloisonnée** : Radar (« une question n'est
pas une nouvelle », « avant d'écrire, dis en une phrase ce que tu as compris ») et Pages (« PROPOSE… et
attends. Publie seulement si l'utilisateur l'a demandé »). **La bonne doctrine existe — il faut
l'élever au rang de règle générale.**

## 2. La solution (à l'image de Claude Code)

Claude Code sépare **répondre** d'**agir**, et possède un **mode plan** explicite qui présente un plan
pour approbation *avant* toute modification. On reproduit cet esprit en trois temps, du moins risqué au
plus structurant.

| SF | Titre | Contenu | Priorité |
|---|---|---|---|
| **SF-120-01** | **Doctrine « réponds d'abord, n'agis que sur demande »** | Élever au rôle général (RUNNER + SANDBOX) la retenue déjà présente chez Radar/Pages : répondre à une question en texte ; **ne jamais** écrire/modifier/exécuter une mutation non demandée ; « une question n'est pas un ordre » ; sur une demande ambiguë, **proposer en une phrase et attendre** (« Voulez-vous que je le fasse ? »). Corriger la description de `set_plan` (« n'établis un plan que si l'utilisateur te demande de planifier/d'exécuter, ou si tu vas agir »). Neutraliser la lecture impérative du CLAUDE.md injecté pour le cas « simple question » (préambule cadrant : ces règles s'appliquent *quand tu implémentes*, pas quand on te pose une question). **Zéro UI, effet immédiat.** | **1** |
| **SF-120-02** | **Un mode explicite « Réponse/Plan » vs « Agir »** | Comme le *plan mode* de Claude Code : un sélecteur dans le composer du terminal. En mode **Réponse/Plan**, `AgentTurnRequest` porte le mode ; `buildTools` **retire les outils mutants** (`write_file`, `edit_file`, `set_plan`, bash gardé en lecture seule) et n'expose que lecture/exploration ; le prompt bascule en « tu réponds / tu proposes un plan, tu n'exécutes pas » ; un bouton **« Passer à l'exécution »** (équivalent ExitPlanMode) lève la restriction pour le tour suivant. Déterministe, sans deviner l'intention. Backend + frontend. | **1** |
| **SF-120-03** | **Garde-fou en mode Agir** *(optionnel)* | Même en mode Agir, une **mutation** décidée dans un tour qui répond à une question évidente (message se terminant par « ? », « qu'as-tu compris », « es-tu prêt ») passe par une **proposition** plutôt qu'une exécution directe. Heuristique légère, **faillible** (un ordre mal classé en question retarderait l'action) : à n'ajouter que si SF-120-01+02 ne suffisent pas. | 3 |

**Recommandation** : livrer SF-120-01 (doctrine, rapide, gros effet sur les 53 %) puis SF-120-02 (mode
explicite, le vrai pendant du plan mode). Garder SF-120-03 en réserve — le classifieur d'intention est
le seul mécanisme qui peut se tromper *dans l'autre sens* (ne pas agir quand on le demande), ce qui
serait pire que le mal ; le mode explicite (SF-120-02) donne le même bénéfice **sans deviner**.

## 3. Coordination avec F-119
SF-120-01 et F-119/SF-119-02 modifient tous deux `buildSystemPrompt`. **Livrer F-120 APRÈS F-119**
(séquence, même fichier cœur) pour éviter les conflits. La doctrine de retenue (F-120-01) et la
discipline d'investigation (F-119-02) sont complémentaires : l'une dit *quand* agir, l'autre *comment*
vérifier quand on agit.

## 4. Hors périmètre
- Un classifieur d'intention automatique par défaut (risque de ne pas agir quand on le demande) — écarté
  au profit du mode explicite SF-120-02.
- Refondre le crochet de fin de tour (F-119/checkpoints) : distinct.
- Les retenues de domaine existantes (Radar/Pages/Teams) : conservées telles quelles, on les généralise.

## 5. Préoccupations transversales
- **Navigation** : SF-120-02 ajoute un contrôle au composer (frontend) → vérifier tous les terminaux
  (projet, poste, Teams, mosaïque) et l'état par défaut du sélecteur.
- **Auth / tenant** : non. **Plans / limites** : non (le mode ne change pas les droits, il restreint
  les outils au sein d'un terminal déjà autorisé).
- **Composants** : `AtelierChatService` (mode, prompt, buildTools), `AgentTurnRequest` (champ mode),
  `atelier-terminal.component` + composer (sélecteur, bouton « Passer à l'exécution »), catalogue
  d'outils, `set_plan`.
