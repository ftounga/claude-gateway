# Le Radar du client — la mémoire de l'organisation

> Cadrage commun de **F-99 → F-105**, du 2026-09-13. **Cadrage seul : la livraison attend le go du
> PO.** Maquette présentée au PO : `maquette-radar.html` dans ce dossier, publiée sur
> https://claude.ai/code/artifact/bd131ed1-2bc4-4d35-b7ec-effed59563ba.
> Dépend du volet Teams (F-87 → F-91, livré) et de la Forge refondue (F-98, cadrée) pour l'onglet.

---

## 1. Le besoin, dans les mots du PO

> « J'ai un client chez qui la complexité ne se trouve pas dans l'infra, mais plutôt dans
> l'organisationnel : je suis mentionné dans plusieurs groupes, chats, threads, réunions ; il faut que
> je fasse des actions, que je mette untel en relation avec untel, que je relance ; que si mon manager
> me demande où en est tel sujet, je puisse lui dire, ou du moins que je sache à qui aller poser la
> question. […] Un tableau de bord qui a recueilli les infos, qui le fait quotidiennement, et affiche
> les sujets en cours. Je peux l'aider à mettre à jour certains sujets en lui donnant des updates.
> Pareil pour les réunions que j'ai enregistrées moi-même, en dehors de Teams. […] Je veux un truc
> abouti. La solution doit être complète et surtout robuste. L'interface graphique et l'expérience
> utilisateur doivent être au rendez-vous. »

Précisions du PO après la maquette :

> « J'ai dit chaque jour. Bien sûr que je peux activer la synchro moi-même. Peut-être en fin de
> soirée, vu que ça peut être très long. J'ose espérer que tu sauras quand un sujet est clôturé. Ou
> même moi je peux le dire avec le bouton Donner la nouvelle : tel sujet peut être considéré comme
> clos. »

## 2. Ce que le volet Teams ne fait pas, et pourquoi il ne suffit pas

| | Volet Teams (F-87 → F-91) | Le Radar |
|---|---|---|
| Qui déclenche | l'utilisateur, par une question | **la synchro du soir**, et l'utilisateur quand il veut |
| Mémoire | **aucune** : chaque question relit Teams depuis zéro | un **registre** qui s'accumule |
| Unité | la conversation, la réunion | **le sujet**, qui traverse les sources |
| Sources | Teams | Teams (**tout ce qui a bougé**, découvert seul), **enregistrements hors Teams**, **nouvelles de l'utilisateur**, **courriels collés** par l'utilisateur |
| Ce qu'on n'a pas demandé | invisible | **signalé** : promesse oubliée, relance due, sujet silencieux, tâche sans porteur |
| Forme | un terminal, sans boutons | **un écran pour l'état, le chat pour le nourrir** |

**Pourquoi un terminal ne suffit pas.** Un terminal répond à ce qu'on lui demande. La difficulté du
PO, c'est ce qu'il **oublie** de demander. Il faut quelque chose qui regarde à sa place, tous les
jours, et qui se souvienne.

