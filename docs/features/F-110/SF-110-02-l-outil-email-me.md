# Mini-spec — F-110 / SF-110-02 — L'outil `email_me`

> Base : `docs/features/F-110/CADRAGE-F-110-le-courriel-du-client.md` §2, §4, §6, §7, §9 (cadrage validé par le
> PO, non rediscuté ici). S'appuie sur SF-110-01 (`HostMailAddressService.resolveRecipient`).

## Identifiant

`F-110 / SF-110-02`

## Feature parente

`F-110` — Le courriel du client : l'application m'envoie des courriels

## Statut

`done` — livrée le 2026-09-13 (PR #565)

## Date de création

2026-09-13

## Branche Git

`feat/SF-110-02-email-me`

---

## Objectif

Donner à l'agent de tout terminal d'un poste un outil `email_me` (objet + corps Markdown, **sans aucun champ
destinataire**) qui met en file un courriel vers le destinataire **résolu par la gateway** (adresse vérifiée du
client, sinon adresse du compte, dit), envoyé en tâche de fond avec reprise, et qui pose dans le terminal un bloc
« Courriel envoyé » portant l'état de remise.

---

## Comportement attendu

### Cas nominal

1. **La garde** — `email_me` est donné (dans `buildTools`) seulement si : le workspace s'exécute sur un **poste**
   (`hostId` non nul, cible `RUNNER`) et l'utilisateur a le droit **Forge ou Vigie** (`SpaceEntitlementService`,
   ADMIN d'office via `AdministratorEntitlement`). Sinon l'outil n'existe pas ; appelé quand même, il est refusé
   en une phrase.
2. **La description** de l'outil, construite au tour, **nomme le destinataire** : « Destinataire (résolu par la
   gateway, non modifiable) : franck.tounga@cagip.fr, adresse vérifiée du client CAGIP » — ou, en repli :
   « Aucune adresse vérifiée pour CAGIP : les courriels partent à l'adresse du compte ntounga@gmail.com. Dis-le à
   l'utilisateur avant d'envoyer. » Elle porte aussi les règles : pas de secret, et la règle des transcriptions
   bloquées (F-87 §9 bis : résumé oui, transcription brute non).
3. **Le schéma** : `subject` (texte, requis), `body` (Markdown, requis). **Aucun champ destinataire** ; tout
   autre champ fourni par le modèle (`to`, `cc`…) est **ignoré**.
4. **L'appel** (exécuté dans la gateway, jamais sur la machine, aucune confirmation) :
   1. valide objet et corps ; 2. refuse un objet ou un corps qui contient manifestement un secret ; 3. vérifie la
   limite de **50 courriels par compte sur 24 heures glissantes** ; 4. résout le destinataire
   (`resolveRecipient`) ; 5. rend le Markdown en **HTML sobre** (échappement du HTML brut, liens assainis) + une
   **version texte** ; 6. écrit une ligne `client_emails` `PENDING` et rend au modèle : « Courriel mis en file
   pour franck.tounga@cagip.fr (adresse vérifiée de CAGIP) — objet « … ». L'état de remise s'affiche dans le
   terminal. » (en repli : la phrase dit l'adresse du compte et demande de le dire à l'utilisateur).
5. **Le bloc « Courriel envoyé »** — relayé au fil de l'eau (événement SSE `email`) et gardé dans la transcription
   du tour (champ `email` du bloc) : « Courriel envoyé à franck.tounga@cagip.fr — objet — état ». L'écran relit
   `GET /api/client-emails/{id}` tant que l'état n'est pas final : *en cours d'envoi* → *accepté par le relais* ou
   *non remis : motif*. Il dit le repli (« aucune adresse vérifiée pour CAGIP : envoyé à l'adresse du compte ») et
   « vérifiez vos courriers indésirables la première fois ». Présent dans **tous** les terminaux (projet, poste,
   Teams).
6. **L'envoi asynchrone** — un travailleur (`@Scheduled`, désactivable) prend les courriels dus un par un (bail
   de 2 min, tous pods confondus), les envoie par le `JavaMailSender` existant (délais bornés F-77, 5 s),
   expéditeur `MAIL_FROM` avec le **nom affiché « claude-gateway pour <client> »**, en `multipart/alternative`
   (texte + HTML). **Accepté** → `SENT` (`sent_at`). **Refus définitif** (adresse refusée par le relais, message
   invalide) → `FAILED` avec motif. **Échec passager** (relais injoignable, délai dépassé) → reprise à 1, 5, 15,
   60 min ; au 5ᵉ échec → `FAILED`. Dès l'état final, **le corps est effacé** de la ligne.
