# Mini-spec — F-141 / SF-141-05 Aiguillage proactif à choix structuré (synergie F-141 × F-164)

## Identifiant
`F-141 / SF-141-05`

## Feature parente
`F-141` — L'aiguilleur de sujet à la racine (rouverte le 2026-09-30 pour cette subfeature)

## Statut
`draft`

## Date de création
2026-09-30

## Branche Git
`feat/SF-141-05-aiguillage-proactif-choix-structure`

---

## Contexte (retour PO, cas réel)
Au terminal du **poste** CAGIP, le PO colle un nouveau sujet (ticket **LDIC-223**, activer le Terraform
State sur GitLab CAPFM). L'aiguilleur (SF-141-02) a résumé le sujet puis a fini par une phrase en prose :
« **Avant d'écrire quoi que ce soit, je te demande où ranger.** » Le PO juge la question **beaucoup trop
timide** — « j'ai failli louper ce message ». Il attend que l'aiguilleur :
1. **se rende compte** de la destination probable et **PROPOSE** activement où ranger (des **options**, des
   **candidats de sujets existants**), au lieu de poser une question ouverte en prose ;
2. exemple : LDIC-223, il l'aurait **vu dans `cloudops-run`** → l'aiguilleur aurait dû **proposer
   `cloudops-run`** (ou d'autres candidats) ;
3. via le **nouveau mécanisme de question structurée** (outil `demander`, F-164, déployé) → visible,
   options tapables, **impossible à rater**.

SF-141-02 a déjà donné à l'aiguilleur la capacité de **classer et proposer** (existant / transverse /
nouveau / mix) et d'**attendre la validation** ; SF-141-03 crée le sujet gouverné sur « oui, nouveau
sujet ». Ce que SF-141-02 n'a pas : la proposition passe encore par la **prose** (ratable) et n'est pas
présentée comme des **choix concrets, classés, recommandés**. SF-141-05 comble exactement ce trou en
branchant l'aiguillage sur `demander`.

---

## Objectif
> En une phrase : à la racine (terminal du poste), quand l'aiguilleur détecte une entrée à ranger, il
> **pose une question structurée** (outil `demander`, F-164) **au lieu d'une phrase en prose** — proposant
> des **destinations concrètes classées** (sujet(s) existant(s) qui matchent, chacun avec une raison
> courte ; « Nouveau sujet : \<nom déduit\> » ; Transverse ; option libre), la plus probable marquée
> **recommandée** — de sorte que le choix soit visible, tapable et impossible à rater.

---

## Comportement attendu

### Cas nominal
1. Le PO amène un **nouveau sujet / une info floue** au **terminal du poste** (racine, `isHostTerminal()`).
2. L'aiguilleur **découvre les sujets existants** — **exactement comme SF-141-02** : dossiers projet sous
   la racine (`bash`/`list_files`), sommaire de la carte du poste (F-136), sujets Radar/suivis (F-99) —
   avec leurs thèmes/descriptions (STATE.md / PLAN-ACTION.md).
