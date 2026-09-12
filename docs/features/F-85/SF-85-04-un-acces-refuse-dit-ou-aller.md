# Mini-spec — F-85 / SF-85-04 — Un accès refusé dit pourquoi, et où aller

## Identifiant

`F-85 / SF-85-04`

## Feature parente

`F-85` — Un fichier refusé dit pourquoi, et quoi faire

## Statut

`done`

## Date de création

2026-09-12

## Branche Git

`feat/SF-85-04-acces-refuse-dit-ou-aller`

---

## Objectif

Aux endroits où la Forge se refuse, l'écran dit que l'accès n'est pas ouvert, **nomme les deux
sorties** — souscrire, ou saisir un code d'accès — et **y conduit**, au lieu de laisser croire à une
panne ou à un geste à réessayer.

---

## Le défaut corrigé

Un prospect, une heure après l'échec des `.docx` et devant le PO, tente de connecter un poste puis de
télécharger le runner. **Rien ne se passe**, et cette fois **sans aucune trace côté serveur**.

Cause vérifiée en base : il n'a jamais consommé son code d'accès (`plan_code` vide, statut
`TRIALING`, zéro poste, les trois codes émis portant un `redeemed_at` nul). La Forge est gardée par
`AtelierAccessService.requireAccess()` — administrateur, ou compte habilité via
`AtelierEntitlementService` — et le refus part **avant** toute journalisation métier.

**La garde est juste. C'est son silence qui ne l'est pas.** C'est le même défaut que pour le `.docx`,
sur un autre objet : le produit sait pourquoi il refuse, et ne le dit pas.

---

## Vérification préalable — l'écran distingue-t-il un 403 d'une panne ?

La consigne de cadrage demandait de le vérifier **avant** d'écrire quoi que ce soit. Résultat :

| Constat | Verdict |
|---|---|
| `authInterceptor` ne touche qu'au `401` (purge + `/login`). Le `403` **traverse** et arrive au composant, `HttpErrorResponse` intact | ✅ l'information existe |
| Le serveur pose déjà un discriminant : `403 {"error":"atelier_forbidden","message":"…"}` (`GlobalExceptionHandler`) | ✅ reconnaissable sans ambiguïté |
| `postes` (liste), `governance` (liste), `atelier` (liste), `add-project-dialog` (exploration) **testent** le statut | ✅ distinction faite |
| **`runner-pairing-dialog.generateCode()`** — la génération du code d'appairage, le geste exact de l'incident — ne teste **que le 404**. Un `403` y devient « Le code d'appairage n'a pas pu être généré. **Veuillez réessayer.** » | ❌ **le 403 est dit comme une panne** |
| **`postes.openHostTerminal()`** et **`postes.setHostMissionStatus()`** ne testent **aucun** statut | ❌ même confusion |

**Donc : l'écran *peut* distinguer, et par endroits ne le fait pas.** Les deux défauts sont traités
ici — c'est la moitié « et l'un des deux mentirait » du cadrage. Partout ailleurs la distinction
existe déjà mais **ne débouche sur rien** : « La Forge est nécessaire pour ce geste. » nomme le mur,
pas la porte.

### Ce que la vérification a aussi établi — le runner n'est pas gardé

`GET /api/runner/download`, `/download/windows`, `/download/macos-*` et `/download/formats` sont
**`permitAll`** (`RunnerSecurityConfig`, chaîne dédiée `/runner/**`) et `RunnerDownloadController`
n'appelle jamais la garde : **un 403 n'y est pas possible**, seul un `404` (binaire non publié) l'est.

Le téléchargement n'a donc pas échoué par refus d'accès : l'utilisateur **n'a jamais atteint l'étape
de téléchargement**, parce que le parcours « Connecter un poste » se referme deux étapes plus tôt —
création du poste (`POST /api/runner-hosts`) puis code d'appairage
(`POST /api/runner-hosts/{id}/pairing-code`), tous deux gardés. C'est là que le correctif porte.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur, sans droit d'accès à la Forge, fait un geste gardé (connecter un poste, ouvrir un
   projet, ouvrir un terminal de poste, supprimer, appliquer un paquet de gouvernance…).
