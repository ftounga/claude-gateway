# Mini-spec — F-95 / SF-95-01 — Le rapport d'intégrité : deux niveaux, un geste par message

## Identifiant

`F-95 / SF-95-01`

## Feature parente

`F-95` — L'intégrité du poste

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-95-01-le-rapport-d-integrite`

---

## Objectif

Poser **ce qu'un constat d'intégrité est** — son **niveau** (erreur bloquante ou avertissement
informatif), sa **cible**, et son **message qui porte le geste** — ainsi que les **lectures pures**
dont l'inspection de SF-95-02 aura besoin : le statut et la dette d'un `STATE.md`, et les références
de chemins d'un fichier de carte.

---

## Pourquoi ces pièces d'abord

Tout ce qui est livré ici se vérifie **sans machine, sans runner, sans fournisseur et sans tour de
conversation** : ce sont des fonctions d'un texte vers un constat. C'est là que vivent les deux
exigences littérales de la feature, et ce sont précisément celles qu'un test doit pouvoir épingler
ligne à ligne :

| Exigence du prompt | Où elle vit ici |
|---|---|
| « chaque message porte son **action corrective** » | les fabriques de `IntegriteConstat` — un constat ne se construit pas sans geste |
| « **deux niveaux** distincts, ne les mélange pas » | `IntegriteNiveau`, et `IntegriteRapport` qui rend les deux **séparés** |

SF-95-02 lira la machine ; SF-95-03 remettra le rapport à ses lecteurs. Aucune des deux n'aura à
réécrire un message.

---

## Comportement attendu

### 1. Deux niveaux, et ils ne se mélangent pas — `IntegriteNiveau`

| Niveau | Ce que ça veut dire | Ce que ça fait (SF-95-03) |
|---|---|---|
| `ERREUR` | la gouvernance **ne peut pas fonctionner** en l'état : plus de destination où promouvoir, plus de trace de dette, ou du savoir sur le point d'être perdu ou livré chez un client | **bloque** la fin du tour |
| `AVERTISSEMENT` | la carte **fonctionne**, mais elle vieillit mal | **informe**, ne bloque jamais |

La frontière est posée une fois : **est une erreur ce qu'un modèle peut corriger mécaniquement et
dont l'absence de correction fait perdre du savoir**. Est un avertissement ce qui demande un
jugement — et qu'un modèle corrigerait donc en devinant, dans la carte d'un client.

### 2. Les règles — `IntegriteRegle`

Chaque règle porte un **identifiant stable** (publié : il apparaît dans les constats) et son niveau.

| Identifiant | Niveau | Ce qui le déclenche (SF-95-02 / 03) |
|---|---|---|
| `carte/fichier-absent` | ERREUR | un fichier de carte attendu manque à la racine du poste |
| `carte/sans-structure` | AVERTISSEMENT | un fichier de carte présent ne porte aucune section `##` |
| `carte/index-surcharge` | AVERTISSEMENT | l'index de la carte (`README.md`) porte plus de 60 faits |
| `carte/lien-mort` | AVERTISSEMENT | la carte cite un chemin qui n'existe plus |
| `projet/state-absent` | ERREUR | un projet du poste n'a pas de `STATE.md` |
| `dette/a-la-cloture` | ERREUR | statut `clos` **et** au moins une case `- [ ]` |
| `dette/en-cours` | AVERTISSEMENT | statut en cours **et** au moins une case `- [ ]` |
| `clonage/projet-sans-git` | ERREUR | un projet porte un `.git/` |
| `clonage/note-hors-depot` | ERREUR | un `.md` non versionné à la racine d'un dépôt client |

Les deux derniers identifiants **sont ceux de `GovernanceHostRule`** (F-93), et leur énoncé comme
leur geste sont **repris de là**, jamais recopiés : F-93 a écrit noir sur blanc que cette
énumération est « là que F-95 se branchera pour les vérifier mécaniquement, sans réécrire ni les
énoncés ni les gestes ».

**`clonage/depot-dans-repos` n'a pas d'entrée propre** : du point de vue du produit, un dépôt cloné
parmi les sujets **est** un projet qui porte un `.git/` — une seule détection, et c'est
`clonage/projet-sans-git` dont le geste dit déjà « déplace-le sous `repos/` ». Deux constats pour
une même situation rendraient deux corrections dont une seule serait traitée.

### 3. Un constat ne se construit pas sans son geste — `IntegriteConstat`

`IntegriteConstat(regle, cible, message)`. Le `message` est **toujours** de la forme
« *ce qui est constaté* → *ce qu'il faut faire* », et il est produit par une **fabrique par règle**,
jamais par l'appelant :

```
IntegriteConstat.carteAbsente("reseau.md")
 → « le fichier de carte « reseau.md » manque à la racine de ce poste : reprends « Appliquer »
    sur ce poste depuis l'écran Gouvernance pour le reposer, ou crée-le à la racine avec un
    titre « # … » et ses sections « ## … ». Sans lui, il n'y a nulle part où promouvoir ce que
    ce projet fait apparaître. »
```

