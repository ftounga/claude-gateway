# CADRAGE F-240 — Jarvis va à l'essentiel : la réponse en trois étages

**Statut :** cadrage du 2026-11-02 (décision PO du même jour). Aucun code. Famille « Jarvis » : F-230 → F-239, **F-240**.
**Estimation :** 4 SF, ≈ 2,5 j.

## 1. Demande PO (2026-11-02)

Jarvis parle trop longtemps. Le PO veut une réponse en **trois étages** :

1. **Ce qu'il DIT** : une seule idée, ≈ 25 mots, moins de 10 s. Le verdict ou la réponse d'abord, jamais le chemin parcouru.
2. **Ce qu'il MONTRE** : l'essentiel en 3 à 5 lignes, c'est-à-dire le bloc `<<essentiel>>` existant, affiché seul.
3. **Le DÉTAIL** complet, replié, qu'on ouvre d'un toucher.

- « Dis-m'en plus » ou « développe » fait **descendre d'un étage** en lisant la réponse **déjà calculée** : **aucun nouvel
  appel à Opus** (≈ 0 €). La demande est reconnue sur l'appareil, ou par le cerveau rapide.
- Une invitation **ciblée** seulement si un point important a été coupé, et elle le nomme précisément (« Il y a aussi un
  point sur Helios, je vous le dis ? »). **Jamais d'invitation générique.**
- Mesure : la longueur dite (mots, secondes) entre au banc (avec des scénarios de délégation) et dans
  `jarvis_turn_timings`. Critère : ≈ 25 mots au p90.

## 2. Constat (code et prod, vérifié le 2026-11-02)

| # | Constat | Où |
|---|---|---|
| 1 | La consigne orale est **fixe et jointe au message**, jamais à la consigne système (D3 de F-231, préfixe stable). Elle demande une ligne « 🎙 » d'**au plus deux phrases courtes**, sans aucune borne en mots. | `backend/.../jarvis/JarvisOralNote.java` (`TEXT`) |
| 2 | En prod, les lignes 🎙 vont jusqu'à **≈ 300 caractères (≈ 20 s de voix)** : deux phrases longues, remplies au maximum. Exemple : une réponse de 4 950 caractères le 01/11 à 18:44. Le modèle respecte « deux phrases », mais « courtes » ne le borne pas. | prod |
| 3 | L'écran coupe seulement à **400 caractères** (`MAX_ORAL_CHARS`) et à **2 phrases par ligne 🎙** (`MAX_LINE_SENTENCES`). L'annonce qui précède les outils (« 🎙 je regarde… ») est une autre ligne 🎙, dite en plus. | `frontend/.../jarvis/jarvis-oral.ts` (`oralPhrase`, `OralStream`) |
| 4 | L'écran `/jarvis` affiche la phrase dite (`jv__spoken`), puis un bouton « Voir le détail » qui déplie **la réponse entière** : essentiel et détail en un seul pavé Markdown (`stripShowLines(reply)`). **Il n'y a pas d'étage intermédiaire** : `splitEssential` (F-126) n'est utilisé que par le terminal. | `jarvis.component.html` (l. 184-193), `jarvis.component.ts` (`detail`, `onDone`, fin de délégation l. 1502) |
| 5 | Les réponses portent **déjà** un bloc `<<essentiel>>…<</essentiel>>`, demandé par la doctrine système `ESSENTIAL_ANSWER_DOCTRINE` (« une phrase, deux au plus ») sur toutes les réponses du terminal. | `AtelierChatService.java` l. 510 ; `frontend/.../atelier/terminal/essential.ts` |
| 6 | L'écran a **déjà la réponse complète en mémoire**, dans les deux chemins : tour Opus direct (`onDone`) et fin de délégation du cerveau rapide (F-235). Descendre d'un étage ne demande donc aucun appel. | `jarvis.component.ts` |
| 7 | Le cerveau rapide ne voit que **400 caractères par message** du fil (`MAX_MESSAGE`). Il ne peut donc pas développer lui-même une réponse d'Opus : il inventerait, ou bien il déléguerait (≈ 1 €). | `JarvisFastContextBuilder.java` |
| 8 | Un patron de commande reconnue sur l'appareil existe déjà : « redis-moi » / « répète » (`isRepeatCommand`, liste fermée), qui relit la dernière annonce sans aucun appel. | `jarvis-fast-brain.ts` |
| 9 | Le cerveau rapide est en consigne **v6** : 1 à 3 phrases, sans offre de détail d'office (garde `isDetailOffer`). Le banc CONVERSATION (34 scénarios) est la porte de déploiement : ≥ 90 % et zéro défaut grave. **Il n'exécute pas Opus** : une délégation est jugée sur sa seule route. Il ne mesure aucune longueur en mots. | `JarvisFastBrainPrompt`, `JarvisFastGuards`, `JarvisConversationBench`, `conversation-scenarios.json` |
| 10 | `jarvis_turn_timings` mesure des durées (premier jeton, première phrase, premier son, fin), mais **pas la longueur dite**. | `JarvisTurnTiming.java`, migrations 196 / 235 / 239 |
| 11 | Rien ne gère encore « dis-m'en plus », « développe » ou « détaille » : ni l'appareil, ni le cerveau rapide. Ces mots ne figurent qu'en négatif, dans la garde anti-offre. | grep |

