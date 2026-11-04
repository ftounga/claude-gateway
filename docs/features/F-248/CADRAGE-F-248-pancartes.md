# CADRAGE F-248 — Les pancartes : Jarvis montre ce dont il parle

**Statut :** cadrage du 2026-11-05 (besoin PO du même jour). Aucun code.
Famille « Jarvis » : F-230 → F-247, **F-248**. Base vérifiée : `origin/main` `a0a40a3d`.
**Dépend de F-247** (registre d'état, cadré) pour les faits du poste ; le socle se livre avant, sur les faits déjà
lus aujourd'hui (§8).
**Estimation :** 10 SF, ≈ 15,5 j, aucune au-delà de 2 j.

---

## 1. Le besoin (PO, 2026-11-05)

Quand le PO parle à Jarvis de faits que l'application connaît — tickets, rendez-vous, accès et moyens de connexion,
personnes, résultats de commandes ou de journaux déjà au registre, MR et pipelines, déploiements, courriels —, Jarvis
fait apparaître à l'écran, façon film, des **pancartes** :

- **un seul composant générique** de l'application, pas un composant par type de fait ;
- dynamique, en 3D, avec zoom ;
- **une pancarte par élément** (3 tickets → 3 pancartes ; un seul → un bloc) ;
- nom, détail, lien rapide ; cliquable (plein écran, grand écran) ;
- surtout sur PC, aussi sur téléphone.

**Exigence forte : zéro ralentissement.** Jarvis vocal doit rester fluide (F-235, F-239, F-247 Z13).

## 2. L'existant, vérifié dans le code

| Brique | Ce qu'elle fait déjà | Ce que F-248 en fait |
|---|---|---|
| **Galerie holographique F-237** (`jarvis-piece-gallery.component.ts`, `jarvis-piece-fan.ts`, `jarvis-piece-nav.ts`) | Éventail CSS 3D au-dessus de la scène (arc 110°, ≤ 12 visibles, héros qui avance), grille en repli 2D, **téléphone : une pièce plein écran** au balayage ; réducteur pur `PieceNav` (`openGallery` accepte des pièces **données**, `select`, `next`, `close`, `zoom`, plein écran) ; rien en présentation. | **Réutilisée telle quelle** : les pancartes sont un **mode** de cette galerie, pas un nouvel écran (D1). |
| **Registre des visionneuses** `PIECE_VIEWERS` (+ garde `kindsWithoutViewer`) | Une visionneuse par type de pièce, remplaçable. | Un type `FAIT` et une visionneuse `pancarte` y entrent. |
| **Ordres locaux sans IA** (`jarvis-piece-voice.ts`) | « la deuxième », « suivante », « ferme », « zoome »… liste fermée, phrase entière, < 100 ms, 0 €. | Étendus d'une poignée de formes (« montre le deuxième », « ferme ça », « compare le 1 et le 3 »). |
| **Avant / après** (`jarvis-piece-compare.component.ts`) | Deux pièces côte à côte. | Comparer deux pancartes. |
| **Outil `afficher`** + `JarvisShowCommand` + événement SSE `show` | L'écran reçoit un ordre validé par la passerelle. `agendaView` : **la passerelle** ouvre d'elle-même la vue d'agenda quand des rendez-vous ont été lus — patron exact du choix déterministe. | `afficher selection/fermer/comparer` agit sur les pancartes **sans changer l'outil** (le bloc `## Écran` décrit déjà la galerie ouverte) : préfixe intact. |
| **Synchronisation voix** (`sayingHooks` → `scenes.saying(sentence)` sur `onPlaying`, SF-234-11) | Chaque phrase **au moment où elle est jouée** allume les noms qu'elle contient (`jarvis-name-matcher.ts`, prudent). | Le même crochet avance la pancarte de l'élément prononcé. |
| **Panneaux holographiques F-236** (`jarvis-panels.ts`, `jarvis-panels-3d.ts`, `jarvis-panels-style.ts`) | Placement hors cœur, hors sous-titres, pool borné (PC 8, téléphone 2) ; verre holographique (§23). | Le style de verre est repris ; le placement évite les mêmes zones réservées. |
| **Mur de tickets** (SF-236-18, `ticketWall`, `jarvis-session-memory.ts`) et **personnes et attentes** (`peopleMap`) | Tickets vus pendant la session, groupés par poste. | Inchangés ; toucher un ticket du mur ouvre sa pancarte s'il est connu. |
| **`maskSecrets`** (`jarvis-scene-reader.ts`) | Masque les secrets dans ce que les scènes affichent. | Filet côté écran, **en plus** de la liste blanche côté passerelle (§4.6). |
| **Visionneuse de courriel F-242** (`jarvis-mail-card.component.ts`) et **vue d'agenda F-243** (`jarvis-agenda.component.ts`) | Détail d'un courriel (texte seul, jamais de HTML) ; frise jour / semaine. | **Restent** ; elles deviennent le **plein écran** de leurs gabarits (D9). |
| **Cockpit écran externe** (SF-236-24, signaux relayés) | Une seconde fenêtre qui dessine ce que l'écran principal lui relaie. | « Ouvrir sur le grand écran » envoie la pancarte au cockpit. |
| **Mode présentation** (SF-236-23) | Initiales, ni coût, ni détail ; galerie absente. | Aucune pancarte (et, par construction, aucun fait lu : F-247 ne joint ni bloc ni `etat` en présentation). |
| **Mesure** (`RenderCounts.KEYS`, `PIECE_COUNT_KEYS`, `jarvis_usage_days.render_counts`, `jarvis_turn_timings`) | Compteurs par jour, vocabulaire fermé, aucun contenu ; i/s par classe ; temps du premier son. | Clés `c_*` ajoutées ; délai « premier mot → pancartes » mesuré. |
| **F-247 (cadré)** : `jarvis_state_facts`, bloc « État connu » **préparé à l'avance** (Z12), outil `etat`, `JarvisStateNote` (Opus poste éteint) | Faits datés par la passerelle, source et confiance, valeur précédente. | La source principale des pancartes ticket / MR / pipeline / déploiement / résultat. |

**Conclusion.** Tout l'appareil d'affichage existe (galerie, réducteur, voix locale, comparaison, téléphone, repli 2D,
présentation, mesure). Il manque quatre choses :

1. une **forme de carte** générique pour un **fait** (et non une pièce de contenu) ;
2. un **catalogue des faits lus** dans la réponse, construit par la passerelle, jamais par le modèle ;
3. la règle **« lu et cité ⇒ montré »**, appliquée au moment où la phrase est jouée ;
4. le branchement des sources F-247.

## 3. Principes

- **Gateway-First, déterministe.** La passerelle dit **quels faits ont été lus** dans la réponse et fabrique leur
  pancarte. Le modèle ne nomme jamais une pancarte, n'en invente jamais, et ne reçoit **aucun outil ni consigne
  nouvelle**.
- **Aucun appel IA ajouté.** 0 €, 0 jeton. Préfixe de cache **inchangé à l'octet** (consigne système du cerveau rapide,
  outils, consigne du terminal).
