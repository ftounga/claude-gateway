# Mini-spec — F-98 / SF-98-05 — Les textes, la carte au retour en ligne, le téléphone

## Identifiant

`F-98 / SF-98-05`

## Feature parente

`F-98` — La Forge, refondue (cadrage : `CADRAGE-F-98-la-forge-refondue.md` §5 et « Les règles de
détail » — téléphone)

## Statut

`done`

## Date de création

2026-09-13

## Branche Git

`feat/SF-98-05-textes-retour-en-ligne`

---

## Objectif

Faire dire à la Forge **ce qu'on voit et ce qu'on peut faire** plutôt que sa mécanique, **relire la
carte d'un poste une fois** quand il repasse en ligne, et servir le **téléphone** par une liste
plein écran puis un détail avec retour, sur la même URL.

---

## Comportement attendu

### Cas nominal

1. **Textes réécrits** (cadrage §5) :
   | Aujourd'hui | Demain |
   |---|---|
   | « La carte n'a pas été lue : lancez le runner sur la machine, puis Rafraîchir. » | « **Poste hors ligne : la carte sera lue à la prochaine connexion.** » — rien à cliquer |
   | « Le terminal du poste s'ouvre à la racine, là où vit la carte : c'est de là qu'on l'écrit. » | En tête de l'onglet Carte : « **Ce que vous savez de l'infrastructure de ce client. Chaque projet l'enrichit.** » et un bouton **« Écrire dans la carte »** qui ouvre le terminal du poste |
   | « en ligne · vu il y a 12 s » (colonne) | « **En ligne · vu il y a 12 s** » / « **Hors ligne · vu il y a 18 min** » / « **Jamais connecté** » |
2. **Relecture de la carte au retour en ligne** : la règle A1 de F-72 / SF-72-03 tient (la carte n'est
   jamais relue par le sondage) ; **un seul** déclencheur s'ajoute — un poste vu **hors ligne** à une
   lecture de la vue et **en ligne** à la suivante voit sa carte (et son intégrité, lue avec elle,
   F-95) **relue une fois**. L'état « en ligne » est celui de `HostPresenceService` (F-97) : un refus
   reçu dans un terminal puis un battement revenu déclenchent aussi la relecture. Aucun sondage de la
   machine n'est ajouté.
3. **Téléphone (< 820 px)** :
   - `/forge` : la **colonne plein écran**, le détail masqué ;
   - `/forge/<id>` : le **détail plein écran**, la colonne masquée, et un bouton **« ← Postes »** en
     tête qui ramène à `/forge` ;
   - au-dessus de 820 px, rien ne change (colonne et détail côte à côte, pas de bouton retour).

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Premier chargement (aucune lecture précédente) | aucune relecture supplémentaire : la carte des postes en ligne est lue comme avant | — |
| Poste qui reste hors ligne d'une lecture à l'autre | aucune lecture de carte | — |
| Poste qui reste en ligne | aucune relecture (A1) | — |
| Relecture de la carte en échec | silencieuse, comme toute lecture de carte (F-92) | 5xx |
| « Écrire dans la carte » sur un poste dont le terminal ne s'ouvre pas | message d'échec existant du terminal du poste, pas de navigation | 4xx/5xx |
| Poste « Hébergé » | ni onglet Carte, ni bouton « Écrire dans la carte » | — |

---

## Critères d'acceptation

- [ ] L'onglet Carte d'un poste hors ligne dit « Poste hors ligne : la carte sera lue à la prochaine
      connexion. » et ne propose aucun geste.
- [ ] L'onglet Carte d'un poste en ligne commence par « Ce que vous savez de l'infrastructure de ce
      client. Chaque projet l'enrichit. » et « Écrire dans la carte » ouvre le terminal du poste.
- [ ] La colonne écrit « En ligne · vu il y a … », « Hors ligne · vu il y a … », « Jamais connecté ».
- [ ] Hors ligne → en ligne entre deux lectures : **une** lecture de carte de plus ; en ligne → en
      ligne : aucune ; hors ligne → hors ligne : aucune.
- [ ] Sur `/forge/<id>`, le détail porte la classe d'ouverture et le bouton « ← Postes » mène à
      `/forge` ; les règles CSS < 820 px masquent la colonne ou le détail selon l'URL.
- [ ] Aucune couleur hors `DESIGN_SYSTEM.md`.

---

## Périmètre

### Hors scope (explicite)

- Toute relecture périodique de la carte ou des dossiers (règle A1 inchangée).
- La relecture des **dossiers** de la racine au retour en ligne (le cadrage ne l'ajoute que pour la
  carte ; un poste jamais lu est de toute façon lu à sa première lecture en ligne).
- La réécriture des textes hors de la Forge (terminal, dialogue d'appairage).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| état « en ligne » précédent d'un poste | inconnu | connu à partir de la deuxième lecture de la vue |

---

## Contraintes de validation

Aucune saisie ni paramètre nouveau.

---

## Technique

### Endpoint(s)

Aucun. `GET /api/governance/hosts/{ref}/map` et `/integrite` (existants) relus au retour en ligne.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants Angular

- `PostesComponent` — libellés de l'onglet Carte, « Écrire dans la carte », relecture au retour en
  ligne dans `load()`, bouton retour et classe d'ouverture du détail, règles < 820 px.
- `ForgeRailComponent` — statut daté capitalisé.

### Préoccupations transversales

- **Navigation / routing : oui.** Composants impactés : `PostesComponent` (bouton « ← Postes » vers
  `/forge`, classe dépendant de `hostRef`) ; routes `/forge` et `/forge/:hostRef` inchangées
  (SF-98-01), aucun autre chemin touché.
- Auth / Principal, tenant, plans / limites : non.

---

## Plan de test

### Tests unitaires (frontend)

- [ ] `PostesComponent` — texte hors ligne sans « Rafraîchir » ; phrase d'en-tête de l'onglet Carte ;
      « Écrire dans la carte » appelle l'ouverture du terminal du poste ; relecture de carte :
      hors ligne → en ligne (1 appel de plus), en ligne → en ligne (0), hors ligne → hors ligne (0),
      refus reçu puis battement revenu (1) ; classe `forge-split--detail-open` et bouton « ← Postes ».
- [ ] `ForgeRailComponent` — libellés capitalisés (trois formes).

### Isolation workspace

- [x] Non applicable — aucune donnée nouvelle.

---

## Dépendances

### Subfeatures bloquantes

- SF-98-02 — `done` (onglet Carte).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **La transition est lue sur l'état partagé** (F-97) plutôt que sur le seul champ `connected` de la
  réponse : un refus reçu ailleurs rend le poste hors ligne pour l'écran, et son retour doit relire
  la carte comme n'importe quel retour.
- **Le téléphone se vérifie par classes et règles CSS** : la fenêtre des tests est fixée à 1440 px
  (SF-98-03) ; le comportement < 820 px est déclaratif et porté par la classe testée.
