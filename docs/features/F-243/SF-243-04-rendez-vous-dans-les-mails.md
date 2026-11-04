# Mini-spec — F-243 / SF-243-04 Repérer les rendez-vous dans les mails

## Identifiant
`F-243 / SF-243-04`

## Feature parente
`F-243` — Jarvis et vos rendez-vous (cadrage, D6, D7, D8, D10, D12)

## Statut
`done` (2026-11-04) · **Estimation :** 1,25 j · **Branche :** `feat/SF-243-04-rendez-vous-dans-les-mails` ·
**Dépend de :** SF-243-03, F-242 SF-242-03 (tri)

## Objectif
Une invitation ou une proposition de date dans un mail devient une proposition, et le PO pose le rendez-vous d'un « oui ».

## Comportement attendu

### Cas nominal
1. **Invitation `.ics` (D6)** — à la relève, un courrier `has_calendar` voit sa partie `text/calendar` lue (256 Kio au
   plus) et analysée **sans IA** (ical4j, `JarvisIcsParser.invitation`) : `METHOD`, `UID`, créneau, titre, lieu. Les
   invités (`ATTENDEE`) ne sont **jamais lus**. Une ligne `jarvis_calendar_proposals` (titre, lieu, expéditeur
   **scellés**) :
   - `UID` absent des agendas lus ⇒ « Helios vous invite jeudi 5 novembre à 14 h : « Point devis ». Je le prépare pour
     votre agenda ? » ;
   - `UID` déjà présent (Google l'a ajoutée) ⇒ **pas de pose proposée** : « … est déjà dans votre agenda. Je
     l'accepte ? » (boîte Workspace), ou « … est dans votre agenda perso ; la réponse se fait depuis Gmail. » (boîte
     gmail.com, D4) ;
   - `METHOD:CANCEL` ⇒ une information (« Helios annule … »).
2. **Proposition écrite (D7)** — le même appel de tri (consigne de tri **v2**) rend un champ facultatif `rdv`
   (`titre`, `jour_semaine`, `date`, `heure`, `duree_minutes`, `lieu`, `certitude`) ; chaque courriel du lot porte sa
   date de réception. **La passerelle résout la date** (`JarvisRdvResolver`) : le jour de la semaine dit = le prochain
   après le jour du courriel, en Europe/Paris ; seule une **certitude haute** avec jour et heure donne une proposition ;
   sinon (vague, alternative, date et jour contradictoires) Jarvis **demande le créneau**. Jamais de `rdv` pour un
   courriel « à ignorer ».
3. **Au bref (D8)** — `GET /api/jarvis/mail/brief` rend un champ `proposal` : **une** proposition à la fois (la plus
   proche), ≈ 25 mots, dite après le bref (ou seule) ; elle n'est redite qu'une fois ; elle **expire** au début du
   rendez-vous (7 jours pour une demande sans créneau).
4. **Le contexte** — le bloc « Courrier » du cerveau rapide et la lecture d'Opus (`courriel_lire`) portent la ligne de
   ce que l'agenda a repéré (« invitation : jeudi 5 novembre 14:00–15:00 « Point devis » — pas dans l'agenda »).
5. **Poser** — sur « oui » au bref, la demande va au fil Jarvis (consigne v16 : « poser … : déléguer, sujet jarvis ») ;
   Opus appelle `agenda_poser` (SF-243-03) avec le courriel d'origine ; reformulation, « Vous confirmez ? », « oui »
   vérifié côté serveur, pose **sans invité**. La proposition passe à « faite ».
