# Mini-spec — [F-29 / SF-02] Signaux d'indexation pour crawlers et moteurs de classification

---

## Identifiant

`F-29 / SF-02`

## Feature parente

`F-29` — Identité publique & conformité web

## Statut

`ready`

## Date de création

2026-08-23

## Branche Git

`feat/SF-29-02-signaux-indexation`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Donner aux crawlers et aux moteurs de classification de quoi comprendre ce qu'est le site **sans exécuter de JavaScript** — description, Open Graph, contenu de repli lisible, `robots.txt` et `sitemap.xml` — afin qu'ils ne se rabattent plus sur le seul nom de domaine pour le classer.

---

## Contexte

SF-29-01 a traité le signal lexical (le mot « Proxy »). Il reste le second signal du diagnostic du 2026-08-21 : **la page ne contient aucun texte exploitable**. Un crawler reçoit aujourd'hui un `<body>` réduit à `<app-root></app-root>` — ni `meta description`, ni Open Graph, ni `robots.txt` (l'URL `/robots.txt` renvoie l'index HTML par le fallback SPA de nginx). Un classifieur sans contenu se rabat sur les heuristiques de domaine et d'usage, qui sont précisément celles qui produisent le verdict « anonymizer ».

---

## Comportement attendu

### Cas nominal

