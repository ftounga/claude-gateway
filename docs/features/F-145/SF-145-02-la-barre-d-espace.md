# Mini-spec — F-145 / SF-145-02 — La barre d'espace maintenue bascule en dictée

## Identifiant
`F-145 / SF-145-02` — feature parente `F-145`

## Objectif
Dicter en **maintenant** la barre d'espace — l'appui court continuant d'écrire un espace, comme
partout.

## Ce que le PO a précisé
> *« Sur Claude Code, la barre d'espace écrit aussi un espace, mais quand tu la maintiens ça bascule
> en enregistrement de la dictée. Je voulais le même comportement. »*

Deux conceptions avaient été essayées avant celle-ci, et aucune ne correspondait :

| Essai | Pourquoi ce n'était pas ça |
|---|---|
| SF-145-01 : `Ctrl/⌘ + Espace` | un geste de plus à apprendre, au lieu de la touche demandée |
| Première version de SF-145-02 : Espace **hors champ** | on dicte justement **depuis** le champ ; hors champ, la touche ne sert à rien |

La bonne lecture est la **durée** : un appui écrit, un **maintien** bascule.

## Comportement attendu
1. Un appui **court** sur Espace écrit un espace — rien ne change, nulle part.
2. Un **maintien** au-delà du seuil (**500 ms**) bascule en enregistrement, où que soit le curseur.
3. Au basculement, les espaces que la répétition du clavier a insérés pendant le maintien sont
   **retirés** : le brouillon retrouve exactement ce qu'il était avant l'appui.
4. Le relâchement **arrête** et transcrit ; le texte rejoint le brouillon.
5. Relâcher **avant** le seuil ne déclenche rien : l'espace écrit reste écrit.
6. `Ctrl/⌘ + Espace` reste un geste **immédiat**, sans attente — pour qui préfère l'explicite.

| Cas d'erreur | Comportement |
|---|---|
| Focus sur un **bouton** ou un lien | rien : l'espace y actionne l'élément, et on ne vole pas une touche au clavier |
| Maintien puis départ du focus | l'enregistrement s'arrête comme à un relâchement |
| Navigateur sans micro | aucun raccourci actif |

## Critères d'acceptation
- [ ] Un appui **court** écrit un espace et ne déclenche **rien**.
- [ ] Un **maintien** de 500 ms bascule en enregistrement, **y compris dans le champ de saisie**.
- [ ] Au basculement, les espaces insérés par la répétition sont **retirés** du brouillon.
- [ ] Le relâchement transcrit, et le texte rejoint le brouillon.
- [ ] Le focus sur un **bouton** ne déclenche rien.
- [ ] `Ctrl/⌘ + Espace` déclenche **immédiatement**, sans attendre le seuil.
- [ ] Un maintien ne lance **qu'un** enregistrement, malgré la répétition du clavier.

## Hors scope
Rendre le raccourci configurable · la dictée hors du terminal.

## Technique
| Fichier | Changement |
|---|---|
| `dictation-button.component.ts` | maintien détecté par minuterie, nettoyage des espaces insérés |
| `atelier-terminal.component` | passe le brouillon au composant, pour qu'il puisse le restaurer |

Aucune route, aucune table, aucun appel réseau nouveau.

## Plan de test
- [ ] Appui court ⇒ rien ; maintien ⇒ enregistrement.
- [ ] Les espaces insérés pendant le maintien sont retirés.
- [ ] Bouton ⇒ rien ; `Ctrl + Espace` ⇒ immédiat.
- [ ] Répétition ⇒ un seul démarrage.

## Préoccupations transversales
| Préoccupation | Cochée | Composants |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | — |
| Plans / limites | non | — |
| **Navigation / routing** | **non** | aucun changement d'écran ; seule la règle du raccourci change |
