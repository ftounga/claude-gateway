# Mini-spec — F-134 / SF-134-01 — Le préfixe cesse de muter

## Identifiant
`F-134 / SF-134-01`

## Feature parente
`F-134` — Le cache qui ne prend pas

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-134-01-le-prefixe-cesse-de-muter`

---

## Objectif

Faire que la coupure entre « tours rejoués avec leurs traces » et « tours rejoués en texte seul »
**cesse de se déplacer à chaque tour**, pour que le cache de prompt puisse enfin se relire.

---

## Le défaut

`firstTracedIndex` (`AtelierChatService:1667`) compte les tours **depuis la fin** : il rend l'index
du 12ᵉ tour assistant en partant du dernier.

À chaque nouveau tour, cet index **avance d'un cran**. Le tour qui était tracé cesse de l'être et
change de forme — or il se situe **au début** du préfixe envoyé au fournisseur. L'API cachant un
**préfixe**, tout ce qui suit la mutation doit être réécrit.

**Le préfixe mute donc à chaque tour, par construction.** Mesuré en production : 14 à 16 % du
contexte relu au-delà du 12ᵉ tour, contre 90 % en deçà — et 98 % du coût d'un tour en écriture de
cache.

---

## La correction : une coupure par paliers

La coupure est calculée **depuis le début** du fil, et ne se déplace que **tous les
`replayedTraceTurns` tours** :

```
coupure = max(0, ⌊(N − F) / F⌋ × F)        N = tours assistants, F = fenêtre (12)
```

| Tours dans le fil | Coupure | **Tours tracés** | Le préfixe mute ? |
|---|---|---|---|
| 10 → 23 | 0 | 10 → 23 | **non** |
| **24** | 12 | 12 | **oui** |
| 25 → 35 | 12 | 13 → 23 | **non** |
| **36** | 24 | 12 | **oui** |

**Une mutation tous les douze tours, au lieu d'une par tour.**

### Pourquoi cela ne peut pas dégrader la qualité

C'est le point décisif au regard de la contrainte de F-130 (*« certitude d'avoir toujours le
meilleur résultat »*) :

> Le nombre de tours rejoués **avec** leurs traces passe de **exactement 12** à **entre 12 et 23**.
> Il n'est **jamais inférieur** à ce qu'il est aujourd'hui.

Le modèle voit donc **autant ou plus** de preuves qu'avant, jamais moins. Le couplage
affirmation ↔ preuve que F-119 a établi est préservé, et même renforcé la plupart du temps.

### Pourquoi cela coûte moins cher malgré plus de contexte

Ces tours supplémentaires sont **relus** du cache, à 0,50 $/M, au lieu d'être **réécrits** à
10 $/M — vingt fois moins cher. Plus de contexte, moins cher : c'est tout l'intérêt d'un cache qui
prend.

### L'option écartée

**Ne jamais couper** (tous les tours tracés à vie) donnerait un préfixe parfaitement stable, mais
laisserait la taille du fil croître sans borne jusqu'à la compaction — laquelle **réécrit**
l'historique et invaliderait le cache à chaque déclenchement. Les tours mesurés pesant déjà
200 000 à 400 000 tokens, la compaction se déclencherait souvent : le remède serait pire que le mal.

Les paliers bornent le fil à **deux fois** la fenêtre, tout en stabilisant le préfixe onze fois
sur douze.

---

## Comportement attendu

### Cas nominal
1. Un fil compte N tours assistants.
2. La coupure est calculée par paliers, depuis le début.
3. Les tours au-delà de la coupure sont rejoués **avec** leurs traces d'outils ; les précédents en
   texte seul — exactement comme aujourd'hui.
4. Entre deux tours consécutifs, la coupure est **identique** onze fois sur douze.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Fil plus court que la fenêtre | coupure à 0 : **tout** est tracé, comme aujourd'hui |
| Fenêtre configurée à 0 ou négative | retombe sur le comportement d'aujourd'hui, jamais de division par zéro |
| Trajectoire illisible | le tour retombe en texte seul, inchangé |
| Aucun tour assistant | coupure à 0, aucune exception |

---

## Critères d'acceptation

- [ ] Pour un fil de 10 à 23 tours, la coupure vaut **0** : tous les tours sont tracés.
- [ ] La coupure ne change **qu'aux multiples de la fenêtre** (24, 36, 48…).
- [ ] Entre deux tours consécutifs hors palier, la liste des messages rejoués est **identique
      octet pour octet** sur son préfixe — c'est la propriété qui fait tenir le cache.
- [ ] Le nombre de tours tracés est **toujours ≥ `replayedTraceTurns`**, jamais moins qu'aujourd'hui.
- [ ] Une fenêtre à 0 ou négative ne lève aucune exception.
- [ ] Le contenu d'un tour rejoué est **inchangé** : mêmes blocs, même ordre, mêmes traces.
- [ ] Le résumé de compaction reste en tête, inchangé.

---

## Périmètre

### Hors scope (explicite)
- **Réduire la fenêtre de rejeu** : ce serait échanger de la justesse contre de l'argent, ce que la
  contrainte de F-130 interdit.
- Le seuil de compaction (OQ-22 : à mesurer **après** cette subfeature, pas à régler d'avance).
- Le marqueur de cache sur `tools` (SF-134-02).
- L'affichage de la part relue (SF-134-03).

---

## Technique

| Classe | Changement |
|---|---|
| `AtelierChatService.firstTracedIndex` | coupure par paliers, calculée depuis le début |

Aucun endpoint, aucune table, aucune migration, aucun changement de configuration.

---

## Plan de test

### Tests unitaires
- [ ] Fil de 10, 12, 23 tours ⇒ coupure 0 (tout tracé).
- [ ] Fil de 24 tours ⇒ coupure au 12ᵉ ; de 35 ⇒ toujours au 12ᵉ ; de 36 ⇒ au 24ᵉ.
- [ ] **La coupure est stable entre deux tours consécutifs hors palier** (le test central).
- [ ] Le nombre de tours tracés est toujours ≥ à la fenêtre.
- [ ] Fenêtre à 0, négative, fil vide, fil sans tour assistant : aucune exception.
- [ ] Le contenu rejoué est identique à celui d'avant, à coupure égale (non-régression).

### Tests d'intégration
- [ ] Un fil de plus de douze tours rejoue bien ses traces ; deux tours consécutifs produisent le
      **même préfixe**.
- [ ] Non-régression F-119 : les traces restent présentes, en nombre au moins égal.

### Isolation utilisateur
- [x] Non applicable — aucune lecture de données nouvelle ; le rejeu filtre déjà `user_id`.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Plans / limites** | **oui** | Le volume rejoué augmente (12→23 tours tracés) : vérifier que le plafond de contexte (`AtelierCompactionService`, seuil 200 000) et le plafond de dépense par message (F-36) encaissent. Le surcoût est en **lecture de cache**, vingt fois moins chère |
| Auth / Principal | non | aucun changement |
| Contexte tenant | non | le rejeu filtre déjà `user_id` |
| Navigation / routing | non | aucun écran |

---

## Estimation

**0,5 jour.** Une fonction, ses tests, et la vérification de non-régression.