C'est la garantie mécanique de l'exigence : **il n'existe aucun chemin de code qui fabrique un
constat sans geste**, parce qu'il n'y a pas de constructeur public qui prenne un message libre.

### 4. Le rapport — `IntegriteRapport`

Porte les constats **dans l'ordre de découverte**, sait les rendre **séparés par niveau**, et sait
composer le texte correctif remis au modèle :

- les **erreurs** d'abord, numérotées, chacune avec son geste ;
- puis, **sous un intitulé distinct**, les avertissements — précédés de la mention qu'ils **ne
  bloquent pas** ;
- le tout **borné** : au plus 3 erreurs et 2 avertissements cités, le reste annoncé (« … et N
  autres, rejoués au prochain passage »), et une longueur totale compatible avec les 2 000
  caractères de `AtelierCheckpointVerdict`.

**Un rapport vide est un état normal**, et `silencieux()` dit la différence entre « rien à
signaler » et « rien n'a été inspecté » : la seconde ne doit jamais se présenter comme la première.

### 5. Lire un `STATE.md` — `StateMarkdown`

Rend le **statut** du sujet et le **nombre de cases ouvertes**.

| Ce qui est lu | Résultat |
|---|---|
| `## Statut` suivi d'une ligne contenant `clos`, `clôturé`, `terminé`, `fini` | `CLOS` |
| une ligne `statut : clos` (ou `**Statut** : clos`), n'importe où | `CLOS` |
| statut absent, illisible, ou toute autre valeur | `EN_COURS` |
| `- [ ]` hors bloc de code et hors citation | +1 case ouverte |
| `- [x]` | ignorée (elle est traitée) |

**Le défaut est `EN_COURS`, et c'est structurant** : les `STATE.md` déjà déposés chez les clients
n'ont pas de section `Statut` — tous seraient « clos » si le défaut était l'inverse, et la première
inspection bloquerait tous les tours de tous les postes déjà gouvernés.

**Les blocs de code et les citations sont ignorés** : le gabarit `STATE.md` livré contient
`- [ ] cluster « atlas »` **dans un bloc de code** d'exemple. Les compter ferait déclarer une dette
à un projet qui n'a rien écrit.

### 6. Les références d'un fichier de carte — `MapReferences`

Extrait d'un contenu Markdown les chemins **du poste** qu'il cite, pour que SF-95-02 puisse aller
vérifier qu'ils existent encore.

**Ce qui est retenu** : les cibles de liens `[texte](cible)` et les chemins entre accents graves,
qui ressemblent à un chemin relatif du poste (contiennent un `/`, ou finissent par `.md`).

**Ce qui est écarté — la liste d'exceptions**, parce qu'une référence volontairement indicative
n'est pas un lien mort :

| Écarté | Pourquoi |
|---|---|
| tout ce qui est en **citation** (`>`) ou en **bloc de code** | c'est le texte du gabarit : ses exemples sont indicatifs par construction |
| une URL (`http://`, `mailto:`…) | ne désigne pas un chemin du poste |
| un chemin **absolu** (`/etc/…`, `~/…`, `C:\…`) | désigne la machine du client, pas la racine du poste |
| un chemin portant un **caractère de gabarit** (`<`, `>`, `*`, `$`, `{`, `…`, `?`) | `repos/<dépôt>` est une forme, pas un chemin |
| un chemin de la **liste d'exceptions déclarée** (`chemin/vers`, `dossier/fichier`, `projet/STATE.md`…) | les tournures que les gabarits emploient pour montrer une forme |
| le nom d'un fichier **de la carte elle-même** | il est déjà vérifié par `carte/fichier-absent` ; le signaler deux fois rendrait deux corrections |

La liste est **bornée** (au plus 10 références rendues par fichier) : au-delà, ce n'est plus une
vérification, c'est un crawl.

---

## Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| contenu `null` ou vide donné à `StateMarkdown` / `MapReferences` | lecture neutre — statut `EN_COURS`, zéro case, aucune référence ; **aucune exception** |
| bloc de code non fermé dans un `STATE.md` | tout ce qui suit est du code : les cases ne sont pas comptées (prudence : on ne fabrique pas de dette) |
| message de constat plus long que la borne | tronqué, **jamais** rendu vide — un constat muet resterait un constat |
| rapport dont les constats dépassent la borne de citation | les premiers sont cités, le reste est **annoncé** (« et N autres ») |
| niveau demandé absent du rapport | liste vide, jamais `null` |

Aucun code HTTP : cette subfeature n'expose **aucun endpoint**.

---

## Critères d'acceptation

1. `IntegriteNiveau` distingue `ERREUR` et `AVERTISSEMENT`, et `IntegriteRapport` rend les deux
   **séparément**.
2. Chaque règle de la table porte un identifiant stable, et `clonage/projet-sans-git` /
   `clonage/note-hors-depot` reprennent **l'énoncé et le geste de `GovernanceHostRule`**.
3. Aucun constat ne peut être construit sans message : il n'existe pas de constructeur public
   prenant un message libre.
