# Mini-spec — F-142 / SF-142-16 — Le vocabulaire qui pardonne

## Identifiant
`F-142 / SF-142-16` — feature parente `F-142`. **Absorbe SF-142-14** (même racine).

## Objectif
Qu'un nom de service raisonnable soit **compris**, et qu'un nom incompris rende une **piste utile**.

## Le constat, sur le rendu réel du 2026-09-27
Sur la vue réseau, **MWAA est dessiné en pointillés rouges** : le type n'a pas été résolu. L'agent
a écrit le nom que tout le monde emploie — `aws.mwaa` — et le catalogue attend
`aws.amazonmanagedworkflowsapacheairflow`.

Deux moitiés du même défaut :
- **le catalogue ne pardonne rien** : le sigle officiel du service n'est pas un alias ;
- **le refus ne guide pas** — reste-à-faire déjà écrit dans SF-142-12 (« l'affiner est une autre
  subfeature ») : `aws.managedworkflowsforapacheairflow` suggère aujourd'hui « aws.sf », et un nom
  court rend la liste alphabétique.

Depuis SF-142-12 le composant est au moins **visible**. Il devrait surtout **marcher**.

## L'arbitrage, tranché ici
**Des alias explicites, jamais une ressemblance calculée.** Un rapprochement approximatif finirait
par rendre une icône **fausse** dans un livrable client — la règle de fond de F-142 l'interdit. Une
table d'alias est un choix écrit, relu, testé.

**La suggestion, elle, peut approcher** : proposer n'engage à rien, c'est l'humain ou l'agent qui
tranche. La distance d'édition y est légitime là où elle ne l'est pas pour résoudre.

## Comportement attendu
1. Les **sigles usuels** résolvent : `mwaa`, `sm` (Secrets Manager), `tgw`, `asg`, `igw`, `nlb`…
2. Une **garde de build** vérifie que chaque alias pointe vers une classe qui existe — un alias mort
   est pire qu'une absence d'alias.
3. Un type non résolu propose des **types proches réellement pertinents** (distance d'édition +
   sous-chaîne), jamais une liste alphabétique par défaut quand mieux existe.
4. La suggestion apparaît dans le refus `strict` **et** dans l'avertissement du mode normal.
5. Aucun alias ne rend une icône **approchante** : un alias est une **égalité**, pas une ressemblance.

## Critères d'acceptation
- [ ] `aws.mwaa` rend l'icône officielle MWAA.
- [ ] Au moins six sigles usuels résolvent, testés un par un.
- [ ] Un alias pointant vers une classe inexistante **fait échouer le build**.
- [ ] `aws.managedworkflowsforapacheairflow` suggère le bon type, pas « aws.sf ».
- [ ] La suggestion remonte dans l'avertissement du mode normal, pas seulement en `strict`.
- [ ] Aucun type ne résout par ressemblance.

## Plan de test minimal
**Unitaires (renderer)** — chaque alias résout · la garde de build détecte un alias mort · la
suggestion sur un nom long, un nom court, un nom inconnu · aucune résolution approximative.
**Non-régression** — les types déjà résolus rendent la même classe.

## Technique
| Élément | Changement |
|---|---|
| `cloud.py` | table `ALIASES` enrichie ; suggestion par distance d'édition |
| `check_catalog.py` | garde de build : chaque alias pointe vers une classe existante |

**Aucune migration.**

## Hors périmètre
L'imbrication (**SF-142-15**) · le placement (**SF-142-17**) · enrichir le catalogue de `diagrams`
lui-même.
