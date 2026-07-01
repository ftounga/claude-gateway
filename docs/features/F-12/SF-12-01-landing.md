# Mini-spec — [F-12 / SF-12-01] Landing page consultants

## Identifiant

`F-12 / SF-12-01`

## Feature parente

`F-12` — Landing & onboarding (consultants)

## Statut

`in-progress`

## Date de création

2026-07-01

## Branche Git

`feat/SF-12-01-landing`

---

## Objectif

Fournir une page produit publique (route `/`) présentant claude-gateway aux consultants, avec un
appel à l'action principal « Démarrer l'essai gratuit » menant à l'inscription.

---

## Comportement attendu

### Cas nominal

- **Landing** (`/`, publique) : hero (titre de valeur + sous-titre), section « bénéfices consultants »
  (3 arguments : accès sécurisé, expérience Claude native, essai 14 jours), section « comment ça
  marche » (3 étapes), et zone d'appels à l'action.
- **CTA principal** « Démarrer l'essai gratuit » → navigue vers `/register`.
- **CTA secondaire** « Se connecter » → navigue vers `/login`.
- **Utilisateur déjà authentifié** (JWT présent) : les CTA « essai/connexion » sont remplacés par
  « Ouvrir le chat » → `/chat`, pour éviter de renvoyer un utilisateur connecté vers l'inscription.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Aucun appel réseau au chargement | La landing est statique : aucun état d'erreur réseau possible |
| Route inconnue (`**`) | Redirige vers `/` (landing) — comportement de routage existant conservé |

---

## Critères d'acceptation

- [ ] La route `/` affiche la landing consultants (remplace l'ancien placeholder de statut backend).
- [ ] Le CTA principal « Démarrer l'essai gratuit » route vers `/register`.
- [ ] Un CTA secondaire route vers `/login`.
- [ ] Si l'utilisateur est authentifié, un CTA « Ouvrir le chat » vers `/chat` est présenté à la place.
- [ ] Aucun appel réseau n'est déclenché par la landing (page marketing statique).
- [ ] Couleurs / polices / espacements conformes `DESIGN_SYSTEM.md` (palette indigo/cyan, Space
      Grotesk / Inter, espacements multiples de 4px) ; aucun `window.alert/confirm/prompt`.
- [ ] Tests unitaires (composant, `AuthService` mocké) verts ; `npm run build` et `npm test` verts.

---

## Périmètre

### Hors scope (explicite)

- L'onboarding post-inscription et le choix Hosted/BYOK → `SF-12-02`.
- Contenu marketing définitif / SEO / i18n / illustrations sur mesure.
- Toute logique backend : la landing est purement frontend.

---

## Technique

### Endpoint(s)

Aucun. La landing ne consomme aucun endpoint (lecture locale de l'état d'authentification via
`AuthService.isAuthenticated`, basé sur la présence du JWT en `localStorage`).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable (frontend, aucun schéma).

### Composants Angular (standalone)

- `landing/landing.component.ts|html|scss` — page produit publique.
- Modification `app.routes.ts` : `/` → `LandingComponent` (lazy). Suppression du placeholder
  `HomeComponent` / `HomeService` (artefacts de dev non routés ailleurs).

---

## Plan de test

### Tests unitaires (mock du service)

- [ ] `LandingComponent` — se crée sans appel réseau.
- [ ] `LandingComponent` — non authentifié : expose les CTA inscription (`/register`) et connexion.
- [ ] `LandingComponent` — authentifié : expose le CTA `/chat` (via `AuthService.isAuthenticated`).

### Tests d'intégration

- [x] Non applicable (aucun endpoint backend créé/modifié).

### Isolation utilisateur

- [x] Non applicable côté front : page publique sans données utilisateur. L'isolation reste garantie
      côté backend via le `user_id` porté par le JWT pour les routes protégées.

---

## Dépendances

### Subfeatures bloquantes

- `F-01` (inscription/connexion) — Done : cibles des CTA.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Remplacement du placeholder `/`** : l'ancien `HomeComponent` affichait un statut de backend (utile
  en dev, sans valeur produit). Il est remplacé par la landing et supprimé (réversible).
- **CTA contextuel selon l'état d'auth** : évite d'inviter un utilisateur déjà connecté à s'inscrire ;
  décision UX réversible.
- **Page statique** : aucun appel réseau, meilleure perf perçue et pas de dépendance backend au
  premier écran public.
</content>
