# Mini-spec — F-76 / SF-76-02 — Le terminal dit ce qu'il fait, la carte le montre

---

## Identifiant

`F-76 / SF-76-02`

## Feature parente

`F-76` — Voir travailler ses terminaux

## Statut

`todo`

## Date de création

2026-09-12

## Branche Git

`feat/SF-76-02-apercu-sur-la-carte`

---

## Objectif

Faire dire à l'onglet qui travaille **ce qu'il est en train de faire**, et le montrer **sur la page
d'accueil de la Forge** — quelques lignes sous le nom de chaque projet vivant — pour qu'on sache
qu'un agent attend quelque chose **sans rien ouvrir**.

---

## Comportement attendu

### Cas nominal

**(1) Le terminal relève ce qu'il fait.** À chaque évolution de son état, l'écran du terminal en
dérive, par fonction **pure** (`terminal-preview.ts`) :

| Ce que l'écran voit | Activité | Détail | Lignes |
|---|---|---|---|
| Une demande d'autorisation en attente | `AWAITING_APPROVAL` | la commande soumise à décision | les dernières lignes du tour |
| Un tour en cours, une commande engagée | `RUNNING` | la commande (`npm test`) | les dernières lignes |
| Un tour en cours, aucune commande encore | `THINKING` | — | les dernières lignes |
| Rien en cours | `IDLE` | — | les dernières lignes du dernier tour |

Les lignes sont les **six dernières** de la transcription visible, tronquées à 160 caractères
**avant l'envoi** — on ne demande pas au réseau de transporter ce qu'on va jeter — puis rebornées
par la gateway (SF-76-01).

**(2) L'envoi suit l'urgence, pas l'horloge.**

- Un **changement d'activité** part **immédiatement**, sans attendre le battement : c'est ce qui
  fait qu'« attend une autorisation » se voit en quelques secondes, et non dans trente.
- Un simple défilement de lignes, à activité **constante**, est **apaisé** : un envoi toutes les
  **5 s** au plus.
- Le battement de cœur de 30 s emporte de toute façon le dernier relevé connu.
- Rien ne part quand rien n'a changé : un aperçu identique au précédent n'est pas renvoyé.

**(3) La carte du poste le montre.** Sur `/forge`, sous le nom de chaque projet **dont un terminal
vit et a quelque chose à dire**, un bloc discret en monospace affiche :

- la ligne d'activité — « **Exécute** `npm test` », « **Réfléchit** », « **Attend votre
  autorisation** », « Inactif » ;
- les **trois dernières lignes** (la carte est dense ; la tuile de SF-76-03 en montrera six).

Le **terminal du poste** (F-74) porte le même bloc, sous la carte, quand il vit.

**(4) Ce qui attend une autorisation se signale, dès la carte.** Le projet concerné porte une
**pastille §5 « En attente »** (`#FFF8E1` / `#F9A825`) avec le libellé **écrit** « Attend votre
autorisation ». Aucun registre de couleur nouveau : c'est la palette de statut de la charte, celle
qui sert déjà à dire « en attente ».

**(5) Un composant unique pour les deux densités.** `app-terminal-preview` rend l'aperçu ; son
entrée `density` vaut `card` (3 lignes) ou `tile` (6 lignes). Aucun écran ne recompose l'aperçu à la
main — c'est la règle commune aux §9, §10 et §11 de la charte.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Le relevé échoue (réseau, 500) | **Rien n'est bloqué** : l'envoi de l'aperçu suit le sort du battement de cœur, et une panne n'interdit ni de travailler ni de tenir sa place (règle F-70 inchangée) |
| 409 `terminal_limit_reached` | Inchangé : bandeau de refus, envoi bloqué. Aucun aperçu n'est relevé — il n'y a pas de place à décorer |
| La gateway ne rend pas `terminalPreview` (backend antérieur) | La carte n'affiche **rien de plus** qu'aujourd'hui : pastille de vie seule. Aucune erreur, aucun bloc vide |
| Aperçu vide (`IDLE`, aucune ligne) | **Aucun bloc** : une ligne « Inactif » sous chaque projet n'apprendrait rien de plus que la pastille de vie |
| Onglet en arrière-plan | Le relevé d'un **changement d'activité** part depuis le gestionnaire d'événement du flux, pas depuis une minuterie : il n'est pas soumis à la limitation des minuteries d'arrière-plan |

---

## Critères d'acceptation

1. Une demande d'autorisation affichée ⇒ le relevé émis porte `AWAITING_APPROVAL` et la commande en
   détail, et il est envoyé **sans attendre** le battement suivant.
2. Une commande en cours ⇒ `RUNNING` + la commande ; un tour sans commande encore engagée ⇒
   `THINKING` ; rien en cours ⇒ `IDLE`.
3. Deux relevés **identiques** consécutifs ⇒ **un seul** envoi.
4. Deux relevés successifs à activité constante en moins de 5 s ⇒ **un seul** envoi immédiat, le
   second différé.
5. Les lignes envoyées sont au plus **6**, tronquées à **160** caractères.
6. À la destruction du composant, aucune minuterie d'aperçu ne survit (pas de `setTimeout`
   orphelin).
