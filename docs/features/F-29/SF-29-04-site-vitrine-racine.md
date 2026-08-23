# Mini-spec — [F-29 / SF-04] Site vitrine du domaine racine servi par le cluster

---

## Identifiant

`F-29 / SF-04`

## Feature parente

`F-29` — Identité publique & conformité web

## Statut

`ready`

## Date de création

2026-08-23

## Branche Git

`feat/SF-29-04-site-vitrine-racine`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Servir une page vitrine statique sur `www.ng-itconsulting.com` depuis le cluster existant, afin que le domaine de l'éditeur présente un site réel en HTTPS au lieu d'une page de parking non indexable — dernier signal défavorable pesant sur la réputation du domaine.

---

## Contexte

Le domaine racine sert aujourd'hui la page **« Site en construction » d'OVHcloud** (`server: openresty`, en-têtes `x-iplb-*`, enregistrement `TXT "1|www.ng-itconsulting.com"` : service de redirection/parking, pas un hébergement), avec `<meta name="robots" content="none,noindex,nofollow">` et **aucun certificat TLS** — `https://ng-itconsulting.com` échoue en `connection reset`.

Pour un moteur de classification, un domaine dont la façade est une page de parking non indexable, sans HTTPS, et dont le seul contenu vivant est un sous-domaine applicatif, correspond au profil « domaine acheté pour héberger un service » — le motif des services de contournement, précisément ce que F-29 corrige.

**Arbitrage retenu** : servir la page depuis le cluster existant plutôt que souscrire un hébergement. L'infrastructure, l'ingress nginx et `cert-manager` sont déjà en place ; le coût marginal est un pod `nginx:alpine`. La zone DNS n'est pas migrée, seul un enregistrement `CNAME www` est ajouté — les `MX` Google Workspace et les `TXT` (SPF, vérification Search Console, Brevo) restent intacts.

---

## Comportement attendu

### Cas nominal

