# Mini-spec — F-243 / SF-243-01 Lire les agendas par l'adresse iCal secrète

## Identifiant
`F-243 / SF-243-01`

## Feature parente
`F-243` — Jarvis et vos rendez-vous (cadrage `CADRAGE-F-243-jarvis-et-vos-rendez-vous.md`, D1, D2, D12, D13)

## Statut
`done` (2026-11-04) · **Estimation :** 1 j · **Branche :** `feat/SF-243-01-lire-les-agendas` · **Dépend de :** —

## Objectif
Les rendez-vous des agendas Google du PO sont dans le cache, à jour au quart d'heure, sans aucune IA.

## Comportement attendu

### Cas nominal
1. **Ajouter** — `POST /api/jarvis/calendar/feeds` `{url, label}` : l'adresse est validée (garde anti-SSRF :
   `https` vers `calendar.google.com` seulement, `webcal://` accepté et réécrit, aucun identifiant dans l'adresse),
   **lue** puis **analysée** ; si elle donne un agenda iCal lisible, elle est chiffrée par `ByokKeyCipher` (patron
   `user_git_credentials`) et l'agenda est enregistré `OK` avec ses occurrences. Rien n'est écrit avant. Une adresse
   déjà ajoutée par ce compte (même empreinte SHA-256) voit seulement son libellé changer et elle est relue.
2. **Lister** — `GET /api/jarvis/calendar/feeds` : `[{id, label, status, secretLast4, eventCount, lastOkAt, lastError,
   createdAt}]`. L'adresse n'est **jamais** renvoyée.
3. **Tester** — `POST /api/jarvis/calendar/feeds/{id}/test` : relecture sans condition, analyse refaite, état rendu.
4. **Retirer** — `DELETE /api/jarvis/calendar/feeds/{id}` : l'agenda et ses occurrences en cache.
5. **Relève (D2)** — un worker serveur (tick 60 s) relève chaque agenda **dû** (toutes les 15 min), réservé par un
   bail (`UPDATE` conditionnel, 2 min). Requête **conditionnelle** (`If-None-Match` / `If-Modified-Since`) ; un 304,
   ou un corps de même empreinte, ne donne **aucune analyse**. La fenêtre J-1 → J+60 glisse : un flux inchangé est
   redéployé au plus une fois par 24 h.
6. **Analyse (ical4j)** — événements simples, journées entières, `RRULE`, `EXDATE`, occurrences modifiées
   (`RECURRENCE-ID`), annulations (`STATUS:CANCELLED`, non gardées), `TZID` avec ou sans `VTIMEZONE`, nom de fuseau
   Windows (repli Europe/Paris), heures flottantes lues dans le fuseau de l'agenda (`X-WR-TIMEZONE`, sinon Paris).
   2 000 occurrences au plus par agenda.
7. **Données gardées (D13)** — titre (≤ 200), lieu (≤ 200) et description courte (≤ 300) **scellés** ensemble
   (`PortraitSeal`, une colonne `sealed`), heures en clair pour l'index (`user_id`, `starts_at`). Purge chaque nuit des
   occurrences finies depuis plus de 7 jours. Aucun historique.
8. **Mesure** — `jarvis_usage_days.calendar_polls` et `calendar_polls_unchanged`. Le délai « modifié chez Google → vu
   par Jarvis » (G3) est relevé à la main à l'essai.
9. **Écran** — bouton « Mes agendas » dans les réglages de `/jarvis` (à côté de « Mes boîtes »), volet : pas-à-pas en
   3 lignes (paramètres de l'agenda ; « Adresse secrète au format iCal », lien vers l'aide Google ; la coller), champ
   adresse **masqué** (secret), libellé, liste des agendas avec état, `…last4`, nombre de rendez-vous, « à jour à
   hh:mm », « Tester », « Retirer » (confirmation). **Masqué en présentation.**
10. **Purge du compte** : `AccountService` supprime agendas et occurrences.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Adresse hors `calendar.google.com`, schéma autre que `https`, identifiants dans l'adresse, > 1 024 | refusé **avant tout appel réseau** | 400 `invalid_calendar_feed` |
| Google répond 404/410 à l'ajout | « Google ne reconnaît pas cette adresse… » ; rien n'est écrit | 400 `calendar_feed_gone` |
| Le corps n'est pas un agenda iCal (ou > 20 Mo) | rien n'est écrit | 400 `calendar_unreadable` |
| Google injoignable / délai / 5xx | rien n'est écrit | 503 `calendar_unavailable` |
| 11ᵉ agenda | refusé | 400 `calendar_feeds_full` |
| Agenda d'un autre utilisateur (tester, retirer) | introuvable | 404 `not_found` |
| 404 pendant la relève | `status = BROKEN`, **une** notification `CALENDAR_BROKEN` (au passage OK → BROKEN), relève toutes les 6 h, occurrences connues gardées ; une adresse qui revient repasse `OK` | — |
| Chiffrement non configuré | jamais de repli en clair | 503 (existant) |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `url` | `https://calendar.google.com/…` (hôtes en réglage, tests : `127.0.0.1` en HTTP), ≤ 1 024, unique par (`user_id`, `url_hash`) |
| `label` | facultatif, ≤ 40 (défaut « Agenda ») |
| agendas par compte | 10 au plus (réglage) |
| corps d'un flux | ≤ 20 Mo |
| occurrences | fenêtre J-1 → J+60 (journées de Paris), ≤ 2 000 par agenda |
| `status` | `OK`, `BROKEN` |

## Critères d'acceptation
- [x] Une adresse valide donne des occurrences dans la fenêtre, récurrences et fuseau compris (réunion hebdomadaire à
  9 h Paris à travers le passage à l'heure d'hiver du 25/10 : 07:00Z puis 08:00Z).
