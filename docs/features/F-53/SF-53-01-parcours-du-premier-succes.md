# Mini-spec — F-53 / SF-53-01 — Le parcours du premier succès

## Identifiant

`F-53 / SF-53-01`

## Feature parente

`F-53` — Guide d'accueil

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-53-01-parcours-premier-succes`

---

## Objectif

Poser dans l'Atelier un **guide superposé à trois étapes** — créer un projet, connecter son poste,
voir une commande aboutir — qui **se coche sur des faits réels**, s'abandonne d'un seul geste, et se
souvient de son état sur le poste de l'utilisateur.

---

## Comportement attendu

### Cas nominal

1. Un utilisateur qui arrive dans l'Atelier **sans aucun projet** voit apparaître le guide, en haut à
   droite, sous le bandeau : trois étapes numérotées, la première dépliée, les autres réduites à leur
   titre.
2. **Étape 1 — « Créez votre projet »** : un bouton ouvre le parcours « Sur ma machine » déjà en
   place. Dès que l'utilisateur possède au moins un projet, l'étape se coche et le guide déplie la
   suivante.
3. **Étape 2 — « Connectez votre poste »** : un bouton ouvre le dialogue d'appairage (F-45 / F-48),
   où vivent la vérification réseau, la fiche pour la DSI et la commande de lancement. Le guide n'en
   redit rien : il annonce en une ligne ce qui va s'y passer et y renvoie. L'étape se coche dès que
   l'état du runner du projet ouvert passe à **connecté**.
4. **Étape 3 — « Faites exécuter une commande »** : le guide invite à demander quelque chose dans le
   terminal. L'étape se coche quand un tour **s'achève sans erreur sur le poste** (moteur
   `LOCAL_MACHINE`) — pas sur le bac à sable, qui n'est pas le premier succès visé.
5. Les trois étapes cochées, le guide affiche sa **conclusion** — « votre poste exécute » — avec un
   unique bouton `Terminer` qui le referme **définitivement**.
6. À tout instant, un bouton `Masquer le guide` l'abandonne : il disparaît et **ne revient pas de
   lui-même**.
7. Une étape déjà franchie **reste cochée** au rechargement de la page : l'état est relu depuis le
   navigateur.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| `localStorage` indisponible (navigation privée verrouillée, quota) | Le guide ne plante pas : il se comporte comme un guide neuf, et l'écriture échouée est ignorée |
| Contenu mémorisé illisible ou d'une autre version | Traité comme un guide neuf, sans exception remontée à l'écran |
| L'utilisateur possède déjà un projet, un poste connecté et un tour abouti | Le guide **ne s'affiche pas** : il n'a rien à raconter à qui a déjà réussi |
| L'Atelier est refusé (403 `atelier_forbidden`) | Aucun guide : l'écran d'appel à souscrire reste seul |
| Aucun projet ouvert à l'étape 2 ou 3 | Le bouton d'action de l'étape n'est pas proposé ; l'étape reste lisible |

---

## Critères d'acceptation

- [ ] Le guide apparaît de lui-même dans l'Atelier pour un utilisateur dont le parcours n'est pas
      accompli, et **jamais** pour un utilisateur qui l'a accompli, abandonné ou terminé.
- [ ] Les trois étapes sont : créer un projet, connecter son poste, faire exécuter une commande.
- [ ] Une étape se coche sur un **signal réel** (projet existant, `runnerStatus.connected`, tour
      abouti sur `LOCAL_MACHINE`), jamais sur un simple clic de navigation.
- [ ] L'étape courante est la première non cochée ; les autres sont repliées sur leur titre.
- [ ] L'étape 2 renvoie au dialogue d'appairage existant et **ne recopie ni** le diagnostic réseau,
      **ni** la fiche DSI, **ni** la commande de lancement.
- [ ] `Masquer le guide` le referme immédiatement, depuis n'importe quelle étape, et il ne réapparaît
      pas au rechargement.
- [ ] Les trois étapes cochées, la conclusion s'affiche et `Terminer` referme le guide pour de bon.
- [ ] L'état survit à un rechargement de page ; aucune donnée sensible n'est écrite (ni jeton, ni
      identifiant de poste, ni chemin).
- [ ] Aucune couleur ni police hors `docs/DESIGN_SYSTEM.md` ; aucun `window.alert/confirm/prompt`.
- [ ] Le guide ne couvre ni la zone de saisie du terminal, ni la bulle d'aide (F-54), et passe
      **sous** le rappel d'autorisation en attente (F-47).
- [ ] `npm run build` et `npm test` verts.

---

## Périmètre

### Hors scope

- La **reprise** du guide après abandon et la commande suggérée insérée en un clic (SF-53-02).
- Toute persistance côté serveur de l'avancement.
- Une visite guidée d'autres écrans que l'Atelier.
- Le moindre changement au dialogue d'appairage (F-45 / F-48) ou au terminal.

---

## Valeurs initiales

| Champ (état local) | Valeur initiale | Règle |
|---|---|---|
| `status` | `active` | `active` tant que le parcours court ; `dismissed` après abandon ; `done` après conclusion |
| `steps.project` | `false` | Passe à `true` dès qu'un projet existe, et n'en redescend jamais |
| `steps.host` | `false` | Passe à `true` au premier `runnerStatus.connected` observé |
| `steps.command` | `false` | Passe à `true` au premier tour abouti sur le poste |
| `version` | `1` | Une valeur inattendue = état neuf |

---

## Contraintes de validation

| Champ | Obligatoire | Format / valeurs | Normalisation |
|---|---|---|---|
| clé `localStorage` | oui | `cg_atelier_guide` — une seule clé, JSON | — |
| `version` | oui | entier `1` ; toute autre valeur donne un état neuf | — |
| `status` | oui | `active`, `dismissed` ou `done` ; toute autre valeur donne `active` | — |
| `steps` | oui | trois booléens ; toute autre valeur donne `false` | — |

Notes :
- Aucune donnée sensible n'est stockée : ni jeton, ni identifiant de poste, ni chemin de projet.
- L'état est **par navigateur**, non par utilisateur : c'est assumé (`localStorage`, comme F-12).

---

## Technique

### Endpoint(s)

Aucun — subfeature entièrement frontend.

### Tables impactées

Aucune.

### Migration Liquibase

Non applicable.

### Composants Angular

- `core/services/atelier-guide.service.ts` (**nouveau**) — lit et écrit l'état local, expose un
  `signal` de l'état, `markStep()`, `dismiss()`, `finish()`, et `shouldStart()` qui décide si un
  guide neuf doit s'ouvrir.
- `atelier/guide/atelier-guide.component.*` (**nouveau**) — panneau superposé, sans logique métier :
  entrées `steps`, `canAct` ; sorties `createProject`, `connectHost`, `dismiss`, `finish`.
- `atelier/atelier.component.ts` et son gabarit (**modifiés**) — branchent le service et le panneau,
  et cochent les étapes sur les signaux existants (`workspaces()`, `runnerStatus()`, fin de tour sur
  `LOCAL_MACHINE`).

---

## Plan de test

### Tests unitaires (Karma)

- [ ] `AtelierGuideService` — état neuf quand rien n'est mémorisé.
- [ ] `AtelierGuideService` — `markStep('host')` persiste et se relit.
- [ ] `AtelierGuideService` — `dismiss()` puis relecture : statut `dismissed`, le guide ne s'ouvre plus.
- [ ] `AtelierGuideService` — `finish()` : statut `done`.
- [ ] `AtelierGuideService` — contenu illisible ou version inconnue donne un état neuf, sans exception.
- [ ] `AtelierGuideService` — `localStorage` qui lève à la lecture **et** à l'écriture : aucune exception.
- [ ] `AtelierGuideService` — parcours déjà accompli : le guide ne démarre pas.
- [ ] `AtelierGuideComponent` — trois étapes rendues ; la courante est la première non cochée.
- [ ] `AtelierGuideComponent` — `Masquer le guide` émet `dismiss` depuis n'importe quelle étape.
- [ ] `AtelierGuideComponent` — trois étapes cochées : conclusion et bouton `Terminer` qui émet `finish`.
- [ ] `AtelierComponent` — un projet dans la liste coche l'étape 1.
- [ ] `AtelierComponent` — un runner connecté coche l'étape 2.
- [ ] `AtelierComponent` — fin de tour sur `LOCAL_MACHINE` coche l'étape 3 ; sur le bac à sable, non.
- [ ] `AtelierComponent` — guide non rendu après abandon, ni quand l'accès Atelier est refusé.

### Tests d'intégration

Sans objet : aucune route ni aucun accès serveur n'est ajouté.

### Isolation utilisateur

Non applicable — aucun accès aux données. L'état vit dans le navigateur et ne contient aucun
identifiant. La règle `user_id` reste tenue par l'API pour tous les appels existants.

---

## Analyse d'impact

### Préoccupations transversales touchées

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | inchangé |
| Contexte tenant | non | inchangé |
| Plans / limites | non | inchangé — le guide n'apparaît pas quand l'Atelier est refusé |
| Navigation / routing | non | aucune route neuve ; le panneau vit dans l'écran Atelier existant |

### Composants existants potentiellement impactés

| Composant | Impact potentiel | Non-régression prévue |
|---|---|---|
| `AtelierComponent` | Un panneau de plus dans le gabarit, et trois branchements de signaux | La suite existante de `atelier.component.spec.ts` reste verte |
| `AtelierTerminalComponent` | Aucun changement ; le panneau est superposé, pas inséré | Suite existante verte |
| Bulle d'aide (F-54) | Coin bas-droit — le guide est en haut à droite | Vérifié par la règle de position en SCSS |

---

## Dépendances

### Subfeatures bloquantes

- `F-48` (SF-48-01 à SF-48-03) — **done** : c'est le parcours décrit par l'étape 2.
- `F-45` (SF-45-01 à SF-45-05) — **done** : le dialogue d'appairage vers lequel le guide renvoie.

### Questions ouvertes impactées

Aucune.

---

## Notes et décisions

- **Panneau non modal** plutôt que `MatDialog` : les étapes s'accomplissent dans des dialogues, et un
  guide modal se fermerait à chaque geste. Décision de cadrage n° 1.
- **Position haut-droite** : le bas de l'écran porte la zone de saisie du terminal — la couvrir à
  l'étape 3 serait un contresens — et le coin bas-droit est pris par la bulle d'aide (F-54).
  `z-index` 950 : au-dessus de l'explorateur superposé (900), **sous** le rappel d'autorisation
  (1000), qui doit rester visible quoi qu'il arrive (F-47).
- **Une étape cochée ne se décoche jamais** : un poste momentanément déconnecté ne doit pas faire
  reculer le guide.
