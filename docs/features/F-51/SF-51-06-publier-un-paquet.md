# Mini-spec — F-51 / SF-51-06 — Publier un paquet (admin)

## Identifiant

`F-51 / SF-51-06`

## Feature parente

`F-51` — Catalogue de gouvernance

## Statut

`ready`

## Date de création

2026-09-10

## Branche Git

`feat/SF-51-06-publier-un-paquet`

---

## Objectif

Donner à l'admin l'endroit où il **rédige** un paquet — règles, contrôles, gabarits, skills — et où il
le **publie**, sans quoi le catalogue reste vide pour tout le monde.

---

## Comportement attendu

### Cas nominal

1. `/admin` gagne une section **Gouvernance**, sous la liste des utilisateurs.
2. Le tableau des paquets montre : identifiant lisible, nom, version, état (publié / brouillon), ce
   qu'il apporte (règles, contrôles, fichiers).
3. **Nouveau paquet** ouvre un formulaire : `slug` (immuable après création), nom, résumé, règles,
   contrôles (choisis dans la liste **que le serveur fournit**, jamais saisis à la main), et des
   fichiers (chemin + genre + contenu), ajoutables et supprimables.
4. **Modifier** rouvre le même formulaire, pré-rempli, `slug` verrouillé. Enregistrer **remplace
   intégralement** le contenu et incrémente la version — le formulaire le dit.
5. **Publier** / **Dépublier** basculent la visibilité. **Supprimer** demande confirmation et n'est
   proposé que sur un brouillon.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Appelant non admin | La section n'apparaît pas (le lien `/admin` non plus) et l'API rend 403 ; le bandeau d'erreur le dit |
| `slug` déjà pris (409) | Message d'erreur nommant le conflit ; le formulaire reste ouvert avec la saisie |
| Champ invalide (400) | Le message du backend est affiché tel quel — il nomme déjà le champ fautif |
| Suppression d'un paquet publié (409) | Le bouton n'est pas proposé ; si le cas survient, le message l'explique |
| Gateway injoignable | Bandeau + bouton Réessayer |

---

## Critères d'acceptation

- [ ] La section Gouvernance apparaît dans `/admin` et liste tous les paquets, brouillons compris.
- [ ] Les contrôles proposés viennent de `GET /admin/governance/controls` — **aucune saisie libre**.
- [ ] Créer envoie un `POST` avec le contenu saisi ; la liste se met à jour.
- [ ] Modifier envoie un `PUT` **sans** changer le `slug` ; le champ est désactivé.
- [ ] Publier / dépublier envoient les `POST` correspondants et l'état affiché suit.
- [ ] Supprimer n'est proposé que sur un brouillon, et demande confirmation.
- [ ] Une erreur backend (400 / 409) est affichée avec **son** message, sans être réécrite.
- [ ] Ajouter puis retirer un fichier du formulaire ne perd pas les autres.
- [ ] Aucune couleur ni police hors `docs/DESIGN_SYSTEM.md` ; aucune `window.confirm`.

---

## Périmètre

### Hors scope (explicite)

- Écrire des **contrôles** : ce sont des composants du serveur (F-50), un écran n'en crée pas.
- Importer un paquet depuis un fichier : la rédaction se fait dans le formulaire.
- Voir qui a retenu ou activé un paquet : ce serait lire les données d'autres comptes.

---

## Impacts

### Tables / endpoints

**Aucun changement backend.** L'écran consomme les endpoints d'administration de SF-51-01.

### Composants

| Composant | Changement |
|---|---|
| `admin/governance-admin.service.ts` | **créé** — un appel par endpoint d'administration |
| `admin/governance-admin.models.ts` | **créé** — vues admin (contenu des fichiers compris) |
| `admin/governance-packages/*` | **créé** — la section : tableau + actions |
| `admin/governance-packages/package-editor-dialog/*` | **créé** — le formulaire de rédaction |
| `admin/admin.component.*` | la section est ajoutée sous la liste des utilisateurs |

---

## Arbitrages de cette subfeature

| # | Sujet | Décision | Motif | Réversible |
|---|---|---|---|---|
| F1 | Où vit la rédaction | Dans `/admin`, sous les utilisateurs | Publier est un geste d'administration ; lui donner une route à part multiplierait les endroits où l'on se demande « que peut faire un admin » | oui |
| F2 | Contrôles | **Choisis dans une liste**, jamais saisis | La publication refuse un identifiant inconnu (SF-51-01). Laisser taper à la main ferait découvrir l'erreur après coup, alors que le serveur sait déjà répondre | oui |
| F3 | Modification | **Remplacement intégral**, dit dans le formulaire | C'est le contrat du backend. Un formulaire qui laisserait croire à une édition partielle mentirait sur ce qui part | oui |
| F4 | Suppression | Proposée **seulement** sur un brouillon | Le backend la refuse sur un paquet publié ; proposer un bouton qui échoue est un piège | oui |

---

## Plan de test minimal

`governance-packages.component.spec.ts` :

- Chargement : paquets + contrôles disponibles.
- Créer : le dialogue confirmé émet le `POST` avec le contenu saisi.
- Modifier : émet le `PUT`, et le `slug` n'est pas modifiable.
- Publier / dépublier émettent les bons appels et rafraîchissent la liste.
- Supprimer : absent sur un paquet publié ; confirmé sur un brouillon, émet le `DELETE`.
- 409 et 400 : le message du backend est affiché.

`package-editor-dialog.component.spec.ts` : ajout / retrait de fichier, `slug` verrouillé en édition,
contrôles proposés à partir de la liste reçue, fermeture avec le contenu saisi.

### Isolation

Aucune donnée utilisateur n'est manipulée : un paquet est un contenu produit. La garde est celle du
backend (403), et l'écran ne la rejoue pas — il l'affiche.

---

## Contraintes de validation

Toutes côté backend (SF-51-01) : slug `[a-z0-9-]+` de 3 à 64, nom ≤ 120, résumé ≤ 500, règles
≤ 8 000, ≤ 20 contrôles, ≤ 50 fichiers de ≤ 64 000 caractères. Le formulaire les rappelle en
indication et laisse le backend trancher — **une seule** définition de la règle.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Analyse |
|---|---|---|
| **Auth / Principal** | **oui** (rôle ADMIN) | Aucun changement du Principal ni de la chaîne. La section vit dans `/admin`, déjà gardé par `authGuard` et par le rôle côté backend (`AdminService.assertAdmin`). Composants concernés : `AdminComponent` (une section de plus), `GovernanceAdminService` (nouveaux appels sous `/admin/**`). Non-régression : la liste des utilisateurs et son 403 restent inchangés, couverts par `admin.component.spec.ts` et `AdminApiIntegrationTest` |
| Contexte tenant | non | Un paquet n'appartient à aucun utilisateur |
| Plans / limites | non | Aucun quota, aucun gate |
| Navigation / routing | non | Aucune route ajoutée : la section vit dans `/admin` |
