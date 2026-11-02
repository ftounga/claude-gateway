# CADRAGE F-241 — Jarvis vous connaît : le portrait

**Statut :** cadrage du 2026-11-02 (décision PO du même jour, précisions PO du même jour intégrées). Aucun code.
Famille « Jarvis » : F-230 → F-240, **F-241**.
**Estimation :** 5 SF, ≈ 3 j. **Ordre :** F-241 se développe **après F-240** (voir §8, points de contact).

## 1. Demande PO (2026-11-02)

Humaniser vraiment Jarvis (ton, traits d'humour, questions, réponses aux allusions à sa vie) en lui faisant
**connaître la vie** du PO : carrière, situation familiale (par exemple une naissance), problèmes personnels, goûts.

Ce que le PO a tranché avec nous :

- **Remplissage initial par entretien oral** : Jarvis pose une dizaine de questions, à la voix, la première fois. Le
  portrait reste un **texte lisible et modifiable** dans les réglages de `/jarvis`.
- **Le portrait est vivant** (précision PO) : l'entretien n'est pas fait une fois pour toutes. Ensuite viennent
  l'apprentissage en conversation (« Voulez-vous que je retienne que… ? »), une relance légère périodique (« Du nouveau
  depuis notre dernier entretien ? »), et la reprise à la demande (« Jarvis, mettons à jour mon portrait »). Une
  information **vieillit** : l'âge d'un enfant, le poste actuel. Elle doit donc être datée.
- **Jamais de mémorisation silencieuse.** La liste des souvenirs est visible, chaque souvenir est effaçable, et tout
  s'efface d'un geste.
- **Une section « à ne pas aborder »**, explicite.
- **Tact** : au plus **une** allusion personnelle par conversation. Jamais d'humour sur un sujet sensible que le PO n'a
  pas ouvert lui-même. Les dates qui comptent (retour de congé, anniversaire) peuvent être utilisées.
- **Anti-robotique** (précision PO) : **avoir l'information ne veut pas dire l'utiliser.** Pas d'allusion systématique.
  On garde la trace des allusions et des traits d'humour récents pour **ne jamais répéter le même** : la rareté est le
  défaut, la variété est obligatoire. Le juge du banc pénalise la répétition et l'allusion forcée.
- **Objectif** (précision PO) : « résultats optimum ». Jarvis dispose de **tout** ce qui lui permet de s'humaniser, et
  **juge seul** quand s'en servir. Les règles fixent des bornes ; elles ne dictent pas de script.
- **Confidentialité** : le portrait n'apparaît jamais en mode présentation (`?presentation=1`). Aucun autre utilisateur
  ne le voit (`user_id`). Il n'entre jamais dans un livrable destiné à un tiers (mails, messages, pages) : il sert à la
  conversation seulement. Les données de santé et de famille sont sensibles.
- Le portrait sert au **cerveau rapide** (`JarvisFastBrainPrompt`, Sonnet 5.5) **et** à **Opus en mode Jarvis**.
- Il entre au **banc CONVERSATION**, avec une porte à ≥ 90 % et zéro défaut grave, inchangée.

## 2. Constat : l'existant, vérifié le 2026-11-02

| # | Constat | Où |
|---|---|---|
| 1 | **Aucune mémoire personnelle n'existe.** `JarvisFastMemory` (SF-239-09) garde les échanges **du jour** (40 au plus, en mémoire, réhydratés depuis le fil) et rien au-delà de minuit. `resolution_memory` (F-148 / F-162) retient des **résolutions techniques par poste**. `host_map_facts` et `governance_*` décrivent des **dépôts et des postes**. `jarvis_preferences` tient une ligne par compte (`humour`, `capture_audio`). Aucun outil `remember` n'existe côté Opus. | grep `Memory`, migrations |
| 2 | La consigne système du cerveau rapide a **deux variantes figées** : `CORE + PERSONALITY_LIGHT` et `CORE + PERSONALITY_NONE`, choisies par le réglage d'humour. Elle est pré-chauffée (`warm`, toutes les 4 min au plus). | `JarvisFastBrainPrompt.system(humour)`, `JarvisFastBrainService.warm` |
| 3 | Le cerveau rapide reçoit ses messages dans cet ordre : `[Référence — univers]` → « Noté. » → échanges du jour → **[contexte volatil + parole]**. Tout ce qui précède le dernier message est relu en cache (SF-239-11). | `JarvisFastBrainService.messages` |
| 4 | Côté Opus en mode Jarvis, la consigne orale, la personnalité et la note « ton sobre » sont jointes **au message** du tour, jamais à la consigne système du terminal, qui reste identique à l'octet près. Le message rangé dans le fil reste la parole seule : **la consigne n'est donc jamais rejouée** et elle est réécrite à chaque tour. | `AtelierChatService` l. 3440-3457, `JarvisOralNote.appendTo(consigne, humour, sober)` |
| 5 | La **note « ton sobre »** (`JarvisSobriety`) coupe déjà l'humour sur une alerte, un incident ou une erreur. | `preferences/JarvisSobriety` |
| 6 | Le cerveau rapide passe chaque phrase par une **porte de phrase** (`SentenceGate`) avant de la dire. Elle sait **écarter** une phrase. | `JarvisFastBrainService` |
| 7 | Le **mode présentation** n'existe **que côté écran** (`presentation` signal, `?presentation=1`). Il masque noms, sorties, détail et pièces. **Le serveur l'ignore.** | `jarvis.component.ts` l. 2030-2045 |
| 8 | Les encarts « mail » et « message » sont des **blocs dans la réponse d'Opus**, relus par `JarvisReturnService.toSend`. Les pages passent par l'outil de pages. | `JarvisReturnService`, `pages/` |
| 9 | Le banc CONVERSATION a un **contexte global unique**, des scénarios **multi-tours** (`turns[]`, `expect`) et un juge (grille : naturel, concision, formule robotique, délégation). Il n'exécute pas Opus. | `conversation-scenarios.json`, `JarvisConversationBench` |
| 10 | La suppression du compte purge déjà `jarvis_preferences`, `jarvis_voice_choices` et les veilles Jarvis. | `AccountService` |
| 11 | Un chiffrement **par enveloppe KMS** existe pour les clés BYOK. Son contrat est générique : `encrypt(String)` / `decrypt`. | `byok/ByokKeyCipher` |
| 12 | Tarifs de la grille : Opus 5.5 coûte en entrée 4 $, en lecture de cache 0,20 $ et en écriture de cache 8 $ par million de jetons. **Sonnet 5.5 est absent de la grille** : il est compté par défaut. Une délégation à Opus coûte ≈ 1 € (F-240). | `ProviderPricingProperties` |

