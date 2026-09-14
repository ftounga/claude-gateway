# Mini-spec — F-110 / SF-110-05 — Courriel : pièces orphelines et secrets dans les documents

> Base : `docs/features/RELIQUATS-2026-09-14.md` §B (SF-110-05, cadrage validé par le PO, non rediscuté ici) et
> `docs/features/F-110/CADRAGE-F-110-le-courriel-du-client.md` §4 (« pas de secret dans un courriel »). S'appuie sur
> SF-110-03 (les pièces jointes, la file `client_emails`, le stockage `client-emails/{userId}/{emailId}/`) et
> réutilise `DocxTextExtractor` (F-86 / SF-86-01).

## Identifiant

`F-110 / SF-110-05`

## Feature parente

`F-110` — Le courriel du client : l'application m'envoie des courriels

## Statut

`in-progress`

## Date de création

2026-09-14

## Branche Git

`feat/SF-110-05-pieces-orphelines-secrets-doc`

---

## Objectif

Fermer deux trous laissés par SF-110-03 : les pièces jointes écrites dans le stockage mais jamais rattachées à un
envoi (nettoyage périodique), et les secrets cachés dans le **texte** d'un document joint (PDF, `.docx`, `.xlsx`),
que l'inspection « nom seul » des binaires ne voyait pas.

---

## Comportement attendu

### Cas nominal — 1. Extraction du texte des documents pour la détection de secrets

1. À la collecte des pièces (`ClientMailAttachments.collect`, appelée par l'outil `email_me` **avant** toute mise
   en file), une pièce **binaire** qui **est** un document lisible — décidé sur le **contenu**, jamais sur le nom —
   voit son **texte** extrait et passé à `ClientMailSecrets` :
   - **`.docx`** (archive OOXML portant `word/document.xml`) — texte lu par `DocxTextExtractor` (déjà durci :
     bornes zip-bomb, refus DTD/XXE) ;
   - **`.xlsx`** (archive OOXML portant `xl/workbook.xml`) — texte des chaînes partagées (`xl/sharedStrings.xml`)
     et des chaînes en ligne des feuilles (`xl/worksheets/*.xml`), lu avec les mêmes garde-fous (zip borné, XML
     durci sans DTD) ;
   - **PDF** (`%PDF-` en tête) — texte des flux de contenu (opérateurs `Tj`/`TJ`, chaînes littérales `( )` et
     hexadécimales `< >`), flux `FlateDecode` décompressés sous borne. Effort au mieux : un PDF **chiffré**, une
     police **CID/Type0** (glyphes sans texte lisible) ou un PDF **scanné** (image sans couche texte) ne rendent
     rien d'exploitable — limite assumée et dite (comme l'était « binaire non inspecté »).
2. Un secret manifeste trouvé dans ce texte **refuse le courriel**, avec la phrase existante qui **nomme la pièce
   et la nature** du secret, **jamais sa valeur**, et **rien n'est mis en file**.
3. Un document **sans** secret, ou un binaire qui **n'est pas** un de ces trois documents (image, zip quelconque,
   octets opaques), est joint comme avant : l'extraction ne change ni le contenu envoyé, ni les pièces texte
   (toujours inspectées telles quelles), ni le plafond de 10 Mo.
4. L'extraction est **synchrone et locale** (pièces ≤ 10 Mo, bornes anti zip-bomb) : elle ne sort pas de la
   machine, n'appelle aucun fournisseur, ne relève pas d'un traitement « lourd » (pas d'OCR, pas d'embeddings).
   Elle **échoue en s'ouvrant** (fail-open) : un document illisible ou dépassant une borne n'est pas inspecté et
   part comme un binaire ordinaire — l'inspection est une défense en profondeur, pas un blocage de plus.
5. Aucun texte extrait, aucune valeur de secret, aucun nom de pièce ne sont journalisés.

### Cas nominal — 2. Balayage des pièces orphelines

