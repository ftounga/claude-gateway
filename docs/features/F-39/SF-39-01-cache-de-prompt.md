# Mini-spec — F-39 / SF-39-01 — Le cache de prompt, et le comptage qui va avec

## Identifiant

`F-39 / SF-39-01`

## Feature parente

`F-39` — L'Atelier comme harnais

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-01-cache-de-prompt`

---

## Objectif

> Mettre en cache le préfixe stable de chaque appel de la boucle d'agent — outils, consigne système,
> historique déjà envoyé — **et** ajuster le comptage des tokens pour que la facturation continue de
> refléter ce qui est réellement consommé.

---

## Déclencheur

Décision **D3** du cadrage F-39. Sans `cache_control`, chaque itération renvoie au tarif plein la
consigne système (jusqu'à 40 000 caractères de `CLAUDE.md` + skills), l'historique et les définitions
d'outils : le volume d'entrée facturé sur un tour de N itérations croît en **N²**.

| Tour de 30 itérations | Tokens d'entrée facturés | Coût estimé |
|---|---|---|
| Sans cache (aujourd'hui) | ~1,35 M | **~6,75 $** |
| Avec cache | ~0,15 M pleins + ~1,2 M en lecture cache | **~1,27 $** |

C'est le levier de rentabilité du chantier, et il conditionne l'économie de tous les lots suivants.

---

## Comportement attendu

### Cas nominal

L'appel à `POST /v1/messages` porte des marqueurs `cache_control` à **deux** endroits, choisis pour
que le préfixe caché grandisse à chaque itération sans jamais être invalidé :

1. **Fin de la consigne système.** L'ordre de rendu du fournisseur étant `tools` → `system` →
   `messages`, un marqueur posé sur le système couvre **aussi les définitions d'outils** qui le
   précèdent. C'est le bloc le plus stable et le plus volumineux : il ne change pas d'une itération à
   l'autre du même tour.
2. **Dernier bloc du dernier message.** À chaque itération, l'historique gagne un message assistant
   et ses résultats d'outils ; le marqueur glisse avec lui. L'itération N+1 lit alors en cache tout
   ce que l'itération N venait d'écrire.

Le `system` passe de la forme texte à la forme **liste de blocs** (`[{type:"text", text:…}]`), seule
forme qui accepte un `cache_control`. Aucun changement de contenu.

### Comptage des tokens — le piège à ne pas manquer

`usage.input_tokens` **ne compte pas** les tokens servis par le cache : ils arrivent dans
`cache_read_input_tokens` et `cache_creation_input_tokens`. Continuer à ne lire que le premier
ferait chuter le décompte du quota d'environ 90 % **sans que rien ne le signale** — les tours
seraient quasiment gratuits pour l'utilisateur, et la plateforme paierait la différence.

Les tokens d'entrée comptabilisés deviennent donc :

```
input_tokens + cache_creation_input_tokens + cache_read_input_tokens
```

C'est-à-dire **le volume réellement traité**, exactement comme avant le cache. L'économie est réelle
côté fournisseur ; elle n'est pas répercutée en silence sur le quota, qui reste une mesure de
consommation et non de facture.

### Observabilité

Le risque principal de cette subfeature est un cache qui **ne prend pas, sans erreur** (préfixe
instable, contenu trop court pour le minimum cacheable). Les trois compteurs sont donc journalisés
en `debug` à chaque tour — jamais le contenu, seulement les nombres.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Consigne système vide | Aucun bloc système envoyé, aucun marqueur ; l'appel reste valide | 200 |
| Aucun outil déclaré | Marqueur système posé quand même (il couvre le système seul) | 200 |
| Réponse sans champ `usage` | Compteurs à zéro, comme aujourd'hui — jamais d'exception | 200 |
| Préfixe trop court pour être caché | Le fournisseur ignore le marqueur ; aucun impact fonctionnel, visible dans les compteurs journalisés | 200 |

---

## Critères d'acceptation

- [ ] Le corps envoyé porte un `cache_control` de type `ephemeral` sur le **dernier bloc système**.
- [ ] Le corps envoyé porte un `cache_control` sur le **dernier bloc du dernier message**.
- [ ] Le nombre total de marqueurs par requête est **≤ 4** (limite du fournisseur).
- [ ] Le `system` est transmis en **liste de blocs**, et son texte est inchangé.
- [ ] Aucun marqueur n'est posé quand la consigne système est vide.
- [ ] `AgentTurn.inputTokens()` vaut `input_tokens + cache_creation + cache_read`.
- [ ] Une réponse sans `usage`, ou sans les champs de cache, donne 0 — jamais d'exception.
- [ ] Le quota décompte le même volume qu'avant le cache, à contenu égal (non-régression de
      facturation, testée).
- [ ] Isolation `user_id` inchangée.

---

## Périmètre

### Hors scope (explicite)

- **SF-39-02** — chargement paresseux des skills. La consigne système reste construite à
  l'identique ; seule sa **mise en cache** change. Les deux subfeatures se renforcent (un préfixe
  plus court est mieux caché) mais ne se conditionnent pas.
- Le cache côté **chat** (F-02) et côté **Managed Agents** — hors du périmètre de la boucle d'agent.
- Toute répercussion de l'économie sur la tarification utilisateur : décision commerciale, pas
  technique.

---

## Contraintes de validation

| Champ | Obligatoire | Valeurs | Normalisation |
|-------|-------------|---------|---------------|
| `cache_control.type` | — | `ephemeral` (seule valeur utilisée) | — |
| Marqueurs par requête | — | ≤ 4 | 2 posés au plus par construction |

---

## Technique

### Endpoint(s)

Aucun endpoint applicatif modifié. L'appel sortant `POST /v1/messages` change de forme.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] **Non applicable.**

### Classes impactées

| Classe | Changement |
|--------|-----------|
| `agent/AnthropicAgentProvider` | `system` en liste de blocs + marqueur ; marqueur sur le dernier bloc du dernier message ; comptage des tokens de cache ; journalisation des compteurs |

Aucune autre classe n'est touchée : `AtelierChatService` ne voit qu'un `AgentTurn` dont les
compteurs ont le même sens qu'avant.

### Composants Angular

Aucun.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés et vérification |
|--------------|-----------|-----------------------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | Aucun chemin d'accès aux données modifié ; `requireOwned` inchangé |
| **Plans / limites** | **Oui — c'est le point sensible** | Le décompte alimente `QuotaService.recordUsage` (F-10), lui-même à la base du plafond de session (F-36) et de la facturation au coût réel. Composants passés en revue : `AtelierChatService.runLoop` (accumulation `inputTokens`), `QuotaService.recordUsage` / `assertWithinQuota`, `AtelierSessionService` (chemin Managed Agents, **non touché** — il lit l'usage du fournisseur, pas `AgentTurn`). Vérification : un test prouve qu'à contenu égal le volume décompté est identique avec et sans cache. |
| Navigation / routing | Non | — |

---

## Plan de test

### Tests unitaires

- [ ] `AnthropicAgentProviderTest` — le corps envoyé porte `system[dernier].cache_control.type ==
      "ephemeral"`.
- [ ] `AnthropicAgentProviderTest` — le corps porte un `cache_control` sur le dernier bloc du dernier
      message.
- [ ] `AnthropicAgentProviderTest` — au plus 4 marqueurs dans la requête.
- [ ] `AnthropicAgentProviderTest` — consigne système vide ⇒ aucun champ `system`, aucun marqueur.
- [ ] `AnthropicAgentProviderTest` — le texte du système est transmis inchangé sous forme de bloc.
- [ ] `AnthropicAgentProviderTest` — `usage` avec cache ⇒ `inputTokens` = somme des trois champs.
- [ ] `AnthropicAgentProviderTest` — `usage` sans les champs de cache ⇒ comportement d'avant,
      aucun échec (non-régression de facturation).
- [ ] `AnthropicAgentProviderTest` — réponse sans `usage` ⇒ 0, aucune exception.

### Tests d'intégration

- [ ] Couvert par `AtelierChatApiIntegrationTest` : le contrat de l'endpoint ne change pas, et les
      tests existants prouvent la non-régression du tour complet.

### Isolation workspace

- [x] Applicable — tests d'isolation existants verts ; aucun nouveau chemin d'accès.

---

## Dépendances

### Subfeatures bloquantes

- `SF-28-18` — done · `SF-28-19` — done

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

**D1 — Deux marqueurs, pas quatre.** La limite du fournisseur est de 4 breakpoints. Deux suffisent
ici : celui du système couvre `tools` + `system` (ordre de rendu), celui du dernier message couvre
tout l'historique. En ajouter davantage fragmenterait le cache sans rien gagner.

**D2 — Le marqueur du dernier message écrit à chaque itération, et c'est voulu.** Une écriture de
cache coûte davantage qu'un envoi simple ; mais dès l'itération suivante, tout ce qui a été écrit est
relu à une fraction du prix. Sur un tour de 30 itérations, l'écriture est payée une fois par segment
et relue jusqu'à 29 fois. Le calcul est favorable à partir de la deuxième itération.

**D3 — Compter le volume traité, pas la facture.** Le quota est une mesure de **consommation**
exprimée en tokens ; il doit rester comparable avant et après cette subfeature. Faire baisser le
décompte parce que le fournisseur nous facture moins reviendrait à offrir aux utilisateurs une remise
qui n'a jamais été décidée, et à rendre incomparables les historiques d'usage.
