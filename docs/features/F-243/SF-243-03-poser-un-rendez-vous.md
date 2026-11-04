# Mini-spec — F-243 / SF-243-03 Poser un rendez-vous dans l'agenda Workspace

## Identifiant
`F-243 / SF-243-03`

## Feature parente
`F-243` — Jarvis et vos rendez-vous (cadrage, D3, D5, D12)

## Statut
`done` (2026-11-04) · **Estimation :** 1 j · **Branche :** `feat/SF-243-03-poser-un-rendez-vous` ·
**Dépend de :** SF-243-01, F-245 SF-245-02 (fil Jarvis)

## Objectif
Sur un « oui », Jarvis pose un rendez-vous dans l'agenda pro, sans jamais écrire à un tiers.

## Comportement attendu

### Cas nominal
1. **Relier l'agenda pro (D3)** — dans « Mes agendas », « Relier l'agenda pro (Google) » appelle
   `GET /api/jarvis/calendar/oauth/start` : la passerelle crée un parcours (`state` 256 bits gardé par son empreinte,
   lié au `user_id` et à l'empreinte d'un témoin `HttpOnly` `cg_cal_oauth` posé dans ce navigateur, PKCE S256, usage
   unique, 10 min) et rend l'adresse de consentement Google (client **Internal**, `access_type=offline`,
   `prompt=consent`, portée `openid email calendar.events`, `hd=ng-itconsulting.com`). Le navigateur y va.
2. **Retour** `GET /api/jarvis/calendar/oauth/callback?state&code` (navigation de premier niveau, sans jeton d'API) :
   `state` consommé d'abord, puis contrôlé (connu, non expiré, **même navigateur**) ; code échangé (avec le
   vérificateur PKCE) ; portée `calendar.events` exigée ; jeton de rafraîchissement **chiffré** (`ByokKeyCipher`) dans
   `jarvis_calendar_grants`, adresse lue dans le jeton d'identité. Redirection `/jarvis?agenda=relie` (ou `refuse`,
   sans détail) ; le volet « Mes agendas » s'ouvre.
3. **Poser (D5)** — l'outil d'Opus `agenda_poser` (fil Jarvis **seulement**, client configuré) : `titre`, `debut`,
   `fin` ou `duree_minutes`, `lieu`, `description`, `courriel`. La passerelle valide (heure lisible, fin après début,
   24 h au plus, ni passé ni au-delà d'un an), calcule la **reformulation** (« Jeudi 5 novembre, 14 h – 15 h, « Point
   devis », dans votre agenda pro (lieu : Visio). ») et crée l'action `PENDING` (`jarvis_calendar_actions`, 10 min,
   champs scellés) ; elle rend à Opus la phrase à dire mot pour mot, terminée par « Vous confirmez ? ». **Rien n'est
   posé.**
4. **Le « oui » vérifié côté serveur** : au message suivant de l'utilisateur **dans ce fil Jarvis** (message persisté,
   jamais un texte du modèle ni un résultat d'outil), la liste fermée de F-242 (`MailConfirmation`) : « oui » net ⇒
   `PENDING → CONFIRMED` (une seule fois) puis création **exacte** des champs scellés, `sendUpdates=none`, **aucun
   champ `attendees`**, description terminée par « Posé par Jarvis. » ; tout le reste annule. Note fixe jointe au
   message d'Opus (« posé », « pas posé », « expiré », « ÉCHEC »).
5. **Jeton d'accès** : rafraîchi à la demande (gardé en mémoire jusqu'à 1 min avant l'échéance) ; un **401** ⇒
   rafraîchissement puis un seul nouvel essai ; `invalid_grant` ⇒ droit effacé, « agenda pro non relié ».
6. **Révoquer** `DELETE /api/jarvis/calendar/grants/{id}` : révocation chez Google (au mieux), puis effacement du jeton
   et des actions en attente. L'outil dit ensuite « Agenda pro non relié ».