1. `https://www.ng-itconsulting.com/` renvoie **200** et sert la page vitrine (activité de l'éditeur, présentation de Claude Portal, contact, mentions légales), avec un certificat valide émis par `cert-manager`.
2. `https://www.ng-itconsulting.com/robots.txt` et `/sitemap.xml` répondent **200** avec les bons types MIME.
3. `https://portal.ng-itconsulting.com/` **reste inchangé** : l'application continue d'être servie normalement, le certificat existant n'est pas affecté.
4. L'apex `ng-itconsulting.com` conserve la redirection OVH vers `www` (hors périmètre technique de cette subfeature).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `CNAME www` absent au moment du déploiement | `cert-manager` ne peut pas valider le challenge HTTP-01 : le certificat reste en attente et `www` répond en erreur TLS. **Le CNAME doit être créé avant le déploiement** — dépendance externe explicitée. |
| Le nouvel ingress capte du trafic de `portal` | Refusé : la règle est nominative par `host`, elle ne peut pas s'appliquer à un autre nom. Vérifié après déploiement. |
| Pod vitrine indisponible | Seule la vitrine est affectée ; l'application reste servie par ses propres pods (déploiements et services distincts). |
| Contenu de la page à corriger | Modification de la ConfigMap et redéploiement — aucune image à reconstruire. |

---

## Critères d'acceptation

- [ ] `https://www.ng-itconsulting.com/` répond **200** avec un certificat valide
- [ ] La page affiche la raison sociale, l'activité, Claude Portal, le contact et les mentions légales
- [ ] `/robots.txt` (`text/plain`) et `/sitemap.xml` (`application/xml`) répondent 200
- [ ] La page ne contient **ni `noindex` ni `nofollow`**
- [ ] `https://portal.ng-itconsulting.com/` répond toujours 200 et l'application fonctionne — **non-régression vérifiée explicitement**
- [ ] Le certificat de `portal` n'est pas modifié (secret TLS distinct pour la vitrine)
- [ ] Le contenu est porté par une **ConfigMap** : corriger un texte ne nécessite aucune reconstruction d'image
- [ ] Les ressources du pod sont bornées (`requests`/`limits`)
- [ ] Aucune donnée d'éditeur inventée : valeurs issues du Kbis du 23/12/2025 et des informations fournies
- [ ] `kubectl apply -k k8s/overlays/staging/` reste idempotent et n'entraîne aucun rollout de l'application

---

## Périmètre

### Hors scope (explicite)

- **Résolution HTTPS de l'apex nu** `ng-itconsulting.com` : un apex ne peut pas porter de CNAME et les IP de l'ELB sont dynamiques (TTL 59 s). L'apex conserve la redirection OVH existante vers `www`. Une résolution complète exigerait soit un hébergement, soit une migration de la zone DNS vers un fournisseur gérant l'aplatissement de CNAME — arbitré comme non prioritaire.
- **Création de l'enregistrement `CNAME www`** : action dans l'espace client OVH, hors du dépôt, à la main de l'éditeur.
- Site multi-pages, formulaire de contact, blog, mesure d'audience.
- Suppression de la page de parking OVH côté apex.

---

## Valeurs initiales

Sans objet.

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Données d'éditeur affichées | Identiques à `frontend/src/app/legal/legal-info.ts` (même source de vérité factuelle) |
| Ressources du pod | `requests` et `limits` explicites (CPU et mémoire) — un pod non borné peut évincer des pods applicatifs |
| Secret TLS | Nom **distinct** de `claude-gateway-tls` pour ne pas interférer avec le certificat de l'application |
| Réplicas | 1 suffit (contenu statique, trafic marginal) |

---

## Technique

### Endpoint(s)

Aucun endpoint applicatif.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable**

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `k8s/base/corporate/configmap.yaml` | **Créé** — `index.html`, `robots.txt`, `sitemap.xml` |
| `k8s/base/corporate/deployment.yaml` | **Créé** — `nginx:alpine`, 1 réplica, ConfigMap montée, ressources bornées |
| `k8s/base/corporate/service.yaml` | **Créé** — ClusterIP port 80 |
| `k8s/base/ingress/corporate-ingress.yaml` | **Créé** — ingress dédié `www.ng-itconsulting.com`, secret TLS propre |
| `k8s/base/kustomization.yaml` | Nouvelles ressources référencées |

**Ingress séparé plutôt qu'un host ajouté à l'ingress applicatif** : une erreur sur la vitrine ne peut alors pas invalider la règle qui sert l'application, et les deux certificats restent indépendants.

### Composants Angular

Aucun — la vitrine est servie hors application Angular.

---

## Plan de test

### Tests automatisés

Aucun test unitaire ou de composant n'est applicable : la subfeature ne contient ni code applicatif ni logique. Ce sont des manifestes Kubernetes et du HTML statique.

### Vérification avant déploiement

- [ ] `kubectl kustomize k8s/overlays/staging/ > /dev/null` — le rendu kustomize aboutit sans erreur
- [ ] Le rendu contient bien les 4 nouvelles ressources et **conserve** l'ingress applicatif inchangé
- [ ] Le HTML de la ConfigMap est identique au fichier validé

### Vérification post-déploiement

- [ ] `curl -sI https://www.ng-itconsulting.com/` → 200, certificat valide
- [ ] `curl -sI https://www.ng-itconsulting.com/robots.txt` → 200 `text/plain`
- [ ] `curl -sI https://www.ng-itconsulting.com/sitemap.xml` → 200 `application/xml`
- [ ] **Non-régression** : `curl -sI https://portal.ng-itconsulting.com/` → 200 et `/api/actuator/health` → OK
- [ ] `kubectl get certificate -n claude-gateway-staging` → les deux certificats `Ready`

### Isolation utilisateur

- [x] **Non applicable** — contenu statique public, aucun accès aux données, aucun backend.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | La vitrine n'a aucun lien avec l'authentification et ne partage aucun composant avec l'application. |
| Contexte tenant | **Non** | Aucun accès aux données. |
| Plans / limites | **Non** | Aucun service de quota touché. |
| **Navigation / routing** | **OUI — au niveau infrastructure** | Le routage HTTP du cluster est modifié par l'ajout d'une règle d'ingress. **Composants impactés à vérifier** : l'ingress `claude-gateway-ingress` (host `portal.ng-itconsulting.com`, ses 4 chemins `/api`, `/oauth2`, `/login/oauth2`, `/`) doit rester intact et prioritaire sur son propre host ; le secret `claude-gateway-tls` ne doit pas être réutilisé ni réémis ; l'annotation `ssl-redirect` de l'application ne doit pas être altérée. La nouvelle règle est **nominative par host**, donc elle ne peut pas capter le trafic de `portal` — mais la vérification est faite explicitement après déploiement, sur l'application comme sur sa santé. |

---

## Dépendances

### Subfeatures bloquantes

- `SF-29-03` — done (les mentions légales de la vitrine reprennent les mêmes valeurs)

### Dépendance externe bloquante

- **Enregistrement `CNAME www.ng-itconsulting.com` → ELB**, à créer dans l'espace client OVH **avant** le déploiement. Sans lui, `cert-manager` ne peut pas émettre le certificat.

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

- **Servir depuis le cluster plutôt que souscrire un hébergement** : l'infrastructure, l'ingress et `cert-manager` existent déjà ; le coût marginal est un pod `nginx:alpine`. Contrepartie assumée : la vitrine partage la disponibilité du cluster avec l'application. Pour une page statique de présentation, le compromis est favorable.
- **Contenu en ConfigMap plutôt que dans une image** : corriger une virgule ne doit pas imposer un cycle de build et de publication d'image.
- **Ingress séparé** : isole les risques et les certificats.
- **L'apex nu reste imparfait** et c'est documenté : redirection OVH en HTTP vers `www`. Le bénéfice de réputation vient de `www`, qui servira un site réel en HTTPS indexable.