7. Sur `/forge`, un projet portant `terminalPreview` affiche la ligne d'activité **écrite** et ses
   trois dernières lignes ; un projet sans aperçu n'affiche **aucun** bloc.
8. Un projet dont l'aperçu vaut `AWAITING_APPROVAL` porte la pastille **« Attend votre
   autorisation »** — le libellé est dans le DOM, la couleur ne le porte pas seule.
9. La pastille d'attente n'emploie **aucune** couleur de la palette d'identité (§9) et **aucune**
   couleur nouvelle : test de non-régression sur les tons `host-identity`.
10. Le terminal du poste (F-74) affiche son aperçu quand il vit.
11. `app-terminal-preview` est employé **aux deux densités** et n'expose aucune entrée permettant de
    masquer le libellé d'activité.
12. Le bloc d'aperçu est en lecture seule : aucun champ de saisie, aucun bouton d'envoi
    (hors périmètre de la feature).

---

## Plan de test minimal

### Unitaires (`terminal-preview.spec.ts`)

- dérivation des quatre activités depuis l'état de l'écran ;
- six dernières lignes, troncature à 160 ;
- égalité de deux relevés (ce qui décide de ne pas renvoyer).

### Unitaires (`live-terminal.service.spec.ts`)

- changement d'activité ⇒ envoi immédiat ;
- lignes seules ⇒ envoi apaisé (5 s), un seul appel ;
- relevé identique ⇒ aucun envoi ;
- `stop()` annule la minuterie d'aperçu ;
- le corps envoyé porte bien `activity` / `activityDetail` / `previewLines`.

### Intégration composant (`postes.component.spec.ts`)

- projet avec aperçu `RUNNING` ⇒ « Exécute npm test » + les lignes ;
- projet avec `AWAITING_APPROVAL` ⇒ pastille « Attend votre autorisation » écrite ;
- projet sans aperçu ⇒ aucun bloc ;
- terminal du poste vivant avec aperçu ⇒ bloc affiché ;
- la pastille de vie (F-70) et le compteur « n / 4 » restent affichés (non-régression).

### Intégration composant (`atelier.component.spec.ts`)

- une demande d'autorisation déclenche un relevé `AWAITING_APPROVAL` ;
- un 409 ne déclenche aucun relevé.

### Isolation utilisateur

Aucun accès direct aux données depuis l'écran : l'isolation est tenue par SF-76-01. Le test
d'écran vérifie que **tout** ce qui est affiché vient de la réponse de la gateway — aucun aperçu
n'est reconstruit à partir d'un identifiant fabriqué côté client.

---

## Tables / endpoints / composants impactés

Aucune table, aucun endpoint nouveau (ceux de SF-70-01 enrichis par SF-76-01).

| Composant | Nature |
|---|---|
| `shared/terminal-preview/terminal-preview.component.*` | **Nouveau** — l'aperçu, aux deux densités |
| `atelier/terminal/terminal-preview.ts` (+ `.spec.ts`) | **Nouveau** — dérivation **pure** de l'aperçu depuis l'état de l'écran |
| `core/services/live-terminal.service.ts` | `report()`, cadence, envoi avec le battement |
| `core/models/atelier.models.ts` | `TerminalActivity`, `TerminalPreview` ; champs `terminalPreview` / `hostTerminalPreview` sur la vue d'ensemble et sur `LiveTerminalEntry` |
| `atelier/atelier.component.*` | Relève l'aperçu et le confie au service |
| `postes/postes.component.*` | Le bloc d'aperçu sous le nom du projet, et sous la carte pour le terminal du poste |
| `docs/DESIGN_SYSTEM.md` | **§12** — ce qui attend une décision, et pourquoi ça n'ajoute aucune couleur |

---

## Contraintes de validation

| Élément | Règle |
|---|---|
| Lignes envoyées | 6 au plus, 160 caractères chacune (le serveur reborne) |
| Cadence | immédiate sur changement d'activité ; 5 s au plus sinon ; 30 s par le battement |
| Lignes affichées | **3** sur la carte, **6** dans la tuile |
| Couleurs | **aucune couleur nouvelle** : §5 pour l'attente, §11 pour la vie, §9 pour l'identité |
| Espacements | multiples de 4 px |
| Police des lignes | JetBrains Mono (§3), taille réduite |

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés / vérification |
|---|---|---|
| Auth / Principal | **Non** | Aucun changement de session ni de Principal ; les appels sont ceux de F-70. |
| Contexte tenant | **Non** | Aucun identifiant de tenant manipulé côté écran. |
| Plans / limites | **Non** | Aucun plafond nouveau, aucun gate modifié. Le plafond de terminaux (F-70) et son bandeau de refus sont **inchangés** — non-régression vérifiée : le compteur « n / 4 » et la pastille de vie restent affichés quand l'aperçu apparaît. |
| Navigation / routing | **Non** | Aucune route ajoutée ni guard modifié. (La route de supervision arrive en SF-76-03.) |

---

## Hors périmètre

- **Écrire** dans un aperçu (décision du PO).
- La **vue de supervision** et ses tuiles (SF-76-03).
- Rejouer le flux complet d'un terminal ailleurs que dans le terminal.
- Notifier hors du navigateur.
