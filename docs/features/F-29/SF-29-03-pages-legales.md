# Mini-spec — [F-29 / SF-03] Pages légales publiques

---

## Identifiant

`F-29 / SF-03`

## Feature parente

`F-29` — Identité publique & conformité web

## Statut

`ready`

## Date de création

2026-08-23

## Branche Git

`feat/SF-29-03-pages-legales`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Publier les pages légales du service — mentions légales, politique de confidentialité, conditions générales d'utilisation et contact — afin d'identifier publiquement l'éditeur, de satisfaire les obligations légales d'un service en ligne commercial, et de fournir aux analystes de reclassification la preuve qu'un éditeur réel est derrière le domaine.

---

## Contexte

Troisième et dernier signal du diagnostic du 2026-08-21. Le site ne comportait **aucune page légale** : rien n'identifiait l'éditeur, aucune politique de données, aucune condition d'utilisation. Pour un moteur de classification comme pour l'analyste humain qui instruit une demande de reclassification, l'absence d'éditeur identifiable est un critère défavorable — et c'est l'un des points vérifiés manuellement.

Au-delà de la classification, ces pages sont des **obligations légales** : article 6-III de la loi pour la confiance dans l'économie numérique (mentions légales et directeur de la publication), RGPD articles 13-14 (information des personnes), et conditions contractuelles d'un service payant.

---

## Comportement attendu

### Cas nominal

1. Quatre routes publiques, accessibles **sans authentification** : `/mentions-legales`, `/confidentialite`, `/cgu`, `/contact`.
2. Chaque page affiche son contenu dans une mise en page lisible conforme au design system, avec un lien de retour vers l'accueil.
3. Le pied de page de la landing expose les quatre liens.
4. Les quatre URL sont ajoutées au `sitemap.xml` et restent indexables (`robots.txt` inchangé sur ce point : elles sont couvertes par `Allow: /`).
5. Les informations de l'éditeur proviennent d'**une source unique** (`legal-info.ts`) : une correction de raison sociale ou d'adresse se fait à un seul endroit.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Utilisateur non authentifié accédant à une page légale | **Accès autorisé** — ces routes ne sont pas protégées par `authGuard` (c'est le point le plus important de cette subfeature : une page légale derrière une authentification ne remplit aucune de ses fonctions) |
| Utilisateur authentifié accédant à une page légale | Accès autorisé, page affichée hors coquille applicative |
| URL légale inconnue (ex. `/mentions`) | Comportement de route inconnue inchangé (`**` existant) |
| Donnée d'éditeur manquante dans `legal-info.ts` | Détecté à la compilation : constante typée, champs obligatoires |

---

## Critères d'acceptation

