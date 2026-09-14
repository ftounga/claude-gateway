# Mini-spec — F-112 / SF-112-07 : Pages, courriel, compte, administration

## Identifiant

`F-112 / SF-112-07`

## Feature parente

`F-112` — Le serveur MCP

## Statut

`ready`

## Date de création

2026-09-14

## Branche Git

`feat/SF-112-07-outils-pages-courriel-compte-admin`

---

## Objectif

Exposer sur le serveur MCP les outils **Pages, Courriel, Compte et Administration** (cadrage §5),
relais des services existants, chacun gardé par son périmètre — les outils d'administration étant en
plus **gardés par le rôle ADMIN**.

---

## Comportement attendu

| Outil | Périmètre | Garde | Relaie |
|---|---|---|---|
| `page_publier` | `pages` | — | `PageService.publish` |
| `pages_lister` | `pages` | — | `PageService.list` |
| `page_lire` | `pages` | — | `PageService.require` + `html` |
| `courriel_m_envoyer` | `courriel` | droit terminal du poste | `ClientMailTool.send` (F-110, destinataire résolu par la gateway) |
| `compte_consommation` | `compte:lire` | — | `UsageReportService.buildReport` |
| `compte_abonnement` | `compte:lire` | — | `SubscriptionService.getOrCreateForUser` |
| `admin_utilisateurs` | `admin` | **rôle ADMIN** | `AdminService.listUsers` |
| `admin_codes_acces_emettre` | `admin` | **rôle ADMIN** | `AccessCodeService.issue` |
| `admin_quota_crediter` | `admin` | **rôle ADMIN** | `QuotaService.creditBonusTokens` |
| `admin_sante` | `admin` | **rôle ADMIN** | version + état applicatif |

- Les outils d'administration exigent **et** le périmètre `admin` **et** le rôle ADMIN (double garde).
  Un compte non-ADMIN portant `admin` est refusé (les périmètres ne sont jamais plus larges que les
  droits).
- Contenu de page (potentiellement écrit par une IA) rendu par `page_lire` : marqué **non fiable**
  (§6.3) et masqué (§6.4).
- `courriel_m_envoyer` réutilise F-110 (limites, filtre de secrets du corps, adresse du compte
  résolue par la gateway) via le terminal du poste ; le corps n'est jamais renvoyé.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Périmètre absent | refus nommé |
| Outil admin sans rôle ADMIN | refus (même si `admin` accordé) |
| `page_publier` HTML vide / trop volumineux | erreur claire (relayée) |
| `courriel_m_envoyer` sujet/corps manquant, limite atteinte, secret détecté | refus nommé (F-110) |
| `admin_quota_crediter` utilisateur cible inconnu | erreur claire |

---

## Critères d'acceptation

- [ ] Les 10 outils sont découverts avec leurs annotations.
- [ ] Chaque outil refuse sans son périmètre.
- [ ] Les 4 outils admin refusent un compte non-ADMIN, **même avec le périmètre `admin`**.
- [ ] `compte_consommation`/`compte_abonnement` renvoient les données du **porteur** (isolation).
- [ ] `page_lire` marque le contenu `untrusted`.
- [ ] `admin_utilisateurs` (ADMIN) liste les utilisateurs.

---

## Périmètre — hors scope

- Suppression/export/purge de pages, gestion fine des versions : l'app les garde.
- Changement de plan, remboursement, webhooks Stripe : hors MCP.
- Détail k8s (pods/déploiement) de `admin_sante` : hors de portée du backend — `admin_sante` rend la
  **version** et l'**état applicatif** ; le détail d'orchestration reste à la console ops (documenté).

---

## Contraintes de validation

| Champ | Obligatoire | Format |
|---|---|---|
| `title`, `html` | Oui (`page_publier`) | non vides |
| `space` | Non | `FORGE` (défaut) / `VIGIE` |
| `page_id` | Oui (`page_lire`) | UUID |
| `host_id` | Oui (`courriel_m_envoyer`) | UUID, poste accessible |
| `subject`, `body` | Oui (`courriel_m_envoyer`) | non vides |
| `label`, `email` | Oui (`admin_codes_acces_emettre`) | non vides |
| `user_id`, `tokens` | Oui (`admin_quota_crediter`) | UUID, entier > 0 |

---

## Technique

Aucun endpoint HTTP, aucune migration. `McpAdminGuard` applique la double garde (périmètre `admin` +
rôle ADMIN) depuis `McpCallContext`. Contenu de page rendu via `McpToolSupport.okUntrusted`.

### Préoccupations transversales

- **Auth / Principal** : oui — outils admin gardés par le rôle depuis le jeton (`McpAdminGuard`,
  miroir d'`AdminService.assertAdmin` mais résolu depuis `McpCallContext`). Composants : services
  relayés (`PageService`, `ClientMailTool`, `UsageReportService`, `SubscriptionService`,
  `AdminService`, `AccessCodeService`, `QuotaService`). Aucune route HTTP existante touchée.
- **Plans / limites** : oui — `compte:lire` lit quota/abonnement ; `courriel` suit les limites F-110.

---

## Plan de test

### Intégration (client SDK réel, périmètres)

- [ ] Découverte des 10 outils.
- [ ] `pages_lister` sans `pages` → refus.
- [ ] `compte_consommation` avec `compte:lire` → OK, données du porteur.
- [ ] `admin_utilisateurs` sans rôle ADMIN (mais avec `admin`) → refus.
- [ ] `admin_utilisateurs` avec rôle ADMIN + `admin` → OK.
- [ ] `page_publier` puis `page_lire` → contenu `untrusted`.

### Isolation utilisateur

- [ ] Applicable — services appelés avec `ctx.user().id()` ; test A/B sur `compte_consommation`.

---

## Dépendances

- `SF-112-01→06` — done. F-107 (droits), F-109 (pages), F-110 (courriel).

---

## Notes et décisions

- **Double garde admin** : le périmètre OAuth `admin` ne suffit pas — le rôle ADMIN est exigé en plus
  (un périmètre n'est jamais plus large que les droits, cadrage §4/§8). `admin_codes_acces_emettre`
  rend le code émis (c'est son objet, comme l'écran admin) ; il ne correspond à aucun motif de secret.
- `courriel_m_envoyer` passe par le **terminal du poste** (F-110) pour résoudre l'adresse et suivre
  les limites/filtre de secrets ; l'ouverture du terminal du poste est un effet attendu de F-74.
