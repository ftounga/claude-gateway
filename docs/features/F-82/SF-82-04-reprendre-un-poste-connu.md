# Mini-spec — F-82 / SF-82-04 — Reprendre un poste connu ne coûte plus un code

## Identifiant

`F-82 / SF-82-04`

## Feature parente

`F-82` — Arrêter proprement, et savoir quand on n'y arrive pas

## Statut

`done`

## Date de création

2026-09-12

## Branche Git

`feat/SF-82-04-reprendre-poste-connu`

---

## Objectif

Quand un poste **déjà appairé** n'est pas connecté, le parcours de mise en service propose **la
reprise d'abord** — une ligne à copier, aucun code à générer — et ne demande plus, ni en mode poste
ni en mode projet, autre chose que **la racine du poste**.

---

## Contexte — deux constats du PO, un seul parcours

Les deux défauts vivent dans le même fichier (`runner-pairing-dialog.component.{ts,html}`) et dans
la même décision : « de quoi ce poste a-t-il besoin, là, maintenant ? ». Ils sont traités ensemble.

### D5 — « non connecté » recouvre deux situations que l'écran ne distingue pas

`runner-pairing-dialog.component.ts:572` :

```ts
this.step.set(this.hostAlreadyLive() ? null : 'code');
```

| État du poste | Ce qu'il faut réellement | Ce que l'écran propose aujourd'hui |
|---|---|---|
| Connecté | rien | rien — **juste**, c'est le gain de F-48 |
| Jamais appairé | un code d'appairage | un code — **juste** |
| **Appairé, runner éteint** | `java -jar claude-runner.jar`, **sans argument** | **un code** — inutile, et il expire en 5 min |

Le troisième cas est le cas **courant** : une machine qu'on rallume, un `Ctrl-C` de la veille. La
commande de reprise **existe** (`…component.html:903`, `resumeCommand()`) mais dans la
**conclusion**, sous la commande complète : ce dont on a besoin le plus souvent est ce qu'on voit en
dernier.

### D6 — le mode projet demande une « racine du projet », et fait un poste par projet

`runner-pairing-dialog.component.html:870` :

```html
{{ hostMode ? 'Racine du poste sur la machine' : 'Racine du projet sur la machine' }}
```

Cette valeur part telle quelle dans `--root` (`ts:997`) et dans la commande de reprise (`ts:1011`).
Or `--root` **est la racine du poste** — le dossier sous lequel vivent **tous** les projets
(F-48 / SF-48-02). En mode projet, le runner déclare donc la machine comme si elle commençait au
dossier du projet : la racine du poste devient `~/dev/mon-projet`, « Ajouter un projet » n'offre
plus que les sous-dossiers de ce projet, et l'on retombe sur **un poste par projet** — le modèle que
F-48 a supprimé.

Les chemins d'exemple disent la même chose (`C:\Users\moi\projets\mon-projet`) : ils décrivent un
**projet**, pas une racine de machine.

---

## Comportement attendu

### Cas nominal 1 — poste appairé, runner éteint (D5)

1. L'utilisateur ouvre le parcours depuis l'en-tête d'un terminal de projet.
2. Le relevé d'état du poste (déjà en place, `GET .../runner/status`) rapporte
   `connected: false` **et** `paired: true` (le poste porte un jeton valide et non révoqué).
3. L'écran affiche **au-dessus du parcours** un encart **« Relancer le runner »** :
   - la commande **sans argument** (`java -jar claude-runner.jar`, ou le lanceur du paquet retenu),
     avec son bouton de copie ;
   - le rappel qu'elle se lance **depuis le dossier du poste où le runner a été déposé** ;
   - la mention de la racine déjà déclarée par la machine quand elle est connue ;
   - **un repli nommé** : « La machine a changé, ou le jeton a été révoqué » → un bouton qui déplie
     l'étape « Générer un code ».
4. Le parcours complet reste **entier et replié** dessous ; tout en-tête reste cliquable.
5. Dès que le runner se signale, la conclusion « Machine connectée » remplace l'encart — comportement
   existant, inchangé.

### Cas nominal 2 — la racine demandée est celle du poste (D6)

1. En mode **projet** comme en mode **poste**, le champ s'intitule **« Racine du poste sur la
   machine »**, et son aide dit que **tous** les projets vivent dessous.
2. Les chemins d'exemple décrivent une racine de machine (`C:\Users\moi\projets`,
   `/Users/moi/projets`, `/chemin/vers/vos/projets`) et non un projet.
