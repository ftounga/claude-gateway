# CADRAGE F-249 — Les mails et l'agenda Outlook de vos clients

**Statut :** cadrage du 2026-11-07 (besoin PO du même jour). Famille « Jarvis » : F-230 → F-248, **F-249**.
Base vérifiée : `origin/main` `fdb143c8`. **Estimation :** 8 SF, ≈ 8,75 j, aucune au-delà de 1,5 j.

## 1. Le besoin (PO, 2026-11-07)

Chez ses clients (CAGIP, EDENRED…), le PO envoie et reçoit des mails **Outlook professionnels** qui font avancer ses
sujets ; souvent, il ne voit pas qu'un mail a fait bouger un sujet. **Aucun accès IMAP ni Graph** (postes
d'entreprise). Il veut :

1. un **dossier de dépôt par client** sur le poste, où il glisse ses mails depuis Outlook (`.msg`, et `.eml`) ;
2. que le **runner surveille ce dossier en arrière-plan** (faible priorité, jamais pendant un tour), envoie chaque
   fichier **une fois**, le range, et que le serveur **dédoublonne** (un re-dépôt complet ne retraite rien) ;
3. que ces mails entrent dans le **pipeline existant** de F-242 (cache, tri règles puis cerveau rapide, garde
   anti-injection, bref, pièce `MAIL`) et de F-243 (invitations, agenda) — **réutiliser, ne pas dupliquer** — rattachés
   à leur **client** et, si possible, aux **sujets en cours** ;
4. un **rappel du soir** « Déposez vos mails <client> » (≈ 18 h 30, réglable), répété 2 à 3 fois au plus, qui se tait
   dès qu'un dépôt a eu lieu ce jour-là pour ce client ;
5. une **annonce des nouveaux mails** qui touchent un sujet en cours, sur **n'importe quel écran** de l'application
   (« 2 mails sur « Alarme Fortec » (EDENRED) », lien vers le sujet), dite par Jarvis dans `/jarvis` ; un
   **récapitulatif du matin**. **Interdit** : injecter un message ou un tour dans le fil d'un sujet ;
6. un **agenda tous clients** alimenté par les invitations, et des **alertes avant réunion** (« Réunion X dans
   10 min », délai réglable), dans l'application et en notification ;
7. un **questionnaire des mails répétitifs** (« Ignorer ces mails ? ») ; les ignorés sont écartés **par règle, avant
   toute IA**, réversibles d'un clic ;
8. **runner éteint**, Jarvis répond sur ces mails et l'agenda (données côté serveur).

## 2. L'existant, vérifié le 2026-11-07

| # | Constat | Où |
|---|---|---|
| 1 | Le cache du courrier `jarvis_mail_items` (en-têtes + extrait **scellés**, tri, `message_id`, `has_calendar`, `bulk`) est **lié à une boîte IMAP** (`account_id` non nul, unicité `account_id, uidvalidity, uid`). Le corps complet n'est jamais stocké : il est **relu par IMAP** (`JarvisMailReader`, `JarvisMailBox`). | `jarvis/mail/*`, migration `242-jarvis-mail-items.xml` |
| 2 | Le tri (`JarvisMailTriageService`) prend tout courrier `EN_ATTENTE` du compte, quelle que soit sa boîte : règles (`bulk`) puis cerveau rapide par lot de 20, consigne figée `JarvisMailTriagePrompt`. Il est déclenché par `JarvisMailTriageTrigger.triageSoon(userId)`. | `JarvisMailTriageService` |
| 3 | Le bref (`JarvisMailBriefService`), le bloc « Courrier » du cerveau rapide et l'outil `courriels` lisent **le cache**, sans IMAP : un courrier déposé y apparaît **sans rien changer**. | `JarvisMailBriefService` |
| 4 | Les invitations `.ics` d'un courrier relevé passent par `JarvisMailCalendarHook.onInvitation` (propositions F-243 à poser dans Google). L'agenda lu (`JarvisCalendarReader.between`) fusionne les occurrences des flux iCal et les propositions en pointillé. | `jarvis/calendar/*` |
| 5 | Un **client = un poste** (`runner_hosts`, nom « CAGIP », « EDENRED »). Un **sujet** = un `workspace` rattaché au poste (`host_id`). Le registre d'état F-247 range ses faits par (`user_id`, `host_id`, `workspace_id`). | `RunnerHost`, `Workspace`, `JarvisStateRegistry` |
| 6 | Le runner sait **céder la place** (`low_priority`, `RunnerActivity.busy()`), et remonte déjà des fichiers par HTTP authentifié par son jeton (`X-Runner-Token`, vérifié par le contrôleur, routes `/runner/...` déclarées une par une). | `BashTool`, `RunnerDepartureController`, `RunnerMeetingAudioController` |
| 7 | La file durable `async_jobs` (SKIP LOCKED, baux, reprises, `payload_bytes` effacé en fin de travail) accueille tout travail asynchrone. | `shared/jobs/AsyncJobQueue` |
| 8 | Les notifications F-185 : `PushNotificationService.notify(userId, workspaceId, PushEvent, url)` écrit dans la cloche et pousse sur les appareils ; **titre et corps neutres** (aucun nom de sujet ni contenu). | `push/*` |
| 9 | Aucune bibliothèque ne lit le format Outlook `.msg` (OLE2/MAPI). `jakarta.mail` lit `.eml`. | `backend/pom.xml` |
| 10 | Le shell de l'application (`layout/shell`) enveloppe tous les écrans sauf `/jarvis` (plein écran) : c'est la seule surface commune où une annonce peut apparaître « sur n'importe quel écran ». | `app.routes.ts` |

