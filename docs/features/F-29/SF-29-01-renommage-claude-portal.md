# Mini-spec — [F-29 / SF-01] Renommage de l'identité produit « Claude Proxy » → « Claude Portal »

---

## Identifiant

`F-29 / SF-01`

## Feature parente

`F-29` — Identité publique & conformité web

## Statut

`in-review`

## Date de création

2026-08-21

## Branche Git

`feat/SF-29-01-renommage-claude-portal`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Éliminer de toute la surface publique du produit le vocabulaire qui fait classer `portal.ng-itconsulting.com` en catégorie « anonymizer / proxy avoidance » par les filtres d'entreprise — c'est-à-dire le nom de marque « Claude **Proxy** » (titre de page, favicon, logo, en-têtes, textes) et l'accroche « Unrestricted AI. No limits. » — au profit de l'identité **« Claude Portal »** et d'un positionnement explicitement professionnel.

---

## Contexte (diagnostic du 2026-08-21)

Le domaine est bloqué par des proxys d'entreprise. Le crawl du site montre que les moteurs de classification (Zscaler, Netskope, FortiGuard, Symantec/BlueCoat, Cisco Umbrella) ne disposent que de deux signaux exploitables :

1. `<title>Claude Proxy</title>` et `<link rel="icon" href="claude-proxy-logo.png">` ;
2. un corps de page vide (`<app-root></app-root>`, SPA Angular).

Le mot « proxy » dans le titre d'un domaine relayant du trafic vers une API tierce déclenche la catégorie *Proxy Avoidance / Anonymizer* de façon quasi mécanique. L'accroche `Unrestricted AI. No limits.` (visible d'un analyste humain lors d'une demande de reclassification, et des crawlers exécutant JS) aggrave le signal : c'est le registre lexical exact des services de contournement.

Cette subfeature traite le **signal lexical**. Les signaux 2 (contenu indexable, `robots.txt`) et 3 (pages légales) sont traités par SF-29-02 et SF-29-03.

---

## Comportement attendu

### Cas nominal

1. L'onglet du navigateur affiche **« Claude Portal — passerelle professionnelle vers Claude »** au lieu de « Claude Proxy ».
2. La landing (`/`), la coquille authentifiée (toolbar) et la page Facturation affichent la marque **« Claude Portal »**.
3. L'accroche de la landing porte un positionnement professionnel explicite ; les termes `Unrestricted`, `No limits`, `Proxy` n'apparaissent plus dans aucun texte rendu à l'utilisateur.
4. Le fichier de logo est renommé `claude-portal-logo.png` et toutes ses références (favicon `index.html`, nav, hero, footer de la landing) pointent vers le nouveau nom — aucune image cassée.
5. Le comportement fonctionnel de l'application est **strictement inchangé** : aucune route, aucun appel API, aucun état modifié.

### Cas d'erreur

Aucun endpoint, aucune saisie utilisateur, aucun accès aux données n'est introduit ou modifié : la table « situation → code HTTP » est sans objet. Les modes de défaillance sont des régressions de build ou d'affichage, couverts par les critères d'acceptation :

| Situation | Comportement attendu |
|-----------|---------------------|
| Renommage du fichier logo incomplet (référence orpheline) | Détecté au build/tests : `grep` sur `claude-proxy-logo` doit retourner 0 occurrence dans `frontend/src` et `frontend/public` |
| Occurrence textuelle de « Proxy » oubliée dans un template | Détecté par le critère d'acceptation « grep insensible à la casse = 0 résultat » |
| Cache navigateur servant l'ancien favicon | Accepté et sans gravité : nom de fichier différent → nouvelle URL, pas de collision de cache |

---

## Critères d'acceptation

