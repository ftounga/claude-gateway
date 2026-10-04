# Mini-spec — [F-174 / SF-174-02] L'index de la carte

## Identifiant

`F-174 / SF-174-02`

## Feature parente

`F-174` — La carte qui répond (cadrage `CADRAGE-F-174-la-carte-qui-repond.md`, D1→D10 validées le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-174-02-index-de-la-carte`

---

## Objectif

Construire et tenir à jour, de façon incrémentale et asynchrone, un index dérivé de la carte de chaque poste (sections, faits typés, identifiants exacts, entités, relations, pièges, échéances), le texte Markdown restant la référence.

---

## Comportement attendu

### Cas nominal

1. **Signal de changement (D3).** `host_map_files` gagne `indexed_digest`. Un fichier dont `indexed_digest` est nul ou différent de `digest` (posé par le rafraîchissement existant `HostMapStore`) est à ré-indexer. Les cartes déjà en base (jamais indexées) le sont donc d'elles-mêmes : c'est le rétro-remplissage.
2. **Couche déterministe (D2 a), dans une transaction par fichier.** Le fichier est découpé en sections (`##`, sous-titres inclus, préambule compris). Chaque section a une empreinte SHA-256 de son texte. Section d'empreinte connue : gardée telle quelle (seul l'ordre peut bouger). Section nouvelle : créée avec ses **faits** (lignes porteuses, hors titres / gabarit `>` / code / séparateurs de tableau), chacun typé `FAIT` / `PIEGE` (marques : piège, ⚠, attention, ne jamais, interdit, cause réelle…) / `ECHEANCE` (date après « périme / expire / jusqu'au / avant le / renouveler… », hors date de constat), avec sa date de constat et ses **identifiants exacts** (compte AWS 12 chiffres, ARN, URL, IP/CIDR, domaine hors noms de fichiers, dépôt `groupe/projet` dans une ligne qui parle de dépôt) ; une **entité `MOTIF`** par identifiant. Section disparue : supprimée avec ses faits, entités et relations. Puis `indexed_digest = digest`.
3. **Couche sémantique (D2 b).** Les sections `PENDING` sont lues par Claude **via `AIProvider`** (modèle `APP_MAP_INDEX_MODEL`, défaut `claude-sonnet-5-5`, D4 ; clé BYOK du propriétaire sinon plateforme), sortie structurée après le marqueur `===CARTE===` : entités (type, libellé, identifiants, domaine, environnement, état), relations, lignes de pièges, échéances. **Vérification** : un identifiant n'est gardé que s'il figure mot pour mot dans la section ; une ligne hors section ou une date illisible est ignorée. La lecture ne se range que si la section existe encore avec la même empreinte. Section → `DONE` (tokens et modèle comptés).
4. **Asynchrone et borné.** `HostMapIndexWorker` (planifié, 60 s, premier passage à 2 min) appelle `HostMapIndexService.runOnce()` : au plus 10 fichiers et 6 sections par passe. Hors de tout tour.
5. **Coupe-circuit (D4).** `APP_MAP_INDEX_ENABLED=false` : aucune passe.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Fournisseur sans clé | Sections laissées `PENDING`, aucun essai consommé, passe arrêtée |
| Réponse illisible / panne fournisseur | Essai compté ; au 3e, section `FAILED` — la couche déterministe reste |
| Fichier modifié pendant la lecture du modèle | Lecture non rangée (empreinte différente) |
| Section sans fait | `SKIPPED`, aucun appel au modèle |
| Erreur sur un fichier | Journalisée (classe seulement), passe continue sur les suivants |

---

## Critères d'acceptation

- [x] Une carte jamais indexée est indexée par une passe : sections, faits typés (FAIT/PIEGE/ECHEANCE), dates, identifiants exacts, entités MOTIF + MODELE, relations.
- [x] Modifier une seule section ne ré-extrait qu'elle ; une passe sans changement n'appelle pas le modèle.
- [x] Un identifiant inventé par le modèle (absent du texte) n'est pas gardé.
- [x] Sans clé, rien n'est consommé ; la couche déterministe est en place.
- [x] Coupe-circuit éteint : rien n'est indexé.
- [x] Isolation : aucune lecture sans `(user_id, host_id)` ; purge du poste et du compte bornées.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Texte d'un fait | ≤ 2 000 caractères (tronqué avec « … ») |
| Texte d'une section envoyé au modèle | ≤ 24 000 caractères |
| Libellé d'entité | ≤ 300 caractères |
| Sortie d'une extraction | ≤ 8 000 tokens |

---

## Périmètre

### Hors scope (explicite)

- La recherche sur l'index (SF-174-03), la priorité pièges / échéances (SF-174-04), l'outil `carte_chercher` (SF-174-05), la consolidation (SF-174-06).
- Les embeddings (SF-174-03).
- Toute écriture sur la carte ou le poste (D1). Aucun écran (F-173).

---

## Technique

### Tables (migration `139-host-map-index.xml`)

- `host_map_files` + `indexed_digest varchar(64)`.
- `host_map_sections` (user_id, host_id, path, heading, ordinal, fingerprint, status, attempts, input/output_tokens, model, extracted_at, created_at).
- `host_map_facts` (… section_id FK cascade, line_no, text, kind, due_on, observed_on, identifiers).
- `host_map_entities` (… section_id FK cascade, kind, label, label_norm, identifiers, attributes JSON, state, observed_on, origin).
- `host_map_relations` (… section_id FK cascade, from_label, to_label, nature).

### Composants backend

`governance/map/index/` : `HostMapSectionSplitter`, `HostMapPatterns`, `HostMapLikes`, `HostMapSectionExtractor` (AIProvider), `HostMapIndexer`, `HostMapIndexService`, `HostMapIndexWorker`, `HostMapIndexProperties` (+ `HostMapIndexConfig`), entités et dépôts ; `HostMapIndexPurge` étendue ; `HostMapFile` (+ `indexedDigest`), `HostMapFileRepository` (+ `findStaleIndexIds`). `application.yml` : bloc `app.map-index`, grille de prix `claude-sonnet-5-5`.

### Endpoints / écrans

Aucun.

### Préoccupations transversales

| Préoccupation | Cochée | Composants |
|---|---|---|
| Contexte tenant | Oui | le travailleur balaie tous les comptes (comme les backfills F-148/F-162) mais chaque écriture porte le `(user_id, host_id)` de SA ligne source ; toutes les lectures métier filtrent `(user_id, host_id)` |
| Plans / limites | Oui | coût d'extraction : clé BYOK du propriétaire sinon plateforme (patron `RadarExtractor`) ; tokens comptés par section ; coupe-circuit D4 ; bornes par passe |
| Auth, routing | Non | — |

---

## Plan de test

- [x] `HostMapPatternsTest` (identifiants, faux positifs fichiers/chemins, dépôts avec contexte, dates, pièges).
- [x] `HostMapSectionSplitterTest` (sections, faits, code et tableaux, empreinte par section).
- [x] `HostMapSectionExtractorTest` (passage par `AIProvider` avec le modèle D4, vérification de la réponse, illisible, sans clé, panne).
- [x] `HostMapIndexIntegrationTest` (H2 + Liquibase réel, extracteur simulé) : indexation complète, incrémental, sans clé, isolation + purges, coupe-circuit.
- [x] Suite complète verte.

## Dépendances

SF-174-01 mergée.

## Notes et décisions

- **Arbitrage (réversible)** : déclenchement par balayage périodique de l'empreinte (`indexed_digest ≠ digest`) plutôt qu'un appel direct depuis `HostMapStore` : aucune modification du chemin de rafraîchissement, rétro-remplissage gratuit, reprise naturelle après redémarrage.
- **Arbitrage (réversible)** : l'extraction utilise la clé BYOK du propriétaire si elle existe (patron `RadarExtractor`), sinon la clé plateforme ; elle n'est pas imputée au quota de tours (c'est de la tenue d'index, comme le Radar) — tokens mesurés par section pour SF-174-07.
- **Arbitrage (réversible)** : un fait est « un piège » dès la marque déterministe ; le modèle ajoute ceux qu'il reconnaît, il n'en retire aucun.
