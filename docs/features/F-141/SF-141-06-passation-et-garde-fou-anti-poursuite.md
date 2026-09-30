# Mini-spec — F-141 / SF-141-06 Passation vers le sujet + garde-fou anti-poursuite au poste

## Identifiant

`F-141 / SF-141-06`

## Feature parente

`F-141` — L'aiguilleur de sujet à la racine (rouverte le 2026-09-30, **En cours**)

## Statut

`draft` (Cadrée / À faire)

## Date de création

2026-09-30

## Branche Git

`feat/SF-141-06-passation-garde-fou`

---

## Contexte (retour PO, cas réel)

Au terminal du **poste** CAGIP, l'aiguilleur a bien **créé** un sujet (`gitlab-tfstate`, 11:42, via
`create_subject` — SF-141-03). **Mais le travail a continué dans le terminal du poste** (11:52-11:55 :
lecture de code, gestes propres au sujet) **au lieu de basculer dans le nouveau sujet**. Deux conséquences :

1. **Le poste devient un fil marathon** : le contexte enfle à chaque tour → le **plafond de consommation
   par message** est atteint de plus en plus vite (« continue » en boucle). C'est exactement le coût **N²**
   que le cadrage F-141 (§8) et F-134 (cache de prompt) cherchent à éviter — le poste doit rester **léger**.
2. **Le travail du sujet n'est pas rangé là où il doit vivre** : il s'accumule dans le terminal du poste au
   lieu du terminal du sujet (contexte focalisé, bon marché, sans mélange entre sujets/clients).

Le PO veut une **gouvernance** : dès qu'un sujet est **nouvellement créé** au terminal du poste, le système
doit **enjoindre de basculer dans ce sujet** et **quasi interdire de poursuivre le travail de ce sujet dans
le terminal du poste**. À la fin de la création, l'aiguilleur doit dire : **« Rouvre le terminal dans le
sujet \<X\>, et écris \<telle phrase\> pour démarrer le travail. »**

Doctrine sous-jacente (déjà portée par F-141) : **le poste ROUTE, il n'EXÉCUTE pas le travail d'un sujet.**
SF-141-01→05 ont outillé l'entrée (annoncer, aiguiller, créer, reclasser, proposer en question
structurée). SF-141-06 outille la **sortie** : la **passation** après création, et le **garde-fou** qui
empêche que le travail du sujet reflue dans le poste.

---

## Objectif

> En une phrase : après qu'un sujet a été créé/aiguillé au terminal du poste, l'aiguilleur **passe la main
> de façon visible et impossible à rater** (nom du sujet + comment y aller + **phrase de démarrage prête à
> coller**) et **redirige au lieu d'exécuter** si l'utilisateur poursuit le **travail de ce sujet** dans le
> terminal du poste — sans **verrou technique dur**, le poste restant libre pour le routage, le transverse
> et les autres sujets.

---

## Décisions de cadrage

### Curseur du garde-fou : **refus doux avec redirection** (ni verrou dur, ni simple rappel passif)

Trois positions étaient possibles :
- **(a) simple rappel insistant** puis exécuter quand même le travail du sujet au poste — **rejeté** : ne
  casse pas le fil marathon (le plafond continue d'être atteint) et laisse le travail au mauvais endroit ;
- **(b) verrou technique dur** (bloquer l'exécution des outils tant que non basculé) — **rejeté** :
  contredit l'esprit F-141 (« pas un remplacement des terminaux, un complément »), casserait les usages
  légitimes du poste, et le poste doit rester **utilisable** ;
- **(c) refus doux avec redirection** — **retenu**. Sur une **poursuite du travail du sujet** au poste,
  l'aiguilleur **ne l'exécute pas** : il rappelle « ce travail vit dans \<X\>, rouvre-le là-bas », **propose
  la bascule** (phrase de démarrage prête à coller) et **s'arrête là** pour ce geste. Ce n'est **pas** un
  verrou : sur **insistance explicite** de l'utilisateur (« non, fais-le ici quand même »), l'aiguilleur
  **obtempère** mais avec un **caveat d'une ligne** (« le poste va devenir un fil marathon → plafond atteint
  en boucle ; le sujet \<X\> reste le bon endroit »). C'est une **injonction de gouvernance forte** — le
  « quasi interdire » du PO — pas un blocage matériel.

Cohérent avec la mémoire projet : le poste est l'**unité** sans confinement dur ; on ne pose pas de verrou
global qui gênerait un usage légitime (cf. « porte de validation vs policy client »), on **guide fortement**.

### Distinguer « poursuite du travail du sujet » d'un « usage légitime du poste »

Aucun **verrou technique dur**, donc **aucune machine à états persistante** : la distinction est faite par
le **raisonnement du modèle guidé par la doctrine** (prompt), sur des signaux **lisibles dans le fil du
terminal du poste** — au premier chef, le fait qu'un `create_subject` (ou un aiguillage rattachant à un
sujet, SF-141-02/05) **vient d'avoir lieu dans ce même terminal**, pour une **intention identifiée** (le
sujet \<X\>). Signal **conversationnel, non un état verrouillé** : cohérent avec « le tour vit dans le
flux ».