**Conclusion :** on **ajoute une source** au cache F-242 (le dépôt), on **branche** le tri, le bref, les outils, la
garde et la pièce `MAIL` tels quels ; on **ajoute** à l'agenda F-243 les réunions des clients ; on construit ce qui
n'existe pas : la surveillance du dossier (runner), la lecture `.msg`, le rattachement aux sujets, les annonces, le
rappel, les alertes avant réunion et les règles « ignorer ».

## 3. Principes (non négociables)

- **Réutiliser F-242/F-243** : un courrier déposé est une ligne `jarvis_mail_items` (source `DEPOT`) ; aucun second
  cache, aucun second tri, aucune seconde consigne.
- **Aucune consigne ne change** (ni tri, ni cerveau rapide, ni orale) ⇒ **aucun banc à rejouer** pour F-249. Le
  rattachement aux sujets et les annonces sont **déterministes** (0 €). Si une SF venait à toucher une consigne, le
  banc concerné (MAIL/CONVERSATION) devrait passer **2 fois vert avant merge** (V8).
- **Isolation `user_id`** sur chaque table, chaque route, chaque travail ; l'identité du dépôt vient **du jeton runner
  seul** (compte + poste).
- **Asynchrone** : la route de dépôt ne fait qu'une **lecture bornée** du fichier (en-têtes, texte ≤ 8 000 caractères,
  invitation ≤ 256 Kio) et l'enregistrement ; tout le reste (règles, rattachement, agenda, tri IA, annonce) part dans
  `async_jobs` (`MAIL_DEPOSIT`).
- **Le contenu d'un mail est une donnée** : mêmes balises et mêmes filets que F-242 D9 ; aucune action (répondre,
  envoyer, supprimer) n'est possible sur un mail déposé — on y répond depuis Outlook.
- **Jamais de message dans le fil d'un sujet** : les annonces sont des **notifications d'interface** (table
  `jarvis_notices`, pop-up, cloche F-185), jamais une ligne de conversation ni un tour.
- **Rien de volatil dans la consigne système** : ce que Jarvis dit des annonces est une phrase composée par la
  passerelle et jouée par l'écran.
- **Aucun secret stocké** : le dépôt ne demande ni mot de passe ni jeton tiers.
- **Pas de Vigie, Teams ni Radar** comme source (décision F-247 v2).
- **DESIGN_SYSTEM.md** : palette navy/orange de l'application, verre §23 dans `/jarvis` ; aucune couleur nouvelle.

## 4. Décisions tranchées par défaut (pas de question au PO)