**Ce qui existe déjà et sera réutilisé, sans le recréer :** le bloc essentiel et son découpage (`splitEssential`), le
repli « Voir le détail », le patron « redis-moi », la file d'annonces, les marques de ligne (`🎙`, `🖥`), le banc
CONVERSATION et sa porte, `jarvis_turn_timings` et `GET /api/admin/jarvis/latency`.

**Travaux en cours à ne pas heurter :** F-237 (SF-237-08 à 14) modifie en ce moment la consigne du cerveau rapide (outils
`pieces` / `afficher`, SF-237-08) et `JarvisOralNote` (ligne `🖥 montrer:` étendue, SF-237-09). **F-240 se développe donc
après le merge de SF-237-09**, ou se rebase dessus. SF-240-01 ne touche ni l'un ni l'autre et peut partir avant.

## 3. Principes (contraintes non négociables)

- **Justesse avant coût, et le raisonnement reste intact.** Rien ne borne ce qu'Opus pense ou écrit. On ne règle que **la
  forme** de la ligne dite et la **taille** de l'essentiel. Le détail reste complet.
- **Préfixe stable.** Toute nouvelle consigne est **fixe** et part **dans le message**, via `JarvisOralNote`. La consigne
  système du terminal (`ESSENTIAL_ANSWER_DOCTRINE` comprise) ne change pas d'un octet. La consigne du cerveau rapide, si
  elle change, change une fois (nouvelle version figée).
- **Jamais d'escalade vers Opus** pour descendre d'un étage : la lecture se fait sur l'appareil.
- **Porte de déploiement :** banc CONVERSATION ≥ 90 % et zéro défaut grave, inchangé, plus le nouveau critère de longueur.
- **Isolation `user_id`** : aucune lecture hors des données de l'utilisateur. Les mesures ajoutées sont écrites sous son
  `user_id`. L'administrateur ne lit que **ses** tours (patron existant).
- **DESIGN_SYSTEM.md** : `/jarvis` reste navy, sans nouvelle couleur (§22). Le rendu 3D garde §23.

## 4. Décisions tranchées par défaut (pas de question au PO)

