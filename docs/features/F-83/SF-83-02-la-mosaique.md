# Mini-spec — F-83 / SF-83-02 — La mosaïque : quatre terminaux en même temps

## Identifiant

`F-83 / SF-83-02`

## Feature parente

`F-83` — La mosaïque : quatre terminaux en même temps

## Statut

`in-progress`

## Date de création

2026-09-12

## Branche Git

`feat/SF-83-02-la-mosaique`

---

## Objectif

Un écran qui montre **les quatre terminaux vivants en même temps**, chacun avec le **contenu réel de
son flux** — pas un aperçu —, en lecture seule, sur presque toute la surface de l'écran.

---

## Comportement attendu

### Cas nominal

1. `/forge/mosaique` lit le registre des terminaux vivants : `GET /api/terminals/live`. **Lecture
   pure** — cet appel ne prend aucune place (c'est déjà celui de la vue de supervision).
2. Pour **chaque** terminal du registre, la page ouvre **sa propre place lectrice** :
   `GET /api/workspaces/{id}/chat/attach?cursor=N` (SSE, F-84 / SF-84-02). Le flux rejoue ce qui a
   été manqué, puis passe au direct.
3. Chaque tuile rend `<app-atelier-terminal [readOnly]="true">` (SF-83-01) alimenté par ce flux :
   commandes, sorties, commentaire de l'agent, plan, ligne vivante, défilement automatique. **Le
   contenu réel**, identique à celui d'un terminal ouvert.
4. La tuile porte l'**identité du client** (SF-49-03) : filet de la couleur dérivée du nom du poste,
   pastille d'initiales, et le nom du poste **écrit** à côté du nom du projet.
5. Une tuile **en attente d'autorisation** : elle **passe en tête**, porte l'**anneau ambre**, et la
   mention écrite « Attend votre autorisation » (rendue par le terminal lui-même, SF-83-01). Le
   compte est **écrit en toutes lettres** dans l'en-tête de la page. Trois signaux, comme F-76.
6. Le registre est relu toutes les **5 s** : un terminal qui apparaît ouvre une place lectrice, un
   terminal qui disparaît la referme.
7. Un tour qui se termine (`done`, `error`) ou un projet sans tour (`idle`) **ne ferme pas la
   tuile** : la lectrice se rebranche 5 s plus tard, et la tuile dit « au repos » en attendant.
8. **Entrer** : un lien explicite par tuile ouvre `/atelier/{id}` — écrire reste un geste pris dans
   le terminal entier, devant son flux entier.
9. Quitter la page abandonne les quatre lectures. Rien n'est libéré : **rien n'avait été pris**.

### Densité — exigence littérale du PO

Tout ce qui n'est pas du terminal se réduit à l'indispensable : **un en-tête de page d'une ligne**
et **un en-tête de tuile d'une ligne**. Mesuré par test : au moins **85 %** de la hauteur utile de
la page revient aux flux.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Registre illisible au **premier** chargement | Écran d'erreur sobre + « Réessayer » | 200 (flux) / erreur HTTP |
| Registre illisible **ensuite** | On **garde** ce qui est affiché et on dit « dernier état connu à hh:mm » — vider la grille ferait croire que les agents se sont arrêtés | — |
| Aucun terminal vivant | « Aucun terminal ouvert » + lien vers la Forge. Aucun flux ouvert | — |
| `attach` répond `idle` (rien ne tourne) | La tuile reste, dit « au repos », et se rebranche 5 s plus tard | 200 + flux |
| `attach` échoue (réseau, 5xx) | Traité comme `idle` : la tuile reste, aucune panne annoncée | — |
| `attach` rejoue après un trou (`truncated`) | La tuile le **dit** (« suite du tour ») plutôt que de le maquiller | 200 + flux |
| Terminal d'un **autre utilisateur** | Impossible : `attach` cherche le tour par le couple `(userId, workspaceId)` et le registre filtre sur `user_id`. Rien n'est demandé par identifiant d'utilisateur côté écran | — |

---

## Critères d'acceptation

- [ ] Quatre terminaux vivants ⇒ **quatre tuiles**, chacune montrant le **contenu réel** de son flux
      (commandes et sorties reçues sur `attach`), et non les `previewLines` du registre.
- [ ] Le fond de chaque tuile est **exactement** `#141D33` (`--cg-navy-2`).
- [ ] Une tuile en attente d'autorisation **passe en tête**, porte l'anneau ambre, et l'en-tête
      compte l'attente **en toutes lettres**.
- [ ] Chaque tuile porte la **couleur du client** (filet + pastille) et le nom du poste **écrit**.
- [ ] **Aucune place émettrice consommée** : ouvrir la mosaïque n'émet **aucun**
      `POST /workspaces/*/terminal/live`. Le registre reste à `n / 4` et un quatrième terminal
      reste ouvrable ailleurs. *(Le test qui protège le portefeuille du PO.)*
- [ ] Aucun champ de saisie, aucun bouton d'envoi, aucun bouton de décision dans une tuile.
- [ ] Au moins **85 %** de la hauteur utile revient aux flux (mesuré).
- [ ] Quitter la page abandonne les quatre flux (`abort` appelé autant de fois qu'il y a de tuiles).
- [ ] Suite frontend verte.

---

## Périmètre

### Hors scope (explicite)

- **Écrire depuis une tuile** — tranché dès F-76.
- Voir un terminal dont **aucun onglet n'est ouvert** — sujet distinct de la source de vérité.
- Retirer les aperçus de F-76 : ils gardent leur sens sur l'accueil de la Forge, là où l'on ne veut
  précisément **pas** de flux.
- **Changer le plafond de quatre.**
- L'agrandissement d'une tuile (SF-83-03).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|---|---|---|
| `cursor` d'une lectrice | `0` | Un écran neuf n'a rien vu : il demande tout ce que le tampon garde |
| Cadence du registre | `5 s` | Celle de la vue de supervision — assez pour voir une attente arriver |
| Cadence de rebranchement | `5 s` | Un `attach` sans tour vivant répond `idle` et se clôt aussitôt : il ne coûte rien |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|---|---|---|---|---|---|
| `workspaceId` (chemin) | Oui | — | UUID, **venu du registre**, jamais saisi | — | aucune |
| `cursor` (requête) | Non | — | entier ≥ 0 | — | `< 0` ⇒ `0` côté gateway |

Aucun champ saisi par l'utilisateur : cet écran n'a **aucune** entrée.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Nature |
|---|---|---|---|
| GET | `/api/terminals/live` | Oui | **existant** (F-70) — lecture du registre, ne prend aucune place |
| GET | `/api/workspaces/{id}/chat/attach?cursor=N` | Oui | **existant** (F-84 / SF-84-02) — flux lecteur |

**Aucun endpoint créé ou modifié. Aucune ligne de backend.**

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `MosaiqueComponent` (`mosaique/`) — la page : registre, tuiles, tri, densité.
- `LiveTurnView` (`mosaique/live-turn-view.ts`) — **la place lectrice** : branche `attachTurn` et en
  dérive l'état du terminal (blocs via `chatStepsToBlocks`, commentaire, tokens, plan, autorisation).
- `AtelierTerminalComponent` — réutilisé tel quel en `[readOnly]` (SF-83-01).
- Route `forge/mosaique`, porte depuis `/forge` et depuis `/forge/supervision`.
- `docs/DESIGN_SYSTEM.md` §13 — déclaration de la surface « terminal en lecture seule ».

---

## Plan de test

### Tests unitaires

- [ ] `mosaique.component.spec` — quatre terminaux vivants ⇒ quatre tuiles.
- [ ] `mosaique.component.spec` — le **contenu réel du flux** s'affiche (une commande reçue sur
      `attach` apparaît dans la tuile), et non les `previewLines` du registre.
- [ ] `mosaique.component.spec` — **aucun `POST .../terminal/live`** n'est émis : aucune place
      émettrice consommée.
- [ ] `mosaique.component.spec` — la tuile qui attend passe **en tête**, anneau ambre, compte écrit.
- [ ] `mosaique.component.spec` — couleur du client : filet et pastille, nom du poste écrit.
- [ ] `mosaique.component.spec` — aucune saisie : ni `input`, ni `form`.
- [ ] `mosaique.component.spec` — quitter la page abandonne les flux.
- [ ] `mosaique.component.spec` — registre en panne après un premier succès : on garde l'affichage.
- [ ] `mosaique.component.spec` — densité : ≥ 85 % de la hauteur utile pour les flux.
- [ ] `live-turn-view.spec` — `attached` puis `action`/`output`/`text` ⇒ blocs et commentaire.
- [ ] `live-turn-view.spec` — `idle` / `done` ⇒ tuile au repos, puis rebranchement.
- [ ] `live-turn-view.spec` — `confirm` ⇒ autorisation attendue, `resolved` ⇒ elle disparaît.
- [ ] `live-turn-view.spec` — `close()` abandonne le flux et n'ouvre plus rien.

### Tests d'intégration

Sans objet côté backend : **aucun endpoint n'est créé ni modifié**. Les deux endpoints employés sont
déjà couverts (`AtelierChatApiIntegrationTest` pour `attach`, `LiveTerminalControllerTest` pour le
registre), isolation `user_id` comprise.

### Isolation utilisateur

- [x] Applicable — et **portée entièrement par la gateway** : le registre filtre sur `user_id`, et
      `attach` cherche le tour par le couple `(userId, workspaceId)` — le tour d'autrui est
      *introuvable*, pas « refusé » (SF-84-02, testé). L'écran n'envoie **aucun** identifiant
      d'utilisateur et n'ouvre que des projets nommés par **son propre** registre.

---

## Dépendances

### Subfeatures bloquantes

- `SF-83-01` — statut : `done`
- `SF-84-02` — statut : `done`

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

> **La mosaïque touche au plafond de F-70.** Tous les composants qui comptent des places, vérifiés
> **un par un** :

| Composant | Rôle dans le plafond | Vérification | Verdict |
|---|---|---|---|
| `LiveTerminalService` (front) | prend et **tient** une place (`POST .../terminal/live` toutes les 30 s) | **Jamais injecté** par la mosaïque. Test : aucun `POST` n'est émis pendant la vie de l'écran | intact |
| `live_terminals` (table) | une ligne = **un onglet ouvert** | Aucune écriture : la mosaïque n'appelle que `GET /api/terminals/live` et `GET .../chat/attach` | intact |
| `LiveTerminalService` (back) | `claim` / `release` / `snapshot`, plafond dur `MAX_LIMIT = 4` | Seul `snapshot` est appelé — il ne prend rien | intact |
| 409 `terminal_limit_reached` | refuse le cinquième **onglet** | Inchangé : la mosaïque n'ouvre aucun onglet. Un quatrième terminal reste ouvrable ailleurs pendant que la mosaïque tourne (critère d'acceptation) | intact |
| `app-live-badge` (pastille) | dit qu'un onglet **tient** sa place | Non employé dans la tuile : une tuile n'est pas un onglet vivant, et l'y mettre mentirait | intact |
| En-tête « n / 4 » de `/forge` | dit le coût du plafond | Inchangé, et **repris** dans l'en-tête de la mosaïque avec la même phrase | intact |
| `chatStreamExecutor` (back) | pool des flux **émetteurs**, chacun facturé | Non employé : `attach` passe par `turnAttachExecutor`, pool **lecteur** distinct (SF-84-02) | intact |

