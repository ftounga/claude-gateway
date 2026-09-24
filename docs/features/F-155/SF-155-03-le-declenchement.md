# Mini-spec — F-155 / SF-155-03 — Le déclenchement du bilan

## Identifiant
`F-155 / SF-155-03` — feature parente `F-155` — dépend de **SF-155-01** et **SF-155-02**

## Objectif
Que le bilan se propose **au bon moment** — automatiquement quand la session le mérite, d'un clic
sinon — et **jamais** quand il n'y a rien à dire.

## La demande
> PO : *« Lorsque je ferme une session — quand je clique sur "reprendre depuis le début" —, qu'un
> processus se lance. »* · arbitrage : *« automatique au-delà d'un seuil »*, **en euros ET en tours**,
> le **premier atteint**.

## Le moment
**« Nouveau départ »** (`POST /workspaces/{id}/restart`, F-117) : c'est le geste par lequel
l'utilisateur **ferme lui-même** une session. Il n'y en a pas d'autre — un projet qu'on quitte n'est
pas fermé, il attend.

## Comportement attendu
1. Au moment du nouveau départ, la session qui se ferme est **relevée** (SF-155-01) sur la fenêtre
   `[frontière précédente → maintenant]`, **avant** que la frontière ne bouge — sinon il n'y aurait
   plus rien à relever.
2. **Automatique** si la session dépasse **le seuil en euros OU le seuil en tours** — le premier
   atteint. Une session courte mais coûteuse le mérite ; une session longue et bon marché aussi.
3. **Proposé** en dessous : l'écran dira qu'un bilan est disponible, sans le produire. *Une session
   de trois tours à quelques centimes ne mérite pas qu'on dépense pour l'analyser.*
4. **Rien du tout** si la session est vide, ou si **aucune suggestion** ne passe le seuil d'impact
   **et** qu'il n'y a rien d'écarté à signaler : il n'y a alors littéralement rien à montrer.
5. **Réservé à l'administrateur** (arbitrage du PO). Pour les autres, le nouveau départ se comporte
   **exactement comme avant** — aucun relevé n'est même calculé.
6. Le nouveau départ **ne doit jamais échouer à cause du bilan**. Une erreur pendant le relevé est
   avalée et journalisée : on perd un bilan, on ne perd pas le geste de l'utilisateur.

| Cas d'erreur | Comportement |
|---|---|
| Relevé impossible (base, calcul) | journalisé ; le nouveau départ réussit, **sans** bilan |
| Projet sans frontière précédente | la fenêtre part de la **création du projet** — la première session est une session |
| Utilisateur non administrateur | aucun calcul, réponse inchangée |

## Critères d'acceptation
- [ ] Le relevé est pris **avant** le déplacement de la frontière.
- [ ] Au-dessus du **seuil en euros** → `AUTOMATIQUE`. Au-dessus du **seuil en tours** → `AUTOMATIQUE`.
      Sous **les deux** → `PROPOSÉ`. Rien à dire → `AUCUN`.
- [ ] Les deux seuils sont **configurables** (défauts : **2 €** et **20 tours**).
- [ ] Un **non-administrateur** obtient `AUCUN`, et **aucun** relevé n'est calculé.
- [ ] Une exception pendant le bilan **ne casse pas** le nouveau départ.
- [ ] La réponse de reprise porte l'issue, **sans casser** les appelants existants.
- [ ] **ISOLATION** : le relevé passe par `SessionLedgerService`, donc par `requireOwned`.

## Hors scope
La **persistance** du bilan et l'**écran** (**SF-155-04**) · le renvoi vers F-156 (**SF-155-05**) ·
tout appel modèle.

## Technique
| Élément | Changement |
|---|---|
| `BilanTrigger` (enum) | `AUTOMATIQUE`, `PROPOSE`, `AUCUN` |
| `SessionBilanTriggerService` | la décision : admin, fenêtre, relevé, seuils |
| `SessionBilanProperties` | `autoEuros` (défaut **2**) et `autoTurns` (défaut **20**) |
| `AtelierThreadService.restart` | appelle le déclencheur **avant** de déplacer la frontière |
| `AtelierResumeResponse` | un champ `bilan` de plus, les formes historiques conservées |

**Aucune migration** : rien n'est encore gardé — c'est SF-155-04.

## Plan de test
- [ ] Au-dessus du seuil en euros seul → `AUTOMATIQUE` ; au-dessus du seuil en tours seul → `AUTOMATIQUE`.
- [ ] Sous les deux, avec quelque chose à dire → `PROPOSE`.
- [ ] Session vide, ou rien à dire → `AUCUN`.
- [ ] Non-administrateur → `AUCUN`, **et** `SessionLedgerService` n'est jamais appelé.
- [ ] Exception du relevé → `AUCUN`, le nouveau départ réussit quand même.
- [ ] La fenêtre part de la **frontière précédente**, pas de la nouvelle.
- [ ] Seuils configurés différents du défaut → respectés.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | nouvelle lecture du **rôle** : `AtelierThreadService.restart` demande `CurrentUser.principal().role()`. Aucun autre endpoint ne change de comportement ; le rôle n'est lu que pour **décider de produire un bilan**, jamais pour autoriser le nouveau départ lui-même — un non-administrateur redémarre exactement comme avant. `AuthenticatedUser` est inchangé. |
| **Contexte tenant** | **oui** | le relevé passe par `SessionLedgerService.of(...)`, donc par `requireOwned` ; `AtelierThreadService.restart` faisait déjà `requireOwned` — c'est le même projet, lu deux fois, sans élargissement. |
| **Plans / limites** | **oui** | nouveau gate : **rôle ADMIN**. Il **ferme**, il n'ouvre rien : sans lui, le comportement d'avant. Aucun appel fournisseur, aucun quota consommé. |
| **Navigation / routing** | **oui** *(réponse enrichie)* | `AtelierResumeResponse` gagne un champ ; les **constructeurs historiques sont conservés** pour que `resume()` et les tests existants ne changent pas. Aucune route, aucun guard, aucune redirection. |
