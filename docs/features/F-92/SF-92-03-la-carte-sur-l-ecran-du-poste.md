# Mini-spec — F-92 / SF-92-03 — La carte sur l'écran du poste

## Identifiant

`F-92 / SF-92-03`

## Feature parente

`F-92` — La carte du poste

## Statut

`done` — mergée le 2026-09-12 (PR #456)

## Date de création

2026-09-12

## Branche Git

`feat/SF-92-03-la-carte-a-l-ecran`

---

## Objectif

Montrer la carte **sur la carte du poste** (`/forge`) — ce que la machine sait, sans ouvrir un
terminal —, et **dire enfin à quoi sert le terminal du poste** : il s'ouvre à la racine, là où la
carte vit.

---

## Comportement attendu

### La section « Carte du poste », sur chaque carte de poste réel

1. Sous les projets et les dossiers non ouverts, chaque **poste réel** porte une section
   **« Carte du poste »**.
2. Elle affiche en tête ce que la machine sait, en une ligne :
   **« N faits · M / 6 fichiers »** — et, quand `N = 0`, la phrase qui explique à quoi ça sert
   plutôt qu'un zéro sec.
3. Puis un fichier par ligne : son nom, son titre, et son nombre de faits. Un fichier **rempli** se
   distingue d'un fichier **encore vide** sans aucune couleur nouvelle : le gris « inactif » du §5 et
   le retrait, exactement comme les dossiers non ouverts (F-72 / SF-72-03).
4. **Cliquer un fichier l'ouvre** : un dialogue montre ses sections (avec le nombre de faits de
   chacune) puis son **contenu exact**. C'est là qu'on relit les pièges d'un client sans ouvrir un
   terminal.
5. Sous la liste, la phrase qui relie la carte au terminal :
   *« Le terminal du poste s'ouvre à la racine, là où vit la carte : c'est de là qu'on l'écrit. »*

### Quand la lecture a lieu — et quand elle n'a pas lieu

Exactement la règle de F-72 / SF-72-03 (arbitrage A1), pour la même raison :