- [x] Un flux inchangé ne donne aucune analyse (304 ; même empreinte sans `ETag`).
- [x] Une erreur 404 fait passer l'état à `BROKEN` et déclenche une seule notification.
- [x] L'adresse est chiffrée et n'est jamais renvoyée (ni l'adresse ni le titre en clair en base).
- [x] L'utilisateur B reçoit 404 sur les flux de A et ne les voit pas.
- [x] La suppression du compte purge les tables.
- [x] Le volet est absent en présentation.

## Plan de test
- **Unitaires** `JarvisIcsParserTest` (10) : simple avec `TZID`, hebdomadaire + `EXDATE` à travers le changement
  d'heure, occurrence déplacée et annulée, journée entière et heure flottante, UTC et fuseau étranger sans
  `VTIMEZONE`, nom Windows, fenêtre (récurrence ancienne, passé), événement en cours au début de la fenêtre, `METHOD`
  et bornes du texte, corps illisible.
- **Intégration** `JarvisCalendarFeedIntegrationTest` (5) avec un serveur HTTP local (`CalendarTestServer`, 200 /
  304 / 404 / HTML) : ajout (adresse et texte jamais en clair, relecture descellée, même adresse ⇒ une ligne),
  mauvaises adresses (404, HTML, 5 adresses SSRF) ⇒ aucune ligne, 304 et même empreinte ⇒ `UNCHANGED` + compteurs,
  changement ⇒ cache remplacé, bail tenu ⇒ sauté, fenêtre glissante, 404 ⇒ `BROKEN` + une notification puis retour
  `OK` par « Tester », **isolation A/B** (liste, tester, retirer, lecture du cache), 401 sans jeton, retrait, purge.
- **Frontend** `jarvis-calendars.component.spec.ts` (8) : liste (état, `…last4`, « à jour à », jamais l'adresse),
  champ masqué et aide Google, ajouter, refus affiché, champ vide, retirer après confirmation, tester ;
  `jarvis.component.spec.ts` : bouton présent, absent en présentation même ouvert.
- Liquibase (contexte de test), suites complètes backend + frontend + build.

## Impact
- **Tables** nouvelles `jarvis_calendar_feeds` (`243-jarvis-calendar-feeds.xml`), `jarvis_calendar_events`
  (`243-jarvis-calendar-events.xml`) ; colonnes `jarvis_usage_days.calendar_polls*` (`243-jarvis-usage-calendar.xml`).
  Toutes réversibles.
- **Endpoints** nouveaux : `GET/POST /api/jarvis/calendar/feeds`, `POST /api/jarvis/calendar/feeds/{id}/test`,
  `DELETE /api/jarvis/calendar/feeds/{id}`. (`GET /api/jarvis/calendar/events` : SF-243-02.)
- **Backend** : paquet `fr.claudegateway.jarvis.calendar` (nouveau), `PushEvent.CALENDAR_BROKEN`,
  `JarvisUsageDay`/`JarvisUsageService`, `AccountService` (purge), `JarvisConfig`, `application.yml`
  (`app.jarvis.calendar.*`, `APP_JARVIS_CALENDAR_ENABLED`), `ical4j.properties`, `pom.xml` (**ical4j**).
- **Frontend** : `jarvis/jarvis-calendar.ts`, `jarvis/jarvis-calendars.component.ts`, `jarvis.component.{ts,html}`.
- **Docs** : `TECH_STACK.md` (variante ical4j).

**Préoccupation transversale « Contexte tenant »** — composants qui résolvent l'appartenance d'un agenda :
`JarvisCalendarFeedRepository.findByIdAndUserId` (seul accès par id venant de l'API), `JarvisCalendarFeedService`
(tous les gestes), `JarvisCalendarEventRepository` (toute lecture filtre `user_id`), `JarvisCalendarStore` (écrit sous
le `user_id` de l'agenda relu), `AccountService` (purge). Aucune autre préoccupation (pas d'auth, plan ni route).

## Arbitrages (documentés dans la PR)
- **ical4j 4.0.8** (BSD) — variante de pile signalée dans `TECH_STACK.md` ; `groovy`/`jparsec` exclus. Le registre de
  fuseaux d'ical4j ne connaît que les `VTIMEZONE` du flux : repli sur `java.time`, puis sur Europe/Paris pour un nom
  Windows (cas fréquent des invitations Outlook).
- **Garde anti-SSRF** : liste d'hôtes admis (`calendar.google.com`), HTTPS, aucune redirection hors liste. Pas dans le
  cadrage mais nécessaire (le serveur lit une adresse saisie).
- **Colonnes en plus du §5** : `url_hash` (unicité sans clair), `last_modified`, `expanded_at` (fenêtre glissante),
  `event_count`, `last_error`, `created_at`. Titre/lieu/description **scellés ensemble** dans une colonne `sealed` (un
  appel de chiffrement par occurrence au lieu de trois, patron `jarvis_mail_items`).
- **10 agendas au plus** (le cadrage dit « sans limite ») : borne de sûreté, en réglage.
- **Fenêtre glissante** : un flux inchangé est redéployé une fois par 24 h (sinon J+60 reculerait).
- **`BROKEN` relevé toutes les 6 h** (une réinitialisation temporaire ou une erreur Google se répare seule).
- **Remplacement en bloc** des occurrences quand le flux change (simple ; quelques changements par jour).
- **Une PR back + front** (SF séquentielle, contrat interne figé dans cette mini-spec).

## Hors périmètre
L'écriture (SF-243-03) ; la lecture des occurrences par l'API, l'outil `agenda` et la pièce `AGENDA` (SF-243-02) ; le
repérage dans les mails (SF-243-04) ; CalDAV ; les agendas non Google.
