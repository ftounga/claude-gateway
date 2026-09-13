# Mini-spec — F-108 / SF-108-04 — Écrire les fichiers

> Base : `docs/features/F-108/CADRAGE-F-108-agir-dans-microsoft-365.md` §4 (gardes NON négociables,
> dont §4.4 « chaque écriture est confirmée »), §5.1 et §6. **Arbitrage du PO du 2026-09-13** :
> API REST SharePoint appelée depuis la page (`_api/contextinfo` pour le digest, `Files/add`,
> `folders`, `MoveTo`, `recycle`), aucun sélecteur DOM, aucun jeton/cookie/digest ne remonte ;
> modifier = télécharger → modifier sur la machine → redéposer comme nouvelle version ; chaque
> écriture confirmée par le mécanisme de SF-108-02. S'appuie sur SF-108-03 (`SharePointPage`,
> `SharePointProjection`, `PageActions`).

## Identifiant

`F-108 / SF-108-04`

## Feature parente

`F-108` — Agir dans Microsoft 365 : fichiers, enregistrements, gestes

## Statut

`done` — PR #520

## Date de création

2026-09-13

## Branche Git

`feat/SF-108-04-ecrire-les-fichiers`

---

## Objectif

Implémenter côté runner les six écritures déjà classées et confirmées par la gateway (SF-108-02) —
créer un dossier, déposer, renommer, déplacer, supprimer (corbeille), remplacer une version — par
l'API REST SharePoint appelée depuis la page, et rendre à l'utilisateur, **avant** son accord, un
libellé qui nomme l'action, l'emplacement **et le fichier local** concerné.

---

## Comportement attendu

### Cas nominal

1. **Confirmation d'abord (gateway, SF-108-02)** : l'outil n'est émis qu'après accord. Le libellé
   devient **propre à chaque écriture** et lisible :
   - « Créer le dossier « Livrables » dans ProjetIAM › Shared Documents › General »
   - « Déposer le fichier local « /home/u/rapport.docx » sous le nom « rapport.docx » dans … »
   - « Renommer « plan.docx » en « plan-v2.docx » dans … »
   - « Déplacer « plan.docx » vers … »
   - « Supprimer « vieux.docx » dans … (corbeille du site) »
   - « Remplacer la version de « plan.docx » dans … par le fichier local « /home/u/plan.docx » »
   Les adresses web sont traduites en « site › bibliothèque › dossier » ; un emplacement déjà en
   clair est gardé tel quel.
