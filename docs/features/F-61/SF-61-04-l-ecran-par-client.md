# Mini-spec — F-61 / SF-61-04 — L'écran « ce que chaque client consomme »

---

## Identifiant

`F-61 / SF-61-04`

## Feature parente

`F-61` — Consommation : par client pour chacun, par utilisateur pour l'admin

## Statut

`done` — livrée le 2026-09-11

## Date de création

2026-09-11

## Branche Git

`feat/SF-61-04-les-ecrans-de-consommation`

---

## Objectif

Montrer à l'utilisateur, sur l'écran **Rapports**, **ce que chaque client lui coûte** — poste par
poste, projet par projet, sur une période qu'il choisit — avec l'identité visuelle du poste (F-49 /
SF-49-03) pour qu'il reconnaisse la mission sans la lire.

---

## Comportement attendu

### Cas nominal

1. Sous l'historique mensuel de `/reports`, une section **« Par client »** appelle
   `GET /api/usage/by-client` et rend une carte par client, du plus consommateur au moins.
2. Chaque carte porte : la **pastille d'identité du poste** (`app-host-badge`, composant existant —
   couleur et initiales, **fonction pure du nom**, rien de rangé nulle part), le nom du poste, le
   **coût estimé**, la **part du total** (barre), et les **tokens d'entrée et de sortie
   distingués** — jamais un total seul.
3. Chaque carte déplie ses **projets**, avec les mêmes colonnes, triés par consommation.
4. Un sélecteur de période — **3, 6 ou 12 mois** — recharge la section. Le choix vaut pour cette
   section seule ; le reste de l'écran F-16 est inchangé.
5. Le seau **« Hors client »** est rendu en dernier, sans pastille, avec une phrase qui dit ce
   qu'il contient (« projets sans poste, conversations et questions ») — plutôt qu'un libellé nu
   qui se lirait comme une anomalie.
6. Une **note de portée** est affichée sous le titre : la ventilation par client commence avec la
   mise en service du relevé ; la consommation antérieure figure dans l'historique mensuel mais
   n'est attribuable à aucun client. Elle est écrite, pas cachée (cadrage §1).
7. **États** : chargement (spinner, comme le reste de l'écran), vide (carte d'état vide, message
   explicite), erreur (snackbar, la section ne casse pas le reste de l'écran).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| API en erreur (5xx, réseau) | Snackbar « Impossible de charger la consommation par client. » ; l'historique mensuel reste affiché |
| Aucune donnée sur la période | Carte d'état vide : « Aucune consommation attribuée sur cette période. » |
| Poste supprimé (`hostName: null`) | « Poste supprimé » en libellé, ligne conservée |
| Projet supprimé (`name: null`) | « Projet supprimé » en libellé, ligne conservée |
| Total nul | Barres à 0 %, aucune division par zéro |

---

## Critères d'acceptation

- [ ] La section apparaît sur `/reports`, sous l'historique mensuel, et **ne modifie ni ne masque**
      ce qui existait (F-16 intact).
- [ ] Chaque client montre **entrée et sortie séparées** et un coût estimé.
- [ ] La pastille d'identité est celle de `HostBadgeComponent` — aucune couleur recalculée sur place,
      aucune palette nouvelle (SF-49-03 non annulée).
- [ ] Le nom du poste est **toujours écrit** : la couleur ne porte jamais seule l'information.
- [ ] Le sélecteur 3 / 6 / 12 mois recharge la section et met à jour les totaux.
- [ ] « Hors client » est rendu en dernier et expliqué.
- [ ] La note de portée est présente.
- [ ] États chargement / vide / erreur couverts.
- [ ] **Aucune couleur, police ou espacement hors `DESIGN_SYSTEM.md`** : uniquement des jetons
      `--cg-*` et les classes de l'écran.
- [ ] Écran étroit : les tableaux restent lisibles (défilement horizontal contenu, pas de débordement
      de page).
- [ ] Tests de composant verts.

---

## Périmètre

### Hors scope (explicite)

- Tout bouton d'action sur les quotas, l'export, la facture.
- Le détail par tour.
- Une page dédiée : la consommation par client appartient à l'écran qui parle déjà de consommation.

---

## Technique

### Composants Angular

- `ReportsComponent` — accueille la section (signaux, `OnPush` conservé s'il l'était).
- `UsageByClientService` (`core/services`) — appelle `/api/usage/by-client`.
- `usage-by-client.models.ts` (`core/models`) — types de la réponse.
- `HostBadgeComponent` — **réutilisé**, non modifié.

---

## Plan de test

### Tests de composant

- [ ] Rendu nominal : deux clients, leurs projets, totaux et parts.
- [ ] Changement de période → nouvel appel avec les bonnes bornes.
- [ ] État vide.
- [ ] Erreur API → snackbar, historique mensuel toujours rendu.
- [ ] Poste/projet supprimé → libellé de repli.

### Isolation

- [x] Non applicable côté écran — l'API ne rend que les données du porteur du JWT (SF-61-02).

---

## Dépendances

- `SF-61-02` — mergée avant (backend d'abord).

---

## Notes et décisions

- **Pourquoi dans `/reports` et non une route neuve** : l'utilisateur qui se demande « combien me
  coûte ce client » se demande d'abord « combien j'ai consommé ». Deux écrans pour une question
  obligeraient à choisir avant de savoir.
