# Mini-spec — [F-266 / SF-266-01] Règles de compte : table, injection, portée COMPTE, recopie

## Identifiant
`F-266 / SF-266-01`

## Feature parente
`F-266` — Règles de tous mes postes (portée COMPTE)

## Statut
`in progress`

## Date de création
2026-11-16

## Branche Git
`feat/SF-266-01-regles-de-compte`

---

## Objectif
Une règle de gouvernance rangée côté serveur pour un compte vaut à chaque tour de cet utilisateur, quel
que soit le poste, et se crée par proposition de l'agent, par recopie d'une règle de poste, ou par route.

## Comportement attendu

### Cas nominal
- **Injection** : `buildSystemPrompt` ajoute, avant les règles du poste, le bloc
  `--- Règles du client — tous mes postes (portée compte) : elles valent sur chaque poste ; une règle du
  poste ou du sujet de même titre les remplace ---` + sections `## titre\n\ncontenu` triées par clé de
  titre + borne de fin. Omis si aucune règle (consigne inchangée à l'octet près pour un compte sans
  règle). Les règles dont le titre apparaît en `## titre` dans le texte injecté (borné) du poste ou du
  sujet sont omises.
- **Proposer** : `gouvernance_proposer(type=REGLE, portee=COMPTE, nom, contenu)` → carte (path
  `compte · tous mes postes`, diff DEL ancienne règle / ADD nouvelle), rien n'est écrit. Aucun poste
  requis (projet hébergé accepté).
- **Appliquer** (COMPTE) : vérifie que la règle de même titre n'a pas changé depuis la proposition,
  écrit en base (crée ou remplace), statut APPLIED, sans aller-retour runner.
- **Recopier depuis une proposition** : `POST /api/workspaces/{w}/governance-proposals/{p}/promote` sur
  une REGLE POSTE/SUJET **appliquée** : extrait la section `## nom` du contenu appliqué, la range en
  portée COMPTE (crée ou remplace). Poste éteint accepté.
- **Recopier depuis le poste (Forge)** : `POST /api/governance/hosts/{hostRef}/account-rules`
  `{title}` : lit `GOUVERNANCE.md` du poste, extrait la section, la range.
- **Routes du compte** : `GET /api/governance/account-rules` (liste), `POST /api/governance/account-rules`
  `{title, content}` (crée ou remplace), `DELETE /api/governance/account-rules/{id}`.
- **La consigne ne coupe plus les règles du client** (découvert en développant, décision D6 du cadrage) :
  le plafond global `SYSTEM_MAX_CHARS` (40 000 depuis F-28) est dépassé par les seules doctrines et guides
  d'outils (mesuré : ~43 000 car. pour un sujet de poste, ~49 000 au terminal, contexte complet, compte
  ADMIN) ; la coupe en fin de consigne retirait CLAUDE.md, les `GOUVERNANCE.md`, les règles des paquets
  — et donc aussi les règles COMPTE. Plafond porté à 120 000 (filet), CLAUDE.md borné à part à 30 000
  avec coupe dite, et toute coupe globale journalisée (`Consigne système coupée…`).
