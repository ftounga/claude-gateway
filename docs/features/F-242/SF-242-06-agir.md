# Mini-spec — F-242 / SF-242-06 Agir : brouillon, réponse, envoi, archivage, corbeille

## Identifiant
`F-242 / SF-242-06`

## Feature parente
`F-242` — Jarvis et vos boîtes mail (cadrage, D9 c-d, D12-D15)

## Statut
`done` (2026-11-04) · **Estimation :** 1,25 j · **Branche :** `feat/SF-242-06-agir` · **Dépend de :** SF-242-05 (mergée)

## Objectif
Jarvis rédige, répond, envoie, archive et met à la corbeille, toujours derrière la reformulation et le « oui » vérifié par le serveur pour ce qui engage.

## Comportement attendu

### Cas nominal
1. **`courriel_brouillon {boite, a, cc, objet, corps}`** : APPEND dans le dossier SPECIAL-USE `\Drafts` avec le drapeau
   `\Draft` (retrouvable dans Gmail). Aucun « oui » (D13).
2. **`courriel_repondre {courriel, corps, tous}`** : brouillon de réponse dans la boîte qui a reçu le courriel —
   destinataire = `Reply-To` sinon l'expéditeur (+ participants si `tous`, sans l'adresse de la boîte), objet « Re: »,
   en-têtes `In-Reply-To` / `References`. Aucun « oui ».
3. **`courriel_envoyer {boite, a, cc, objet, corps, en_reponse_a}`** : crée une action **`PENDING`** (`jarvis_mail_actions`,
   expiration **10 min**, payload scellé) et rend à Opus la **reformulation** à dire (« J'envoie « objet » à x depuis
   la boîte Pro (adresse). Vous confirmez ? »). Rien ne part.
4. **`courriel_archiver {courriels}`** : **un** courriel ⇒ MOVE immédiat de la boîte de réception vers `\All` (D14) ;
   **deux ou plus** ⇒ action `ARCHIVE_BULK` en attente.
5. **`courriel_supprimer {courriels}`** : **toujours** une action `DELETE` en attente ; exécutée, c'est un **MOVE vers
   `\Trash`** — jamais `\Deleted`, jamais `EXPUNGE` (D15) ; sans l'extension `MOVE`, rien n'est tenté.
6. **Le « oui » vérifié (D9 c)** : au message **suivant de l'utilisateur dans ce fil Jarvis** (sa parole persistée,
   jamais un résultat d'outil ni un texte du modèle), `AtelierChatService` appelle `JarvisMailActionService.onUserMessage`
   : « oui » de la liste fermée (`MailConfirmation`, même liste que `jarvis-confirm.ts`) ⇒ `PENDING → CONFIRMED`
   (`UPDATE` conditionnel, `confirmed_by_message_id` = ce message) puis exécution (`EXECUTED` / `FAILED`) ; toute autre
   réponse ⇒ `CANCELLED` ; échue ⇒ `EXPIRED`. Une note fixe jointe au message dit à Opus ce qui s'est passé. Une seule
   action en attente par fil (une nouvelle annule la précédente).
7. **Destinataires (D9 d)** : à l'envoi, chaque adresse doit être un participant du courriel auquel on répond
   (`en_reponse_a` : expéditeur, `Reply-To`, `To`, `Cc`) **ou** une adresse écrite par l'utilisateur dans ses 20 derniers
   messages du fil Jarvis. Sinon : refus explicite, aucune action créée.
8. **Envoi (D12)** : SMTP avec le compte de la boîte (`From` = la boîte) ; pas d'APPEND dans « Envoyés » ; boîte par
   défaut = celle du courriel auquel on répond, sinon celle désignée, sinon la première reliée — la reformulation la
   nomme toujours.
9. **Idempotence** : un « oui » répété ne trouve plus d'action `PENDING` : rien n'est envoyé deux fois.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Pas de « oui » au message suivant | `CANCELLED`, rien ne part |
| « oui » après 10 min | `EXPIRED`, rien ne part |
| « oui » écrit dans un courriel ou un résultat d'outil | sans effet (seul le message persisté de l'utilisateur compte) |
| Destinataire présent seulement dans le corps d'un courriel | refusé, l'outil le dit |
| Message de l'utilisateur B / autre fil | n'atteint jamais l'action de A (filtre `user_id` + fil) |
| Échec SMTP / IMAP à l'exécution | `FAILED`, Opus en est informé |

