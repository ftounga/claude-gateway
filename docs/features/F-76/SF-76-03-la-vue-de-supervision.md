# Mini-spec — F-76 / SF-76-03 — La vue de supervision, en tuiles

---

## Identifiant

`F-76 / SF-76-03`

## Feature parente

`F-76` — Voir travailler ses terminaux

## Statut

`todo`

## Date de création

2026-09-12

## Branche Git

`feat/SF-76-03-vue-de-supervision`

---

## Objectif

Donner un **écran où l'on voit les quatre terminaux travailler en même temps** — une tuile chacun,
la couleur de son client, ses dernières lignes, ce qu'il fait — et où **celui qui attend une
autorisation se signale franchement**, un clic suffisant pour entrer dans le terminal.

---

## Comportement attendu

### Cas nominal

**(1) L'écran.** `/forge/supervision`, atteint depuis un bouton **« Voir travailler »** dans
l'en-tête de `/forge`. Fil d'Ariane : « Forge › Supervision ». L'écran interroge
`GET /api/terminals/live` — **un seul appel**, qui porte déjà tout — et le rejoue toutes les **5 s**.
Le rafraîchissement s'arrête à la destruction de l'écran.

**(2) Les tuiles.** Une par terminal vivant, en grille (une colonne sur mobile, deux à trois au
large). Chacune porte :

- **la couleur du client** (SF-49-03) : filet gauche du ton du poste et pastille d'initiales
  (`app-host-badge`), le nom du poste **écrit** à côté — quatre terminaux, c'est souvent quatre
  clients ;
- le **nom du projet** ;
- la **pastille de vie** (`app-live-badge`, §11) — la tuile ne montre que du vivant ;
- l'**aperçu** (`app-terminal-preview`, densité `tile` : six lignes) — activité écrite et dernières
  lignes ;
- l'**ouverture depuis** : « ouvert depuis 12 min », dérivé de `openedAt` **à l'affichage**.

La tuile **entière** est un lien vers `/atelier/:id` : un clic entre dans le terminal.

**(3) Ce qui attend une autorisation se signale franchement.** Une tuile `AWAITING_APPROVAL` :

- est **placée en tête** de la grille, avant toutes les autres ;
- porte l'**anneau ambre** (§5 « En attente ») et la pastille **écrite** « Attend votre
  autorisation », que le composant d'aperçu rend déjà ;
- est **comptée en en-tête** : « **1 terminal attend votre autorisation** » (accordé au pluriel), en
  toutes lettres, au-dessus de la grille.

Aucun de ces trois signaux ne suffit seul : le 2026-09-08, la demande était à l'écran et a échappé
douze heures durant.

**(4) Le poste « Hébergé » et le terminal du poste.** Un terminal sans poste (projet hébergé, F-71)
n'emprunte **aucun** des dix tons d'identité — il n'identifie pas une machine : filet neutre, et
« Hébergé » écrit. Le terminal du poste (F-74) apparaît comme les autres, nommé par son projet.

**(5) Quand il n'y a rien à voir.** Aucun terminal vivant ⇒ un message qui dit ce qui manque et
comment y remédier : « Aucun terminal ouvert. Ouvrez un projet depuis la Forge. », avec le retour
vers `/forge`.

**(6) Cet écran ne prend pas de place.** Il n'ouvre aucun terminal : il ne **claim** rien, ne tient
aucun battement de cœur, et n'apparaît donc pas dans le « n / 4 ». Regarder ne doit pas coûter un
des quatre flux **payants**.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| La gateway ne répond pas (réseau, 500) | L'écran **garde** les tuiles déjà affichées et signale « Dernier état connu à hh:mm » — effacer la grille sur un hoquet réseau ferait croire que tout s'est arrêté |
| Premier chargement en échec | Message d'erreur et bouton « Réessayer » — rien d'inventé |
| 401 | Le comportement standard de l'application (intercepteur) ; l'écran ne le traite pas à part |
| Un terminal disparaît entre deux rafraîchissements | Sa tuile disparaît. C'est exact : la place a expiré ou l'onglet s'est fermé |
| Un terminal vit sans aperçu | Sa tuile s'affiche quand même — nom, poste, pastille de vie — sans bloc d'aperçu : il vit, il n'a rien dit |

---

## Critères d'acceptation

1. `/forge/supervision` affiche une tuile par terminal rendu par `GET /api/terminals/live`.
2. Chaque tuile porte **le nom du poste écrit** et son ton d'identité (§9) — un projet sans poste
   porte « Hébergé » et **aucun** des dix tons.
3. Chaque tuile porte la **pastille de vie** et son libellé écrit (§11).
4. Une tuile `AWAITING_APPROVAL` est **la première** de la grille, porte l'anneau ambre et le
   libellé **écrit** « Attend votre autorisation ».
5. L'en-tête écrit « **N terminal(aux) attend(ent) votre autorisation** » quand il y en a, et rien
   quand il n'y en a pas.