| # | Sujet | Défaut | Pourquoi |
|---|---|---|---|
| D1 | Dossier | `<racine du poste>/jarvis/mails/`, créé par le runner au démarrage avec un `LISEZ-MOI.txt`. Le client est **le poste** (un poste = un client, F-106). Sous-dossiers rangés par le runner : `envoyes/AAAA-MM/`, `refuses/`. | Le poste est l'unité ; un sous-dossier par client sur un même poste n'a pas d'usage réel. |
| D2 | Surveillance | Un fil de **priorité minimale** du runner, toutes les **5 min**, plus un passage 30 s après le démarrage. Un passage est **sauté** si le runner a une activité en cours (`RunnerActivity.busy()` non vide). Fichiers `.msg`/`.eml` du premier niveau, non vides, **stables depuis 1 min** (copie finie), **20 par passage** au plus, du plus ancien au plus récent. | Ne jamais gêner un tour (même règle que `low_priority`). |
| D3 | Envoi | `POST /runner/mail-deposit` (corps brut, en-têtes `X-Runner-Token`, `X-File-Name`), **20 Mo** au plus. Réponses : `200 {outcome: ACCEPTED|DUPLICATE|IGNORED}` ⇒ fichier rangé dans `envoyes/` ; `413`/`415`/`422` ⇒ `refuses/` ; `401`, `5xx`, réseau ⇒ laissé en place, repris au passage suivant (curseur). | Un fichier n'est envoyé qu'une fois en régime normal. |
| D4 | Curseur | Le **rangement** est le curseur ; s'il échoue (fichier verrouillé par Outlook), l'empreinte SHA-256 envoyée est notée dans `jarvis/mails/.envoyes` (500 dernières) et le fichier n'est plus renvoyé. | Pas de relecture de tout à chaque fois. |
| D5 | Dédoublonnage serveur | Par **empreinte** du fichier (`jarvis_mail_deposits`, unique `user_id, host_id, content_hash`), puis par **Message-ID** (unique `user_id, host_id, message_id` sur les courriers déposés). Un re-dépôt complet rend `DUPLICATE` partout et ne retraite rien. Un mail reçu il y a plus de 30 jours est `IGNORED` (purge F-242). | Exigence PO. |
| D6 | Lecture `.msg` | **Côté serveur**, Apache POI `poi-scratchpad` 5.3.0 (HSMF, licence Apache 2.0) : objet, expéditeur (adresse SMTP, pas le DN Exchange), destinataires, date, Message-ID, `In-Reply-To`, en-têtes de transport (`List-Unsubscribe`, `Precedence`, `Auto-Submitted`), texte (repli HTML ⇒ texte), pièces `.ics`/`text/calendar` ; demandes de réunion Outlook (`IPM.Schedule.Meeting.*`) par leurs propriétés nommées (début, fin, lieu, identifiant global). `.eml` : `jakarta.mail` (déjà là). **Variante de pile à signaler** (TECH_STACK + PR). | Un seul analyseur, corrigé sans mettre à jour les postes ; le runner reste léger ; POI est la bibliothèque de référence du format. |
| D7 | Ce qui est gardé | Comme F-242 D5 (en-têtes + extrait 2 000 car., scellés) **plus** le **corps texte borné à 8 000 caractères, scellé** (`body_sealed`), parce qu'aucun IMAP ne permet de le relire. Les pièces jointes ne sont **jamais** gardées, sauf l'invitation `.ics` (≤ 256 Kio) transmise au travail asynchrone puis effacée. Purge à **30 jours** (celle de F-242). Rien dans les journaux. | Minimiser, mais permettre « runner éteint, Jarvis répond ». |
| D8 | Tri | Le **tri F-242 inchangé** (règles `bulk` puis cerveau rapide, consigne figée) ; les règles « ignorer » de D13 passent **avant**. | Réutilisation stricte ; aucune consigne ne change. |
| D9 | Rattachement aux sujets | **Déterministe**, sans IA : (1) **même fil** — un courrier qui répond (`In-Reply-To`/`References`, ou même objet normalisé chez le même client sous 30 j) à un courrier rattaché hérite de ses sujets ; (2) **clé de ticket** (`ABC-123`) présente dans l'objet ou l'extrait et dans le nom du sujet ou ses faits F-247 ; (3) **mots du nom du sujet** : au moins la moitié des mots distinctifs (≥ 4 lettres, hors mots vides) du nom présents dans l'objet, ou tous dans l'objet + extrait. Seuls les sujets **du poste du dépôt**, non fils Jarvis. Au plus 3 sujets par courrier. Un courrier « à ignorer » n'est ni rattaché ni annoncé. | 0 €, aucune consigne touchée ; justesse : on préfère ne rien rattacher que mal rattacher. |
| D10 | Annonces | Table `jarvis_notices` (genre, texte, lien, client, sujet, échéance, vue, dite). Une annonce **par sujet** qui **cumule** tant qu'elle n'est pas vue (« 2 mails sur « Alarme Fortec » (EDENRED) », lien `/atelier/<id>`). Lues par `GET /api/jarvis/notices` (sondage 60 s), fermées par `POST /api/jarvis/notices/{id}/seen`. Push F-185 `CLIENT_MAILS_ON_SUBJECT`, corps neutre, lien `/atelier/<id>`. | Notification d'interface, jamais un message de fil. |
| D11 | Récap du matin | À **7 h 30** (Paris), si des courriers ont été déposés depuis le récap précédent : une annonce « Hier soir : 14 mails déposés (EDENRED 9, CAGIP 5), dont 3 sur vos sujets : « Alarme Fortec » (2), « SSO » (1). » ; dite par Jarvis à l'ouverture de `/jarvis`. Pas de push (le rappel et les réunions suffisent). | Les alertes prêtes le matin, sans bruit. |
| D12 | Rappel du soir | Par **client actif** (un poste ayant déjà reçu un dépôt, ou activé à la main ; désactivable) : à l'heure réglée (**18 h 30**), puis toutes les **45 min**, **3 fois au plus**, **jours ouvrés** seulement (réglable) — annonce « Déposez vos mails EDENRED » + push `CLIENT_MAIL_DEPOSIT_REMINDER`. Se tait (et l'annonce se ferme) dès qu'un dépôt a eu lieu **ce jour-là** pour ce client. | Exigence PO. |
| D13 | Mails répétitifs | Après un dépôt, si le client a ≥ 10 courriers : les **expéditeurs** qui reviennent ≥ 3 fois avec l'indice `bulk` ou une adresse automatique, et les **objets** répétés ≥ 3 fois (chiffres et préfixes `RE:/TR:` neutralisés) sont **proposés** (« Ignorer ces mails ? ») dans l'écran des réglages et par une annonce. Une règle acceptée (`jarvis_mail_ignore_rules`, par client) classe « à ignorer » **avant toute IA** ; la retirer remet ses courriers récents au tri. Une proposition refusée n'est plus reproposée. | Gratuit, réversible, gérable. |
| D14 | Agenda des clients | Une invitation d'un courrier déposé (`.ics` `REQUEST` ou demande de réunion Outlook) devient une **réunion du client** (`jarvis_client_meetings`, titre et lieu scellés, occurrences J-1 → J+60), **sans « oui »** : ce n'est pas une écriture dans Google, c'est le reflet de l'Outlook du client. `CANCEL` la retire ; une version plus récente (même `UID`) la remplace. La vue d'agenda F-243 et l'outil `agenda` les **fusionnent** (libellé « Outlook EDENRED »). Aucune proposition « à poser dans Google » n'est faite pour un dépôt. | Vue globale tous clients sans écrire chez Google. |
| D15 | Alertes avant réunion | Un travail chaque minute : réunions (flux iCal **et** clients) qui commencent dans le délai réglé (**10 min**, 0 = coupé), hors journée entière, une seule fois par (UID, début) ⇒ annonce « Réunion « Point Fortec » (EDENRED) dans 10 min » (échéance au début) + push `MEETING_SOON` (corps neutre). En présentation, l'écran `/jarvis` ne la dit pas. | Exigence PO. |
| D16 | Écran | Un volet **« Mails de vos clients »** dans les réglages de `/jarvis` : par client, l'état du poste (runner à jour ou non), le dossier à utiliser, le dernier dépôt, le nombre de mails, le rappel (oui/non) ; réglages communs (heure du rappel, répétitions, jours ouvrés, délai des alertes) ; les propositions « ignorer » et les règles actives (retirer d'un clic). Masqué en présentation. | DESIGN_SYSTEM §22-23. |
| D17 | Annonceur | Un composant **dans le shell** (tous les écrans de l'application) : pop-up empilées en bas à droite, une par annonce, lien et « OK » ; dans `/jarvis`, les annonces sont **dites** par Jarvis (hors présentation, hors tour en cours) puis marquées vues et dites. | « N'importe quel écran ». |
| D18 | Runner éteint | Rien à faire de plus : cache, corps scellé, agenda et annonces sont côté serveur ; `courriel_lire` rend le corps scellé d'un courrier déposé. | Exigence PO. |
| D19 | Coupe-circuits | `APP_JARVIS_CLIENT_MAIL_ENABLED` (route de dépôt et travaux), `APP_JARVIS_NOTICES_ENABLED` (annonces). | Retour arrière sans redéploiement. |
| D20 | Mise à jour des postes | Le runner change (SF-249-02) : chaque poste devra être **mis à jour** (bouton « Mettre à jour » de la carte du poste). Le serveur sait qu'un poste est à jour par la capacité annoncée `mail_deposit`. | Le volet le dit par client. |

## 5. Données et routes

- `jarvis_mail_items` : + `source` (`IMAP` | `DEPOT`, défaut `IMAP`), + `host_id` (nullable), + `body_sealed`
  (nullable), `account_id` devient **nullable** (nul pour un dépôt). Unicité partielle par index sur (`user_id`,
  `host_id`, `message_id`) gérée par le service (H2 ne sait pas l'index partiel).
- `jarvis_mail_deposits` : `id`, `user_id`, `host_id`, `content_hash`, `item_id`, `outcome`, `deposited_at` ; unique
  (`user_id`, `host_id`, `content_hash`). Purge 30 j.
- `jarvis_mail_subject_links` : `id`, `user_id`, `item_id`, `workspace_id`, `reason`, `created_at`.
- `jarvis_notices` : `id`, `user_id`, `host_id`, `workspace_id`, `kind`, `dedup_key`, `text`, `link`, `count`,
  `created_at`, `updated_at`, `expires_at`, `seen_at`, `spoken_at` ; unique (`user_id`, `dedup_key`).
- `jarvis_client_meetings` : `id`, `user_id`, `host_id`, `ical_uid`, `recurrence_id`, `starts_at`, `ends_at`,
  `all_day`, `sealed`, `sequence`, `source_received_at`, `mail_item_id`, `created_at`.
- `jarvis_meeting_alerts` : `id`, `user_id`, `alert_key`, `created_at` ; unique (`user_id`, `alert_key`).
- `jarvis_client_mail_settings` (par compte) et `jarvis_client_mail_hosts` (par client).
- `jarvis_mail_ignore_rules` et `jarvis_mail_ignore_offers`.
- Routes : `POST /runner/mail-deposit` (jeton runner) ; `GET /api/jarvis/notices`, `POST /api/jarvis/notices/{id}/seen`,
  `POST /api/jarvis/notices/{id}/spoken` ; `GET /api/jarvis/client-mail` (état par client + réglages),
  `PUT /api/jarvis/client-mail/settings`, `PUT /api/jarvis/client-mail/hosts/{hostId}`,
  `POST /api/jarvis/client-mail/offers/{id}/accept|decline`, `DELETE /api/jarvis/client-mail/rules/{id}`.
- Purges : compte supprimé (toutes les tables), poste supprimé (`RunnerHostLifecycleEvent`), 30 jours.

## 6. Découpage — SF-249-01 → SF-249-08 (≈ 8,75 j)

| SF | Titre | Estimation | Dépend de | Touche |
|---|---|---|---|---|
| SF-249-01 | Le dépôt reçu : lecture `.msg`/`.eml`, dédoublonnage, entrée dans le cache F-242 | 1,5 j | — | back |
| SF-249-02 | Le runner surveille le dossier | 1 j | SF-249-01 | runner |
| SF-249-03 | Rattacher aux sujets, annoncer, récap du matin | 1,25 j | SF-249-01 | back |
| SF-249-04 | Les réunions des clients dans l'agenda, alertes avant réunion | 1,25 j | SF-249-03 | back |
| SF-249-05 | Le rappel du soir et les réglages | 1 j | SF-249-03 | back |
| SF-249-06 | Les mails répétitifs : proposer, ignorer par règle | 0,75 j | SF-249-03 | back |
| SF-249-07 | L'écran « Mails de vos clients » | 1 j | SF-249-05, 06 | front |
| SF-249-08 | L'annonceur sur tous les écrans, Jarvis le dit | 1 j | SF-249-03 | front |

Chaque SF a sa mini-spec `SF-249-XX-*.md` dans ce dossier, écrite avant son code.

## 7. Hors périmètre

- Lire la boîte Outlook directement (IMAP, Graph, EWS) : impossible sur les postes d'entreprise (besoin PO).
- Répondre, transférer ou supprimer un mail déposé depuis Jarvis : on répond depuis Outlook.
- Accepter/refuser une invitation Outlook depuis Jarvis.
- Rattachement aux sujets par IA (une consigne de plus, un banc) : à rouvrir sur mesure si le rattachement déterministe
  s'avère insuffisant à l'usage.
- Une application de bureau qui exporterait Outlook toute seule (macro, complément) : le geste reste le glisser-déposer.

## 8. Ce que le PO aura à faire

1. **Mettre à jour le runner** de chaque poste client (bouton « Mettre à jour » de la carte du poste).
2. Glisser ses mails depuis Outlook dans `<racine du poste>/jarvis/mails/` (le runner crée le dossier ; le chemin
   exact est affiché dans le volet « Mails de vos clients »).
3. Régler, s'il le souhaite, l'heure du rappel (18 h 30), les répétitions (3), les jours ouvrés et le délai des
   alertes avant réunion (10 min) ; activer les notifications (F-185) sur son téléphone.
