# Mini-spec — [F-221 / SF-221-05] Les suites en split

## Identifiant
SF-221-05

## Feature parente
F-221 — Deux chantiers côte à côte : un fil par chantier, le terminal en split

## Statut
in-progress

## Date de création
2026-10-29

## Branche Git
`feat/SF-221-05-suites-en-split`

---

## Objectif
En split, chaque volet ne propose que les suites de **son** chantier : la colonne « hors chantier » disparaît, et la consigne du tour le sait. Hors split, rien ne change.

## Comportement attendu

### Cas nominal
- **L'écran dit qu'il est en split.** L'envoi d'un volet en split porte `split: true` en plus de `chantier` : volet droit, et volet gauche quand le split est ouvert. Le serveur retient « ce fil est montré dans un volet » jusqu'au prochain envoi du même fil sans `split`. Les précisions et les tours de suite en héritent.
- **La consigne du tour.** Elle est jointe au **message**, jamais au système (F-134, préfixe stable). Le fil du chantier N affiché en split reçoit, dans la note « [Fil du chantier N] » : « Écran partagé : ce volet n'affiche que les suites du chantier N ; propose uniquement des suites de ce chantier, pas de suite hors chantier ce tour. » La doctrine système des suites (F-209) est inchangée, à l'octet.
- **Côté serveur**, après rattachement (`ChantierAnchor`), les suites rangées en colonne `HORS` sont retirées pour un tour en split. Cela vaut pour les suites globales et pour celles des réponses par question (F-210). La règle « colonne Chantier jamais vide » (repli F-209) reste.
- **Côté écran**, `terminal-next-steps` reçoit `chantierOnly`. En split, il n'affiche que la colonne du chantier, y compris pour les tours relus d'avant le split.
- **Hors split** : aucune différence, sur les deux colonnes, la consigne ou le parseur.

### Cas d'erreur
| Situation | Comportement |
|-----------|--------------|
| `split: true` sans `chantier` | ignoré (le fil commun n'est jamais montré en volet) |
| Un ancien client sans `split` | comportement d'avant |
| Le modèle propose malgré tout une suite hors chantier en split | retirée côté serveur, absente à l'écran |

## Critères d'acceptation
- [ ] Un tour du fil 8 avec `split: true` : la note du message contient « Écran partagé », la consigne système est identique à celle d'un tour sans split, et les suites `HORS` sont retirées.
- [ ] Le même fil sans `split` (split refermé) : les deux colonnes reviennent.
- [ ] Le fil commun avec `split: true` : aucun effet.
- [ ] Écran : en split, une seule colonne (« Chantier N — … »). Hors split, deux colonnes comme avant.

## Périmètre

### Hors scope (explicite)
- Le worktree (SF-221-06).
- La doctrine F-209 elle-même (plafonds, ordre, prérequis) : inchangée.

## Valeurs initiales
- `split` absent = `false`.

## Contraintes de validation
- `split` : booléen facultatif.

## Technique

### Endpoint(s)
- `POST /chat` et `/chat/stream` : corps + `split`.

### Tables impactées
- Aucune.

### Migration Liquibase
- [x] Non applicable

### Composants
- Backend :
  - `AtelierChatRequest` : `split` ;
  - `AtelierChatService` : `setLaneSplit`, la note, et le filtre des suites ;
  - `ChantierAnchor.chantierOnly` (pur) ;
  - `Workspace.turnSplitView` (transitoire).
- Front :
  - `AtelierService.streamChat(…, split)` ;
  - `AtelierComponent` et `TerminalLanePaneComponent` passent `split` ;
  - `AtelierTerminalComponent` → `terminal-next-steps` `[chantierOnly]`.

## Plan de test

### Tests unitaires
- [ ] `ChantierAnchor.chantierOnly` : retire `HORS`, garde `CHANTIER` et les suites sans colonne.
- [ ] `AtelierChatService` :
  - fil 8 en split : note « Écran partagé » dans le message, consigne système identique, suites `HORS` retirées ;
  - fil 8 sans split : les deux colonnes ;
  - fil commun : sans effet.
- [ ] Karma :
  - `terminal-next-steps` `chantierOnly` : une colonne ;
  - `AtelierService.streamChat` : `split` dans le corps.

### Tests d'intégration
- [ ] Contrôleur : `split` lu dans le corps et transmis (`AtelierChatControllerSteerTest` ou équivalent).

### Isolation utilisateur
- [x] La marque est clefée `(user, sujet, fil)`, comme les autres clefs d'état du tour (SF-221-01).

## Préoccupations transversales
- Aucune (ni auth, ni tenant, ni limite, ni route). Préfixe stable : la consigne système est inchangée.

## Dépendances
- SF-221-01, SF-221-03 (mergées).

## Notes et décisions
- La marque « montré en split » vit dans la mémoire du pod, comme les autres clefs d'état du tour. Si un autre pod exécute le tour, il voit le fil sans split. C'est un repli sûr : deux colonnes, pas une erreur.
