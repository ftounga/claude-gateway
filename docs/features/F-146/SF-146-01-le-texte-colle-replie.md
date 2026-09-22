# Mini-spec — F-146 / SF-146-01 — Un long texte collé ne noie pas la saisie

## Identifiant
`F-146 / SF-146-01` — feature parente `F-146`

## Objectif
Coller trois cents lignes sans que la zone de saisie devienne illisible — et sans rien perdre du
texte.

## Le défaut
Le champ du terminal tient sur **une ligne**. Y coller un journal, une configuration ou une
transcription y déverse tout : on ne voit plus ce qu'on écrit, on ne peut plus relire sa demande, et
le fil affichera ensuite le pavé en entier.

## Comportement attendu
1. Un collage **long** (plus de 5 lignes, ou plus de 400 caractères) est **replié** : le champ reçoit
   une référence — `[texte collé #1]` — et une puce le récapitule : *« texte collé #1 · 347 lignes »*.
2. Un collage **court** passe normalement dans le champ : on ne replie pas ce qui tient.
3. À l'envoi, chaque référence **encore présente** dans le champ est remplacée par son texte
   intégral. **Le modèle reçoit tout**, l'écran n'a montré qu'une ligne.
4. **Garantie de non-surprise** : une référence **effacée** du champ n'est **pas** envoyée. Rien ne
   part que l'utilisateur ne voie.
5. La puce permet de **retirer** un collage ; sa référence disparaît alors du champ.
6. Plusieurs collages successifs sont numérotés, et restent distincts.

| Cas d'erreur | Comportement |
|---|---|
| Collage d'un fichier ou d'une image | inchangé — c'est le dépôt existant qui s'en charge |
| Collage long dans un champ en lecture seule | rien, comme aujourd'hui |
| Référence dupliquée à la main dans le champ | le texte est inséré **à chaque occurrence** : le champ fait foi |
| Envoi vidé de toute référence | aucun collage n'est joint |

## Critères d'acceptation
- [ ] Un collage de plus de 5 lignes, ou de plus de 400 caractères, est replié en une référence.
- [ ] Un collage court n'est **pas** replié.
- [ ] Le texte envoyé contient le **contenu intégral**, à la place de la référence.
- [ ] Une référence **effacée** du champ ⇒ son texte **n'est pas** envoyé.
- [ ] La puce dit le nombre de **lignes**, et permet de retirer le collage.
- [ ] Deux collages sont numérotés distinctement.
- [ ] Le collage d'un **fichier** reste traité comme avant.
- [ ] Après envoi, les collages sont oubliés : un nouveau message repart de `#1`.

## Hors scope
Le repli du texte **dans le fil** une fois le message envoyé — c'est un autre écran et un autre lot ·
l'édition du texte replié · le stockage des collages.

## Technique
| Fichier | Changement |
|---|---|
| **`pasted-text.ts`** *(nouveau)* | fonctions **pures** : faut-il replier, référence, recomposition |
| `atelier-terminal.component` | intercepte le collage, tient la liste, recompose à l'envoi, affiche les puces |

Aucune table, aucune migration, aucune route, aucun appel réseau.

## Plan de test
- [ ] Seuils : long ⇒ replié, court ⇒ non.
- [ ] Recomposition exacte, y compris avec deux collages.
- [ ] Référence effacée ⇒ texte non envoyé.
- [ ] Retrait par la puce.
- [ ] Collage de fichier inchangé.

## Préoccupations transversales
| Préoccupation | Cochée | Composants |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | rien n'est lu ni écrit côté serveur |
| Plans / limites | non | le texte envoyé est **identique** à ce qu'il aurait été : même consommation |
| **Navigation / routing** | **oui** *(un écran)* | Seul le terminal change ; aucune route. |