| # | Décision | Défaut retenu | Pourquoi |
|---|---|---|---|
| D1 | Borne de la ligne dite | **Une phrase, une idée, ≈ 25 mots, 30 au plus.** Le verdict ou la réponse en premier. Interdits : le chemin parcouru (« j'ai regardé… », « après vérification… »), les listes, les chiffres en cascade. | La demande du PO. Une borne en **mots** est plus nette pour le modèle que « courtes ». |
| D2 | Où vit la consigne | Nouveau texte de `JarvisOralNote.TEXT` (**v2 figée**), dans le message. | Préfixe stable (§3). |
| D3 | Annonce avant outils (D-PO-7 de F-233) | Gardée, mais **≤ 12 mots** et **comptée à part** (`announce_words`). Elle n'entre pas dans les 25 mots de la réponse. | Elle sert à patienter. Le PO juge la réponse, pas l'attente. |
| D4 | Taille de l'essentiel | **En mode Jarvis seulement**, l'essentiel tient en **3 à 5 lignes courtes** : le verdict, puis les points importants. **Tout point qui mérite une invitation y figure.** La consigne le dit dans le message (« en mode Jarvis, ton bloc essentiel tient en 3 à 5 lignes »). La doctrine système reste « une phrase, deux au plus » pour le terminal. | Le terminal n'est pas visé. La consigne la plus précise, portée par le message, prévaut pour ce tour. |
| D5 | Garde de longueur sur l'appareil | Si la ligne 🎙 finale dépasse **35 mots**, l'écran **ne dit que sa première phrase**. Le reste est à l'étage 2. La coupe est comptée (`oral_cut`). | Filet de sécurité. On ne perd rien : tout est dans l'essentiel et le détail. |
| D6 | Invitation ciblée | Une ligne facultative **`↪`**, écrite par Opus, **≤ 15 mots**, qui **nomme** le point coupé et finit par une question (« ↪ Il y a aussi un point sur Helios, je vous le dis ? »). Elle est **interdite** quand la réponse demande une confirmation (« Vous confirmez ? »). L'écran la dit après la ligne 🎙. Elle est **écartée sur l'appareil** si elle est générique (même règle que `isDetailOffer`, portée côté écran) ou si une confirmation est en attente. | Le ton reste naturel, puisqu'Opus formule l'invitation. La garde rend le cas générique impossible à l'oral. |
| D7 | Que dit « dis-m'en plus » | Étage 1 → 2 : **dire l'essentiel** (nettoyé, ligne par ligne). Étage 2 → 3 : **déplier le détail à l'écran** et en dire **la première section** (≤ 60 mots, sans tableaux ni code : « le tableau est à l'écran »). Chaque « encore » dit la section suivante. À la fin : « C'est tout pour cette réponse. » Un « oui » à l'invitation `↪` équivaut à « dis-m'en plus ». | On ne lit jamais 5 minutes d'un coup, et chaque pas reste maîtrisé par le PO. |
| D8 | Reconnaissance | **D'abord sur l'appareil**, par une liste fermée normalisée (patron `isRepeatCommand`) : « dis-m'en plus », « dites-m'en plus », « développe », « détaille », « plus de détails », « encore », « la suite », « et ensuite », « vas-y » / « oui » / « dis-le » après une invitation. « continue » est **exclu** (c'est déjà un acquiescement dans `classifyInterruption`). **Ensuite par le cerveau rapide** (formulations libres) : il répond par une ligne-marque fixe `⤵`, et l'écran descend d'un étage. Garde serveur : si le fil a une réponse d'Opus récente, une demande de développement **ne délègue jamais**. | Coût nul et pas de latence pour les cas fréquents. Le cerveau rapide couvre le reste sans pouvoir escalader. |
| D9 | Quelle réponse on développe | **La dernière réponse d'Opus dite dans la session** (tour direct ou fin de délégation). Si l'écran n'en a aucune, par exemple après un rechargement, il relit la dernière réponse d'Opus **du fil courant** par l'historique déjà exposé au terminal (filtre `user_id` existant, aucun nouveau point d'accès). Sinon, c'est un tour normal du cerveau rapide, qui ne délègue pas. Pour forcer Opus, il reste « vérifie » (liste `FORCE` existante). | Contrainte « jamais d'escalade ». |
| D10 | Toucher | Le bloc essentiel est affiché ; un toucher sur « Voir le détail » ouvre l'étage 3 sans rien dire. L'étage atteint est mesuré, qu'on y arrive à la voix ou au toucher. | La demande du PO : le détail s'ouvre au toucher. |
| D11 | Cerveau rapide | Sa longueur est **mesurée** au banc (SF-240-01). **Si son p90 dépasse 30 mots**, la consigne passe en v7 (« ≈ 25 mots ») dans SF-240-02. Sinon, elle n'est pas touchée. | Ne pas rouvrir sans preuve la porte de F-239, passée au vert le 02/11. |
| D12 | Mesure de la parole d'Opus au banc | Un **banc de la parole** : 8 scénarios de délégation (point client, état d'un chantier, diagnostic, conseil A/B, ticket, réponse longue avec tableau, action qui engage, réponse avec un point annexe important). Opus 5.5 est appelé **sans outils**, sur un contexte figé, avec la consigne système du terminal et la consigne orale réelles. Accès ADMIN, à la demande, **plafond 3 € par passage**. Mesures : mots de la ligne 🎙, verdict d'abord (juge), invitation ciblée ou absente, essentiel de 3 à 5 lignes. | Le banc CONVERSATION n'exécute pas Opus (constat 9). Sans outils, on mesure la **forme**, ce qui suffit ici. La justesse se juge en prod. |