| Situation au terminal du poste | Classe | Comportement |
|---|---|---|
| Après création de \<X\>, l'utilisateur demande à **avancer le travail substantiel de \<X\>** (lire/écrire les fichiers de \<X\>, lancer son build/déploiement, exécuter les étapes de son `PLAN-ACTION.md`) | **Poursuite du travail du sujet** | **Refus doux + redirection** vers le terminal de \<X\> (+ phrase de démarrage) |
| **Router** un fait / classer une entrée / mettre à jour la **carte du poste** (transverse F-136) | **Usage légitime (routage/transverse)** | Exécuter normalement au poste |
| **Infra du poste** : `git clone`, VPN, contrôles réseau/accès, lister les sujets, gestes host-wide | **Usage légitime (infra poste)** | Exécuter normalement au poste |
| Amener un **autre sujet / un sujet nouveau et non lié** | **Usage légitime (nouveau routage)** | Aiguiller (SF-141-05) — éventuellement créer un autre sujet, puis **repasser la main** (même passation) |
| L'utilisateur **insiste explicitement** pour exécuter \<X\> au poste | **Override explicite** | Obtempérer **avec caveat d'une ligne** (marathon → plafond) |

Règle de tranche : est « travail du sujet » l'**exécution substantielle** propre à \<X\> (avancer son plan,
manipuler son code/ses artefacts). Restent **légitimes** au poste le **routage**, le **transverse**,
l'**infra du poste** et le **routage d'autres sujets**. En cas de doute réel, l'aiguilleur **redirige en
proposant** (il n'invente pas un blocage) et l'option libre / la correction de l'utilisateur tranche —
**discipline anti-spam** : ne pas répéter l'injonction à chaque message.

---

## Comportement attendu

### Cas nominal — (1) Passation explicite après création

1. Au **terminal du poste** (`isHostTerminal()`), après un `create_subject` (SF-141-03) réussi — ou un
   rattachement à un sujet existant (SF-141-02/05) — l'aiguilleur **termine par une passation VISIBLE**,
   portant trois éléments :
   - le **nom du sujet** \<X\> créé/retenu ;
   - **comment y aller** : « **rouvre le terminal dans le sujet \<X\>** » ;
   - une **phrase de démarrage prête à coller**, résumant l'**intention**, pour lancer le travail **dans le
     sujet** — ex. « Reprends LDIC-223 : active le Terraform State sur GitLab CAPFM — commence par lire le
     repo GitOps ».
2. **Présentation privilégiée via l'outil `demander` (F-164)** : une question « Ouvrir le sujet \<X\> ? »
   avec options **« Ouvrir le sujet \<X\> »** (recommandée) / **« Plus tard »** + option libre systématique
   — visible, tapable, **impossible à rater** (rendu SF-164-02, pause/reprise F-84, cross-device). La
   **phrase de démarrage** est donnée dans le corps de la question / la réponse, **prête à coller**.
   - Sur **« Ouvrir le sujet \<X\> »** : l'aiguilleur **redonne** l'instruction de bascule + la phrase de
     démarrage (il **ne dépose plus de travail au poste** pour ce sujet).
   - Sur **« Plus tard »** / réponse libre : il **acquitte** et rappelle en une ligne que le travail de \<X\>
     vit dans son terminal.
3. Repli **prose** si `demander` indisponible ou décider-par-défaut (vague autonome) : la passation reste
   **émise en clair** (nom + « rouvre le terminal dans \<X\> » + phrase de démarrage) — jamais silencieuse.

### Cas nominal — (2) Garde-fou anti-poursuite

4. Si, **après** la création/l'aiguillage de \<X\> **dans ce même terminal du poste**, l'utilisateur demande
   à **avancer le travail substantiel de \<X\>** au poste, l'aiguilleur applique le **refus doux** :
   - il **n'exécute pas** le geste (n'appelle pas les outils d'exécution du sujet au poste) ;
   - il **redirige** : « ce travail vit dans \<X\> — rouvre le terminal là-bas » et **propose la bascule**
     (phrase de démarrage prête à coller), idéalement via `demander` (mêmes options qu'au §2) ;
   - il **reste disponible** pour le routage, le transverse et les autres sujets.
5. **Override explicite** : si l'utilisateur insiste (« fais-le ici quand même »), l'aiguilleur
   **obtempère** avec un **caveat d'une ligne** (marathon → plafond ; \<X\> reste le bon endroit). Pas de
   verrou.
6. **Non-régression F-125** : la passation et l'injonction portent la **destination / la gouvernance**
   (information utile au PO) — **pas de plomberie narrée** (marqueurs, promotion/dette restent en coulisse,
   strip `fin-de-tour` intact).

### Cas d'erreur / limites

| Situation | Comportement attendu |
|-----------|----------------------|
| Terminal de **projet/sujet** (pas la racine) | **Aucune** passation ni garde-fou : dans un sujet, travailler le sujet **est** légitime. Doctrine **scopée** `isHostTerminal()` (comme SF-141-02/03/05) → préfixe court hors racine, cache F-134 préservé. |
| Terminal **Teams** / hébergé | Hors périmètre : la doctrine ne s'injecte qu'au terminal du poste. |
| `create_subject` a **échoué** (doublon, nom vide — SF-141-03) | **Pas** de passation « ouvre \<X\> » : rien n'a été créé ; l'aiguilleur suit le message d'erreur SF-141-03 (« existe déjà, dépose dedans / reclasse »). |
| `demander` indisponible / vague autonome (personne au clavier) | Repli **prose** : passation émise en clair ; le garde-fou **redirige en clair** (pas d'attente bloquante). La décision par défaut de la question éventuelle **délègue** à F-164-03. |
| Doute réel poursuite-vs-légitime | **Redirige en proposant** (jamais un blocage inventé) ; l'utilisateur tranche par la réponse libre. **Anti-spam** : ne pas répéter à chaque tour. |
| Aucun sujet n'a été créé/aiguillé récemment dans ce terminal | **Pas** de garde-fou : sans intention de sujet identifiée, tout geste au poste est traité comme routage/infra légitime. |

---

## Critères d'acceptation

- [ ] Après un `create_subject` réussi (ou un rattachement à un sujet) **au terminal du poste**, la doctrine
      **impose une passation visible** : **nom du sujet** + **« rouvre le terminal dans \<X\> »** + **phrase
      de démarrage prête à coller** résumant l'intention.
- [ ] La passation est **présentée via `demander`** (F-164) quand disponible — options « Ouvrir le sujet
      \<X\> » (recommandée) / « Plus tard » + option libre — avec **repli prose** en clair (jamais
      silencieuse) sinon.
- [ ] La doctrine énonce le **garde-fou anti-poursuite** : sur une **poursuite du travail substantiel de
      \<X\>** au poste, l'aiguilleur **redirige au lieu d'exécuter** (refus doux) et **propose la bascule**.
- [ ] La doctrine **distingue explicitement** poursuite-du-sujet (exécution substantielle de \<X\>) des
      **usages légitimes du poste** (routage, transverse/carte, infra poste : git/VPN/accès, routage d'un
      autre sujet) — ces derniers **ne sont pas bloqués**.
- [ ] **Pas de verrou technique dur** : sur **override explicite**, l'aiguilleur **obtempère avec un caveat
      d'une ligne** ; aucune machine à états persistante, aucun blocage matériel des outils.
- [ ] Doctrine **scopée `isHostTerminal()`** : **absente** sur un terminal de projet ordinaire / SANDBOX /
      hébergé (préfixe court → cache F-134 préservé).
- [ ] **Non-régression** : SF-141-01 (annonce destination), SF-141-02 (découverte/classement), SF-141-03
      (`create_subject` idempotent, jamais d'écrasement), SF-141-04 (`reclass_entry`), SF-141-05 (aiguillage
      via `demander`), F-125 (carte silencieuse, strip `fin-de-tour`), F-164 (`demander` : schéma 1-4, option
      libre, recommandée, pauses répétées, décider-par-défaut F-164-03) restent intacts.
- [ ] **Isolation** : aucun **nouveau** chemin de résolution de tenant. La doctrine est construite sur
      `requireOwned` (`user_id` + workspace possédé) ; la passation/le garde-fou réutilisent les chemins du
      poste possédé (`user_id`+`host_id`) et `demander` (isolation `/chat/answer` déjà en place). **Aucun**
      accès cross-tenant introduit.

---

## Périmètre

### Hors scope (explicite)

- **Aucun verrou technique dur** : pas de blocage matériel des outils d'exécution, pas d'interdiction
  applicative, pas de machine à états persistante « sujet en cours au poste ». Injonction de gouvernance
  par **prompt/doctrine** uniquement.
- **Auto-navigation / deep-link** « ouvrir le sujet \<X\> » (bascule automatique du terminal vers le
  workspace du sujet) : **hors périmètre**. La passation **dit comment** rouvrir le terminal dans \<X\> ;
  l'ouverture reste un geste de l'utilisateur. Un raccourci de navigation serait une **subfeature front
  ultérieure** (elle toucherait le routing → analyse d'impact transversale dédiée). Pas de route front ici.
- La **création** du sujet (SF-141-03), la **découverte/le classement** (SF-141-02), l'**aiguillage via
  `demander`** (SF-141-05), le **rendu de la carte de question** (SF-164-02), la **politique
  décider-par-défaut** (F-164-03) : **réutilisés**, **pas** re-spécifiés.
- L'**appui sémantique** (recall/embeddings F-162) évoqué dans SF-141-05 comme « ex. SF-141-06 » n'est
  **PAS** cette subfeature : il reste **documenté et hors périmètre**, à ouvrir sous **un autre numéro** si
  mesuré nécessaire. SF-141-06 est réattribuée à la **passation + garde-fou** (besoin PO prioritaire).

---

## Technique

### Endpoint(s)

Aucun endpoint HTTP nouveau. `demander` réutilise `POST /workspaces/{id}/chat/answer` (F-164) inchangé.

### Tables impactées

Aucune. **Aucune** migration Liquibase.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants impactés

- `AtelierChatService` — **évolution de la doctrine d'aiguillage** injectée **uniquement** au terminal du
  poste (au voisinage de `SUBJECT_ROUTING_DOCTRINE` / `DESTINATION_ANNOUNCE_DOCTRINE`) : ajouter la règle de
  **passation après création/aiguillage** (nom + « rouvre le terminal dans \<X\> » + phrase de démarrage,
  via `demander` de préférence) et la règle de **garde-fou anti-poursuite** (refus doux + redirection +
  override caveaté ; distinction poursuite-vs-légitime). **Prompt/doctrine** — pas de nouvelle branche
  d'exécution d'outil.
- **Réutilisés sans modification** : outil `demander` + pause F-84 + `/chat/answer` (F-164) ; outil
  `create_subject` (SF-141-03) ; découverte des sujets (SF-141-02) ; `isHostTerminal()`.
- **Aucun** nouvel outil, **aucun** nouvel outil runner, **aucune** mise à jour du binaire runner.

### Composants Angular

Aucun. Le rendu de la carte de question (`demander`) est **déjà** livré (SF-164-02). Aucune route front,
aucun guard.

---

## Préoccupations transversales

- **Auth / Principal** : inchangé.
- **Contexte tenant** : **aucun** nouveau chemin de résolution de tenant. La doctrine est bâtie sur le
  workspace possédé (`requireOwned`, `user_id`+`host_id`) ; la passation et le garde-fou réutilisent
  `create_subject` (isolé `user_id`+`host_id`) et `demander` (isolé `user_id` + propriété workspace).
  Composants qui résolvent le tenant ici : `buildSystemPrompt` (injection conditionnelle, inchangée) et les
  outils réutilisés — **aucun** autre touché.
- **Plans / limites** : inchangé — mais **effet positif visé** : garder le poste léger repousse le
  **plafond de consommation par message** (moins de N², cache F-134 préservé).
- **Navigation / routing** : **aucune** route front modifiée (auto-navigation hors scope, cf. Périmètre).
  Préoccupation **cochée sans impact** : liste des composants navigation touchés = **∅** (rien à vérifier
  côté routing).

---

## Plan de test

### Tests unitaires (prompt/doctrine)

- [ ] `AtelierChatServiceSystemPromptTest` — **racine** : la doctrine de **passation** est présente au
      terminal du poste (nom du sujet + « rouvre le terminal dans \<X\> » + phrase de démarrage prête à
      coller, présentation `demander`).
- [ ] `AtelierChatServiceSystemPromptTest` — **racine** : la doctrine de **garde-fou anti-poursuite** est
      présente (refus doux + redirection ; distinction poursuite-vs-usage-légitime ; override caveaté ; pas
      de verrou dur).
- [ ] `AtelierChatServiceSystemPromptTest` — **projet ordinaire / SANDBOX / hébergé** : passation et
      garde-fou **absents** (scope `isHostTerminal()`).
- [ ] Non-régression prompt : `DESTINATION_ANNOUNCE_DOCTRINE` (SF-141-01), `SUBJECT_ROUTING_DOCTRINE`
      (SF-141-02/05), la carte silencieuse F-125 (`Tenue de la carte, en silence`) restent présents et
      inchangés.

### Tests d'intégration

- [ ] Réutilisés/cités, **non redupliqués** : `create_subject` (SF-141-03), `demander` + `/chat/answer`
      (F-164), décider-par-défaut (F-164-03). SF-141-06 n'ajoute **aucun** endpoint ni outil → pas de
      nouveau test d'intégration HTTP.

### Isolation

- [x] Applicable — vérifiée par **réutilisation** : `create_subject` isole `user_id`+`host_id` ;
      `/chat/answer` isole `user_id` + propriété workspace (404 sur projet d'autrui). Aucun accès
      cross-tenant introduit (doctrine seule).
- [ ] Non applicable

---

## Dépendances

### Subfeatures / features bloquantes

- **SF-141-03** (`create_subject` gouverné) — statut : **done**. Point d'accroche de la passation.
- **SF-141-05** (aiguillage proactif via `demander`) — statut : **draft (Cadrée / À faire)**. SF-141-06 est
  **cohérente** avec elle (même mécanisme `demander`, même scope racine) et s'y **enchaîne** logiquement (la
  passation suit le rattachement/la création décidés par SF-141-05). Peut être livrée après SF-141-05.
- **F-164** (`demander`, SF-164-01/02) — statut : **done/déployé**. Fournit la question structurée et la
  pause/reprise (F-84) réutilisées.
- **F-164-03** (décider-par-défaut + flag) — **aligné** : le repli sans réponse **délègue** à cette
  politique, non réimplémenté.

### Questions ouvertes impactées

- [ ] Aucune de `docs/OPEN_QUESTIONS.md` rouverte. Le curseur du garde-fou (**refus doux**, non verrou dur)
      et la distinction poursuite-vs-légitime sont **tranchés** dans le § Décisions de cadrage.

---

## Cohérence architecture

- **`ARCHITECTURE_CANONIQUE.md`** : **aucune incohérence**. SF-141-06 est **prompt/doctrine + réutilisation**
  d'outils et d'un mécanisme existants ; **aucune table, aucun endpoint, aucune migration, aucune mise à
  jour runner** → rien à refléter dans le modèle de données canonique.
- **Gateway-First / Provider-First** : l'aiguilleur **raisonne** (poursuite ou routage ?) et **relaie** via
  un mécanisme d'**interaction** (`demander`) et de gouvernance — **aucun moteur IA**, **aucun moteur de
  décision maison** persistant, **aucun verrou applicatif**. Le poste **route**, il n'**exécute** pas le
  travail d'un sujet.

---

## Notes et décisions

- **Curseur retenu** : **refus doux avec redirection** (option c), **overridable explicitement** avec caveat
  d'une ligne. Ni simple rappel (n'endigue pas le marathon), ni verrou dur (casse les usages légitimes,
  contredit F-141 §5).
- **Distinction** faite par **raisonnement du modèle guidé par la doctrine**, sur signal **conversationnel**
  (un `create_subject`/aiguillage vient d'avoir lieu dans ce terminal, pour l'intention \<X\>) — **pas** de
  machine à états persistante, cohérent avec « le tour vit dans le flux » et « pas de confinement dur ».
- **Effet économique** visé : garder le poste léger (le travail lourd bascule dans le terminal du sujet →
  contexte focalisé) repousse le **plafond de consommation par message** (moins de N², cache F-134
  préservé). C'est le motif premier du besoin PO.
- **Réattribution du numéro** : SF-141-05 citait « ex. SF-141-06 » pour un éventuel **appui sémantique** ;
  ce dernier reste **hors périmètre** et prendra **un autre numéro** si ouvert. SF-141-06 sert le besoin PO
  prioritaire (passation + garde-fou).

## Références

- Cadrage F-141 : `docs/features/F-141/CADRAGE-F-141-aiguilleur-de-sujet-a-la-racine.md` (§4 comportement,
  §5 « pas un remplacement des terminaux par sujet », §8 coût).
- `docs/features/F-141/SF-141-03-creer-sujet.md` (`create_subject`), `SF-141-05-aiguillage-proactif-choix-structure.md`
  (aiguillage via `demander`), `SF-141-01`/`SF-141-02`/`SF-141-04`.
- F-164 (`demander`, pause F-84, option libre/recommandée, décider-par-défaut F-164-03).
- F-125 (carte silencieuse), F-134 (cache de prompt), F-136 (carte du poste), F-99 (sujets Radar/suivis),
  F-162 (recall/embeddings — appui sémantique optionnel, hors périmètre).
