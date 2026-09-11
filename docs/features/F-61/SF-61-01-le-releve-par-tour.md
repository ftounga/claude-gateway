# Mini-spec — F-61 / SF-61-01 — Le relevé par tour, source de vérité

---

## Identifiant

`F-61 / SF-61-01`

## Feature parente

`F-61` — Consommation : par client pour chacun, par utilisateur pour l'admin (`docs/PRODUCT_SPEC.md`)

## Statut

`done` — livrée le 2026-09-11

## Date de création

2026-09-11

## Branche Git

`feat/SF-61-01-releve-et-agregation`

---

## Objectif

Ranger, à chaque tour facturé, **une mesure durable et attribuable** — combien de tokens d'entrée et
de sortie, pour quel projet, sous quel poste — dans un journal **append-only** qui ne porte
**aucun contenu**, parce que le compteur existant (`workspaces.agent_*_tokens`) est remis à zéro à
chaque session et ferait **rétrécir** les totaux (cadrage §1).

---

## Comportement attendu

### Cas nominal

1. Partout où la consommation est **déjà** décomptée (`QuotaService.recordUsage`), une ligne est
   **ajoutée** à `usage_turns` : `user_id`, `workspace_id` (le projet, si le tour en a un),
   `host_id` (le poste **au moment du tour**), `input_tokens`, `output_tokens`, `occurred_at`.
2. Les quatre chemins servis sont couverts :
   - `AtelierSessionService.recordSessionUsage` (Forge, Managed Agents) — projet et poste connus ;
   - `AtelierChatService` (Forge, boucle tool-use) — projet et poste connus ;
   - `ChatService` et `AskService` — **sans projet** : `workspace_id` et `host_id` nuls.
3. **Le journal ne remplace rien** : `usage_counters` (F-10) continue d'être incrémenté à
   l'identique. Le quota, l'alerte F-42 et le rapport F-16 sont **inchangés**.
4. **Rien de nul, rien de négatif** : un tour à 0 token n'écrit aucune ligne (comme
   `recordUsage` n'incrémente rien) ; les valeurs négatives sont ramenées à 0.
5. **L'écriture du journal ne peut pas faire échouer un tour.** Elle est encadrée : une erreur est
   journalisée en `warn` et avalée. Un relevé perdu est un défaut d'information ; un tour en échec
   après que le fournisseur a été payé serait un défaut de service **et** d'argent.
6. **Suppression de compte** : `deleteByUserId` purge le journal, comme les autres tables portant
   `user_id` (`AccountService`, F-11).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Tour à 0 token d'entrée **et** 0 de sortie | Aucune ligne écrite, aucun compteur touché | — (interne) |
| Valeur négative rapportée par le fournisseur | Ramenée à 0 avant écriture | — (interne) |
| Écriture du journal en échec (base indisponible) | `warn` journalisé, tour **livré normalement**, compteur F-10 inchangé | — (interne) |
| Projet supprimé après le tour | La ligne **survit** (pas de clé étrangère) — la dépense a eu lieu | — (interne) |
| Tour hors projet (`/chat`, `/ask`) | Ligne écrite avec `workspace_id` et `host_id` nuls | — (interne) |

---

## Critères d'acceptation

- [ ] La table `usage_turns` existe (migration `068`), avec `user_id` **NOT NULL**, `workspace_id` et
      `host_id` **nullables**, `input_tokens`/`output_tokens` **NOT NULL** par défaut 0,
      `occurred_at` NOT NULL.
- [ ] La table **ne contient aucune colonne de texte libre** — aucun message, aucune commande, aucun
      chemin, aucun nom. Vérifiable en lisant la migration.
- [ ] Un tour de Forge de 1 000 / 200 tokens écrit **une** ligne portant le projet et son poste.
- [ ] Deux tours successifs écrivent **deux** lignes : le cumul lu **croît**, il ne rétrécit jamais —
      y compris après une remise à zéro de session (test explicite du scénario du cadrage §1).
- [ ] Un appel `/chat` écrit une ligne sans projet ni poste.
- [ ] Un tour à 0/0 n'écrit **aucune** ligne.
- [ ] Une panne d'écriture du journal **ne fait pas échouer** le tour et n'empêche pas
      l'incrément de `usage_counters`.
- [ ] `AccountService.deleteAccount` purge `usage_turns` de l'utilisateur.
- [ ] Isolation : toute lecture du journal filtre `user_id` (aucune méthode de repository ne lit
      sans ce filtre, sauf l'agrégation admin — qui n'existe pas ici, cf. arbitrage A-4).

---

## Périmètre

### Hors scope (explicite)

