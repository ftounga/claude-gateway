# Mini-spec — F-72 / SF-72-03 — « Ajouter un projet », et les dossiers qu'on n'a pas encore ouverts

## Identifiant

`F-72 / SF-72-03`

## Feature parente

`F-72` — Connecter un poste, puis y ajouter des projets

## Statut

`done`

## Date de création

2026-09-12

## Branche Git

`feat/SF-72-03-ajouter-un-projet`

---

## Objectif

Mettre sur la carte de chaque poste le **second geste** : « Ajouter un projet » — l'explorateur
liste, on clique, le projet existe —, et **montrer sans rien créer** les dossiers de la racine
encore non ouverts.

---

## Comportement attendu

### Cas nominal — « Ajouter un projet »

1. La carte d'un poste **réel** porte un bouton **« Ajouter un projet »**.
2. Il ouvre un dialogue qui liste les **sous-dossiers du poste**
   (`GET /runner-hosts/{id}/folders`, SF-71-02) : un fil du chemin courant, la **racine** comme
   premier choix, un bouton par dossier, une flèche « entrer » pour descendre, la phrase de
   troncature quand la liste est incomplète.
3. Un dossier **déjà ouvert** est marqué « déjà ouvert » et **non sélectionnable**.
4. Le clic sur « Ouvrir ce dossier » appelle `POST /runner-hosts/{id}/projects` (SF-72-01) :
   **aucun nom n'est demandé**, le projet prend celui du dossier.
5. Le dialogue **reste ouvert** et la liste se relit : on peut en ajouter un deuxième, un
   troisième — **sans jamais réappairer**. Chaque ajout est confirmé par une ligne à l'écran.
6. À la fermeture, l'accueil de la Forge **relit sa vue** : les nouveaux projets sont sur la carte.

### Cas nominal — les dossiers qu'on n'a pas encore ouverts

1. Sous la liste des projets d'un poste **connecté**, la carte affiche en **retrait** les dossiers
   de la **racine** qui ne portent pas encore de projet (`used = false`).
2. Chacun s'active d'un **clic** : même appel que ci-dessus, le dossier devient un projet et remonte
   dans la liste des projets.
