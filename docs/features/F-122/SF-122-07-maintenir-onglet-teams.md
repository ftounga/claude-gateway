# Mini-spec — [F-122 / SF-122-07] Le Chrome managé maintient un onglet Teams connecté

## Identifiant

`F-122 / SF-122-07`

## Feature parente

`F-122` — Mise en service automatique et guidée de la Vigie

## Statut

`in-progress`

## Date de création

2026-09-19

## Branche Git

`feat/SF-122-07-maintenir-onglet-teams`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire en sorte que le Chrome managé **maintienne** en permanence un onglet `teams.microsoft.com` ouvert (le **rouvrir** s'il a été fermé), pour que « Teams connecté » puisse passer au vert **sans** rejoindre de réunion et que le Radar puisse **observer en continu** ; le relogin (SF-122-03 `reveal()`/`remask()`) s'enchaîne quand la session est expirée.

---

## Comportement attendu

### Cas nominal

1. Au lancement, le Chrome managé ouvre déjà `teams.microsoft.com` (SF-122-01 : la ligne de commande porte l'URL de démarrage).
2. À chaque relevé de la Vigie (boucle SF-122-06), quand le Chrome est **joignable**, on **s'assure** qu'un onglet Teams (ou une page d'identification Microsoft) est présent : on liste les cibles (`/json/list`).
3. Si un onglet Teams **ou** une page d'identification Teams est présent → **rien** (on ne double pas les onglets).
4. Si **aucun** n'est présent (l'utilisateur a fermé l'onglet) → on **rouvre** `teams.microsoft.com` dans le Chrome managé (`PUT /json/new?<url>` sur le port de débogage, boucle locale sans proxy).
5. Le relevé suivant peut alors attacher l'onglet, sonder Teams, et « Teams connecté » passe au vert (si signé) — **sans** aucune réunion. Si la session est expirée, la sonde voit la page d'identification et le relogin (SF-122-03) fait **surgir** la fenêtre, puis la **remasque**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Chrome injoignable au moment du maintien | La garde n'est appelée que si le Chrome est joignable ; sinon elle ne tourne pas (le relevé remonte déjà l'état Chrome) |
| `/json/list` illisible / port muet pendant le maintien | Best-effort strict : l'échec est avalé (jamais fatal pour la boucle ni le heartbeat), retenté au prochain relevé |
| `PUT /json/new` échoue | Avalé, diagnostiqué ; retenté au relevé suivant |
| Un onglet Teams est déjà là | Aucune ouverture (idempotent, pas de spam d'onglets) |
| Une page d'identification Teams est là | Aucune ouverture (c'est le relogin SF-122-03 qui prend le relais, pas un nouvel onglet) |

---

## Critères d'acceptation

- [ ] Quand le Chrome est joignable et qu'aucun onglet Teams ni page d'identification n'existe, `teams.microsoft.com` est rouvert (`/json/new`).
- [ ] Quand un onglet Teams existe déjà, **aucune** ouverture (idempotent).
- [ ] Quand une page d'identification Teams existe, **aucune** ouverture (relogin SF-122-03 inchangé).
- [ ] Le maintien est **best-effort strict** : toute erreur est avalée et n'interrompt ni la boucle Vigie ni le heartbeat.
- [ ] Le maintien n'est appelé que lorsque le Chrome est **joignable** (pas de tentative sans navigateur).
- [ ] Diagnostic F-132 : une réouverture émet un événement clair (`chrome/teams_tab`), best-effort.
- [ ] Non-régression : boucle Vigie (SF-122-06), relogin (SF-122-03), auto-accept (SF-122-05), `TeamsAdapterV1`, heartbeat, F-132.

---

## Périmètre

### Hors scope (explicite)

- L'ouverture **au lancement** existe déjà (SF-122-01) ; cette SF ajoute le **maintien** (réouverture).
- La reprise après **fermeture complète de Chrome** est SF-122-08 (distincte).
- La capture d'onglet (F-128) navigue ce même onglet vers la réunion — inchangée.
- Aucun changement backend / frontend / base de données.

---

## Technique

### Fichiers impactés (runner)

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `TeamsTabOpener.java` | AJOUT | garde de maintien : liste les cibles, rouvre `teams.microsoft.com` si absent ; HTTP boucle locale sans proxy, injecté pour test |
| `VigieLoop.java` | MODIF | champ optionnel `TabGuard` (no-op par défaut) + appel best-effort dans `tick()` quand le Chrome est joignable |
| `RunnerConnection.java` | MODIF | branche le `TeamsTabOpener` réel sur la boucle Vigie |

### Migration Liquibase

- [x] Non applicable (fichiers runner uniquement)

### Composants Angular

- Aucun.

---

## Plan de test

### Tests unitaires (runner, sans navigateur)

- [ ] `TeamsTabOpenerTest` — aucun onglet Teams ⇒ `PUT /json/new?teams.microsoft.com` émis.
- [ ] `TeamsTabOpenerTest` — un onglet Teams déjà présent ⇒ aucune ouverture.
- [ ] `TeamsTabOpenerTest` — page d'identification Teams présente ⇒ aucune ouverture (relogin prend le relais).
- [ ] `TeamsTabOpenerTest` — `/json/list` illisible / erreur HTTP ⇒ avalé (best-effort), aucune exception.
- [ ] `TeamsTabOpenerTest` — une réouverture émet un diagnostic F-132 `chrome/teams_tab`.

### Tests d'intégration (boucle)

- [ ] `VigieLoopTest` — Chrome joignable ⇒ la garde d'onglet est appelée avant la sonde.
- [ ] `VigieLoopTest` — Chrome injoignable / NO_BROWSER ⇒ la garde **n'est pas** appelée.
- [ ] `VigieLoopTest` — une garde qui explose ne casse pas le `tick` (best-effort).

### Isolation utilisateur

- [x] Non applicable — code runner local, aucune donnée multi-tenant. Le maintien n'ouvre que `teams.microsoft.com` sur la boucle locale.

---

## Préoccupations transversales

- **Navigation / routing** : « navigation » = ouverture d'un onglet **dans le Chrome managé** (endpoint de débogage), pas le routing Angular. Composants runner impactés listés ci-dessus (`TeamsTabOpener`, `VigieLoop`, `RunnerConnection`). Aucun impact produit.
- **Auth / Principal**, **Contexte tenant**, **Plans / limites** : non touchés.

---

## Dépendances

### Subfeatures bloquantes

- `SF-122-01` (le runner gère un Chrome dédié) — done
- `SF-122-03` (reveal/remask, veille de session) — done
- `SF-122-06` (boucle Vigie câblée) — done

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Constat** : `ManagedChrome.commandLine()` porte déjà `teams.microsoft.com` (SF-122-01), donc un onglet Teams s'ouvre au lancement. Le manque était le **maintien** : si l'utilisateur ferme l'onglet (Chrome restant ouvert), rien ne le rouvrait, la sonde échouait en `TEAMS_NOT_OPEN` (que `VigieSonde.feedFromFailure` ne traduit pas), « Teams connecté » restait `UNKNOWN` et le Radar ne pouvait pas observer.
- **Choix** : ouvrir via l'endpoint HTTP de débogage (`/json/new`), pas par une commande CDP — il n'existe pas de cible à laquelle envoyer `Page.navigate` quand il n'y a plus d'onglet Teams. L'endpoint est sur la boucle locale, sans proxy (comme la découverte existante, `BrowserLink.httpGet`).
- **Relogin** : inchangé — une fois l'onglet rouvert, s'il tombe sur une page d'identification, la sonde le voit et `reveal()`/`remask()` (SF-122-03) s'enchaînent.