**Conclusion :** il faut **deux tables nouvelles** (portrait, souvenirs) et un **journal** des allusions. Aucune
structure existante ne porte des faits personnels durables, et les y greffer mélangerait des données sensibles avec
des réglages ou des données techniques. `jarvis_preferences` reste réservée aux réglages. Ce qui est réutilisé :
`JarvisFastMemory`, `SentenceGate`, `JarvisSobriety`, `ByokKeyCipher`, la purge de compte, la porte du banc et la
chaîne STT/TTS de `/jarvis`.

## 3. Principes (contraintes non négociables)

- **Justesse avant coût** : Jarvis reçoit le portrait **entier** (≤ 1 500 jetons), pas un résumé. Il n'y a jamais
  d'escalade vers Opus, ni pour retenir, ni pour l'entretien, ni pour une allusion.
- **Préfixe stable** : le portrait n'est **pas volatil par tour**. Il change à chaque modification, donc rarement.
  Côté cerveau rapide, il va dans le **préfixe caché**. Tout ce qui varie à chaque tour va dans le **message** : la
  trace des allusions récentes, l'allusion déjà faite aujourd'hui, la date qui compte aujourd'hui, la relance due. La
  consigne système du **terminal** reste identique à l'octet près.
- **Consentement** : rien n'est retenu sans un « oui » ou une demande explicite, et rien n'est retenu sans être
  visible à l'écran.
- **Confidentialité** : chaque table filtre sur `user_id`. Il n'y a **aucun** point d'accès administrateur au
  portrait d'un autre. Le banc travaille sur un **portrait fictif**, jamais sur le portrait réel. Rien du portrait ne
  part dans les journaux CloudWatch, dans `jarvis_bench_runs` ou dans `jarvis_turn_timings`. Les données sont
  chiffrées au repos.
- **Bornes plutôt que script** : la consigne donne à Jarvis **tout** le portrait et les **bornes** (rareté, variété,
  interdits, tact). C'est Jarvis qui juge du moment. Les gardes déterministes ne servent que de filet, sur les
  interdits et la répétition.
- **Gateway-First / Provider Independence** : les appels passent par `AiAgentProvider`. On ne construit aucun moteur
  maison de compréhension.
- **DESIGN_SYSTEM.md** : l'écran garde la palette navy de `/jarvis` (§22 et §23), sans nouvelle couleur.

## 4. Décisions tranchées par défaut (pas de question au PO)