3. Quand le poste retenu a **déjà déclaré** une racine, l'écran le **dit** (« Cette machine a déjà
   déclaré sa racine : « projets » — reprenez le même dossier ») au lieu de laisser croire qu'un
   chemin neuf est attendu.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| **Tous les jetons du poste sont révoqués** (coupe-circuit SF-38-08) | `paired: false` → **aucune** reprise proposée, l'étape « code » s'ouvre comme aujourd'hui | 200 |
| Jeton présent mais **expiré** (TTL 30 j) | `paired: false` → étape « code » | 200 |
| Poste **connecté** (`hostAlreadyLive`) | inchangé : conclusion, aucun encart de reprise | 200 |
| Projet rattaché à **aucun poste** | `paired: false`, `hostId: null` → parcours complet | 200 |
| Le relevé d'état échoue (403/404/réseau) | silencieux, comme aujourd'hui : pas de reprise proposée, parcours complet | — |
| Gateway antérieure (champ `paired` absent) | traité comme `false` → parcours complet, aucune régression | — |
| Un code a déjà été demandé par l'utilisateur | l'encart de reprise apparaît, mais **l'étape « code » n'est pas repliée** : on n'escamote pas un code obtenu | — |

---

## Critères d'acceptation

- [x] `GET /api/workspaces/{id}/runner/status` et `GET /api/runner-hosts/{id}/status` renvoient un
      champ `paired` : vrai **si et seulement si** le poste porte au moins un jeton `revoked_at IS
      NULL` **et** `expires_at > now`.
- [x] Un projet rattaché à aucun poste renvoie `paired: false`.
- [x] Un poste dont **tous** les jetons sont révoqués renvoie `paired: false`.
- [x] Le champ est lu sous **isolation `user_id`** : les jetons sont relus par
      `findByUserIdAndHostIdOrderByCreatedAtDesc`, jamais par `host_id` seul.
- [x] Le dialogue affiche l'encart de reprise **si et seulement si** `paired && !connected` et qu'un
      poste est connu.
- [x] L'encart porte la commande **sans argument** et un bouton de copie qui copie exactement
      cette commande.
- [x] L'encart porte un repli **nommé** qui ouvre l'étape « Générer un code » en un clic.
- [x] Quand la reprise devient possible et qu'aucun code n'a été demandé, le parcours se **replie**
      au lieu d'ouvrir l'étape « code ».
- [x] Quand un code a déjà été demandé, l'étape « code » **reste ouverte**.
- [x] `hostAlreadyLive` est inchangé : un poste connecté saute toujours à la conclusion.
- [x] Le champ de racine s'intitule « Racine du poste sur la machine » dans **les deux** modes.
- [x] Les chemins d'exemple ne contiennent plus de segment de projet.
- [x] La racine déjà déclarée par la machine est rappelée quand elle est connue.
- [x] Aucune couleur ni police hors `docs/DESIGN_SYSTEM.md` (jetons `--cg-*` uniquement).

---

## Périmètre

### Hors scope (explicite)

- **Arrêter le runner depuis l'application** — hors périmètre de F-82 entier.
- **Mémoriser le chemin absolu de la racine côté gateway** : `runner_hosts.root_name` ne stocke
  volontairement que le **dernier segment** (l'arborescence d'une machine cliente n'a rien à faire
  dans la base). Le pré-remplissage du champ est donc **impossible sans changer cette décision** —
  on rappelle le segment, on ne devine pas le chemin.
- La carte du poste (`postes.component`) — c'est SF-82-02.
- Le mode **poste** du parcours : il demandait déjà la bonne chose.
- Toute modification du coupe-circuit, du repli de transport, ou du crochet d'arrêt du runner.

---

## Valeurs initiales

Aucune entité créée. Aucun état initial modifié.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|---|---|---|---|---|---|
| `paired` (réponse) | Oui | — | booléen | — | calculé, jamais reçu d'un client |
| racine du poste (saisie) | Non | 200 (inchangé) | texte libre, reste dans le navigateur | Non | `trim()` (inchangé) |

