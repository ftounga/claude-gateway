# F-01 — Authentification (découpage en subfeatures)

**Feature parente** : F-01 (V1, `docs/PRODUCT_SPEC.md`).
**Décision auth** : OAuth2/OIDC (Google) **+** email/mot de passe via **JWT** (OQ-05 tranchée).
**Préoccupation transversale** : Auth / Principal + contexte tenant `user_id` → SF-01-01 pose le socle, toutes les autres SF s'y branchent.

## Subfeatures

| SF | Objet | Dépend de | Parallélisable |
|----|-------|-----------|----------------|
| **SF-01-01** | Socle sécurité : entité `User`, migration Liquibase, Spring Security stateless, `JwtService`, résolution `CurrentUser` (isolation `user_id`), endpoint `/api/me` | — (racine, **séquentiel, pose les patterns**) | non |
| **SF-01-02** | Inscription + connexion email/mot de passe (hash BCrypt, émission JWT) | SF-01-01 | back//front |
| **SF-01-03** | Vérification d'email (token, endpoint de confirmation, envoi mail) | SF-01-01 | back//front |
| **SF-01-04** | Réinitialisation de mot de passe (demande + reset par token) | SF-01-01 | back//front |
| **SF-01-05** | Connexion OAuth2/OIDC Google (fédération vers le même `User`) | SF-01-01 | back//front |
| **SF-01-06** | Profil utilisateur (`GET/PUT /api/me`) + déconnexion toutes sessions | SF-01-01 | back//front |
| **SF-01-07** | Écrans Angular `auth/` (login / register / verify / reset / profil) | SF-01-02..06 (contrats figés) | — |

## Séquencement de livraison
1. **SF-01-01 séquentiel** — établit package layout, pattern entité/repo, Liquibase, sécurité, JWT, isolation, pattern de test. Revue avant de paralléliser.
2. **SF-01-02 → SF-01-06 en parallèle** (contrats API figés dans leurs mini-specs) via `parallel-frontback-delivery`.
3. **SF-01-07** écrans, une fois les contrats back stabilisés.

Hors périmètre F-01 : quotas (F-10), BYOK (F-03), billing (F-09).
