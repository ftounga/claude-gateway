# Mini-spec — F-110 / SF-110-03 — Les pièces jointes

> Base : `docs/features/F-110/CADRAGE-F-110-le-courriel-du-client.md` §4 et §8 (cadrage validé par le PO, non
> rediscuté ici). S'appuie sur SF-110-02 (l'outil `email_me` et la file `client_emails`), F-109 (les pages) et
> SF-99-05 (l'export Markdown du Radar).

## Identifiant

`F-110 / SF-110-03`

## Feature parente

`F-110` — Le courriel du client : l'application m'envoie des courriels

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-110-03-pieces-jointes`

---

## Objectif

Permettre à l'outil `email_me` de joindre au courriel un fichier du poste (lu par le runner), une page de F-109
(fichier HTML joint et lien privé) ou l'export Markdown du Radar du client, dans la limite de 10 Mo au total, en
refusant les secrets, et en proposant un lien au-delà du plafond.

---

## Comportement attendu

### Cas nominal

1. **Le schéma** de `email_me` gagne un champ facultatif `attachments` : une liste (10 au plus) d'objets portant
   **exactement une** source :
   - `path` — un fichier **du poste** du terminal (chemin relatif au projet, absolu ou `~/…`, comme `read_file`) ;
   - `page_id` — une **page publiée** (F-109) **de ce client** (même poste) ;
   - `radar_export: true` — l'**export Markdown du Radar** de ce client (au plus une fois).
   Plus deux options : `name` (nom du fichier joint, facultatif) et, pour une page, `link_only: true` (le lien
   privé seul, sans fichier). Toujours **aucun champ destinataire**.
2. **Fichier du poste** — la gateway le lit **par le runner**, en binaire, par tranches (nouvel outil runner
   `read_file_bytes` : `path`, `offset`, `length` → contenu en Base64, taille totale du fichier). Le runner refuse
   un fichier de plus de 10 Mo (`too_large`). La lecture est **tracée** dans le journal du runner (une ligne par
   fichier, outil `email_me`, chemin). Un runner **antérieur** qui ne connaît pas l'outil (`unsupported_tool`) :
   un fichier **texte** (extension de texte connue) est lu par `read_file` s'il tient en 512 Kio ; un fichier
   binaire est refusé avec « mettez à jour le runner de ce poste ».
3. **Page F-109** — la version courante de la page du compte **au même poste** est jointe en
   `<titre-en-slug>.html`, et une section **« Pages »** est ajoutée au bas du corps avec le **lien privé**
   `{app.frontend-url}/pages/{id}` (« connexion requise »). Avec `link_only`, seul le lien part. Les ressources
   annexes de la page ne sont pas jointes : le lien privé la montre entière.
4. **Export du Radar** — le document de `RadarExportService.export` (celui du bouton d'export), sous son nom
   `radar-<poste>-<date>.md`, `text/markdown`.
5. **Règles de contenu, appliquées aux pièces** :
   - un fichier **nommé comme un conteneur de secrets** est refusé quel que soit son contenu (`.env`, `.env.*`,
     `id_rsa`/`id_dsa`/`id_ecdsa`/`id_ed25519`, `.npmrc`, `.netrc`, `.pgpass`, `.git-credentials`, `credentials`,
     `kubeconfig`, extensions `pem`, `key`, `p12`, `pfx`, `jks`, `keystore`, `kdbx`, `ppk`) — contrôle fait sur
     le nom joint **et** sur le nom du fichier lu ;
   - une pièce **texte** (UTF-8 valide, sans octet nul) passe par `ClientMailSecrets` : un secret manifeste la
     fait refuser, la phrase nomme la pièce et la nature du secret, jamais sa valeur ;
   - une pièce **binaire** (PDF, docx…) n'est pas inspectée au-delà de son nom (limite assumée, dite ici).
6. **Plafond : 10 Mo au total** (10 × 1 048 576 octets) — pièces **et** corps rendus. Au-delà, **rien n'est mis
   en file** et le résultat demande à l'agent de **proposer un lien** : `link_only` pour une page, ou publier le
   document en page, ou dire où trouver le fichier sur le poste. La lecture s'arrête dès que la taille annoncée
   par le runner fait dépasser le plafond (aucune tranche inutile).
7. **La file** — les pièces sont rangées dans le **stockage objet** existant (`WorkspaceStorage`) sous
   `client-emails/{userId}/{emailId}/{nn}/{nom encodé}`, dans la **même transaction** que la ligne
   `client_emails` (`attachment_count`, `size_bytes` pièces comprises). Le travailleur les relit et envoie un
   message `multipart/mixed` (texte + HTML, puis les pièces, noms encodés). **À l'état final (`SENT` ou
   `FAILED`), les pièces sont effacées** avec les corps. Une pièce introuvable au moment d'envoyer → `FAILED`
   « pièce jointe introuvable », sans reprise.
8. **Le reçu et le bloc** — `attachmentCount` du reçu vaut le nombre de fichiers joints ; le bloc « Courriel envoyé »
   (déjà livré) affiche « — 2 pièces jointes ». Le résultat rendu au modèle nomme les pièces et le lien de page
   inclus.
9. **Suppression du compte** — les pièces en attente d'un compte supprimé sont effacées du stockage.

### Cas d'erreur

| Situation | Comportement attendu | Code / effet |
|-----------|---------------------|-----------|
| `attachments` n'est pas une liste, ou plus de 10 éléments | refus, rien en file | outil `is_error` |
| Élément sans source, ou avec plusieurs sources | refus nommant l'élément | outil `is_error` |
| `radar_export` demandé deux fois ; deux pièces de même nom | refus | outil `is_error` |
| `name` invalide (vide, > 100 caractères, séparateur de chemin, caractère de contrôle) | refus | outil `is_error` |
| Fichier du poste introuvable / illisible / poste hors ligne | refus avec le motif du runner | outil `is_error` |
| Fichier binaire sur un runner qui ne connaît pas `read_file_bytes` | refus « mettez à jour le runner » | outil `is_error` |
| Fichier modifié pendant la lecture (taille qui change) | refus « le fichier a changé pendant la lecture » | outil `is_error` |
| `page_id` inconnu, d'autrui, ou d'un autre poste | refus « page inconnue pour ce client » (indiscernables) | outil `is_error` |
| Radar du client vide | refus « rien à joindre » | outil `is_error` |
| Nom de conteneur de secrets, ou secret manifeste dans une pièce texte | refus nommant la pièce et la nature | outil `is_error` |
| Total > 10 Mo | refus + consigne de proposer un lien | outil `is_error` |
| Écriture des pièces impossible dans le stockage | refus, ligne annulée (transaction), pièces effacées | outil `is_error` |
| Pièce disparue du stockage à l'envoi | `FAILED` « pièce jointe introuvable » sans reprise | état du bloc |

---

## Critères d'acceptation

- [ ] `email_me` accepte `attachments` (fichier du poste, page, export du Radar) et le schéma n'a toujours aucun champ destinataire.
- [ ] Un fichier binaire du poste (ex. PDF) arrive intact (octet pour octet) dans le courriel envoyé, lu par tranches via `read_file_bytes`, et la lecture est tracée dans le journal du runner.
- [ ] Un runner sans `read_file_bytes` joint encore un fichier texte ≤ 512 Kio ; un binaire est refusé avec « mettez à jour le runner ».
- [ ] Une page du même poste est jointe en `.html` avec son lien privé `/pages/{id}` dans le corps ; `link_only` n'envoie que le lien ; une page d'autrui ou d'un autre poste est refusée.
- [ ] L'export Markdown du Radar du client est joint sous `radar-<poste>-<date>.md`.
- [ ] Au-delà de 10 Mo au total, rien n'est mis en file et le résultat demande de proposer un lien.
- [ ] Une pièce texte contenant un secret manifeste, ou un fichier nommé comme un conteneur de secrets, est refusé sans rien mettre en file.
- [ ] Le travailleur envoie texte + HTML + pièces ; les pièces sont effacées du stockage à l'état final ; une pièce manquante → `FAILED` sans reprise.
- [ ] Le reçu porte le nombre de pièces ; le journal applicatif ne porte ni nom de pièce, ni contenu, ni adresse.
- [ ] Isolation : page filtrée par `user_id` **et** `host_id` du terminal ; Radar filtré par `RadarScope(userId, hostId)` ; clés de stockage reconstruites depuis `userId` et l'identifiant de la ligne.

---

## Périmètre

### Hors scope (explicite)

- Un **lien de téléchargement** d'un fichier du poste (aucun partage de fichier n'existe) : au-delà du plafond, l'agent propose une page ou l'emplacement du fichier.
- L'inspection du contenu des fichiers **binaires** (PDF, docx, xlsx) à la recherche de secrets.
- Les ressources annexes d'une page (css/js joints en F-109) et le choix d'une version antérieure de la page.
- Une pièce jointe au **résumé du matin** (SF-110-04).
- Tout changement d'écran : le bloc « Courriel envoyé » affiche déjà le nombre de pièces (SF-110-02).
- Le relèvement du niveau de contrat du runner (voir Notes).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `attachment_count` | nombre de fichiers joints | les liens seuls (`link_only`) ne comptent pas |
| `size_bytes` | corps rendus + pièces | ce que le journal retient |
| objets `client-emails/{userId}/{emailId}/…` | écrits à la mise en file | effacés à l'état final |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `attachments` | Non | 10 éléments | liste d'objets | — | — |
| élément | — | — | exactement une source parmi `path`, `page_id`, `radar_export: true` | `radar_export` une fois | — |
| `path` | si source | 4 096 | chemin du poste (normalisé par `RunnerToolGateway`) | — | `strip()` |
| `page_id` | si source | — | UUID d'une page du compte au poste du terminal | — | `strip()` |
| `name` | Non | 100 | pas de `/`, `\`, caractère de contrôle ; pas vide ; défaut : nom du fichier, `<slug-titre>.html`, nom de l'export | noms uniques dans le courriel | `strip()` |
| total | — | 10 Mo (10 485 760 octets) | pièces + corps texte + HTML | — | — |
| tranche runner | — | 360 Kio lus (≈ 480 Kio en Base64, sous la trame de 1 Mio) | `offset` ≥ 0, `length` 1…393 216 | — | — |

---

## Technique

### Endpoint(s)

Aucun endpoint nouveau. Contrat runner : nouvel outil `read_file_bytes` dans la trame `tool_call` existante.

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| — | outil runner `read_file_bytes` `{ path, offset, length }` → `content` Base64, `bytes` = taille totale, `truncated` = il reste des octets | jeton runner | — |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `client_emails` | INSERT / UPDATE (existant) | `attachment_count` et `size_bytes` renseignés ; aucune colonne nouvelle |
| `pages` / `page_versions` | SELECT | via `PageService.html(userId, pageId, null)` + contrôle du poste |
| tables du Radar | SELECT | via `RadarExportService.export(RadarScope)` |
| `runner_audit` | INSERT (existant) | une ligne par fichier lu sur le poste |

### Migration Liquibase

- [x] Non — les pièces vivent dans le stockage objet existant ; `attachment_count` existe depuis `102`.

### Composants

| Composant | Rôle |
|-----------|------|
| `mail/ClientMailAttachments` (nouveau) | valide la liste, lit chaque source (runner, page, Radar), contrôle noms / secrets / plafond, rend les pièces et les liens |
| `mail/ClientMailAttachmentStore` (nouveau) | écriture, relecture et effacement des pièces sous `client-emails/{userId}/{emailId}/` |
| `mail/ClientMailTool` | schéma `attachments`, appel de `ClientMailAttachments`, section « Pages » ajoutée au corps, reçu et résultat |
| `mail/ClientMailOutbox` | mise en file transactionnelle avec pièces, relecture à l'envoi, effacement à l'état final, pièce manquante → `FAILED` |
| `email/ClientMailMessage` (+ `Attachment`) | pièces du message |
| `email/SmtpEmailService`, `LoggingEmailService` | `addAttachment` (noms encodés) ; journal : nombre seulement |
| `runner/exec/RunnerToolGateway` | `readFileBytes(target, callId, path, offset, length)` |
| `account/AccountService` | effacement des pièces en attente à la suppression du compte |
| runner `FileTools` | outil `read_file_bytes` |

---

## Plan de test

### Tests unitaires

- [ ] `ClientMailAttachmentsTest` — fichier binaire lu en plusieurs tranches et recomposé ; audit une ligne ; repli `read_file` pour un texte sur runner ancien ; binaire refusé sur runner ancien ; taille qui change ; poste hors ligne ; page jointe + lien, `link_only`, page d'un autre poste refusée ; export du Radar ; Radar vide ; nom de conteneur de secrets (nom joint et chemin) ; secret dans une pièce texte ; binaire non inspecté ; plafond de 10 Mo dépassé dès la taille annoncée (aucune tranche de plus) ; élément sans source / plusieurs sources ; plus de 10 ; noms en double ; `name` invalide.
- [ ] `ClientMailToolTest` (étendu) — schéma `attachments` sans destinataire ; les pièces passent à la file ; section « Pages » dans le corps ; refus des pièces → rien en file ; reçu avec le nombre de pièces.
- [ ] `ClientMailOutboxTest` (étendu) — mise en file avec pièces : rangées, taille comptée ; envoi avec pièces puis effacement ; pièce manquante → `FAILED` sans reprise ; échec passager : pièces conservées.
- [ ] `ClientMailAttachmentStoreTest` — aller-retour d'un nom accentué, ordre conservé, effacement, isolation de préfixe par compte.
- [ ] `SmtpEmailServiceTest` (étendu) — `multipart/mixed` avec texte, HTML et pièce au nom accentué.
- [ ] Runner `FileToolsTest` (étendu) — `read_file_bytes` : tranches, Base64 exact, `bytes` = taille totale, `truncated`, fichier > 10 Mo refusé, `offset` au-delà de la fin, `length` hors bornes, dossier refusé.

### Tests d'intégration

- [ ] `ClientEmailApiIntegrationTest` (étendu) — sur base réelle et stockage mémoire : page F-109 publiée au poste jointe et lien privé dans le corps, passage du travailleur (`EmailService` espion) → pièce reçue, `attachment_count` = 1, objets du stockage effacés ; page d'un autre compte refusée.

### Isolation utilisateur

- [x] Applicable — une page d'autrui ou d'un autre poste est indiscernable d'une page inconnue ; le Radar et les fichiers ne sont lus que pour le poste du terminal possédé ; les clés de stockage sont reconstruites depuis `userId` et l'identifiant de la ligne, jamais depuis une donnée du modèle.

---

## Dépendances

### Subfeatures bloquantes

- SF-110-02 — `done` (PR #565). F-109 — `Terminée` (SF-109-01 à 05). SF-99-05 — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui** — composants : `ClientMailAttachments` (page par `PageService.html(userId, …)` **puis**
  contrôle `page.hostId == workspace.hostId` ; Radar par `RadarScope(userId, workspace.hostId)` ; fichiers par
  `RunnerTargets.of(workspace)`, workspace déjà vérifié possédé), `ClientMailAttachmentStore` (clé
  `client-emails/{userId}/{emailId}/`), `ClientMailOutbox` (relit la ligne puis son préfixe). Aucun autre composant
  résolvant le tenant n'est modifié.
- **Plans / limites : oui** — nouveau plafond 10 Mo par courriel, lu seulement par `ClientMailAttachments`. La garde
  de l'outil (`ClientMailTool.isOpenFor` : Forge ou Vigie, ADMIN d'office via `AdministratorEntitlement`) et la
  limite de 50 courriels / 24 h sont **inchangées** ; l'export du Radar suit la règle de l'export existant
  (possession du poste). Aucun quota de jetons touché.
- **Sécurité : oui** — lecture de fichiers de la machine sans confirmation (comme `read_file`, tracée) ; refus des
  conteneurs de secrets et des secrets manifestes dans les pièces texte ; composants : `ClientMailAttachments`,
  runner `FileTools`, `RunnerToolGateway`, journal du runner.
- **Auth / Principal : non.** **Navigation / routing : non.**

---

## Notes et décisions

- **Lecture binaire par tranches** : `read_file` rend du texte UTF-8 borné à 512 Kio — un PDF y serait corrompu.
  Une trame fait 1 Mio au plus (contrat §5) : 360 Kio lus deviennent ≈ 480 Kio en Base64. 10 Mo = 29 appels au plus,
  chacun sous le délai des outils fichiers (30 s).
- **Pas de relèvement du contrat runner** (`RunnerBuild.CONTRACT` reste 2) : le niveau de contrat dit les *trames*
  que la gateway peut envoyer (`update`) ; un nouvel outil voyage dans la trame `tool_call` existante, et un runner
  ancien répond `unsupported_tool`, que la gateway traite (repli texte ou « mettez à jour »).
- **Pièces dans le stockage objet, pas en base** : 10 Mo en `bytea`/`varchar` alourdiraient la file que le
  travailleur balaie ; le stockage existant (S3 en cluster, mémoire en test) sert déjà les pages. Mise en file et
  écriture des pièces dans une transaction : un échec d'écriture annule la ligne.
- **Binaire non inspecté** : chercher un secret dans un PDF ou un docx demanderait d'en extraire le texte ; le nom
  est contrôlé, et la consigne de l'outil interdit d'y mettre un secret.