**Ce qui ne contredit pas la décision fondatrice de F-87** (« Teams n'est pas un écran à boutons ») :
cette décision visait les **questions**, qu'on ne peut pas prévoir. Elles restent des conversations.
Le Radar ajoute un écran pour un **état** (des sujets, des engagements), et les seuls boutons portent
sur cet état : *fait*, *pas moi*, *reporter*, *clore*.

**Le parallèle avec la gouvernance** : la carte du poste (F-92) retient ce qu'on sait de
l'**infrastructure** du client, et chaque projet l'enrichit. Le Radar retient ce qu'on sait de son
**organisation**, et chaque échange l'enrichit.

## 3. Les objets

| Objet | Ce qu'il porte |
|---|---|
| **Sujet** | nom, alias, état (`nouveau`, `avance`, `en attente`, `bloqué`, `en sommeil`, `clos ?`, `clos`), résumé sourcé, prochaine étape, échéance connue, dernière activité |
| **Personne** | identité de la source (nom, adresse, fonction si Teams la fournit), rôle **par sujet** : `décide`, `pilote`, `expert`, `informé` |
| **Engagement** | qui doit quoi à qui : **moi → autre** (« à faire par moi »), **autre → moi** (« j'attends des autres »), **mise en relation** (moi → A et B) ; échéance explicite ou résolue depuis la date du message (« jeudi ») ; statut `ouvert`, `tenu`, `reporté`, `abandonné` ; certitude |
| **Preuve** | source (`teams_message`, `teams_meeting`, `local_recording`, `user_note`, `pasted_mail`), identifiant stable dans la source, horodatage (à la seconde pour une réunion), **citation courte**, lien profond |
| **Synchro** | début, fin, par source : curseur, éléments lus, échecs, **couverture** ; consommation |

**Règle d'isolation** : tout objet porte `user_id` **et** `host_id`. Le Radar d'EDENRED ne voit
jamais celui de CAGIP, y compris dans une invite au modèle.

## 4. Les règles qui empêchent le Radar de mentir

1. **Pas de fait sans preuve.** Un engagement, un état, une phrase du résumé sans au moins une preuve
   **n'est pas enregistré**. Le résumé est rendu phrase par phrase, chacune avec ses renvois.
2. **L'utilisateur a le dernier mot.** Toute correction (*pas moi*, *fait*, *ce n'est pas le même
   sujet*, *fusionner*, *clore*) est **souveraine** : marquée comme telle en base, jamais écrasée par
   une synchro. Elle alimente les alias et les consignes de rattachement, pour ne pas refaire l'erreur.
3. **La certitude en toutes lettres** : `certain` (« je m'en charge », « je te l'envoie jeudi ») ou
   `probable` (tâche évoquée sans porteur, échéance déduite). **Jamais un score.** Un `probable` est
   présenté comme une question.
4. **Il dit ce qu'il n'a pas lu.** Chaque synchro produit sa **couverture**. Une synchro partielle
   produit un résumé qui **le dit en tête**, jamais un résumé qui a l'air complet.
5. **Il ne parle jamais à la place de l'utilisateur.** Relances et présentations sont **préparées**,
   copiées ou ouvertes dans la conversation d'origine ; **rien n'est écrit** dans Teams
   (hors périmètre inchangé de F-87).
6. **Des extraits, pas des archives.** On persiste le fait, la citation courte (≤ 280 caractères) et
   le lien. Le texte brut transmis pour analyse est **supprimé dès l'analyse réussie** (au plus tard
   7 jours, pour permettre une reprise). **Clôturer la mission (F-60) purge le Radar du poste**, après
   proposition d'un export.

## 5. La synchro du soir (tranché par le PO)

- **Chaque jour, à une heure choisie** par poste, **22 h 00 par défaut**, et un bouton **« Synchroniser
  maintenant »** disponible à tout moment.
- **Deux temps, deux lieux.**
  1. **La collecte**, sur la machine, par le runner, avec le navigateur relié (F-87) : c'est la partie
     **longue** (défilement des fils, pagination), et la seule qui exige que le portable soit allumé.
  2. **L'analyse**, dans la gateway, **en tâche de fond** (règle async de `CLAUDE.md`, modèle
     d'`OcrPollingWorker` / `IngestionWorker`) : elle continue **portable fermé**.
- **Incrémentale** : un curseur par source et par poste. Seul le nouveau depuis la dernière collecte
  réussie est lu. **La première synchro** remonte sur une fenêtre bornée (30 jours par défaut) : c'est
  la seule vraiment longue, et l'écran l'annonce avec sa progression.