3. Au lieu de proposer en prose, il **appelle l'outil `demander`** (F-164) avec **une question** « Où ranger
   ce sujet ? » et des **options concrètes classées** :
   - **(a) sujet(s) existant(s) qui matchent** : une option par candidat, **libellée avec une raison
     courte** — ex. « `cloudops-run` — même périmètre run/infra CAGIP » ;
   - **(b) « Nouveau sujet : \<nom proposé\> »** — un nom déduit du contenu ;
   - **(c) Transverse** — si ça concerne plusieurs sujets / la carte du poste ;
   - **(d) option libre** « autre / je te dis » — **toujours** présente (garantie F-164 : réponse libre
     systématique).
   L'**option la plus probable** (meilleur match, sinon « Nouveau sujet ») est marquée **recommandée**
   (mécanisme d'option recommandée de `demander`).
4. Le tour **se met en pause** (porte F-84 généralisée par F-164) ; la question s'affiche en **carte
   tapable** (rendu SF-164-02), répondable **depuis n'importe quel appareil** (cross-device F-84).
5. Sur le **choix** de l'utilisateur, l'aiguilleur **route** :
   - option (a) → **rattache au sujet existant** choisi (dépose l'info via les outils d'écriture de carte
     existants — chemin SF-141-02/04) ;
   - option (b) → **crée le sujet gouverné** via l'outil `create_subject` (**SF-141-03**, inchangé), puis
     y dépose l'info ;
   - option (c) → **transverse** : range dans la carte du poste (chemin SF-141-02) ;
   - option (d) / réponse libre → suit l'instruction du PO (route, crée, ou re-demande si encore flou).
6. Il **annonce la destination** retenue (SF-141-01) — « rangé dans `cloudops-run/PLAN-ACTION.md` ».

### Décider-par-défaut (non-attendu : vague autonome / personne au clavier)
Aligné sur la **politique F-164-03** (décider-par-défaut + flag) : si personne ne peut répondre
(timeout / vague autonome / détaché sans reprise), l'aiguilleur **prend l'option recommandée** (meilleur
match, sinon « Nouveau sujet : \<nom déduit\> ») et **flague** la décision (trace visible du choix par
défaut), pour ne jamais **figer** un tour autonome ni ranger en silence sans marque. SF-141-05
**réutilise** ce comportement fourni par `demander` — elle ne le réimplémente pas.

### Cas d'erreur / limites
| Situation | Comportement attendu |
|-----------|----------------------|
| Terminal de **projet** (pas la racine) | Pas d'aiguillage ni de `demander` de routage : dans un sujet le routage n'a aucune ambiguïté (cadrage §5). La consigne reste **scopée** `isHostTerminal()` (comme SF-141-02). |
| **Aucun** sujet existant ne matche | La question ne propose que **« Nouveau sujet : \<nom\> »** (recommandé) + **Transverse** + option libre — jamais un candidat inventé. |
| Racine muette / liste des sujets indisponible | Proposer au mieux avec ce qui est connu (carte + Radar) ; si rien n'est connu → « Nouveau sujet » + Transverse + libre, sans inventer de dossier existant. |
| Trop de candidats (> 4, plafond `demander`) | Ne présenter que les **meilleurs candidats** (le lot `demander` est borné à 1-4 options utiles) + « Nouveau sujet » + libre ; les autres restent atteignables par la réponse libre. |
| Entrée manifestement triviale / pas un fait durable | Pas de question (discipline anti-spam F-164 : ne demander que si vraiment un rangement est en jeu). |
| L'utilisateur choisit « Nouveau sujet » mais le dossier existe déjà | Comportement **inchangé SF-141-03** : `create_subject` refuse le doublon (« existe déjà, dépose dedans ou reclasse »), **rien n'est écrasé**. |

---

## Critères d'acceptation
- Au **terminal du poste**, quand une entrée est à ranger, la consigne **impose l'outil `demander`**
  (question structurée) pour proposer la destination — **la prose est proscrite** pour ce choix (règle par
  défaut obligatoire F-164 : une question à réponses proposables passe par l'outil structuré, jamais la
  prose).
- La question **propose des destinations concrètes classées** : (a) sujet(s) existant(s) qui matchent,
  **chacun avec une raison courte** ; (b) « Nouveau sujet : \<nom déduit\> » ; (c) Transverse ; (d) option
  libre systématique. **L'option la plus probable est marquée recommandée.**
- La consigne dit d'**identifier et classer les sujets existants** (liste racine, carte du poste, sujets
  Radar/suivis) **avant** de bâtir les options — réutilise la découverte SF-141-02, **sans** nouvel outil.
- Sur le choix : rattachement au sujet existant, **création via `create_subject` (SF-141-03)**, ou
  transverse — **aucun chemin de rangement dupliqué**.
- **Décider-par-défaut** : en l'absence de réponse (timeout / vague autonome), l'option **recommandée** est
  prise **et flaggée** — comportement **délégué** à `demander` (politique F-164-03), non réimplémenté.
- Sur un terminal de **projet** ordinaire, l'aiguillage à choix structuré **n'apparaît pas** (scope
  `isHostTerminal()`, préfixe court → cache F-134 préservé).