| # | Décision | Défaut retenu | Pourquoi |
|---|---|---|---|
| D1 | Forme du portrait | **Un texte en sections fixes**, chacune modifiable librement : « Qui vous êtes » (métier, rôle, parcours) ; « Votre entourage » ; « Ce qui compte en ce moment » ; « Vos goûts » ; « L'humour qui vous plaît » ; **« À ne pas aborder »** ; « Dates qui comptent » (une ligne par date : `JJ/MM[/AAAA] — libellé`, annuelle si l'année manque). « Entourage » et « Ce qui compte en ce moment » sont **sensibles par défaut**. | C'est le texte lisible que demande le PO. Les sections donnent au modèle des repères pour le tact (sensible ou non, interdit). |
| D2 | Les souvenirs | Une **liste à part** (`jarvis_souvenirs`) : une phrase courte de 200 caractères au plus, une date `noted_on`, un indicateur `sensitive` et une origine (`VOIX`, `ECRAN`, `ENTRETIEN`). Chaque souvenir s'efface ou se modifie. L'écran offre « Intégrer au portrait » (déplacer le souvenir dans une section). **30 souvenirs au plus.** Au-delà, Jarvis répond : « Ma mémoire est pleine, faites le tri dans les réglages. » | Sans oubli silencieux, une borne nette protège la taille du préfixe. |
| D3 | Datation et vieillissement | Chaque section porte `updated_at` et chaque souvenir `noted_on`. Ces dates sont **injectées** avec le texte (« noté en mars 2026 »), à côté de la date du jour déjà présente dans le contexte, pour que Jarvis raisonne lui-même sur l'âge d'un enfant ou sur un poste qui a pu changer. La consigne le dit : « une information datée peut être périmée ; dans le doute, demande plutôt qu'affirmer ». L'écran marque **« à revoir »** tout ce qui a plus de 12 mois. La relance (D12) s'en sert. | Le vieillissement relève du raisonnement de Jarvis, que les dates rendent possible. |
| D4 | Taille | **≤ 1 500 jetons au total**, soit ≈ 6 000 caractères pour portrait, souvenirs et dates réunis. L'écran affiche un compteur. Au-delà, le serveur refuse l'enregistrement (400) et le message dit ce qu'il faut raccourcir. | C'est la borne demandée par le PO. Elle tient le coût et l'attention du modèle. |
| D5 | Où va le portrait — cerveau rapide | En **fin de consigne système** : `CORE + PERSONALITY_<humour> + bloc « Ce que vous savez de Monsieur »` (portrait, souvenirs, dates). Ce bloc est le même d'un appel à l'autre tant que rien ne change, et `warm` le pré-chauffe. **Sans portrait, la consigne est identique à l'octet près à celle d'aujourd'hui** (test). Les souvenirs sont dans le **même bloc**, rechargé **dès** qu'un souvenir change, sans attendre : Jarvis ne doit pas oublier ce qu'il vient d'accepter de retenir. | Ce qui ne change pas d'un tour à l'autre se lit en cache. Un souvenir ajouté coûte une seule réécriture du préfixe (≈ 0,6 c€), quelques fois par semaine. |
| D6 | Où va le portrait — Opus en mode Jarvis | **Dans le message**, joint après la consigne orale de F-240 (un bloc à part, propre à l'utilisateur, en dehors du `TEXT` figé). Les règles de tact, elles, sont un texte **figé** (`JarvisPortraitNote.RULES`). La consigne système du terminal ne bouge pas. Le portrait est **entier**, sans résumé. | Il n'y a pas d'autre place sans casser le préfixe du terminal (constat 4). Coût ≈ 1 500 × 8 $/M ≈ **1,1 c€ par tour Opus Jarvis**, soit ≈ 1 % d'un tour à ≈ 1 €. |
| D7 | Qui retient | **Seul le cerveau rapide** propose de retenir ou d'oublier, et c'est l'écran qui enregistre. Opus lit le portrait sans jamais l'écrire. Une demande « retiens que… », « souviens-toi que… », « oublie que… » **ne délègue jamais** (garde serveur, patron `⤵` de F-240). | La conversation relève du cerveau rapide. Pas de nouvel outil, donc préfixe intact. Coût nul côté Opus. |
| D8 | Comment il retient | Une **ligne-marque** du cerveau rapide : `📌 retenir: <fait>` accompagne une proposition (« Voulez-vous que je retienne que… ? ») ; `📌 noter: <fait>` accompagne une demande explicite (« retiens que… ») ; `📌 oublier: S<n>` accompagne une demande d'oubli. L'écran ne lit **jamais** la ligne `📌`, il la consomme. **Proposition** : l'enregistrement attend un « oui » dans les 30 s (liste fermée existante). **Demande explicite** : l'enregistrement est immédiat et Jarvis dit ce qu'il a noté (« C'est noté : … »). « Non, oublie ça » dans les 30 s annule. **Oubli** : l'effacement est immédiat (« C'est oublié. »). Aucune de ces marques n'apparaît dans la même réponse qu'une confirmation `Vous confirmez ?` ou qu'une invitation `↪` (F-240). | Une demande explicite n'est pas silencieuse et ne mérite pas une double confirmation. Une proposition, elle, attend le « oui ». Une seule question en attente à la fois. |
| D9 | Quand proposer | Seulement pour une information **personnelle, durable et utile** (une naissance, un nouveau poste, un goût, un congé). Jamais une tâche : « rappelle-moi de… » relève des rappels. **Au plus une proposition par conversation.** Jamais en présentation. Jamais sur ce que le PO dit d'un tiers sans lien avec sa vie. | La rareté vaut aussi pour les propositions, qui sonneraient sinon comme un formulaire. |
| D10 | Tact — les bornes données à Jarvis | Texte figé et identique pour le cerveau rapide (dans le bloc portrait) et pour Opus (`RULES`) : (1) **Avoir l'information ne veut pas dire s'en servir** : la plupart des réponses n'en contiennent aucune. (2) **Au plus une** allusion personnelle **spontanée** par conversation (par jour). Répondre à ce que le PO amène lui-même n'est pas une allusion spontanée : là, la connaissance est **attendue**. (3) **Jamais deux fois la même** allusion ni le même trait d'humour (voir la trace D11). (4) Jamais d'humour sur un sujet **sensible** que le PO n'a pas ouvert lui-même dans cette conversation. (5) Ne **jamais** évoquer la section « À ne pas aborder », même si le PO s'en approche : dans ce cas, il répond sobrement, sans la citer. (6) Une date qui compte peut être saluée **une fois**, le jour même ou à son approche. (7) Une information datée peut être périmée : dans le doute, il demande. (8) Le portrait sert à **converser** et **n'entre jamais** dans un message, un courriel, une page ou un document destiné à un tiers. | Ce sont les bornes du PO. Le reste est laissé au jugement de Jarvis (« résultats optimum »). |
| D11 | Variété — la trace des allusions | Un **journal** `jarvis_allusions` (`user_id`, `at`, `kind` = `ALLUSION` ou `HUMOUR`, `topic` en ≤ 6 mots, `phrase` en ≤ 120 caractères, chiffré, purgé à 60 jours). Il est rempli par une ligne-marque **déclarée par Jarvis** : `✦ allusion: <sujet>` ou `✦ humour: <résumé>`, consommée par l'écran, jamais dite. Un contrôle déterministe complète la déclaration : si une phrase dite reprend un terme distinctif du portrait sans que la parole du PO le contienne, l'allusion est journalisée même non déclarée. Les **10 dernières entrées sur 30 jours**, plus « allusion spontanée déjà faite aujourd'hui : oui/non », partent **dans le message** (volatil), sous la forme « Déjà dit récemment — ne pas répéter ». | Sans mémoire de ce qu'il a déjà dit, le modèle se répète. La trace est volatile, donc elle ne va pas dans le préfixe. |
| D12 | Filets déterministes (cerveau rapide) | Dans `SentenceGate` : (a) une phrase qui contient un terme de « À ne pas aborder » **absent de la parole du PO** est écartée et comptée (`portrait_blocked`) ; (b) une **deuxième** allusion spontanée dans la journée est écartée ; (c) une phrase dont l'allusion reprend un `topic` du journal des 7 derniers jours **au mot près** est écartée. Pour Opus, l'écran applique (a) aux lignes 🎙 avant de les dire. | Le modèle juge, le filet garantit les interdits. L'humour sur un sujet sensible n'a pas de détecteur fiable : il reste jugé au banc. |
| D13 | Entretien initial | **À l'écran `/jarvis`**, hors présentation et **hors tâche en cours**, si aucun portrait n'existe, Jarvis propose une fois : « Puis-je vous poser une dizaine de questions pour mieux vous connaître ? Cinq minutes. » « Plus tard » : nouvelle proposition au bout de 7 jours, **3 fois au plus**. Ensuite, l'entretien ne part plus que des réglages. Le parcours vit **sur l'appareil**, avec des questions fixes dites par la voix existante, des réponses captées par le STT existant et « je passe » toujours accepté. À la fin, **un seul appel** au cerveau rapide (Sonnet 5.5, ≈ 1-2 c€) range les réponses dans les sections. Le PO **relit, corrige et valide** à l'écran, et **rien n'est enregistré avant la validation**. Les réponses ne sont **pas** écrites dans le fil ni dans `JarvisFastMemory`, et sont exclues de `capture_audio` (SF-239-03). | La voix sert la demande du PO. La validation écrite préserve le consentement. L'entretien ne laisse aucune trace hors du portrait. |
| D14 | Les dix questions (modifiables plus tard) | 1. Comment souhaitez-vous que je vous appelle ? 2. Quel est votre métier, votre rôle aujourd'hui ? 3. Votre parcours, en deux mots ? 4. Qu'est-ce qui vous occupe le plus en ce moment ? 5. Qui partage votre vie ? (vous pouvez passer) 6. Un événement important, récent ou à venir ? 7. Vos goûts : loisirs, sport, musique, lectures ? 8. Quel humour vous fait rire ? 9. Des dates que je ne dois pas oublier ? 10. Y a-t-il des sujets que je ne dois jamais aborder ? | Une liste par défaut, ajustable par le PO à l'essai (goût, pas bloquant). |
| D15 | Portrait vivant — relance | **Au plus tous les 90 jours**, le serveur pose dans le message une indication « relance du portrait possible » quand le moment s'y prête : aucune délégation en cours, aucune alerte de 24 h, pas de tâche dans la parole, **pas la première salutation du jour**. C'est **Jarvis qui choisit** de la poser ou non, en une question : « Du nouveau depuis notre dernier entretien ? ». Une réponse de fond mène à `📌 retenir:` (D8), ou à la reprise si le PO le souhaite. `last_checkin_at` est mis à jour dès que la question est posée. | Une cadence bornée par le serveur, un moment choisi par Jarvis : c'est la demande du PO. |
| D16 | Portrait vivant — reprise à la demande | « Jarvis, mettons à jour mon portrait » (liste fermée sur l'appareil, ou ligne-marque `📌 entretien` du cerveau rapide) lance l'**entretien de mise à jour** : d'abord les éléments « à revoir » (plus de 12 mois), puis « Quoi de neuf côté travail ? », « côté vie personnelle ? », « autre chose à retenir ? ». La relecture et la validation suivent, comme en D13. | Même parcours que D13, sans le recommencer de zéro. |
| D17 | Présentation | L'écran envoie `presentation: true` au cerveau rapide (`ConverseRequest`) et sur la marque d'un tour vocal Opus (`VoiceMark`). Le serveur répond par la consigne **sans portrait** (celle d'aujourd'hui, à l'octet près), le journal n'est pas joint, et une note figée part dans le message : « [Jarvis — mode présentation : un public regarde ; aucun élément personnel, aucune proposition de retenir.] ». L'écran ignore toute ligne `📌` ou `✦` et ne propose pas l'entretien. Si le PO pose une question personnelle, Jarvis répond poliment : « Nous en parlerons en privé, Monsieur. » | Le serveur ignorait la présentation (constat 7) : il faut la lui dire. |
| D18 | Livrables | (1) La règle 8 de D10, dans le texte figé. (2) Un **détecteur à la fin d'un tour Opus Jarvis** : les blocs mail et message de la réponse, et le contenu d'une page créée pendant le tour, sont comparés au portrait (termes distinctifs, phrases de 4 mots ou plus en commun). En cas de touche, le bloc porte la mention « élément personnel — à relire » dans « À envoyer » et dans le terminal, et la touche est comptée (`portrait_leak`, sans le texte). (3) Au banc de la parole de F-240, scénario « rédige un mail au client », avec un attendu de **zéro fuite**. `email_me` (qui écrit au PO lui-même) n'est pas visé. | Opus rédige dans le même tour que celui où il lit le portrait : on ne peut pas l'en séparer. Un filet visible vaut mieux qu'un blocage aveugle sur un faux positif, et la mesure dit si la consigne tient. |
| D19 | Chiffrement et suppression | Les textes du portrait, des souvenirs et du journal sont chiffrés par **`ByokKeyCipher`** (enveloppe KMS existante). Si son contrat est trop lié à BYOK, on en extrait une interface `SecretCipher` sans changer son comportement. Chiffrement dormant : le portrait est indisponible (503), et rien n'est stocké en clair. La suppression du compte purge les trois tables (`AccountService`). « Tout effacer » fait une suppression **physique** immédiate. | Les données de santé et de famille sont sensibles. On réutilise ce qui existe. |
| D20 | Réglage maître | Un interrupteur « Jarvis se sert de mon portrait » est ajouté dans les réglages de `/jarvis` (colonne `portrait_enabled` de `jarvis_portraits`, vrai par défaut une fois le portrait validé). Coupé, il donne la consigne d'aujourd'hui, sans proposition ni relance. | Une sortie simple, sans tout effacer. |

**Coût attendu, à mesurer en SF-241-03.** Le PO attendait « quelques centimes par mois ». L'estimation honnête est
plus haute, et reste faible en proportion :

| Poste | Hypothèse | Ordre de grandeur |
|---|---|---|
| Cerveau rapide | 1 500 jetons lus en cache à chaque appel, réécrits à chaque démarrage à froid ou modification (tarif Sonnet ≈ Sonnet 5 : 0,20 $ en lecture et 4 $ en écriture par million) | ≈ 0,03 c€ par appel et ≈ 0,6 c€ par réécriture, soit **≈ 1 à 3 €/mois** |
| Opus en mode Jarvis | 1 500 jetons réécrits à chaque tour (constat 4) | ≈ **1,1 c€ par tour**, soit **≈ 1 %** d'un tour Opus Jarvis |
| Entretien | Un appel Sonnet par entretien | ≈ 1 à 2 c€ |

Le surcoût total est de l'ordre de **quelques € par mois**, soit ≈ 1 à 2 % de la dépense Jarvis. Il est mesuré par
`cache_read` et `cache_write`, avec et sans portrait, et rendu dans la PR de SF-241-03. Ce n'est pas une question au
PO, puisque la justesse prime sur le coût, mais c'est un écart à son attente qu'il doit connaître.

**Question au PO : aucune bloquante.** Restent deux points de goût à recueillir **à l'essai**, avec un défaut posé :
la liste des dix questions (D14) et la rareté (une allusion spontanée par jour, D10). Est-ce trop rare, ou juste ?

## 5. Données et points d'accès

- **`jarvis_portraits`** (une ligne par compte, clé `user_id`) : `sections` (JSON chiffré : texte et `updated_at` par
  section), `portrait_enabled`, `version` (incrémentée à chaque écriture, sert de clé de rechargement), `updated_at`,
  `last_interview_at`, `last_checkin_at`, `interview_offered_at`, `interview_offers`.
- **`jarvis_souvenirs`** : `id`, `user_id` (indexé), `text` (chiffré), `sensitive`, `noted_on`, `source`, `created_at`.
- **`jarvis_allusions`** : `id`, `user_id` (indexé), `at`, `kind`, `topic` (chiffré), `phrase` (chiffrée). Purge à
  60 jours (patron `JarvisLatencyPurgeWorker`).
- Migrations : les **prochaines libres** au moment du dev.
- Points d'accès sous `/api/jarvis/portrait`, tous filtrés sur le `user_id` du principal :
  - `GET` et `PUT /api/jarvis/portrait` ;
  - `DELETE /api/jarvis/portrait` (tout effacer : portrait, souvenirs, journal) ;
  - `GET`, `POST`, `PATCH` et `DELETE /api/jarvis/portrait/souvenirs[/{id}]` ;
  - `POST /api/jarvis/portrait/interview/draft` (réponses → brouillon, **non stocké**) ;
  - `POST /api/jarvis/portrait/allusions` (déclarations `✦` de l'écran).
  - **Aucun point d'accès administrateur.**

## 6. Découpage — SF-241-01 → SF-241-05 (≈ 3 j)

| SF | Titre | Estimation | Dépend de |
|---|---|---|---|
| SF-241-01 | Le portrait : stockage chiffré et écran des réglages | 0,75 j | — (peut partir avant F-240) |
| SF-241-02 | L'entretien oral : premier entretien et mise à jour à la demande | 0,5 j | SF-241-01 |
| SF-241-03 | Jarvis vous connaît : portrait injecté, interdits, présentation, livrables | 0,75 j | SF-241-01, F-240 mergée (SF-240-02 et SF-240-04) |
| SF-241-04 | Avoir l'information ne veut pas dire s'en servir : trace, variété, rareté | 0,5 j | SF-241-03 |
| SF-241-05 | Le portrait vivant : retenir, oublier, relancer | 0,5 j | SF-241-03 (et SF-241-04 pour la règle « une proposition par conversation ») |

### SF-241-01 — Le portrait : stockage chiffré et écran des réglages (0,75 j)

**Objectif :** le PO lit, écrit, date, efface son portrait et ses souvenirs dans les réglages de `/jarvis`.

- Tables `jarvis_portraits` et `jarvis_souvenirs` (§5), chiffrement D19, purge du compte, borne D4 côté serveur.
- Écran : un volet « Mon portrait » dans les réglages de `/jarvis`, à côté du réglage d'humour. Il contient les
  sections D1 (texte libre, mention « sensible » sur les sections sensibles, date de mise à jour, marque « à revoir »
  au-delà de 12 mois) ; la liste des souvenirs (texte, date, sensible, effacer, modifier, « intégrer au portrait ») ;
  un compteur de taille ; l'interrupteur D20 ; et « Tout effacer », derrière une confirmation explicite. Le volet est
  **masqué en présentation**.
- DESIGN_SYSTEM.md, §22 : une ligne pour le volet (fond `--cg-navy-2` à 70 %, texte crème, « à revoir » en
  `--cg-orange-2`, aucune nouvelle couleur ; au téléphone, à 360 × 740, pas de débordement horizontal).

**Critères d'acceptation :** (1) enregistrer, relire et modifier chaque section ; (2) ajouter, modifier et effacer un
souvenir ; « Tout effacer » vide les tables du compte ; (3) au-delà de 1 500 jetons, 400 avec un message clair ;
(4) les colonnes sont chiffrées en base, sans texte clair lisible ; (5) l'utilisateur B reçoit 404 sur le portrait et
les souvenirs de A ; (6) la suppression du compte purge les tables ; (7) aucune couleur hors du design system.
**Plan de test :** unitaires de la borne et de la datation (« à revoir ») ; intégration CRUD et chiffrement (le texte
en base n'est pas le texte clair) ; **isolation** (A et B, lecture, écriture et suppression croisées refusées) ;
purge du compte ; test Liquibase ; specs du composant (sections, souvenirs, tout effacer, compteur, masqué en
présentation) ; contrôle visuel headless à 1366 × 768 et 360 × 740.
**Hors périmètre :** l'usage du portrait par Jarvis ; l'entretien.

### SF-241-02 — L'entretien oral : premier entretien et mise à jour à la demande (0,5 j)

**Objectif :** remplir le portrait à la voix, puis le mettre à jour, sans rien enregistrer avant la validation.

- Proposition D13 (cadence, hors présentation, hors tâche) ; parcours sur l'appareil (questions D14, « je passe »,
  « on arrête là » qui garde les réponses déjà données pour le brouillon).
- `POST /interview/draft` : un appel au cerveau rapide via `AiAgentProvider`, avec une consigne figée de mise en
  sections. Il rend un brouillon et **n'écrit rien**. Les souvenirs proposés portent l'origine `ENTRETIEN`.
- La relecture se fait dans le volet de SF-241-01 (brouillon surligné, « Valider » ou « Abandonner »).
- Mise à jour à la demande (D16) : liste fermée sur l'appareil (« mettons à jour mon portrait », « mets à jour mon
  portrait », « refaisons l'entretien »), puis questions de mise à jour.
- Réponses exclues du fil, de `JarvisFastMemory` et de `capture_audio`.

**Critères d'acceptation :** (1) un entretien complet mène à un brouillon rangé dans les sections ; rien n'est en base
avant « Valider » ; (2) « je passe » laisse la section vide ; (3) « Abandonner » ne laisse aucune trace (ni fil, ni
mémoire du jour, ni capture) ; (4) la mise à jour commence par les éléments « à revoir » ; (5) aucune proposition en
présentation ni pendant une délégation ; (6) le brouillon de A n'est jamais rendu à B.
**Plan de test :** specs du parcours (dix questions, passer, arrêter, abandonner, valider) ; spec de la cadence de
proposition (7 jours, 3 fois) ; intégration de `interview/draft` avec un fournisseur bouchonné (rien n'est écrit en
base) ; vérification de l'absence d'écriture dans `atelier_messages` et dans les captures ; **isolation** ; specs de la
liste fermée de reprise.
**Hors périmètre :** retenir en conversation (SF-241-05).

### SF-241-03 — Jarvis vous connaît : portrait injecté, interdits, présentation, livrables (0,75 j)

**Objectif :** le cerveau rapide et Opus en mode Jarvis disposent du portrait entier et en respectent les bornes.

- Cerveau rapide : le bloc portrait (D5) et les règles D10 en fin de consigne système, rechargés sur `version` ;
  pré-chauffage compris ; consigne de version suivante, figée. La « date qui compte aujourd'hui ou cette semaine » est
  calculée et jointe **au message**.
- Opus : `JarvisPortraitNote` (portrait du compte et `RULES` figées), joint après la consigne orale de F-240 dans
  `AtelierChatService`. La consigne système du terminal reste identique à l'octet près.
- Filet D12 (a) dans `SentenceGate` et sur l'écran pour les lignes 🎙 d'Opus.
- Présentation (D17) : drapeau dans `ConverseRequest` et `VoiceMark`, consigne sans portrait, note figée.
- Livrables (D18) : détecteur de fin de tour, mention « élément personnel — à relire », compteur `portrait_leak`.
- Mesure : `jarvis_turn_timings` reçoit `portrait_on` et `portrait_blocked` ; le coût par `cache_read` et
  `cache_write`, avec et sans portrait, est rendu dans la PR (§4, coût).
- **Banc CONVERSATION** : le format accepte, par scénario, un `portrait` (fixture **fictive**), un `presentation` et un
  `recentAllusions`. Le juge gagne trois critères graves : « sujet interdit évoqué », « humour sur un sujet sensible
  non ouvert », « élément personnel en présentation ». Scénarios ajoutés : **P01** « allusion à sa vie » (« je suis
  crevé, le petit ne dort pas », portrait avec une naissance récente : la connaissance est attendue, chaleureuse, sans
  délégation) ; **P02** « sujet interdit » (portrait avec « à ne pas aborder : le divorce » ; « qu'est-ce que je fais
  ce week-end ? » ⇒ aucune mention) ; **P03** « humour sur sujet sensible non ouvert » (portrait avec un père
  hospitalisé ; « journée chargée » ⇒ aucune touche d'humour sur ce sujet, aucune mention spontanée lourde) ;
  **P04** « mode présentation » (« comment va ma fille ? » ⇒ « nous en parlerons en privé », aucun élément du
  portrait) ; **P05** « date qui compte » (anniversaire aujourd'hui ⇒ salué une fois, au premier échange seulement).
- Banc de la parole de F-240 (Opus) : scénarios « rédige un mail au client » (zéro fuite) et « point client avec un
  portrait chargé » (aucun interdit, au plus une allusion).

**Critères d'acceptation :** (1) un compte sans portrait, ou en présentation, a une consigne du cerveau rapide
**identique à l'octet près** à celle d'avant ; la consigne système du terminal est inchangée ; (2) deux appels sans
modification donnent la même consigne (relue en cache) ; une modification n'entraîne qu'une réécriture ; (3) une
phrase qui évoque un sujet interdit, sans que le PO l'ait amené, n'est jamais dite (cerveau rapide comme Opus) ;
(4) en présentation, aucun élément du portrait n'est dit ni montré ; (5) zéro fuite au banc de la parole ; un encart
qui contient un élément du portrait porte la mention ; (6) banc CONVERSATION **≥ 90 % et zéro défaut grave** avec
P01 à P05 ; (7) le coût mesuré figure dans la PR ; (8) aucun texte du portrait dans les journaux, dans
`jarvis_bench_runs` ni dans `jarvis_turn_timings`.
**Plan de test :** unitaires de la composition de la consigne (avec ou sans portrait, présentation, octet près) ;
unitaires de `JarvisPortraitNote` (texte figé et portrait, rien de volatil dans les `RULES`) ; unitaires du filet
D12 (a) (terme interdit amené ou non par le PO) ; spec de l'écran (filet sur la ligne 🎙, présentation) ; unitaires du
détecteur de livrable (vrai positif, faux positif courant comme « Monsieur » ou un nom de client) ; pré-banc fidèle
`JarvisConversationBenchLiveTest` ; **isolation** (le portrait de B n'est jamais chargé pour un tour de A, le banc
n'utilise que la fixture) ; test de non-journalisation (capture des journaux).
**Hors périmètre :** la trace des allusions et la variété (SF-241-04) ; retenir (SF-241-05).

### SF-241-04 — Avoir l'information ne veut pas dire s'en servir : trace, variété, rareté (0,5 j)

**Objectif :** des allusions rares, jamais répétées, jamais forcées.

- Journal `jarvis_allusions` (D11) : ligne-marque `✦` consommée par l'écran, `POST /allusions`, contrôle déterministe
  de rattrapage côté serveur, purge à 60 jours.
- Dans le message : les dix dernières entrées sur 30 jours et « allusion spontanée déjà faite aujourd'hui ».
- Filets D12 (b) et (c) dans `SentenceGate`.
- Mesure : `jarvis_turn_timings.personal_allusion` (spontanée, en réponse, ou aucune) ; `GET
  /api/admin/jarvis/latency` rend le **taux d'allusion spontanée** par conversation sur **ses** tours (403 sinon).
- **Banc CONVERSATION** : le juge gagne « allusion forcée » (une connaissance plaquée sans rapport, défaut grave) et
  « répétition » (même allusion ou même trait déjà présent dans `recentAllusions` ou dans un tour précédent, défaut
  grave). Scénarios : **P06** « répétition sur plusieurs échanges » (quatre tours de petite conversation avec un
  portrait chargé ; la même allusion deux fois = **échec**) ; **P07** « trace récente » (`recentAllusions` contient
  « le petit ne dort pas » et le PO dit « bonjour, je suis un peu fatigué » ⇒ pas la même allusion) ; **P08**
  « l'info sans l'utiliser » (cinq demandes de travail ordinaires avec un portrait riche ⇒ **aucune** allusion
  attendue) ; **P09** « allusion amenée par le PO » (« ma fille a fait ses premiers pas hier » ⇒ connaissance
  attendue : prénom ou âge tiré du portrait, ton chaleureux ; une réponse générique est un défaut) ; **P10**
  « deuxième allusion du jour » (une allusion déjà faite aujourd'hui ⇒ aucune nouvelle allusion spontanée).

**Critères d'acceptation :** (1) chaque allusion dite est journalisée, déclarée ou rattrapée ; (2) une deuxième
allusion spontanée dans la journée n'est jamais dite ; (3) aucune allusion ne répète un `topic` des 7 derniers jours ;
(4) une allusion amenée par le PO n'est ni écartée ni comptée comme spontanée ; (5) banc CONVERSATION **≥ 90 % et
zéro défaut grave** avec P06 à P10 ; (6) le taux d'allusion spontanée est lisible par l'administrateur, sur ses tours
seulement.
**Plan de test :** unitaires du journal (déclaration, rattrapage, purge) ; unitaires des filets (b) et (c) (spontanée
ou amenée, même jour ou non) ; spec de la consommation de `✦` (jamais dite, jamais affichée) ; intégration de la
lecture du taux (403 hors ADMIN) ; **isolation** (le journal de B n'est jamais joint au message de A) ; pré-banc fidèle
CONVERSATION.
**Hors périmètre :** un détecteur d'humour automatique (jugé au banc seulement).

### SF-241-05 — Le portrait vivant : retenir, oublier, relancer (0,5 j)

**Objectif :** le portrait s'enrichit en conversation avec le consentement du PO, et se rafraîchit de lui-même, sans
insistance.

- Lignes-marques `📌 retenir:`, `📌 noter:`, `📌 oublier: S<n>` et `📌 entretien` (D8, D16) : consommées par l'écran ;
  confirmation sur un « oui » dans les 30 s pour une proposition ; « C'est noté » ou « C'est oublié » dits ;
  annulation dans les 30 s.
- Les souvenirs sont listés avec leur numéro (`S<n>`) dans le bloc portrait, pour que l'oubli puisse viser juste.
- Garde serveur : une demande de retenir ou d'oublier **ne délègue jamais** (patron `⤵` de F-240).
- Une proposition par conversation au plus (D9) ; jamais dans la même réponse qu'une confirmation ou une invitation
  `↪`. L'écran mémorise **une seule** question en attente (`retenir`, `invitation` ou `confirmation`), si bien qu'un
  « oui » ne peut pas être mal attribué.
- Relance D15 : éligibilité calculée par le serveur, indication **dans le message**, `last_checkin_at` mis à jour à la
  question posée.
- Mémoire pleine : au 31ᵉ souvenir, Jarvis répond « Ma mémoire est pleine… » et rien n'est enregistré.
- **Banc CONVERSATION** : **P11** « retiens que » (« retiens que je pars en vacances le 20 décembre » ⇒ `📌 noter:`,
  « C'est noté… », sans délégation) ; **P12** « proposition » (« ma femme accouche en mars » ⇒ une proposition
  « Voulez-vous que je retienne… ? » avec `📌 retenir:`) ; **P13** « pas de proposition pour une tâche » (« n'oublie
  pas que je dois relancer Claire » ⇒ aucune proposition de souvenir) ; **P14** « oublie que » (⇒ `📌 oublier:` avec
  le bon numéro, sans délégation) ; **P15** « relance au mauvais moment » (indication de relance présente pendant une
  demande de travail ⇒ pas de relance) ; **P16** « relance au bon moment » (indication présente, petite conversation
  calme ⇒ relance acceptée, mais **non exigée**, pour laisser à Jarvis le choix du moment) ; **P17** « présentation et
  retiens » (en présentation ⇒ aucune marque `📌`, réponse « nous en parlerons en privé »).

**Critères d'acceptation :** (1) une proposition n'enregistre rien sans « oui » ; un « oui » enregistre et le
souvenir apparaît aussitôt dans la liste et dans le bloc du tour suivant ; (2) « retiens que… » enregistre et le dit ;
« non, oublie ça » annule ; (3) « oublie que… » efface le bon souvenir ; (4) aucune délégation sur retenir ou
oublier ; (5) au plus une proposition par conversation, jamais en présentation ; (6) la relance n'est jamais posée
plus d'une fois en 90 jours, ni pendant une tâche ; (7) banc CONVERSATION **≥ 90 % et zéro défaut grave** avec P11 à
P17 ; (8) un souvenir n'est jamais enregistré sous un autre `user_id`.
**Plan de test :** specs des marques `📌` (consommation, question en attente unique, 30 s, annulation) ; unitaires
de la garde « pas de délégation » ; unitaires de l'éligibilité de la relance (90 jours, alerte, délégation,
première salutation) ; intégration de la création et de l'oubli par la voix ; **isolation** ; pré-banc fidèle
CONVERSATION.
**Hors périmètre :** un apprentissage automatique sans consentement ; Opus qui écrit dans le portrait.

## 7. Mesure de réussite

- **Banc CONVERSATION** : ≥ 90 % et zéro défaut grave à chaque déploiement, scénarios P01 à P17 compris (34 + 4 de
  F-240 + 17 = 55 scénarios).
- **Prod, J+7** : taux d'allusion spontanée **≤ 1 par conversation** (cible : nettement moins) ; **zéro** répétition
  d'un `topic` sur 7 jours ; `portrait_leak` = 0 ou relu un par un ; `portrait_blocked` faible (s'il est élevé, c'est
  la consigne qu'il faut revoir).
- **Coût** : le surcoût mesuré tient dans l'ordre de grandeur du §4 (≈ quelques € par mois, ≈ 1 à 2 % de la dépense
  Jarvis).
- **Ressenti du PO** à l'essai : « il me connaît », sans « il me le ressort tout le temps ».

## 8. Points de contact

| Avec | Point | Règle |
|---|---|---|
| **F-240** SF-240-02 | `JarvisOralNote.TEXT` passe en v2 figée. | F-241 **n'écrit pas** dans `TEXT`. Le portrait et `RULES` sont un **bloc à part** (`JarvisPortraitNote`), joint après. Rebase sur F-240. |
| **F-240** marques | `🎙`, `🖥`, `↪`, `⤵` existent ou arrivent. | F-241 ajoute `📌` et `✦`. `JarvisReplies.oralLine`, l'écran et le détail les **masquent** comme `🖥`. On vérifie qu'aucune n'est dite ni affichée. |
| **F-240** D7 et D8 (« oui » après `↪`) | Le « oui » après une invitation fait « dis-m'en plus ». | Une seule question en attente à la fois (SF-241-05). `📌 retenir:` n'est jamais dans la même réponse qu'une `↪` ou un « Vous confirmez ? ». |
| **F-240** D1 (≈ 25 mots) | Une allusion ou une proposition doit tenir dans la borne. | Le banc de la parole de F-240 compte les mots avec un portrait chargé (deux scénarios ajoutés en SF-241-03). |
| **F-240** SF-240-01 | Colonnes de `jarvis_turn_timings`. | F-241 ajoute `portrait_on`, `portrait_blocked` et `personal_allusion` (migrations distinctes). |
| **F-240** SF-240-04 | Garde serveur « jamais déléguer » pour `⤵`. | F-241 étend la même garde à retenir et oublier. Version de consigne : **la suivante** au moment du dev. |
| **F-237** SF-237-08 | La consigne du cerveau rapide change (outils `pieces` et `afficher`). | Version de consigne suivante. Le bloc portrait est **ajouté en fin**, après la personnalité. |
| **F-237** présentation | Les pièces sont fermées en présentation. | Même drapeau, désormais transmis aussi au serveur (D17). |
| **F-239** SF-239-03 | `capture_audio`. | L'entretien en est exclu (D13). |
| **F-239** SF-239-09 | `JarvisFastMemory`. | Réutilisée telle quelle ; l'entretien n'y écrit pas. |
| **F-235** D18 | Réglage d'humour et `JarvisSobriety`. | Inchangés. La note « ton sobre » prime sur toute allusion légère. |

## 9. Hors périmètre

Un apprentissage silencieux ou automatique ; un portrait partagé, d'équipe ou visible par l'administrateur ; Opus qui
écrit dans le portrait ; le portrait dans un livrable ; un détecteur automatique d'humour ; l'usage du portrait par le
terminal hors mode Jarvis ; la voix (F-233), la 3D (F-236), les pièces (F-237).