- **Reprenable** : points de reprise par conversation. Interrompue (veille, réseau, Teams qui
  redemande l'authentification), elle reprend où elle en était ; **idempotente** par identifiant de
  source (contrainte d'unicité), jamais de doublon.
- **Portable fermé à l'heure prévue** : la synchro part **à la prochaine connexion** du runner, et le
  résumé du matin dit « synchro d'hier soir non faite, rattrapée à 8 h 12 ».
- **Session Microsoft expirée** : échec **bruyant** avec le geste (« rouvrez Teams dans Chrome »),
  grâce à la sonde de santé de F-87. Jamais un résumé vide présenté comme calme.
- **Progression visible** et **annulable** ; la synchro en cours ne bloque ni les terminaux ni les
  commandes du poste.

## 6. Quand un sujet se termine (tranché par le PO)

| Cas | Comportement |
|---|---|
| **Signal explicite dans une source** : « on peut fermer », « c'est clos », ticket fermé, dernier engagement tenu et remercié | le sujet passe en **`clos ?`** avec la phrase qui le justifie ; **un clic** pour confirmer ou refuser |
| **L'utilisateur le dit** dans *Donner la nouvelle* : « le sujet LDAP peut être considéré comme clos » | **clos immédiatement**, sans confirmation : parole souveraine |
| **Silence** prolongé (21 jours par défaut) | **jamais clos** : passe `en sommeil`, et le résumé demande ce qu'il en est |
| **Nouvelle activité sur un sujet clos** | **pas de réouverture silencieuse** : le résumé annonce « le sujet LDAP se réveille », l'utilisateur rouvre ou rattache ailleurs |

Un sujet clos sort des listes par défaut, reste consultable et cherchable, et **ses engagements
ouverts sont signalés** au moment de la clôture (« 1 engagement encore ouvert : le fermer aussi ? »).

## 7. La lecture des échanges : le cœur, et la vraie difficulté

**Gateway-First respecté** : la gateway **rassemble la matière, borne la dépense et lit une forme** ;
l'analyse est une question posée au fournisseur par l'abstraction `AIProvider`, exactement comme le
juge indépendant de F-94. Aucune logique de « compréhension » n'est codée en dur.

- **La difficulté** : reconnaître qu'un échange parle d'un sujet déjà suivi quand personne ne le nomme
  pareil (« le MFA », « la double auth des presta », « le chantier Okta »). Réponse : l'invite porte
  la **liste des sujets ouverts avec leurs alias et leur résumé**, le modèle propose un rattachement
  **ou** un nouveau sujet (`nouveau`, présenté comme tel), et **les fusions et séparations de
  l'utilisateur deviennent des alias**.
- **Deux passes pour borner le coût** : un tri rapide (« cet échange contient-il un engagement, une
  décision, un blocage, une date, une clôture ? ») sur un modèle rapide, puis l'extraction sur les
  seuls échanges retenus. **Cache de prompt** sur le registre des sujets, stable d'un lot à l'autre.
- **Forme de sortie bornée** (bloc final strict, comme `===VERDICT===` en F-94) ; une sortie illisible
  **n'écrit rien** et compte comme un échec de couverture.
- **Enveloppe** : chaque synchro connaît son plafond de consommation. Plafond atteint : elle s'arrête
  **proprement**, garde son curseur, et le résumé le dit.

## 8. Les écrans

> *Amendement F-106 (2026-09-13)* : le Radar vit dans la **Vigie**, l'espace du pilotage, et non dans
> un onglet de la Forge. Tout ce qui suit s'applique à l'onglet Radar du client dans la Vigie.

**Où** : un onglet **Radar** dans le poste de la Forge refondue (F-98), en premier quand l'option est
active, avec son compte « 5 à traiter ». Le bandeau de flotte de F-98 gagne « 2 relances dues ».

- **Le résumé du matin** : ce qui a bougé depuis la veille en trois phrases maximum, les compteurs
  (à faire par moi, relances dues, mises en relation, sujets suivis), **la couverture** de la synchro
  et le bouton *Synchroniser maintenant*.
- **Trois colonnes** : *À faire par moi* · *Sujets en cours* · *J'attends des autres*. Les éléments
  dus ou en retard portent un filet orange ; les `probable` sont posés comme des questions (*C'est
  moi / Pas moi*).
- **La page d'un sujet** : état, prochaine étape, échéance ; **résumé dont chaque phrase renvoie à sa
  preuve** ; **chronologie multi-sources** (icône par source, citation, lien au message ou à la seconde
  de réunion) ; les personnes et leur rôle ; **« Ce que le Radar ne sait pas »** et **à qui demander** ;
  **« Réponse préparée pour votre manager »** (*Copier*, *Ajuster en discutant*).
- **L'annuaire** : les personnes rencontrées, leurs sujets, leur rôle, la dernière interaction.
- **Donner la nouvelle** : un champ en bas du Radar, texte libre, joindre un document, déposer un
  enregistrement.
- **Téléphone** : colonnes empilées, résumé en premier.
- `DESIGN_SYSTEM.md` gagne un **§17 — Le Radar** : états de sujet (réemploi de §10 et §12, bleu §9
  index 0 pour `nouveau` et `clos ?`), icônes de source, rendu d'une preuve. **Aucune couleur hors
  charte.**

## 9. Nourrir le Radar

- **Les nouvelles** : *Donner la nouvelle* ouvre un **tour d'agent** muni d'outils Radar
  (`radar_find_subject`, `radar_update_subject`, `radar_close_subject`, `radar_add_engagement`,
  `radar_mark_engagement`, `radar_merge_subjects`). Le texte de l'utilisateur **est** la preuve
  (`user_note`). L'agent **montre ce qu'il a compris** avant d'écrire (« Je note : pilote MFA en
  octobre ; sujet LDAP clos. ») ; pas de confirmation supplémentaire, mais **tout est annulable**
  depuis la chronologie.
- **Les mêmes outils** sont ajoutés au catalogue du **terminal Teams** (F-89) : « où en est le MFA ? »
  y trouve sa réponse dans le registre, sans relire Teams.
- **Les enregistrements hors Teams** (téléphone, salle, autre outil de visio) :
  - nominal : un **dossier de dépôt** sur le poste (`<racine>/radar/depot/`), relevé par la synchro,
    transcrit **sur la machine** par le moteur de F-91 ; seul le texte remonte ;
  - depuis l'écran : *Déposer un enregistrement* envoie le fichier **au runner** du poste (relais
    découpé, taille bornée — plafond à fixer en mini-spec), jamais stocké par la gateway ;
  - la date et le titre de la réunion sont demandés au dépôt, pour placer l'enregistrement dans la
    chronologie.