**Question au PO : aucune.** Tout ce qui précède relève de défauts techniques ou découle de sa décision. Seul le ressenti à
l'essai reste à recueillir après le déploiement : 25 mots, trop court ou juste ?

## 5. Découpage — SF-240-01 → SF-240-04 (≈ 2,5 j)

| SF | Titre | Estimation | Dépend de |
|---|---|---|---|
| SF-240-01 | Mesurer ce qui est dit | 0,5 j | — |
| SF-240-02 | Ce qu'il dit : une idée, 25 mots | 0,75 j | SF-240-01, merge de SF-237-09 |
| SF-240-03 | Ce qu'il montre : l'écran en trois étages | 0,5 j | — (gagne à suivre SF-240-02) |
| SF-240-04 | « Dis-m'en plus » descend d'un étage | 0,75 j | SF-240-02, SF-240-03, merge de SF-237-08 |

### SF-240-01 — Mesurer ce qui est dit (0,5 j)

**Objectif :** savoir combien Jarvis parle, avant et après, en prod et au banc.

- `jarvis_turn_timings` (prochaine migration libre) reçoit des colonnes nullables : `spoken_words`, `spoken_chars`,
  `spoken_ms` (durée audio de la réponse dite, hors annonce), `announce_words`, `oral_cut` (booléen), `invitation`
  (booléen), `depth_reached` (1 à 3), `depth_via` (`voix` ou `toucher`). Elles sont remplies par l'écran via
  `POST /api/jarvis/timings` (existant), sous le `user_id` de l'appelant.
- `GET /api/admin/jarvis/latency` ajoute p50 / p90 des mots et des secondes dites, le taux de coupe, le taux
  d'invitation et la répartition des étages atteints (sur **ses** tours, 403 sinon).
- **Base de départ** : un relevé en lecture seule des réponses `via = JARVIS` des 14 derniers jours (`oralLine`, mots),
  pour donner le p90 avant F-240 dans la PR.
- Banc CONVERSATION : chaque tour jugé porte `words`. Le résultat expose p50 / p90 des mots du cerveau rapide, à titre
  **informatif** : la porte reste inchangée.

**Critères d'acceptation :** (1) un tour Jarvis en prod écrit les mots et la durée dite ; (2) l'administrateur lit
p50 / p90 ; un autre utilisateur reçoit 403 ; (3) le banc rend le p90 des mots du cerveau rapide ; (4) la base de départ
figure dans la PR.
**Plan de test :** unitaires du comptage de mots (ponctuation, « Monsieur », chiffres, apostrophes) ; intégration de
l'enregistrement et de la lecture des nouvelles colonnes ; isolation (les tours de B ne sont pas comptés pour A, 403 hors
ADMIN) ; test Liquibase de la migration ; spec frontend de l'envoi des champs.
**Hors périmètre :** changer ce qui est dit.