- Toute exposition HTTP du journal : c'est SF-61-02 et SF-61-03.
- La reprise rétroactive de la consommation passée : impossible et malhonnête (cadrage §1).
- Le temps de bac à sable et le coût réel fournisseur (`agent_list_cost`) : le journal mesure des
  **tokens**. Les ajouter demanderait de trancher leur ventilation par projet — non demandé.
- Toute modification du quota, de l'alerte F-42 ou du rapport F-16.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `input_tokens` | 0 | `max(0, valeur rapportée)` |
| `output_tokens` | 0 | `max(0, valeur rapportée)` |
| `occurred_at` | `now()` (horloge applicative `Clock`) | Posé à l'écriture, jamais fourni par le client |
| `workspace_id` | `null` | Renseigné seulement si le tour appartient à un projet |
| `host_id` | `null` | **Instantané** du `workspaces.host_id` au moment du tour (arbitrage A-2) |
| `user_id` | utilisateur du contexte de sécurité | Jamais un paramètre client |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|-------|-------------|-------------|------------------|---------|---------------|
| `user_id` | Oui | — | UUID | Non | — |
| `workspace_id` | Non | — | UUID | Non | — |
| `host_id` | Non | — | UUID | Non | — |
| `input_tokens` | Oui | `bigint` | ≥ 0 | Non | `max(0, v)` |
| `output_tokens` | Oui | `bigint` | ≥ 0 | Non | `max(0, v)` |
| `occurred_at` | Oui | — | horodatage avec fuseau | Non | horloge serveur |

Notes :
- Aucune clé étrangère vers `workspaces` ni `runner_hosts` : **volontaire**. Une pièce de
  refacturation ne doit pas disparaître parce que le projet a été rangé. Les noms sont résolus à la
  lecture, et un projet disparu se lit « projet supprimé ».
- `bigint` et non `int` : un tour d'agent long dépasse déjà le million de tokens d'entrée.

---

## Technique

### Endpoint(s)

Aucun. Subfeature d'infrastructure de mesure.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `usage_turns` | CREATE, INSERT, DELETE (purge compte) | Table neuve, append-only |
| `usage_counters` | — | **Inchangée** — le journal s'ajoute, ne remplace pas |

### Migration Liquibase

- [x] Oui — `068-usage-turns.xml` (changesets PostgreSQL **et** H2, `rollback` = `dropTable`)
- Numéro `068` : premier libre après `067-access-codes.xml` / `067-runner-hosts-mission-status.xml`.

### Composants Angular

Aucun.

---

## Plan de test

### Tests unitaires

- [ ] `UsageLedgerServiceTest` — un tour nominal écrit une ligne avec projet et poste.
- [ ] `UsageLedgerServiceTest` — un tour sans projet écrit une ligne à `workspace_id`/`host_id` nuls.
- [ ] `UsageLedgerServiceTest` — 0/0 n'écrit rien ; valeurs négatives ramenées à 0.
- [ ] `UsageLedgerServiceTest` — une panne du repository est avalée (aucune exception remontée).
- [ ] `QuotaServiceTest` (existants) — **toujours verts** : le comportement de quota est inchangé.

### Tests d'intégration

- [ ] `UsageLedgerIntegrationTest` — deux tours consécutifs sur le même projet : la somme lue est
      la somme des deux (le total ne rétrécit pas), y compris quand `workspaces.agent_input_tokens`
      est remis à 0 entre les deux.
- [ ] `AccountService` — après suppression de compte, aucune ligne de journal ne subsiste.

### Isolation workspace / utilisateur

- [x] Applicable — test : les lignes de l'utilisateur A ne sont jamais lues par une requête portant
      l'identifiant de l'utilisateur B (repository filtré `user_id`).

---

## Dépendances

### Subfeatures bloquantes

Aucune (F-10 et F-48 sont livrées).

### Questions ouvertes impactées

- [ ] Aucune. `OQ-15` (contradiction interne de la charte) ne concerne que le frontend et n'est pas
      tranchée ici.

---

## Notes et décisions

- **Pourquoi ne pas incrémenter simplement une colonne cumulative sur `workspaces`** : un cumul par
  projet répondrait à « par projet », mais pas à « sur une période choisie ». Un journal répond aux
  deux, et il est la seule forme dont on puisse **prouver** qu'elle ne rétrécit pas.
- **Pourquoi l'écriture est encadrée** : même argument que `recordSessionUsage` (F-30), qui avale
  déjà ses erreurs — le tour est livré, le fournisseur est payé, il est trop tard pour échouer.
- **Volume** : une ligne par tour facturé. Un utilisateur intensif produit quelques milliers de
  lignes par mois ; l'index `(user_id, occurred_at)` couvre la seule lecture prévue.