**La place lectrice existe donc déjà, et elle est distincte par construction** : le registre compte
des **onglets** (flux payants), l'attache passe par un pool **lecteur** et n'écrit nulle part. Voie
**A** du cadrage, sans rien ajouter au comptage. **Réversible** : si le PO veut un jour compter les
lectures, c'est une ligne de plus dans le registre, pas une refonte de l'écran.

| Autre préoccupation | Composants vérifiés | Verdict |
|---|---|---|
| **Navigation / routing** | `app.routes.ts` : `forge/mosaique` ajoutée **après** `forge` et `forge/supervision`, deux segments — elle ne masque ni `forge` (un segment), ni `atelier/:id` (autre préfixe), ni `forge/supervision` (segment final différent). `postes` → `/forge` (redirection `pathMatch: 'full'`) inchangée. Aucune entrée nouvelle dans la barre de navigation (F-68 a désencombré, on ne le défait pas) | traité |
| **Auth / Principal** | Aucun changement : la route vit sous la coquille authentifiée (`authGuard` du parent pathless), et les deux endpoints employés lisent le `CurrentUser` comme avant | traité |
| **Contexte tenant** | Aucun nouveau moyen de résoudre le tenant : `user_id` vient du jeton, côté gateway, sur les deux appels | traité |

---

## Notes et décisions

- **Voie A, tranchée par défaut au cadrage** : la page ouvre ses propres flux. Écartée, la voie B
  (rejouer `atelier_messages`) livrerait un **aperçu décalé** — l'erreur de F-76 sous une autre
  forme. Le PO demande de *voir travailler*, pas de *relire*.
- **Au repos, la tuile ne rejoue rien.** Un terminal ouvert sur lequel aucun tour ne tourne affiche
  « au repos », et **pas** les dernières lignes du registre : ce serait refaire F-76 au milieu de
  l'écran qui existe pour ne pas le faire. Les aperçus gardent leur place sur l'accueil de la Forge.
  **Réversible** : une entrée de plus sur la tuile.
- **Une seule minuterie pour tout l'écran** (1 s pour les chronomètres, 5 s pour le registre) :
  quatre tuiles × deux minuteries seraient huit réveils par seconde pour afficher des secondes.