- **Non-régression** : SF-141-01 (annonce de destination), SF-141-02 (découverte + classement), SF-141-03
  (`create_subject` idempotent), SF-141-04 (reclasser), F-125 (carte silencieuse, strip `fin-de-tour`),
  F-164 (`demander` : schéma 1-4, option libre, recommandée, pauses répétées) restent intacts.

---

## Mécanisme de matching (décision de cadrage)
**Retenu — démarrage simple : raisonnement du modèle sur les sujets existants.**
- L'aiguilleur **raisonne** sur la **liste des sujets du poste** — déjà découverte par SF-141-02 (dossiers
  sous la racine via `bash`/`list_files`, sommaire de la carte F-136, sujets Radar/suivis F-99) — et sur
  leurs **thèmes/descriptions** (STATE.md / PLAN-ACTION.md). Il **classe** les candidats par pertinence,
  attache à chacun une **raison courte**, et marque le meilleur **recommandé**.
- **Aucun** nouvel index, **aucun** embedding, **aucune** table : c'est du **prompt/doctrine** + les outils
  existants. Gateway-First / Provider-First respectés (l'aiguilleur **raisonne et relaie** — il ne bâtit
  aucun moteur de matching maison).

**Appui sémantique — documenté, hors périmètre SF-141-05.**
- Si le raisonnement seul se révèle insuffisant (poste avec **beaucoup** de sujets, noms de dossiers peu
  parlants, candidats manqués), un **appui sémantique** est possible : brancher **`recall` / embeddings
  F-162** (déjà livré, `text-embedding-3-small` + pgvector cosine, isolé `user_id`+`workspace_id`) sur
  l'**historique des sujets** pour remonter le bon candidat.
- **Décision** : **on commence simple** (raisonnement du modèle) et on **mesure**. L'appui sémantique
  n'est **ouvert que si nécessaire**, dans une **subfeature ultérieure** (ex. SF-141-06), pas ici — pour
  ne pas ajouter de complexité avant preuve du besoin.

---

## Plan de test minimal
- **Unitaire (prompt) — racine** : `theProactiveStructuredRoutingDoctrineIsPresentOnTheHostTerminal` — au
  terminal du poste, la consigne impose `demander` pour le routage, avec options classées (existant +
  raison / nouveau sujet nommé / transverse / libre) et option recommandée.
- **Unitaire (prompt) — proscription prose** : la consigne dit explicitement de **ne pas** poser la
  question de routage en **prose** quand des candidats sont proposables (règle par défaut F-164).
- **Unitaire (prompt) — projet ordinaire** : `theProactiveStructuredRoutingDoctrineIsAbsentOnAnOrdinaryProject`
  — absente sur projet RUNNER ordinaire et SANDBOX (scope `isHostTerminal()`).
- **Non-régression** : SF-141-01 (`Dis où tu ranges…`), SF-141-02 (`SUBJECT_ROUTING_DOCTRINE` : découverte
  + classement), F-125 (`Tenue de la carte, en silence`) présents ; `create_subject` (SF-141-03) et
  `reclass_entry` (SF-141-04) inchangés ; `demander` (F-164) inchangé.
- **Décider-par-défaut** : couvert par les tests **F-164-03** (option recommandée + flag) — **cité, non
  redupliqué** ; SF-141-05 vérifie seulement que l'option recommandée est **fournie** à `demander`.
- **Isolation** : inchangée — prompt/doctrine uniquement ; la consigne est construite sur `requireOwned`
  (`user_id` + workspace possédé), la découverte passe par les outils du poste possédé
  (`user_id`+`host_id`), `demander` isole déjà `user_id` + propriété workspace sur `/chat/answer`
  (SF-164-01). **Aucun** accès cross-tenant introduit.

---

## Tables / endpoints / composants impactés
- `AtelierChatService` : **évolution de la consigne d'aiguillage** (`SUBJECT_ROUTING_DOCTRINE`, SF-141-02)
  — au terminal du poste, imposer `demander` avec options classées + recommandée, et le mapping du choix
  vers rattachement / `create_subject` / transverse. Prompt/doctrine.