- [ ] `grep -ri "claude proxy\|claude-proxy" frontend/src frontend/public` retourne **0 résultat**
- [ ] `grep -ri "unrestricted\|no limits" frontend/src --include=*.html --include=*.scss` retourne **0 résultat** (les fichiers `*.spec.ts` sont exclus : ils citent ces termes dans l'assertion qui les interdit)
- [ ] `frontend/src/index.html` : `<title>` = `Claude Portal — passerelle professionnelle vers Claude`
- [ ] `frontend/public/claude-portal-logo.png` existe ; `claude-proxy-logo.png` n'existe plus
- [ ] Le favicon et les 3 emplacements de logo de la landing (nav, hero, footer) référencent `claude-portal-logo.png`
- [ ] La toolbar de la coquille authentifiée affiche « Claude Portal »
- [ ] La landing affiche une accroche professionnelle, sans registre de contournement
- [ ] `docs/DESIGN_SYSTEM.md` (§Logo, §Identité) reflète le nouveau nom de marque et le nouveau chemin de fichier
- [ ] Aucune modification de `app.routes.ts`, d'un service, d'un guard, ou d'un appel HTTP
- [ ] `npm run build` OK et l'intégralité des specs frontend vertes
- [ ] Une spec vérifie explicitement l'absence du terme « Proxy » dans le rendu de la landing et de la coquille (garde-fou anti-régression)

---

## Périmètre

### Hors scope (explicite)

- `meta description`, Open Graph, `<h1>`/`<noscript>` statiques, `robots.txt`, `sitemap.xml` → **SF-29-02**
- Pages légales publiques (mentions légales, CGU, confidentialité, contact) → **SF-29-03**
- Soumissions de reclassification auprès des éditeurs de filtrage → **volet B**, tâche d'exploitation hors code, à exécuter **après** déploiement de SF-29-01 et SF-29-02
- Renommage du dépôt Git, de l'artefact Maven `claude-gateway`, du namespace Kubernetes `claude-gateway-staging`, du nom de domaine : **non impactants** pour la classification (non exposés publiquement) et coûteux/risqués
- Refonte graphique du logo lui-même (le visuel est conservé à l'identique, seul le nom de fichier change)
- Réécriture des entrées d'historique de `docs/PRODUCT_SPEC.md` et des mini-specs F-12/F-27 mentionnant « Claude Proxy » : ce sont des **traces horodatées**, elles restent exactes au moment où elles ont été écrites

---

## Valeurs initiales

Sans objet — aucune entité créée, aucun état de ressource modifié.

---

## Contraintes de validation

Sans objet — aucun champ soumis à saisie utilisateur n'est introduit ou modifié.

Contrainte rédactionnelle appliquée aux textes produits :

| Élément | Contrainte |
|---------|-----------|
| `<title>` | ≤ 60 caractères (bonne pratique d'indexation), contient le nom de marque et la nature du service |
| Accroche landing | Registre professionnel ; interdiction des termes `unrestricted`, `no limits`, `bypass`, `unblock`, `anonymous`, `proxy` |
| Nom de marque | « Claude Portal », graphie unique sur toute l'application |

---

## Technique

### Endpoint(s)

Aucun. Subfeature **100 % frontend, purement présentationnelle**.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable**

### Fichiers impactés

| Fichier | Nature de la modification |
|---------|--------------------------|
| `frontend/src/index.html` | `<title>`, `href` du favicon |
| `frontend/public/claude-proxy-logo.png` | Renommé en `claude-portal-logo.png` (`git mv`) |
| `frontend/src/app/landing/landing.component.html` | Marque (nav `aria-label`, `alt`), 3 `src` de logo, accroche `landing__eyebrow`, sous-titre, mention de pied de page |
| `frontend/src/app/layout/shell/shell.component.html` | Libellé de marque de la toolbar |
| `frontend/src/app/billing/billing.component.html` | Sous-titre « Gérez votre offre … » |
| `frontend/src/styles.scss` | Commentaires (l. 1, 23) nommant le thème |
| `frontend/src/app/landing/landing.component.scss` | Commentaire (l. 1) nommant la palette |
| `docs/DESIGN_SYSTEM.md` | §Logo (l. 19) et §Identité (l. 190) — nom de marque et chemin du fichier |

### Composants Angular

Aucun composant créé ou supprimé. Templates modifiés : `LandingComponent`, `ShellComponent`, `BillingComponent`.

---

## Plan de test

### Tests unitaires / de composant

- [ ] `landing.component.spec` — le rendu ne contient **aucune** occurrence de « Proxy » (insensible à la casse)
- [ ] `landing.component.spec` — le rendu ne contient ni « Unrestricted » ni « No limits »
- [ ] `landing.component.spec` — les `src` des images de logo pointent vers `claude-portal-logo.png`
- [ ] `shell.component.spec` — la toolbar affiche « Claude Portal »
- [ ] Non-régression : l'intégralité des specs frontend existantes reste verte (aucun changement de comportement attendu)

### Tests d'intégration

Sans objet — aucun endpoint n'est introduit ni modifié. La vérification d'intégration est le **build de production Angular** (`npm run build`), qui échoue si un asset référencé est absent.

### Vérification manuelle post-déploiement

- [ ] `curl -s https://portal.ng-itconsulting.com/ | grep -o "<title>[^<]*</title>"` → nouveau titre
- [ ] `curl -sI https://portal.ng-itconsulting.com/claude-portal-logo.png` → 200
- [ ] Landing, toolbar et page Facturation affichées sans occurrence de « Proxy »

### Isolation utilisateur

- [ ] Applicable
- [x] **Non applicable** — raison : la subfeature ne lit ni n'écrit aucune donnée. Aucun appel HTTP, aucun service, aucun repository n'est touché ; seuls des libellés statiques et un nom de fichier d'asset changent. Aucun chemin d'accès aux données n'est introduit ou modifié.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun composant d'authentification touché ; `authGuard`, `AuthService` et le Principal sont inchangés. `ShellComponent` est modifié sur son **seul libellé de marque**, pas sur sa logique de session ni son menu compte. |
| Contexte tenant | **Non** | Aucune résolution de `user_id` n'est introduite ou modifiée ; aucun accès aux données. |
| Plans / limites | **Non** | `BillingComponent` est modifié sur son **seul sous-titre** ; aucun appel aux services de quota/entitlement, aucun gate touché. |
| Navigation / routing | **Non** | `app.routes.ts` n'est pas modifié. Les `routerLink` existants de la landing et de la coquille (`/`, `/login`, `/register`, `/chat`) sont conservés à l'identique — seuls les libellés et `aria-label` changent. |

Composants vérifiés au titre du renommage (surface complète, issue du `grep`) : `LandingComponent`, `ShellComponent`, `BillingComponent`, `index.html`, `styles.scss`, asset `public/`. Le backend ne contient **aucune** occurrence de « Claude Proxy » (`grep` sur `backend/src` : 0 résultat) — aucun e-mail transactionnel, en-tête ou libellé serveur n'est concerné.

---

## Dépendances

### Subfeatures bloquantes

Aucune. SF-29-02 et SF-29-03 dépendent en revanche du nom retenu ici (elles réutiliseront « Claude Portal » dans les métadonnées et les pages légales).

### Questions ouvertes impactées

- [ ] Aucune question de `docs/OPEN_QUESTIONS.md` n'est concernée.

---

## Notes et décisions

- **Choix du nom « Claude Portal »** (arbitré par l'utilisateur le 2026-08-21) : changement minimal supprimant le terme déclencheur, cohérent avec le sous-domaine `portal.` déjà en production. Alternative écartée : un nom entièrement neutre sans « Claude », qui aurait aussi levé le risque de marque vis-à-vis d'Anthropic mais imposé un rebranding beaucoup plus large. **Ce risque de marque subsiste et reste à arbitrer séparément** — il est hors du périmètre du problème de filtrage traité ici.
- **L'accroche « Unrestricted AI. No limits. » est traitée dans cette subfeature** bien qu'elle ne soit pas un renommage : elle relève du même signal lexical et serait lue par l'analyste humain qui instruira les demandes de reclassification du volet B. La séparer aurait laissé le dossier de reclassification incomplet.
- **Le visuel du logo est conservé** : seul le nom de fichier change. Le renommage se fait par `git mv` pour préserver l'historique.
- **L'historique documentaire n'est pas réécrit** : les entrées datées de `PRODUCT_SPEC.md` et les mini-specs F-12/F-27 conservent la mention « Claude Proxy », exacte à leur date. Seul `DESIGN_SYSTEM.md`, document de référence vivant, est mis à jour.
