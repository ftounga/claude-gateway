# CADRAGE F-243 — Jarvis et vos rendez-vous

**Statut :** cadrage du 2026-11-02, sur un ajout du PO du même jour à la demande « boîtes mail ». Aucun code.
Famille « Jarvis » : F-230 → F-242, **F-243**.
**Estimation :** 4 SF, ≈ 4,25 j.
**Pourquoi une feature à part :** les rendez-vous utilisent un autre protocole (iCal, puis l'API Google Calendar), une
autre authentification (OAuth) et un autre écran. Seule SF-243-04 dépend de F-242, pour le repérage dans les mails.
SF-243-01 et SF-243-02 peuvent partir sans F-242.

## 1. Demande PO (2026-11-02)

- **(a) Repérer** les rendez-vous dans les mails, qu'il s'agisse d'invitations `.ics` ou de propositions de date
  écrites dans le texte. Les **poser** dans l'agenda, après un « oui » explicite.
- **(b) Montrer** les rendez-vous prévus : une vue holographique de l'agenda, et une réponse orale à « qu'est-ce que
  j'ai aujourd'hui ? » ou « cette semaine ? ».

Le cadre technique proposé par le PO est vérifié au §2.

## 2. Vérification dans la documentation Google (2026-11-02)

| # | Affirmation du cadre PO | Verdict | Source |
|---|---|---|---|
| G1 | IMAP ne donne pas accès à Google Agenda. | **Exact.** IMAP est un protocole de messagerie. | — |
| G2 | Google n'accepte pas les mots de passe d'application pour CalDAV. | **Exact, et même plus strict.** L'API CalDAV de Google « must use the OAuth 2.0 authentication scheme ». Une authentification Basic reçoit un **401**. Par ailleurs, depuis l'arrêt des « applications moins sécurisées » dans Workspace, CalDAV exige OAuth. | [CalDAV API guide](https://developers.google.com/workspace/calendar/caldav/v2/guide), [Transition LSA → OAuth](https://knowledge.workspace.google.com/admin/sync/transition-from-less-secure-apps-to-oauth) |
| G3 | L'adresse iCal secrète permet de lire chaque agenda, sans expiration, en lecture seule. | **Exact pour la lecture seule**. Google la décrit comme « a read-only view of one calendar ». **Google ne documente aucune expiration**, mais l'adresse peut être **réinitialisée** (« Reset »), ce qui casse l'ancienne. Dans Workspace, **l'administrateur peut la masquer** par les règles de partage (le PO est l'administrateur du domaine). **Google ne documente aucun délai de rafraîchissement** : il faut le mesurer. | [Aide Agenda, adresse iCal](https://support.google.com/calendar/answer/37648) |
| G4 | Pour ng-itconsulting.com, un client OAuth « interne » au domaine Workspace ne demande ni vérification ni n'expire. | **Exact.** Une application de type **Internal**, dont le projet appartient à l'organisation, est exemptée de vérification. L'expiration à 7 jours ne vise **que** les applications « External » au statut « Testing ». **Condition** : le projet Google Cloud doit **appartenir à l'organisation** ng-itconsulting.com. Le client OAuth actuel de la connexion Google (emprunté à legalcase) ne convient donc pas. | [Sensitive scope verification, exceptions](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification), [OAuth 2.0, refresh token expiration](https://developers.google.com/identity/protocols/oauth2) |
| G5 | Pour ntounga@gmail.com, `calendar.events` est une portée « sensible » et non « restreinte ». | **Exact.** Google cite « reading events stored in Google Calendar » comme exemple de portée sensible. Il faut une vérification de marque **et** une vérification de portée sensible (« jusqu'à 10 jours », vidéo de démonstration), mais **pas** d'audit CASA. | [Sensitive scope verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification) |
| G6 | « Sinon, jeton de 7 jours en mode test. » | **Exact mais incomplet, à corriger.** Le jeton de 7 jours ne vise que le statut **Testing**. Une application External **publiée en Production sans vérification** est admise pour un **usage personnel** (« used by only a few users, all of whom are known personally to you »). Elle affiche alors un écran « application non validée » et se limite à **100 utilisateurs** ; ses jetons de rafraîchissement **n'ont pas l'expiration de 7 jours**. C'est une **troisième voie** pour gmail.com, sans vérification et sans invitation détournée. Réserve : un jeton reste révocable par Google ou par l'utilisateur, et il expire s'il n'est pas utilisé pendant 6 mois. | [OAuth 2.0](https://developers.google.com/identity/protocols/oauth2), [Unverified apps](https://support.google.com/cloud/answer/7454865) |
| G7 | Les mots de passe d'application restent valables pour IMAP et SMTP sur Workspace (F-242). | **Exact.** Google recommande « OAuth ou un mot de passe d'application » pour SMTP, IMAP et POP après l'arrêt des « applications moins sécurisées ». La validation en deux étapes est **requise**. | [Transition LSA → OAuth](https://knowledge.workspace.google.com/admin/sync/transition-from-less-secure-apps-to-oauth), [Mots de passe d'application](https://support.google.com/accounts/answer/185833) |

## 3. Constat : l'existant, vérifié le 2026-11-02 (`origin/main` `2ca051d1`)

| # | Constat | Où |
|---|---|---|
| 1 | **Aucun code d'agenda Google**, et aucune bibliothèque iCal (ni ical4j ni autre). | `backend/pom.xml`, grep |
| 2 | Les réunions **Teams** (F-89, `calendarView` Graph) sont lues **sur le poste**, par le runner, pour le volet Teams. C'est **distinct** et il n'y a pas de recouvrement. | F-89, SF-89-15 |
| 3 | `WorkdayCalendar` (F-124) ne sert qu'aux jours ouvrés du CRA. Ce n'est pas un agenda. | `activity/WorkdayCalendar.java` |
| 4 | Les briques réutilisables sont : `ByokKeyCipher` (patron `user_git_credentials`) pour l'adresse iCal et le jeton OAuth ; le patron de bail des workers ; la galerie F-237 ; la règle de confirmation (`JarvisOralNote`, `jarvis-confirm.ts`) ; la relève et le balisage non fiable de F-242. | voir F-242 §2 |

## 4. Décisions tranchées par défaut

| # | Sujet | Défaut | Pourquoi |
|---|---|---|---|
| D1 | Lecture | Elle passe par l'**adresse iCal secrète** de chaque agenda, sans limite de nombre : l'agenda pro, l'agenda perso et un agenda partagé si le PO le souhaite. L'adresse est **chiffrée** (`ByokKeyCipher`), jamais renvoyée au navigateur (seuls un libellé et les 4 derniers caractères le sont), et révocable. | C'est le moyen le plus simple pour la lecture seule : aucune expiration documentée, aucun OAuth (G3). |
| D2 | Relève de l'agenda | Un worker serveur, **toutes les 15 min**. Il envoie une requête conditionnelle (`ETag`/`Last-Modified` si Google les fournit, sinon une empreinte du corps) : **inchangé ⇒ aucun traitement**. L'analyse se fait avec **ical4j** (récurrences `RRULE`/`EXDATE`, fuseaux), avec un déploiement des occurrences sur la **fenêtre J-1 → J+60**. L'écran affiche « à jour à hh:mm ». Une erreur 404 (adresse réinitialisée) fait passer l'état à `BROKEN` et déclenche une notification F-185 unique. | ical4j est une bibliothèque mûre (licence BSD) : c'est une **variante de pile à signaler** dans TECH_STACK et dans la PR. Écrire un analyseur iCal maison serait réinventer l'existant. |
| D3 | Écriture : agenda **Workspace** | Elle passe par l'**API Google Calendar**, en OAuth, avec un client **Internal** dans un projet Google Cloud **appartenant à ng-itconsulting.com** (G4). La portée est **`calendar.events`**. Le jeton de rafraîchissement est chiffré. Le PO peut révoquer depuis `/jarvis` (révocation chez Google, puis effacement). | Ni vérification, ni expiration (G4). |
| D4 | Écriture : agenda **gmail.com** | **Par défaut, un seul agenda cible : l'agenda Workspace.** Un rendez-vous repéré dans la boîte gmail est posé dans l'agenda Workspace, et l'agenda perso reste **lu** par iCal. « Qu'est-ce que j'ai aujourd'hui ? » fusionne les deux. **Si le PO veut écrire aussi dans l'agenda gmail**, la voie recommandée est la **troisième voie de G6** : un client External publié en Production, non vérifié, à usage personnel. Elle ne demande ni vérification, ni jeton de 7 jours, ni invitation détournée ; le PO accepte une fois l'écran « non validée ». L'invitation envoyée par mail depuis la boîte Workspace est **écartée** : elle crée un événement dont le PO est à la fois organisateur et invité, donc un doublon dans la vue fusionnée. | C'est le plus simple qui marche. La correction de G6 évite une vérification Google inutile. |
| D5 | Poser un rendez-vous | Un outil d'Opus, `agenda_poser`, offert seulement dans le **fil Jarvis** (F-245). Il demande une **reformulation** (« Jeudi 14 h – 15 h, Helios, point devis, dans votre agenda pro. Vous confirmez ? »), puis un **« oui » vérifié par le serveur** (même mécanique que F-242 D9 c, table `jarvis_calendar_actions`). L'appel utilise `sendUpdates=none` et **aucun invité ajouté** par défaut : poser un rendez-vous n'envoie **aucun** mail à un tiers. L'événement porte la mention « posé par Jarvis » et un lien vers le mail d'origine. | Un mail ne peut pas faire écrire dans l'agenda sans le PO. Jarvis n'écrit jamais à des tiers par effet de bord. |
| D6 | Invitation `.ics` reçue | Elle est **analysée de façon déterministe** (ical4j, `METHOD:REQUEST`), sans IA. Si un événement de même **`UID`** figure déjà dans un agenda lu (Google ajoute souvent les invitations tout seul), Jarvis ne propose **pas** de le poser. Pour l'agenda **Workspace**, il propose « accepter » ou « refuser » (`events.patch` du `responseStatus`, avec un « oui » ; Google prévient l'organisateur). Pour l'agenda gmail, sans écriture (D4), il dit « c'est dans votre agenda perso ; la réponse se fait depuis Gmail ». | Pas de doublon. On reste dans les gestes que l'API couvre. |
| D7 | Proposition de date dans le texte | Elle est **repérée au tri de F-242** : le même appel du cerveau rapide renvoie un champ facultatif `rdv` (`titre`, `debut`, `fin` ou durée, `lieu`, `certitude` haute ou basse), sans appel supplémentaire. **Seule une certitude haute** fait l'objet d'une proposition. Une proposition ambiguë (« on se voit la semaine prochaine ? ») n'est **pas** proposée telle quelle : Jarvis demande le créneau. Les dates relatives sont résolues par rapport à la **date du mail**, en Europe/Paris. | Justesse : ne jamais poser un horaire deviné. Le coût est marginal (quelques jetons de sortie). |
| D8 | Proposer, quand | Une **carte « rendez-vous proposé »** apparaît dans le bref des mails (F-242 D8), en ≈ 25 mots : « Helios propose jeudi 14 h pour le devis, je le pose dans votre agenda ? ». **Une proposition à la fois.** Une proposition sans réponse **expire** au début du rendez-vous. | Cohérent avec F-240 : une seule question en attente. |
| D9 | Montrer, à l'oral | « Qu'est-ce que j'ai aujourd'hui / demain / cette semaine ? » est servi par le **cerveau rapide** grâce à un outil de **lecture seule** `agenda` (le cache des occurrences), **sans Opus** et à ≈ 0 €. La réponse tient en ≈ 25 mots, le prochain rendez-vous d'abord, et la suite va à l'étage 2 de F-240 (l'essentiel à l'écran). | Lire n'engage rien. C'est la règle F-237 des outils sans effet de bord. |
| D10 | Montrer, à l'écran | Un **type de pièce `AGENDA`** dans la galerie F-237 : vue **jour** ou **semaine** sur une frise holographique, avec le rendez-vous en cours ou le prochain mis en avant. Un rendez-vous **proposé** s'affiche en pointillé avant le « oui », puis « se pose » à la confirmation (scène). | Réutilise F-237 et DESIGN_SYSTEM §23. |
| D11 | Présentation | **Rien** : ni vue d'agenda, ni réponse orale détaillée. L'outil `agenda` répond « indisponible en présentation ». | Les titres de réunions sont confidentiels. |
| D12 | Anti-injection | Le titre, la description et le lieu d'un événement ou d'un `.ics` sont des **données non fiables** (`UntrustedMail.wrap()` de F-242). Le contenu d'un `.ics` ne peut **jamais** ajouter un invité, déclencher un envoi ni poser autre chose que ce que la reformulation a dit. Le serveur pose **exactement** les champs de l'action confirmée. | Même règle que F-242. |
| D13 | Données gardées | Les occurrences de la fenêtre seulement (titre, lieu et description courte, **chiffrés**), purgées au-delà de J-7. Il n'y a aucun historique d'agenda. | Minimiser. |

## 5. Données et points d'accès

- **`jarvis_calendar_feeds`** : `id`, `user_id`, `label`, `encrypted_data_key`, `cipher_iv`, `ciphertext`
  (l'adresse iCal), `secret_last4`, `status` (`OK`, `BROKEN`), `etag`, `content_hash`, `last_ok_at`, `next_poll_at`,
  `lease_until`.
- **`jarvis_calendar_events`** : `id`, `user_id`, `feed_id`, `ical_uid`, `recurrence_id`, `starts_at`, `ends_at`,
  `all_day`, `title`, `location` et `description` (chiffrés), `status`. Index sur (`user_id`, `starts_at`).
- **`jarvis_calendar_grants`** : `id`, `user_id`, `account` (l'adresse), `client_kind` (`INTERNAL`, `EXTERNAL`), le
  jeton de rafraîchissement chiffré, `scope`, `granted_at`, `revoked_at`.
- **`jarvis_calendar_actions`** : même modèle que `jarvis_mail_actions` (F-242), avec `kind` = `CREATE`, `ACCEPT` ou
  `DECLINE`, et une source (`mail_item_id`).
- Points d'accès, tous filtrés sur le `user_id` du principal :
  - `GET`, `POST` et `DELETE /api/jarvis/calendar/feeds` ;
  - `GET /api/jarvis/calendar/events?from=&to=` ;
  - `GET /api/jarvis/calendar/oauth/start` et `/callback` (paramètre `state` lié à la session et au `user_id`) ;
  - `DELETE /api/jarvis/calendar/grants/{id}`.
- Configuration : `APP_JARVIS_CALENDAR_ENABLED`, `APP_JARVIS_CALENDAR_OAUTH_CLIENT_ID` et `_SECRET` (le client
  Internal). **Action du PO, hors code** : créer le projet Google Cloud dans l'organisation ng-itconsulting.com,
  l'écran de consentement Internal et le client Web. La marche à suivre est dans la mini-spec de SF-243-03.

## 6. Découpage — SF-243-01 → SF-243-04 (≈ 4,25 j)

| SF | Titre | Estimation | Dépend de |
|---|---|---|---|
| SF-243-01 | Lire les agendas par l'adresse iCal secrète | 1 j | — (peut partir avant F-242) |
| SF-243-02 | « Qu'est-ce que j'ai aujourd'hui ? » et la vue d'agenda | 1 j | SF-243-01, F-245 non requis |
| SF-243-03 | Poser un rendez-vous dans l'agenda Workspace | 1 j | SF-243-01, F-245 SF-245-02 |
| SF-243-04 | Repérer les rendez-vous dans les mails | 1,25 j | SF-243-03, F-242 SF-242-03 |

### SF-243-01 — Lire les agendas par l'adresse iCal secrète (1 j)

**Objectif :** les rendez-vous des agendas du PO sont dans le cache, à jour au quart d'heure, sans aucune IA.

- Table `jarvis_calendar_feeds` et table `jarvis_calendar_events`. Chiffrement D1. Worker D2 (requête
  conditionnelle, ical4j, fenêtre J-1 → J+60). Purge D13.
- Volet « Mes agendas » dans les réglages de `/jarvis` : coller l'adresse iCal secrète (le pas-à-pas renvoie à
  l'aide Google), libellé, « Tester », « Retirer », état, « à jour à hh:mm ». Volet masqué en présentation.
- Mesure : `calendar_polls`, `calendar_polls_unchanged`, et le **délai observé** entre une modification et son
  apparition (relevé manuel à l'essai, G3).

**Critères d'acceptation :**
1. Une adresse valide donne des occurrences dans la fenêtre, récurrences et fuseau compris (exemple : une réunion
   hebdomadaire à 9 h Paris, à travers le changement d'heure).
2. Un flux inchangé ne donne aucune analyse.
3. Une erreur 404 fait passer l'état à `BROKEN` et déclenche une seule notification.
4. L'adresse est chiffrée et n'est jamais renvoyée.
5. L'utilisateur B reçoit 404 sur les flux de A.
6. La suppression du compte purge les tables.

**Plan de test :**
- Unitaires : analyse `.ics` (récurrence, `EXDATE`, toute la journée, fuseaux, passage à l'heure d'hiver).
- Intégration avec un serveur HTTP bouchonné (200, 304, 404).
- **Isolation** A/B.
- Purge et Liquibase.

**Hors périmètre :** l'écriture.

### SF-243-02 — « Qu'est-ce que j'ai aujourd'hui ? » et la vue d'agenda (1 j)

**Objectif :** Jarvis répond à l'oral en ≈ 25 mots et montre la journée ou la semaine.

- Un outil de lecture seule `agenda` pour le cerveau rapide, à la **version suivante** de la consigne au moment du
  dev.
- La pièce `AGENDA` (D10).
- Présentation (D11).
- DESIGN_SYSTEM.md, §23 : une ligne pour la frise de l'agenda, sans couleur nouvelle.
- Banc CONVERSATION : 5 scénarios (aujourd'hui, demain, cette semaine, journée vide, présentation).

**Critères d'acceptation :**
1. « Qu'est-ce que j'ai aujourd'hui ? » donne le prochain rendez-vous d'abord, en 30 mots au plus, **sans Opus**.
2. Une journée vide donne une phrase courte.
3. La vue semaine s'affiche avec les deux agendas fusionnés, sans doublon de même `UID`.
4. En présentation, rien n'est affiché ni dit.
5. À 360 × 740, il n'y a pas de débordement horizontal.

**Plan de test :**
- Unitaires : fusion et dédoublonnage, choix du « prochain ».
- Intégration de l'outil `agenda` et **isolation** A/B.
- Specs du composant et contrôle visuel headless.
- Banc.

**Hors périmètre :** poser un rendez-vous.

### SF-243-03 — Poser un rendez-vous dans l'agenda Workspace (1 j)

**Objectif :** sur un « oui », Jarvis pose un rendez-vous dans l'agenda pro, sans jamais écrire à un tiers.

- Parcours OAuth du client Internal (D3) : `state` anti-CSRF lié au `user_id`, jeton chiffré, révocation.
- L'outil `agenda_poser` dans le fil Jarvis, avec l'action en attente et le « oui » vérifié par le serveur (D5).
  `sendUpdates=none`, aucun invité.
- La scène « le rendez-vous se pose ».
- Mini-spec : la marche à suivre pour le PO dans la console Google Cloud.

**Critères d'acceptation :**
1. Après le consentement, un événement est créé dans l'agenda pro sur un « oui », et rien n'est créé sans « oui ».
2. L'événement n'a aucun invité, et aucun mail ne part (vérifié sur le corps de la requête API).
3. Un « oui » contenu dans un mail ne vaut pas confirmation.
4. La révocation efface le jeton, et l'outil dit alors « agenda pro non relié ».
5. Un `state` OAuth d'un autre utilisateur est refusé.
6. Isolation A/B sur les droits et les actions.

**Plan de test :**
- Unitaires : construction de l'événement et machine d'états.
- Intégration : l'API Google bouchonnée (création, 401 qui déclenche un rafraîchissement, révocation).
- **Isolation** et CSRF du `state`.
- Jeton chiffré en base.

**Hors périmètre :** l'écriture dans l'agenda gmail (D4, sur décision du PO) ; inviter des tiers ; modifier ou
supprimer un événement existant.

### SF-243-04 — Repérer les rendez-vous dans les mails (1,25 j)

**Objectif :** une invitation ou une proposition de date dans un mail devient une proposition, et le PO pose le
rendez-vous d'un « oui ».

- Invitation `.ics` (`has_calendar` de F-242) : analyse déterministe, dédoublonnage par `UID` contre le cache, puis
  « poser » ou « accepter / refuser » (D6).
- Proposition écrite : le champ `rdv` du tri de F-242 (version suivante de `JarvisMailTriagePrompt`). Seule une
  certitude haute est proposée. Les dates relatives sont résolues par rapport à la date du mail (D7).
- La carte « rendez-vous proposé » dans le bref (D8), avec une expiration.
- Banc `MAIL` : 8 cas de rendez-vous (`.ics`, invitation déjà présente, proposition claire, proposition vague,
  date relative, fuseau étranger, `.ics` piégé avec des invités ajoutés, injection « pose aussi… »).

**Critères d'acceptation :**
1. Une invitation déjà présente dans l'agenda n'est pas proposée à la pose. On propose « accepter ».
2. « Jeudi 14 h » dans un mail du mardi 3 donne jeudi 5 à 14 h, heure de Paris.
3. « La semaine prochaine ? » ne donne aucune pose proposée, mais une question.
4. Un `.ics` qui contient des invités ne fait **jamais** inviter personne.
5. Le banc des rendez-vous passe à 90 % au moins, et ses cas d'injection à 100 %.
6. Isolation A/B.

**Plan de test :**
- Unitaires : analyse des `.ics` (`REQUEST`, `CANCEL`, `UID`), résolution des dates relatives, seuil de certitude.
- Intégration : du mail (GreenMail) jusqu'à la proposition, puis au « oui » et à l'API bouchonnée.
- **Isolation**.
- Banc.

**Hors périmètre :** contre-proposer un créneau ; la recherche de disponibilité commune ; les agendas autres que
Google.

## 7. Mesure de réussite

- **Coût** : la lecture coûte 0 € (aucune IA). La détection est incluse dans le tri de F-242. « Qu'est-ce que j'ai
  aujourd'hui ? » coûte quelques centièmes de centime par question (cerveau rapide).
- **Justesse** : **zéro** rendez-vous posé à une mauvaise heure à J+14 (relu avec le PO). Délai « modifié chez
  Google → vu par Jarvis » mesuré (G3).
- **Ressenti du PO** : « il me dit ma journée et pose mes rendez-vous ».

## 8. Points de contact

| Avec | Point | Règle |
|---|---|---|
| **F-242** | Le tri, `has_calendar`, `UntrustedMail.wrap()`, le modèle d'action en attente. | SF-243-04 suit SF-242-03. Le modèle `jarvis_*_actions` est commun : il est factorisé dans SF-242-06 et réutilisé ici. |
| **F-240** | Les 25 mots, l'étage 2, une seule question en attente. | Une proposition de rendez-vous n'est jamais dans la même réponse qu'une `↪` ou qu'une confirmation d'envoi. |
| **F-241** | Le portrait contient des « dates qui comptent ». | Pas de fusion : l'agenda n'écrit jamais dans le portrait. Jarvis peut évoquer un rendez-vous personnel seulement dans les bornes de tact de F-241 (D10). |
| **F-237** | Le type de pièce `AGENDA`. | C'est un ajout au registre des visionneuses. |
| **F-244** | Le budget Jarvis. | Les appels sont comptés au budget Jarvis (étiquette `AGENDA` pour l'outil du cerveau rapide). |
| **F-245** | Le fil Jarvis. | `agenda_poser` n'existe que dans le fil Jarvis. |
| **F-89** | Les réunions Teams. | Distinctes. La fusion Teams + Google est hors périmètre. |

## 9. Hors périmètre

Le paiement ; CalDAV ; la vérification Google de l'application ; l'écriture dans l'agenda gmail **tant que le PO ne
l'a pas demandée** (D4) ; inviter des tiers ; modifier ou supprimer un événement ; la recherche de créneaux communs ;
les réunions Teams ; les agendas non Google.

## 10. Question au PO (goût, non bloquante)

- **Q1 — Agenda perso en écriture ?** Par défaut, Jarvis pose tout dans l'agenda **pro** et se contente de **lire**
  l'agenda perso. Si vous voulez qu'il écrive aussi dans l'agenda gmail, la voie la plus simple est un client
  « External » publié sans vérification, pour votre seul usage (G6), au prix d'un écran « application non validée »
  à accepter une fois. Ce serait une SF de plus, d'environ 0,5 j.