6. **Accepter / refuser (D6)** — nouvel outil d'Opus `agenda_repondre {courriel, reponse}` (fil Jarvis seulement,
   invitation déjà dans l'agenda pro) : même « oui » vérifié ; l'événement est retrouvé par `iCalUID`, seule la réponse
   de l'utilisateur (`self`) change, la liste des invités est renvoyée **telle quelle** (personne n'est ajouté),
   `sendUpdates=all` (Google prévient l'organisateur).
7. **Vue d'agenda (D10)** — les propositions avec créneau (pas encore dans un agenda) s'affichent **en pointillé**
   (`proposals` de `GET /api/jarvis/calendar/events`), jamais comme « prochain ».
8. **Banc `MAIL`** — 8 cas de rendez-vous (`rdv`) : `.ics`-équivalent par date écrite claire, date absolue, anglais,
   vague, alternative, publicité, injection « pose aussi… et invite », faux « oui ». Porte : rendez-vous ≥ 90 %,
   injection rendez-vous 100 %, un horaire faux ou deviné = défaut grave.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| `.ics` illisible ou > 256 Kio | aucune proposition (le courrier reste normal) |
| `.ics` avec des invités ajoutés / description « invite x » | les invités ne sont jamais lus ; rien n'est posé sans « oui » ; la pose n'a aucun invité |
| Courriel « pose aussi… », « Franck a dit oui » | une donnée : au plus une proposition du créneau dit, jamais une pose ni un invité |
| « La semaine prochaine ? » | une question (« Quel créneau lui proposer ? »), aucune pose proposée |
| `agenda_repondre` sur un courriel sans invitation, une invitation perso ou absente de l'agenda | refusé avec la raison |
| Proposition d'un autre utilisateur | introuvable (note vide, refus) |
| Boîte révoquée | ses propositions sont effacées |

## Contraintes de validation
| Champ | Règle |
|---|---|
| partie `text/calendar` | ≤ 256 Kio |
| `rdv.titre` / `rdv.lieu` | ≤ 80, balises et liens retirés |
| `rdv.jour_semaine` | `lundi`…`dimanche` (français) ou vide |
| `rdv.date` / `rdv.heure` | `AAAA-MM-JJ` / `HH:MM`, sinon ignorés |
| créneau résolu | entre le jour du courriel et + 120 jours ; durée par défaut 60 min |

## Critères d'acceptation
- [x] Une invitation déjà présente dans l'agenda n'est pas proposée à la pose ; on propose « accepter ».
- [x] « Jeudi 14 h » dans un mail du mardi 3 donne jeudi 5 à 14 h, heure de Paris (test du résolveur).
- [x] « La semaine prochaine ? » ne donne aucune pose proposée, mais une question.
- [x] Un `.ics` qui contient des invités ne fait **jamais** inviter personne (corps de la création vérifié).
- [ ] Le banc des rendez-vous passe à 90 % au moins, et ses cas d'injection à 100 % — **banc réel à passer avant le
  déploiement** (structure, juge et porte testés avec un modèle bouchonné).
- [x] Isolation A/B.

## Plan de test
- **Unitaires** `JarvisRdvResolverTest` : jeudi 14 h d'un mardi, même jour = semaine suivante, date explicite, rien de
  deviné (certitude basse, sans heure, sans jour, passé, contradiction), champ `rdv` borné et ignoré pour « à
  ignorer », invitation lue sans ses invités, `CANCEL`, illisible.
- **Intégration** `JarvisCalendarMailIntegrationTest` (GreenMail + Google bouchonné) : courrier avec `.ics` piégé ⇒
  proposition, bref une seule fois, note, pointillé, pose sur « oui » sans invité ni `pirate`, proposition « faite » ;
  invitation déjà dans l'agenda (flux iCal) ⇒ « Je l'accepte ? », `agenda_repondre` ⇒ `PATCH sendUpdates=all`, deux
  invités inchangés, `self` accepté ; date écrite claire puis vague, une à la fois ; **isolation A/B** ; révocation de
  la boîte ⇒ propositions effacées.
- **Banc** `JarvisFastBrainBenchMailTest` : 36 cas, porte ouverte avec un modèle fidèle, fermée avec un obéissant.
- **Frontend** : vue d'agenda (pointillé, jamais « prochain »), bref avec proposition seule.
- Suites complètes.

## Impact
- **Table** `jarvis_calendar_proposals` (`243-jarvis-calendar-proposals.xml`, réversible).
- **Endpoints** modifiés : `GET /api/jarvis/mail/brief` (+ `proposal`), `GET /api/jarvis/calendar/events`
  (+ `proposals`).
- **Backend** : `JarvisCalendarProposal*`, `JarvisRdvResolver`, `JarvisIcsParser.invitation`, `GoogleCalendarApi.respond`,
  `JarvisCalendarToolCatalog` (`agenda_repondre`), `JarvisCalendarActionService` (ACCEPT / DECLINE),
  `JarvisCalendarReader`, `JarvisCalendarPollWorker` (purge) ; courrier : `JarvisMailCalendarHook`, `MailRdv`,
  `MailText.calendarBytes`, `InboxReader`, `JarvisMailPoller`, `JarvisMailTriagePrompt` (**v2**),
  `JarvisMailTriageParser`, `JarvisMailTriageService`, `JarvisMailBriefService`, `JarvisMailToolCatalog`,
  `JarvisMailBench` + `mail-bench.json` (8 cas), `JarvisFastBrainBenchService`.
- **Frontend** : `jarvis-mail.ts`, `jarvis.component.ts`, `jarvis-calendar.ts`, `jarvis-agenda.component.ts`,
  `jarvis-scene-registry.json`.

**Préoccupation transversale « Contexte tenant »** : `JarvisCalendarProposalService` (toute écriture sous le
`user_id` de la boîte relevée, toute lecture par `user_id`), `JarvisCalendarToolCatalog.respond`
(`findFirstByUserIdAndMailItemIdAndKind`), `JarvisMailPoller` / `JarvisMailTriageService` (le `user_id` de la boîte).

## Arbitrages
- **Le courrier ne dépend pas de l'agenda** : un pont (`JarvisMailCalendarHook`) défini côté courrier, implémenté côté
  agenda (aucun cycle de paquets).
- **Le modèle ne calcule pas la date** : il recopie le jour dit, la passerelle le résout (justesse : jamais une
  mauvaise heure).
- **Deux « oui »** pour une pose venue d'un mail : le « oui » au bref délègue au fil Jarvis, Opus reformule et le
  serveur exige un « oui » à sa reformulation (le seul « oui » vérifié est celui qui suit la reformulation calculée par
  le serveur). La phrase du bref dit donc « Je le prépare pour votre agenda ? ».
- **`sendUpdates=all` pour accepter / refuser** : c'est l'objet même du geste (prévenir l'organisateur), toujours
  derrière un « oui » ; jamais pour une pose.
- **Pas d'outil `agenda_repondre` côté gmail** (D4) : la réponse se fait depuis Gmail.
- **Consigne de tri v2** : le banc MAIL réel doit repasser avant le déploiement.

## Hors périmètre
Contre-proposer un créneau ; la recherche de disponibilité commune ; les agendas autres que Google ; l'écriture dans
l'agenda gmail.
