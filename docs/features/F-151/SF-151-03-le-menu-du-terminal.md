# Mini-spec — F-151 / SF-151-03 — Le menu du terminal

## Identifiant
`F-151 / SF-151-03` — feature parente `F-151` — dépend de **SF-151-01** et **SF-151-02**

## Objectif
Qu'en entrant dans un terminal, l'utilisateur voie **d'un coup d'œil** ce qu'il doit faire, lui.

## La demande
> PO : *« Lorsque je rentre dans un terminal, par exemple le terminal d'un sujet, j'ai une option au
> niveau de l'écran, je peux cliquer et j'ai directement la liste des actions qu'il faut que je
> fasse. »* — et *« quitte à ce que moi-même je les annule après »*.

## Comportement attendu
1. Une **pastille** dans la barre du terminal : « **3 à faire** ». **Absente quand il n'y a rien** —
   une pastille à zéro est un bruit permanent qui apprend à ne plus la regarder.
2. Un clic ouvre un **panneau à droite**, même patron que le panneau d'une page (F-109). **Échap**
   le ferme.
3. Chaque action montre : **ce qu'il faut faire**, **↳ bloque : …**, **qui** est concerné, et son
   **âge** (« il y a 3 jours ») — l'ancienneté est le signal qui fait agir.
4. Deux gestes par action : **C'est fait** et **Annuler**. Discrets, sans confirmation : ce sont des
   gestes réversibles.
5. Une action fermée **reste visible un instant**, barrée, avec **Rétablir** — puis disparaît au
   prochain chargement. Sans ce repentir, un clic malheureux serait définitif.
6. **Ailleurs** : sous la liste du terminal, les actions ouvertes des **autres projets** du compte,
   repliées, **en lecture seule**, avec le nom de leur projet. Le PO voulait tout voir d'un endroit
   sans perdre le contexte du terminal courant.

| Cas d'erreur | Comportement |
|---|---|
| Le chargement échoue | un mot dans le panneau, jamais une page blanche ni une erreur technique |
| Un geste échoue | l'action revient à son état d'avant, et l'échec est annoncé (`MatSnackBar`) |
| Le terminal n'est pas possédé | 404 côté gateway — le panneau reste vide, rien ne fuit |

## Critères d'acceptation
- [ ] Pastille **absente** à zéro action, présente et chiffrée sinon.
- [ ] Le panneau liste les actions ouvertes du terminal, **les plus anciennes d'abord**.
- [ ] **C'est fait** et **Annuler** ferment l'action ; **Rétablir** la rouvre.
- [ ] Un échec d'appel **ne laisse pas l'écran mentir** : l'état d'avant est rétabli, et dit.
- [ ] La section **Ailleurs** montre les actions des autres projets, en lecture seule, avec le nom
      du projet, et n'apparaît que s'il y en a.
- [ ] **Design system** : couleurs et espacements issus des variables `--cg-*` ; aucune couleur en
      dur ; pas de `window.confirm`.
- [ ] **ISOLATION** : aucun identifiant de compte n'est envoyé — la gateway part du JWT.

## Hors scope
**Envoyer le message depuis la liste** — le courriel client (F-110) part aujourd'hui **de l'agent**,
pas d'un écran ; lui donner une porte d'écran est une subfeature à part entière, pas un bouton. ·
La **fermeture par la conversation** (**SF-151-04**) · toute relance ou notification.

## Technique
| Élément | Changement |
|---|---|
| `GET /terminal-actions` | **nouvelle route** : les actions ouvertes du compte, tous projets, avec le nom du projet (racine distincte pour ne pas entrer en collision avec `/workspaces/{id}/actions`) |
| `TerminalActionsService` (front) | lire, fermer, annuler, rétablir |
| `terminal-actions-panel.component.ts` | le panneau, patron `page-panel.component.ts` |
| `atelier-terminal.component` | la pastille dans la barre, l'ouverture du panneau |

## Plan de test
- [ ] Pastille : absente à 0, « 3 à faire » à 3.
- [ ] Panneau : liste ordonnée, âge affiché, « bloque : … » affiché quand il existe.
- [ ] **C'est fait** → l'action sort de la liste et propose **Rétablir** ; **Rétablir** la remet.
- [ ] Échec d'appel → état d'avant rétabli + message.
- [ ] **Ailleurs** : masquée quand vide, montrée avec le nom du projet sinon, **sans** bouton d'action.
- [ ] Aucune couleur en dur dans le composant (garde de charte).

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | route utilisateur ordinaire, `CurrentUser` |
| **Contexte tenant** | **oui** | la nouvelle route `/terminal-actions` lit par `user_id` **seul** (elle traverse les projets, c'est son objet) ; elle ne rend **que** des actions du compte, et le nom de projet vient d'un `findByIdAndUserId`. Aucun identifiant n'est accepté du client. |
| Plans / limites | non | aucun appel fournisseur, aucune garde d'espace (l'écran est ouvert à qui possède le terminal) |
| **Navigation / routing** | **oui** *(état d'écran, pas de route)* | le panneau est un **état** du composant terminal, comme le panneau de page (F-109 / SF-109-03) : aucune route Angular ajoutée, aucun guard touché, aucune redirection. Les chemins existants sont inchangés. |
