# Mini-spec — F-49 / SF-49-03 — L'appartenance se voit

---

## Identifiant

`F-49 / SF-49-03`

## Feature parente

`F-49` — Vue d'ensemble des postes (rouverte le 2026-09-10, voir `docs/PRODUCT_SPEC.md`)

## Statut

`done` — PR #346, mergée le 2026-09-10

## Date de création

2026-09-10

## Branche Git

`feat/SF-49-03-identite-visuelle-postes`

---

## Objectif

Donner à chaque **poste** une identité visuelle **stable et dérivée de son nom** — une couleur et
des initiales, calculées par fonction pure, jamais stockées — et la porter partout où un projet
apparaît, jusque dans **l'en-tête du terminal**, sans que la couleur ne porte jamais seule une
information.

---

## Comportement attendu

### Cas nominal

1. **La couleur se calcule, elle ne se range pas.** `hostTone(nom)` réduit le nom normalisé
   (NFC, `trim`, espaces internes ramenés à un seul, minuscules) par un hachage **FNV-1a 32 bits**,
   et l'indexe dans une palette **fermée de 10 tons** validés pour le contraste. Le résultat ne
   dépend que du nom : identique d'une session à l'autre, d'un écran à l'autre, d'un poste de
   consultation à l'autre. **Aucune persistance** — ni base, ni `localStorage`, ni champ d'API.
2. **Les initiales aussi.** `hostInitials(nom)` rend une ou deux lettres majuscules : première
   lettre des deux premiers mots (séparateurs ` `, `-`, `_`, `.`, `/`), ou les deux premières
   lettres d'un mot unique.
3. **Un objet visuel unique** : `app-host-badge` — une pastille carrée arrondie remplie du ton
   *solide* du poste, portant ses initiales en blanc, suivie (par défaut) du **nom écrit**.
4. **Il est porté à trois endroits** :
   - **La vue d'ensemble** (`/postes`) : chaque carte de poste reçoit un **filet vertical** de sa
     couleur sur son bord gauche, une pastille en grand format dans l'en-tête à côté du nom, et
     chaque projet rangé dessous un **filet gauche** du même ton — ce qui rattache visuellement le
     projet à sa machine sans rien écrire de plus.
   - **La liste des projets** (barre latérale de la Forge) : chaque projet rattaché à un poste
     porte, sous son nom, la pastille du poste **et le nom du poste écrit**. Un projet non rattaché
     n'affiche **rien** de plus (jamais « aucun poste », qui se lirait comme un défaut).
   - **L'en-tête du terminal** : la pastille du poste **et son nom écrit** ouvrent la barre, avant
     le nom du projet. C'est là qu'on travaille, et c'est là qu'on doit savoir chez quel client on
     est.
5. **Le nom du poste arrive au terminal sans aller-retour supplémentaire** : `GET /api/workspaces`
   rend désormais `hostName` par projet (champ additif), et la Forge lit celui du projet ouvert,
   avec repli sur `RunnerStatus.hostName` déjà connu.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Nom de poste vide ou seulement des espaces | Ton **0** de la palette (déterministe) et initiales `?` — jamais d'exception, jamais de `NaN` | — |
| Nom sans caractère alphanumérique (`«  —  »`, emoji seuls) | Initiales `?`, ton déterministe ; le nom reste écrit tel quel | — |
| Projet non rattaché à un poste (`hostName` absent/`null`) | Aucune pastille, aucun filet coloré, aucun texte de repli | — |
| Backend antérieur ne renvoyant pas `hostName` | Champ optionnel : la liste s'affiche exactement comme avant | 200 |
| `GET /api/workspaces` appelé par un autre utilisateur | Les postes lus sont **filtrés `user_id`** ; aucun nom de poste d'autrui ne peut apparaître | 200 (liste vide) |

---

## Critères d'acceptation

- [ ] `hostTone(nom)` est une **fonction pure** : deux appels sur le même nom rendent le même ton, et
      rien n'est écrit nulle part (aucun `localStorage`, aucune colonne, aucun champ d'API).
- [ ] `hostTone` est **insensible à la casse et aux espaces de bord** : `« Client Alpha »`,
      `«client alpha»` et `«  Client   Alpha  »` rendent le même ton.