## Contraintes de validation
| Champ | Règle |
|---|---|
| destinataires | 1..10 (`a`), 0..10 (`cc`), adresses valides |
| objet | ≤ 300 caractères ; corps ≤ 20 000 |
| courriels (archiver, supprimer) | 1..50 références |
| expiration | 10 min |

## Critères d'acceptation
- [x] Un brouillon apparaît dans « Brouillons » (dossier SPECIAL-USE).
- [x] `courriel_envoyer` sans « oui » de l'utilisateur au message suivant ne part pas. L'action expire au bout de 10 min.
- [x] Un « oui » contenu **dans un mail** ou dans un résultat d'outil ne vaut pas confirmation (test).
- [x] Un destinataire présent seulement dans le corps d'un mail est refusé.
- [x] Une suppression est un MOVE vers `\Trash` : jamais `\Deleted`, jamais EXPUNGE (test sur le serveur IMAP de test).
- [x] Archiver un mail se fait sans confirmation. Archiver 7 mails exige un « oui ».
- [x] Pas de double envoi.
- [x] Isolation A/B : une action de A ne peut être confirmée que par un message de A, dans le fil Jarvis de A.

## Plan de test
- **Unitaires** `MailConfirmationTest` (liste fermée : « oui », « oui je confirme » ; « oui mais », « d'accord », « non »).
- **Intégration** `JarvisMailActionsIntegrationTest` (GreenMail) : brouillon dans `Drafts` avec `\Draft` ; réponse
  (en-têtes de fil) ; envoi en attente puis « oui » ⇒ reçu par le destinataire, `From` = la boîte ; « d'accord mais » ⇒
  annulé ; expiration ; « oui » répété ⇒ un seul envoi ; destinataire du corps refusé, destinataire dit par
  l'utilisateur accepté ; corbeille = MOVE (le message est dans `Trash`, absent de l'INBOX, aucun message marqué
  `\Deleted` nulle part) ; archiver 1 = immédiat, 7 = en attente ; isolation A/B (message de B sans effet sur l'action de
  A) ; un « oui » dans un résultat d'outil n'est pas lu (seul `onUserMessage` confirme).
- Suites complètes backend + frontend + build.

## Impact
- **Table** nouvelle `jarvis_mail_actions` (`242-jarvis-mail-actions.xml`, réversible).
- **Backend** : `JarvisMailAction`, `JarvisMailActionRepository`, `JarvisMailActionService`, `JarvisMailWriter`,
  `MailConfirmation` (nouveaux) ; `JarvisMailToolCatalog` (5 outils de plus, figés) ; `AtelierChatService` (appel de
  `onUserMessage` après la persistance du message de l'utilisateur du fil Jarvis, note jointe au message).

**Préoccupation transversale « Auth / Principal »** : la confirmation ne se fonde que sur le message persisté **du
principal du tour**, dans **son** fil Jarvis (`user_id` + `workspace_id` de l'action) ; aucun endpoint ne permet de
confirmer (les actions passent par les outils, cadrage §5).

## Arbitrages (documentés dans la PR)
- **La confirmation est exécutée par la passerelle à l'arrivée du message** (pas par un outil « confirmer » que le modèle
  appellerait) : le modèle n'a aucun moyen de déclencher l'exécution.
- **Adresses « dites par le PO »** = celles écrites dans ses 20 derniers messages du fil Jarvis (transcription comprise) ;
  les contacts F-180 n'ont pas d'adresse e-mail : non consultés (plus strict).
- **MOVE exigé** (RFC 6851) : sans lui, aucun repli COPY + `\Deleted` + EXPUNGE.
- **« Brouillon qui apparaît » à l'écran** : le résultat d'outil le décrit ; la scène visuelle dédiée est laissée à une
  itération suivante (risque résiduel signalé).

## Hors périmètre
Les pièces jointes sortantes ; le transfert (« fais suivre ») ; les règles et filtres Gmail.