- **Réutilisés sans modification** : outil `demander` + porte F-84 généralisée + endpoint `/chat/answer`
  (F-164) ; outil `create_subject` (SF-141-03) ; découverte des sujets (`bash`/`list_files`, carte F-136,
  Radar F-99) ; écriture des cartes (SF-141-02/04).
- **Aucune** table, **aucun** endpoint HTTP nouveau, **aucune** migration, **aucune** mise à jour runner.

## Préoccupations transversales
- **Auth / Principal** : inchangé.
- **Contexte tenant** : aucun **nouveau** chemin de résolution de tenant. La découverte et le rangement
  passent par le poste possédé (`requireOwned`, `user_id`+`host_id`) ; `demander` isole déjà `user_id` +
  propriété workspace. Composants qui résolvent le tenant ici : `buildSystemPrompt` (injection
  conditionnelle, inchangée) et les outils réutilisés — **aucun** autre touché.
- **Navigation / routing** : aucune route front (le rendu de la carte de question est **déjà** livré,
  SF-164-02).
- **Coût / cache** : consigne **scopée** `isHostTerminal()` (préfixe court hors racine → cache F-134
  préservé) ; l'aiguilleur **route/propose** — il ne fait pas tenir tout le contexte à la racine (pas de
  N²). Le travail profond reste dans le terminal du sujet.

## Dépendances
### Subfeatures / features bloquantes
- **F-164 (`demander`) déployé** — SF-164-01 (outil + pause/reprise) et SF-164-02 (rendu terminal
  desktop+mobile) : **statut livré**. Fournit le mécanisme de question structurée réutilisé ici.
- **F-164-03** (décider-par-défaut + flag + push) : politique **alignée**. La décision par défaut de
  SF-141-05 **délègue** à ce mécanisme F-164 ; SF-141-05 ne fait que fournir l'option recommandée.
- **SF-141-02** (aiguillage : découverte + classement) et **SF-141-03** (`create_subject` gouverné) :
  **livrées**. Réutilisées telles quelles (SF-141-05 ne duplique ni la découverte ni la création).
- **F-162** (recall / embeddings) : **livrée**, mobilisée **seulement** si l'appui sémantique est ouvert
  ultérieurement (hors périmètre ici).

### Questions ouvertes impactées
- Aucune de `docs/OPEN_QUESTIONS.md` rouverte. Le choix « démarrer simple, sémantique plus tard » est
  tranché dans le § Mécanisme de matching.

## Cohérence architecture
- **`ARCHITECTURE_CANONIQUE.md`** : aucune incohérence. SF-141-05 est **prompt/doctrine + réutilisation**
  d'outils et d'un mécanisme existants ; **aucune table, aucun endpoint, aucune migration** → rien à
  refléter dans le modèle de données canonique.
- **Gateway-First / Provider-First** : l'aiguilleur **raisonne** sur les sujets connus et **relaie** via un
  mécanisme d'**interaction** (`demander`) — **pas de moteur IA maison**, pas de moteur de matching maison.

## Hors périmètre
- **L'appui sémantique** (recall / embeddings F-162 sur l'historique des sujets) → subfeature ultérieure
  si mesuré nécessaire (voir § Mécanisme de matching).
- La **création** effective du dossier + gouvernance (SF-141-03) et le **reclassement** (SF-141-04) :
  réutilisés, **pas** re-spécifiés ici.
- Le **rendu** de la carte de question (SF-164-02) et la **politique décider-par-défaut** (SF-164-03) :
  fournis par F-164, **pas** réimplémentés.
- Toute question hors racine / inter-projets ; toute refonte de l'UX du terminal.

## Références
- Cadrage F-141 : `docs/features/F-141/CADRAGE-F-141-aiguilleur-de-sujet-a-la-racine.md` (§4.1 aiguillage,
  §5 pas de routage dans un sujet).
- SF-141-02 (découverte + classement), SF-141-03 (`create_subject`), SF-141-01 (annonce de destination),
  SF-141-04 (reclasser).
- F-164 (`demander`, pause F-84, option libre/recommandée, décider-par-défaut F-164-03).
- F-162 (recall / embeddings — appui sémantique optionnel), F-99 (sujets Radar), F-136 (carte du poste),
  F-125 (carte silencieuse), F-134 (cache de prompt).