Notes :
- `paired` est **dérivé**, jamais persisté : aucune colonne, aucune migration.
- La racine saisie **ne quitte pas le navigateur** (décision d'origine du dialogue) — inchangé.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Changement |
|---|---|---|---|
| GET | `/api/workspaces/{id}/runner/status` | Oui | **+ champ `paired`** (additif) |
| GET | `/api/runner-hosts/{hostId}/status` | Oui | **+ champ `paired`** (additif) |

### Tables impactées

| Table | Opération | Notes |
|---|---|---|
| `runner_tokens` | SELECT | lecture déjà faite par `statusOf` — aucune requête de plus |

### Migration Liquibase

- [ ] Oui
- [x] Non applicable — aucun changement de schéma.

### Composants Angular

- `RunnerPairingDialogComponent` — encart de reprise, cible d'étape, libellés de racine.

---

## Plan de test

### Tests unitaires — backend

- [x] `RunnerStatusServiceTest` — jeton valide non révoqué → `paired = true`.
- [x] `RunnerStatusServiceTest` — tous les jetons révoqués → `paired = false`.
- [x] `RunnerStatusServiceTest` — jeton expiré → `paired = false`.
- [x] `RunnerStatusServiceTest` — projet sans poste → `paired = false`.

### Tests d'intégration — backend

- [x] `RunnerStatusApiIntegrationTest` — la réponse porte `paired` et il vaut `true` sur un poste
      appairé, `false` après révocation.

### Tests unitaires — frontend

- [x] `paired && !connected` → `resumeAvailable()` vrai, encart rendu.
- [x] `paired && connected` → pas d'encart (conclusion).
- [x] `!paired && !connected` → pas d'encart, étape « code ».
- [x] La commande de reprise ne porte ni `--gateway`, ni `--root`, ni `--code`.
- [x] Le repli nommé ouvre l'étape « code ».
- [x] Le parcours se replie quand la reprise devient possible sans code demandé ; il ne se replie
      pas si un code est affiché.
- [x] Le libellé de racine est identique dans les deux modes.
- [x] Les chemins d'exemple ne contiennent pas de segment de projet.

### Isolation utilisateur

- [x] Applicable — `paired` est calculé à partir de jetons relus par `user_id` **et** `host_id` ; le
      poste est lui-même vérifié possédé (`requireOwned`) avant tout calcul.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Composants impactés |
|---|---|---|
| Auth / Principal | Non | — |
| Contexte tenant | **Oui, en lecture seule** | `RunnerStatusService.statusOf` (déjà filtré `user_id`), `RunnerStatusService.status` (via `requireOwned`), `RunnerHostController#status`, `RunnerManagementController#status`. Aucun nouveau chemin de résolution du tenant : le champ ajouté est calculé **dans** la lecture existante, sur les mêmes lignes déjà relues. |
| Plans / limites | Non | — |
| Navigation / routing | Non | — |

---

## Dépendances

### Subfeatures bloquantes

- `SF-48-01` (le poste) — done
- `SF-46-01/02` (le runner mémorise passerelle et racine) — done
- `SF-38-08` (coupe-circuit / révocation) — done

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

**D5-a — un encart, pas une sixième étape.** Les cinq étapes sont numérotées en dur dans le
gabarit ; en insérer une sixième renumérotait tout le parcours pour un cas qui n'est **pas** une
étape mais un **raccourci**. L'encart vit au-dessus, comme la conclusion, et le parcours reste
strictement intact.

**D5-b — la commande de reprise de l'encart est nue.** Celle de la conclusion (`resumeCommand()`)
préfixe un `cd "<chemin saisi>"`, ce qui a du sens juste après que l'utilisateur a tapé sa racine.
À la réouverture du dialogue, ce chemin est vide et le `cd` porterait le chemin **d'exemple** — une
commande faussement prête. L'encart affiche donc le lanceur seul et **dit** d'où le lancer.
`resumeCommand()` n'est pas touché.

**D5-c — on ne replie jamais un code déjà obtenu.** La mise en avant de la reprise s'exerce une
seule fois, et jamais au détriment d'un code affiché : un code expire en cinq minutes, le faire
disparaître serait le perdre.

**D6-a — le pré-remplissage est impossible, et c'est voulu.** `runner_hosts.root_name` ne porte que
le dernier segment. Rappeler ce segment est le maximum honnête ; stocker le chemin absolu d'une
machine cliente serait un changement de périmètre, pas une commodité.

**D6-b — le mode projet reste.** Il sert à **rattacher** un projet à un poste. Ce qui disparaît,
c'est l'idée qu'on appaire un dossier de projet.

---

## Livraison

- PR : `feat(F-82): reprendre un poste connu sans code, et une racine qui est celle du poste (SF-82-04)`
- Mergée le 2026-09-12 (squash).
