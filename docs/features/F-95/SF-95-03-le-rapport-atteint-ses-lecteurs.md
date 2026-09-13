# Mini-spec — F-95 / SF-95-03 — Le rapport atteint ses deux lecteurs

## Identifiant

`F-95 / SF-95-03`

## Feature parente

`F-95` — L'intégrité du poste

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-95-03-le-rapport-atteint-ses-lecteurs`

---

## Objectif

Remettre le rapport d'intégrité (SF-95-02) à ses **deux** lecteurs : le **modèle**, par un contrôle
de fin de tour qui **bloque** sur les erreurs ; et l'**humain**, par la carte du poste, où erreurs et
**avertissements** se voient sans ouvrir un terminal.

---

## Pourquoi deux lecteurs, et pas un

La feature exige **deux niveaux distincts** : des erreurs qui bloquent, des avertissements qui
informent. Un verdict de F-50 ne connaît que deux issues — passer, ou bloquer. **Un avertissement
seul n'a donc, par construction, aucun canal** : il ne peut pas bloquer, et rien d'autre ne le
porterait.

C'est ce qui décide l'écran. Dans le prompt d'origine, `infra-doctor` **écrit son rapport dans la
console** : erreurs et avertissements y sont lus ensemble. Ici, la console de la carte, c'est
**l'écran du poste** — celui où F-92 a déjà rendu la carte visible. Sans lui, la moitié
informative de la feature serait écrite et jamais lue.

---

## Comportement attendu

### 1. Le contrôle `integrite-du-poste` (fin de tour)

| Condition | Comportement |
|---|---|
| le tour n'a **rien écrit** | **passe** — rien n'a pu changer sur le poste, et une inspection coûte des allers-retours vers la machine |
| ces mêmes écritures ont **déjà** été inspectées (mémoire, 30 min) | **passe** — on ne repose pas la même question |
| rapport **silencieux** (machine muette, poste non gouverné) | **passe** |
| rapport **sans erreur** (avertissements seuls, ou rien) | **passe** — un avertissement n'a jamais bloqué personne, et l'écran le porte |
| rapport **avec erreur** | **bloque**, avec le texte du rapport : les erreurs numérotées, puis les avertissements sous leur propre intitulé |
| l'inspection **lève** | **passe** — un contrôle cassé ne condamne pas le projet de quelqu'un (F-50, décision D2) |

Le paquet **`savoir-durable`** cite ce contrôle **après** les contrôles gratuits et **avant** le juge
indépendant : le premier blocage l'emporte (F-50), donc l'ordre est un garde-fou de dépense — on ne
paie un appel au fournisseur que si la forme et l'intégrité sont déjà bonnes.

### 2. L'endpoint `GET /governance/hosts/{hostRef}/integrite`

Rend un `GovernanceIntegriteView` : le poste, s'il a été **inspecté**, et ses constats **séparés par
niveau**, chacun avec son identifiant de règle, sa cible et son message porteur de geste.

| Situation | Réponse |
|---|---|
| poste possédé, inspection faite | 200, `inspected: true`, les deux listes |
| poste possédé, rien à inspecter | 200, `inspected: false`, listes vides — **jamais** « tout va bien » |
| poste inconnu, ou d'un autre utilisateur | 404 (jamais 403 : un 403 apprendrait qu'un poste existe sous cet identifiant) |
| accès Forge absent | 402/403 selon `AtelierAccessService`, comme toutes les routes voisines |

### 3. L'écran

Dans la section **Carte du poste** (F-92 / SF-92-03), sous le gain : un bloc **Intégrité**.

- il n'apparaît **que s'il a quelque chose à dire** — un « aucune erreur » affiché en permanence
  deviendrait invisible en trois jours, comme le « +0 » écarté par F-93 ;
- les **erreurs** d'abord, puis les **avertissements**, sous deux intitulés distincts et jamais
  mêlés ;
- le message du serveur est **repris tel quel** : il porte déjà son action corrective, et le
  réécrire ici le ferait diverger au premier correctif ;
- **aucun registre de couleur de plus** (règle du design system, et doctrine explicite de cet
  écran) : l'**icône** porte le sens — `error_outline` / `info_outline` —, l'encre reste celle du
  texte ;
- la lecture se fait **une fois par page**, **hors** du sondage de 15 s, et seulement pour un poste
  **connecté** — même règle que la carte, et pour la même raison : une inspection, ce sont des
  allers-retours sur la machine d'un client.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| poste non connecté | aucun appel ; le bloc n'apparaît pas (la section dit déjà de lancer le runner) |
| l'appel échoue | **silencieux** — le bloc n'apparaît pas ; un rouge ici enverrait chercher au mauvais endroit (même choix que la carte) |
| rapport non inspecté | le bloc n'apparaît pas |
| le tour n'a rien écrit | aucun appel runner, aucune inspection |
| l'inspection lève dans le contrôle | le tour se termine normalement |

---

## Critères d'acceptation

1. Le contrôle porte l'identifiant `integrite-du-poste`, se branche sur `END_OF_TURN`, et le
   registre le connaît.
2. Un tour sans écriture ne déclenche **aucune** inspection.
3. Deux passages avec les mêmes écritures ne déclenchent **qu'une** inspection.
4. Un rapport sans erreur ne bloque **jamais**, même s'il porte des avertissements.
5. Un rapport avec erreur bloque, et le texte rendu porte les erreurs **puis** les avertissements
   sous deux intitulés distincts.
6. Une inspection qui lève laisse le tour se terminer.
7. Le paquet `savoir-durable` cite le contrôle, **avant** `juge-independant`.
8. `GET /governance/hosts/{ref}/integrite` rend les constats séparés par niveau ; un poste d'un
   autre utilisateur rend **404**.
9. L'écran affiche les deux niveaux sous des intitulés distincts, n'affiche rien quand il n'y a rien
   à dire, et n'appelle pas pour un poste non connecté.
10. Aucune couleur nouvelle n'est introduite ; le sens passe par l'icône.
11. `mvn test` et `npm test` verts.

---

## Plan de test minimal

### Unitaires — backend

- `IntegritePosteControlTest` : identifiant et point d'accroche ; tour sans écriture → aucune
  inspection ; mémoire → une seule inspection pour deux passages ; avertissements seuls → passe ;
  erreur → bloque, et le message porte les deux intitulés ; inspection qui lève → passe.
- `IntegriteMemoTest` : inclusion des chemins, expiration, isolation par `(userId, workspaceId)`.
- `GovernancePackageSeederTest` (existant) : le paquet cite les **cinq** contrôles dans l'ordre.

### Intégration — backend

- `GovernanceIntegriteApiIntegrationTest` : 200 sur un poste possédé (rapport non inspecté, runner
  absent) ; **404** sur le poste d'un autre utilisateur ; 404 sur un poste inconnu.

### Frontend

- `postes.component.spec.ts` : le bloc n'apparaît pas sans constat ; il affiche erreurs et
  avertissements séparément ; aucun appel pour un poste non connecté ; un échec d'appel reste
  silencieux.

### Isolation utilisateur

- L'endpoint passe par `GovernanceHostScope.require` (`requireOwned`) ; test dédié sur le poste d'un
  autre utilisateur.
- Le contrôle reçoit `(userId, workspaceId)` déjà vérifiés par la boucle (F-50), et l'inspection
  refait la résolution par `GovernanceHostScope`.

---

## Contraintes de validation

| Champ | Contrainte | Valeur |
|---|---|---|
| identifiant du contrôle | minuscules et tirets, **immuable** | `integrite-du-poste` |
| durée de la mémoire d'inspection | bornée | 30 min |
| entrées de la mémoire | bornées (LRU) | 500 |
| chemins retenus par entrée | bornés | 200 |
| constats rendus par l'endpoint | bornés | 20 par niveau |
| longueur du blocage | bornée par F-50 | 2 000 caractères |

---

## Tables / endpoints / composants impactés

- **Tables** : aucune. **Migration** : aucune.
- **Endpoint** : `GET /governance/hosts/{hostRef}/integrite` (nouveau).
- **Backend** : `IntegritePosteControl`, `IntegriteMemo`, `GovernanceIntegriteView` /
  `GovernanceIntegriteConstatView` (DTO), `GovernanceHostController` (une route),
  `GovernancePackageSeeder` (un identifiant de plus), `GOUVERNANCE.md` (le tableau des contrôles).
- **Frontend** : `governance.models.ts`, `governance.service.ts`, `postes.component.ts` / `.html` /
  `.scss`.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | non | la nouvelle route suit exactement le patron des routes voisines de `GovernanceHostController` (`atelierAccess.requireAccess()` puis `currentUser.requireId()`), aucun nouveau Principal |
| **Contexte tenant** | **oui** | un seul chemin de résolution, celui qui existe : `GovernanceHostScope.require` pour la route, `GovernanceHostScope.hostOf` pour le contrôle. Aucun nouveau moyen de résoudre le poste n'est introduit |
| Plans / limites | non | aucun quota lu ni modifié |
| **Navigation / routing** | **oui, côté API seulement** | une route de lecture ajoutée sous `/governance/hosts/{hostRef}` ; aucune route Angular nouvelle, aucun guard touché, aucune redirection |

**Un contrôle de plus s'exécute en fin de tour** sur les postes qui ont activé `savoir-durable` :
c'est le but, et le coût est borné par « seulement si le tour a écrit », par la mémoire de 30
minutes, et par le budget d'appels de SF-95-02.

---

## Hors périmètre

- Corriger quoi que ce soit automatiquement : l'intégrité constate et dit le geste, elle ne répare
  pas.
- Un écran d'administration des contrôles, ou un réglage pour débrayer celui-ci : F-75 a tranché
  qu'il n'y a **aucune dérogation par dossier**, et le débrayage d'un contrôle se fait en retirant le
  paquet.
- Les contrôles du prompt qui supposent l'ossature personnelle de l'auteur, et les cinq scripts.
