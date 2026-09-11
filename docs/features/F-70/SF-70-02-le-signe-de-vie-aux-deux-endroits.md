# Mini-spec — F-70 / SF-70-02 — Le signe de vie, aux deux endroits

---

## Identifiant

`F-70 / SF-70-02`

## Feature parente

`F-70` — Plusieurs terminaux, et l'on voit lesquels vivent

## Statut

`done` — mergée le 2026-09-12 (PR #381)

## Date de création

2026-09-12

## Branche Git

`feat/SF-70-02-signe-de-vie-terminaux`

---

## Objectif

Montrer d'un coup d'œil **quels terminaux vivent** — la même pastille et le même mot « connecté »
dans la barre du terminal et sur la carte du poste — et **refuser explicitement** le cinquième en
disant ce que quatre flux vivants engagent.

---

## Comportement attendu

### Cas nominal

**(1) Le terminal prend sa place et la tient.** À l'ouverture d'un projet, le terminal appelle
`POST /api/workspaces/{id}/terminal/live` avec un `sessionId` **propre à l'onglet**, rangé dans
`sessionStorage` : un rechargement garde la même place, un onglet dupliqué en prend une autre. Un
battement de cœur rejoue le même appel toutes les **30 s**. Fermer le terminal, quitter l'écran ou
fermer l'onglet libère la place (`DELETE`, envoyé en `keepalive` pour survivre à la fermeture).

**(2) Le signe de vie dans la barre.** Tant que la place est tenue, la barre du terminal affiche
`app-live-badge` : une **pastille** qui pulse et le mot **« connecté »**, écrit. Même signe, même
composant que sur la carte du poste.

**(3) Le même signe sur la carte du poste.** Sur `/forge`, chaque carte dont un projet a un terminal
vivant porte **la même pastille** avec « Terminal connecté » (ou « N terminaux connectés »), et la
ligne du projet concerné la porte aussi. La donnée vient de `liveTerminals` / `liveTerminal` rendus
par la vue d'ensemble (SF-70-01), au rythme de rafraîchissement existant (15 s).

**(4) Ce que ça engage, dit à l'écran.** L'en-tête de `/forge` affiche en permanence
« **Terminaux vivants : n / 4 — chaque terminal vivant consomme un tour en parallèle** », **écrit**
et non rangé dans une infobulle : un garde-fou de dépense qu'il faut survoler pour découvrir
n'informe personne. La phrase complète — « quatre consommations simultanées », « garde-fou de
dépense » — est reprise dans le bandeau de refus, et l'infobulle de la pastille la rappelle.

**(5) Le refus au cinquième.** Si la prise de place rend **409 `terminal_limit_reached`**, le
terminal s'ouvre quand même — on peut relire son historique — mais un **bandeau** non modal s'installe
en tête :

> **Quatre terminaux actifs au maximum, fermez-en un pour en ouvrir un autre.**
> Quatre flux vivants, ce sont quatre consommations simultanées : quatre tours facturés en parallèle.
> *Terminaux vivants : `<projet>` — chez `<poste>` …* (les quatre, **nommés**)

Et **l'envoi est bloqué** tant que la place n'est pas obtenue : champ de saisie désactivé, bouton
d'envoi désactivé, libellé « Terminal en attente d'une place ». C'est le refus explicite demandé —
jamais un agent qu'on croit actif et qui dort. Un bouton « Réessayer » rejoue la prise de place.

**(6) Reprise.** Dès qu'une place se libère, le battement suivant (30 s au plus) l'obtient : le
bandeau disparaît, la pastille s'allume, la saisie se rouvre. Aucun rechargement.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| 409 `terminal_limit_reached` | Bandeau de refus + envoi bloqué (ci-dessus) |
| Erreur réseau / 500 sur la prise de place | **Rien n'est bloqué** : pas de pastille, pas de bandeau, l'envoi reste possible. Le battement réessaie. Une panne de la gateway n'interdit pas de travailler |
| 404 (projet disparu) | Aucune pastille, aucun bandeau ; l'écran gère déjà le projet manquant |
| Onglet masqué (autre onglet au premier plan) | Le battement **continue** : l'onglet est bien vivant, et le PO facture la vie, pas le regard. En revanche il n'est pas relancé plus vite au retour |
| `sessionStorage` indisponible | Un identifiant en mémoire prend le relais : la place fonctionne, elle n'est simplement pas retrouvée après rechargement |

---

## Critères d'acceptation

1. Un terminal ouvert affiche, dans sa barre, **une pastille et le mot « connecté »** — le mot est
   présent dans le DOM, pas seulement une couleur.
2. Le **même composant** (`app-live-badge`) est employé dans la barre du terminal et sur la carte du
   poste : aucun écran ne recompose la pastille à la main.
3. Sur `/forge`, une carte dont un projet est vivant affiche « Terminal connecté » ; deux vivants
   affichent « 2 terminaux connectés ».
4. La pastille n'emploie **aucune** couleur de la palette d'identité (§9) ni des pastilles de statut
   (§10 / §5) : elle est **monochrome** (`currentColor`). Test de non-régression : les classes
   `.badge--success` / `.badge--warning` / `.badge--neutral` et les tons de `host-identity` ne sont
   pas référencés par le composant.
5. Un 5ᵉ terminal affiche le bandeau contenant **mot pour mot** « Quatre terminaux actifs au
   maximum, fermez-en un pour en ouvrir un autre. » et la phrase sur les quatre consommations
   simultanées.
6. Sous ce bandeau, le champ de saisie et le bouton d'envoi sont **désactivés**.
7. Le bandeau **nomme** les quatre terminaux vivants (« projet — chez poste »).
8. Une erreur réseau sur la prise de place ne désactive **pas** la saisie.
9. Quitter le terminal appelle la libération ; revenir la reprend.
10. L'en-tête de `/forge` affiche « Terminaux vivants : n / 4 » et la phrase de coût.
11. Le battement est de 30 s et s'arrête à la destruction du composant (aucun `setInterval` orphelin).
12. `prefers-reduced-motion` supprime la pulsation ; la pastille reste visible.

---

## Plan de test minimal

### Unitaires (`LiveTerminalService` front, `live-badge.component.spec.ts`)

- `sessionId` stable entre deux lectures, régénéré si `sessionStorage` jette ;
- battement démarré / arrêté, `clearInterval` appelé ;
- libération en `keepalive` à l'arrêt ;
- le badge écrit toujours son libellé ; singulier / pluriel ; `aria-live` absent (ce n'est pas une
  alerte) mais `role="status"` sur la barre.

### Intégration composant (`atelier.component.spec.ts`, `postes.component.spec.ts`)

- 409 ⇒ bandeau + saisie désactivée + phrase de coût ;
- 200 ⇒ pastille « connecté », saisie active ;
- erreur réseau ⇒ ni bandeau ni blocage ;
- carte de poste avec `liveTerminals: 2` ⇒ « 2 terminaux connectés » ; `0` ⇒ aucune pastille ;
- ligne de projet avec `liveTerminal: true` ⇒ pastille.

### Isolation utilisateur

Aucun accès direct aux données depuis le frontend : l'isolation est tenue par SF-70-01. Le test
d'écran vérifie seulement que **rien** n'est affiché à partir d'un identifiant construit côté client
— tout vient des réponses de la gateway.

---

## Tables / endpoints / composants impactés

Aucune table, aucun endpoint nouveau (ceux de SF-70-01).

| Composant | Nature |
|---|---|
| `shared/live-badge/live-badge.component.*` | **Nouveau** — pastille + mot, composant unique |
| `core/services/live-terminal.service.ts` | **Nouveau** — prise, battement, libération, état |
| `core/models/atelier.models.ts` | Types `LiveTerminals`, `LiveTerminalEntry` ; champs `liveTerminals` / `liveTerminal` sur la vue d'ensemble |
| `atelier/terminal/atelier-terminal.component.*` | Pastille dans la barre, bandeau de refus, saisie bloquée |
| `atelier/atelier.component.*` | Cycle de vie de la place, blocage de `send()` |
| `postes/postes.component.*` | Pastille sur la carte et la ligne de projet, compteur d'en-tête |
| `docs/DESIGN_SYSTEM.md` | **§11** — le signe de vie, et pourquoi il n'a pas de couleur |

---

## Contraintes de validation

| Élément | Règle |
|---|---|
| Battement | 30 s (constante, alignée sur un `ttl` serveur de 90 s) |
| Libellés | « connecté » (barre), « Terminal connecté » / « N terminaux connectés » (carte) |
| Couleurs | **Aucune** couleur nouvelle ; `currentColor` uniquement (charte §11) |
| Espacements | multiples de 4 px |

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés / vérification |
|---|---|---|
| Auth / Principal | **Non** | Aucun changement de session ni de Principal. |
| Contexte tenant | **Non** | Aucun identifiant de tenant manipulé côté écran. |
| Plans / limites | **Oui** | Un nouveau plafond **visible**. Écrans affichant déjà une limite : `/billing` (quota tokens), le bandeau de quota de l'Atelier, l'alerte de quota (SF-58). **Aucun n'est modifié** : le plafond de terminaux vit dans son propre bandeau, avec son propre libellé, et ne réutilise ni ne masque les leurs. Vérifié par test : le bandeau de quota reste affiché quand les deux coexistent. |
| Navigation / routing | **Non** | Aucune route ajoutée, aucun guard modifié. Les liens du bandeau visent `/atelier/:id`, route existante. |

---

## Hors périmètre

- Un agent qui travaille onglet fermé.
- Plusieurs terminaux côte à côte dans une même page.
- Fermer à distance le terminal d'un autre onglet depuis le bandeau — ce serait agir sur une
  session qu'on ne voit pas.
- **Rendre la liste du bandeau cliquable** (arbitrage pris pendant le dev, réversible) : un lien
  amènerait **cet onglet refusé** sur un projet déjà tenu par un autre onglet — visible, et toujours
  refusé. Un piège. On dit lequel fermer ; l'onglet à fermer, lui, est dans le navigateur.
- Notifier quand une place se libère.
