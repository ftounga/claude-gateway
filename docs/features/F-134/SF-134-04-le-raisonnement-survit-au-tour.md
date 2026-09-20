# Mini-spec — F-134 / SF-134-04 — Le raisonnement survit au tour

## Identifiant
`F-134 / SF-134-04`

## Feature parente
`F-134` — Le cache qui ne prend pas

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-134-04-le-raisonnement-survit`

---

## Objectif

Conserver les blocs de raisonnement du modèle avec la trace du tour, et les **rejouer tels quels**
au tour suivant — pour que le ruban envoyé au fournisseur redevienne identique à celui qu'il a mis
en cache.

---

## Le défaut

Pendant un tour, le message assistant envoyé contient trois choses, dans cet ordre
(`AtelierChatService:1390-1405`) :

```
[ raisonnement ] [ texte ] [ appels d'outils ]
```

C'est cet ensemble que le fournisseur met en cache.

Au tour suivant, `AtelierToolTrace.replay()` reconstruit l'historique depuis la base et produit :

```
[ texte ] [ appels d'outils ]
```

**Le raisonnement n'a jamais été persisté.** Le ruban rejoué diffère donc de celui qui était en
mémoire, **dès le premier bloc de chaque tour** — et tout ce qui suit est réécrit.

Ce défaut frappe **toutes** les conversations, y compris celles de deux tours : c'est lui qui
explique un tour de fil court mesuré à **23 %** de contexte relu.

---

## Ce que le fournisseur attend

Les blocs de raisonnement sont **signés**. La règle est déjà écrite dans notre propre code
(`AgentContentBlock.Reasoning`, F-39 / SF-39-10) : *« rejoué tel quel, jamais reconstruit […] le
modifier ou l'omettre casse la boucle »*.

Deux formes existent, et les deux doivent survivre :

- `Reasoning(text, signature)` — le texte est souvent vide, **la signature ne l'est pas** ;
- `RedactedReasoning(data)` — charge opaque, réémise sans interprétation.

---

## Comportement attendu

### Cas nominal
1. À chaque itération d'un tour, les blocs de raisonnement rendus par le fournisseur sont rangés
   dans la trace, **à côté** du texte et des appels d'outils de cette itération.
2. Au tour suivant, `replay()` les remet **en tête** du message assistant, avant le texte, puis les
   appels — exactement l'ordre d'origine.
3. Le ruban rejoué redevient identique à celui qui a été mis en cache.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Trace d'un tour **antérieur** à cette subfeature | aucun bloc de raisonnement : le tour se rejoue comme avant, sans erreur |
| Trace illisible | le tour retombe en texte seul, inchangé |
| Bloc sans signature ni données | ignoré : un bloc vide n'apporte rien et pourrait être refusé |
| Trace trop volumineuse | bornée comme aujourd'hui, les étapes les plus anciennes du tour étant abandonnées |

---

## Critères d'acceptation

- [ ] Un tour dont le modèle a raisonné enregistre ses blocs dans la trace, avec leur **signature**.
- [ ] Le rejeu les restitue **en tête** du message assistant, avant le texte et les appels d'outils.
- [ ] L'ordre et le contenu sont **identiques** à ce qui avait été envoyé : aucun bloc reconstruit, aucune retouche.
- [ ] Les blocs **expurgés** survivent aussi, sans interprétation.
- [ ] Une trace écrite **avant** cette subfeature se rejoue exactement comme aujourd'hui.
- [ ] Un bloc sans signature ni données n'est pas écrit.
- [ ] La borne de volume de la trace tient compte des blocs ajoutés.

---

## Périmètre

### Hors scope (explicite)
- L'effort qui varie entre itérations (SF-134-05).
- Les marqueurs de cache (SF-134-02).
- L'affichage (SF-134-03).
- Le contenu du raisonnement n'est **pas exposé** : il ne sort ni dans l'historique rendu au client,
  ni dans le relevé. Il ne sert qu'au rejeu vers le fournisseur.

---

## Technique

| Classe | Changement |
|---|---|
| `AtelierToolTrace.Step` | gagne `List<Thought> thoughts` |
| **`AtelierToolTrace.Thought`** *(nouveau)* | `text`, `signature`, `redacted` |
| `AtelierToolTrace.replay()` | restitue les blocs en tête du message assistant |
| `AtelierChatService` | range les blocs de raisonnement dans la trace |

Aucun endpoint, aucune table, aucune migration : la trace est un JSON dans une colonne existante,
et un champ ajouté y est rétro-compatible (`@JsonIgnoreProperties(ignoreUnknown = true)`).

---

## Plan de test

### Tests unitaires
- [ ] Une trace portant un raisonnement le rejoue **en tête**, avant le texte.
- [ ] La **signature** traverse intacte.
- [ ] Un bloc expurgé traverse intact.
- [ ] Une trace **sans** champ `thoughts` (ancienne) se rejoue comme avant.
- [ ] Un bloc vide n'est pas écrit.
- [ ] L'ordre `raisonnement → texte → appels` est respecté.

### Tests d'intégration
- [ ] Après un tour où le modèle a raisonné, le tour suivant **renvoie le même préfixe** que ce qui
      avait été envoyé — la propriété qui fait tenir le cache.
- [ ] Non-régression : un fil sans raisonnement se comporte exactement comme avant.

### Isolation utilisateur
- [x] Non applicable — la trace est déjà lue sous filtre `user_id`.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Plans / limites** | **oui** | `AtelierToolTrace.MAX_TRACE_CHARS`, `AtelierCompactionService` — la trace grossit des signatures : vérifier que la borne les compte, et que le volume reste sous le seuil de compaction |
| Auth / Principal | non | aucun changement |
| Contexte tenant | non | lecture déjà filtrée |
| Navigation / routing | non | aucun écran |

---

## Estimation

**0,5 jour.**
