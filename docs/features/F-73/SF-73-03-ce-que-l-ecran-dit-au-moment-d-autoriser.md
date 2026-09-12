# Mini-spec — F-73 / SF-73-03 — Ce que l'écran dit au moment d'autoriser

---

## Identifiant

`F-73 / SF-73-03`

## Feature parente

`F-73` — Le runner n'est plus confiné

## Statut

`done` — mergée le 2026-09-12 (PR #391)

## Date de création

2026-09-12

## Branche Git

`feat/SF-73-03-portee-dite-a-l-autorisation`

---

## Objectif

> Que l'invite d'autorisation dise **ce qu'autoriser veut dire** : la commande s'exécutera sur cette
> machine, avec les droits du compte qui a lancé le runner, **sans restriction de dossier** — la
> seule chose qui reste entre l'utilisateur et une fuite de secret client.

---

## Déclencheur

Cadrage F-73, D5 et D6. Puisque plus rien ne s'interpose (SF-73-01), ce que l'utilisateur **lit
avant d'autoriser** est la garde. Le ton est **factuel** : c'est sa machine, il a lancé le runner
lui-même, et l'invite n'a pas à le faire sursauter — elle a à l'informer.

---

## Comportement attendu

### Cas nominal

**(1) Une ligne de portée, dans l'invite.** Le bloc `.terminal-ask` (F-33 / SF-33-03) gagne une
mention, placée **sous la commande** et **au-dessus des boutons**, là où le regard passe avant le
clic :

> **S'exécute sur cette machine, avec les droits de votre compte, dans n'importe quel dossier.**
> Ce runner n'est pas limité au dossier du projet : une commande peut lire vos fichiers personnels et
> ceux de vos autres clients.

**(2) La mention de cible est exacte.** Elle n'apparaît **que** pour un projet en cible
d'exécution `RUNNER`. En cible `SANDBOX`, rien n'est ajouté : le bac à sable est jetable et ne
touche pas la machine — y écrire la même phrase serait faux.

**(3) L'élévation reste dite là où elle l'était.** La ligne « Cette machine tourne en
administrateur » (F-38 / SF-38-18) est **conservée telle quelle** et affichée **sous** la nouvelle
mention : la portée d'abord (elle vaut toujours), les droits ensuite (ils varient).

**(4) Aucun geste nouveau.** Boutons « Autoriser », « Tout autoriser pour ce message », « Refuser »
et son motif : **inchangés**. Le compte à rebours (F-47 / SF-47-02) et la peinture immédiate
(SF-47-01) : **inchangés**. On ajoute une phrase, on ne rajoute pas une étape.

**(5) Accessibilité.** La mention vit **dans** le `role="alertdialog"` existant : elle est donc lue
par un lecteur d'écran au moment où l'invite est annoncée, sans `aria-live` supplémentaire qui la
ferait relire à chaque tick du compte à rebours.

**(6) Design system.** Aucune couleur nouvelle (cadrage, §registres) : le bloc porte déjà l'orange
de charte `--cg-orange-2` et le fond `--cg-navy-2`. La mention emprunte la couleur de texte du bloc
(`--cg-surface`) pour sa première phrase, à `85 %` d'opacité pour la seconde ; icône Material
`travel_explore`, `18px`, comme les autres icônes de l'invite. Espacements multiples de 4 px.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Cible d'exécution inconnue / absente dans le détail du projet | Rien n'est affiché — on n'affirme pas une portée qu'on ne connaît pas |
| Détail du projet non encore chargé quand l'invite arrive | Rien n'est affiché ; la mention apparaît au chargement, sans faire sauter la mise en page (elle est **au-dessus** des boutons, pas entre eux) |
| Projet en cible `SANDBOX` | Aucune mention (2) |

---

## Critères d'acceptation

1. Invite affichée sur un projet en cible `RUNNER` → la mention de portée est présente, **avant** les
   boutons dans l'ordre du DOM.
2. Invite affichée sur un projet en cible `SANDBOX` → **aucune** mention de portée.
3. Sans invite en attente, la mention n'est nulle part dans le DOM.
4. La ligne « administrateur » reste affichée quand `runnerElevated` est vrai, **après** la mention
   de portée.
5. Les trois boutons et le champ de motif conservent leur libellé, leur ordre et leur comportement.
6. La mention est **à l'intérieur** de l'élément `role="alertdialog"`.
7. Aucune couleur hors `DESIGN_SYSTEM.md` dans le SCSS ajouté (variables `--cg-*` uniquement).
8. `npm test` (frontend) vert, avec un test qui **aurait vu la mention manquer** (présence pour
   `RUNNER`, absence pour `SANDBOX`).

---

## Périmètre

### Hors scope (explicite)

- Une confirmation à la **lecture** d'un fichier sensible (risque assumé, cadrage D8).
- Un écran de réglages dédié, une modale, un « j'ai compris » à cocher : ce serait une étape de plus,
  pas une information de plus.
- La déclaration au **démarrage du runner** : c'est SF-73-01.
- Le texte du dialogue d'appairage et celui de l'accueil de la Forge (F-68), inchangés.

---

## Valeurs initiales

Aucune donnée. Texte figé dans le gabarit du composant.

---

## Contraintes de validation

Aucune saisie utilisateur ajoutée.

---

## Technique

### Endpoint(s)

Aucun. La cible d'exécution est déjà portée par `WorkspaceDetailResponse.executionTarget`, déjà lue
par l'écran de l'Atelier.

### Tables impactées

Aucune.

### Migration Liquibase

Aucune.

### Composants Angular

| Fichier | Changement |
|---|---|
| `atelier/terminal/atelier-terminal.component.html` | mention ajoutée dans `.terminal-ask`, sous la commande |
| `atelier/terminal/atelier-terminal.component.ts` | accesseur `runnerScope` — l'entrée `executionTarget` **existait déjà** (F-38 / SF-38-05), aucune entrée nouvelle |
| `atelier/terminal/atelier-terminal.component.scss` | classe `.terminal-ask-scope`, tokens `--cg-*` uniquement |
| ~~`atelier/atelier.component.html`~~ | **rien à faire** : `[executionTarget]` était déjà transmis au terminal |

---

## Plan de test

### Tests unitaires

1. `atelier-terminal.component.spec.ts` : invite en attente + cible `RUNNER` → la mention est dans le
   DOM, dans l'`alertdialog`, avant les boutons.
2. Même invite, cible `SANDBOX` → mention absente.
3. Sans invite → mention absente.
4. `runnerElevated` vrai → les **deux** mentions présentes, portée avant élévation.

### Tests d'intégration

5. La liaison `[executionTarget]` d'`atelier.component.html` existait déjà et reste couverte par les
   specs de `atelier.component.spec.ts` : rien à ajouter, la mention hérite d'un chemin déjà éprouvé.

### Isolation workspace

Sans objet : aucun accès aux données ajouté. L'invite est déjà bornée au terminal du projet ouvert,
lui-même vérifié `requireOwned` côté gateway.

---

## Dépendances

### Subfeatures bloquantes

- **SF-73-02** (merge d'abord) : c'est elle qui rend l'invite de nouveau visible par défaut.

### Questions ouvertes impactées

- **OQ-15** (`DESIGN_SYSTEM.md` se contredit sur sa palette) : **non impactée** — aucune couleur
  nouvelle n'est introduite, la mention emprunte celles du bloc existant.

---

## Notes et décisions

- **Préoccupation transversale — « Navigation / routing » : non.** Aucune route, aucun garde, aucune
  redirection. Le seul composant touché est le terminal de l'Atelier.
- **Pourquoi là et pas ailleurs.** L'en-tête du terminal porte déjà l'interrupteur de la porte ; la
  page d'accueil de la Forge porte l'état des postes. Mais la seule seconde où l'information change
  une décision est celle où l'on s'apprête à cliquer « Autoriser » — c'est le même raisonnement que
  SF-38-18 pour l'élévation de droits, et il vaut *a fortiori* ici.