- [ ] Les 4 routes existent, sont **publiques** (hors `authGuard`) et rendent leur composant
- [ ] Les mentions légales affichent : raison sociale, forme juridique, capital social, SIREN et ville du RCS, adresse du siège, président, **directeur de la publication (personne physique)**, e-mail de contact, et l'identité de l'hébergeur
- [ ] La politique de confidentialité couvre : responsable de traitement, catégories de données, finalités, bases légales, **sous-traitants et transferts hors UE**, durées de conservation, droits des personnes et modalités d'exercice
- [ ] Les CGU couvrent : objet, accès et compte, usage acceptable, offres et facturation, disponibilité, responsabilité, résiliation, droit applicable
- [ ] La page contact affiche l'e-mail et l'adresse postale, **sans formulaire** (aucun endpoint backend n'est créé)
- [ ] Toutes les données d'éditeur proviennent de `legal-info.ts` — aucune valeur en dur dans un template
- [ ] Le pied de page de la landing expose les 4 liens
- [ ] `sitemap.xml` contient les 4 URL ; le vérificateur les reconnaît comme publiques
- [ ] `npm run verify:public` et `verify:public:dist` restent verts
- [ ] Aucune valeur d'éditeur inventée : toutes proviennent de l'extrait Kbis ou ont été fournies explicitement
- [ ] Conformité `DESIGN_SYSTEM.md` (jetons `--cg-*`, polices, espacements ×4px)
- [ ] Build production OK et intégralité des specs vertes

---

## Périmètre

### Hors scope (explicite)

- **Formulaire de contact** : nécessiterait un endpoint, une protection anti-spam et un envoi d'e-mail. Une adresse e-mail affichée remplit l'obligation.
- **Bandeau de consentement aux cookies** : le service n'utilise ni cookie de mesure d'audience ni traceur publicitaire ; seul un stockage technique d'authentification est utilisé, exempté de consentement. À réévaluer si un outil d'analyse est ajouté.
- **CGV distinctes des CGU** : les conditions de facturation sont traitées dans les CGU. Une séparation CGU/CGV pourra être faite si l'offre se complexifie.
- **Versionnage et acceptation tracée des CGU** (case à cocher à l'inscription, historique des versions acceptées) : sujet distinct, qui touche le parcours d'inscription et la base de données.
- **Traduction** : pages en français uniquement.
- **Page vitrine du domaine racine `ng-itconsulting.com`** : livrable séparé, hors de ce dépôt.

---

## Valeurs initiales

Sans objet — aucune entité créée, aucun état persisté.

---

## Contraintes de validation

Aucun champ de saisie n'est introduit (pas de formulaire). Contraintes portant sur les données publiées :

| Donnée | Source | Contrainte |
|--------|--------|-----------|
| Raison sociale, forme juridique, capital, SIREN, siège, président | Extrait Kbis du 23/12/2025 | Reproduites **à l'identique**, jamais reformulées |
| Directeur de la publication | Fourni explicitement le 2026-08-23 | Personne **physique** (exigence LCEN art. 6-III) |
| E-mail de contact | Fourni explicitement le 2026-08-23 | — |
| N° de TVA intracommunautaire | **Non publié** | Absent du Kbis ; assujettissement non confirmé — publier un numéro pour une société en franchise serait une mention fausse |
| Hébergeur | Connu (infrastructure AWS eu-west-3) | Raison sociale et adresse de l'entité européenne |

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
| `frontend/src/app/legal/legal-info.ts` | **Créé** — source unique des données d'éditeur (constante typée) |
| `frontend/src/app/legal/legal-page.component.*` | **Créé** — mise en page commune des pages légales (en-tête, retour à l'accueil, conteneur de lecture) |
| `frontend/src/app/legal/mentions-legales.component.*` | **Créé** |
| `frontend/src/app/legal/confidentialite.component.*` | **Créé** |
| `frontend/src/app/legal/cgu.component.*` | **Créé** |
| `frontend/src/app/legal/contact.component.*` | **Créé** |
| `frontend/src/app/app.routes.ts` | 4 routes publiques ajoutées, **avant** la route parente authentifiée |
| `frontend/src/app/landing/landing.component.html` | Liens de pied de page |
| `frontend/public/sitemap.xml` | 4 URL ajoutées |
| `frontend/scripts/verify-public-metadata.mjs` | `PUBLIC_URLS` étendu aux 4 URL légales |

### Composants Angular

5 composants standalone, lazy-loadés comme les autres routes du projet.

---

## Plan de test

### Tests de composant

- [ ] `mentions-legales.component.spec` — affiche la raison sociale, le SIREN, le directeur de la publication et l'hébergeur
- [ ] `confidentialite.component.spec` — mentionne les sous-traitants et les droits des personnes
- [ ] `cgu.component.spec` — affiche les sections attendues
- [ ] `contact.component.spec` — affiche l'e-mail et l'adresse, et **ne contient aucun `<form>`**
- [ ] `app.routes` — les 4 routes légales ne sont **pas** protégées par `authGuard` (garde-fou : une page légale inaccessible sans compte ne remplit pas son rôle)
- [ ] Non-régression : specs existantes vertes

### Vérificateur des signaux publics

- [ ] `PUBLIC_URLS` étendu → `verify:public` valide la présence des 4 URL dans `sitemap.xml` et l'absence d'URL non publique

### Vérification post-déploiement

- [ ] Les 4 URL répondent en navigation privée (sans session)
- [ ] Les liens du pied de page fonctionnent

### Isolation utilisateur

- [ ] Applicable
- [x] **Non applicable** — raison : contenu statique public, aucune donnée lue ni écrite, aucun service ni repository touché.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun composant d'authentification touché ; les pages sont publiques par conception. |
| Contexte tenant | **Non** | Aucun accès aux données. |
| Plans / limites | **Non** | Les CGU **décrivent** les offres sans appeler aucun service de quota ni de facturation. |
| **Navigation / routing** | **OUI** | 4 routes ajoutées à `app.routes.ts`. **Composants impactés à vérifier** : les routes publiques existantes (`''` landing, `/login`, `/register`, `/auth/*`) doivent rester accessibles sans session ; la route parente pathless authentifiée (`ShellComponent` + `authGuard`) et ses 11 enfants doivent rester protégés ; le joker `**` doit continuer de capter les URL inconnues. Les 4 nouvelles routes sont déclarées **avant** la route parente pathless — Angular résolvant dans l'ordre de déclaration, une déclaration après le parent pathless les ferait passer par `authGuard`. Vérification par test sur la table de routes, pas seulement à l'œil. |

---

## Dépendances

### Subfeatures bloquantes

- `SF-29-01` — done (marque « Claude Portal »)
- `SF-29-02` — done (`sitemap.xml` et vérificateur, étendus ici)

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

- ⚠️ **Ces documents ne sont pas un avis juridique.** Ils sont rédigés à partir des obligations usuelles (LCEN art. 6-III, RGPD art. 13-14) et de la connaissance du fonctionnement réel du service. **Une relecture par un professionnel du droit est recommandée avant de s'y fier**, en particulier sur les clauses de responsabilité et de résiliation des CGU. Cette réserve est portée dans la PR.
- **Transferts hors UE à déclarer honnêtement** : les requêtes sont relayées à **Anthropic** (États-Unis) et les paiements traités par **Stripe**. L'hébergement est en France (AWS eu-west-3). La politique de confidentialité doit le dire — l'omettre serait à la fois une faute RGPD et un mauvais signal auprès d'un analyste.
- **Aucune donnée d'éditeur inventée** : deux tentatives précédentes d'écrire « NG IT Consulting » ont été retirées faute de confirmation. Les valeurs publiées proviennent de l'extrait Kbis du 23/12/2025 et des informations fournies explicitement.
- **Écart nom de domaine / raison sociale** : le domaine est `ng-itconsulting.com`, la société `NG-CONSULTING`. Sans conséquence juridique, mais les mentions légales portent la raison sociale exacte du Kbis.