7. **Le journal** — la ligne `client_emails` garde date, poste, objet, taille, nombre de pièces, destinataire,
   état et motif — **jamais le corps** une fois l'envoi terminé ; les journaux applicatifs ne portent que
   l'identifiant, le poste, la taille et l'état (ni objet, ni corps, ni adresse).

### Cas d'erreur

| Situation | Comportement attendu | Code / effet |
|-----------|---------------------|-----------|
| Objet absent, > 200 caractères ou sur plusieurs lignes | résultat d'outil en erreur, rien n'est mis en file | outil `is_error` |
| Corps absent ou > 100 000 caractères | idem | outil `is_error` |
| Secret manifeste (clé privée, clé AWS, jeton GitHub/Slack/Stripe/Anthropic/OpenAI/Google, JWT, `mot de passe : …`) | refus, la phrase nomme la nature du secret sans le recopier | outil `is_error` |
| 50 courriels déjà mis en file sur 24 h | refus « limite de 50 courriels par jour atteinte » | outil `is_error` |
| Workspace sans poste, ou sans droit Forge/Vigie | outil non donné ; appelé quand même → refus | outil `is_error` |
| Relais indisponible | reprise ; après 5 échecs `FAILED` « relais injoignable » | état du bloc |
| Adresse refusée par le relais | `FAILED` « adresse refusée par le relais » sans reprise | état du bloc |
| `GET /client-emails/{id}` d'autrui ou inconnu | indiscernables | 404 |
| `GET /client-emails/{id}` sans droit Forge/Vigie | refus | 403 |

---

## Critères d'acceptation

- [ ] `email_me` est donné dans un terminal de poste avec droit Forge ou Vigie (ADMIN compris), jamais sur un workspace sans poste ni sans droit.
- [ ] Le schéma de l'outil n'a aucun champ destinataire ; un `to` fourni par le modèle est ignoré et le destinataire reste celui résolu par la gateway.
- [ ] Sans adresse vérifiée, le courriel part à l'adresse du compte et la description, le résultat et le bloc le disent.
- [ ] Un objet ou un corps contenant un secret manifeste est refusé sans rien mettre en file.
- [ ] Le 51ᵉ courriel sur 24 h est refusé.
- [ ] Le travailleur envoie en `multipart/alternative` (texte + HTML échappé), nom affiché « claude-gateway pour <client> » ; accepté → `SENT` ; refus définitif → `FAILED` sans reprise ; échec passager → reprise puis `FAILED` au 5ᵉ ; le corps est effacé à l'état final.
- [ ] Le bloc « Courriel envoyé » apparaît au fil de l'eau et après rechargement, dans tous les terminaux, et suit l'état de remise.
- [ ] Isolation : `GET /client-emails/{id}` d'autrui → 404 ; toute écriture et lecture filtre `user_id` (+ `host_id` à la résolution).
- [ ] Aucune ligne de journal applicatif ne contient le corps, l'objet ou l'adresse.
- [ ] DESIGN_SYSTEM respecté pour le bloc (jetons `--cg-*`, aucune couleur nouvelle).

---

## Périmètre

### Hors scope (explicite)