1. `curl https://portal.ng-itconsulting.com/` renvoie un HTML contenant une `meta description`, les balises Open Graph, un lien canonique et un **contenu de repli lisible** (titre, description du service, nature de l'éditeur) placé à l'intérieur de `<app-root>`.
2. Au démarrage d'Angular, ce contenu de repli est **remplacé** par l'application — l'utilisateur ne voit aucune différence par rapport à aujourd'hui.
3. Un visiteur sans JavaScript voit un message `<noscript>` expliquant le service et invitant à activer JS, au lieu d'une page blanche.
4. `curl https://portal.ng-itconsulting.com/robots.txt` renvoie un `robots.txt` en `text/plain` : indexation autorisée sur les pages publiques, refusée sur les zones authentifiées, avec la référence au sitemap.
5. `curl https://portal.ng-itconsulting.com/sitemap.xml` renvoie un sitemap XML valide listant les URL publiques.

### Cas d'erreur

Aucun endpoint ni saisie utilisateur n'est introduit. Modes de défaillance couverts par les critères d'acceptation :

| Situation | Comportement attendu |
|-----------|---------------------|
| `robots.txt` capté par le fallback SPA et servi en `text/html` | Refusé : le critère d'acceptation vérifie le `Content-Type` `text/plain` sur le fichier servi |
| Contenu de repli non remplacé au bootstrap (doublon visible à l'écran) | Refusé : placé **à l'intérieur** de `<app-root>`, qu'Angular vide au démarrage ; vérifié par une spec de rendu de l'application |
| Sitemap listant une URL authentifiée | Refusé : seules les URL publiques sont listées, cohérence vérifiée avec `robots.txt` |
| `robots.txt` bloquant tout le site | Refusé : le critère impose `Allow` explicite sur `/` |

---

## Critères d'acceptation

- [ ] `index.html` contient une `meta name="description"` décrivant le service (≤ 160 caractères), sans terme de contournement
- [ ] `index.html` contient les balises Open Graph `og:title`, `og:description`, `og:type`, `og:url`, `og:image` (le logo) et `og:locale`
- [ ] `index.html` contient `<link rel="canonical" href="https://portal.ng-itconsulting.com/">`
- [ ] `index.html` contient, **à l'intérieur de `<app-root>`**, un contenu de repli avec un `<h1>` et une description du service, lisible sans JavaScript
- [ ] `index.html` contient un bloc `<noscript>` explicatif
- [ ] `frontend/public/robots.txt` existe : `Allow: /` sur les pages publiques, `Disallow` sur les **11** routes authentifiées (`/chat`, `/atelier`, `/documents`, `/ask`, `/templates`, `/billing`, `/reports`, `/settings`, `/profile`, `/admin`, `/onboarding`) + le préfixe `/auth/`, et une directive `Sitemap:`
- [ ] `frontend/public/sitemap.xml` existe, XML valide, ne listant **que** des URL publiques
- [ ] Après build, `robots.txt` et `sitemap.xml` sont présents dans `dist/frontend/browser/`
- [ ] Servis par nginx avec le bon `Content-Type` (`text/plain`, `application/xml`) et non captés par le fallback SPA
- [ ] Aucune occurrence des termes `proxy`, `unrestricted`, `no limits`, `bypass`, `unblock`, `anonymous` dans les métadonnées ajoutées
- [ ] L'application démarre normalement : le contenu de repli disparaît, aucune régression visuelle
- [ ] Build production OK et intégralité des specs frontend vertes

---

## Périmètre

### Hors scope (explicite)

- Rendu côté serveur (SSR / Angular Universal) : réponse structurelle au problème, mais changement d'architecture de déploiement hors de proportion avec l'objectif — le contenu de repli statique suffit à un classifieur
- Pages légales publiques → **SF-29-03** (leurs URL seront ajoutées au sitemap à ce moment-là)
- Soumissions de reclassification auprès des éditeurs de filtrage → **volet B**, hors code, après déploiement
- Optimisation SEO de fond (contenu éditorial, mots-clés, performance, données structurées `schema.org`) : l'objectif est la **classification**, pas le référencement
- Site vitrine sur le domaine racine `ng-itconsulting.com` (qui ne répond pas aujourd'hui) : signal favorable mais projet distinct

---

## Valeurs initiales

Sans objet — aucune entité créée.

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| `meta description` | ≤ 160 caractères ; décrit le service et son public ; lexique de contournement interdit |
| `og:image` | Chemin absolu vers `claude-portal-logo.png` |
| `robots.txt` | `Allow: /` explicite ; toute route authentifiée de `app.routes.ts` en `Disallow` |
| `sitemap.xml` | Uniquement des URL publiques ; `<lastmod>` au format `AAAA-MM-JJ` |
| Contenu de repli | Placé **dans** `<app-root>` (jamais après), pour être remplacé au bootstrap |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable**

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `frontend/src/index.html` | `meta description`, Open Graph, canonique, contenu de repli dans `<app-root>`, `<noscript>` |
| `frontend/public/robots.txt` | **Créé** |
| `frontend/public/sitemap.xml` | **Créé** |
| `frontend/nginx.conf` | `location` explicites pour `robots.txt` et `sitemap.xml` (type MIME et cache court), placés **avant** le fallback SPA |

Note : `angular.json` copie déjà `public/` vers la racine du build — aucun changement de configuration d'assets nécessaire.

### Composants Angular

Aucun composant créé ou modifié.

---

## Plan de test

### Test automatisé — vérificateur des signaux publics

**Décision prise en cours de dev** (écart assumé à la première rédaction de cette mini-spec) : plutôt que de s'en remettre à des commandes manuelles, un vérificateur exécutable est ajouté — `frontend/scripts/verify-public-metadata.mjs`, lancé par `npm run verify:public` (sources) et `npm run verify:public:dist` (build). Il sort en code non nul dès qu'un signal disparaît et contrôle :

- [ ] `<title>`, `meta description` (présence **et** longueur ≤ 160), les 6 balises Open Graph, le lien canonique
- [ ] la présence d'un `<h1>` et d'au moins 300 caractères de texte **à l'intérieur de `<app-root>`** — c'est le placement, cause réelle du défaut, qui est vérifié
- [ ] la présence du bloc `<noscript>`
- [ ] l'absence du lexique de contournement (hors commentaires)
- [ ] `robots.txt` : `User-agent`, `Allow: /`, directive `Sitemap`, et **les 11 routes privées + `/auth/`** — l'ajout futur d'une route privée sans mise à jour de `robots.txt` fait échouer la vérification
- [ ] `sitemap.xml` : déclaration XML, espace de noms, **aucune URL non publique**, présence des 3 URL publiques, format des `lastmod`

Le vérificateur est lui-même éprouvé par un test négatif (retrait d'un `Disallow` → échec attendu, exit 1).

**Test Karma écarté, et pourquoi** : la première rédaction prévoyait une spec `app.component.spec` vérifiant que le contenu de repli est bien remplacé au démarrage. Karma instancie `AppComponent` dans un fixture, **hors du vrai `index.html`** : une telle spec passerait quel que soit le placement du contenu de repli et ne prouverait rien. Elle est remplacée par le contrôle structurel ci-dessus et par la vérification en navigateur post-déploiement.

- [ ] Non-régression : l'intégralité des specs Karma existantes reste verte

### Vérification sur le build

- [ ] `grep -c 'og:title\|description\|canonical' dist/frontend/browser/index.html` > 0
- [ ] `test -f dist/frontend/browser/robots.txt && test -f dist/frontend/browser/sitemap.xml`
- [ ] `python3 -c "import xml.dom.minidom; xml.dom.minidom.parse('dist/frontend/browser/sitemap.xml')"` → XML valide

### Vérification post-déploiement

- [ ] `curl -sI https://portal.ng-itconsulting.com/robots.txt` → `200` + `Content-Type: text/plain`
- [ ] `curl -sI https://portal.ng-itconsulting.com/sitemap.xml` → `200` + `Content-Type: application/xml`
- [ ] `curl -s https://portal.ng-itconsulting.com/ | grep -o '<meta name="description"[^>]*>'` → présent
- [ ] Landing affichée normalement dans un navigateur, sans doublon de contenu

### Isolation utilisateur

- [ ] Applicable
- [x] **Non applicable** — raison : aucune donnée lue ni écrite, aucun service ni repository touché. `robots.txt` interdit l'indexation des zones authentifiées mais **n'est pas un mécanisme de sécurité** : la protection reste `authGuard` côté frontend et l'autorisation côté backend, tous deux inchangés.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun composant d'authentification touché. |
| Contexte tenant | **Non** | Aucun accès aux données. |
| Plans / limites | **Non** | Aucun gate ni service de quota touché. |
| Navigation / routing | **Non** (avec vérification) | `app.routes.ts` n'est **pas** modifié. Mais `robots.txt` et les `location` nginx énumèrent des chemins de routes : la liste des `Disallow` doit être **dérivée de `app.routes.ts`** et vérifiée exhaustive. Les nouvelles règles nginx sont placées avant le fallback SPA et ne matchent que deux noms de fichiers exacts — aucune route applicative ne peut être interceptée. À vérifier en review : `/`, `/login`, `/register` et toutes les routes authentifiées répondent comme avant. |

Routes authentifiées à couvrir en `Disallow` (**relevé exhaustif** depuis `app.routes.ts` au 2026-08-23) : `/chat`, `/atelier` (dont `/atelier/:id/fichiers`), `/documents`, `/ask`, `/templates`, `/billing`, `/reports`, `/settings`, `/profile`, `/admin`, `/onboarding`, plus le préfixe `/auth/` (verify, forgot, reset, callback). **11 routes + 1 préfixe.** `/templates` et `/profile` avaient été omises de la première rédaction de cette mini-spec : le relevé exhaustif imposé par la préoccupation transversale « Navigation / routing » les a rattrapées avant le dev. URL publiques : `/`, `/login`, `/register`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-29-01` — **done** (le nom « Claude Portal » est repris dans les métadonnées)

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

- **Contenu de repli plutôt que SSR** : Angular Universal résoudrait le problème à la racine, mais impose un serveur Node en production et une refonte du déploiement. Un classifieur a besoin de quelques centaines de mots cohérents, pas d'un rendu complet. Décision réversible : le contenu de repli ne gêne pas une adoption ultérieure du SSR.
- **`robots.txt` n'est pas une mesure de sécurité** : il exprime une intention d'indexation. Les zones authentifiées restent protégées par `authGuard` et par l'autorisation backend, inchangés.
- **Les URL des pages légales seront ajoutées au sitemap en SF-29-03**, pas anticipées ici — un sitemap listant des URL en 404 serait un signal négatif.