3. Cette liste est **lue une fois** par poste, au chargement de l'écran, et **relue sur demande**
   (bouton « Rafraîchir » de l'en-tête, ou après un ajout). Elle n'est **pas** relue par le sondage
   de 15 s (arbitrage A1).
4. Au-delà de **8 dossiers**, la liste est **tronquée et le dit** : « et N autres — ouvrez-les
   depuis Ajouter un projet ». C'est la leçon de SF-38-21 : une liste incomplète se **dit**.
5. Le **bruit** n'y figure pas : `.runnerignore`, `.gitignore` et les vingt motifs de SF-38-21 sont
   écartés **par le runner** ; les dossiers cachés le sont par la gateway. L'écran n'ajoute aucun
   filtre — ce qui est exclu ne quitte jamais la machine.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Poste **non connecté** | La section des dossiers **n'apparaît pas** sur la carte. « Ajouter un projet » reste visible mais explique dans son dialogue : « Le runner de ce poste n'est pas connecté : lancez-le sur la machine, puis réessayez. » + « Réessayer ». **Aucun champ vide offert** |
| 409 `runner_browse_unavailable` au retour | Le même encart, et le bouton « Réessayer » |
| 409 `host_project_exists` à l'ouverture | Le message du serveur repris tel quel, et la liste est **relue** : l'écran était en retard (projet créé dans un autre onglet) |
| 400 `invalid_project_path` | « Ce dossier n'est pas exploitable. » — ne devrait pas arriver, les chemins venant de la machine |
| 403 (accès Atelier) | « La Forge est nécessaire pour ce geste. » |
| 404 (poste disparu) | « Poste introuvable. » + relecture de la vue |
| Réseau muet | « Les dossiers n'ont pas pu être lus. » + « Réessayer » ; rien n'est créé |
| Racine sans sous-dossier | « Aucun sous-dossier ici. » — la racine reste ouvrable |
| Poste « Hébergé » | **Ni bouton, ni dossiers** : il n'y a pas de machine à lire |

---

## Critères d'acceptation

- [ ] Chaque carte de poste **réel** porte « Ajouter un projet » ; la carte « Hébergé » ne le porte
      pas.
- [ ] Le dialogue liste les sous-dossiers, permet de descendre et de remonter, et **n'offre aucun
      champ de saisie de chemin**.
- [ ] Ouvrir un dossier crée **un** projet nommé comme lui, **sans demander de nom**.
- [ ] Le dialogue reste ouvert après un ajout : on peut en enchaîner plusieurs.
- [ ] Un dossier déjà ouvert est marqué et non sélectionnable.
- [ ] La carte d'un poste connecté montre les dossiers **non ouverts** de la racine, **en retrait**.
- [ ] Cette liste est tronquée à **8** et **le dit** au-delà.
- [ ] Elle n'est **pas** relue par le sondage de 15 s ; elle l'est au chargement, sur « Rafraîchir »
      et après un ajout.
- [ ] Poste non connecté → **pas** de section de dossiers sur la carte, et le dialogue le dit.
- [ ] **Aucune couleur nouvelle** : les dossiers non ouverts emploient le gris « archivé / inactif »
      du §5 et le retrait ; ils ne prennent aucun ton d'identité (§9), aucune pastille de mission
      (§10), aucun signe de vie (§11).
- [ ] Après fermeture du dialogue, la vue des postes est relue.

---

## Périmètre

### Hors scope (explicite)

- **Créer** un dossier sur la machine (D8 de F-71).
- Montrer les dossiers non ouverts **plus profond** que la racine : la carte montre la racine ; le
  reste se parcourt dans le dialogue.
- Compter les fichiers ou peser un dossier : une information de plus par dossier, c'est un balayage
  de plus sur la machine pour une décision que le nom suffit à prendre (SF-71-02).
- Retirer « Nouveau projet » de la racine : c'est SF-72-04.
- Reprendre les projets de l'ancien parcours (D10).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|-------|-------------|-------------|--------|---------------|
| `path` envoyé | non (vide = racine) | 512 | relatif, `/` | fait par la gateway (`RunnerProjectPath`) ; l'écran renvoie tel quel ce que la machine a listé |

Borne d'affichage sur la carte : **8 dossiers**. Au-delà, on dit combien il en reste. Huit tient
dans une carte sans la faire dérouler ; en afficher trente rendrait la carte illisible, ce qui est
le seul critère ici (D9 : les projets ne sont pas facturés, le poste l'est).

---

## Technique

### Composants Angular

| Composant | Changement |
|---|---|
| `postes/add-project-dialog/` (nouveau) | l'explorateur de dossiers + l'ouverture ; réutilise le gabarit de SF-71-03 |
| `postes.component.ts/.html/.scss` | bouton « Ajouter un projet » ; section « dossiers non ouverts » en retrait ; chargement par poste, hors sondage |
| `core/services/atelier.service.ts` | `openHostProject(hostId, path)` → `POST /runner-hosts/{hostId}/projects` |

### Endpoints consommés

| Méthode | URL | Origine |
|---------|-----|---------|
| GET | `/api/runner-hosts/{id}/folders` | SF-71-02, existant |
| POST | `/api/runner-hosts/{id}/projects` | **SF-72-01** |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — frontend seul.

### Design system

| Élément | Jeton | Règle |
|---|---|---|
| Dossier non ouvert | `--cg-text-secondary` (#64748B) + retrait | le gris « archivé / inactif » du §5 : ce dossier **n'est pas** un projet |
| Filet de la sous-liste | `--cg-divider` | jamais le ton d'identité du poste — il désigne ce qui **est** ouvert |
| Bouton « Ajouter un projet » | `mat-stroked-button` | action secondaire de carte, comme « Terminal » |

**Pas de quatrième registre** : les dossiers non ouverts se distinguent par leur **place** (en
retrait, après les projets) et par le gris déjà prévu. Aucune couleur n'est ajoutée.

---

## Plan de test

### Unitaires (`add-project-dialog.component.spec.ts`)

- [ ] Poste connecté → `runnerHostFolders` appelé, un bouton par dossier.
- [ ] Entrer dans un dossier → second appel avec `path` ; remonter → retour au parent.
- [ ] Ouvrir un dossier → `openHostProject(hostId, chemin)` avec le chemin exact, **sans** demande
      de nom.
- [ ] Après un ajout réussi, le dialogue **reste ouvert** et la liste est relue.
- [ ] Dossier `used` → non sélectionnable.
- [ ] 409 `runner_browse_unavailable` → l'encart « pas connecté », **aucun** `input` de chemin.
- [ ] 409 `host_project_exists` → le message du serveur, et la liste est relue.
- [ ] Liste tronquée → la phrase qui le dit.
- [ ] Racine choisissable, et envoyée comme chaîne vide.

### Unitaires (`postes.component.spec.ts`)

- [ ] Une carte de poste connecté affiche les dossiers non ouverts, en retrait.
- [ ] Un dossier `used` n'y figure pas.
- [ ] Plus de 8 dossiers → 8 affichés + la phrase qui dit le reste.
- [ ] Poste non connecté → aucune section de dossiers.
- [ ] Carte « Hébergé » → ni bouton « Ajouter un projet », ni dossiers, et **aucun** appel
      `runnerHostFolders`.
- [ ] Le sondage de 15 s **ne relit pas** les dossiers ; « Rafraîchir » les relit.
- [ ] Clic sur un dossier non ouvert → `openHostProject` puis relecture de la vue.

### Isolation `user_id`

- [x] Applicable — garantie **côté gateway** : `folders` et `projects` vérifient tous deux
  l'appartenance du poste (`requireOwned`) avant quoi que ce soit, et le marquage `used` ne lit que
  les projets de l'appelant. L'écran n'envoie que l'`id` d'un poste déjà listé pour lui.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | aucun changement |
| Contexte tenant | non | aucun identifiant construit côté écran |
| Plans / limites | **oui** | compteur « Terminaux vivants : n / 4 » (`postes.component.ts`) : **inchangé** — ouvrir un projet n'ouvre aucun terminal. Sièges facturés (F-65) : **inchangés** — le siège est le **poste**, et aucun poste n'est créé ici (D9). `WorkspaceCreatedEvent` reste émis par la gateway comme pour toute création, et ses consommateurs (gouvernance, F-51) ne changent pas |
| Navigation / routing | non | aucune route ajoutée ; le dialogue ne navigue pas |

---

## Notes et décisions

- **A1 (cadrage) — les dossiers ne sont pas sondés** : la vue des postes se relit toutes les 15 s.
  Y attacher une lecture de la machine ferait 240 `list_files` par heure et par poste pour une
  liste qui ne bouge presque jamais — et chacun est une ligne d'audit sur la machine du client. Une
  fois au chargement, et sur demande.
- **Le dialogue reste ouvert après un ajout** : « autant de fois qu'on veut » est la promesse de
  F-48. Refermer après chaque projet obligerait à rouvrir, re-lister, re-descendre — la friction
  qu'on vient de supprimer.
- **Le refus `host_project_exists` déclenche une relecture**, il ne se contente pas d'un message :
  s'il arrive, c'est que l'écran était en retard, et le laisser en retard rejouerait le refus.
