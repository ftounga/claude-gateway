# Mini-spec — [F-101 / SF-101-04] Engagements, relances, mises en relation

---

## Identifiant

`F-101 / SF-101-04`

## Feature parente

`F-101` — Le Radar : la lecture des échanges
(cadrage validé : `docs/features/F-99/CADRAGE-le-radar.md`, §3, §4.3, §6, §7, §12 bis)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-101-04-engagements-relances`

---

## Objectif

Étendre l'extraction à **qui doit quoi à qui** — engagements *moi → autre*, *autre → moi*, **mises en
relation** — avec leur **échéance résolue depuis la date du message** et leur **certitude en toutes
lettres**, au **suivi des engagements ouverts** (tenus, reportés, abandonnés), aux **signaux de
clôture** d'un sujet, et calculer la **relance due** (3 jours ouvrés par défaut).

---

## Contexte

Le cadrage (§1) : « il faut que je fasse des actions, que je mette untel en relation avec untel, que je
relance ». Le registre (F-99) sait déjà stocker un engagement sourcé (`recordCommitment`, idempotent par
clé d'extraction), en changer le statut (`markCommitment`, souveraineté respectée) et proposer une
clôture (`proposeClosure`, jamais une clôture). SF-101-03 a posé la forme `===RADAR===` et l'écriture
vérifiée : cette SF y ajoute trois champs par sujet, et la relance due.

---

## Comportement attendu

### Cas nominal

1. **Le contexte** montre, sous chaque sujet ouvert, ses **engagements ouverts** (statut `OPEN`, non
   désavoués), au plus 100 au total, libellés `C1…` :
   `C3 [autre → moi] Marc Durand → MOI : Envoyer le devis (échéance 2026-10-02)`.
2. **La forme** gagne, pour chaque sujet :

   ```json
   "engagements": [
     {"sens": "autre_vers_moi", "description": "Envoyer le devis", "debiteur": "P1",
      "echeance": {"date": "2026-09-17", "nature": "deduite"}, "certitude": "certain", "preuves": ["M2"]},
     {"sens": "moi_vers_autre", "description": "Relancer les achats", "beneficiaire": "P2",
      "certitude": "certain", "preuves": ["M3"]},
     {"sens": "mise_en_relation", "description": "Présenter Marc à l'équipe Okta",
      "beneficiaire": "P1", "autre": "P3", "certitude": "probable", "preuves": ["M1"]}
   ],
   "engagements_suivis": [{"engagement": "C3", "statut": "tenu", "preuves": ["M4"]}],
   "cloture": {"preuves": ["M5"]}
   ```

3. **La lecture** (même règle : une violation → rien n'est écrit) :
   - `sens` ∈ `moi_vers_autre`, `autre_vers_moi`, `mise_en_relation` ;
   - `moi_vers_autre` : `beneficiaire` facultatif, ni `debiteur` ni `autre` ;
   - `autre_vers_moi` : `debiteur` requis, ni `autre` ;
   - `mise_en_relation` : `beneficiaire` et `autre` requis et distincts, pas de `debiteur` ;
   - `certitude` ∈ `certain`, `probable` — **jamais un nombre** ;
   - `echeance.date` ISO, `nature` ∈ `explicite`, `deduite` ; la date doit tomber entre la veille du
     **plus ancien** message cité et deux ans après le **plus récent** ;
   - `engagements_suivis[].engagement` : un `C<k>` montré ; `statut` ∈ `tenu`, `reporte`, `abandonne` ;
   - `cloture` : sur un sujet **suivi** seulement, avec au moins une preuve ;
   - au plus 20 engagements et 20 suivis par sujet.
4. **Les écritures** (via `RadarRegistry`, après celles du sujet) :
   - `recordCommitment` : sens, description, personnes (`upsertPerson`), échéance, `dueDeduced` si
     `deduite`, certitude — **une échéance déduite rend l'engagement `PROBABLE`** (cadrage §4.3) —,
     preuves ; **clé d'extraction** = empreinte de (identifiant de source de la première preuve, sens,
     description normalisée) : un même engagement relu ne se duplique pas ;
   - `markCommitment` : `tenu` → `KEPT`, `reporte` → `POSTPONED`, `abandonne` → `ABANDONED` ; un
     engagement corrigé par l'utilisateur garde son statut (le registre y veille) ;
   - `proposeClosure` : le sujet passe **« clos ? »** avec la phrase qui le justifie ; jamais clos ; un
     refus de l'utilisateur n'est pas remis en cause par un signal antérieur (le registre y veille).
5. **La relance due** (migration 090) : chaque engagement porte `last_evidence_at` (preuve la plus
   récente, tenue par le registre) et `follow_up_due_on`, **recalculé à chaque écriture de
   l'engagement** (analyse, correction, fusion) :
   - `autre_vers_moi` **ouvert** et non désavoué : échéance connue → le **premier jour ouvré après
     l'échéance** ; sinon → **3 jours ouvrés** après la preuve la plus récente ;
   - tout autre cas (`moi_vers_autre`, mise en relation, statut fermé, désavoué) → `NULL`.
   Jours ouvrés = hors samedi et dimanche.
6. **Lecture** : `CommitmentView` gagne `lastEvidenceAt`, `followUpDueOn`, `followUpDue` (échu à la date
   du jour, UTC) ; `GET /api/radar/hosts/{hostId}/commitments?followUpDue=true` ne rend que les relances
   dues.

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Sens inconnu, parties incohérentes (débiteur manquant, mise en relation d'une personne avec elle-même) | illisible, rien n'écrit | `EXTRACTION_UNREADABLE` |
| `certitude` absente, chiffrée ou hors énumération | illisible | `EXTRACTION_UNREADABLE` |
| Échéance hors fenêtre du message, date invalide, `nature` inconnue | illisible | `EXTRACTION_UNREADABLE` |
| Suivi d'un `C<k>` non montré, statut inconnu | illisible | `EXTRACTION_UNREADABLE` |
| `cloture` sur un sujet nouveau, ou sans preuve | illisible | `EXTRACTION_UNREADABLE` |
| `followUpDue` non booléen | `validation_error` | 400 |
| Poste d'autrui | `not_found` | 404 |

---

## Critères d'acceptation

- [ ] Les trois sens sont écrits avec leurs personnes, leur échéance, leur certitude et leurs preuves.
- [ ] Une échéance `deduite` est enregistrée `dueDeduced=true` et l'engagement `PROBABLE`.
- [ ] Un même engagement lu deux fois (lot redéposé) n'est pas dupliqué.
- [ ] `tenu` / `reporte` / `abandonne` changent le statut ; un engagement souverain garde le sien.
- [ ] `cloture` → sujet `CLOSE_PROPOSED` avec ses preuves, jamais `CLOSED`.
- [ ] Toute violation du tableau d'erreurs → aucune écriture.
- [ ] Relance due : `autre_vers_moi` sans échéance, preuve un jeudi → due le mardi suivant ; avec
      échéance un vendredi → due le lundi suivant ; `tenu` → plus de relance ; `moi_vers_autre` → aucune.
- [ ] `GET /commitments?followUpDue=true` ne rend que les relances échues du poste.
- [ ] Migration 090 : colonnes, index, PostgreSQL et H2, rollback ; `ddl-auto: validate`.
- [ ] **Isolation** : `followUpDue` sur le poste B ne rend rien du poste A ; 404 pour Bob.

---

## Périmètre

### Hors scope (explicite)

- Brouillons de relance et de présentation (F-104 / SF-104-05).
- Écrans *À faire par moi* / *J'attends des autres* et compteur de flotte (F-102).
- Jours fériés (seuls samedi et dimanche sont chômés).
- Nouvelle échéance d'un engagement `reporte` lue dans une source (le report garde l'échéance connue ;
  l'utilisateur la corrige par *reporter*, SF-99-02).
- Réserve et mesure (SF-101-05).

---

## Contraintes de validation

| Champ | Obligatoire | Borne | Règle |
|-------|-------------|-------|-------|
| `engagements[].description` | Oui | 500 | non vide |
| `engagements[].sens` | Oui | — | `moi_vers_autre`, `autre_vers_moi`, `mise_en_relation` |
| `engagements[].certitude` | Oui | — | `certain`, `probable` |
| `engagements[].echeance.nature` | si `echeance` | — | `explicite`, `deduite` |
| `engagements[].preuves` | Oui | 1 → 20 | `M<k>` |
| `engagements` / `engagements_suivis` | Non | ≤ 20 chacun | par sujet |
| `engagements_suivis[].statut` | Oui | — | `tenu`, `reporte`, `abandonne` |
| `radar_commitments.follow_up_due_on` | Non | — | calculé, jamais saisi |
| `followUpDue` (requête) | Non | — | booléen |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/radar/hosts/{hostId}/commitments?followUpDue=true` | Oui | droit Teams (inchangé) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_commitments` | INSERT / UPDATE / SELECT | + `last_evidence_at`, `follow_up_due_on` (090) |
| `radar_subjects`, `radar_people`, `radar_evidence`, `radar_evidence_links` | via registre | |

### Migration Liquibase

- [x] Oui — `090-radar-commitment-follow-up.xml`

### Composants Angular (si applicable)

- Aucun (F-102).

---

## Plan de test

### Tests unitaires

- [ ] `RadarFollowUpTest` — jours ouvrés, avec / sans échéance, sens, statut, désaveu.
- [ ] `RadarExtractionParserTest` (étendu) — trois sens valides ; échéance déduite ; chaque violation
      du tableau d'erreurs → illisible ; suivis et clôture.
- [ ] `RadarExtractionContextTest` (étendu) — libellés `C<k>`, rendu sous le sujet, borne de 100.

### Tests d'intégration

- [ ] `RadarExtractionIntegrationTest` (étendu) — bout en bout : trois engagements écrits, suivi `tenu`,
      proposition de clôture, relance due calculée, idempotence d'un lot redéposé.
- [ ] `RadarReadApiIntegrationTest` / `RadarFollowUpApiIntegrationTest` — `followUpDue=true` filtré,
      400 sur valeur invalide.

### Isolation

- [x] Applicable — `RadarFollowUpApiIntegrationTest` : relances du poste A invisibles depuis le poste B
      et pour Bob (404).

---

## Dépendances

### Subfeatures bloquantes

- SF-101-03 — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants impactés : `RadarRegistrySnapshot` (engagements ouverts lus
  par `(user_id, host_id)`), `RadarExtractionWriter`, `RadarRegistry.recordCommitment` /
  `markCommitment` (tenue de `last_evidence_at`), `RadarReadService.commitments` (filtre). Aucun
  résolveur modifié.
- **Plans / limites : non.** **Auth / Principal : non.** **Navigation : non.**

---

## Notes et décisions

- **Relance due stockée plutôt que calculée à la lecture** : le compteur de flotte (F-102) et le résumé
  du matin la compteront sur tous les postes ; recalculée par rappel d'entité, elle reste juste quelle
  que soit l'écriture (analyse, correction, fusion). Pas de rétro-remplissage : aucune synchro
  n'alimente encore le Radar en production.
- **Une échéance déduite rend l'engagement probable** : règle du cadrage appliquée par le code, pas
  laissée au modèle.
- **Mise en relation = engagement de l'utilisateur** : pas de relance due (c'est à lui de faire).
