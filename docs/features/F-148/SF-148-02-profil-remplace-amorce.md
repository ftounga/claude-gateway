# Mini-spec — F-148 / SF-148-02 — Un profil métier actif *remplace* l'amorce de rôle

## Identifiant

`F-148 / SF-148-02`

## Feature parente

`F-148` — Performance du raisonnement (affinages)

## Statut

`ready`

## Date de création

2026-09-23

## Branche Git

`feat/SF-148-02-profil-remplace-amorce`

---

## Objectif

> En une phrase : quand un profil métier est actif sur le poste du projet, sa **phrase de rôle**
> remplace l'amorce générique « Tu es un assistant de développement… » en tête de la consigne système
> (au lieu de s'y ajouter plus bas), sans rien desserrer d'autre ; sans profil actif, comportement
> inchangé à l'octet près.

---

## Constat / existant

- `GovernanceProfileSeeder` sème 4 profils (`profil-architecte`, `profil-infrastructure-production`,
  `profil-securite`, `profil-donnees`) comme des `GovernancePackage` (slug préfixé `profil-`). Chacun
  ouvre son texte par une **phrase de rôle** (« Tu interviens comme **architecte**… », etc.).
- Un profil actif est une activation ordinaire : ses règles rejoignent le bloc « Règles de gouvernance
  actives » via `GovernanceRulesProvider.rulesFor` (`AtelierChatService.java:4389-4392`), donc **après**
  l'amorce « Tu es un assistant de développement… » (`AtelierChatService.java:4256`/`:4264`) → l'effet
  du profil est **dilué** : le mauvais cadre est lu en premier.
- Garde-fou F-138 : un profil **ne desserre jamais** une règle de plateforme ni la discipline
  d'investigation (F-119). Ce garde-fou est **maintenu** : on ne touche qu'à la phrase de rôle ; toutes
  les doctrines (F-119/120/121/125/126/141) et le reste du préfixe restent en place et **après** le
  rôle, comme aujourd'hui.

---

## Comportement attendu

### Cas nominal

- **Profil actif** sur le poste du projet → la phrase de rôle du profil (sa 1re phrase) remplace
  l'amorce générique en tête de consigne. La suite de l'amorce opérationnelle (guidage d'exploration
  selon l'interpréteur en cible RUNNER, ou « Utilise les outils fournis (list_files…) » en cible
  hébergée, puis `read_file`/`write_file`, « ne suppose pas », « résume ») reste **inchangée**.
- **Aucun profil actif** → amorce générique inchangée (comportement d'avant SF-148-02, à l'octet).
- **Plusieurs profils actifs** → le **premier activé** (ordre `createdAt`) fournit la phrase de rôle.
- Le reste du bloc « Règles de gouvernance actives » (dont le texte complet du profil) est **inchangé** :
  le profil reste listé dans ce bloc comme aujourd'hui.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Lecture des activations/paquets en panne | Repli passant : amorce générique, tour non raté |
| Profil actif dont les règles sont vides/blanches | Ignoré comme source de rôle → amorce générique |
| Paquet actif non-profil (slug sans préfixe `profil-`) | Jamais retenu comme rôle → amorce générique |
| `userId`/`workspaceId` nuls | Aucune lecture, amorce générique |

---

## Critères d'acceptation

- [ ] Profil actif → la consigne système commence par la phrase de rôle du profil, et **ne contient
      plus** l'amorce « Tu es un assistant de développement ».
- [ ] Profil actif → l'amorce opérationnelle (`read_file`/`write_file`, outils selon la cible) et la
      discipline d'investigation F-119 (« Vérifie avant d'affirmer ») restent présentes (garde-fou F-138).
- [ ] Aucun profil actif → consigne inchangée (amorce générique présente).
- [ ] Paquet actif non-profil → n'altère pas l'amorce (amorce générique présente).
- [ ] `activeProfileRole` est demandé pour le couple `(userId, workspaceId)` du tour et lui seul
      (isolation), avec repli passant si la source échoue.
- [ ] Extraction de la phrase de rôle : première phrase du texte du profil, bornée, marqueurs de gras
      Markdown retirés.

---

## Périmètre

### Hors scope (explicite)

- L'activation/test **réel** d'un profil sur le poste CAGIP (runner) : **opérationnel**, hors code.
- Toute modification du contenu des 4 profils, du semis, ou du bloc « Règles de gouvernance actives ».
- Tout classement des profils par pertinence à la question (interdit : casserait le cache).
- Aucun changement d'infra, aucun composant cluster, aucune migration, pas de mise à jour runner.

