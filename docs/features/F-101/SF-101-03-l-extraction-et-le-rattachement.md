# Mini-spec — [F-101 / SF-101-03] L'extraction et le rattachement

---

## Identifiant

`F-101 / SF-101-03`

## Feature parente

`F-101` — Le Radar : la lecture des échanges
(cadrage validé : `docs/features/F-99/CADRAGE-le-radar.md`, §4.1, §4.3, §7, §12 bis)

## Statut

`in-progress`

## Date de création

2026-09-13

## Branche Git

`feat/SF-101-03-extraction-rattachement`

---

## Objectif

Sur les échanges retenus par le tri, poser au fournisseur (via `AIProvider`) la question du
**rattachement** — *de quels sujets suivis parlent-ils, ou de quel sujet nouveau ?* — avec le registre
des sujets (noms, alias, résumés) en **contexte mis en cache**, lire une **forme de sortie stricte**, et
n'écrire au registre que des faits **tous sourcés** ; puis brancher le tri et l'extraction sur la file.

---

## Contexte

La vraie difficulté du cadrage (§7) : « le MFA », « la double auth des presta », « le chantier Okta »
sont le même sujet. La gateway ne comprend rien elle-même : elle **rassemble** (registre + échanges),
**borne** (plafonds, tailles), **lit une forme** et **vérifie les renvois**. Le registre de F-99 garantit
déjà « pas de fait sans preuve » et la souveraineté des corrections ; cette SF lui fournit des preuves
qui existent **vraiment** dans les messages lus.

Les engagements, relances, mises en relation et signaux de clôture sont en **SF-101-04** ; la réserve
en **SF-101-05**.

---

## Comportement attendu

### Cas nominal

1. **L'analyseur** `RadarExchangeAnalyzer` (implémente `RadarBatchAnalyzer`, déclaré : **la file devient
   active**) :
   1. droit : `TeamsAccessService.hasAccess(userId)` faux → `DEFER` (`NO_ENTITLEMENT`), rien n'est lu ;
   2. **tri** (SF-101-02) : non lisible → `RETRY` (code du tri) ;
   3. aucun échange retenu → `DONE` sans écriture (le brut est effacé) ;
   4. **extraction** sur les seuls retenus : non lisible → `RETRY` (`EXTRACTION_UNREADABLE`), fournisseur
      absent / en échec → `RETRY` ;
   5. lisible → `DONE` avec les écritures (jouées par la file, dans la transaction qui efface le brut).
2. **Le contexte** (`RadarExtractionContext`), lu avant l'appel, **filtré sur `(user_id, host_id)`** :
   - sujets **ouverts** (non clos, non fusionnés) les plus récemment actifs, au plus 60, libellés
     `S1…` : nom, état, alias, et pour les 40 premiers **leurs phrases de résumé** libellées `S1.1…` ;
   - sujets **clos**, au plus 30 : nom et alias seulement (pour reconnaître un sujet qui se réveille) ;
   - les **noms refusés** par l'utilisateur pour chaque sujet (« ce n'est pas ce sujet ») ;
   - personnes du lot : chaque auteur identifié (`authorKey`) libellé `P1…`, l'utilisateur est `MOI` ;
   - messages retenus libellés `M1…`, rendus `[M3] [Thu 2026-09-10 06:30 UTC] Marc Durand (P2) : …`
     (texte tronqué à 4 000 caractères).
3. **L'appel** : `system` = consigne + registre (**mis en cache**, `cacheSystem=true` : stable d'un lot à
   l'autre tant que le registre ne change pas), `user` = personnes + messages ; modèle
   `app.radar.reading.extraction-model` s'il est connu, sinon `ModelCatalog.defaultModel()` ; plafond
   `extraction-max-tokens` (défaut 8 000) ; clé BYOK de l'utilisateur.
4. **La forme** (`===RADAR===` puis un objet JSON, dernier marqueur, `RadarOutputBlock`) :

   ```json
   {"sujets": [
     {"sujet": "S2",
      "preuves": ["M1", "M3"],
      "alias": ["la double auth des presta"],
      "etat": {"valeur": "bloque", "preuves": ["M3"]},
      "prochaine_etape": {"texte": "Valider le pilote avec le RSSI", "preuves": ["M3"]},
      "echeance": {"date": "2026-10-02", "preuves": ["M3"]},
      "resume": [{"reprise": "S2.1"}, {"phrase": "Le pilote est bloqué par la licence.", "preuves": ["M3"]}],
      "roles": [{"personne": "P2", "role": "decide", "preuves": ["M1"]}],
      "citations": {"M3": "bloqué tant que la licence n'est pas signée"}},
     {"sujet": "nouveau", "nom": "Renouvellement des certificats", "preuves": ["M4"]}
   ]}
   ```