- [ ] Deux noms différents donnent, en pratique, des tons différents (vérifié sur un échantillon de
      noms plausibles : la palette de 10 est couverte, aucune valeur hors bornes).
- [ ] `hostInitials` rend `« PC »` pour `« Poste CAGIP »`, `« MA »` pour `« macbook-air »`,
      `« WE »` pour `« web »`, `« ? »` pour `«   »`.
- [ ] **Accessibilité — contraste** : pour **chacun** des 10 tons, un test calcule les ratios WCAG
      2.1 et exige `blanc sur solide ≥ 4.5:1`, `encre sur blanc ≥ 4.5:1`, `encre sur teinte ≥ 4.5:1`.
      Aucun ton ne peut entrer dans la palette sans passer ce test.
- [ ] **Accessibilité — la couleur ne porte jamais seule** : partout où la pastille apparaît, le nom
      du poste est **écrit en toutes lettres** dans le même bloc ; quand la place manque au point de
      masquer le nom, la pastille porte `role="img"` et un `aria-label` qui le nomme.
- [ ] La vue d'ensemble `/postes` montre, pour chaque poste, sa pastille et son filet de couleur, et
      chaque projet dessous porte le filet du **même** poste.
- [ ] La barre latérale des projets montre la pastille **et le nom écrit** du poste de chaque projet
      rattaché, et **rien** pour un projet non rattaché.
- [ ] L'en-tête du terminal montre la pastille **et le nom écrit** du poste du projet ouvert, et
      **rien** quand le projet n'est rattaché à aucun poste.