- **Les relances et présentations préparées** : un brouillon, dans la langue et le ton du fil
  d'origine, *Copier* ou *Ouvrir la conversation*. Envoyer reste un geste de l'utilisateur.

## 10. Outlook — retiré par le PO

> **Décision du PO, 2026-09-13** : *« on peut retirer Outlook. Le volume de mails est trop important en
> entreprise. Au pire l'utilisateur va checker ses mails et venir lui-même faire des updates dans
> l'application en copiant leur contenu. »*

**F-105 est retirée.** Un courriel entre dans le Radar **par l'utilisateur** : il le colle dans
*Donner la nouvelle* (SF-104-02). L'écran reconnaît l'en-tête collé (`De :` / `From:`, `Envoyé :` /
`Sent:`, `À :`, `Objet :`) : la preuve est **datée du courriel**, pas du collage, son expéditeur est
rattaché à l'annuaire, et seule la citation courte est conservée (règle §4.6). C'est aussi un **tri
humain** : n'entre que ce que l'utilisateur juge utile, ce qu'aucun filtre automatique ne ferait
aussi bien sur un volume d'entreprise.

## 11. Le droit et le prix

> *Amendement F-107 (2026-09-13)* : l'option Radar est portée par l'**option Vigie** (Teams + Radar),
> à côté d'un **Gold Vigie**. Les règles ci-dessous (enveloppe dédiée, essai de deux semaines) sont
> conservées telles quelles.

**Tranché par le PO le 2026-09-13 : une option « Radar » à part**, qui **suppose l'option Teams**, et
**un essai de deux semaines** par code d'accès pour mesurer le coût réel avant de fixer le montant.
Le code d'essai ouvre **Teams et Radar ensemble**, avec une enveloppe d'essai (F-62 à étendre en
F-99). **Unité recommandée : le client suivi** (un poste synchronisé), le coût croissant avec le
nombre de clients, pas avec le nombre de questions.

- **Profil de coût différent** : Teams consomme **quand on pose une question** ; le Radar consomme
  **tous les soirs**, même quand personne ne s'en sert. L'inclure dans Teams ferait payer la synchro à
  ceux qui n'en veulent pas, ou obligerait à monter le prix de Teams pour tous.
- **La synchro ne doit jamais manger le quota des conversations.** C'est une **variante assumée** de
  la doctrine actuelle (`TeamsEntitlementService` : « l'option ouvre l'accès, elle n'ajoute pas de
  jetons ») : l'option Radar apporte **sa propre enveloppe mensuelle de synchro**, décomptée à part.
  Sans cela, un utilisateur découvrirait au réveil que la synchro de la nuit a consommé son quota de
  la journée. En BYOK, la consommation est sur sa clé et l'enveloppe devient un **plafond qui protège
  sa facture**.
- **Valeur différente** : Teams sert à interroger ; le Radar sert à piloter, et à répondre à un
  manager. C'est une montée en gamme naturelle.
- **Le montant : À CONFIRMER PAR LE PO.** Méthode proposée : **deux semaines d'essai offert** (code
  d'accès F-62) sur le client qui motive la demande, **coût réel mesuré par synchro**, puis prix et
  enveloppe fixés sur la mesure. Aucun agent de vague ne crée de prix ni ne touche Stripe.

## 12. Découpage