- **Zéro ralentissement.** Les pancartes partent **avec ou après le premier mot, jamais avant** ; rien n'est calculé
  dans le chemin de la réponse ; budget de rendu tenu ; coupe-circuit (§6).
- **Réutiliser, ne rien dupliquer.** Un mode de la galerie F-237, un type de pièce, une visionneuse, un registre de
  gabarits sur le patron `SCENES_3D` / `PIECE_VIEWERS`.
- **Isolation.** Tout fait est relu sous `user_id` (et `host_id` pour le registre F-247).
- **Jamais de secret en clair**, même masqué : liste blanche des champs par gabarit, côté passerelle.

## 4. La conception

### 4.1 La pancarte : un composant, des gabarits légers

**Un composant** `JarvisCardComponent` (« la pancarte ») rend une **fiche** `JarvisCard` fabriquée par la passerelle :

| Champ | Contenu |
|---|---|
| `id` | `fait:<ref>` — `fait:etat:<id>`, `fait:attente:<id>`, `fait:besoin:<id>`, `fait:envoi:<id>`, `fait:courriel:<id>`, `fait:rdv:<uid>`, `fait:personne:<clé>`, `fait:acces:<clé>`. |
| `type` | `TICKET`, `MR`, `PIPELINE`, `DEPLOIEMENT`, `RESULTAT`, `RENDEZ_VOUS`, `COURRIEL`, `PERSONNE`, `ACCES`, `ATTENTE`, `GENERIQUE`. |
| `title` | Le nom (« PROJ-123 — Migration SSO », « MR projet!42 », « api 2.14.1 en prod »). |
| `status` | `{ word, tone }` — le **mot** est toujours écrit ; `tone` ∈ `normal`, `ok`, `attend`, `alerte`, `eteint`. |
| `fields` | 3 à 5 paires `{label, value}` **choisies par le gabarit** (liste blanche). |
| `link` | Lien rapide : l'URL relevée (`url` / `web_url` du fait), sinon le chantier où le fait a été vu (`/atelier/{id}?chantier=N`), sinon aucun. |
| `observedAt`, `source` | Heure d'observation **posée par la passerelle** et source (`tour`, `relevé`, `veille`, `constat`, `direct`). |
| `group` | Le client (nom de l'univers), pour regrouper. |
| `aliases` | Les formes sous lesquelles le fait peut être prononcé (clé, numéro, titre court, nom de la personne) — servent à la citation (§4.3). |

**Les gabarits** (`CARD_TEMPLATES`, frontend, et `JarvisCardTemplates`, passerelle) sont des **données**, pas des
composants : pour chaque type, l'icône (forme), les champs retenus dans l'ordre, la table mot → ton.

| Gabarit | Champs (3-5) | Exemple de statut → ton |
|---|---|---|
| Ticket | statut, assigné, mis à jour, bloqué par | « En revue » → normal ; « Bloqué » → attend ; « Incident » → alerte |
| MR / pipeline | état, pipeline, approbations, branche | « fusionnée » → ok ; « pipeline échoué » → **alerte** |
| Déploiement | version, image, santé, synchro | « sain » → ok ; « dégradé » → **alerte** |
| Résultat de commande / journal | commande (mono), résultat, ligne clé | « réussi » → ok ; « échec » → alerte |
| Rendez-vous | heure, durée, lieu, avec qui | « en cours », « prochain » → attend |
| Courriel | de, reçu, objet, pièce jointe | « à répondre » → attend |
| Personne | rôle, client, ce qui attend d'elle | — → normal |
| Accès | compte, type, validité, état | « expire dans 3 j » → attend ; « refusé / expiré » → alerte |
| Attente / besoin / à envoyer | depuis, chantier, qui | « attend votre oui » → attend |
| Générique (repli) | 3 premiers attributs du fait | → normal |

**Garde de couverture** (patron `kindsWithoutViewer`) : tout type de fiche a un gabarit ; un type inconnu prend
`GENERIQUE`, jamais rien. Ajouter un type = une entrée de données, aucun composant.

**Fraîcheur** (badge) :

- poste connecté et fait < 15 min : « à l'instant » ;
- sinon : « relevé 18 h 40 » (aujourd'hui), « relevé hier 18 h 40 », « relevé lun. 18 h 40 » ;
- plus de 24 h : badge « **ancien** » écrit, pancarte atténuée (palier 0,45) ;
- poste éteint : mention « poste éteint depuis 18 h 05 » en pied du jeu (une fois, pas par pancarte).

Les heures viennent de la passerelle (F-247 : « le modèle n'invente ni date ni fraîcheur ») ; l'écran ne fait que
formater.

### 4.2 Le catalogue des faits lus, construit par la passerelle

À chaque réponse, la passerelle tient la liste des fiches **des faits réellement lus** pour cette réponse :

| Lecture | Qui la fait | Fiches mises au catalogue |
|---|---|---|
| Bloc « État connu » (F-247, joint d'office) | cerveau rapide | les ≤ 20 faits du bloc, **fiches préparées dans le même cache** que le texte du bloc (Z12) |
| Outil `etat` (F-247, à la demande) | cerveau rapide | les ≤ 20 lignes rendues |
| Bloc « Courrier » (F-242) | cerveau rapide | les courriels du bref |
| Outil `agenda` (F-243) | cerveau rapide | les rendez-vous lus (montrés dans la **frise**, §4.7) |
| Blocs « Attentes ouvertes », « À envoyer » (`JarvisFastContextBuilder`) | cerveau rapide | les lignes du bloc |
| Univers (interlocuteurs) | cerveau rapide | les personnes |
| `JarvisStateNote` (F-247 SF-11, Opus poste éteint) | Opus en mode Jarvis | les faits de la note |
| Clés citées par Opus dans ses lignes 🎙, connues du registre | Opus en mode Jarvis | lues par index **après le premier mot** (§4.8) |

**Règle de sélection (D2), déterministe :**

- **Lecture ciblée** (l'outil `etat` ou `agenda` appelé pour cette question) ⇒ **tout** son résultat est montré (le
  modèle a choisi de lire ces faits pour répondre : « montre-moi mes tickets en revue » → toutes les lignes) ; les
  éléments cités sont mis en avant.
- **Lecture d'office** (blocs joints à chaque réplique) ⇒ **seuls les faits cités** dans ce qui est dit ou dans
  l'essentiel affiché (F-240 étage 2) sont montrés. Un bloc de 20 faits n'envahit jamais l'écran.
- Aucun fait lu, ou aucun cité ⇒ aucune pancarte. **Jamais** de pancarte hors catalogue.

**Ce que la passerelle émet** : un événement SSE **`cards`** (`{ responseId, targeted: bool, cards: JarvisCard[] }`,
≤ 24 fiches, ≈ 6 Ko au plus) sur le même flux que `show`. Il est **retenu** jusqu'à ce que la **première phrase** de la
réponse soit partie (ou jusqu'à la fin de la réponse si rien n'est dit), puis envoyé. Aucune attente n'est ajoutée :
c'est un ordre d'envoi, pas un délai.

**Rien dans le chemin de la réponse** : les fiches du bloc sont **déjà prêtes** dans le cache F-247 ; celles des
outils sont dérivées des lignes **déjà lues** pour fabriquer le résultat d'outil, sur un exécuteur à côté, pendant
que le modèle écrit ; celles des blocs du contexte sont dérivées des lignes déjà chargées par
`JarvisFastContextBuilder`, après l'envoi de la requête au modèle. Aucune requête en base de plus avant le premier
jeton.

### 4.3 Citer = montrer, au moment où la phrase est dite

La reconnaissance de la citation se fait **sur l'appareil**, au crochet existant `onPlaying` de chaque phrase
(`sayingHooks`, SF-234-11) :

- un **rapprocheur pur** `cardsCited(sentence, catalogue)` sur le patron de `jarvis-name-matcher.ts` : texte
  normalisé (casse, accents), frontière de mot, alias ≥ 3 caractères, **clés techniques** reconnues telles quelles
  (`PROJ-123`, `!42`, `#17`, numéro de version), ordinaux d'une liste déjà ouverte (« le deuxième ») ; un alias qui
  désigne deux fiches n'allume rien (prudence F-234 D2) ;
- l'essentiel affiché (étage 2) est passé au même rapprocheur quand il apparaît ;
- une fiche citée **apparaît** (lecture d'office) ou **s'avance et s'illumine** (lecture ciblée, jeu déjà posé) quand
  sa phrase **commence à être jouée** ; la précédente reprend sa place.

Un faux positif coûte une pancarte, un faux négatif rien. Le serveur reçoit, en fin de réponse, les identifiants
montrés **par compteurs seulement** (§7) — aucun contenu.

### 4.4 Disposition (D5)

| Nombre | Disposition PC (3D) |
|---|---|
| 1 | **un bloc au centre**, plus large (le « héros » F-237, échelle 1,6), au-dessus du cœur, jamais dessus |
| 2 à 6 | **éventail** (`fanLayout` avec un arc réduit au nombre : 2 → 40°, 6 → 110°) |
| 7 et plus | **mur** de 6 (3 × 2), paginé, dernière case « **et N autres** » (touchable, ou « la suite » à la voix) |

- **Regroupement** : par client (`group`) quand il y en a plusieurs, sinon par statut (alerte d'abord, puis attend,
  puis le reste) ; l'en-tête de groupe est un mot écrit (Space Grotesk capitales), jamais une couleur seule.
- **Zones réservées** : celles de `panelViewport` (barre du haut, bande des sous-titres, cœur, panneaux latéraux).
- **Effacement** : au changement de sujet, sur « ferme ça », quand une nouvelle réponse apporte son propre jeu ; une
  réponse **sans** pancarte laisse le jeu 60 s puis il s'efface en fondu (T-fondu §23).
- **Coexistence** : une galerie de pièces **demandée** par le PO n'est jamais remplacée ; les pancartes s'annoncent alors
  par une pastille « 3 pancartes » touchable. Une galerie d'initiative est remplacée.

**La 3D** est celle de la galerie F-237 : **cartes DOM en verre** posées par transformations CSS 3D (perspective)
au-dessus de la scène WebGL — texte net, aucun maillage, aucune texture à générer. Zoom : molette / pincer sur le jeu
(bornes `MIN_ZOOM`–`MAX_ZOOM` du réducteur), double toucher = plein écran. Entrée : chaque pancarte sort du cœur en
320 ms (`sortie`), décalées de 60 ms ; jamais plus de 6 animées à la fois.

### 4.5 Interaction (D6)

- **Clic / toucher** ⇒ **plein écran** : tous les champs (pas seulement les 3-5), la valeur précédente et son heure
  (« passé de *En cours* à *En revue* hier 17 h 42 »), la source, le sujet et le chantier où le fait a été vu,
  « Ouvrir le lien », « Ouvrir sur le grand écran » (cockpit externe SF-236-24, s'il est ouvert). Le détail est lu à
  l'ouverture par `GET /api/jarvis/cards/{id}` (une ligne par index, filtrée `user_id` + `host_id`), pas porté par
  l'événement.
- **Courriel** ⇒ la visionneuse F-242 ; **rendez-vous** ⇒ la vue d'agenda F-243 sur son jour, le rendez-vous
  surligné (D9).
- **À la voix, sur l'appareil, sans IA** (`jarvis-piece-voice.ts` étendu, liste fermée) : « ferme ça », « montre le
  deuxième », « le premier », « la suite », « compare le premier et le troisième », « en grand ». Une phrase plus
  riche (« celle de Rémi ») part au cerveau rapide, qui a déjà `afficher selection` et lit l'état de la galerie dans
  `## Écran` : **outil inchangé**.
- **Comparer** : deux pancartes côte à côte (`jarvis-piece-compare.component.ts`), champs alignés, différences
  soulignées (mot, pas couleur seule).

### 4.6 Confidentialité (D8)

- **Accès** : la pancarte montre le **nom du compte**, le **type** (jeton GitLab, mot de passe d'application, clé
  d'API, profil AWS…), la **validité** et l'**état**. **Jamais** la valeur, même partielle, même masquée. La passerelle
  ne met au catalogue que les attributs de la liste blanche du gabarit (`account`, `kind`, `expires_at`, `status`,
  `scope`) ; un attribut hors liste ne quitte pas le serveur. `maskSecrets` reste un filet à l'écran.
- **Sources des accès** : les boîtes et agendas reliés (F-242, F-243 : état `OK` / `AUTH_FAILED` / `BROKEN`), le droit
  OAuth Google Calendar, le jeton du runner (F-194 : échéance glissante), et les **identités** relevées sur le poste
  (§8, SF-248-09 : amendement de F-247).
- **Présentation** : aucune pancarte, aucun appel `cards/{id}`.
- **Portrait F-241** : jamais de pancarte tirée du portrait ou des souvenirs.
- **Isolation** : `user_id` sur toute lecture, `host_id` pour le registre ; test à deux utilisateurs et deux clients.

### 4.7 Unification avec les vues dédiées (D9)

Les vues **restent**, elles ne sont pas réécrites en pancartes :

- la **frise d'agenda** dit le temps (enchaînement, trous, « maintenant ») mieux que des cartes : une lecture
  `agenda` continue d'ouvrir la frise (`agendaView`, inchangé) ; les rendez-vous **cités** y sont surlignés au moment
  où ils sont dits ; une pancarte rendez-vous n'apparaît que pour un rendez-vous cité hors d'une lecture d'agenda
  (proposition d'un courriel, attente) ;
- la **visionneuse de courriel** reste le seul endroit où un corps de courriel s'affiche (texte seul, anti-injection
  F-242) : la pancarte courriel n'en montre que l'en-tête et ouvre la visionneuse au toucher.

Elles deviennent le **plein écran** de leurs gabarits. Réécrire les deux vues ferait perdre leurs gardes éprouvées
(texte seul, fusion d'`UID`) pour aucun gain visible.

### 4.8 Opus en mode Jarvis

Opus garde sa consigne (terminal intact, test à l'octet) et ses lignes 🎙. Deux sources de pancartes :

1. les faits de `JarvisStateNote` (poste éteint, F-247 SF-11), mis au catalogue comme le bloc du cerveau rapide ;
2. poste allumé, les **clés techniques** reconnues dans les lignes 🎙 jouées (`PROJ-123`, `projet!42`) : l'écran
   demande leurs fiches **après le premier mot** à `GET /api/jarvis/cards?keys=` (≤ 6 clés, lecture par index
   `(user_id, host_id, object_key)`, Z10 ≤ 10 ms p95). Seuls des faits **déjà au registre** reviennent ; leur badge dit
   l'heure du relevé (le fait du tour en cours arrive plus tard par l'extraction F-247 et rafraîchit la pancarte à son
   ouverture).

### 4.9 Téléphone et replis (D7)

- **Téléphone (< 768 px)** : **pile 2D défilable** verticalement (une pancarte pleine largeur moins 2 × 8 px), aucune
  perspective, aucun flou d'arrière-plan (§23 « téléphone allégé ») ; toucher ⇒ plein écran ; balayage vers le bas ⇒
  fermer. Compagnon Android (F-232) : même écran.
- **Repli 2D** (WebGL absent, mode sobre, mouvement réduit, qualité Basse) : grille F-237, apparition sans animation,
  palette §22.
- **Accessibilité** : chaque pancarte est un bouton (« Ouvrir PROJ-123 ») ; la région `aria-live` existante annonce
  « 3 pancartes » au plus une fois, jamais pendant que Jarvis parle.

## 5. Les décisions par défaut

| # | Décision | Pourquoi |
|---|---|---|
| D1 | Les pancartes sont un **mode `cards` de la galerie F-237** (type de pièce `FAIT`, visionneuse `pancarte` dans `PIECE_VIEWERS`), pas un nouvel écran. | Réducteur, voix locale, comparaison, téléphone, repli et présentation existent déjà et sont testés. |
| D2 | Lecture **ciblée** ⇒ tout le résultat ; lecture **d'office** ⇒ seuls les cités. | Déterministe ; reflète ce que le modèle a choisi de lire ; un bloc de 20 faits n'envahit pas l'écran. |
| D3 | La citation est reconnue **sur l'appareil**, au crochet `onPlaying`. | C'est le seul endroit qui sait quand la phrase est **dite** ; 0 ms côté serveur. |
| D4 | **Aucun outil, aucune consigne, aucun appel modèle** ajoutés ; l'outil `afficher` sert tel quel. | Préfixe de cache stable, 0 €, bancs CONVERSATION / PIECES non exposés. |
| D5 | 1 → centre ; 2-6 → éventail ; ≥ 7 → mur de 6 paginé « et N autres » ; groupes client puis statut. | Demande PO ; au-delà de 6, un éventail devient illisible. |
| D6 | Le détail plein écran est lu **à l'ouverture**, pas porté par l'événement. | Événement petit (≈ 6 Ko), ouverture rare. |
| D7 | Téléphone : pile 2D sans flou ; repli 2D = grille F-237. | Budget de rendu du téléphone (F-236 : ≥ 50 i/s p50). |
| D8 | Accès : liste blanche côté passerelle, aucune valeur jamais émise. | Un masque à l'écran ne suffit pas : ce qui n'est pas émis ne fuit pas. |
| D9 | Courriel et agenda gardent leurs vues, qui deviennent le plein écran de leurs gabarits ; la frise reste la vue d'une lecture d'agenda. | Gardes éprouvées (texte seul, `UID`) ; la frise dit le temps. |
| D10 | Événement `cards` retenu jusqu'à la première phrase partie. | « Avec ou après le premier mot, jamais avant. » |
| D11 | Une réponse sans pancarte laisse le jeu 60 s ; changement de sujet ou « ferme ça » l'efface. | Le PO garde le temps de regarder ; l'écran ne s'encombre pas. |
| D12 | Coupe-circuit serveur `APP_JARVIS_CARDS_ENABLED` (défaut `true`) + réglage « Pancartes » par appareil dans les réglages de `/jarvis` (défaut oui). | Retour arrière sans déploiement ; un appareil lent peut couper. |
| D13 | Dégradation automatique : qualité Basse ou i/s p50 sous la porte pendant 5 s ⇒ pancartes en 2D plates (sans perspective ni flou). | Le budget de rendu prime sur l'effet. |
| D14 | Accès « identités du poste » : un amendement de F-247 (famille `ACCESS`, extracteurs d'identité en lecture), porté par SF-248-09. | La source naturelle est l'extraction F-247 ; aucune seconde extraction. |
| D15 | Lien rapide : `url`/`web_url` relevée (amendement mineur des extracteurs F-247 : garder l'attribut `url` quand la sortie JSON le porte), sinon le chantier, sinon rien. | Aucun lien deviné ni reconstruit. |

## 6. Zéro ralentissement : portes bloquantes

| # | Porte | Mesure |
|---|---|---|
| K1 | **Premier jeton et premier son de Jarvis inchangés** : ≤ +0 % p50, ≤ +20 ms p95 (cerveau rapide et Opus en mode Jarvis). | `jarvis_turn_timings`, base SF-248-01 vs après, 7 j. |
| K2 | **Aucune pancarte avant le premier mot** : 100 % des événements `cards` après la première phrase. | Colonne `cards_lag_ms` (≥ 0 exigé) ; banc PANCARTES. |
| K3 | **Délai premier mot → pancarte** ≤ 300 ms p90 (lecture d'office : délai phrase jouée → pancarte ≤ 150 ms p90). | `cards_lag_ms`, compteurs `lat_*` existants. |
| K4 | **Rendu** : avec 6 pancartes animées, PC ≥ 60 i/s p50, téléphone ≥ 50 i/s p50 et ≤ 4 ms CPU p90 par image (budgets F-236 inchangés). | `jarvis-render-stats`, banc headless. |
| K5 | **Aucune requête en base ajoutée avant le premier jeton.** | Test : compteur de requêtes Hibernate sur le chemin `converse` avant l'envoi au modèle. |
| K6 | **Préfixe** : consigne système du cerveau rapide, outils et consigne du terminal identiques à l'octet. | Tests à l'octet existants (SF-241, SF-247-11), rejoués. |
| K7 | **Coupe-circuit** : `APP_JARVIS_CARDS_ENABLED=false` ⇒ aucun événement, aucun calcul de fiche. | Test d'intégration. |

## 7. Mesurer

Clés fermées ajoutées à `RenderCounts.KEYS` / `PIECE_COUNT_KEYS` (aucun contenu, aucun identifiant) :

- `c_set` jeux montrés, `c_n1`, `c_n2_6`, `c_n7p` par taille, `c_tgt` / `c_cit` (ciblé / cité) ;
- `c_open` plein écran, `c_link` lien ouvert, `c_big` grand écran, `c_cmp` comparaison, `c_more` « et N autres » ;
- `c_voice` / `c_touch` ordres sur les pancartes, `c_close2s` fermées en moins de 2 s (« mal montré ») ;
- `c_old` pancartes « ancien » montrées, `c_2d` jeux dégradés en 2D (D13).

`jarvis_turn_timings` reçoit `cards_lag_ms` et `cards_count` (migration additive, nullable).
Si `render_counts` (varchar 1000) devient trop court, il est élargi à 2000 dans la même migration.

**Valeur, revue à J+7 / J+14** : taux d'ouverture (`c_open / c_set`) ≥ 15 % ; `c_close2s / c_set` ≤ 10 % ; K1-K4
tenues. Lecture admin dans la mesure Jarvis existante (`/api/admin/jarvis/...`), pas d'écran nouveau.

## 8. Découpage (10 SF, ≈ 15,5 j)

| SF | Objet | Est. | Dépend de |
|---|---|---|---|
| **SF-248-01** | **Mesurer d'abord.** `cards_lag_ms`, `cards_count` (migration), clés `c_*`, bases de K1 et K4 sur 7 j, coupe-circuit `APP_JARVIS_CARDS_ENABLED`. | 0,75 j | — |
| **SF-248-02** | **La fiche et le catalogue (passerelle).** `JarvisCard`, `JarvisCardTemplates` (liste blanche par type, tons, fraîcheur), catalogue par réponse depuis les lectures **déjà faites** (attentes, à envoyer, courriels du bref, personnes de l'univers, rendez-vous lus), règle D2, événement SSE `cards` retenu jusqu'à la première phrase (D10), hors chemin (K5). Tests d'isolation à deux utilisateurs. | 2 j | 01 |
| **SF-248-03** | **La pancarte (écran).** `JarvisCardComponent`, `CARD_TEMPLATES` + garde de couverture, statut mot + ton, champs, lien, badge de fraîcheur, peaux verre 3D (§23) et 2D (§22). DESIGN_SYSTEM §25. | 1,5 j | 02 (contrat) |
| **SF-248-04** | **Le jeu de pancartes.** Mode `cards` de la galerie, type `FAIT`, visionneuse `pancarte` dans `PIECE_VIEWERS` ; dispositions 1 / 2-6 / ≥ 7 paginé, groupes ; entrée depuis le cœur ; effacement (D11) ; coexistence avec une galerie demandée ; pile 2D au téléphone ; repli grille ; dégradation D13 ; réglage par appareil. | 2 j | 03 |
| **SF-248-05** | **Citer = montrer.** `cardsCited` pur (alias, clés techniques, ordinaux, ambiguïté), branché sur `sayingHooks` et l'essentiel ; apparition / mise en avant à la phrase jouée ; compteurs `c_cit` / `c_tgt`. | 1,5 j | 04 |
| **SF-248-06** | **Interaction.** Plein écran (`GET /api/jarvis/cards/{id}`, valeur précédente, source, lien), courriel → visionneuse F-242, rendez-vous → frise F-243 surlignée, ordres vocaux locaux, comparer, zoom, grand écran ; route ajoutée à `JarvisCompanionScope`. | 2 j | 05 |
| **SF-248-07** | **Les faits du registre (F-247).** Fiches du bloc « État connu » préparées dans le cache Z12, fiches de l'outil `etat` ; gabarits ticket, MR / pipeline, déploiement, résultat ; « poste éteint depuis » ; attribut `url` (D15). | 1,5 j | 02, **SF-247-10** |
| **SF-248-08** | **Opus en mode Jarvis.** Faits de `JarvisStateNote` ; clés citées dans les lignes 🎙 → `GET /api/jarvis/cards?keys=` après le premier mot (≤ 6, index Z10). Consigne du terminal intacte (test à l'octet). | 1,25 j | 07, **SF-247-11** |
| **SF-248-09** | **Les accès.** Gabarit ACCES ; sources boîtes / agendas / droit OAuth / jeton runner ; amendement F-247 : famille `ACCESS` et extracteurs d'identité en lecture (`aws sts get-caller-identity`, `az account show`, `kubectl config current-context`, `glab auth status`, `gh auth status`) limités à compte / type / validité / état ; corpus « aucun secret » (100 %). | 1,5 j | 07, **SF-247-05** |
| **SF-248-10** | **Bancs et planche.** Banc PANCARTES déterministe (40 scénarios rejoués sans modèle : 0 pancarte hors catalogue, 100 % des cités montrés, 0 avant le premier mot, 0 secret, présentation vide) ; banc headless 1366×768 et 360×740 (K4, aucun débordement) ; bancs CONVERSATION et PIECES rejoués (non-régression) ; planche de captures `docs/features/F-248/captures/index.html`. | 1,5 j | 06, 08, 09 |

**Ordre** : 01 → 02 → 03 → 04 → 05 → 06 se livrent **sans attendre F-247** (courriels, attentes, à envoyer,
personnes, rendez-vous cités). 07 dès SF-247-10, 08 dès SF-247-11, 09 dès SF-247-05. 10 en dernier.

Chaque SF suit la séquence CLAUDE.md (mini-spec, readiness, dev, review, push + release, merge, étape 6).

## 9. Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | **Oui** | Deux routes nouvelles en lecture : `GET /api/jarvis/cards/{id}` et `GET /api/jarvis/cards?keys=` — ajoutées à la liste fermée `JarvisCompanionScope.ROUTES` (session `jarvis-compagnon`, SF-232-05) ; test de non-régression de la portée (toute autre route reste 403). Endpoints Jarvis existants inchangés. |
| Contexte tenant | **Oui** | `JarvisCardCatalog` (fiches dérivées de lignes déjà relues sous `user_id`), `JarvisCardController` (`user_id` + `host_id`), lecture par `object_key` dans `JarvisStateFactRepository` (F-247), `JarvisFastContextBuilder` (lignes réutilisées), `JarvisMailItemRepository`, `JarvisCalendarEventRepository`, univers. Test d'isolation à deux utilisateurs et deux clients dans 02, 06, 07, 08. |
| Plans / limites | Non | Aucun appel modèle ; rien n'entre dans `jarvis_usage_days` en € ni dans le quota Gateway. |
| Navigation / routing | Non | Aucune route Angular nouvelle : un mode de la galerie de `/jarvis` ; les liens ouvrent des routes existantes (`/atelier/{id}?chantier=N`) ou un onglet externe. |

## 10. Hors périmètre

- Toute pancarte **produite par le modèle** (texte libre, champ inventé) et tout outil ou consigne nouveaux.
- Une **action** depuis une pancarte (fusionner une MR, changer un statut) : la pancarte montre, elle n'agit pas ; une
  action passe par la voix et le « oui » existant.
- La réécriture de la visionneuse de courriel ou de la frise d'agenda.
- Des pancartes en **maillages WebGL** (textures de texte) : coûteux, texte flou ; écarté au profit du DOM en CSS 3D.
- Toute source Vigie, Teams ou Radar (correction PO, F-247).
- L'historique d'un fait au-delà de la valeur précédente (F-247).
- Le déploiement : décision séparée, après vérification des tours en cours.

## 11. Questions PO

**Aucune bloquante.** Les défauts D1-D15 sont pris. Un point de **goût** sera soumis par la planche de SF-248-10 (comme
pour F-236 / F-237) : l'aspect des pancartes (taille du bloc unique, densité du mur).