---

## Contraintes de validation

| Champ | Valeur | Règle |
|-------|--------|-------|
| Préfixe de slug profil | `profil-` | Constante partagée (`GovernanceProfileSeeder.PROFILE_SLUG_PREFIX`) |
| Phrase de rôle | 1re phrase du texte, `**` retirés, espaces normalisés | Bornée à `PROFILE_ROLE_MAX_CHARS` |

---

## Technique

### Composants impactés

| Composant | Opération |
|-----------|-----------|
| `ProjectRulesSource` | Nouvelle méthode **`default`** `activeProfileRole(userId, workspaceId)` → `null` par défaut (interface fonctionnelle préservée : SAM inchangé) |
| `GovernanceRulesProvider` | Implémente `activeProfileRole` : 1er profil actif → phrase de rôle extraite de ses règles ; repli passant |
| `GovernanceProfileSeeder` | Expose `PROFILE_SLUG_PREFIX` (`profil-`) ; slugs inchangés |
| `AtelierChatService.buildSystemPrompt` | Calcule la phrase de rôle active et la substitue à l'amorce générique (les deux cibles) ; le reste inchangé |

### Endpoint(s) / Tables / Migration

Aucun endpoint, aucune table, aucune migration Liquibase.

### Composants Angular

Aucun.

---

## Préoccupations transversales

- **Auth / Principal** : inchangé — aucune nouvelle résolution d'auth.
- **Contexte tenant** : `activeProfileRole` lit par `(userId, workspaceId)` → `activeOnWorkspace`, qui
  résout le poste par `hostScope.hostOf` (isolation `user_id` + `host_id`), exactement comme `rulesFor`.
  Aucun nouveau moyen de résoudre le tenant. Composants qui résolvent le tenant : inchangés
  (`GovernanceActivationService`, `GovernanceHostScope`).
- **Plans / limites** : non touché.
- **Navigation / routing** : non touché.

---

## Plan de test

### Tests unitaires

- [ ] `GovernanceRulesProviderTest` — profil actif → `activeProfileRole` rend la 1re phrase du profil.
- [ ] `GovernanceRulesProviderTest` — aucun paquet actif → `null` ; que des paquets non-profil → `null`.
- [ ] `GovernanceRulesProviderTest` — profil sans règles → `null` ; source en panne → `null`.
- [ ] `GovernanceRulesProviderTest` — extraction : `**gras**` retiré, bornée.
- [ ] `AtelierChatServiceGovernanceRulesTest` — profil actif → amorce remplacée, « assistant de
      développement » absent, amorce opérationnelle + F-119 présentes (garde-fou F-138).
- [ ] `AtelierChatServiceGovernanceRulesTest` — sans profil → amorce générique présente (non-régression).

### Tests d'intégration

- Non applicable : composition de consigne système, aucun endpoint. Observé via la requête reçue par le
  fournisseur (stub), comme les tests existants de `buildSystemPrompt`.

### Isolation workspace / tenant

- [x] Testée : `activeProfileRole` demandé pour le couple `(userId, workspaceId)` du tour et lui seul
      (même chemin d'isolation que `rulesFor`).

---

## Dépendances

### Subfeatures bloquantes

- Aucune (SF-148-01/04/09 déjà livrées ; indépendantes).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Cache de prompt (F-134) préservé** : la phrase de rôle fait partie du **préfixe stable**. Le profil
  actif est **stable par session** (une activation ne change pas d'un tour à l'autre), donc la
  substitution n'introduit **aucune volatilité par tour** : à profil constant, la consigne est
  byte-identique d'un tour au suivant.
- **Garde-fou F-138** : seule la phrase de rôle est remplacée ; discipline F-119, doctrines et règles de
  plateforme restent après le rôle, inchangées. Un profil ne peut donc rien desserrer.
- **`ProjectRulesSource` reste fonctionnelle** : `activeProfileRole` est une méthode `default`, elle
  n'ajoute pas de méthode abstraite ; les lambdas et `NONE` existants continuent de compiler et rendent
  l'amorce générique.
- **Provider-First / Gateway-First** : aucune logique de « moteur IA » ; on ne fait que composer un
  préfixe. Aucune dépendance directe à Anthropic.