| Feature | Titre | Contenu |
|---|---|---|
| **F-99** | Le registre de l'organisation | Modèle (sujets, alias, personnes, rôles, engagements, preuves, synchros) et migrations ; corrections souveraines ; fusion et séparation de sujets ; clôture (§6) ; purge à la clôture de mission et export ; **droit de l'option Radar et enveloppe dédiée** |
| **F-100** | La synchro du soir | Planification par poste (22 h par défaut), *Synchroniser maintenant*, collecte runner incrémentale et reprenable, rattrapage à la connexion, couverture, progression, annulation ; relevé du dossier de dépôt |
| **F-101** | La lecture des échanges | Tri puis extraction via `AIProvider`, rattachement aux sujets avec alias, engagements, relances dues, mises en relation, signaux de clôture, certitude, forme de sortie stricte, enveloppe, suppression du brut après analyse |
| **F-102** | Le Radar et le résumé du matin | Onglet Radar (F-98), résumé, couverture, trois colonnes, gestes *fait / pas moi / reporter / clore*, compteur de flotte, téléphone, charte §17 |
| **F-103** | La page sujet | État, résumé sourcé phrase par phrase, chronologie multi-sources, personnes et rôles, « ce que le Radar ne sait pas », à qui demander, réponse préparée au manager ; annuaire |
| **F-104** | Nourrir le Radar | *Donner la nouvelle* (tour d'agent, outils Radar, compréhension affichée, annulation) ; outils Radar au terminal Teams ; enregistrements hors Teams (dossier de dépôt, dépôt depuis l'écran, transcription F-91) ; relances et présentations préparées |
| ~~**F-105**~~ | ~~Outlook~~ | **Retirée par le PO** (§10) : les courriels entrent collés par l'utilisateur (SF-104-02) |

**Ordre** : F-99 → F-100 → F-101 → (F-102 ∥ F-103) → F-104. Le premier usage réel arrive avec
F-102 : Teams seul, synchro du soir, résumé du matin.

## 12 bis. Découpage en sous-features (2026-09-13)

