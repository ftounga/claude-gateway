# Mini-spec — F-155 / SF-155-05 — Le renvoi vers le diagnostic du produit

## Identifiant
`F-155 / SF-155-05` — feature parente `F-155` — dépend de **SF-155-01** → **04**

## Objectif
Que le bilan **sache se taire sur ce qui n'est pas son sujet** — et **passer la main** quand le
problème n'est plus l'usage mais **le produit**.

## La demande
> Cadrage F-155 : *« Quand le bilan voit un motif revenir (« 4ᵉ fois en dix sessions »), il propose
> de lancer le diagnostic. Proposé quand c'est justifié, jamais à chaque fermeture. »*

## La frontière, et pourquoi elle compte
Le bilan dit ce que **l'utilisateur** aurait pu faire autrement. Quand **la même suggestion revient
séance après séance**, ce n'est plus une habitude à corriger : c'est que **l'application** laisse le
défaut se reproduire. Le remède n'est alors pas un conseil, c'est une **feature** — et c'est F-156.

Sans ce renvoi, le bilan répéterait indéfiniment un conseil que l'utilisateur a déjà lu, et
deviendrait exactement le bruit qu'il est censé éviter.

## Comportement attendu
1. Chaque suggestion porte désormais un **genre stable** (`CACHE_FROID`, `TOUR_HORS_NORME`,
   `OUTIL_DOMINANT`, `ECHECS_REPETES`) — pas son texte : un texte qu'on reformule cesserait de se
   reconnaître d'un bilan à l'autre.
2. Les genres d'un bilan sont **gardés en clair** à côté de lui, pour qu'on puisse les compter
   **sans désérialiser** les photographies.
3. Un genre vu **au moins 3 fois sur les 10 derniers bilans** devient un **motif**.
4. Un motif est rendu avec **son compte et sa fenêtre** — « 4ᵉ fois sur 10 sessions » — et une
   phrase qui dit **ce que le diagnostic irait chercher**.
5. **Une anecdote n'est pas un motif** : sous le seuil, rien. Et un motif ne se signale **que** s'il
   apparaît dans le bilan qu'on regarde — sinon le renvoi serait hors sujet.
6. **Aucun lancement automatique.** F-156 se lance **à la demande** — c'est son cadrage, et le
   diagnostic coûte cher.

| Cas d'erreur | Comportement |
|---|---|
| Moins de bilans que la fenêtre | on compte sur ce qu'on a ; le seuil reste le même |
| Aucun bilan antérieur | aucun motif — le premier bilan ne peut rien répéter |
| Genres illisibles sur un ancien bilan | ignorés, sans erreur : les bilans d'avant cette subfeature n'en ont pas |

## Critères d'acceptation
- [ ] Chaque suggestion porte un **genre**, et les quatre détecteurs le renseignent.
- [ ] Les genres sont **gardés en colonne**, lisibles sans désérialiser.
- [ ] Un genre vu **3 fois sur 10** est un motif ; 2 fois n'en est pas un.
- [ ] Le motif est rendu **avec son compte et sa fenêtre**, et une phrase sur ce qu'irait chercher
      le diagnostic.
- [ ] Seuls les genres **présents dans le bilan ouvert** peuvent être signalés.
- [ ] Un bilan **sans antériorité** ne signale rien.
- [ ] Les seuils sont **configurables**.
- [ ] **ISOLATION** : le comptage lit les bilans du **compte**, filtrés `user_id`.

## Hors scope
Le **diagnostic lui-même** (**F-156**) · tout lancement automatique · toute comparaison chiffrée
entre deux bilans.

## Technique
| Élément | Changement |
|---|---|
| Migration `133-session-bilan-kinds.xml` | colonne `suggestion_kinds` |
| `SessionSuggestion` | un champ `kind` |
| `SessionSuggestionService` | chaque détecteur nomme son genre |
| `SessionPatternService` | le comptage, le seuil, la phrase du renvoi |
| `SessionBilanStore` | garde les genres en clair |
| `SessionBilanDetail` + écran | rend les motifs |

## Plan de test
- [ ] Les quatre détecteurs renseignent leur genre.
- [ ] 3 occurrences sur 10 → motif ; 2 → rien.
- [ ] Un genre absent du bilan ouvert n'est pas signalé, même s'il est fréquent ailleurs.
- [ ] Premier bilan → aucun motif.
- [ ] Genres illisibles / absents (bilans d'avant) → ignorés sans erreur.
- [ ] **ISOLATION** : le comptage ne voit que les bilans du compte.
- [ ] Écran : le motif est rendu avec son compte, sa fenêtre et sa phrase.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | les routes existent déjà (SF-155-04) et gardent leur garde |
| **Contexte tenant** | **oui** | `SessionPatternService` lit par `SessionBilanRepository.findByUserIdOrderByCreatedAtDesc` — `user_id` **et** une fenêtre bornée. Aucun accès par identifiant seul, aucun élargissement. |
| Plans / limites | non | aucun appel fournisseur ; le comptage lit des colonnes |
| Navigation / routing | non | aucune route ; le motif s'affiche **dans** le détail existant |
