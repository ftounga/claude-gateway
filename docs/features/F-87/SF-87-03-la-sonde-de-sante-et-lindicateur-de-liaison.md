# Mini-spec — F-87 / SF-87-03 — La sonde de santé et l'indicateur de liaison

## Identifiant

`F-87 / SF-87-03`

## Feature parente

`F-87` — Le volet Teams : la liaison

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-87-03-sonde-et-indicateur`

---

## Objectif

**Le produit doit s'apercevoir qu'il ne sait plus lire Teams avant l'utilisateur** : une sonde jouée
au rattachement confronte l'hypothèse de l'adaptateur au réel, et son verdict se lit dans la barre du
terminal — un état, sans aucune action.

---

## Comportement attendu

### Cas nominal — la sonde

1. Le runner se rattache au navigateur (SF-87-02) et écoute ce que l'onglet Teams reçoit.
2. Il **fait défiler une fois** — le seul geste — pour que la page demande quelque chose, puis
   récolte les réponses observées.
3. Chaque réponse est confrontée à l'adaptateur : combien des champs attendus sont là.
4. Le verdict agrège les réponses, et il a **trois issues** :

| Issue | Ce qu'on fait | Ce qu'on écrit |
|---|---|---|
| **tout reconnu** | on travaille | « Teams est lu normalement. » |
| **partiellement reconnu** | on travaille **et on le dit**, à chaque résultat | « Teams a changé : … champs reconnus sur … Non reconnus : … » |
| **rien reconnu** | on **refuse** | « Teams a changé : le produit ne sait plus lire ses réponses. Version observée : … » |

5. **Une quatrième situation existe et ne doit pas être confondue avec la troisième** : l'onglet
   n'a rien reçu pendant la sonde (page inactive). On dit alors que la liaison est établie et que
   **la forme sera vérifiée à la première lecture** — crier « Teams a changé » parce que personne
   n'écrivait serait le plus sûr moyen de rendre l'alerte inaudible.

### Cas nominal — l'outil `teams_status`

`teams_status` rend un objet JSON portant : l'**état de liaison**, la **phrase** à lire, le
**remède** s'il y en a un, la **santé** (verdict, champs reconnus, version observée), le navigateur
observé, et l'annonce de premier usage (D3). Il sert **deux** consommateurs : l'indicateur de l'écran
(cette subfeature) et l'agent (F-88, qui le recevra dans son catalogue).

### Cas nominal — l'indicateur

Dans la barre du terminal, **trois états, aucun bouton** :

| État | Registre de couleur | Libellé écrit |
|---|---|---|
| relié | **aucun** — encre de la barre (charte §11) | « Teams relié » |
| navigateur non détecté | §5 `badge--neutral` | « Teams : navigateur non détecté » |
| Teams a changé | §5 « En attente » (charte §12) | « Teams a changé » |

**Aucun registre de couleur nouveau.** L'infobulle porte le détail et le remède ; elle ne porte
aucune action — la commande à taper y est écrite, elle n'est pas exécutée par un bouton.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Projet inexistant ou appartenant à quelqu'un d'autre | indistinguables : introuvable | 404 |
| Aucun poste rattaché au projet | état « navigateur non détecté », phrase disant qu'aucune machine n'est reliée | 200 |
| Runner non connecté | idem, avec la phrase qui dit de lancer le runner | 200 |
| Le runner refuse (`--no-teams`) | état « navigateur non détecté » + la raison écrite | 200 |
| Navigateur absent | état « navigateur non détecté » + **la ligne de commande** (SF-87-02) | 200 |
| Rien n'est reconnu | état « Teams a changé » + version observée | 200 |
| Le runner ne répond pas à temps | état « navigateur non détecté » + « la machine n'a pas répondu » | 200 |

**Pourquoi 200 dans tous ces cas** : l'indicateur **dit un état**. Un 503 ferait clignoter une
erreur d'application là où il n'y a qu'un navigateur à lancer.

---

## Critères d'acceptation

- [ ] La sonde rend **trois** verdicts distincts, et un quatrième état « rien observé » qui n'est
      **pas** un refus.
- [ ] Un refus **nomme** ce qui a changé, la version d'interface observée et l'adaptateur utilisé.
- [ ] Un verdict partiel **écrit** ce qui n'est plus reconnu — et laisse travailler.
- [ ] `teams_status` est routé par le runner et **déclaré** dans la trame `ready`
      (capacité `teams`).
- [ ] `--no-teams` retire la capacité et fait refuser l'outil, en disant pourquoi.
- [ ] `--teams-port` et `CLAUDE_TEAMS_DEBUG_PORT` choisissent le port.
- [ ] `GET /workspaces/{id}/teams/link` rend l'état ; un projet d'un autre utilisateur rend 404.
- [ ] L'indicateur écrit **toujours** son libellé, n'ajoute **aucun** registre de couleur, et ne
      porte **aucun** bouton.
- [ ] L'infobulle de l'état « navigateur non détecté » contient la commande à lancer.
- [ ] `docs/DESIGN_SYSTEM.md` gagne une section §14 qui dit quels registres **existants** sont
      employés.

---

## Périmètre

### Hors scope (explicite)

- Le catalogue d'outils de lecture (F-88) — seul `teams_status` est livré ici, parce que la sonde et
  l'indicateur en dépendent.
- Le terminal Teams, ses blocs, son droit (F-89).
- Toute action depuis l'indicateur : il dit un état, il ne répare rien.

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|---|---|---|
| `state` | Oui | `LINKED`, `BROWSER_NOT_DETECTED`, `TEAMS_CHANGED` — liste close |
| durée de la sonde | Oui | 3 000 ms au plus |
| gestes de défilement de la sonde | Oui | 1 |
| délai de l'appel `teams_status` | Oui | 20 000 ms |
| `label` de l'indicateur | Oui | toujours écrit, non masquable |

---

## Technique

### Endpoint

| Méthode | URL | Auth | Rôle |
|---|---|---|---|
| GET | `/workspaces/{id}/teams/link` | Oui | utilisateur propriétaire du projet |

### Tables impactées

Aucune. Aucune migration Liquibase : la liaison est un **état vivant**, pas une donnée.

### Composants Angular

- `TeamsLinkBadgeComponent` (`shared/teams-link-badge/`) — l'indicateur, sans action.
- `TeamsLinkService` (`atelier/teams/`) — lit l'endpoint.
- `AtelierTerminalComponent` — reçoit l'état en entrée et le pose dans la barre.

---

## Plan de test

### Tests unitaires

- [ ] `TeamsProbeTest` — les trois verdicts + « rien observé » ; le refus nomme la version.
- [ ] `TeamsToolsTest` — JSON rendu, navigateur absent → remède complet ; `--no-teams` → refus.
- [ ] `ToolRouterTest` — `teams_status` est routé ; capacité `teams` annoncée.
- [ ] `RunnerConfigTest` — `--teams-port`, `--no-teams`.
- [ ] `TeamsLinkServiceTest` (backend) — traduction des issues du runner en état d'écran.
- [ ] `TeamsLinkBadgeComponent` (front) — libellé toujours écrit, aucun registre nouveau, aucun
      bouton, infobulle porteuse du remède.

### Tests d'intégration

- [ ] `GET /workspaces/{id}/teams/link` → 200 avec état, poste absent.
- [ ] `GET /workspaces/{id}/teams/link` → **404** pour le projet d'un autre utilisateur.

### Isolation utilisateur

- [x] Applicable — le projet est relu par `requireOwned(userId, id)`, jamais depuis un paramètre
      client ; test d'intégration dédié.

---

## Dépendances

- `SF-87-01` — done. `SF-87-02` — done.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants vérifiés |
|---|---|---|
| Auth / Principal | **Non** | aucun changement ; l'endpoint utilise `CurrentUser` comme les autres |
| Contexte tenant | **Non** | `requireOwned` inchangé, réutilisé tel quel |
| Plans / limites | **Non** | aucun quota, aucun gate — le droit d'accès Teams est F-89 (D5) |
| Navigation / routing | **Non** | aucune route Angular nouvelle ; l'indicateur vit dans une barre existante |

---

## Notes et décisions

**A8 — La sonde observe, elle ne navigue pas.** Le cadrage dit « ouvrir une conversation connue ».
Ouvrir demanderait `Page.navigate`, que la liste blanche de SF-87-02 refuse — nous lisons, nous ne
pilotons pas, et déplacer l'onglet de l'utilisateur sous ses yeux serait une intrusion. La sonde fait
donc ce qui a le même effet sans les inconvénients : elle **écoute l'onglet ouvert** et le fait
défiler d'un geste. Alternative écartée : autoriser la navigation pour la seule sonde — elle aurait
ouvert une exception dans la garde la plus importante du volet. Réversible.

**A9 — « Rien observé » n'est pas « rien reconnu ».** Une alerte qui se déclenche quand personne
n'écrit devient une alerte qu'on ignore. La sonde distingue donc l'absence de trafic du non-respect
du contrat, et ne parle de changement que lorsqu'elle a **vu** quelque chose qu'elle n'a pas compris.

**A10 — L'indicateur ne porte aucune action, et l'infobulle porte la commande.** Un bouton
« relancer Chrome » ne peut pas tenir sa promesse : le navigateur doit être lancé par l'utilisateur,
avec son profil. Écrire la commande là où il regarde est le seul remède honnête.