> Tient compte de F-106 (le Radar vit dans la **Vigie**) et de F-107 (le **droit** et la **réserve**
> relèvent de l'option Vigie : SF-107-03 et SF-107-04). F-99 ne porte donc plus le droit.

### F-99 — Le registre de l'organisation

| SF | Titre | Contenu |
|---|---|---|
| SF-99-01 | Le modèle | Migrations `radar_subjects`, `radar_subject_aliases`, `radar_people`, `radar_subject_roles`, `radar_commitments`, `radar_evidence`, `radar_syncs` ; `user_id` + `host_id` partout ; contrainte d'unicité par identifiant de source ; tests d'isolation entre deux postes et deux utilisateurs |
| SF-99-02 | Les corrections souveraines | Marquage « souverain », jamais écrasé par une synchro, journal des corrections, annulation depuis la chronologie |
| SF-99-03 | Fusionner, séparer, les alias | Fusion et séparation de sujets avec leurs preuves et engagements ; alias appris des corrections |
| SF-99-04 | La clôture d'un sujet | États `clos ?` / `clos` / `en sommeil` (21 jours) ; réveil d'un sujet clos annoncé, jamais rouvert en silence ; engagements ouverts signalés à la clôture |
| SF-99-05 | La purge et l'export | Purge à la clôture de mission (F-60) et au retrait de la Vigie (F-106), export Markdown proposé avant |

### F-100 — La synchro du soir

> **Constat du 2026-09-13, sur question du PO** (« comment sait-il où récupérer l'enregistrement et la
> transcription ? il trouve l'URL comment ? »), vérifié dans le runner :
> - le runner **ne cherche aucune adresse** : il observe ce que Teams web charge dans le Chrome de
>   l'utilisateur, et classe chaque réponse par son chemin (`TeamsUrls`). Une réunion n'est connue que
>   si Teams l'a chargée depuis le rattachement ;
> - **la transcription** n'est lue que si Teams l'a servie, c'est-à-dire si l'onglet Transcription a été
>   ouvert : l'outil répond aujourd'hui « ouvrez la transcription dans Teams, puis redemandez »
>   (`TeamsTools.meetingTranscript`). **Incompatible avec une synchro de nuit autonome** ;
> - **l'enregistrement n'est jamais téléchargé**, par décision de sécurité : l'adresse signée perd sa
>   chaîne de requête à l'entrée (SF-87-02) — l'outil ne rend que « annonce un enregistrement » et le
>   lien ;
> - les gestes permis sont `scroll`, `nudge` et `show` (ouvrir un **fil**), par `Runtime.evaluate`
>   seulement — ni `Page.navigate`, ni `Input.dispatch*` (`PageGestures`). **Aucun geste n'ouvre une
>   réunion ni son onglet Transcription** ;
> - `TeamsUrls` ne reconnaît que les hôtes Teams. Or Microsoft range enregistrements et transcriptions
>   dans **OneDrive / SharePoint** (Stream) : les réponses de ces hôtes sont vraisemblablement classées
>   `UNKNOWN`. **À vérifier sur un vrai poste** : l'adaptateur n'a été éprouvé que sur des jeux de test
>   (`runner/src/test/resources/teams/`), jamais sur un tenant client.
>
> D'où **SF-100-00**, **SF-100-01** et le contenu élargi de SF-100-03 ci-dessous.

**Rien à déclarer à l'avance — correction du PO, 2026-09-13.** Une première version proposait de
coller à l'activation les liens des fils à suivre. Le PO l'a refusée : *« ça signifie déjà savoir à
l'avance quoi suivre »*. C'est tout le problème qu'il décrit : les sujets naissent dans des fils qu'on
ne connaissait pas la veille. **Le Radar découvre, l'utilisateur écarte après coup.**

- **Ce qui est lu** : **toutes les conversations** (privées, de groupe, de réunion) **actives depuis la
  dernière synchro**, découvertes dans la liste que Teams charge lui-même, triée par activité — on la
  parcourt jusqu'à atteindre des fils plus anciens que le curseur. Dans les **canaux d'équipe**, souvent
  très volumineux : les fils où l'utilisateur a **écrit, répondu ou été mentionné** ; les autres canaux
  actifs sont **comptés** dans la couverture (« 6 canaux actifs non lus ») et l'utilisateur peut dire
  *lire ce canal* depuis le résumé.
- **Les exclusions s'apprennent** : sur un sujet, une preuve ou dans la couverture, *ignorer ce fil* —
  correction souveraine (règle §4.2), jamais remise en cause par une synchro, annulable.
- **Le volume est tenu par la mécanique, pas par un périmètre** : le tri rapide écarte l'essentiel des
  messages avant l'extraction (SF-101-02), la réserve borne la dépense (SF-101-05), et une synchro qui
  s'arrête à réserve épuisée **le dit**.
- **Les adresses techniques ne sont jamais demandées, et il n'y a rien à détecter** — remarque du PO,
  vérifiée : *« ces URL sont toujours les mêmes pour tous les clients, c'est juste le fait qu'on soit
  déjà authentifié qui fera la différence »*. Teams (`teams.microsoft.com`, `teams.cloud.microsoft`)
  est le même pour tous ; SharePoint et OneDrive portent le nom du tenant en préfixe
  (`<tenant>.sharepoint.com`, `<tenant>-my.sharepoint.com`), mais **le motif est universel** et se
  reconnaît dans le code sans rien demander. Les chemins sont ceux d'un même service en ligne : **un
  seul relevé réel suffit pour tous les clients**. *Seule exception connue : les clouds souverains
  (`sharepoint.us`, `sharepoint.cn`), hors de la clientèle visée.*
- **Le runner n'a pas à retrouver ces adresses** : c'est Teams qui les appelle en s'affichant, le runner
  observe. **La vraie question est : les voit-il ?** Vérifié dans le code (`BrowserTargets`,
  `NetworkObserver`) : il se rattache à **un seul onglet**, celui de Teams, et à rien d'autre. Trois
  angles morts possibles, que seul le relevé réel tranchera :
  1. **un autre onglet** — une transcription ou un enregistrement ouvert dans un onglet SharePoint /
     Stream n'est pas observé ;
  2. **un cadre intégré d'un autre site** — le lecteur Stream intégré à Teams vient de
     `*.sharepoint.com` ; avec l'isolation des sites de Chrome, un tel cadre tourne dans son propre
     processus et **son trafic n'apparaît pas** dans celui de l'onglet ;
  3. **un service worker** — ses requêtes n'apparaissent pas non plus dans l'onglet.
  **Si l'un d'eux se confirme**, le remède est de se rattacher aussi aux cadres et workers **de la
  page Teams, et seulement aux hôtes Microsoft** (`Target.setAutoAttach` filtré). C'est **une commande
  de plus dans la liste blanche CDP** : décision de sécurité **à soumettre au PO** avec le résultat du
  relevé, jamais prise en douce.
- **Ce qui change d'un client à l'autre, c'est la session et les droits**, pas les adresses : la
  politique du tenant peut désactiver la transcription, et l'accès à une transcription ou à un
  enregistrement dépend du rôle dans la réunion (organisateur, participant, invité).

| SF | Titre | Contenu |
|---|---|---|
| **SF-100-00** | **Le relevé réel** | **Préalable au développement du Radar, fait une seule fois** (les adresses sont les mêmes pour tous les clients). Sur un vrai poste d'entreprise — celui du client qui motive la demande, avec l'accord du PO : l'utilisateur ouvre un fil, une réunion passée, son récapitulatif, sa transcription. Le runner relève **hôtes, chemins et formes** des réponses (jamais les corps ni les requêtes). Livrable : la table réelle des chemins (Teams, et SharePoint / OneDrive reconnus par motif `*.sharepoint.com`), les écarts avec `TeamsUrls`, les gestes nécessaires, et **pour chaque réponse utile : vue depuis l'onglet Teams, ou seulement depuis un autre onglet, un cadre intégré ou un worker** (les trois angles morts). Aucune donnée client ne quitte la machine |
| **SF-100-01** | **La vérification guidée** | À l'activation d'un client dans la Vigie (F-106), **sans rien déclarer de ce qu'il faut suivre** : **vérification guidée** — ouvrir un fil suivi, puis une réunion passée et sa transcription ; l'écran coche ce que le runner a vu (✓ session Microsoft active, ✓ conversations, ✓ réunions, ✓ transcriptions). **Aucune adresse n'est demandée ni détectée.** La vérification sert à ce qui change vraiment d'un client à l'autre : **la session et les droits** — un « ✗ transcriptions » dit que la politique du client les désactive ou que l'accès est refusé, et le Radar l'annonce au lieu de laisser croire que les réunions sont vides |
| SF-100-02 | La planification | Heure par poste (22 h par défaut), *Synchroniser maintenant*, **une seule synchro à la fois par poste** tous pods confondus, rattrapage à la prochaine connexion du runner |
| SF-100-03 | La collecte Teams incrémentale | Runner : **découverte des fils actifs** depuis la dernière synchro dans la liste de conversations de Teams (parcourue jusqu'au curseur), canaux d'équipe limités aux fils où l'utilisateur a écrit, répondu ou été mentionné, fils ignorés écartés ; curseur par fil, points de reprise, fenêtre de 30 jours à la première synchro, remontée par lots idempotents. **Pour les réunions** : deux gestes nouveaux sur le modèle de `show` — *afficher le calendrier sur une période* et *afficher une réunion et son onglet Transcription* (vue de l'utilisateur remise ensuite) ; **reconnaissance par motif de SharePoint / OneDrive** (`*.sharepoint.com`, `*-my.sharepoint.com`) dans l'adaptateur unique, sans configuration par client. Toujours par `Runtime.evaluate`, sans `Page.navigate` : la liste blanche CDP ne bouge pas. **La vidéo n'est pas nécessaire au Radar** : l'enregistrement reste où il est |
| SF-100-04 | La couverture et la progression | Rapport par source et par fil (lu, échoué, sans transcription, canaux actifs non lus), échecs bruyants avec le geste (session Microsoft expirée), progression et annulation ; *lire ce canal* et *ignorer ce fil* depuis la couverture |
| SF-100-05 | Le dossier de dépôt | `<racine>/radar/depot/` relevé par la synchro, transcription sur la machine (F-91), seul le texte remonte |

### F-101 — La lecture des échanges

| SF | Titre | Contenu |
|---|---|---|
| SF-101-01 | La file d'analyse | Worker asynchrone, lots, reprises, **suppression du texte brut** après analyse (7 jours au plus) |
| SF-101-02 | Le tri | Passe rapide « engagement, décision, blocage, date, clôture ? », modèle rapide via `AIProvider` |
| SF-101-03 | L'extraction et le rattachement | Invite portant les sujets ouverts, alias et résumés (cache de prompt) ; forme de sortie stricte ; **aucun fait sans preuve** ; certitude en toutes lettres |
| SF-101-04 | Engagements, relances, mises en relation | Échéances résolues depuis la date du message, relance due (3 jours ouvrés par défaut), mises en relation, signaux de clôture |
| SF-101-05 | La réserve et la mesure | Arrêt propre à réserve épuisée, coût relevé par synchro (sert l'essai de F-107), taux de corrections « pas le même sujet » |

### F-102 — Le Radar et le résumé du matin *(dans la Vigie)*

| SF | Titre | Contenu |
|---|---|---|
| SF-102-01 | Le résumé du matin | Ce qui a bougé (3 phrases au plus), compteurs, couverture, *Synchroniser maintenant* |
| SF-102-02 | Les trois colonnes et les gestes | *À faire par moi* · *Sujets en cours* · *J'attends des autres* ; *fait / pas moi / reporter / clore* ; les `probable` posés en questions |
| SF-102-03 | Flotte, téléphone, charte | Compteur dans le bandeau de la Vigie, écran téléphone, `DESIGN_SYSTEM.md` §17 |

### F-103 — La page sujet

| SF | Titre | Contenu |
|---|---|---|
| SF-103-01 | État, résumé sourcé, chronologie | Chaque phrase renvoie à sa preuve ; chronologie multi-sources avec liens profonds |
| SF-103-02 | Qui, et à qui demander | Personnes et rôles, « ce que le Radar ne sait pas », la personne à interroger |
| SF-103-03 | La réponse au manager | Réponse préparée, *Copier*, *Ajuster en discutant* |
| SF-103-04 | L'annuaire | Personnes rencontrées, leurs sujets, leur rôle, dernière interaction |

### F-104 — Nourrir le Radar

| SF | Titre | Contenu |
|---|---|---|
| SF-104-01 | Les outils Radar | `radar_find_subject`, `radar_update_subject`, `radar_close_subject`, `radar_add_engagement`, `radar_mark_engagement`, `radar_merge_subjects`, gardés par le droit Vigie dans `buildTools` |
| SF-104-02 | Donner la nouvelle | Tour d'agent, compréhension affichée, preuve `user_note`, annulation ; **coller un courriel** : en-tête reconnu (`De :` / `From:`, `Envoyé :` / `Sent:`, `À :`, `Objet :`), preuve `pasted_mail` **datée du courriel**, expéditeur rattaché à l'annuaire, citation courte seule conservée |
| SF-104-03 | Le Radar au terminal Teams | Les mêmes outils dans le catalogue du terminal de conversation |
| SF-104-04 | Déposer un enregistrement | Depuis l'écran, relayé au runner par morceaux, taille bornée, date et titre demandés |
| SF-104-05 | Relances et présentations préparées | Brouillon dans le ton du fil, *Copier* / *Ouvrir la conversation*, jamais envoyé |

### ~~F-105 — Outlook~~ — retirée par le PO le 2026-09-13

Voir §10. Sa seule trace est dans SF-104-02 : **coller un courriel**.

## 13. Préoccupations transversales

- **Plans / limites : oui.** Composants impactés : `TeamsEntitlementService` (prérequis), nouveau
  droit Radar sur le motif F-40 / F-89, `EntitlementService`, décompte d'usage par client (F-61),
  accès offerts (F-62). L'enveloppe dédiée est une **variante** de la doctrine « l'option n'ajoute pas
  de jetons » : à valider par le PO avec le montant.
- **Contexte tenant : oui.** Toute lecture et toute invite filtrées par `user_id` **et** `host_id` ;
  test d'isolation entre deux postes du même utilisateur et entre deux utilisateurs.
- **Navigation : oui.** Onglet Radar et page sujet sous la route de poste de F-98 (`/forge/:hostRef`,
  `?onglet=radar`, `/forge/:hostRef/radar/sujets/:id`).
- **Auth / Principal : non.**

## 14. Risques, écrits

- **Contractuel, chez le client.** Le Radar conserve des extraits de communications internes du client
  dans l'application. Certains contrats l'interdisent. **Prérequis de mise en service**, rappelé à
  l'activation : l'utilisateur confirme que son client l'autorise. Cloisonnement par poste, purge à la
  clôture, pas d'archives (§4.6).
- **Données personnelles de tiers** (collègues du client) : minimisation (citations courtes, pas de
  contenus entiers), purge, export et suppression à la demande.
- **Qualité du rattachement** : c'est le point qui décidera de l'adoption. Mesure prévue en F-101 : taux
  de corrections *ce n'est pas le même sujet* par synchro, visible par le PO.
- **Tenue dans le temps** : refontes Teams, portées par les adaptateurs uniques et les
  sondes de santé de F-87.

## 15. Hors périmètre

- **Écrire** dans Teams, envoyer une relance à la place de l'utilisateur.
- **Lire Outlook** (retiré par le PO : volume d'entreprise ; les courriels entrent collés, SF-104-02).
- Graph, mode application, droits sur le tenant.
- Le résumé du matin envoyé par courriel ou notification (ajout possible plus tard, sans rien réécrire).
- Zoom, Meet, Webex **en direct** ; leurs enregistrements entrent par le dépôt (§9).
- Un Radar partagé entre plusieurs consultants d'une même mission (V3, F-17).
