# Mini-spec — F-155 / SF-155-07 — Le bilan dans le terminal

## Identifiant
`F-155 / SF-155-07` — feature parente `F-155`

## Objectif
Montrer le bilan **là où le geste a lieu** — dans le terminal, au « nouveau départ » — au lieu de le
garder en base pour un écran d'administration que personne n'ouvre.

## La demande
> PO, 2026-09-26 : *« Je pensais qu'on avait mis en place une fonctionnalité qui permettait […]
> lorsqu'on arrête une conversation et qu'on clique sur reprendre depuis le début […] d'afficher un
> diagnostic […]. Dans ma tête, ça avait été livré, mais […] rien ne s'est passé. »*

## Le constat : le moteur tourne, l'écran manque
Vérifié en production le 2026-09-26. Le bilan de la session KPMG **existe** :

| | |
|---|---|
| Gardé à | `2026-09-26 01:27:47` — l'instant du clic |
| Tours / coût | **113** / **152,58 €** |
| Origine | `AUTOMATIQUE` |
| Suggestions | `CACHE_FROID`, `OUTIL_DOMINANT`, `ECHECS_REPETES` |

**Trois trous, tous constatés dans le code :**

1. `AtelierThreadService.restart()` renvoie `AtelierResumeResponse.bilan`
   (`AUCUN`/`PROPOSE`/`AUTOMATIQUE`), mais l'interface `AtelierResume` côté Angular **ne déclare pas
   le champ**, et `restartThread()` n'ouvre qu'un bandeau générique. Seul écran : `/admin` → Bilans.
2. `SessionBilanTriggerService` appelle `store.keep(userId, workspaceId, null, …)` — le **nom du
   projet n'est jamais résolu**, d'où la colonne `workspace_name` vide.
3. Le nom du déclencheur seul ne suffirait pas : `AUTOMATIQUE` ne dit ni le coût, ni les
   suggestions. Il faut **le contenu**, pas une étiquette.

## L'arbitrage, tranché ici
**Le contenu voyage avec la réponse, pas un identifiant.** Un identifiant obligerait le terminal à
appeler `/admin/bilans/{id}` — un chemin d'administration depuis l'écran de travail — et surtout un
bilan `PROPOSE` **n'est pas gardé** : il n'a pas d'identifiant. Seul le contenu couvre les deux cas.

**Additif, jamais substitutif** : `bilan` (la chaîne) reste ; un `bilanReport` facultatif s'ajoute.
Les appelants existants ne voient aucune différence.

## Comportement attendu
1. Au **nouveau départ**, quand la fermeture décide `AUTOMATIQUE` ou `PROPOSE`, la réponse porte le
   **relevé** (tours, durée, coût, part de cache, appels d'outils, échecs) et les **suggestions**
   (conseil, mesure citée, gain).
2. Le terminal ouvre un **panneau** — pas un bandeau : trois suggestions ne tiennent pas dans une
   ligne de bandeau, et le bandeau disparaît tout seul.
3. `AUCUN` → comportement d'aujourd'hui, à l'identique. Un non-administrateur ne reçoit **rien**, et
   rien n'est même calculé.
4. Le **nom du projet** est résolu et enregistré avec le bilan.
5. Le bilan **ne doit jamais faire échouer le nouveau départ** — garantie déjà tenue par le
   `try/catch` de `SessionBilanTriggerService`, à préserver.

| Cas | Comportement |
|---|---|
| Admin, session coûteuse (≥ 2 € ou ≥ 20 tours) | panneau ouvert, relevé + suggestions |
| Admin, session modeste avec suggestions | panneau ouvert (`PROPOSE`), le bilan **n'est pas gardé** |
| Admin, session sans rien à signaler | rien — `AUCUN`, comme aujourd'hui |
| Non-administrateur | rien, et **aucun calcul** |
| Le calcul du bilan échoue | le nouveau départ **réussit quand même** |

## Critères d'acceptation
- [ ] La réponse du nouveau départ porte le relevé et les suggestions quand le bilan existe.
- [ ] `AUCUN` → `bilanReport` absent, et l'écran se comporte exactement comme avant.
- [ ] Un non-administrateur ne reçoit pas de `bilanReport`.
- [ ] Le panneau affiche coût, tours, durée, part de cache, et **chaque suggestion avec sa mesure**.
- [ ] Le nom du projet est enregistré dans `session_bilans.workspace_name`.
- [ ] Un échec du bilan ne fait pas échouer le nouveau départ (test de non-régression).
- [ ] **ISOLATION** : `requireOwned` reste en premier ; le relevé est lu pour `(userId, workspaceId)`.

## Plan de test minimal
**Unitaires** — `SessionBilanTriggerService` : le nom du projet est transmis à `keep` ·
`AtelierThreadService` : `bilanReport` présent pour `AUTOMATIQUE`/`PROPOSE`, absent pour `AUCUN` ·
le mapping relevé → DTO ne perd aucun chiffre.
**Intégration** — `POST /workspaces/{id}/chat/restart` rend le rapport pour un admin, ne le rend pas
pour un non-admin ; un bilan qui lève n'empêche pas le nouveau départ (200 + `AUCUN`).
**Isolation** — un projet d'autrui rend 404 avant tout calcul de bilan.
**Écran** — le panneau s'ouvre sur `AUTOMATIQUE`, reste fermé sur `AUCUN`, et rend les trois
suggestions avec leur mesure.

## Technique
| Élément | Changement |
|---|---|
| `AtelierResumeResponse` | `bilanReport` **facultatif** (record imbriqué) — additif |
| `SessionBilanTriggerService` | `Decision` déjà porteuse du relevé et du verdict ; **résoudre le nom du projet** et le passer à `keep` |
| `AtelierThreadService` | projeter `Decision` en `bilanReport` |
| `atelier.models.ts` | `AtelierResume.bilanReport` |
| `session-bilan-panel.component.ts` | le panneau, patron `terminal-actions-panel` |

**Aucune migration** : `session_bilans.workspace_name` existe déjà, elle n'était pas alimentée.

## Préoccupations transversales
Aucune. Ni auth, ni contexte tenant, ni plans/limites, ni routing : la garde d'administration et le
filtre `user_id` sont **inchangés**, et aucune route n'est ajoutée.

## Hors périmètre
Réécrire les bilans **déjà gardés** (celui du 26/09 porte la part de cache fausse d'avant SF-155-06,
et ne sera pas corrigé) · le déclenchement du diagnostic profond F-156 depuis le terminal
(SF-155-05 le porte déjà, côté admin) · l'historique des bilans dans le terminal (`/admin` le rend).