### SF-240-02 — Ce qu'il dit : une idée, 25 mots (0,75 j)

**Objectif :** que la ligne dite tienne en une idée d'environ 25 mots, verdict d'abord, avec au plus une invitation
ciblée.

- `JarvisOralNote.TEXT` passe en **v2 figée** (D1, D3, D4, D6). Les règles existantes restent : vouvoiement,
  « Monsieur », confirmation, message préparé, `email_me`, `🖥 montrer:`.
- Écran : garde de longueur (D5) dans `oralPhrase` / `OralStream`. La ligne `↪` est reconnue, dite après la phrase 🎙,
  écartée si elle est générique ou si une confirmation est en attente, et masquée du détail comme `🖥`.
  `JarvisReplies.oralLine` ignore aussi `↪`.
- **Banc de la parole** (D12) : `POST /api/admin/jarvis/oral-bench`, ADMIN, passages en base (patron `jarvis_bench_runs`),
  pré-banc fidèle en `*LiveTest` sur le code de prod.
- Si le p90 du cerveau rapide mesuré en SF-240-01 dépasse 30 mots : consigne v7 (D11) et passage du banc CONVERSATION.

**Critères d'acceptation :** (1) banc de la parole : **p90 ≤ 30 mots** et médiane ≈ 25 sur la ligne 🎙, verdict d'abord
dans au moins 7 scénarios sur 8, **zéro invitation générique**, invitation présente dans le scénario « point annexe
important », absente dans le scénario « confirmation » ; (2) banc CONVERSATION **≥ 90 % sans zéro** (inchangé ou
repassé) ; (3) la consigne système du terminal est **identique à l'octet** (test existant du préfixe) ; (4) consigne orale
identique d'un tour à l'autre (aucune donnée variable) ; (5) en prod, à J+3, `spoken_words` p90 ≤ 30.
**Plan de test :** unitaires de `JarvisOralNote` (texte figé, rien de volatil, `appendTo` inchangé dans sa structure) ;
specs `jarvis-oral` (coupe à 35 mots qui garde la première phrase, ligne `↪` lue puis masquée, invitation générique
écartée, invitation supprimée si « Vous confirmez ? ») ; unitaires `JarvisReplies` (`↪` ignoré) ; pré-banc fidèle de la
parole ; isolation (le banc est ADMIN, ses passages sont lus sous son `user_id`).
**Hors périmètre :** la doctrine système du terminal ; le raisonnement et les outils d'Opus.

### SF-240-03 — Ce qu'il montre : l'écran en trois étages (0,5 j)

**Objectif :** sous l'orbe, l'écran montre la phrase dite, puis l'essentiel seul, et le détail reste replié.