4. Chaque fabrique de constat produit un message qui **nomme la cible** et **dit le geste** — un
   test le vérifie règle par règle.
5. `IntegriteRapport.correction()` cite les erreurs avant les avertissements, sous des intitulés
   distincts, dit que les avertissements ne bloquent pas, et tient dans 2 000 caractères.
6. Un rapport sans constat rend une correction vide ; `silencieux()` se distingue d'un rapport vide.
7. `StateMarkdown` rend `CLOS` sur les quatre formes citées, `EN_COURS` par défaut, et ne compte
   **pas** les cases des blocs de code ni des citations.
8. `MapReferences` retient un chemin relatif cité dans un fait, et écarte chacune des six catégories
   d'exception.
9. Le gabarit `STATE.md` du paquet porte une section `Statut`, et le document de règles dit à quoi
   elle sert.
10. `mvn -q test` vert sur le module backend.

---

## Plan de test minimal

### Unitaires

- `IntegriteConstatTest` — une fabrique par règle : la cible est nommée, le geste est présent
  (verbe d'action), le message n'est jamais vide ; les deux règles de clonage citent le texte de
  `GovernanceHostRule`.
- `IntegriteRapportTest` — séparation des niveaux ; ordre erreurs → avertissements ; intitulés
  distincts ; bornes de citation et de longueur ; rapport vide ; `silencieux()`.
- `StateMarkdownTest` — les quatre formes de `CLOS` ; le défaut `EN_COURS` ; le gabarit livré tel
  quel rend **zéro** case ouverte ; une case hors code compte ; `- [x]` ne compte pas ; contenu nul.
- `MapReferencesTest` — un chemin cité dans un fait est retenu ; citations, blocs de code, URL,
  chemins absolus, formes à chevrons, liste déclarée et noms de la carte sont écartés ; borne.
- `GovernancePackageSeederTest` (existant) — reste vert avec le gabarit `STATE.md` modifié.

### Intégration

Aucune : cette subfeature n'a ni endpoint, ni accès base, ni appel runner. La couverture
d'intégration arrive avec SF-95-02 (lecture machine) et SF-95-03 (contrôle et écran).

### Isolation utilisateur

Sans objet ici — **aucune** de ces classes ne reçoit d'identifiant d'utilisateur, de projet ou de
poste, et aucune ne lit quoi que ce soit. L'isolation est portée par SF-95-02, qui passe
exclusivement par `GovernanceHostScope` et `GovernanceMapDestinations`.

---

## Contraintes de validation

| Champ | Contrainte | Valeur |
|---|---|---|
| identifiant de règle | minuscules, chiffres, `/` et `-` ; **immuable** | publié dans les constats |
| message d'un constat | non vide, borné | 400 caractères |
| correction d'un rapport | bornée | 2 000 caractères (`AtelierCheckpointVerdict.MAX_CORRECTION_CHARS`) |
| erreurs citées | bornées | 3 |
| avertissements cités | bornés | 2 |
| références rendues par fichier de carte | bornées | 10 |
| faits au-delà desquels l'index est surchargé | seuil | 60 |

---

## Tables / endpoints / composants impactés

- **Tables** : aucune. **Migration** : aucune.
- **Endpoints** : aucun.
- **Backend** : nouveau paquet `fr.claudegateway.governance.integrite` — `IntegriteNiveau`,
  `IntegriteRegle`, `IntegriteConstat`, `IntegriteRapport`, `StateMarkdown`, `MapReferences`.
- **Ressources** : `governance/savoir-durable/STATE.md` (section `Statut`),
  `governance/savoir-durable/regles.md` et `GOUVERNANCE.md` (ce que la section signifie).
- **Frontend** : aucun.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucune de ces classes ne voit un utilisateur |
| Contexte tenant | non | aucune ne résout de poste ni de projet |
| Plans / limites | non | — |
| Navigation / routing | non | — |

**Un changement touche l'existant** : le contenu du gabarit `STATE.md`. Conséquence connue et
assumée — `GovernancePackageSeeder` incrémente la version du paquet au démarrage, et le dépôt
**n'écrase jamais** un fichier déjà posé (F-51) : un poste déjà activé garde son `STATE.md` sans
section `Statut`, et sera donc lu « en cours ». C'est exactement le défaut prudent choisi en §5.
La mise à jour d'un fichier déjà déposé est le sujet de **F-96**, pas celui-ci.

---

## Hors périmètre

- **Lire la machine** : aucun appel runner ici (SF-95-02).
- **Brancher quoi que ce soit sur F-50**, et l'écran (SF-95-03).
- Le `git status --porcelain` des dépôts clients (SF-95-02) : ici, seuls l'identifiant, l'énoncé et
  le geste de la règle existent.
- Les contrôles du prompt d'origine qui supposent l'ossature **personnelle** de l'auteur
  (`~/dev` limité à `repos/` et `infra/`, `~/poste/`, `~/methodo/`, le site) : hors périmètre de
  toute la feature, comme le cadrage l'a tranché.
- Les **cinq scripts** : un paquet porte des identifiants de contrôles, jamais du code (F-50).
