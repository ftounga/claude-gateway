# Mini-spec — F-62 / SF-62-03 — Générer et suivre les codes depuis la console d'administration

## Identifiant

`F-62 / SF-62-03`

## Feature parente

`F-62` — Codes d'accès à durée limitée

## Statut

`done` — PR #355, mergée le 2026-09-10

## Date de création

2026-09-10

## Branche Git

`feat/SF-62-03-console-codes-acces`

---

## Objectif

Donner à l'admin, dans `/admin`, l'endroit où il crée un code, le copie une fois, et lit ensuite qui
l'a consommé, quand, et vers quel plan ce compte reviendra.

---

## Comportement attendu

### Cas nominal

1. Une section **« Codes d'accès »** s'ajoute dans `/admin`, sous la liste des utilisateurs et à côté
   de la section Gouvernance — même écran, même garde : il n'y a qu'un seul endroit où l'on se
   demande ce qu'un admin peut faire (arbitrage repris de F-51).
2. Bouton *Nouveau code* → dialogue (`MatDialog`, jamais `window.confirm`) demandant un **libellé**
   (obligatoire, ex. « démo prospect Dupont ») et, facultativement, un **e-mail** pour rendre le code
   nominatif.
3. À la création, le code en clair est affiché **une fois**, dans un encart explicite (« ce code ne
   sera plus jamais affiché »), avec un bouton *Copier*.
4. La liste montre, par code : libellé · destinataire (ou « non nominatif ») · état (**à remettre** /
   **en cours** / **terminé** / **périmé**) · consommé par (e-mail) · date de consommation · terme du
   droit · plan de retour. **Jamais le code.**

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Libellé vide | Bouton *Créer* désactivé. |
| Refus du backend (403, 400) | Le message du backend est affiché tel quel (il nomme déjà le champ fautif) — même règle que la section Gouvernance. |
| Chargement de la liste en échec | Message « La liste des codes n'a pas pu être lue. » + bouton *Réessayer*. |

---

## Critères d'acceptation

- [ ] La section n'existe que dans `/admin` ; un non-admin n'y accède pas (route + 403 serveur).
- [ ] Le code en clair est affiché **une seule fois**, à la création, avec un avertissement explicite
      et un bouton *Copier*.
- [ ] La liste ne contient **jamais** de code en clair (ni dans le DOM, ni dans la réponse API).
- [ ] Chaque ligne porte la trace complète : qui, quand, jusqu'à quand, plan de retour.
- [ ] L'état affiché est **calculé par le serveur** ; l'écran ne redérive pas l'expiration.
- [ ] **Design system** : jetons `--cg-*`, composants Material déjà employés dans `/admin` ; la passe
      de cohérence F-56 et l'identité SF-49-03 restent intactes.
- [ ] Tests du composant : liste rendue, création réussie (code affiché une fois), erreur de
      chargement, erreur de création.

---

## Périmètre

### Hors scope (explicite)

- Révoquer / supprimer un code (non demandé ; réversible à ajouter plus tard).
- Envoyer le code par e-mail au destinataire.
- Toute pagination ou recherche (le volume attendu se compte en dizaines).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|---|---|---|---|---|
| `label` | Oui | 120 | non vide après trim | `trim()` |
| `assignedEmail` | Non | 255 | e-mail | `trim()`, minuscules côté serveur |

---

## Technique

### Endpoints consommés

| Méthode | URL | Rôle |
|---|---|---|
| GET | `/api/admin/access-codes` | ADMIN |
| POST | `/api/admin/access-codes` | ADMIN |

### Composants Angular

- `AccessCodesComponent` (`admin/access-codes/`) — la section.
- `AccessCodeDialogComponent` — le formulaire de création + l'affichage unique du code.
- `AccessCodeAdminService` + `access-code-admin.models.ts`.
- `AdminComponent` — importe et affiche la section.

### Migration Liquibase

- [ ] Non applicable.

---

## Plan de test

### Tests unitaires (composant)

- [ ] La liste rend les codes renvoyés par le service.
- [ ] Une création réussie affiche le code une fois et recharge la liste.
- [ ] Une erreur de chargement affiche le message d'échec et le bouton *Réessayer*.
- [ ] Une erreur de création affiche le message du backend.

### Isolation utilisateur

- [x] Non applicable au sens tenant (écran d'administration transverse, gaté par
      `AdminService.assertAdmin()` côté serveur) — mais la **trace** affichée nomme bien le compte
      consommateur, ce qui est l'exigence de F-62.

---

## Dépendances

- `SF-62-01` — **doit être mergée avant** (contrat d'API).

---

## Notes et décisions

- **D9 — Dans `/admin`, pas une route à part.** Reprise explicite de l'arbitrage F1 de F-51 : une
  seule page répond à « que peut faire un admin ».
- **D10 — Pas de bouton *Révoquer*.** Le droit dure au plus 24 h ; ajouter la révocation reste
  possible sans rien défaire.