6. Un clic sur une tuile mène à `/atelier/:workspaceId`.
7. L'écran **ne prend aucune place** : aucun appel à `POST /workspaces/{id}/terminal/live` n'est
   émis, et le registre n'est que **lu**.
8. Le rafraîchissement est de **5 s** et s'arrête à la destruction du composant (aucun `setInterval`
   orphelin).
9. Une erreur de rafraîchissement **conserve** les tuiles et affiche « Dernier état connu à hh:mm ».
10. Aucun terminal vivant ⇒ message explicite + retour vers la Forge, jamais une page blanche.
11. La tuile est en **lecture seule** : aucun champ de saisie, aucun bouton d'envoi.
12. Aucune couleur hors charte : §9 pour l'identité, §5 pour l'attente, §11 pour la vie — aucune
    quatrième famille.

---

## Plan de test minimal

### Unitaires (`supervision.component.spec.ts`)

- une tuile par terminal, nom de projet et de poste **écrits** ;
- tri : la tuile en attente d'autorisation passe **devant** ;
- compteur d'en-tête, accordé au singulier et au pluriel ;
- absence de compteur quand personne n'attend ;
- terminal sans poste ⇒ « Hébergé », aucun ton d'identité ;
- terminal sans aperçu ⇒ tuile présente, aucun bloc d'aperçu ;
- état vide ⇒ message et lien vers `/forge` ;
- erreur au rafraîchissement ⇒ tuiles conservées + « Dernier état connu » ;
- erreur au **premier** chargement ⇒ message + « Réessayer » ;
- rafraîchissement toutes les 5 s, arrêté à la destruction ;
- **aucun `POST` de prise de place** n'est émis par cet écran ;
- la tuile mène à `/atelier/:id` ;
- aucun `input` / `textarea` dans la grille.

### Routing

- `/forge/supervision` charge le composant et ne masque ni `/forge`, ni `atelier/:id`.

### Isolation utilisateur

Aucun accès direct aux données depuis l'écran : `GET /api/terminals/live` est filtré par `user_id`
côté gateway (SF-70-01 / SF-76-01). Le test vérifie que **tout** ce qui est affiché vient de la
réponse — aucun nom de poste ni de projet n'est reconstruit côté client.

---

## Tables / endpoints / composants impactés

Aucune table, aucun endpoint nouveau.

| Composant | Nature |
|---|---|
| `supervision/supervision.component.*` (+ `.spec.ts`) | **Nouveau** — la vue de supervision |
| `app.routes.ts` | Route `forge/supervision` (additive, deux segments — disjointe de `atelier/:id`) |
| `postes/postes.component.html` | Bouton « Voir travailler » dans l'en-tête |
| `shared/forge-breadcrumb` | **Réemployé** tel quel : « Forge › Supervision » |
| `shared/terminal-preview` | **Réemployé** en densité `tile` |
| `docs/DESIGN_SYSTEM.md` | §12 complété : la mise en tête et le compteur |

---

## Contraintes de validation

| Élément | Règle |
|---|---|
| Rafraîchissement | 5 s, arrêté à la destruction |
| Lignes affichées | 6 (densité `tile`) |
| Grille | 1 colonne < 720 px, 2 au-delà, 3 au-delà de 1100 px |
| Couleurs | §9 identité, §5 attente, §11 vie — **aucune** couleur nouvelle |
| Espacements | multiples de 4 px |

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés / vérification |
|---|---|---|
| Auth / Principal | **Non** | Aucun nouveau type d'auth ; l'écran vit sous le même garde de route que les autres écrans de la Forge. |
| Contexte tenant | **Non** | Aucun identifiant de tenant manipulé : l'appel n'en prend aucun. |
| Plans / limites | **Oui** | L'écran **montre** un plafond sans en créer : il ne prend aucune place au registre (critère 7, vérifié par test), donc le « n / 4 » de `/forge` est inchangé. Aucun appel à `UsageService`, `AtelierAccess` ni `HostSeatService`. |
| Navigation / routing | **Oui** | Route **ajoutée** : `forge/supervision`. Chemins vérifiés : `forge` (un segment, non masqué), `postes` (redirection `pathMatch: 'full'` inchangée), `atelier/:id` (un segment de préfixe différent), `atelier/:id/fichiers`. Aucun guard modifié, aucune redirection ajoutée. Test de routing dédié. |

---

## Hors périmètre

- **Écrire** dans une tuile (décision du PO) : aucun champ, aucun bouton d'envoi.
- La **mosaïque à quatre terminaux interactifs** : rejouer quatre flux complets.
- **Fermer à distance** un terminal depuis une tuile : ce serait agir sur une session qu'on ne voit
  pas (même raison qu'en SF-70-02).
- Notifier hors du navigateur qu'un terminal attend une autorisation.
- Agir sur plusieurs postes à la fois.
