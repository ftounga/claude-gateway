# Mini-spec — F-192 / SF-192-01 Les suites émises par l'agent (backend)

## Identifiant
`F-192 / SF-192-01` — Feature parente : F-192 « Cap : Et maintenant ? » (cadrage `CADRAGE-F-192-cap-et-maintenant.md`).
Statut : `ready` — 2026-10-11 — Branche `feat/SF-192-01-suites-backend`.

## Objectif
L'agent principal termine sa réponse, quand une suite a du sens, par un bloc balisé de 1 à 4 suites ; la passerelle l'extrait du texte affiché, le valide, le persiste avec le message et le transmet au flux et à l'historique.

## Comportement attendu

### Cas nominal
1. **Consigne** : un littéral stable `NEXT_STEPS_DOCTRINE` est ajouté au préfixe système (toutes cibles, juste après `VERIFY_BEFORE_CONCLUDE_DOCTRINE`) — aucun élément volatil, cache de prompt préservé. Il demande, **après** la réponse complète et seulement si une suite a du sens, un bloc :
   ```
   <<suites>>
   [{"titre":"…","pourquoi":"…","etiquette":"Vérifier|Continuer|Livrer|Risque","demande":"…"}]
   <</suites>>
   ```
   2 à 4 options, chaque `pourquoi` fondé sur un fait du tour ; aucun bloc si aucune suite évidente, ni quand l'agent pose une question (`demander`).
2. **Extraction** (`NextStepsBlock.extract`) en fin de tour, sur le texte final, **avant** persistance : tout bloc `<<suites>>…<</suites>>` est retiré du texte (jamais montré brut) ; une ouverture sans fermeture (troncature) retire tout ce qui suit. Le **dernier** bloc lisible fournit les suites.
3. **Validation** de chaque option : `titre`, `pourquoi`, `demande` non vides ; `etiquette` normalisée (casse et accents libres) vers l'une des quatre valeurs ; option invalide **ignorée** seule ; au plus 4 options retenues.
4. **Persistance** : les suites vont dans le relevé du message (`atelier_messages.terminal_json`, champ `nextSteps`) — même document que le reste du relevé, **aucune nouvelle colonne**. Elles survivent au rechargement via `GET /workspaces/{id}/chat` (champ `terminal.nextSteps`).
5. **Flux** : l'événement SSE `done` porte `nextSteps` (champ additif).
6. Un tour **interrompu** ou **coupé au plafond** ne porte aucune suite (le terminal propose déjà « Reprendre »).

### Contrat (figé, consommé par SF-192-02)
```json
// événement SSE "done" (additif) et terminal.nextSteps de l'historique
"nextSteps": [
  {"title": "≤ 80 car.", "why": "≤ 240 car.", "label": "VERIFIER|CONTINUER|LIVRER|RISQUE", "request": "≤ 2000 car."}
]
```
Absent ou `[]` ⇒ aucune suite.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Bloc au JSON malformé | bloc retiré du texte, aucune suite, tour inchangé |
| Ouverture sans fermeture | tout ce qui suit l'ouverture est retiré, aucune suite |
| Option sans `pourquoi` / sans `demande` / étiquette inconnue | option ignorée, les autres restent |
| `demande` > 2000 caractères | option ignorée (une demande tronquée changerait de sens) |
| Plus de 4 options | les 4 premières |
| Réponse réduite au seul bloc | message de dernier recours (contrat SF-28-18 : jamais de message vide) |
| Tour interrompu / plafond | aucune suite persistée ni émise |

## Contraintes de validation
- `title` tronqué à 80 caractères (« … »), `why` à 240, `request` ≤ 2000 sinon option rejetée ; 4 options max.
- Étiquettes : `VERIFIER`, `CONTINUER`, `LIVRER`, `RISQUE` (enum).

## Critères d'acceptation
- [ ] Le préfixe système contient la consigne des suites, une seule fois, littéral stable.
- [ ] Le texte persisté et renvoyé ne contient jamais `<<suites>>`.
- [ ] Un bloc valide de 3 options donne 3 suites au `done` et dans `terminal.nextSteps` au rechargement.
- [ ] Une réponse sans bloc reste identique (aucune régression).
- [ ] Bloc malformé : retiré, aucune suite, aucune exception.
- [ ] Tour interrompu ou au plafond : aucune suite.
- [ ] Isolation : l'historique d'un autre utilisateur reste inaccessible (contrôle `requireOwned` existant inchangé).

## Plan de test
- Unitaires `NextStepsBlockTest` : extraction nominale, absence, malformé, ouverture seule, option invalide ignorée, étiquette normalisée, borne 4, troncature titre, demande trop longue, dernier bloc gagnant, `attachTo` (relevé nul / existant / illisible).
- `AtelierChatServiceTest` (ajouts) : la consigne est dans le système ; le bloc est retiré de la réponse persistée et les suites sont dans le résultat et `terminal_json` ; tour interrompu sans suite.
- Isolation : couverte par les tests d'historique existants (`requireOwned`) — aucune nouvelle route.

## Impacts
- Tables : **aucune** (champ additif dans le JSON `terminal_json`).
- Endpoints : aucun nouveau ; champ additif `nextSteps` sur l'événement `done` (la réponse synchrone, inutilisée par l'écran, est inchangée) ; `terminal.nextSteps` dans l'historique.
- Composants : `AtelierChatService` (consigne, extraction en fin de tour, `AtelierChatResult`), `AtelierChatController.StreamDone`, nouveau paquet `atelier.nextsteps`.
- Préoccupations transversales : Auth ☐ ; Tenant ☐ (aucun nouvel accès données) ; Plans/limites ☐ ; Navigation ☐.
- Provider Independence : aucune dépendance fournisseur — c'est du texte rendu par `AIProvider`.

## Hors périmètre
Rendu (SF-192-02) ; retrait de F-144 (SF-192-03) ; chemin Managed Agents (`AtelierAgentController`), qui garde son comportement ; envoi automatique d'une option.
