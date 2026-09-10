# Mini-spec — F-58 / SF-58-01 — Le mot vu par l'utilisateur

## Identifiant

`F-58 / SF-58-01`

## Feature parente

`F-58` — « Atelier » devient « Forge »

## Statut

`done` — PR #344, mergée le 2026-09-10

## Date de création

2026-09-10

## Branche Git

`feat/SF-58-01-atelier-devient-forge`

---

## Objectif

Partout où l'utilisateur **lit** le mot, « Atelier » devient « **Forge** » — écrans, messages,
centre d'aide, console du runner —, sans qu'aucune route, aucun endpoint, aucune classe ni aucune
table ne bouge.

---

## Comportement attendu

### Cas nominal

L'utilisateur ouvre l'application. L'entrée de menu s'appelle **Forge**. L'écran d'appel à
souscrire, l'écran des postes, l'écran de gouvernance, l'écran de facturation et l'écran des
réglages disent tous **la Forge**. Le centre d'aide et le `README` du runner disent **la Forge**.
Le runner, au démarrage, déclare qu'il exécute « les commandes que vous autorisez depuis **la
Forge** ». Le mot « Atelier » n'apparaît **plus nulle part** dans ce que l'utilisateur lit.

Il colle un lien `/atelier/<id>` reçu la veille : la page s'ouvre exactement comme avant.

### Inventaire des libellés à changer

**Interface — gabarits**

| Fichier | Ce qui est dit |
|---|---|
| `layout/shell/shell.component.html` | Entrée de menu `Atelier` |
| `atelier/atelier.component.html` | Titre et bouton de l'appel à souscrire |
| `atelier/files/atelier-files.component.html` | Infobulle « Retour à l'Atelier », nom de marque `Atelier` |
| `atelier/git/git-push-dialog.component.html` | Valeur d'exemple « Travaux de l'Atelier Claude Gateway » |
| `postes/postes.component.html` | Bandeau d'accès refusé, texte d'état vide, bouton « Ouvrir l'Atelier », note sous un poste |
| `governance/governance.component.html` | Bandeau d'accès refusé, texte d'état vide, bouton « Ouvrir l'Atelier » |
| `billing/billing.component.html` | Mention des cartes de plan, section « Option Atelier » entière |
| `settings/settings.component.html` | Deux phrases du bloc « Compte GitHub » |
| `settings/remove-git-token-dialog/…component.html` | « Les prochains ateliers… » |

**Interface — chaînes TypeScript**

| Fichier | Ce qui est dit |
|---|---|
| `billing/billing.component.ts` | Titre et corps du dialogue de résiliation, message de succès, cinq messages d'erreur |
| `atelier/files/atelier-files.component.ts` | Message de commit pré-rempli |

**Messages du backend affichés à l'utilisateur** (corps `ErrorResponse.message`)

`AtelierAccessDeniedException`, `AtelierOptionIncludedInPlanException`,
`AtelierOptionAlreadyActiveException`, `AtelierOptionNotActiveException`, et le refus
« Souscrivez une offre Solo ou Pro… » d'`AtelierOptionService`.

**Documentation produit**

`backend/src/main/resources/help/01`, `02`, `05`, `06`, `08`, `09` ; `runner/README.md`.

**Console du runner**

`StartupDisclosure.lines()` ; `ResumeMessages.noMemoryHint()` et `ResumeMessages.cannotResume()`
(« Atelier > Connecter une machine »).

### Guide d'accueil (F-53) — vérification

Les trois étapes (`ATELIER_GUIDE_STEPS`) et le gabarit `atelier-guide.component.html` ont été
relus : **aucun texte visible n'y prononce le mot**. Le guide parle de « projet », « poste »,
« commande ». Rien à changer ; la vérification est un critère d'acceptation à part entière, pour que
la relecture soit tracée et non supposée.

### Cas d'erreur

Ce changement n'introduit aucun chemin d'erreur nouveau. Les comportements de refus existants sont
inchangés — seul le **texte** du message change, jamais le code d'erreur ni le statut HTTP.

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Accès à la Forge sans droit | Code `atelier_forbidden` **inchangé**, message reformulé « La Forge demande… » | 403 |
| Souscription d'option alors qu'elle est incluse | Code `atelier_option_included` **inchangé**, message « La Forge est déjà incluse… » | 409 |
| Option déjà active | Code `atelier_option_already_active` **inchangé** | 409 |
| Résiliation sans option | Code `atelier_option_not_active` **inchangé** | 409 |
| Lien `/atelier/:id` ouvert après le changement | La page s'ouvre normalement | 200 |

---

## Critères d'acceptation

- [ ] Aucun gabarit `*.html` de `frontend/src` ne contient le mot « Atelier » **hors commentaire de
      code**, hors nom de classe CSS, hors sélecteur de composant et hors `routerLink="/atelier"`.
- [ ] Aucune chaîne littérale affichée à l'utilisateur (TypeScript ou Java) ne contient
      « Atelier » / « atelier » en tant que **nom de produit**.