7. **Purge du compte** : droits, actions, parcours OAuth (par `JarvisCalendarFeedService.purgeUser`).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Client OAuth non configuré | `start` : « pas encore configurée » ; aucun outil `agenda_poser` | 503 |
| `state` inconnu, rejoué, expiré, autre navigateur, autre compte | rien n'est relié, `/jarvis?agenda=refuse` | 302 |
| Google refuse le code / la portée n'inclut pas `calendar.events` | idem ; jeton rendu révoqué | 302 |
| `agenda_poser` hors fil Jarvis | refusé | — |
| Heure illisible, créneau inversé, > 24 h, passé, > 1 an | refusé avant toute action ; Opus demande le créneau | — |
| « Oui, mais… », « non », autre chose | action annulée, rien posé | — |
| « oui » d'un autre utilisateur, ou dans un autre fil | ne trouve rien | — |
| Droit d'un autre utilisateur (révoquer) | introuvable | 404 |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `titre` | 1..200 |
| `debut` / `fin` | `AAAA-MM-JJTHH:MM`, heure de Paris ; fin > début ; ≤ 24 h ; début ≥ maintenant − 5 min, ≤ + 1 an |
| `duree_minutes` | > 0, défaut 60 |
| `lieu` / `description` | ≤ 200 / ≤ 1 000 |
| action en attente | 10 min, une par fil (la précédente est annulée) |
| `state` OAuth | 256 bits, usage unique, 10 min |

## Critères d'acceptation
- [x] Après le consentement, un événement est créé sur un « oui », et rien sans « oui ».
- [x] L'événement n'a aucun invité et aucun mail ne part (`sendUpdates=none`, pas d'`attendees` : vérifié sur le corps
  de la requête reçue par le Google bouchonné).
- [x] Un « oui » qui n'est pas le message de l'utilisateur dans ce fil ne vaut pas confirmation (autre utilisateur,
  autre fil ; le serveur ne lit jamais un texte du modèle ni un résultat d'outil).
- [x] La révocation efface le jeton, et l'outil dit alors « agenda pro non relié ».
- [x] Un `state` d'un autre utilisateur (ou d'un autre navigateur) est refusé.
- [x] Isolation A/B sur les droits et les actions.

## Plan de test
- **Intégration** `JarvisCalendarWriteIntegrationTest` (contexte dédié, client configuré, `GoogleTestServer` sur un
  port fixe) : parcours OAuth (adresse, PKCE, témoin `HttpOnly`, autre navigateur, rejeu, state inventé, state de A
  avec le témoin de B), jeton jamais en clair ni renvoyé, outil seulement dans le fil Jarvis, « non relié », « oui
  mais » ⇒ rien, « oui » ⇒ une création (après un 401 rafraîchi), corps sans `attendees`, `sendUpdates=none`,
  idempotence, « oui » de B ⇒ rien, révocation (Google appelé, tables vidées), `invalid_grant` ⇒ échec propre et
  droit effacé, créneaux invalides ⇒ aucune action.
- **Frontend** `jarvis-calendars.component.spec.ts` : non configurée, « Relier » ⇒ navigation vers Google, droit
  relié + révocation confirmée.
- Garde du registre des scènes (`agenda_poser` déclaré) ; suites complètes.

## Impact
- **Tables** : `jarvis_calendar_grants`, `jarvis_calendar_oauth_states`, `jarvis_calendar_actions`
  (`243-jarvis-calendar-grants.xml`, réversible).
- **Endpoints** : `GET /api/jarvis/calendar/grants`, `GET /api/jarvis/calendar/oauth/start`,
  `GET /api/jarvis/calendar/oauth/callback` (**permitAll**, prouvé par state + témoin), `DELETE
  /api/jarvis/calendar/grants/{id}`.
