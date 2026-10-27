# Mini-spec — [F-219 / SF-219-03] L'écran du chantier autonome

## Identifiant
SF-219-03

## Feature parente
F-219 — Exécuter un chantier en autonome

## Statut
Done

## Date de création
2026-10-27

## Branche Git
`feat/SF-219-03-ecran-autonome`

---

## Objectif
Montrer dans le tableau des chantiers (F-208) si un chantier est « Prêt à partir », le lancer en autonome d'un clic, suivre « En autonome — étape k/n » et reprendre la main.

## Comportement attendu

### Cas nominal
- À l'ouverture du tableau, et à chaque changement du parcours, l'écran lit `GET /journey/autonomy` ; tant qu'une course tourne sur le sujet, il le relit toutes les 5 s.
- Sous l'en-tête du chantier choisi, un **bloc autonomie** :
  - course en cours → pastille « En autonome — étape k/n » (k = étapes faites + 1, borné à n ; icône animée + libellé, jamais la couleur seule) et bouton **[Reprendre la main]** ;
  - préflight vert → badge **« Prêt à partir »** (vert succès + icône), bouton **[Lancer en autonome]** (orange d'accent), estimation « ≈ 1,50 $ · coût moyen de vos tours sur 30 jours × 3 tour(s) » si elle existe, et « k/4 chantiers autonomes en cours » ;
  - préflight rouge → « Pas prêt à partir » + la liste des raisons telles que le serveur les écrit ;
  - dernière course close → une ligne « Dernière course : terminée / bloquée / arrêtée / en échec — raison ».
- L'onglet d'un chantier en autonome porte la marque « autonome ».
- Lancer : un clic, **aucune confirmation** (F-218) ; succès → snackbar « Chantier N lancé en autonome. Vous pouvez quitter l'écran. » ; le bloc passe en « En autonome ».
- Reprendre la main : un clic → snackbar « Vous avez repris la main sur le chantier N. ».
- **Étape BLOQUÉE** (statut `BLOQUE`, SF-219-01) : état « bloquée », icône cadenas, la raison affichée.
- **Commande `/autonome N`** dans le composer : expansée en une demande à l'agent qui appelle `lancer_chantier_autonome` (SF-219-04).

### Cas d'erreur
- `409` au lancement → snackbar avec le message du serveur (et la première raison s'il y en a), le bloc est relu.
- Erreur réseau sur la lecture → le bloc n'apparaît pas (le tableau reste utilisable).
- Terminal en lecture seule (`canSend=false`) → les boutons ne sont pas montrés, l'état reste lisible.

## Critères d'acceptation
1. Préflight vert → « Prêt à partir » + [Lancer en autonome] ; clic → `POST .../chantiers/N/autonomy`, aucune boîte de confirmation.
2. Préflight rouge → raisons listées, aucun bouton de lancement.
3. Course en cours → « En autonome — étape k/n » + [Reprendre la main] → `POST .../autonomy/stop`.
4. `409` → snackbar avec le message.
5. Étape `BLOQUE` → « bloquée » + raison.
6. `/autonome 2` → demande contenant `lancer_chantier_autonome` et `2`.
7. Conformité `DESIGN_SYSTEM.md` : jetons `--cg-*`, espacements multiples de 4, polices du système, téléphone (le bloc passe en colonne).

## Périmètre
### Hors scope (explicite)
- Le tableau de bord du client et la multi-sélection (F-220).
- Le code couleur « qui débloque ? » (F-220).

## Valeurs initiales
Relecture toutes les 5 s pendant une course.

## Contraintes de validation
Sans objet (aucune saisie).

## Technique
### Endpoint(s)
Consommés : `GET /api/workspaces/{ws}/journey/autonomy`, `POST /api/workspaces/{ws}/journey/chantiers/{n}/autonomy`, `POST /api/workspaces/{ws}/journey/autonomy/stop`.
### Tables impactées
Aucune.
### Migration Liquibase
Aucune.
### Composants Angular
- `core/models/autonomy.models.ts` (nouveau), `core/services/autonomy.service.ts` (nouveau).
- `journey.models.ts` : `BLOQUE` → état « bloquée ».
- `terminal-chantiers-board.component.ts` : bloc autonomie, marque d'onglet, relecture.
- `slash-commands.ts` : `/autonome`.

### Préoccupation transversale — aucune
Pas de route, pas de garde, pas d'auth modifiées.

## Plan de test
### Tests unitaires (Jasmine)
- `terminal-chantiers-autonomy.spec.ts` : prêt → badge + lancer (POST, pas de confirmation) ; pas prêt → raisons ; en cours → étape k/n + reprendre (POST stop) ; 409 → snackbar ; dernière course.
- `journey.models` : `BLOQUE` → `blocked`, raison affichée.
- `slash-commands.spec.ts` : `/autonome 2`.
### Tests d'intégration
- Contrat API couvert par `AutonomyApiIntegrationTest` (backend) ; build Angular complet.
### Isolation utilisateur
Aucun identifiant de compte envoyé ; la gateway part du JWT (404 sur le terminal d'autrui, testé côté backend).

## Dépendances
SF-219-01, SF-219-02, SF-219-04 (mergées).