2. Le serveur répond `403 atelier_forbidden` — **inchangé**.
3. L'écran le reconnaît **comme un refus d'accès** et non comme une panne, et affiche **les trois
   temps**, dans cet ordre :
   1. **ce qui est refusé** : « L'accès à la Forge n'est pas ouvert sur ce compte. » ;
   2. **les deux sorties** : « souscrire, ou saisir le code d'accès que vous avez reçu » ;
   3. **où aller** : un lien/bouton vers `/billing#code-acces` — l'ancre de la section
      « Vous avez un code d'accès ? », qui existe déjà.
4. Sur un écran entier (liste des postes, gouvernance, Forge), le refus est un **panneau** ; sur un
   geste ponctuel, une **snackbar** dont l'action mène au même endroit.

### La formulation vit à un seul endroit

`frontend/src/app/shared/forge-access.ts` — construit sur le modèle de `file-format-names.ts`
(SF-85-02) : **une seule source pour le vocabulaire du refus**, réutilisée partout.

| Export | Rôle |
|---|---|
| `isForgeAccessDenied(err)` | Vrai **uniquement** pour le refus de la garde. `status: 0` (réseau), `5xx`, `409`, `404` ⇒ faux |
| `FORGE_ACCESS_REFUSAL` | Le message en trois temps, sans jargon d'abonnement |
| `FORGE_ACCESS_HEADLINE` / `FORGE_ACCESS_EXITS` | Les mêmes mots, découpés pour un panneau |
| `FORGE_ACCESS_BILLING_ROUTE` / `FORGE_ACCESS_CODE_FRAGMENT` | `/billing` et `code-acces` — la destination, écrite une fois |
| `openForgeAccessSnackBar(snackBar, router)` | La snackbar + son action qui **conduit** à la section |

**Aucun mot d'abonnement** : ni « Gold », ni « option », ni « plan », ni « offre ». Le message dit
l'accès et les deux gestes qui l'ouvrent.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Panne réseau / gateway injoignable (`status: 0`) | Message de panne existant, **inchangé** — jamais le message d'accès | — |
| `5xx` | Message d'échec existant, inchangé | 500+ |
| `409` (runner déconnecté, projets restants…) | Message existant, inchangé : ce n'est pas un refus d'accès | 409 |
| `404` (poste supprimé, binaire runner non publié) | Message existant, inchangé | 404 |
| `403` **sans** corps exploitable (réponse `blob`, gateway antérieure) | Traité comme un refus d'accès : sur ces chemins, le `403` **ne peut venir que** de la garde | 403 |
| `403` d'une **autre** origine (`admin_forbidden`, `access_code_not_for_account`) | **Non** reconnu : le discriminant `error` est lu quand il est là | 403 |
| Section « code d'accès » masquée (droit déjà ouvert) | Le lien mène à la Facturation ; la section d'état y explique que l'accès est ouvert. Aucun lien mort | — |

---

## Critères d'acceptation

- [ ] **Le test de la feature** : un `403 atelier_forbidden` et une panne réseau (`status: 0`) sur le
      **même geste** produisent **deux messages différents** — et celui du 403 nomme le code d'accès.