- [ ] L'entrée de menu s'appelle « Forge ».
- [ ] Le centre d'aide embarqué et `runner/README.md` ne parlent plus que de « la Forge ».
- [ ] Le bloc de démarrage du runner dit « depuis la Forge ».
- [ ] Les textes du guide d'accueil (F-53) ont été relus : aucun n'emploie le mot (constat, pas
      modification).
- [ ] La route `/atelier`, `/atelier/:id` et `/atelier/:id/fichiers` répondent comme avant — test de
      routage existant vert, sans modification.
- [ ] Aucune classe, aucun paquet, aucun fichier source, aucune table, aucune colonne, aucun
      endpoint, aucun code d'erreur et aucune clé de configuration n'a été renommé.
- [ ] Les accords féminins sont corrects dans chaque phrase touchée (« incluse », « la Forge »).
- [ ] `npm run build` et `npm test` verts ; `mvn -pl backend test` vert ; tests du runner verts.
- [ ] Aucune règle du `DESIGN_SYSTEM.md` enfreinte, et aucune modification de la passe de cohérence
      F-56 (PR #342) annulée — le diff ne touche que du texte, jamais une couleur, une police, un
      espacement ni une structure de gabarit.

---

## Périmètre

### Hors scope (explicite)

- Renommer classes, paquets, sélecteurs Angular, noms de fichiers, tables, colonnes, migrations.
- Renommer endpoints, codes d'erreur, métadonnées fournisseur de paiement, clés de configuration,
  variables d'environnement.
- Rediriger, dupliquer ou déprécier la route `/atelier`.
- Réécrire les documents d'ingénierie de `docs/**` (mini-specs, ADR, cadrages) : archives datées.
- Réécrire les commentaires de code qui citent l'historique des features.
- Toute retouche visuelle : couleurs, espacements, iconographie, structure.

---

## Plan de test

### Frontend (unitaires / composants)

| Test | Vérifie |
|---|---|
| `shell.component.spec.ts` | L'entrée de menu porte « Forge » et pointe toujours sur `/atelier`. |
| `postes.component.spec.ts` | Le bandeau d'accès refusé dit « fait partie de la Forge » (assertion existante à mettre à jour). |
| `governance.component.spec.ts` | Idem sur l'écran de gouvernance. |
| `billing.component.spec.ts` | La mention de carte dit « Forge (Claude Code Lite) incluse » ; la section « Option Forge » est titrée ainsi ; les messages d'erreur mappés disent « l'option Forge » (assertions existantes à mettre à jour). |
| `atelier.component.spec.ts` | L'appel à souscrire dit « La Forge demande l'option Forge, ou l'offre Gold ». |
| `app.routes.spec.ts` | **Inchangé** : les routes `/atelier*` résolvent toujours — preuve de non-régression du lien partagé. |
| Nouveau — `forge-vocabulaire.spec.ts` | Garde-fou : parcourt les libellés exportés du guide (F-53) et vérifie qu'aucun ne contient « Atelier ». |

### Backend (unitaires / intégration)

| Test | Vérifie |
|---|---|
| `AtelierAccessServiceTest` / `AtelierOptionAccessApiIntegrationTest` | Les codes d'erreur `atelier_forbidden` etc. sont **inchangés** (tests existants, doivent rester verts sans retouche). |
| Nouveau — `HelpDocsVocabulaireTest` | Aucun fichier de `resources/help/*.md` ne contient « Atelier ». |
| `AtelierOptionServiceTest` | Comportement de souscription/résiliation inchangé. |

### Runner

| Test | Vérifie |
|---|---|
| `StartupDisclosureTest` | La première ligne dit « depuis la Forge » et ne dit plus « Atelier ». |
| `ResumeMessagesTest` (si présent) | Les renvois disent « Forge > Connecter une machine ». |

### Isolation utilisateur

Sans objet : aucun accès aux données n'est ajouté ni modifié. Les filtres `user_id` existants ne sont
pas touchés.

---

## Impacts

### Tables

Aucune.

### Endpoints

Aucun. `/api/billing/atelier-option*` conservés à l'identique.

### Composants

Aucun composant créé, supprimé ni renommé. Seuls des textes changent.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Analyse |
|---|---|---|
| Auth / Principal | Non | Aucun changement d'authentification ni de Principal. |
| Contexte tenant | Non | Aucun changement de résolution du tenant ni d'`user_id`. |
| Plans / limites | **Oui, en libellé seulement** | La section « Option Atelier » devient « Option Forge ». **Aucun** gate, quota, code d'erreur ni appel de service de limite n'est modifié. Composants concernés : `billing.component.{html,ts}`, `AtelierOptionService` (messages), `GlobalExceptionHandler` (messages), `Atelier*Exception` (messages). Les tests de gating existants restent verts **sans retouche**, ce qui est la preuve que seul le texte a bougé. |
| Navigation / routing | Non | `app.routes.ts` **non modifié**. Tous les `routerLink="/atelier"` conservés. |
