# Mini-spec — F-144 / SF-144-01 — La suite suggérée, sous la zone de saisie

## Identifiant
`F-144 / SF-144-01` — feature parente `F-144`

## Objectif
Proposer la **suite logique** du travail en cours, sous la zone de saisie, comme le fait Claude Code.

## Le défaut
La zone de saisie ne porte qu'un texte fixe — *« Décrivez la tâche à exécuter… »*. Après un tour, ce
qu'il y a à faire ensuite est souvent évident **pour qui relit le relevé** : une étape de plan restée
ouverte, un tour interrompu, des fichiers modifiés sans vérification. L'écran le sait, et ne le dit
pas.

## La décision qui structure la feature
**La suggestion est dérivée de l'état du tour, sans aucun appel au modèle.**

Une suggestion produite par un appel modèle coûterait **un tour de plus à chaque réponse** — ce que
F-134 vient précisément de faire baisser d'un facteur 16 — et ajouterait une attente avant de pouvoir
taper. Or tout ce qu'il faut est **déjà dans le relevé** que l'écran affiche : le plan et l'état de
ses étapes (F-39 / SF-39-13), l'interruption (F-32), le plafond atteint (F-36), les fichiers modifiés
(F-37).

Si le résultat se révèle trop pauvre à l'usage, l'appel modèle reste une variante ouverte — et ce
sera alors un choix, pas un défaut.

## Comportement attendu
1. Après un tour, jusqu'à **trois** suggestions apparaissent sous la zone de saisie.
2. Un clic **remplit le champ** ; il **n'envoie rien**. Rien ne part sans un geste.
3. Les suggestions suivent l'état, par ordre d'évidence :

| Ce que le relevé dit | Suggestion |
|---|---|
| Une étape de plan reste `pending` ou `active` | *« Continuer : &lt;titre de l'étape&gt; »* |
| Le tour a été **interrompu** | *« Reprends là où tu t'es arrêté »* |
| Le tour s'est arrêté sur le **plafond** | *« Reprends : le plafond du tour a été atteint »* |
| Des fichiers ont été **modifiés** | *« Vérifie l'état après ces modifications »* |

4. Rien à suggérer ⇒ **aucune puce**, et la zone de saisie est celle d'avant.
5. Dès que l'utilisateur tape, les suggestions **s'effacent** : elles proposent, elles n'encombrent
   pas.

| Cas d'erreur | Comportement |
|---|---|
| Relevé absent (tours d'avant cette version) | aucune suggestion |
| Plan vide | aucune suggestion de plan |
| Titre d'étape très long | tronqué, jamais rejeté |

## Critères d'acceptation
- [ ] Après un tour au plan inachevé, la première suggestion nomme **l'étape suivante**.
- [ ] Un tour interrompu, ou arrêté au plafond, propose de **reprendre**.
- [ ] Des fichiers modifiés proposent de **vérifier**.
- [ ] **Trois au plus**, sans doublon.
- [ ] Un clic **remplit** le champ et **n'envoie pas**.
- [ ] Rien à suggérer ⇒ aucune puce.
- [ ] Les suggestions disparaissent dès que l'utilisateur écrit.
- [ ] **Aucun appel réseau** n'est déclenché par cette feature — vérifié par test.

## Hors scope
La suggestion **produite par le modèle** (variante ouverte, pas ce lot) · l'envoi automatique — rien
ne part sans un geste · les suggestions hors terminal.

## Technique
| Fichier | Changement |
|---|---|
| **`turn-suggestions.ts`** *(nouveau)* | fonction **pure** : relevé → suggestions |
| **`turn-suggestions.component.ts`** *(nouveau)* | les puces |
| `atelier-terminal.component` | les place sous la zone de saisie |

Aucune table, aucune migration, **aucune route**, **aucun appel modèle**.

## Plan de test
- [ ] Plan inachevé, tour interrompu, plafond, fichiers modifiés : une suggestion chacun.
- [ ] Priorité et plafond de trois.
- [ ] Clic ⇒ champ rempli, rien d'envoyé.
- [ ] Relevé absent ou vide ⇒ rien.
- [ ] Aucun appel réseau.

## Préoccupations transversales
| Préoccupation | Cochée | Composants |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | aucune donnée n'est lue : tout vient du relevé déjà affiché |
| Plans / limites | non | aucun appel modèle, donc aucun jeton consommé |
| **Navigation / routing** | **oui** *(un écran)* | Seul le terminal reçoit le composant ; aucune route ne change. |