2. **Exécution (runner, `TeamsWriteTools`)** : l'onglet est amené sur le site
   (`SharePointPage`), chaque appel d'écriture obtient son digest par `POST /_api/contextinfo`
   **dans la page** (jamais rendu), puis :
   - `teams_create_folder` (`location`, `name`) : existence vérifiée, puis `POST /_api/web/folders` ;
   - `teams_upload_file` (`file` local absolu, `location`, `name` facultatif) : existence vérifiée
     (refus si le fichier existe : c'est un remplacement) ; un champ de dépôt **créé par le script**
     reçoit le fichier par `DOM.setFileInputFiles` (Chrome lit le disque, les octets ne passent pas
     par la liaison) ; `Files/AddUsingPath(DecodedUrl=…,Overwrite=false)` ; taille vérifiée ;
   - `teams_rename` (`target`, `name`) et `teams_move` (`target`, `destination`, même site) :
     nature (fichier / dossier) déterminée, `MoveTo(newurl=…,flags=0)` (jamais d'écrasement),
     nouvel emplacement **revérifié** ;
   - `teams_delete` (`target`) : `recycle()` — **corbeille du site**, restaurable ; l'identifiant de
     corbeille est rendu ;
   - `teams_replace_version` (`target`, `file`) : le fichier distant doit exister ;
     `AddUsingPath(…,Overwrite=true)` ; versions **avant / après** rendues — SharePoint garde
     l'historique, c'est le filet, et le résultat le dit.
3. **Modifier un document** = `teams_read_file` → modification locale → `teams_replace_version`.
4. La vue est **remise**, les gestes sont **tracés**, la **provenance** est écrite (« forme éprouvée
   sur documentation, à confirmer sur poste réel »).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Écriture refusée / sans réponse à la confirmation | Jamais émise (SF-108-02, inchangé) |
| Nom invalide (vide, `/ \ : * ? " < > \|`, `.`/`..`, > 255, espace ou point final) | `INVALID_NAME`, **aucun geste** |
| Adresse hors domaines / non SharePoint | `LOCATION_UNKNOWN`, aucun geste |
| Fichier local absent, relatif, illisible, > 250 Mo | refus nommé, **aucun geste** |
| Élément de même nom déjà présent (dossier, dépôt, renommage, déplacement) | `ALREADY_EXISTS`, rien n'est écrasé |
| Remplacement d'un fichier distant absent | `NOT_FOUND` : « c'est un dépôt, pas un remplacement » |
| Déplacement vers un autre site | refus nommé (télécharger puis déposer) |
| 401/403, 404, 423 (verrouillé / extrait) | `ACCESS_DENIED`, `NOT_FOUND`, `WRITE_FAILED` avec le message Microsoft |
| Réponse OK mais forme inattendue / nouvel emplacement introuvable | `SHAPE_MISMATCH` : « l'écriture a peut-être eu lieu, vérifiez » — jamais « c'est fait » |
| Page d'identification | `SIGNED_OUT`, aucun script |
| Poster un message | inexistant (hors périmètre) |

---

## Critères d'acceptation

- [ ] Les six outils sont au catalogue runner et gateway ; `isWrite` reste vrai pour exactement eux.
- [ ] Le libellé de confirmation nomme action, élément, emplacement lisible et, pour dépôt et
      remplacement, **le chemin du fichier local**.
- [ ] Chaque écriture obtient son digest **dans la page** : aucun digest, jeton ou cookie dans le
      résultat (test sur page non filtrée).
- [ ] Création, dépôt, renommage, déplacement, suppression (corbeille) et remplacement de version
      aboutissent sur réponses modèles et rendent un résultat vérifié.
- [ ] Élément existant → `ALREADY_EXISTS` sans écriture ; nom invalide et fichier local absent →
      refus **sans aucune commande CDP d'action**.
- [ ] Le dépôt passe par un champ créé par le script et `DOM.setFileInputFiles` — aucun octet du
      fichier dans les commandes envoyées.
- [ ] Réponse de succès non conforme → `SHAPE_MISMATCH` et texte « a peut-être eu lieu », jamais
      « c'est fait ».
- [ ] Délai gateway adapté (dépôt / remplacement plus longs).

---

## Périmètre

### Hors scope (explicite)

- Écrire **par le dossier synchronisé** : les écritures passent toujours par l'API (résultat
  vérifié côté serveur : version, corbeille, conflit) ; le dossier synchronisé reçoit le changement
  par OneDrive. Décision réversible, dite dans le résultat.
- Dépôt fragmenté au-delà de 250 Mo (`StartUpload`…).
- Déplacement entre sites (`SP.MoveCopyUtil`).
- Taper dans Word / Excel en ligne ; poster un message, répondre, réagir.

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|----------------------------|---------------|
| `name` | selon l'outil | 1–255 car., sans `/ \ : * ? " < > \|`, ni `.`/`..`, sans espace ni point final | trim |
| `location`, `destination`, `target` | selon l'outil | adresse SharePoint / OneDrive professionnel (règles SF-108-03) | requête retirée |
| `file` | dépôt, remplacement | chemin **absolu**, fichier régulier lisible, ≤ 250 Mo | normalisé |
| attente d'une écriture | — | 90 s ; dépôt / remplacement 300 s | — |

---

## Technique

### Composants runner impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `TeamsWriteTools` | créé | six écritures, validations, vérifications, rendu |
| `SharePointWrites` | créé | scripts d'écriture (digest dans la page), lecture des réponses, manques |
| `TeamsTools` | modifié | six outils au catalogue (15 → 21), délégation |
| `TeamsGapKind` | modifié | `ALREADY_EXISTS`, `WRITE_FAILED`, `INVALID_NAME` |
| `FakeCdpConnection` (test) | modifié | `DOM.getDocument` / `querySelector` / `setFileInputFiles` de papier |
| `src/test/resources/teams/sharepoint-*` | créés | réponses modèles d'écriture |

### Composants gateway impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `TeamsToolCatalog` | modifié | six outils au `CATALOG` et à `toolsFor` (descriptions : confirmation, corbeille, versions) ; `describeWrite` enrichi (nouveau nom, fichier local), `readableLocation` |
| `AtelierChatService` | modifié | `teamsWriteDetail` propre à chaque écriture ; cible d'audit (`target` avant `name`) |
| `RunnerToolGateway` | modifié | délais des écritures |
| tests | modifiés | `TeamsToolCatalogTest`, `TeamsReadingCatalogTest`, `AtelierChatServiceRunnerGuardTest`, `RunnerToolGatewayTest` |

### Endpoints / tables

- Aucun endpoint, **aucune migration** (audit runner existant).

### Préoccupations transversales

- **Sécurité (§7)** : `TeamsToolCatalog`, `AtelierChatService` (confirmation SF-108-02, inchangée
  dans son principe), `PageActions`, `SharePointPage`, `TeamsTools`. Gardes : confirmation de
  **chaque** écriture hors « tout autoriser » ; domaine avant émission ; identification refusée ;
  digest et cookies restent dans la page ; vue remise ; trace.
- **Plans / limites** : les six outils sont des `teams_*` donnés par `toolsFor` sous la garde du
  droit Teams — inchangée (test sans droit → aucun outil).
- **Auth / tenant, navigation front** : non.

---

## Plan de test

### Tests unitaires

- [ ] `SharePointWritesTest` — noms invalides ; lecture des réponses (dossier créé, fichier déposé, corbeille, version) ; erreurs 400 « existe déjà » / 423 / 403 ; script d'écriture : digest jamais rangé.
- [ ] `TeamsToolCatalogTest` — `describeWrite` enrichi, `readableLocation`.

### Tests d'intégration

- [ ] `TeamsWriteToolsTest` (navigateur de papier) — les six écritures nominales ; existant → `ALREADY_EXISTS` sans écriture ; nom invalide / fichier local absent → aucune commande d'action ; dépôt par champ créé + `setFileInputFiles` ; réponse non conforme → « a peut-être eu lieu » ; aucun secret ne sort d'une page non filtrée ; vue remise.
- [ ] `AtelierChatServiceRunnerGuardTest` — libellé de dépôt nommant le fichier local ; écriture refusée jamais émise (existant).
- [ ] Catalogues (21 côté runner ; gateway `CATALOG` + écritures) ; `RunnerToolGatewayTest` (délais).

### Isolation utilisateur

- Côté gateway : confirmation par le seul propriétaire du workspace (`RunnerConfirmationGate`,
  existant) ; outils donnés sous le droit Teams du propriétaire (test sans droit). Côté machine :
  session Microsoft du navigateur de l'utilisateur.

---

## Notes et décisions

- **Décision (sécurité, appliquée)** : le libellé de dépôt et de remplacement montre le **chemin
  local** — sans lui, l'utilisateur pourrait autoriser l'envoi d'un fichier qu'il n'a pas choisi
  sous un nom anodin.
- **Décision (réversible)** : `MoveTo` sans écrasement (`flags=0`), suppression en **corbeille**
  (`recycle`), dépôt sans écrasement (`Overwrite=false`) ; seul `teams_replace_version` écrase — et
  crée une version.
- **Décision (réversible)** : écritures toujours par l'API, jamais par le dossier synchronisé.