5. **La lecture** (`RadarExtractionParser`) valide **tout** avant la moindre écriture : libellés connus,
   énumérations, dates ISO, bornes ; **au moins une preuve** pour chaque sujet, état, étape, échéance,
   phrase nouvelle et rôle. **Une seule violation rend toute la sortie illisible : rien n'est écrit.**
6. **Les écritures** (`RadarExtractionWriter`, via `RadarRegistry` uniquement) :
   - personnes : `upsertPerson(authorKey, nom, fonction)` pour les auteurs cités ;
   - preuves : `recordEvidence` pour **chaque message cité** — source de l'échange, `sourceRef` du
     message (idempotent), instant, **citation** = la citation proposée **si elle figure mot pour mot**
     dans le message (espaces normalisés), sinon le début du message (≤ 280) ; lien du message ou de
     l'échange ; auteur ;
   - `sujet: "nouveau"` → si le nom (normalisé) est déjà le nom ou un alias **non refusé** d'un sujet du
     contexte, rattachement à ce sujet ; sinon `createSubject(nom, NEW, preuves)` ;
   - rattachement → `attachEvidence` ; `addAlias` (un alias refusé est ignoré par le registre) ;
     `setState` / `setNextStep` / `setDueDate` (les valeurs souveraines restent, le registre y veille) ;
   - `resume` → `replaceSummary` : une `reprise` garde le texte **et les preuves** de la phrase existante ;
   - `roles` → `assignRole`.
7. **La certitude en toutes lettres** : aucun score n'est accepté ni stocké ; la consigne demande de
   créer un sujet `nouveau` (présenté comme tel) plutôt que de rattacher au hasard.
8. `subjectsAttached` / `subjectsCreated` sont rendus à la file (mesure SF-101-05).

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Marqueur absent, JSON cassé | illisible → `RETRY`, rien n'écrit | `EXTRACTION_UNREADABLE` |
| Libellé inconnu (`S99`, `M42`, `P7`, `S2.9`) | illisible | `EXTRACTION_UNREADABLE` |
| Fait sans preuve (état, phrase, rôle, sujet) | illisible | `EXTRACTION_UNREADABLE` |
| Valeur hors énumération (`etat: "clos"`, `role: "chef"`), date invalide, score numérique | illisible | `EXTRACTION_UNREADABLE` |
| `resume` sur un sujet dont le résumé n'a pas été montré | illisible | `EXTRACTION_UNREADABLE` |
| `nouveau` sans nom, nom > 200 | illisible | `EXTRACTION_UNREADABLE` |
| Registre qui refuse une écriture (sujet purgé entre-temps…) | tout est annulé, brut conservé | `WRITE_REJECTED` (file) |
| Utilisateur sans droit | report, rien n'est lu ni dépensé | `NO_ENTITLEMENT` |

---

## Critères d'acceptation

- [ ] Une sortie valide crée un sujet `NEW` sourcé, rattache des preuves à un sujet existant, ajoute un
      alias, fixe état / étape / échéance, remplace le résumé (reprises comprises) et assigne un rôle —
      chaque fait avec ses preuves.
- [ ] La citation d'une preuve est **toujours** tirée du message : une citation inventée est remplacée
      par le début du message.
- [ ] Toute violation de forme listée ci-dessus → **aucune écriture**, lot `RETRY`.
- [ ] `nouveau` dont le nom est un alias connu → rattaché, pas de doublon ; nom égal à un alias refusé
      → nouveau sujet.
