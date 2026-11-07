# Mini-spec — F-249 / SF-249-01 Le dépôt reçu : lecture `.msg`/`.eml`, dédoublonnage, entrée dans le cache F-242

## Identifiant
`F-249 / SF-249-01`

## Feature parente
`F-249` — Les mails et l'agenda Outlook de vos clients (cadrage D3, D5, D6, D7, D8, D18, D19)

## Statut
`in-progress`

## Date de création
2026-11-07

## Branche Git
`feat/SF-249-01-depot-recu`

## Objectif
Un fichier `.msg` ou `.eml` envoyé par le runner d'un poste devient, une seule fois, un courrier du cache F-242
rattaché à ce client, trié par le pipeline existant et lisible par Jarvis runner éteint.

## Comportement attendu

### Cas nominal
1. `POST /runner/mail-deposit` — corps brut (`application/octet-stream`), en-têtes `X-Runner-Token` (vérifié par le
   contrôleur, rien dans le contexte de sécurité) et `X-File-Name` (nom du fichier, pour l'extension). L'identité
   (compte + poste = client) vient **du jeton seul**.
2. Lecture **bornée**, côté serveur : `.msg` par Apache POI HSMF (`OutlookMsgReader`), `.eml` par `jakarta.mail`
   (`EmlReader`) ⇒ `DepositedMail` : Message-ID, fil (`In-Reply-To`, sinon dernière référence), en-têtes `MailHeaders`
   (expéditeur **SMTP**, nom, à, cc, réponse-à, objet, extrait 2 000 car.), date (réception, sinon envoi), indice
   `bulk` (`List-Unsubscribe`, `Precedence`, `Auto-Submitted` hors `no`, expéditeur automatique), corps texte
   ≤ 8 000 car. (HTML ⇒ texte), invitation (`.ics`/`text/calendar` ≤ 256 Kio ; demande de réunion Outlook
   `IPM.Schedule.Meeting.Request|Canceled` ⇒ un `.ics` équivalent `REQUEST`/`CANCEL` composé depuis ses propriétés
   nommées : début, fin, lieu, identifiant global).
3. Dédoublonnage : empreinte SHA-256 du fichier déjà vue pour ce client ⇒ `DUPLICATE` ; sinon Message-ID déjà
   présent parmi les courriers déposés de ce client ⇒ `DUPLICATE` (le dépôt est noté) ; mail reçu il y a plus de
   30 jours ⇒ `IGNORED`.
4. Sinon : une ligne `jarvis_mail_items` (`source = DEPOT`, `host_id`, `account_id` nul, `uid = uidvalidity = 0`,
   en-têtes scellés, corps scellé dans `body_sealed`, `triage = EN_ATTENTE`), une ligne `jarvis_mail_deposits`
   (`ACCEPTED`), et un travail `MAIL_DEPOSIT` dans `async_jobs` (charge : poste + courrier ; invitation scellée en
   `payload_bytes`). Réponse `200 {"outcome":"ACCEPTED"}`.
5. Le travail `MAIL_DEPOSIT` (asynchrone, relu sous le `user_id` du travail) lance le **tri F-242 inchangé**
   (`triageNow`, consigne figée, budget Jarvis respecté). Les SF suivantes y brancheront règles, rattachement,
   agenda et annonces.
6. Lecture sans IMAP : l'écran (`GET /api/jarvis/mail/items/{id}`) et Opus (`courriel_lire`) rendent le **corps
   scellé** d'un courrier déposé ; le libellé de boîte est « Outlook <client> ». Le bref, le bloc « Courrier » et
   l'outil `courriels` le voient déjà (ils lisent le cache).
7. Actions refusées : brouillon/réponse/envoi/archivage/suppression sur un courrier déposé ⇒ « Courriel déposé depuis
   l'Outlook de <client> : on y répond depuis Outlook. »
8. Purges : compte supprimé (courriers déposés + dépôts), poste supprimé (`RunnerHostLifecycleEvent`), 30 jours.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Jeton runner absent, faux, expiré | refus générique | 401 |
| Coupe-circuit `APP_JARVIS_CLIENT_MAIL_ENABLED=false` | rien n'est lu, le runner retentera | 503 |
| Corps vide | « Fichier vide. » | 400 |
| Plus de 20 Mo | « Fichier trop lourd. » | 413 |
| Extension autre que `.msg`/`.eml` | « Format non pris en charge. » | 415 |
| Fichier illisible (OLE corrompu, MIME invalide, aucun en-tête utile) | « Fichier illisible. » | 422 |
| `.ics` > 256 Kio ou illisible | courrier gardé, sans invitation | 200 |
| Même fichier redéposé / même Message-ID | `DUPLICATE`, rien de retraité | 200 |
| Mail de plus de 30 jours | `IGNORED` | 200 |
| Action (répondre, supprimer…) sur un courrier déposé | refus explicite par l'outil | — |
| Courrier d'un autre compte | introuvable | 404 |

## Contraintes de validation
| Champ | Règle |
|---|---|
| corps | 1 octet à 20 Mo (`APP_JARVIS_CLIENT_MAIL_MAX_BYTES`) |
| `X-File-Name` | se termine par `.msg` ou `.eml` (casse ignorée) ; 255 car. au plus ; jamais stocké |
| Message-ID | ≤ 500 car. |
| extrait / corps | 2 000 / 8 000 caractères |
| invitation | ≤ 256 Kio |

## Critères d'acceptation
- [ ] Un `.eml` et un `.msg` valides donnent chacun un courrier `DEPOT` du bon client, en-têtes et corps scellés
      (aucun texte clair en base), `EN_ATTENTE` puis trié par le cerveau rapide (modèle bouchonné).
- [ ] Le même fichier redéposé ⇒ `DUPLICATE`, aucune nouvelle ligne, aucun nouveau tri ; un autre fichier au même
      Message-ID ⇒ `DUPLICATE`.
- [ ] Une demande de réunion Outlook `.msg` donne une invitation `REQUEST` (début, fin, lieu, UID) ; un `.ics` joint
      est repris tel quel.
- [ ] L'expéditeur Exchange interne est rendu par son adresse SMTP, pas par son DN X.500.
- [ ] `courriel_lire` et `GET /api/jarvis/mail/items/{id}` rendent le corps d'un courrier déposé sans IMAP ; les
      actions sont refusées avec la raison.
- [ ] 401 sans jeton, 413, 415, 422, 503 (coupe-circuit).
- [ ] **Isolation** : le courrier est écrit sous le compte du jeton ; B ne le voit pas (404) ; le dédoublonnage ne
      croise jamais deux comptes ni deux clients.
- [ ] Purges : compte, poste supprimé, 30 jours.

## Plan de test
- **Unitaires** `OutlookMsgReaderTest` (`.msg` fabriqués par POIFS dans le test : courrier simple, expéditeur DN +
  SMTP, en-têtes de transport `List-Unsubscribe`, pièce `.ics`, demande de réunion à propriétés nommées, annulation,
  fichier corrompu), `EmlReaderTest` (texte, HTML seul, `text/calendar`, `bulk`).
- **Intégration** `ClientMailDepositIntegrationTest` (MockMvc + H2) : nominal `.eml`/`.msg`, doublons (empreinte,
  Message-ID), > 30 j, 401/413/415/422/503, travail `MAIL_DEPOSIT` exécuté ⇒ trié (fournisseur bouchonné), lecture du
  corps (route écran), outil `courriel_lire`, action refusée, **isolation A/B** (route, dédoublonnage), purge du poste
  et du compte.
- Suite complète backend à la review (V2).

## Impact
- **Tables** : `jarvis_mail_items` (+ `source`, `host_id`, `body_sealed` ; `account_id` nullable),
  `jarvis_mail_deposits` (nouvelle). Migration `249-01-jarvis-client-mail-deposits.xml`, réversible (rollback :
  suppression des lignes `DEPOT`, puis des colonnes).
- **Endpoints** : `POST /runner/mail-deposit` (nouveau, déclaré seul dans `RunnerSecurityConfig`).
- **Backend** : paquet `jarvis/clientmail` (`ClientMailProperties`, `DepositedMail`, `OutlookMsgReader`, `EmlReader`,
  `ClientMailDeposit*`, `ClientMailDepositService`, `ClientMailDepositController`, `ClientMailDepositJobHandler`),
  `JarvisMailItem`, `JarvisMailItemService` (libellé « Outlook <client> »), `JarvisMailReader`, `JarvisMailBox`
  (lecture sans IMAP, actions refusées), `JarvisMailTriageService.triageNow`, `pom.xml` (`poi-scratchpad` 5.3.0 —
  **variante de pile**, `docs/TECH_STACK.md`).
- **Consignes** : aucune ne change ⇒ aucun banc.

## Préoccupations transversales
- **Auth / Principal** : nouvelle route authentifiée par jeton runner, vérifiée par le contrôleur (patron
  `RunnerDepartureController`). Composants impactés : `RunnerSecurityConfig` (une ligne `permitAll` pour cette route
  seule) ; aucune autre route ne change. Test 401.
- **Contexte tenant** : le `user_id` et le `host_id` viennent du jeton (`RunnerTokenAuthenticator`) ; le travail
  relit sous le `user_id` du travail ; `JarvisMailItemService.views` filtre déjà par `user_id`.
- Plans / limites : aucun. Navigation : aucune.

## Hors périmètre
Le runner (SF-249-02), le rattachement et les annonces (SF-249-03), l'agenda des clients (SF-249-04), le rappel
(SF-249-05), les règles « ignorer » (SF-249-06), les écrans (SF-249-07/08). Les demandes de réunion **récurrentes**
Outlook sans `.ics` (structure binaire `AppointmentRecur`) : seule la première occurrence est lue.
