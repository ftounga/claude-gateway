# Mini-spec — [F-125 / SF-125-02] Suivi de promotion robuste côté serveur, marqueur non fragile

## Identifiant

`F-125 / SF-125-02`

## Feature parente

`F-125` — La tenue de la carte du poste : silencieuse, robuste, jamais dans la réponse

## Statut

`ready`

## Date de création

2026-09-17

## Branche Git

`feat/SF-125-02-marqueur-robuste`

---

## Objectif

> En une phrase : le suivi de la dette de promotion et des destinations ne casse plus sur un libellé mal
> formaté (virgule, troncature) — il tolère la ponctuation et s'appuie sur les **écritures de fichiers
> réelles** de l'agent plutôt que sur un texte libre que le modèle doit formater parfaitement.

---

## Comportement attendu

### Cas nominal

1. **Marqueur tolérant à la virgule** (`FinDeTourMarker.readPromus`) : dans le champ `promu=`, une
   virgule à l'intérieur d'un libellé ne crée plus une fausse promotion sans destination. Un segment
   séparé par virgule qui ne contient **pas** de flèche est **raccroché** au libellé de la promotion
   précédente (celle qui porte la flèche). Ainsi `promu=le compte, avec sa virgule -> plateformes.md`
   se lit comme **une** promotion `élément = « le compte, avec sa virgule »`, `destination =
   « plateformes.md »` — plus jamais deux fragments dont l'un « isolé devant la destination ».
   Plusieurs vraies promotions (`a -> x.md, b -> y.md`) restent bien deux promotions.
2. **Suivi appuyé sur les écritures réelles** (`PromotionDetteBloquanteControl`) : quand une promotion
   est déclarée sans destination lisible (libellé tronqué, flèche perdue) **mais que le tour a écrit
   dans un fichier de la carte du poste** (`context.writtenPaths()` recoupe, par nom de fichier, la
   carte `GovernanceMapDestinations.pathsForProject`), la promotion est considérée **placée** : le
   contrôle ne bloque pas pour une destination manquante ni pour une destination « étrangère ». La
   preuve est l'écriture réelle, pas le libellé.
3. **Guidage vers un format robuste** : le message correctif du marqueur invite à des **libellés
   courts, sans ponctuation interne**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| `promu=` avec une virgule dans le libellé et une seule flèche | **Une** promotion, destination correcte — plus de fragment sans destination |
| Promotion sans destination lisible ET aucune écriture réelle dans la carte | Comportement inchangé : le contrôle demande où (refus existant), tolérance non déclenchée |
| Promotion sans destination MAIS le tour a écrit `plateformes.md` (fichier de la carte) | **Pas de blocage** : l'écriture réelle vaut destination |
| Carte non listable (vide) | Aucune écriture ne peut recouper la carte → tolérance non déclenchée, comportement générique inchangé |
| `writtenPaths` vide | Tolérance non déclenchée, comportement inchangé |

---

## Critères d'acceptation

- [ ] Un libellé `promu=` contenant une virgule (une seule flèche) donne **une** promotion avec la
      bonne destination (`FinDeTourMarker`).
- [ ] Plusieurs promotions séparées par virgule (chacune avec flèche) restent distinctes.
- [ ] Une promotion sans destination lisible **n'est plus bloquée** quand le tour a écrit dans un
      fichier de la carte du poste (recoupement par nom de fichier).
- [ ] Sans écriture réelle recoupant la carte, le refus « dis où » existant est préservé.
- [ ] La dette (`dette>0`) reste comptée depuis le marqueur (le desserrage du blocage est SF-125-04).
- [ ] Le marqueur `FORME` / le message correctif mentionnent le format robuste (libellé court).

## Plan de test minimal

- **Unitaires** :
  - `FinDeTourMarkerTest` : virgule dans un libellé → une promotion + destination ; deux vraies
    promotions restent deux ; non-régression des cas existants (flèches, « aucune »…).
  - `EndOfTurnControlsTest` : promotion sans destination + `writtenPaths` recoupant la carte → non
    bloqué ; promotion sans destination + aucune écriture → toujours bloqué ; destination étrangère +
    écriture réelle → non bloqué.
- **Isolation utilisateur** : le contrôle lit la carte du seul couple `(userId, workspaceId)` du tour
  (déjà couvert par `theMapIsReadForTheCurrentProjectOnly`, conservé) ; `writtenPaths` sont ceux du
  tour courant. Aucun nouvel accès données transverse.

## Tables / endpoints / composants impactés

- **Backend** : `FinDeTourMarker` (parsing `readPromus` tolérant), `PromotionDetteBloquanteControl`
  (tolérance sur écritures réelles). `GovernanceMapDestinations`/marqueur `FORME` : ajout de guidage.
  Aucune table, aucune migration, aucun endpoint, aucun frontend.

## Analyse transversale (préoccupations)

- **Auth / Principal** : non concernée.
- **Contexte tenant** : la carte lue reste celle de `(userId, workspaceId)` ; `writtenPaths` du tour
  courant. Composants qui résolvent la carte : `GovernanceMapDestinations` (inchangé). Aucun nouveau
  résolveur de tenant.
- **Plans / limites** : non concernée.
- **Navigation / routing** : non concernée.

## Hors périmètre (explicite)

- Rendre la dette **non bloquante** (ne plus renvoyer l'agent au travail) → **SF-125-04**.
- Le fichier de carte non déclaré → **SF-125-03**.
- La consigne « zéro plomberie » et le strip d'affichage → **SF-125-01** (livrée).
- On ne change pas ce qui est promu (le fond), seulement la robustesse du suivi.
