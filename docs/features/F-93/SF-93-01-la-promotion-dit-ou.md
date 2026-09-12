# Mini-spec — F-93 / SF-93-01 — La promotion dit où

## Identifiant

`F-93 / SF-93-01`

## Feature parente

`F-93` — La promotion a une destination

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-93-01-la-promotion-dit-ou`

---

## Objectif

Donner à la promotion **la destination qui lui manquait** : un élément durable se range dans la
**carte du poste** (F-92), il se trace **coché** dans le `STATE.md` du projet **en disant où**, et le
compteur de dette existant refuse de clore tant que la destination n'est pas dite.

---

## Le défaut, tel qu'il se constate aujourd'hui

F-52 a livré deux contrôles de fin de tour. Ils fonctionnent — et ils envoient au mauvais endroit :

| Ce qui est dit au modèle aujourd'hui | Ce qui devrait l'être |
|---|---|
| « ajoute-le à la carte du projet (`PLAN-ACTION.md`) » | un **cluster**, un **VPN**, un **bastion** n'appartiennent pas au projet : ils appartiennent au **poste**, et ils doivent lui survivre |
| « la carte du projet garde 3 cases non cochées » | **où** promouvoir ? le message ne le dit pas, donc il ne se corrige pas |
| rien ne demande **où** un élément a été promu | la trace `- [x] … -> promu dans acces.md` est ce qui rend la promotion **vérifiable** |

F-92 a créé la destination. Tant que les contrôles ne la nomment pas, elle reste un jeu de fichiers
que personne ne remplit — et *« à chaque projet, la connaissance de l'infra augmente »* reste une
phrase.

---

## Comportement attendu

### 1. Le marqueur de fin de tour gagne un champ : `promu`

La forme devient — et l'ancienne reste lisible, sans quoi tout poste déjà activé casserait :

```
<!-- fin-de-tour: promotion=aucune; promu=aucune; dette=0 -->
```

| Champ | Ce qu'il porte |
|---|---|
| `promotion` | ce que le tour a fait apparaître de durable et qui **n'est pas encore** dans une carte — le juge refuse de clore tant qu'il n'est pas vide (inchangé) |
| `promu` | ce que le tour **a effectivement promu**, **et où** : `promu=cluster atlas -> plateformes.md, VPN client -> acces.md` |
| `dette` | le nombre de cases `- [ ]` restées non cochées dans les fichiers **du projet** (inchangé) |

`promu` absent vaut `aucune` : un paquet publié avant cette subfeature continue de fonctionner.

### 2. `PromotionDetteBloquanteControl` est **complété**, pas doublé

Le compteur existe ; ce qui lui manquait est la destination. Il la gagne, dans cet ordre — **le
premier refus l'emporte**, donc l'ordre est le message :

| Situation | Verdict | Ce que le refus dit de faire |
|---|---|---|
| un `promu` sans destination (`promu=cluster atlas`) | **bloque** | « dis où : `promu=cluster atlas -> plateformes.md` », avec la liste des fichiers de carte réels du poste |
| une destination inconnue de la carte (`-> notes.md`) | **bloque** | nomme les fichiers **réellement** déposés sur ce poste |
| `dette > 0` | **bloque** | « traite-les, ou retire les lignes sans objet, puis conclus avec `dette=0` » — et **nomme la destination** |
| marqueur absent / illisible | **passe** | réclamer la forme est le travail du juge ; deux contrôles qui demandent la même chose ne rendent qu'une correction |

**Les destinations sont réelles, pas écrites en dur.** Elles sont lues sur le poste du projet en
cours : fichiers de genre `MAP` des paquets **actifs** (F-92 / SF-92-01). Une lecture en base,
jamais un appel au runner — un contrôle de fin de tour ne paie pas un aller-retour réseau.

**Si elles ne se résolvent pas** (poste sans machine, rien d'activé, projet introuvable), le message
reste correct et générique : *« la carte du poste — les fichiers `.md` à la racine »*. Un contrôle ne
tombe jamais pour une carte qu'il n'a pas pu lister.

### 3. `JugeFinDeTourControl` envoie à la bonne carte

Son refus nomme désormais **les deux destinations et leur critère de choix** :

> un élément d'**infrastructure** (cluster, VPN, serveur, storage, bastion, réseau, endpoint,
> contact, convention) va dans la **carte du poste** ; une décision propre au projet va dans
> `PLAN-ACTION.md`.

### 4. Le `STATE.md` livré porte la trace

Une section **« Promotions »** est ajoutée au gabarit, avec sa forme exacte :

```
- [ ] cluster « atlas » (10.0.4.0/24)
- [x] cluster « atlas » (10.0.4.0/24) -> promu dans plateformes.md
```

Une case `- [ ]` restante **est** la dette, et c'est ce que `dette=` compte.

### 5. Les trois règles de clonage entrent dans le paquet, avec un identifiant

Écrites dans `regles.md` **avec leur action corrective**, et chacune porte un **identifiant stable**
pour que F-95 s'y branche mécaniquement :

| Identifiant | Règle | Action corrective portée par le message |
|---|---|---|
| `clonage/depot-dans-repos` | un dépôt client se clone dans `repos/` sous la racine, jamais parmi les sujets | « déplace-le : `mv <dossier> repos/<dossier>` » |
| `clonage/projet-sans-git` | un projet n'est jamais un dépôt git | « ce dossier porte un `.git/` : c'est un dépôt, déplace-le dans `repos/` ; un sujet est un dossier de travail » |
| `clonage/note-hors-depot` | aucune note personnelle non versionnée à la racine d'un dépôt client | « déplace ce `.md` dans la carte du poste — il ne doit pas partir dans un dépôt qu'on ne possède pas » |

**Le seam pour F-95** : les trois règles sont déclarées **dans le code** (`GovernanceHostRule`), avec
identifiant, énoncé et action corrective. `regles.md` les cite par leur identifiant, et le semeur
**refuse de semer** si l'une d'elles manque du document — un paquet qui annoncerait une règle absente
de son texte serait pire qu'un paquet incomplet. F-95 lira la même énumération.

---

## Cas d'erreur

| # | Cas | Comportement attendu |
|---|---|---|
| E1 | `promu=cluster atlas` (sans `->`) | refus nommant la forme **et** les fichiers de carte du poste |
| E2 | `promu=cluster atlas -> notes.md` | refus : `notes.md` n'est pas de la carte ; les fichiers réels sont nommés |
| E3 | destinations non résolvables (poste sans machine, rien d'activé) | **aucune exception** : message générique, et le contrôle continue de compter la dette |
| E4 | marqueur absent | `PromotionDetteBloquanteControl` **passe** (le juge s'en charge) |
| E5 | `promu` absent du marqueur | lu comme `aucune` — rétro-compatibilité stricte |
| E6 | destination écrite `→` ou `vers` | acceptée : on corrige le modèle sur le fond, pas sur la flèche |
| E7 | la résolution des destinations lève (projet d'un autre utilisateur, poste effacé) | capturée : repli générique, jamais de 500 en fin de tour |

---

## Critères d'acceptation

- [x] `FinDeTourMarker` lit `promu=` en couples `élément -> destination`, `aucune`/absent = vide.
- [x] Un marqueur **sans** `promu` reste lisible et vaut promotion vide (rétro-compatibilité).
- [x] `PromotionDetteBloquanteControl` bloque un `promu` **sans destination**, et le refus donne la
      forme exacte.
- [x] Il bloque une destination **hors carte**, et le refus **nomme** les fichiers réels du poste.
- [x] Il bloque encore `dette > 0`, et son message nomme désormais `STATE.md` et la carte du poste.
- [x] Sans marqueur, il **passe** (aucune régression sur F-52).
- [x] Le refus du juge nomme la **carte du poste** et `PLAN-ACTION.md`, avec le critère de choix.
- [x] Les destinations proviennent des fichiers `MAP` des paquets **actifs sur le poste du projet**,
      lus en base, **sans appel runner**.
- [x] Toute défaillance de résolution rend un message générique, jamais une exception.
- [x] `GovernanceHostRule` déclare les trois règles de clonage (id, énoncé, action corrective).
- [x] `regles.md` cite les trois identifiants ; le semeur refuse de semer si l'un manque.
- [x] Le gabarit `STATE.md` porte la section « Promotions » et sa forme `- [x] … -> promu dans …`.
- [x] Isolation : la résolution des destinations passe par `requireOwned` (projet, puis poste).

---

## Plan de test

### Unitaires

1. `FinDeTourMarkerTest` — `promu` absent, `aucune`, un couple, plusieurs couples, flèche `→`,
   séparateur `vers`, élément sans destination, destination vide.
2. `EndOfTurnControlsTest` — les sept cas d'erreur ci-dessus, plus la non-régression F-52.
3. `GovernanceMapDestinationsTest` — fichiers `MAP` des paquets actifs seulement ; `SKILL` et
   `TEMPLATE` exclus ; paquet dépublié ignoré ; exception capturée.
4. `GovernanceHostRuleTest` — trois règles, identifiants stables, énoncé et correction non vides.

### Intégration

5. `GovernancePackageSeederTest` — `regles.md` citant les trois identifiants sème ; un document
   amputé d'un identifiant **ne sème pas**.
6. `GovernanceSeededPackageIntegrationTest` — le `STATE.md` déposé porte la section « Promotions ».

### Isolation utilisateur

7. La résolution des destinations d'un projet d'autrui ne rend **rien** (et ne lève pas) : le
   contrôle retombe sur le message générique.

---

## Impacts

| Zone | Détail |
|---|---|
| Tables | **aucune** |
| Endpoints | **aucun** |
| Backend | `FinDeTourMarker`, `PromotionDetteBloquanteControl`, `JugeFinDeTourControl`, `GovernanceMapDestinations` (nouveau), `GovernanceHostRule` (nouveau), `GovernancePackageSeeder` |
| Ressources | `governance/savoir-durable/regles.md`, `governance/savoir-durable/STATE.md` |
| Frontend | **aucun** |

## Préoccupations transversales

| Préoccupation | Concernée ? | Composants vérifiés |
|---|---|---|
| Auth / Principal | non | aucun endpoint touché |
| Contexte tenant | **oui** | `GovernanceMapDestinations` résout le poste via `GovernanceHostScope.hostOf(userId, workspaceId)` → `requireOwned` ; les activations sont lues par `user_id` + `host_id` (`GovernanceActivationService.activeOn`) ; le contrôle ne reçoit que le couple `(userId, workspaceId)` déjà vérifié par `GovernanceCheckpointDelegate` |
| Plans / limites | non | aucun quota touché |
| Navigation / routing | non | aucune route |

---

## Contraintes de validation

| Champ | Contrainte | Motif |
|---|---|---|
| `promu` (nombre d'éléments cités dans un refus) | `FinDeTourMarker.MAX_CITED` = 10 | déjà en place pour `promotion` : au-delà, ce n'est plus une action corrective mais un inventaire |
| `promu` (longueur citée) | `FinDeTourMarker.MAX_CITED_CHARS` = 300 | idem |
| destinations nommées dans un refus | 12 au plus | un poste peut porter plusieurs paquets ; un refus qui liste trente fichiers ne se corrige plus |
| identifiant de règle | `^[a-z0-9-]+/[a-z0-9-]+$` | c'est ce que F-95 grep |

---

## Décisions prises en cours de dev

| # | Décision | Motif | Réversible ? |
|---|---|---|---|
| D1 | `GovernanceMapDestinations` lit les **dépôts** (`GovernanceActivationRepository`, `GovernancePackageRepository`, `GovernancePackageFileRepository`) et non les services | Passer par `GovernanceActivationService` fait un **cycle de beans** : registre de contrôles → contrôle → destinations → `GovernanceActivationService` → `GovernanceSelectionService` → `GovernancePackageService` → registre. Le contexte Spring refuse alors de démarrer — constaté sur 645 tests d'intégration. Les lectures nécessaires sont trois requêtes sans logique métier. | oui |
| D2 | `GovernanceMapReadingService` **délègue** sa liste de fichiers attendus au même service | La même liste sert à **rendre** la carte (F-92) et à **nommer la destination** d'une promotion. Deux copies auraient divergé au premier paquet ajouté. | oui |
| D3 | La destination est comparée sur le **nom de fichier**, pas sur le chemin écrit | `acces.md` et `./Acces.MD` désignent le même fichier ; un refus pour un `./` ferait perdre un tour sans rien protéger. | oui |
| D4 | Un test verrouille la **longueur** du texte de règles sous `MAX_RULES_LENGTH` | Le semeur se contente d'un avertissement en cas de dépassement : sans ce test, le catalogue resterait silencieusement vide. Marge actuelle : ~500 caractères sur 8 000. | oui |

---

## Hors périmètre

- **Vérifier mécaniquement** les trois règles de clonage sur la machine : c'est F-95, et cette
  subfeature ne fait que les écrire et les rendre branchables.
- **Écrire** dans la carte à la place du modèle : la promotion reste un geste du tour, pas une
  écriture automatique de la gateway.
- Le **juge indépendant** (second appel) : F-94.
- Ce que la carte a **gagné** à l'écran : SF-93-02 et SF-93-03.
