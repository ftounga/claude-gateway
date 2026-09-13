# Mini-spec — F-95 / SF-95-02 — L'inspection lit la machine

## Identifiant

`F-95 / SF-95-02`

## Feature parente

`F-95` — L'intégrité du poste

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-95-02-l-inspection-du-poste`

---

## Objectif

Produire le **rapport d'intégrité d'un poste** en lisant **la machine** par le runner : la carte à la
racine, les projets et leur `STATE.md`, les dépôts clients et leurs notes non versionnées, et les
références mortes de la carte.

---

## Pourquoi ça lit la machine, et pas la base

Le produit sait ce qu'un paquet **devrait** avoir déposé. Il ne sait pas ce que la racine d'un poste
**porte** aujourd'hui : un fichier a pu être supprimé, un dossier déplacé, un dépôt cloné au mauvais
endroit. **C'est la machine qui fait foi** — c'est déjà la doctrine de F-92 (`GovernanceHostFiles`)
et de F-94 (`JugeMatiereReader`), et c'est ce qui rend un constat vrai plutôt que plausible.

**Ce qu'on ne fait pas pour autant** : livrer un script. F-50 a posé qu'un paquet porte des
**identifiants** de contrôles présents dans le produit, jamais du code — un catalogue ouvert ferait
s'exécuter sur la machine de chaque utilisateur du code publié par quelqu'un d'autre. L'inspection
reste donc un **composant du serveur** ; elle *lit* la machine par les outils du runner.

---

## Comportement attendu

### Cas nominal

`IntegriteInspection.dePoste(userId, host)` rend un `IntegriteRapport` (SF-95-01). Trois passes,
dans cet ordre — et l'ordre est le message, puisque le rapport ne cite que les premiers constats :

**1. La carte** — pour chaque fichier de genre `MAP` attendu sur ce poste
(`GovernanceMapDestinations`, jamais une liste écrite en dur) :

| Ce qu'on lit à la racine | Constat |
|---|---|
| `ABSENT` | `carte/fichier-absent` — **erreur** |
| `PRESENT` sans aucune section `##` | `carte/sans-structure` — avertissement |
| `PRESENT`, c'est l'index (`README.md`), plus de 60 **faits** | `carte/index-surcharge` — avertissement |
| `UNKNOWN` (droits, dossier, trop gros) | **rien** — on ne conclut pas sur ce qu'on n'a pas lu |
| `UNREACHABLE` | **on s'arrête** : rapport *silencieux*, pas un rapport sain |

**2. Les projets** — ceux du poste (`GovernanceHostScope.projectsOf`, qui exclut déjà le terminal
de poste et celui de Teams) :

| Situation | Constat |
|---|---|
| l'arborescence est **illisible** | **rien** pour ce projet, on passe au suivant |
| le projet porte un `.git/` et n'est **pas** sous `repos/` | `clonage/projet-sans-git` — **erreur** |
| le projet porte un `.git/` **sous `repos/`** | c'est un dépôt client légitime → passe 2 bis |
| pas de `STATE.md` à la racine du projet | `projet/state-absent` — **erreur** |
| `STATE.md` statut **clos** et au moins une case `- [ ]` (dans `STATE.md` ou `PLAN-ACTION.md`) | `dette/a-la-cloture` — **erreur** |
| statut **en cours** et au moins une case | `dette/en-cours` — avertissement |

**2 bis. Les notes personnelles chez le client** — pour un dépôt sous `repos/`, une commande
**constante** du serveur, `git status --porcelain`, exécutée dans le dossier du dépôt. Les lignes
`?? <nom>.md` **à la racine** du dépôt donnent `clonage/note-hors-depot` — **erreur**. Toute autre
issue (pas un dépôt, `bash` non autorisé, délai dépassé) est **silencieuse**.

**3. Les liens morts** — pour chaque fichier de carte lu, les références extraites par
`MapReferences` (SF-95-01) sont vérifiées **une par une** à la racine du poste : `not_found` exact →
`carte/lien-mort`, avertissement. Tout autre code — y compris `is_directory`, qui veut dire que la
cible **existe** — ne conclut rien.

### Ce que l'inspection ne fait jamais

- **elle n'écrit rien** : c'est le contrat d'un contrôle de F-50 — juger, sans effet de bord ;
- **elle ne lève jamais** : poste effacé, machine muette, paquet dépublié, projet d'autrui rendent
  un rapport *silencieux*, jamais une exception ;
- **elle ne journalise aucun contenu** : ce sont les fichiers de l'utilisateur, sur la machine d'un
  client. Le journal du runner note *qu'on a lu*, jamais *ce qu'on a lu*.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| poste « Hébergé » (pas de machine) | rapport **silencieux**, aucun appel émis |
| aucun paquet actif sur le poste | rapport **silencieux** : aucune carte n'est attendue |
| runner injoignable / délai dépassé | rapport **silencieux** dès le premier `UNREACHABLE` — on ne relance pas cinq délais pour l'apprendre cinq fois |
| un fichier de carte illisible (droits) | aucun constat pour lui ; les autres sont lus |
| arborescence d'un projet illisible | aucun constat pour ce projet |
| `bash` refusé par le runner (`--no-bash`) | aucun constat de note ; le reste du rapport est rendu |
| budget d'appels épuisé | le rapport est rendu avec ce qui a été vu — jamais une conclusion sur ce qui n'a pas été lu |
| un projet dont le listage frôle la borne du runner | l'**absence** d'un `STATE.md` n'est pas conclue (listage probablement tronqué) |

Aucun code HTTP : cette subfeature n'expose **aucun endpoint** (l'écran arrive en SF-95-03).

---

## Critères d'acceptation