- Les pièces jointes (SF-110-03) : `attachment_count` existe et vaut 0 ; le schéma n'a pas encore de champ.
- Le résumé du matin par courriel (SF-110-04), qui réutilisera la file.
- Un écran de journal des envois ; l'annulation d'un envoi en file.
- Écrire à un tiers, `cc`/`bcc`, répondre à un courriel.
- Le moteur Managed Agents (projets sans poste) : pas de poste, donc pas de client ni d'adresse.
- SPF/DKIM/DMARC du domaine d'envoi (geste de déploiement).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `status` | `PENDING` | puis `SENDING` (bail) → `SENT` / `FAILED` |
| `attempts` | 0 | +1 à chaque prise |
| `next_attempt_at` | maintenant | reculé à chaque échec passager |
| `kind` | `AGENT` | `MORNING_SUMMARY` réservé à SF-110-04 |
| `attachment_count` | 0 | SF-110-03 |
| `body_text`, `body_html` | rendus à la mise en file | effacés à l'état final |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `subject` | Oui | 200 | une seule ligne, sans caractère de contrôle, sans secret | — | `strip()` |
| `body` | Oui | 100 000 | Markdown, sans secret ; le HTML brut est échappé | — | — |
| destinataire | — | — | **jamais un paramètre** : `resolveRecipient(userId, workspace.hostId)` | — | — |
| limite | — | 50 / compte / 24 h glissantes | lignes `AGENT` du compte | — | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/client-emails/{emailId}` | JWT | droit Forge ou Vigie (ADMIN d'office) |
| SSE | événement `email` du flux du terminal (`{ toolUseId, email }`) | JWT | droit du terminal |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `client_emails` (nouvelle) | INSERT / SELECT / UPDATE | file et journal ; FK `ON DELETE CASCADE` vers `users` et `runner_hosts` |
| `host_mail_addresses` | SELECT | via `resolveRecipient` |
| `atelier_messages.terminal_json` | UPDATE (existant) | le bloc porte le champ additif `email` |

### Migration Liquibase

- [x] Oui — `102-client-emails.xml` (premier numéro libre au-dessus du dernier sur `main` après rebase : 101 a été pris par F-111).

### Composants

| Composant | Rôle |
|-----------|------|
| `mail/ClientMailTool` | garde, définition (destinataire nommé), exécution : validation, secrets, limite, résolution, mise en file, reçu |
| `mail/ClientMailSecrets` | détection des secrets manifestes (pur) |
| `mail/ClientMailRenderer` | Markdown → HTML sobre + texte (commonmark, HTML brut échappé) |
| `mail/ClientEmail` (+ repository) | file et journal |
| `mail/ClientMailOutbox` + `ClientMailWorker` | prise sous bail, envoi, classement des échecs, reprise, effacement du corps |
| `mail/ClientEmailController` | `GET /client-emails/{id}` |
| `email/EmailService.sendClientMail` (+ `Smtp`, `Logging`) | envoi `multipart/alternative`, nom affiché |
| `atelier/AtelierChatService` | outil ajouté à `buildTools`, exécution dans la gateway, bloc `email` dans la transcription, `listener.onEmail` |
| `atelier/AtelierTurnReport.Block` | champ additif `email` |
| `atelier/AtelierChatController` | événement SSE `email` |
| Angular : `atelier.models.ts`, `atelier.service.ts`, `atelier.component.ts`, `atelier-terminal.component.html`, `terminal/terminal-email.component.ts`, `terminal/terminal-email.ts`, `MailService.email` | le bloc « Courriel envoyé » et son état |
| `pom.xml` | dépendance `org.commonmark:commonmark` (+ `commonmark-ext-gfm-tables`) |

---

## Plan de test

### Tests unitaires

- [ ] `ClientMailSecretsTest` — chaque nature de secret détectée ; textes ordinaires (dont « mot de passe oublié ») non détectés.
- [ ] `ClientMailRendererTest` — titres, listes, gras, code, tableau ; `<script>` échappé ; lien `javascript:` neutralisé ; version texte ; pied « envoyé pour <client> ».
- [ ] `ClientMailToolTest` — garde (sans poste, sans droit, ADMIN) ; schéma sans destinataire ; description avec destinataire / repli ; `to` ignoré ; objet / corps invalides ; secret ; limite 50 ; mise en file et reçu.
- [ ] `ClientMailOutboxTest` — accepté → `SENT` et corps effacé ; refus définitif → `FAILED` sans reprise ; passager → reprise datée, puis `FAILED` au 5ᵉ ; ligne déjà prise par un autre pod ignorée.
- [ ] `SmtpEmailServiceTest` — `sendClientMail` : `multipart/alternative`, nom affiché, destinataire, objet.
- [ ] `AtelierChatServiceEmailToolTest` — l'outil est donné / pas donné ; l'appel met en file, pose le bloc `email` dans la transcription et relaie `onEmail`.
- [ ] `terminal-email.spec.ts` / `terminal-email.component.spec.ts` — phrases d'état, repli, relecture tant que l'état n'est pas final, arrêt à l'état final.
- [ ] `atelier.service.spec.ts` (étendu) — l'événement `email` est relayé.

### Tests d'intégration

- [ ] `ClientEmailApiIntegrationTest` — mise en file par `ClientMailTool` puis passage du travailleur (`EmailService` espion) → `GET /client-emails/{id}` rend `SENT` ; corps effacé en base ; 404 pour autrui ; 403 sans droit ; limite quotidienne sur base réelle.

### Isolation utilisateur

- [x] Applicable — un utilisateur ne lit pas l'état d'un courriel d'autrui (404) ; la mise en file part du `userId` du tour et du `hostId` du workspace possédé.

---

## Dépendances

### Subfeatures bloquantes

- SF-110-01 — `done` (PR #562).

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Plans / limites : oui** — nouveau gate de l'outil : `SpaceEntitlementService.isEntitled(userId, FORGE)` **ou**
  `VIGIE` (ADMIN via `AdministratorEntitlement`), lu dans `ClientMailTool.isOpenFor` ; `GET /client-emails/{id}`
  gardé par `AtelierAccessService.requireRunnerAccess`. Nouvelle limite : 50 courriels / compte / 24 h, lue
  seulement par `ClientMailTool`. Aucun quota de jetons lu ni modifié (le coût est celui du tour). Les gates
  existants (Forge, Vigie, Teams, Radar) ne changent pas.
- **Contexte tenant : oui** — le destinataire est résolu par `HostMailAddressService.resolveRecipient(userId,
  hostId)` (SF-110-01) depuis le workspace du tour, déjà vérifié possédé par `WorkspaceService.requireOwned` ;
  `ClientEmailRepository` filtre `user_id` ; le travailleur lit la ligne sans contexte de sécurité et n'utilise que
  ce qu'elle porte.
- **Sécurité : oui** — destinataire jamais choisi par le modèle, adresse vérifiée ou compte, refus des secrets,
  limite d'envoi, HTML échappé. Composants : `SmtpEmailService`, catalogue d'outils (`buildTools`), journal.
- **Auth / Principal : non.** **Navigation / routing : non.**

---

## Notes et décisions

- **Dépendance commonmark** (variante signalée) : aucun rendu Markdown n'existe côté backend ; écrire un analyseur
  maison serait plus risqué qu'une bibliothèque éprouvée, sans dépendance transitive, qui échappe le HTML brut
  (`escapeHtml`) et assainit les liens (`sanitizeUrls`).
- **Limite sur 24 heures glissantes** plutôt que jour civil : pas de fuseau à choisir, et une boucle de l'agent à
  23 h 59 ne gagne pas 50 envois de plus à minuit.
- **Le destinataire est écrit dans la description de l'outil** : c'est ce qui permet à l'agent de dire le repli
  **avant** d'envoyer, sans confirmation par envoi.
- **État « accepté par le relais »** : c'est tout ce que SMTP permet de savoir ; une quarantaine côté client n'est
  pas visible, et le bloc le dit.