6. Un **balayage quotidien** (`ClientMailAttachmentSweeper`, `@Scheduled`, hors du fil HTTP, désactivable en test)
   parcourt le stockage sous `client-emails/` et, pour chaque `{userId}/{emailId}/` trouvé :
   - **aucune ligne `client_emails`** pour cet `emailId` (transaction de mise en file annulée par un plantage
     entre l'écriture des pièces et le commit) → **effacé** ;
   - ligne présente et **à l'état final** (`SENT`/`FAILED`) dont les pièces n'ont pas été effacées (échec de
     `finish`) → **effacé** ;
   - ligne présente et **en cours** (`PENDING`/`SENDING`) → **conservée** (l'envoi peut encore la lire).
7. L'effacement est **borné à la clé exacte** `client-emails/{userId}/{emailId}/` reconstruite depuis la clé
   trouvée : le balayage ne touche jamais une pièce d'un autre courriel ni d'un autre compte.
8. Le balayage journalise un compte (« n pièce(s) orpheline(s) effacée(s) »), jamais un nom de pièce ni une
   adresse ; il ne s'arrête pas sur l'échec d'un effacement (journalisé, passage suivant).

### Cas d'erreur

| Situation | Comportement attendu | Code / effet |
|-----------|---------------------|-----------|
| `.docx` joint contenant un mot de passe explicite dans le texte | refus nommant la pièce et « un mot de passe », rien en file | outil `is_error` |
| `.xlsx` joint dont une cellule porte un jeton/clé/mot de passe manifeste | refus nommant la pièce et la nature | outil `is_error` |
| PDF (texte) joint contenant une clé/jeton manifeste dans son texte | refus nommant la pièce et la nature | outil `is_error` |
| Document illisible, corrompu, dépassant une borne zip-bomb, PDF chiffré/scanné/CID | non inspecté (fail-open), joint comme un binaire | pièce jointe normale |
| Image ou binaire opaque (non docx/xlsx/pdf) | non inspecté, joint comme avant | pièce jointe normale |
| Pièces d'un `emailId` sans ligne `client_emails` | effacées au balayage suivant | stockage nettoyé |
| Pièces d'un courriel `SENT`/`FAILED` restées dans le stockage | effacées au balayage suivant | stockage nettoyé |
| Pièces d'un courriel `PENDING`/`SENDING` | conservées | stockage inchangé |
| Effacement d'un préfixe impossible (stockage indisponible) | journalisé, le balayage continue | passage non interrompu |

---

## Critères d'acceptation

- [ ] Un `.docx` joint dont le texte contient un mot de passe explicite (`mot de passe : …`, `password=…`) **n'est
  pas envoyé** : `email_me` refuse en nommant la pièce et la nature, sans jamais afficher la valeur, et rien n'est
  mis en file.
- [ ] Un `.xlsx` joint dont une cellule contient un secret manifeste est refusé de la même façon.
- [ ] Un PDF **texte** joint contenant une clé/jeton manifeste est refusé ; un PDF sans couche texte lisible
  (chiffré, scanné, CID) est joint sans blocage (limite assumée).
- [ ] Un `.docx`/`.xlsx`/PDF **sans** secret est joint intact ; le contenu envoyé est identique à SF-110-03.
- [ ] Une image ou un binaire opaque n'est pas inspecté et part comme avant.
- [ ] Une pièce **orpheline** (objets `client-emails/{userId}/{emailId}/…` sans ligne `client_emails`) **disparaît
  au balayage suivant** ; les pièces d'un courriel `PENDING`/`SENDING` restent.
- [ ] Les pièces d'un courriel `SENT`/`FAILED` non effacées par `finish` sont effacées au balayage.
- [ ] Isolation : chaque effacement porte sur la clé exacte `{userId}/{emailId}/` reconstruite depuis la clé du
  stockage ; aucune pièce d'un autre compte ou d'un autre courriel n'est touchée.
- [ ] Le journal applicatif ne porte ni texte extrait, ni valeur de secret, ni nom de pièce, ni adresse.

---

## Périmètre

### Hors scope (explicite)

- Toute inspection des **images** (pas d'OCR d'une pièce : ce serait un traitement lourd et asynchrone, hors de la
  fenêtre d'un envoi ; règle Provider-First et règle async de `CLAUDE.md`).
- Les PDF **chiffrés**, **scannés** (image sans texte) ou en police **CID/Type0** : leur texte n'est pas
  extractible sans bibliothèque ni OCR — limite assumée, l'inspection reste un filet, pas une garantie.
- Le corps du courriel (déjà couvert par `ClientMailSecrets` en SF-110-02) et les conteneurs de secrets par leur
  nom (déjà couverts en SF-110-03).
- Tout changement d'écran, d'API HTTP, de schéma de base, de contrat runner.
- La détection d'un secret **après** l'envoi (le filtre est au moment de la mise en file).

---

## Valeurs initiales

Aucune entité ni colonne nouvelle. Les pièces vivent déjà dans le stockage objet (SF-110-03) ; le balayage ne fait
qu'**effacer** des clés existantes. `attachment_count`, `size_bytes` de `client_emails` inchangés.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| texte extrait d'un document | — | borné par les garde-fous zip-bomb / borne PDF | inspecté par `ClientMailSecrets` | — | non conservé |
| préfixe balayé | — | — | `client-emails/{UUID}/{UUID}/` reconstruit depuis la clé | — | segments non-UUID ignorés |

---

## Technique

### Endpoint(s)

Aucun. Aucun endpoint, aucun contrat runner, aucun schéma nouveau.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `client_emails` | SELECT (`findById`, existant) | le balayage lit l'état pour décider d'effacer |

### Migration Liquibase

- [x] Non applicable — stockage objet existant, table existante, aucune colonne.

### Composants

| Composant | Rôle |
|-----------|------|
| `mail/DocumentSecretScanner` (nouveau) | détecte docx/xlsx/pdf sur le contenu, extrait le texte (bornes zip-bomb, XML durci, inflate PDF borné), rend la nature d'un secret manifeste ou vide ; fail-open |
| `docx/DocxTextExtractor` (existant) | réutilisé pour le texte d'un `.docx` |
| `mail/ClientMailAttachments` | ajoute l'inspection documentaire après l'inspection texte, pour une pièce binaire ; refus existant réutilisé |
| `mail/ClientMailAttachmentStore` | nouvelle lecture `listStored()` (couples `{userId, emailId}` présents dans le stockage) ; `delete` existant réutilisé |
| `mail/ClientMailOutbox` | nouveau `sweepOrphans()` : croise le stockage et `client_emails`, efface les orphelins et les finaux restants |
| `mail/ClientMailAttachmentSweeper` (nouveau) | `@Scheduled` quotidien, hors HTTP, `@ConditionalOnProperty` (désactivable en test), délègue à `sweepOrphans()` |

---

## Plan de test

### Tests unitaires

- [ ] `DocumentSecretScannerTest` — `.docx` (via `DocxFixtures`) avec un mot de passe dans un paragraphe → nature
  « un mot de passe » ; `.docx` sans secret → vide ; `.xlsx` (chaîne partagée) avec un secret → nature ; `.xlsx`
  sans secret → vide ; PDF texte (littéral et hex, flux FlateDecode) avec une clé → nature ; PDF sans texte /
  binaire opaque / image → vide (fail-open) ; document corrompu / au-delà d'une borne → vide sans lever.
- [ ] `ClientMailAttachmentsTest` (étendu) — une pièce `.docx` binaire dont le texte porte un secret est refusée en
  nommant la pièce et la nature, sans la valeur, rien en file ; un `.docx` sans secret est joint intact ; une image
  binaire n'est pas inspectée.
- [ ] `ClientMailAttachmentStoreTest` (étendu) — `listStored()` rend les couples `{userId, emailId}` distincts,
  ignore les clés malformées, isole par compte.
- [ ] `ClientMailOutboxTest` (étendu) — `sweepOrphans()` : efface un `emailId` sans ligne ; efface un `SENT`/
  `FAILED` restant ; conserve un `PENDING`/`SENDING` ; un échec d'effacement n'interrompt pas le passage ; rend le
  compte effacé.

### Tests d'intégration

- [ ] `ClientEmailApiIntegrationTest` (étendu) — sur base réelle + stockage mémoire : des objets écrits sous un
  `emailId` sans ligne `client_emails` disparaissent après `sweepOrphans()` ; les pièces d'un courriel `PENDING`
  restent.

### Isolation utilisateur

- [x] Applicable — chaque effacement du balayage porte sur la clé exacte `{userId}/{emailId}/` reconstruite depuis
  la clé du stockage, jamais depuis une donnée du modèle ; l'inspection documentaire ne lit que les octets de la
  pièce déjà collectée pour le poste possédé.

---

## Dépendances

### Subfeatures bloquantes

- SF-110-03 — `done` (PR #577). F-86 / `DocxTextExtractor` — livré.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Sécurité : oui** — élargit le refus des secrets au **texte** des documents joints (docx/xlsx/pdf) et nettoie le
  stockage des pièces orphelines. Composants impactés : `DocumentSecretScanner` (nouveau), `ClientMailAttachments`
  (inspection ajoutée), `ClientMailSecrets` (réutilisé, inchangé), `ClientMailAttachmentStore` (`listStored`),
  `ClientMailOutbox` (`sweepOrphans`), `ClientMailAttachmentSweeper` (nouveau planificateur). Aucun autre point
  d'inspection de secrets n'est modifié.
- **Contexte tenant : oui** — le balayage est un traitement de fond de la file (comme `ClientMailWorker`, sans
  contexte utilisateur) ; ses **effacements** sont scopés à la clé `client-emails/{userId}/{emailId}/` reconstruite
  depuis le stockage. Composants qui résolvent le tenant des pièces : `ClientMailAttachmentStore` (préfixe par
  `userId`+`emailId`) — inchangé dans sa forme. Aucun composant résolvant le tenant à partir d'une requête
  utilisateur n'est touché.
- **Plans / limites : non** — aucune garde d'outil, aucun quota, aucun plafond touché (le plafond 10 Mo de
  SF-110-03 est inchangé).
- **Auth / Principal : non. Navigation / routing : non.**

---

## Notes et décisions

- **Pourquoi extraire le texte plutôt que réimplémenter un « moteur documentaire »** : on ne fait pas de Q&A ni
  d'indexation (ce serait le pipeline F-05→08, asynchrone). On lit le texte **uniquement** pour y chercher un
  secret manifeste au moment de l'envoi — une garde de sécurité, locale et bornée, dans l'esprit du refus déjà en
  place pour les pièces texte. Provider-First reste respecté : aucun appel modèle.
- **`.docx` réutilise `DocxTextExtractor`** plutôt qu'une seconde lecture : un seul endroit durci (zip-bomb, XXE)
  pour les deux usages (bibliothèque documentaire et pièce de courriel).
- **PDF au mieux, sans bibliothèque** : cohérent avec la doctrine de `DocxTextExtractor` (« aucune bibliothèque ;
  les garde-fous sont écrits, pas cachés dans des réglages »). Un extracteur littéral+hex sur flux `FlateDecode`
  attrape le cas visé (un mot de passe tapé en clair dans un PDF à police standard) ; le reste est une limite dite.
- **Fail-open assumé** : l'inspection ne doit jamais bloquer un document légitime qu'on ne sait pas lire. Le refus
  d'un document non lu resterait un faux positif ; la consigne de l'outil (« pas de secret dans un courriel ») et
  le contrôle du nom restent les premières lignes.
- **Balayage : la ligne est la vérité** : une transaction de mise en file qui commit laisse toujours sa ligne ;
  une ligne absente signe une transaction annulée → pièces orphelines. La fenêtre où une ligne n'est pas encore
  committée pendant que ses objets sont écrits est celle d'**une** transaction (millisecondes) ; la cadence
  quotidienne rend une collision négligeable, et le prochain passage rattraperait de toute façon un faux effacement
  (rien de définitif n'est perdu côté envoi : un courriel dont les pièces manquent devient `FAILED` proprement,
  SF-110-03).
- **Aucune migration** — SF-110-04 a pris `103`, et `104/105/106` sont déjà sur `main` ; cette sous-feature n'en
  crée pas.
</content>
</invoke>