- `/jarvis` (zone « Dernier échange ») : (1) la phrase dite (existante) ; (2) **l'essentiel** via `splitEssential`
  (réutilisé et non dupliqué, déplacé vers `shared/` si l'import croisé gêne), rendu Markdown, 3 à 5 lignes ;
  (3) « Voir le détail » déplie **le détail seul**, sans répéter l'essentiel.
- Repli sans bloc essentiel : les 5 premières lignes de prose (hors tableaux et code) font l'étage 2, le reste reste
  replié. Pendant le flux, l'essentiel apparaît à sa fermeture ; le marqueur brut n'est jamais visible.
- Mêmes étages pour une fin de délégation. Le mode présentation et le cockpit sont inchangés.
- DESIGN_SYSTEM.md, §22 : une ligne ajoute « essentiel = texte crème sur `--cg-navy-2` à 70 %, Inter ; aucune nouvelle
  couleur ; téléphone : l'essentiel reste visible sans défilement à 360 × 740 ».

**Critères d'acceptation :** (1) une réponse avec bloc montre l'essentiel seul et le détail replié ; (2) une réponse sans
bloc suit le repli ; (3) le détail déplié ne contient pas l'essentiel ; (4) au toucher, `depth_reached = 3` et
`depth_via = toucher` ; (5) aucune couleur hors du design system, pas de débordement horizontal à 360 px.
**Plan de test :** specs du composant (avec ou sans bloc, flux, délégation, toucher) ; spec du repli ; contrôle visuel au
banc headless à 1366 × 768 et 360 × 740 (sans `--hide-scrollbars`, `scrollWidth` mesuré).
**Hors périmètre :** la voix ; le terminal.

### SF-240-04 — « Dis-m'en plus » descend d'un étage (0,75 j)

**Objectif :** descendre d'un étage à la voix, sans appel à Opus.

- Écran : `isMoreCommand` (liste fermée D8, patron `isRepeatCommand`), testé **avant** tout appel au cerveau rapide.
  Il exécute D7 sur la réponse retenue en D9. Un « oui », « vas-y » ou « dis-le » dans les 30 s qui suivent une
  invitation `↪` équivaut à « dis-m'en plus ».
- Cerveau rapide : la consigne figée apprend la ligne-marque `⤵`, qui signifie « l'utilisateur veut la suite de la
  dernière réponse » (après SF-237-08, version suivante). Garde serveur : demande de développement et réponse d'Opus
  récente dans le fil ⇒ jamais `deleguer`. L'écran consomme `⤵` sans le dire.
- Banc CONVERSATION : 4 scénarios ajoutés (« dis-m'en plus » après une délégation, « tu peux développer le point
  Helios ? », « et ensuite ? » sans réponse d'Opus, « vérifie » qui délègue toujours). Attendus : pas de délégation
  (sauf « vérifie »), et `⤵` quand une réponse existe.
- Mesure : `depth_reached` / `depth_via = voix`. L'appel au cerveau rapide reste au coût actuel ; le coût Opus est nul.

**Critères d'acceptation :** (1) « dis-m'en plus » après une réponse dit l'essentiel, puis « encore » dit la première
section du détail et le déplie, **aucune requête vers Opus** (vérifié dans le test) ; (2) un « oui » à l'invitation dit
l'essentiel ; (3) une formulation libre passe par `⤵`, sans délégation ; (4) « vérifie » délègue toujours ; (5) banc
CONVERSATION **≥ 90 % sans zéro** avec les 4 scénarios ; (6) la réponse relue après un rechargement provient du fil de
l'utilisateur seulement.
**Plan de test :** specs `isMoreCommand` (variantes, accents, « Jarvis » en tête, « continue » exclu) ; spec du parcours
des étages (1 → 2 → 3 → sections → fin) ; unitaires de la garde serveur sans délégation ; pré-banc fidèle
CONVERSATION ; isolation (relecture du fil sous `user_id`, aucune réponse d'un autre utilisateur).
**Hors périmètre :** développer une réponse du cerveau rapide lui-même (c'est un tour normal) ; résumer le détail par IA.

## 6. Mesure de réussite

- Prod, J+3 puis J+7 après le déploiement : `spoken_words` **p90 ≤ 30** (cible ≈ 25), `spoken_ms` p90 **< 10 s**, et le
  taux de coupe sur l'appareil (D5) **< 10 %**. Au-delà, c'est la consigne qu'il faut revoir, pas la garde.
- Usage des étages (`depth_reached`) : la part de « dis-m'en plus » dit si l'étage 1 suffit.
- Coût : aucun appel Opus supplémentaire dû à F-240 (aucune délégation sur une demande de développement).
- Banc CONVERSATION ≥ 90 % sans zéro à chaque déploiement.

## 7. Hors périmètre

Changer la doctrine système du terminal ; borner ou résumer le raisonnement d'Opus ; un résumé du détail par IA ; la voix
(F-233) ; la 3D (F-236) ; les pièces (F-237).
