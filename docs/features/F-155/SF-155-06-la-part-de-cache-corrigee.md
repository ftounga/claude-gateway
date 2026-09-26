# Mini-spec — F-155 / SF-155-06 — La part de cache, corrigée

## Identifiant
`F-155 / SF-155-06` — feature parente `F-155` — **corrige un défaut livré** en SF-155-01, SF-155-02
et **F-156 / SF-156-02**

## Le défaut
Les trois endroits qui calculent la part de cache écrivent :

```java
part = cacheRead / (input + cacheRead)
```

Or `usage_turns.input_tokens` **contient déjà** le cache. C'est écrit dans le code qui l'alimente :
`TurnTokens.processedInputTokens()` = « entrée directe **+ lectures + écritures de cache** », et
`AnthropicAgentProvider` fait `input_tokens + cacheCreation + cacheRead` avant de l'enregistrer.

Le dénominateur compte donc le cache **deux fois**, et la part est **divisée par deux environ**.

## Ce que ça coûtait, mesuré sur une vraie session
Sur la session KPMG du 25/09 (74 tours, 81,70 $, vérifiée au centime contre la grille) :

| | Formule livrée | Réalité |
|---|---:|---:|
| Part de cache | **46 %** | **86 %** |

Conséquence : le détecteur « cache froid » se serait déclenché sur une session dont **le cache est
sain**, et aurait chiffré un gain fantôme — l'exact contraire de ce que la feature promet. Le vrai
problème de cette session était ailleurs : **72 % de la facture part en écriture de cache**, que ce
détecteur ne regarde pas.

**Un bilan qui se trompe de moitié sur son chiffre principal est pire qu'un bilan absent** : on
corrige ce qui va bien.

## Comportement attendu
1. La part de cache vaut **`cacheRead / inputTokens`**, puisque `inputTokens` porte déjà le cache.
2. Elle reste bornée à **0–100 %** : aucune donnée ne doit produire 120 %.
3. Un relevé sans jeton d'entrée rend **0**, sans division par zéro.
4. Les trois endroits utilisent **la même** formule — un défaut recopié trois fois se re-recopiera
   s'il reste trois formules.
5. **Une garde ancre la sémantique** : un test rappelle, avec les chiffres réels de la session KPMG,
   que `input` inclut le cache — pour que la prochaine lecture du code n'ait pas à le redécouvrir.

| Cas | Comportement |
|---|---|
| `input = 0` | part = 0 |
| `cacheRead > input` (donnée aberrante) | part **plafonnée à 100** |
| Tour sans cache | part = 0, comme avant |

## Ce que je ne corrige pas ici, et pourquoi
Le **gain** du détecteur reste calculé à `(prix d'entrée − prix de lecture)` par jeton déplacé. Sur
un profil dominé par l'**écriture** de cache, le vrai prix évité est celui de l'écriture — plus
élevé. La formule **sous-estime** donc le gain.

C'est le bon sens de l'erreur : une suggestion qui sous-promet vaut mieux qu'une suggestion qu'on
prend en défaut (SF-155-02). Le corriger demande de modéliser l'écriture de cache, ce qui est une
subfeature à part — **pas un ajustement glissé dans une correction**.

## Critères d'acceptation
- [ ] Les trois endroits appellent **une seule** implémentation partagée.
- [ ] `cacheRead / input`, borné 0–100, zéro sur entrée nulle.
- [ ] **Test d'ancrage** sur les chiffres réels de la session KPMG : 42 976 876 / 37 085 919 → **86 %**
      (et non 46 %).
- [ ] Un relevé au cache sain ne déclenche **plus** le détecteur « cache froid ».
- [ ] Les tests existants de SF-155-01/02 et SF-156-02 sont **mis à jour, pas affaiblis**.

## Hors scope
Le modèle de gain (voir plus haut) · la stabilité du runner (sans aucun rapport) · le plancher
d'écartement proportionnel.

## Technique
| Élément | Changement |
|---|---|
| `CacheShare` (classe utilitaire) | **la** formule, une fois, avec la sémantique documentée |
| `SessionLedgerService` | y délègue |
| `SessionSuggestionService` | `totalInput` = `inputTokens` seul |
| `ProductSurveyService` | idem, par tour |

**Aucune migration** : les données sont justes, c'est leur lecture qui était fausse.

## Plan de test
- [ ] Formule : cas nominal, entrée nulle, aberration plafonnée.
- [ ] **Ancrage KPMG** : les vrais chiffres donnent 86 %.
- [ ] Le détecteur se tait sur un cache à 86 % (il se déclenchait à tort).
- [ ] Le détecteur se déclenche toujours sur un vrai cache froid (0 %).
- [ ] L'enquête de F-156 ne compte plus de gaspillage sur un tour au cache sain.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | aucun endpoint |
| Contexte tenant | non | aucune lecture nouvelle |
| Plans / limites | non | aucun appel fournisseur |
| Navigation / routing | non | aucune route ; l'écran affiche la valeur corrigée sans changer |
