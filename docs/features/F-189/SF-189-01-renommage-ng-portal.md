# Mini-spec — [F-189 / SF-01] Renommage « Claude Portal » → « NG Portal »

---

## Identifiant

`F-189 / SF-01`

## Feature parente

`F-189` — Marque NG Portal

## Statut

`done`

## Date de création

2026-10-10

## Branche Git

`feat/SF-189-01-renommage-ng-portal`

---

## Objectif

Retirer « Claude » du **nom** du produit sur toute la surface visible (application, PWA, aide, vitrine corporate) au profit de **« NG Portal »**, décision PO du 2026-10-10.

**Pourquoi** : « Claude » est une marque d'Anthropic. Un nom de produit commercial qui la reprend expose à un risque de marque et laisse croire à un produit Anthropic. « NG Portal » rattache le produit à NG IT Consulting et reste aligné sur l'adresse `portal.ng-itconsulting.com` (aucun changement d'URL).

---

## Comportement attendu

### Cas nominal

1. Onglet, `og:*`, `twitter:*`, `meta description`, repli `<noscript>` : la marque affichée est **NG Portal**.
2. Landing, coquille authentifiée (toolbar), Facturation, informations légales (`SERVICE_NAME`) : **NG Portal**.
3. PWA (`manifest.webmanifest`) : `name` et `short_name` = **NG Portal**.
4. Logo : l'emblème (sans texte, SF-29-06) est **conservé** ; le fichier est renommé `ng-portal-logo.png` et toutes ses références suivent.
5. Aide intégrée (documents `help/` + consigne de l'assistant d'aide) : le produit s'appelle **NG Portal**.
6. Vitrine corporate (`k8s/base/corporate/configmap.yaml`) : la solution s'appelle **NG Portal**.
7. Les mentions **descriptives** de Claude comme modèle relayé (« passerelle professionnelle vers Claude ») sont **conservées** : elles décrivent le service, ne le nomment pas.
8. Comportement fonctionnel strictement inchangé.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Une référence à `claude-portal-logo.png` oubliée | Image cassée → couverte par le test landing (src = `ng-portal-logo.png`) et par la garde `verify-public-metadata` |
| Le nouveau nom fuit dans une page partagée ou un e-mail client (qui ne doivent nommer aucun outil) | Build en échec : `page-partagee.mjs` / `page-partagee.test.mjs` et `ClientMailNeverNamesTheToolTest` interdisent aussi « NG Portal » |
| Un « Claude Portal » résiduel dans le code livré | `grep` vide hors docs historiques et listes d'interdits — vérifié en review |

---

## Critères d'acceptation

- [ ] `<title>` de `index.html` commence par « NG Portal — ».
- [ ] Toolbar et landing affichent « NG Portal ».
- [ ] `manifest.webmanifest` : `name` = `short_name` = « NG Portal ».
- [ ] Aucun fichier `claude-portal-logo.png` ; `ng-portal-logo.png` présent et référencé partout.
- [ ] `grep -ri "claude portal"` hors `docs/` ne renvoie que les listes d'interdits.
- [ ] Les gardes « ne nomme pas l'outil » interdisent aussi « NG Portal ».
- [ ] Suites frontend et backend vertes.

---

## Périmètre

### Hors scope (explicite)

- Identifiants techniques : package `fr.claudegateway`, nom du dépôt, base, namespace k8s, images Docker — invisibles des utilisateurs, coût et risque élevés.
- Nouveau logo : l'emblème actuel ne porte aucun texte.
- Domaine / URL : inchangés.
- Documents historiques (`docs/features/F-29/…`, historique PRODUCT_SPEC) : non réécrits.

---

## Valeurs initiales

Sans objet.

## Contraintes de validation

Sans objet (aucune saisie).

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

Aucune.

### Composants Angular (si applicable)

`index.html`, `manifest.webmanifest`, `robots.txt`, `landing`, `shell`, `billing`, `legal-info.ts`, `styles.scss` (commentaires), scripts de build `verify-public-metadata.mjs` / `page-partagee*.mjs`. Backend : `help/*.md`, `HelpChatService`. Infra : `k8s/base/corporate/configmap.yaml`.

---

## Plan de test

### Tests unitaires

- `landing.component.spec` : marque « NG Portal », logos `ng-portal-logo.png`.
- `shell.component.spec` : marque « NG Portal » sans « Proxy ».
- `tab-alert.service.spec` : titre de référence mis à jour.
- `HelpDocumentLoaderTest` : « Découvrir NG Portal ».

### Tests d'intégration

- `verify-public-metadata.mjs` (garde du HTML servi) : `<title>NG Portal —`.
- `page-partagee.test.mjs` + `ClientMailNeverNamesTheToolTest` : « NG Portal » interdit.

### Isolation workspace

Sans objet : aucun accès aux données.

---

## Dépendances

### Subfeatures bloquantes

Aucune (F-29 Terminée).

### Questions ouvertes impactées

OQ-12 (apex en HTTPS) : non impactée.

---

## Préoccupations transversales

Aucune cochée (ni auth, ni tenant, ni plans, ni routing).

## Notes et décisions

- **Décision PO 2026-10-10** : « NG Portal », retenu contre « LLM Portal » (mot « LLM » = signal défavorable pour les filtres d'entreprise, cf. F-29).
- Mentions descriptives « vers Claude » conservées (usage nominatif du modèle relayé).