- [ ] Le message nomme **les deux sorties** (souscrire **et** code d'accès) et **aucun** mot
      d'abonnement (`Gold`, `option`, `plan`, `offre`, `abonnement`).
- [ ] Chaque refus **conduit** : lien ou action de snackbar vers `/billing#code-acces`.
- [ ] La section « Vous avez un code d'accès ? » porte l'ancre `code-acces`, et arriver sur
      `/billing#code-acces` **l'amène sous les yeux**.
- [ ] `generateCode()` ne dit plus « Veuillez réessayer » sur un `403` (le geste exact de l'incident).
- [ ] `openHostTerminal()` et `setHostMissionStatus()` distinguent le `403` de la panne.
- [ ] La formulation vit **à un seul endroit** : aucune des chaînes n'est recopiée dans un composant.
- [ ] Ni la garde, ni les droits, ni le `403` du serveur ne changent : **aucun fichier de
      `backend/src/main/java/fr/claudegateway/atelier/` ni `.../billing/` n'est modifié**.
- [ ] **Journalisation** : `UploadService` journalise le **type refusé** et la liste blanche, comme
      `DocumentService`.
- [ ] Aucune couleur ni police hors `docs/DESIGN_SYSTEM.md` ; **aucun registre de couleur ajouté**.
- [ ] Suites backend et frontend vertes, `ng build` vert.

---

## Périmètre

### Hors scope (explicite)

- **La garde elle-même**, les droits, le `403` et son corps : rien n'est touché côté serveur sur ce
  chemin. Enrichir `ErrorResponse` d'un champ d'orientation toucherait **toute** l'API.
- **Ouvrir l'accès autrement** (consommer un code à la volée, essai gratuit, adoucir la garde).
- **Les flux SSE**, dont le refus voyage en `{"error":"forbidden"}` hors du `GlobalExceptionHandler` :
  ils portent déjà un message (`mapAgentError`), et ils ne sont atteints **que** depuis un projet
  ouvert — donc jamais par un compte sans accès. Les toucher serait traiter un cas qui ne se produit
  pas.
- **Les trous de garde constatés** (quelques routes `git`/`agent` sans `requireAccess()`) : c'est une
  question de **droits**, explicitement hors périmètre ici. Signalés dans le compte rendu.
- **Le refus de fichier** (SF-85-01/02/03), déjà livré.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Unicité | Normalisation |
|---|---|---|---|---|---|
| `err.status` lu | — | — | entier HTTP | — | — |
| `err.error.error` lu | Non (peut manquer) | — | chaîne libre ; seul `atelier_forbidden` reconnu | — | comparaison stricte |
| ancre de section | Oui | — | `code-acces` (minuscules, sans accent) | unique dans la page | — |

---

## Technique

### Endpoint(s)

Aucun créé ni modifié.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `shared/forge-access.ts` — **créé** : le seul endroit où un refus d'accès devient des mots et une
  destination.
- `PostesComponent` — panneau `forbidden` enrichi ; `403` distingué sur le terminal de poste et le
  statut de mission ; messages de refus passés par la source unique.
- `RunnerPairingDialogComponent` — `generateCode()` et `failAttach()` : `403` nommé, et **conduit**.
- `AddProjectDialogComponent` — `403` passé par la source unique.
- `AtelierComponent` — `isAtelierForbidden()` **remplacé** par `isForgeAccessDenied()` ; panneau
  d'upsell : la deuxième sortie ajoutée.
- `GovernanceComponent` (gabarit) — panneau `forbidden` enrichi.
- `BillingComponent` — ancre `code-acces` + amenée en vue sur arrivée par fragment.

### Backend

- `UploadService` — **la seule ligne backend** : le refus de type journalise le **type** et la liste
  blanche, comme `DocumentService:77`. Le type n'est pas une donnée sensible : c'est déjà ce que le
  message d'erreur rend à l'utilisateur. Le **message** rendu au client ne change pas.

---

## Plan de test

### Tests unitaires

- [ ] `forge-access.spec` — `403 atelier_forbidden` reconnu.
- [ ] `forge-access.spec` — `403` nu (sans corps) reconnu.
- [ ] `forge-access.spec` — `status: 0` (réseau), `500`, `409`, `404` **non** reconnus.
- [ ] `forge-access.spec` — `403 admin_forbidden` **non** reconnu.
- [ ] `forge-access.spec` — le message nomme les deux sorties et ne contient aucun mot d'abonnement.

### Tests d'intégration

- [ ] `postes.component.spec` — `403` sur l'ouverture d'un terminal de poste : message d'accès ;
      panne réseau sur **le même geste** : message différent (**le test de la feature**).
- [ ] `postes.component.spec` — le panneau `forbidden` nomme le code d'accès et pointe
      `/billing#code-acces`.
- [ ] `runner-pairing-dialog.component.spec` — `403` sur la génération du code : message d'accès,
      **sans « réessayer »** ; `404` : « Ce poste n'existe plus. » inchangé.
- [ ] `runner-pairing-dialog.component.spec` — `403` sur la création du poste (mode poste) : message
      d'accès, et le nom saisi est conservé (non-régression F-72).
- [ ] `billing.component.spec` — la section « code d'accès » porte l'ancre `code-acces`.
- [ ] `atelier.component.spec` — le panneau d'upsell nomme la deuxième sortie ; le `403` est toujours
      reconnu (non-régression SF-28-06).
- [ ] `UploadServiceTest` — refus de type : le message rendu **ne change pas** (non-régression).

### Isolation utilisateur

- [x] Non applicable — aucune donnée n'est lue ni écrite. La subfeature transforme un refus en texte
      dans le navigateur ; les appels existants restent isolés par le JWT côté serveur, et la garde
      d'accès n'est ni contournée ni assouplie.

---

## Dépendances

### Subfeatures bloquantes

- `SF-85-01`, `SF-85-02`, `SF-85-03` — statut : `done` (vocabulaire et méthode réutilisés).

### Questions ouvertes impactées

Aucune.

---

## Préoccupations transversales

| Préoccupation | Composants vérifiés un par un | Verdict |
|---|---|---|
| **Auth / Principal** | Aucun appel ajouté, aucun en-tête touché. `authInterceptor` **inchangé** (il ne traite que le `401`). Aucun `Principal`, aucun rôle lu côté écran | traité |
| **Contexte tenant** | Aucune donnée lue ni écrite ; aucun appel ajouté ni modifié ; l'isolation `user_id` reste entièrement côté serveur | traité |
| **Plans / limites** | **La garde n'est pas touchée** : `AtelierAccessService`, `AtelierEntitlementService`, `AccessGrantService`, `BillingController` sont hors diff. Aucun nouvel appel à un service de limites ; l'écran ne décide **jamais** qui a le droit, il ne fait que **dire** un refus déjà rendu | traité |
| **Navigation / routing** | **Aucune route ajoutée ni modifiée, aucun guard.** `provideRouter` reste **inchangé** — l'amenée en vue par fragment est faite **par `BillingComponent` lui-même**, et non par `withInMemoryScrolling`, qui aurait changé le comportement de **toutes** les navigations de l'application. Chemins vérifiés : `/postes`, `/governance`, `/atelier`, `/billing` restent atteignables comme avant ; les liens ajoutés pointent vers `/billing`, route existante | traité |

---

## Notes et décisions

- **D1 — Une source unique pour le refus, comme pour les formats.** Le refus d'accès était écrit à
  six endroits en quatre formulations (« La Forge est nécessaire pour ce geste. », « … pour connecter
  une machine », « … pour parcourir une machine », « La vue des postes fait partie de la Forge »).
  SF-85-02 avait déjà tranché pour les formats : une seule source, sinon les copies divergent.
- **D2 — Snackbar avec action plutôt que dialogue.** Un dialogue arrêterait l'utilisateur sur un
  geste qu'il n'a pas demandé ; la snackbar dit et propose. Conforme au design system (notifications
  par `MatSnackBar`), et l'action porte la conduite.
- **D3 — L'ancre plutôt qu'une page dédiée.** La section existe déjà et est visible pour tout compte
  sans droit ouvert (`canEnterAccessCode()`). Créer une page « saisir mon code » dupliquerait le
  formulaire et la logique de consommation.
- **D4 — L'amenée en vue est locale à `BillingComponent`.** `withInMemoryScrolling({anchorScrolling})`
  est global : il aurait modifié le comportement de toutes les navigations de l'application pour un
  seul lien. Arbitrage : portée minimale.
- **D5 — Le `403` nu est traité comme un refus d'accès.** Sur les chemins concernés, la garde est la
  seule source possible de `403`. Un `403` d'une autre origine reste reconnaissable par son
  discriminant et n'est pas capté.
- **D7 — Le panneau d'upsell de la Forge GAGNE une sortie, il n'en perd aucune.** F-40 / SF-40-01
  y a posé les deux chemins d'achat — l'option d'abord, puis Gold — au point exact où la falaise ×8
  se rencontrait, et un test de la feature les tient. SF-85-04 **ajoute** le code d'accès comme
  troisième sortie plutôt que de réécrire le panneau : la règle « sans jargon d'abonnement » vaut
  pour la **phrase de refus** (source unique), pas pour une surface dont le sujet *est* l'offre.
- **D6 — Le téléchargement du runner n'est pas gardé** : rien n'y est corrigé. Le dire est plus utile
  que d'y ajouter un message qui ne s'afficherait jamais.
