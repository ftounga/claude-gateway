# F-12 — Landing & onboarding (consultants)

Page produit destinée aux consultants (CTA essai gratuit) + parcours d'onboarding en 2 étapes
(inscription puis choix du mode fournisseur Hosted/BYOK).

**Feature 100 % frontend** : consomme uniquement des endpoints existants
(F-01 inscription, F-09 catalogue de plans / checkout). Aucun nouvel endpoint, aucune table,
aucune migration Liquibase. Provider-First : aucune capacité IA n'est réimplémentée.

## Subfeatures

| ID | Titre | Statut |
|----|-------|--------|
| SF-12-01 | Landing page consultants (public, CTA essai → inscription) | done |
| SF-12-02 | Onboarding 2 étapes (compte + choix Hosted/BYOK) | done |

## Décisions d'arbitrage (réversibles)

- **La landing remplace l'ancien placeholder `/`** (carte de statut backend, artefact de dev). Réversible.
- **Le mode fournisseur choisi à l'onboarding n'est pas persisté côté serveur** : aucun endpoint V1
  ne l'expose (PROJECT.md §11.3 le prévoit mais F-01/F-09 ne l'implémentent pas). L'onboarding est
  une couche de guidage/routage ; la préférence est mémorisée en `localStorage` (réversible).
- **Le chemin BYOK route vers l'abonnement** (plans BYOK de F-09) avec une note indiquant que la
  saisie de la clé se fera dans les Réglages (gestion de clé = F-03, non livrée). Évite toute dérive
  hors périmètre. Réversible.
</content>