- [ ] `GET /api/workspaces` rend `hostName` — le nom du poste possédé par **l'utilisateur courant**,
      `null` sinon — sans requête par projet (une seule lecture des postes de l'utilisateur).
- [ ] Aucun acquis de la passe de cohérence **F-56** (PR #342) n'est annulé : les pastilles d'état
      restent des `.badge` de la charte, les polices restent des jetons `--cg-font-*`, les
      espacements restent des jetons `--cg-space-*`.
- [ ] `npm run build` et `npm test` verts ; `mvn -pl backend test` vert.

---

## Périmètre

### Hors scope (explicite)

- **Choisir sa couleur** : aucune personnalisation, aucun sélecteur, aucune persistance. La couleur
  est une **conséquence du nom** ; la changer, c'est renommer le poste.
- **Colorer autre chose que le poste** : ni les projets, ni les conversations, ni l'avatar
  utilisateur ne reçoivent d'identité dérivée ici.
- **Le mode sombre** : le produit n'en a pas ; la palette est validée sur fond clair uniquement.
- **Toucher à la charte au-delà de l'ajout de la palette d'identité** (§9) — la divergence interne
  de `DESIGN_SYSTEM.md` relevée par F-56 reste en `OQ-15`.
- **Agir sur un poste depuis la vue d'ensemble** : l'écran reste en lecture seule (SF-49-02).
- **Renommer un poste depuis ces écrans.**

---

## Valeurs initiales

Sans objet : aucune entité créée, aucune colonne ajoutée, aucun état initial modifié.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `name` (entrée de `hostTone` / `hostInitials`) | Non — `''`, `null` et `undefined` sont acceptés | — | Texte libre (nom de poste existant) | Non | `String.normalize('NFC')`, `trim()`, espaces internes ramenés à un, `toLowerCase()` pour le ton uniquement |
| `HOST_IDENTITY_PALETTE` | Oui | 10 entrées | `{ solid, ink, tint }`, hex `#rrggbb` | Oui (index) | — |
| `hostName` (`GET /api/workspaces`) | Non | 120 (contrainte existante du poste) | Nom du poste possédé, `null` si non rattaché | Non | Aucune — rendu tel qu'enregistré |

Notes :
- Les initiales sont calculées sur le nom **non minusculisé** puis passées en majuscules : un nom en
  minuscules donne malgré tout des initiales majuscules.
- Le hachage porte sur le nom **minusculisé** : renommer la casse ne change pas la couleur.
- Renommer réellement un poste **change** sa couleur. C'est voulu et documenté : la couleur est une
  fonction du nom, pas une donnée du poste.

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum | Changement |
|---------|-----|------|-------------|------------|
| GET | `/api/workspaces` | Oui | utilisateur authentifié + droit Atelier | **Additif** : champ `hostName` (nullable) dans chaque élément |

Aucun autre endpoint touché. `GET /api/runner-hosts/overview` (SF-49-01) rend déjà le nom du poste :
la vue d'ensemble n'a rien à demander de plus.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `runner_hosts` | SELECT | Une lecture par appel de liste, filtrée `user_id` (méthode existante `RunnerHostService.list`) |
| `workspaces` | SELECT | Inchangé |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucune colonne, aucune table. La couleur est calculée, pas rangée.

### Composants Angular

- `shared/host-identity.ts` — **fonction pure** : palette fermée, `hostInitials`, `hostTone`,
  `hostIdentity`. Aucune dépendance Angular, aucun effet de bord.
- `shared/host-badge/host-badge.component.ts` — composant de présentation autonome : pastille
  d'initiales + nom écrit. Entrées `name`, `size` (`sm` | `md` | `lg`), `showName`.
- `postes/postes.component.*` — filet coloré de carte, pastille d'en-tête, filet des projets.
- `atelier/atelier.component.*` — pastille + nom du poste dans la liste des projets ; nouvelle
  entrée `hostName` passée au terminal.
- `atelier/terminal/atelier-terminal.component.*` — pastille + nom du poste en tête de barre.
- `core/models/atelier.models.ts` — `WorkspaceSummary.hostName?: string | null`.

### Backend

- `atelier/dto/WorkspaceSummaryResponse` — composante `hostName` + fabrique
  `from(Workspace, String)`.
- `atelier/AtelierController#list` — une lecture des postes de l'utilisateur (déjà injectée),
  indexée en `Map<UUID, String>`, puis mappage. **Pas de N+1**, pas de logique métier ajoutée au
  contrôleur au-delà d'un mappage de présentation.

---

## Plan de test

### Tests unitaires (frontend, Jasmine)

- [ ] `host-identity` — **pureté / stabilité** : `hostTone('Client Alpha')` rend deux fois le même
      ton ; identique après normalisation de casse et d'espaces.
- [ ] `host-identity` — **bornes** : l'index tiré reste dans `[0, 9]` pour 500 noms générés ;
      `hostTone('')` ne lève pas et rend un ton valide.
- [ ] `host-identity` — **dispersion** : un échantillon de noms plausibles couvre les 10 tons.
- [ ] `host-identity` — **initiales** : deux mots, un mot, séparateurs `-`/`_`/`.`, accents,
      chaîne vide, caractères non alphanumériques seuls.
- [ ] `host-identity` — **contraste WCAG** (le test calcule lui-même les ratios) : les 3 seuils
      ≥ 4.5:1 sur **chacune** des 10 entrées de la palette.
- [ ] `host-badge` — rend les initiales, écrit le nom quand `showName`, et pose `role="img"` +
      `aria-label` quand le nom n'est pas écrit.

### Tests d'intégration (frontend, TestBed)

- [ ] `PostesComponent` — chaque carte porte la couleur du poste, et deux postes de noms différents
      ne portent pas la même ; le nom reste écrit.
- [ ] `PostesComponent` — les projets d'une carte portent le ton **de leur poste**.
- [ ] `AtelierComponent` — la liste des projets écrit le nom du poste d'un projet rattaché et
      **n'écrit rien** pour un projet non rattaché.
- [ ] `AtelierTerminalComponent` — l'en-tête porte la pastille et le nom du poste quand `hostName`
      est fourni, et rien quand il vaut `null`.

### Tests d'intégration (backend, Spring)

- [ ] `GET /api/workspaces` → 200, `hostName` renseigné pour un projet rattaché.
- [ ] `GET /api/workspaces` → 200, `hostName` à `null` pour un projet non rattaché.

### Isolation utilisateur

- [x] **Applicable** — test : un projet rattaché à un poste **d'un autre utilisateur** ne fait
      apparaître **aucun** nom de poste (`hostName` reste `null`) : la carte des noms est bâtie
      depuis `RunnerHostService.list(userId)`, filtrée `user_id`, jamais depuis `hostId` seul.

---

## Dépendances

### Subfeatures bloquantes

- `SF-49-01` — statut : **done** (l'agrégat rend déjà le nom du poste)
- `SF-49-02` — statut : **done** (l'écran à décorer)
- `SF-48-01` — statut : **done** (le poste existe et porte un nom)
- `SF-56-01` — statut : **done** (passe de cohérence ; ne rien annuler)

### Questions ouvertes impactées

- [ ] `OQ-15` (contradiction interne de `DESIGN_SYSTEM.md`, ouverte par F-56) — **non tranchée ici**
      et volontairement non touchée : cette SF **ajoute** une palette d'identité (§9), elle ne
      corrige pas la table §2.

---

## Notes et décisions

### Arbitrage 1 — Une palette fermée de 10 tons validés, plutôt qu'une teinte HSL calculée

**Décision** : le hachage choisit un **index** dans une palette écrite à la main, et non une teinte
HSL générée à la volée.
**Pourquoi** : la contrainte d'accessibilité est *non négociable*, et « le contraste doit rester
conforme **quelle que soit** la couleur tirée » n'est démontrable que sur un ensemble **fini**. Une
teinte calculée oblige à démontrer le contraste sur 360 valeurs et à corriger les jaunes-verts au
cas par cas ; une palette de 10 se prouve par un test qui les parcourt toutes.
**Alternative écartée** : `hsl(hash % 360, 45%, 40%)` — élégant, mais la luminance perçue varie
fortement à luminosité HSL constante, et le contraste réel dépendrait de la teinte tirée.
**Réversible** : oui — la palette est une constante ; en changer les valeurs ou la taille ne touche
ni la base, ni l'API, ni un écran.

### Arbitrage 2 — La couleur n'est pas persistée, même pas côté client

**Décision** : rien n'est écrit — ni colonne, ni `localStorage`.
**Pourquoi** : c'est la demande, et c'est ce qui rend la couleur identique sur un autre poste de
consultation, dans une autre session, chez un autre utilisateur. Une couleur rangée en base serait
la même sur un seul navigateur et divergerait dès la première restauration.
**Contrepartie assumée** : renommer un poste change sa couleur.
**Réversible** : oui.

### Arbitrage 3 — La palette d'identité est **ajoutée à la charte** (§9), pas glissée dans le code

**Décision** : `docs/DESIGN_SYSTEM.md` reçoit une section §9 qui déclare la palette d'identité, son
usage exclusif (identifier un poste) et ses seuils de contraste.
**Pourquoi** : §8 de la charte interdit « les couleurs hors palette **sans validation explicite** ».
Dix tons introduits en douce dans un fichier TypeScript seraient exactement cela. Les déclarer dans
la charte est la validation explicite que la règle exige, et donne à la review un référent.
**Ce que ce n'est pas** : une refonte. La table §2 (rôles applicatifs) n'est pas touchée, la
contradiction interne relevée par F-56 reste en `OQ-15`.
**Réversible** : oui.

### Arbitrage 4 — `hostName` sur `GET /api/workspaces`, plutôt qu'un appel de plus depuis l'écran

**Décision** : champ additif sur le résumé de projet, alimenté par **une** lecture des postes de
l'utilisateur.
**Pourquoi** : la barre latérale et l'en-tête du terminal ont besoin du nom du poste de **chaque**
projet. Les alternatives sont pires : appeler `/runner-hosts` en plus depuis le frontend ajoute un
aller-retour et un état à synchroniser ; lire le poste projet par projet fait du N+1.
**Alternative écartée** : dériver le nom de `RunnerStatus`, déjà chargé par le terminal — il n'existe
que pour le projet **ouvert** et seulement en cible `RUNNER`, donc la liste resterait aveugle. Il est
conservé comme **repli**.
**Réversible** : oui — champ optionnel, aucune migration, un client antérieur l'ignore.

### Arbitrage 5 — Filet de couleur, pas fond coloré

**Décision** : la couleur entre par un **filet** (bord gauche) et une **pastille**, jamais par le
fond d'une carte.
**Pourquoi** : `DESIGN_SYSTEM.md` §8 interdit le fond coloré sur les cartes, et un aplat teinté
derrière du texte remettrait le contraste en jeu à chaque ton. Le filet est visible de loin — c'est
ce que la demande appelle « très visuel » — sans rien mettre derrière un mot.
**Réversible** : oui.
