# Mini-spec — F-133 / SF-133-02 — Le coût sous la réponse

## Identifiant
`F-133 / SF-133-02`

## Feature parente
`F-133` — Le coût réel Anthropic : par message, par client, par semaine

## Statut
`draft` — en attente de validation PO

## Date de création
2026-09-20

## Branche Git
`feat/SF-133-02-cout-sous-la-reponse`

---

## Objectif

Afficher, **sous chaque réponse de l'Atelier et pour l'administrateur seul**, ce que le tour vient
de coûter — au même endroit et dans la même ligne que la durée et les tokens.

---

## Comportement attendu

### Cas nominal

1. Un tour se termine. Le service connaît déjà ses tokens, ses extras et son modèle (SF-133-01/08).
2. Il calcule le **coût en euros** : coût en dollars × taux de change configuré.
3. Si l'appelant est **administrateur**, le montant voyage jusqu'à l'écran ; sinon il vaut `null`.
4. La ligne existante sous la réponse devient : `1 min 12 s · 34 210 tokens · 0,42 €`.
5. Le montant est **persisté avec le relevé du tour** : au rechargement, il est toujours là.

### L'écran des non-administrateurs ne change pas

Un consultant voit exactement ce qu'il voyait : durée et tokens. Le champ n'est pas seulement caché
côté affichage — **il ne quitte pas le serveur**. Un coût masqué en CSS resterait lisible dans le
flux réseau, et F-133 est une fonction d'administration, pas une information client.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Appelant non administrateur | `cost = null` dans la réponse **et** dans le flux ; l'écran affiche la ligne d'avant |
| Taux de change absent ou absurde | retombe sur le défaut documenté, jamais zéro |
| Coût nul (tour sans consommation) | aucune ligne de coût, comme aujourd'hui pour les tokens |
| Relevé ancien, sans montant persisté | la ligne s'affiche sans le montant — jamais de « 0,00 € » trompeur |

---

## Critères d'acceptation

- [ ] Sous une réponse, l'administrateur lit `… · 0,42 €` à la suite de la durée et des tokens.
- [ ] Un utilisateur non administrateur ne reçoit **aucun montant** : ni dans `AtelierChatResponse`, ni dans l'événement `done` du flux, ni dans le relevé rechargé. Vérifié par un test d'intégration qui **inspecte le JSON**.
- [ ] Le montant est en **euros**, converti depuis le dollar au taux `app.cost.usd-to-eur`, et formaté à deux décimales avec la virgule française.
- [ ] Un montant inférieur à un centime s'affiche `< 0,01 €` plutôt que `0,00 €`.
- [ ] Après rechargement de la page, le montant d'un tour ancien est **toujours** affiché.
- [ ] Le calcul du coût **ne peut pas faire échouer un tour** : toute erreur laisse le montant à `null`.
- [ ] Aucune couleur ni police hors `DESIGN_SYSTEM.md` (la ligne existe déjà, on ajoute du texte dedans).

---

## Périmètre

### Hors scope (explicite)
- Les écrans de consommation (F-16, F-61) : ils gardent leur estimation actuelle jusqu'à SF-133-07.
- Le chat simple, l'Ask, le Radar : seul l'Atelier porte cette ligne aujourd'hui.
- Les budgets et les alertes (SF-133-04, 06).
- Le détail du coût (part du cache, des recherches) : un montant, pas une facture.

---

## Technique

### Endpoints

Aucun endpoint nouveau. Trois **charges utiles** gagnent un champ additif et nullable :
`AtelierChatResponse`, `AtelierAgentController.StreamDone`, `AtelierTurnReport` (persisté).

### Tables impactées

Aucune. Le montant vit dans `atelier_messages.terminal_json`, colonne d'affichage existante.

### Migration Liquibase
- [ ] Non applicable

### Classes touchées

| Classe | Changement |
|---|---|
| `ProviderPricingProperties` | `usdToEur` (défaut `0.92`) |
| **`TurnCostView`** *(nouveau)* | décide si le montant sort : admin ou rien ; convertit en euros |
| `AtelierChatService` | calcule le coût du tour, le range dans le relevé et le résultat |
| `AtelierChatResult`, `AtelierChatResponse`, `StreamDone`, `AtelierTurnReport` | champ `costEur` nullable |
| `AtelierAgentController` | relaie le champ |

### Composants Angular

- `atelier.types.ts` — `AtelierTurnCost` gagne `amount?: string`
- `atelier.component.ts`, `atelier-terminal.component.ts` — `costLabel()` ajoute le montant s'il existe

---

## Plan de test

### Tests unitaires
- [ ] `TurnCostView` — administrateur ⇒ montant ; utilisateur ordinaire ⇒ `null`.
- [ ] `TurnCostView` — conversion dollar → euro au taux configuré.
- [ ] `TurnCostView` — montant sous le centime ⇒ `< 0,01 €`.
- [ ] `TurnCostView` — coût nul ⇒ `null`, jamais « 0,00 € ».
- [ ] `TurnCostView` — une erreur de calcul rend `null` sans lever.
- [ ] `costLabel()` (frontend) — avec et sans montant.

### Tests d'intégration
- [ ] `POST /api/workspaces/{id}/chat` en administrateur ⇒ le JSON porte `costEur`.
- [ ] Le même appel en utilisateur ordinaire ⇒ **le champ est absent ou nul dans le JSON**.
- [ ] Le relevé rechargé porte le montant pour l'administrateur, pas pour les autres.

### Isolation utilisateur
- [x] Applicable — un utilisateur ne voit jamais le coût d'un autre, et un non-administrateur n'en voit aucun.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | `AdminService.assertAdmin` (réutilisé, **jamais dupliqué** : une seconde définition de « qui est admin » finirait par diverger), `AtelierAgentController` (réponse **et** flux SSE), `AtelierChatService`. Le flux est le point à ne pas oublier : c'est le chemin nominal de l'écran |
| Contexte tenant | non | aucun changement de résolution |
| Plans / limites | non | aucun changement de décompte |
| Navigation / routing | non | aucune route |

---

## Estimation

**1 jour.** Un service de décision, un champ additif sur trois charges utiles, une ligne d'affichage.