- **Vue Forge** : `GovernanceEffectiveView.hostRuleTitles` = titres `## ` du `GOUVERNANCE.md` du poste
  lu en entier (l'extrait de 4 000 car. ne suffit pas : CAGIP = 18 766 car.).

### Cas d'erreur
| Situation | Résultat |
|---|---|
| `portee=COMPTE` avec type SKILL/GABARIT/AGENT | refus à l'agent : « portée COMPTE réservée aux règles » |
| Titre vide/> 120, contenu vide/> 4 000 | 400 (route) / refus à l'agent (outil) |
| Même contenu déjà en portée COMPTE | « Rien à changer » |
| Règle COMPTE modifiée entre proposition et [Appliquer] | 409 |
| Recopie d'une proposition non appliquée, ou non REGLE, ou déjà COMPTE | 409 |
| Section introuvable dans le fichier / la proposition | 404 « règle introuvable » |
| Poste injoignable (recopie Forge) | 503 « poste injoignable » |
| Règle ou proposition d'un autre utilisateur | 404 |
| Accès Forge absent | 403 (garde `AtelierAccessService`) |

## Critères d'acceptation
- [ ] CA1 — un compte avec une règle COMPTE la voit dans la consigne d'un sujet de poste, du terminal du poste et d'un projet hébergé.
- [ ] CA2 — une règle de poste de même titre (casse ignorée) l'emporte : la règle COMPTE n'est pas injectée.
- [ ] CA3 — deux constructions successives sans changement de règle produisent un préfixe système identique ; sans règle, la consigne est inchangée.
- [ ] CA4 — portée COMPTE proposée puis appliquée sans poste ; conflit si la règle a changé entre-temps.
- [ ] CA5 — recopie d'une règle POSTE appliquée vers COMPTE, poste éteint.
- [ ] CA6 — isolation A/B : B ne voit, ne liste, ne retire ni ne recopie les règles ou propositions de A ; la consigne de B n'a pas les règles de A.
- [ ] CA7 — purge à la suppression du compte.
- [ ] CA8 — le contexte Spring démarre (test d'intégration complet).
- [ ] CA9 — un CLAUDE.md très long est borné (coupe dite) et les règles du client le suivent toujours dans la consigne.

## Contraintes de validation
| Champ | Obligatoire | Longueur max | Format | Unicité | Normalisation |
|---|---|---|---|---|---|
| title | Oui | 120 | texte, sans retour à la ligne | (user_id, clé) | strip ; clé = minuscules, espaces réduits |
| content | Oui | 4 000 | markdown | Non | strip |
| bloc injecté | — | 8 000 | coupe dite | — | — |

## Périmètre — hors scope
- Relever la borne de 8 000 car. **par** `GOUVERNANCE.md` de poste (constat CAGIP signalé au PO).
- SKILL/GABARIT/AGENT en portée COMPTE.
- Écrans (SF-266-02).
- Écrire la règle dans les fichiers des postes (rien ne s'écrit chez le client).

## Technique
- Migration `266-01-account-governance-rules.xml` (table `account_governance_rules`, unique `(user_id, title_key)`), rollback = dropTable.
- `governance/account/` : `AccountGovernanceRule`, `…Repository`, `AccountGovernanceService`, `AccountGovernanceController`, `AccountGovernanceRuleView`.
- `GovernanceProposalService` : portée COMPTE (propose/apply), `promote`. `GovernanceProposalController` : `/promote`.
- `AtelierChatService` : bloc COMPTE, description de l'outil (enum + phrase). `GovernanceEffectiveService/View` : `hostRuleTitles`. `AccountService` : purge.

### Préoccupations transversales
- **Consigne / préfixe de cache (assimilé « Contexte tenant »)** : oui. Composants impactés :
  `AtelierChatService.buildSystemPrompt` (bloc système), définition de l'outil `gouvernance_proposer`
  (liste des outils = préfixe ; changera **une fois** au déploiement pour tous les comptes : une
  réécriture de cache par conversation, puis stable). Le bloc COMPTE est lu en base à chaque tour
  (1 requête indexée) ; contenu déterministe (tri par clé). Rien de volatil (pas de date, pas d'id).
- **Taille de la consigne** : `SYSTEM_MAX_CHARS` 40 000 → 120 000 et borne CLAUDE.md 30 000 — touche
  tous les tours dont la consigne dépassait 40 000 (ils reçoivent désormais CLAUDE.md et les règles du
  client, jusque-là coupés) : préfixe plus long (lu en cache ensuite), réécrit une fois au déploiement.
  Composants : `AtelierChatService.buildPrompt` seul (les sous-agents/délégations ont leur propre consigne).
- Auth / Principal : non (gardes existantes réutilisées). Plans/limites : non. Navigation : non.

## Plan de test
### Unitaires
- `AccountGovernanceServiceTest` : clé de titre, rendu du bloc (tri, exclusion par titre, borne), extraction de section, validations.
- `GovernanceProposalServiceTest` : COMPTE propose/apply/conflit/refus de type ; promote (nominal, non appliquée, section absente).
### Intégration
- `AccountGovernanceApiIntegrationTest` (contexte Spring complet) : CRUD, recopie depuis proposition, A/B (liste, retrait, recopie, propositions), purge.
- `AtelierChatService` : consigne d'un projet hébergé avec règle COMPTE ; exclusion par titre du poste ; stabilité à deux constructions.
### Isolation
- A/B dans le test d'intégration (CA6).

## Dépendances
- F-177 (propositions, injection GOUVERNANCE.md) — done. Aucune question ouverte impactée.