1. Un poste sans machine, sans gouvernance active, ou dont la racine est injoignable rend un rapport
   **silencieux** — et `silencieux` se distingue de `sain`.
2. Un fichier de carte absent produit `carte/fichier-absent` ; un fichier illisible ne produit
   **rien**.
3. Un fichier de carte sans section produit `carte/sans-structure` ; un index de plus de 60 faits
   produit `carte/index-surcharge` ; les **gabarits livrés** (0 fait) n'en produisent aucun.
4. Un projet qui porte un `.git/` hors `repos/` produit `clonage/projet-sans-git`, et **aucun**
   constat de `STATE.md` : un dépôt n'est pas un sujet.
5. Un projet sans `STATE.md` produit `projet/state-absent`.
6. Une case `- [ ]` avec statut `clos` produit `dette/a-la-cloture` (erreur) ; avec statut en cours,
   `dette/en-cours` (avertissement). Les cases de `PLAN-ACTION.md` comptent aussi.
7. Un dépôt sous `repos/` dont `git status --porcelain` rend `?? notes.md` produit
   `clonage/note-hors-depot` ; `?? src/notes.md` (pas à la racine) et `?? notes.txt` n'en produisent
   pas ; la commande émise est **exactement** `git status --porcelain`, sans interpolation.
8. Une référence de carte inexistante produit `carte/lien-mort` ; une référence qui est un
   **dossier** n'en produit pas.
9. L'inspection ne fait **aucune** écriture, et aucun appel n'est émis pour un poste non possédé.
10. Le nombre d'appels runner d'une inspection est **borné**, et l'épuisement du budget n'invente
    aucun constat.
11. `mvn test` vert sur le backend.

---

## Plan de test minimal

### Unitaires — `IntegriteInspectionTest` (doublures de `GovernanceHostFiles`,
`GovernanceProjectFiles`, `GovernanceHostScope`, `GovernanceMapDestinations`, `RunnerToolGateway`)

- poste « Hébergé » → silencieux, **aucun** appel ;
- aucun paquet actif → silencieux, aucun appel ;
- premier fichier de carte `UNREACHABLE` → silencieux, et **pas** de lecture des suivants ;
- carte absente / sans section / index surchargé / gabarit intact ;
- projet dépôt git hors `repos/` → erreur, et pas de constat `STATE.md` ;
- projet sans `STATE.md` → erreur ; projet illisible → rien ;
- dette : clos → erreur, en cours → avertissement, `PLAN-ACTION.md` compté ;
- dépôt sous `repos/` : `?? notes.md` → erreur ; `?? src/notes.md` et `?? notes.txt` → rien ;
  `bash` refusé → rien ; **la commande émise est la constante attendue** ;
- lien mort : `not_found` → avertissement ; `is_directory` → rien ;
- budget : au-delà de la borne, plus aucun appel n'est émis.

### Intégration — `IntegriteInspectionIntegrationTest`

Contexte Spring complet, runner **absent** : l'inspection d'un poste réel rend un rapport
silencieux sans lever, et le contexte démarre — c'est le test qui attraperait un cycle de beans
(le piège documenté par `GovernanceMapDestinations`).

### Isolation utilisateur

- le poste est résolu par `GovernanceHostScope`, qui passe par `hostService.requireOwned` ;
- les projets viennent de `GovernanceHostScope.projectsOf`, lu par `user_id` **et** `host_id` ;
- test dédié : l'inspection du poste d'un **autre** utilisateur ne rend rien et n'émet aucun appel.

---

## Contraintes de validation

| Champ | Contrainte | Valeur |
|---|---|---|
| appels runner par inspection | borné | 24 |
| projets inspectés | borné | 8 |
| références vérifiées par fichier de carte | borné | `MapReferences.MAX_REFERENCES` (10) |
| notes non versionnées citées par dépôt | borné | 5 |
| délai de `git status --porcelain` | borné | 15 s |
| taille de listage au-delà de laquelle on ne conclut pas une absence | seuil | 5 000 entrées |
| commande émise | **constante**, sans interpolation | `git status --porcelain` |

---

## Tables / endpoints / composants impactés

- **Tables** : aucune. **Migration** : aucune.
- **Endpoints** : aucun.
- **Backend** : `fr.claudegateway.governance.integrite.IntegriteInspection` (nouveau).
  Lecture seule de `GovernanceMapDestinations`, `GovernanceHostFiles`, `GovernanceHostScope`,
  `GovernanceProjectFiles`, `RunnerToolGateway`, `RunnerAuditService`.
- **Frontend** : aucun.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun nouvel endpoint, aucun nouveau Principal ; le `userId` reçu vient de l'appelant déjà authentifié |
| **Contexte tenant** | **oui** | le poste est résolu par `GovernanceHostScope.hostOf` / `require` (déjà `requireOwned`), les projets par `projectsOf` (`user_id` + `host_id`), les fichiers par `GovernanceHostFiles` / `GovernanceProjectFiles` qui reçoivent un poste ou un projet **déjà vérifié possédé**. Aucun nouveau chemin de résolution du tenant n'est créé |
| Plans / limites | non | aucun quota consulté ni modifié |
| Navigation / routing | non | aucune route |

---

## Hors périmètre

- **Brancher l'inspection** sur un point de contrôle de F-50, et l'écran : SF-95-03.
- Énumérer les dépôts de `repos/` qui **ne sont pas ouverts comme projets** : voir l'arbitrage A3 de
  la PR — le produit inspecte ce qu'il connaît.
- Écrire ou corriger quoi que ce soit sur la machine : une inspection juge, elle ne répare pas.
- Les contrôles du prompt qui supposent l'ossature personnelle de l'auteur (`~/poste/`,
  `~/methodo/`, le site), et les cinq scripts.