| Moment | Lecture ? |
|---|---|
| Chargement de l'écran | **oui**, une fois par poste **connecté** |
| Sondage de 15 s | **non**, jamais |
| Bouton « Rafraîchir » | **oui** — c'est le geste par lequel on dit « j'ai lancé le runner » |
| Après une activation / un « appliquer » ailleurs | non (l'écran de gouvernance est un autre écran) |
| Poste **non connecté** | **aucun appel** ; la section le dit et propose son geste |
| Poste **« Hébergé »** | **pas de section** — ce n'est pas une machine (même règle que F-72) |

### Le terminal du poste dit enfin à quoi il sert

Le bouton **« Terminal du poste »** (F-74 / SF-74-02) est inchangé — même libellé, même icône, même
destination. Ce qui change est ce que l'écran en **dit** : son infobulle nomme la carte, et la
section ci-dessus l'explique en toutes lettres. Aujourd'hui, rien n'indique à quoi ce terminal sert.

### L'annonce avant activation montre aussi la racine

Le dialogue d'annonce (`deposit-preview-dialog`, F-75 / SF-75-02) doit **distinguer la carte des
gabarits**, sans quoi il ment : un fichier `MAP` n'a aucune entrée dans les dossiers, et le calcul
existant le rendrait « indéterminé dans N dossiers ». Il gagne donc :

- une ligne par fichier de carte, avec **le sort à la racine** (`sera créé` / `déjà présent — laissé
  tel quel` / `indéterminé`) ;
- la phrase qui situe : *« la carte se pose à la racine du poste, une seule fois, à côté des
  dossiers de projets »* ;
- le repli quand le poste n'a pas de racine (« Hébergé ») ou qu'elle n'a pas pu être lue.

### Cas d'erreur — chaque message porte son action corrective

| Situation | Ce que l'écran montre |
|---|---|
| Poste **non connecté** | « La carte n'a pas été lue : lancez le runner sur la machine, puis Rafraîchir. » — **aucun appel** |
| Relevé en **échec réseau** | La section disparaît **silencieusement** pour ce poste, comme les dossiers non ouverts : c'est un confort, et un rouge ici enverrait chercher au mauvais endroit. La carte du poste, elle, reste exacte |
| `governed = false` | « Aucune gouvernance active sur ce poste » + un lien vers l'écran Gouvernance |
| `readable = false` | Le message du serveur, repris **tel quel** |
| Un fichier **absent** | La ligne le dit, avec le geste du serveur (« Appliquer ») |
| Lecture d'un fichier **en échec** | Le dialogue affiche l'échec et un bouton « Réessayer » ; rien n'est inventé |
| 403 (accès Forge) | Inchangé : la vue entière est déjà refusée en amont |

---

## Critères d'acceptation

- [ ] Chaque carte de poste **réel** porte la section « Carte du poste » ; la carte « Hébergé » ne
      la porte pas.
- [ ] La section affiche le **total des faits** et le nombre de fichiers présents sur attendus.
- [ ] Chaque fichier est une ligne cliquable portant son titre et son nombre de faits.
- [ ] Le clic ouvre un dialogue montrant **les sections et le contenu exact** du fichier.
- [ ] Un poste **non connecté** n'entraîne **aucun appel** et affiche son geste.
- [ ] Le relevé est lu **une fois par chargement**, **jamais** par le sondage de 15 s, et **relu**
      sur « Rafraîchir ».
- [ ] Un échec de lecture fait **disparaître** la section pour ce poste, sans rouge.
- [ ] La section relie explicitement la carte au **terminal du poste**, et l'infobulle du bouton
      « Terminal du poste » nomme la carte.
- [ ] Le dialogue d'annonce distingue **la carte** (racine) des **gabarits et skills** (dossiers), et
      ne dit plus « indéterminé » pour un fichier de carte.
- [ ] **Aucune couleur nouvelle** : gris « inactif » du §5 et retrait ; aucun ton d'identité (§9),
      aucune pastille de mission (§10), aucun signe de vie (§11).
- [ ] Espacements multiples de 4 px ; polices Space Grotesk / Inter / JetBrains Mono uniquement.
- [ ] Aucun `window.alert` / `confirm` / `prompt` ; dialogue via `MatDialog`.
- [ ] Tests frontend verts, build Angular vert.

---

## Plan de test minimal

### Unitaires (spec Angular)

- `governance.service.spec` — `getMap` et `readMapFile` frappent les bonnes URL, `path` en paramètre
  de requête.
- `postes.component.spec` :
  - la section n'apparaît **pas** sur le poste « Hébergé » ;
  - un poste **non connecté** ne déclenche **aucun** appel de carte ;
  - un poste connecté déclenche **un** appel, et le sondage ne le rejoue pas ;
  - « Rafraîchir » le rejoue ;
  - un échec fait disparaître la section, sans message d'erreur ;
  - les totaux affichés sont ceux du serveur.
- `map-file-dialog.component.spec` — affiche les sections et le contenu ; un échec affiche
  « Réessayer » et ne montre aucun contenu inventé.
- `deposit-preview-dialog.component.spec` — un fichier `MAP` prend le sort de la **racine**, pas
  celui des dossiers ; « Hébergé » affiche le repli.

### Intégration

Sans objet côté frontend : les endpoints sont couverts par `GovernanceMapApiIntegrationTest`
(SF-92-02).

### Isolation utilisateur

Aucune URL ne porte d'identifiant d'utilisateur : l'isolation est entièrement portée par la gateway
à partir du JWT (règle de `GovernanceService`, inchangée). Vérifié par relecture du service.

---

## Tables / endpoints / composants impactés

### Tables

Aucune.

### Endpoints

Aucun nouveau. Consommation de `GET /governance/hosts/{ref}/map` et
`GET /governance/hosts/{ref}/map/file` (SF-92-02).

### Composants

| Composant | Changement |
|---|---|
| `core/models/governance.models.ts` | `MAP` dans `GovernanceFileKind` ; `GovernanceRootDepositPlan` ; `GovernanceMap*` |
| `core/services/governance.service.ts` | `getMap`, `readMapFile` |
| `postes/postes.component.*` | la section « Carte du poste », sa lecture, son infobulle de terminal |
| `postes/map-file-dialog/` | **nouveau** — sections + contenu d'un fichier de carte |
| `governance/deposit-preview-dialog/` | la carte annoncée à part des gabarits |

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | Aucune URL ne porte d'identifiant ; la gateway tranche à partir du JWT |
| Plans / limites | non | — |
| **Navigation / routing** | **oui** | Aucune route ajoutée. Chemins vérifiés : `/forge` (cet écran, enrichi), le dialogue (pas une route), `/atelier/{id}` (ouverture du terminal du poste, **inchangée**), `/governance` (lien depuis le message « aucune gouvernance active » — route existante, montée dans `app.routes.ts`). Aucun garde modifié, aucune redirection ajoutée |

---

## Périmètre

### Hors scope (explicite)

- **Écrire** la carte depuis l'écran : elle s'écrit au terminal du poste. Un éditeur ici ferait de
  la Forge un traitement de texte, et personne ne l'a demandé.
- Afficher la carte ailleurs que sur `/forge` (l'écran Gouvernance garde son rôle : activer).
- Mettre la carte en cache entre deux chargements.
- Montrer **ce que la carte a gagné** après un tour : c'est F-93.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|---|---|---|
| Relevé d'un poste | **non lu** | l'absence d'entrée n'est pas une carte vide |
| Section sur « Hébergé » | **absente** | ce n'est pas une machine |
| Dialogue de contenu | fermé | il s'ouvre au clic, jamais tout seul |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format | Normalisation |
|---|---|---|---|---|
| `path` envoyé au serveur | oui | 255 | tel quel, repris du relevé | la gateway valide et compare **strictement** |
| contenu affiché | — | 200 000 caractères | texte brut, **jamais interprété comme HTML** | rendu en `<pre>` |