- [ ] Une valeur souveraine (état corrigé par l'utilisateur) n'est pas réécrite.
- [ ] Un sujet clos cité se **réveille** (annoncé), il n'est pas rouvert.
- [ ] Le contexte ne contient que les sujets du poste ; la requête porte `cacheSystem=true` et la clé
      BYOK de l'utilisateur ; le fournisseur Anthropic pose `cache_control` sur la consigne système.
- [ ] Bout en bout (H2, fournisseur factice) : lot déposé → `runOnce` → faits écrits, brut effacé,
      jetons tri + extraction portés sur le lot et la synchro.
- [ ] Sans droit Teams : `DEFERRED`, aucun appel au fournisseur.
- [ ] **Isolation** : un lot du poste A ne voit ni n'écrit rien du poste B (même utilisateur) ni de Bob.

---

## Périmètre

### Hors scope (explicite)

- Engagements, relances dues, mises en relation, signaux de clôture (SF-101-04).
- Réserve, arrêt propre, coût en euros, taux de corrections (SF-101-05).
- Écrans (F-102 / F-103), outils Radar (F-104).

---

## Contraintes de validation

| Champ | Obligatoire | Borne | Règle |
|-------|-------------|-------|-------|
| `sujets` | Oui | ≤ 30 entrées | tableau ; vide = rien à écrire (lisible) |
| `sujet` | Oui | — | `S<k>` du contexte, ou `nouveau` |
| `nom` | si `nouveau` | 200 | non vide |
| `preuves` (partout) | Oui | 1 → 20 | libellés `M<k>` du lot |
| `alias` | Non | ≤ 5, 200 chacun | non vides |
| `etat.valeur` | si `etat` | — | `avance`, `en_attente`, `bloque` |
| `prochaine_etape.texte` | si présent | 500 | vide = effacer |
| `echeance.date` | si présent | — | `AAAA-MM-JJ` ou `null` (effacer) |
| `resume` | Non | ≤ 20 phrases | `{"reprise": "S<k>.<n>"}` ou `{"phrase": ≤ 500, "preuves": [...]}` ; seulement si le résumé du sujet a été montré, ou pour un sujet nouveau |
| `roles[].personne` | Oui | — | `P<k>` du lot |
| `roles[].role` | Oui | — | `decide`, `pilote`, `expert`, `informe` |
| `citations` | Non | clé `M<k>`, valeur ≤ 280 | ignorée si absente du message |
| `app.radar.reading.extraction-max-tokens` | Non | [1 000, 16 000] | défaut 8 000 |

---

## Technique

### Endpoint(s)

- Aucun nouveau (lecture existante inchangée).

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `radar_subjects`, `radar_subject_aliases`, `radar_subject_facts`, `radar_people`, `radar_subject_roles`, `radar_evidence`, `radar_evidence_links` | INSERT / UPDATE / SELECT | **uniquement via `RadarRegistry`** |
| `radar_analysis_batches` | UPDATE | via la file (SF-101-01) |

### Migration Liquibase

- [x] Non applicable

### Composants Angular (si applicable)

- Aucun.

### Couche fournisseur

- `ChatCompletionRequest` gagne `cacheSystem` (défaut `false`, constructeurs existants inchangés) ;
  `AnthropicProvider` envoie alors `system` en bloc avec `cache_control: {type: ephemeral}`. Aucun autre
  appelant n'est modifié.

---

## Plan de test

### Tests unitaires

- [ ] `RadarExtractionParserTest` — sortie complète valide ; chaque violation du tableau d'erreurs →
      illisible ; champs inconnus ignorés.
- [ ] `RadarExtractionContextTest` — libellés, bornes (60 / 40 / 30), noms refusés, personnes et `MOI`,
      rendu des messages.
- [ ] `RadarQuotesTest` — citation mot pour mot acceptée, inventée remplacée, espaces normalisés.
- [ ] `AnthropicProviderTest` — `cacheSystem` → bloc système avec `cache_control` ; sinon chaîne simple.

### Tests d'intégration

- [ ] `RadarExtractionIntegrationTest` (H2, `AIProvider` factice) — bout en bout par la file :
      création, rattachement, alias, état souverain respecté, résumé avec reprises, rôle, réveil d'un
      sujet clos, sortie illisible → rien écrit et `RETRY`, tri sans retenu → `DONE` sans écriture, sans
      droit → `DEFERRED` sans appel.

### Isolation

- [x] Applicable — `RadarExtractionIntegrationTest` : Alice poste A / poste B et Bob ; le contexte
      soumis pour A ne contient aucun sujet de B ni de Bob ; une sortie citant un libellé de sujet absent
      du contexte de A est illisible ; les écritures ne touchent que A.

---

## Dépendances

### Subfeatures bloquantes

- SF-101-01 (file) — `done` ; SF-101-02 (tri) — `done`.

### Questions ouvertes impactées

- Aucune.

---

## Préoccupations transversales

- **Contexte tenant : oui.** Composants impactés : `RadarExtractionContext` (lecture filtrée par
  `RadarScope`), `RadarExtractionWriter` (écrit via `RadarRegistry`, qui refuse toute preuve / personne /
  sujet d'un autre périmètre). Aucun résolveur existant modifié.
- **Plans / limites : oui (lecture seule).** Composant impacté : `TeamsAccessService.hasAccess(userId)`
  appelé par l'analyseur (droit provisoire du Radar, comme F-99 ; remplacé par le droit Vigie en
  SF-107-03). Aucun service de quota appelé : la synchro ne mange pas le quota des conversations (§11).
- **Auth / Principal : non.** **Navigation : non.**

---

## Notes et décisions

- **Les preuves sont des libellés de messages, jamais des identifiants venus du modèle** : un modèle ne
  peut désigner qu'un message qu'on lui a montré, et la citation est relue dans ce message.
- **Rattachement par nom exact en filet de sécurité** (`nouveau` au nom d'un alias connu) : ce n'est pas
  de la compréhension, c'est éviter un doublon que le modèle aurait dû voir. Réversible.
- **Un sujet nouveau naît `NEW`** : l'état proposé pour un sujet découvert n'est pas appliqué, le
  cadrage veut qu'il soit présenté comme nouveau.
- **Modèle d'extraction = modèle par défaut du catalogue** : l'extraction demande plus de jugement que le
  tri. Réversible par configuration.
- **Cache de prompt** : ajout provider-neutre d'un drapeau à la requête ; le seul fournisseur actuel le
  traduit en `cache_control`. Un fournisseur sans cache l'ignore.