- **Backend** : `GoogleCalendarApi`, `JarvisCalendarGrantService`, `JarvisCalendarActionService`,
  `JarvisCalendarToolCatalog`, `JarvisCalendarOAuthProperties`, entités et dépôts ; `AtelierChatService` (outil offert,
  exécuté, « oui » vérifié), `SecurityConfig`, `JarvisConfig`, `application.yml`.
- **Frontend** : `jarvis-calendar.ts`, `jarvis-calendars.component.ts`, `jarvis.component.ts` (retour `?agenda=`),
  `jarvis-scene-registry.json`.

**Préoccupation transversale « Auth / Principal »** — composants impactés : `SecurityConfig` (une seule route
`permitAll` ajoutée, `GET /jarvis/calendar/oauth/callback`, sans principal : le compte vient du `state` lié au
`user_id` et au navigateur) ; tous les autres points d'accès du paquet restent authentifiés (`CurrentUser`), testés 401
en SF-243-01/02. **« Contexte tenant »** : `JarvisCalendarGrantService` (`findByIdAndUserId`), `JarvisCalendarActionService`
(`user_id` + `workspace_id` du fil), `JarvisCalendarToolCatalog` (droit du `user_id` du tour), `AtelierChatService`
(fil Jarvis seulement).

## Marche à suivre pour le PO (hors code, console Google Cloud)
1. Avec le compte administrateur de **ng-itconsulting.com**, ouvrir `console.cloud.google.com` et créer un projet
   **dans l'organisation** ng-itconsulting.com (pas « Aucune organisation »).
2. « API et services » → « Bibliothèque » → activer **Google Calendar API**.
3. « Écran de consentement OAuth » → type **Interne** ; nom « Jarvis » ; adresse d'assistance ; portées :
   `openid`, `email`, `.../auth/calendar.events`.
4. « Identifiants » → « Créer des identifiants » → **ID client OAuth** → type **Application Web** ; URI de redirection
   autorisé : `https://portal.ng-itconsulting.com/api/jarvis/calendar/oauth/callback`.
5. Mettre l'identifiant et le secret dans le secret Kubernetes du backend (`backend-secrets`) :
   `APP_JARVIS_CALENDAR_OAUTH_CLIENT_ID`, `APP_JARVIS_CALENDAR_OAUTH_CLIENT_SECRET` ; redéployer. Sans eux, la
   fonction reste dormante (aucun outil, « pas encore configurée »).
6. Dans `/jarvis` → réglages → « Mes agendas » → « Relier l'agenda pro (Google) », se connecter avec le compte
   Workspace, accepter.

## Arbitrages
- **Témoin `HttpOnly` lié au `state`** (en plus du `user_id`) : l'application s'authentifie par jeton porteur, le retour
  Google n'en porte pas ; le témoin prouve que le retour vient du navigateur qui a lancé le parcours (anti-CSRF et
  anti « connexion forcée »). Route de retour en `permitAll`.
- **PKCE S256** en plus du secret client (défense en profondeur).
- **Portées `openid email`** ajoutées à `calendar.events` pour afficher l'adresse reliée (lue dans le jeton d'identité
  rendu directement par Google sur TLS).
- **Reformulation calculée par le serveur** (jamais par le modèle) : ce qui est dit est ce qui sera posé.
- **Annulation croisée non faite** : une pose en attente et une action de courrier en attente sont deux files ; chacune
  ne s'exécute que sur un « oui » qui suit sa propre proposition (la plus récente dans le fil).
- **Scène « le rendez-vous se pose » non branchée** (résultat d'outil seulement), comme la scène « brouillon » de
  F-242.
- **Jeton d'accès en mémoire du pod** (non persisté) : un autre pod rafraîchit simplement.

## Hors périmètre
L'écriture dans l'agenda gmail (D4, Q1 du PO) ; inviter des tiers ; modifier ou supprimer un événement existant ;
accepter / refuser une invitation (SF-243-04).
