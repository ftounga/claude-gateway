# Mini-spec — [F-172 / SF-172-07] Emballement du raisonnement caché : couper, relancer une fois

## Identifiant

`F-172 / SF-172-07`

## Feature parente

`F-172` — Opus 5.5 dans la Forge (capacités API relayées), **rouverte** le 2026-11-05 pour ce correctif.

## Statut

`in-progress`

## Date de création

2026-11-05

## Branche Git

`feat/SF-172-07-emballement`

---

## Constat (diagnostic du 2026-11-05)

Sur `claude-opus-5-5` en flux (`AnthropicAgentProvider`, `display: "updates"`, effort `high`), environ 1 tour
sur 100 part dans un raisonnement caché sans fin : **un** appel, **aucun** texte émis (3 événements SSE),
≈ 150 jetons/s jusqu'au plafond `max_tokens` → l'utilisateur lit « Ma réponse a dépassé la taille
maximale… rien n'a été exécuté » au bout de 3,5 à 7 min. 9 cas sur 980 tours depuis le 11/10, tous à
182-198 k jetons d'entrée. Le **même** message renvoyé réussit toujours (30-60 s, 1,5-6 k jetons). Aucun
appel réussi n'a jamais produit plus de 18 104 jetons (p99 14 917). Plafond prod remis à 32 768.

## Objectif

Détecter en cours de flux un appel qui raisonne sans rien produire de visible au-delà d'un seuil de
jetons, l'interrompre et le relancer automatiquement une fois à l'identique ; sur second emballement,
dire clairement ce qui s'est passé — sans jamais couper un appel qui avance.

---

## Comportement attendu

### Cas nominal

1. **Détecteur** (`RunawayDetector`, pur, testable) alimenté par le flux SSE d'un appel :
   - une **activité visible** = un delta de texte non vide, une note de progression lisible (delta de
     raisonnement non vide), ou **tout bloc non-raisonnement ouvert** (`tool_use`, `server_tool_use`,
     résultat d'outil serveur, `text`…). Dès la première activité, l'appel n'est **plus jamais** coupé ;
   - jetons produits = `max(sortie rapportée par le flux, durée depuis message_start × débit)`. Le flux
     ne rapporte la sortie qu'à la fin (`message_delta`) : en cours de route seul le temps est
     observable. Débit par défaut **150 jetons/s** = débit mesuré des emballements ;
   - emballement = aucune activité visible **et** jetons estimés > seuil (défaut **16 000**).
2. **Lecture du flux** : un fil dédié lit les lignes SSE et les passe au fil de l'appel, qui interroge
   le détecteur à chaque événement **et** chaque seconde de silence (un raisonnement caché n'émet rien).
   La réponse HTTP est fermée par le fil lecteur (fermer depuis un autre fil bloquerait sur le verrou de
   lecture) ; sur coupure, il ferme la connexion dès qu'il reprend la main.
3. **Coupure → relance unique** : l'appel coupé n'a produit ni texte ni outil (condition même de la
   coupure) ; aucun `AgentTurn` n'en sort, donc **aucun outil ne peut être exécuté**. Le **même corps**
   (marqueurs de cache, raisonnement signé, effort, plafond inchangés) est renvoyé une fois. Le tour
   rendu par la relance porte en plus l'usage de l'appel coupé (entrée exacte lue sur `message_start`,
   sortie estimée) et `runawayRelaunches = 1`.
4. **Second emballement** : `AgentRunawayException` (signal neutre, sous-type de `AIProviderException`)
   portant l'usage cumulé des deux appels. La Forge rend un message clair (ni « taille maximale », ni
   rien d'exécuté), compte l'usage, et clôt le tour.
5. **Journal** (une ligne, des nombres seulement, jamais de contenu) à la coupure **et** à tout arrêt
   `max_tokens` : types de blocs vus, caractères de raisonnement / de texte, outil en cours (nom,
   caractères d'entrée), jetons (rapportés ou estimés), contexte d'entrée (entrée + cache lu + écrit),
   durée, nombre d'événements.
6. **Mesure J+7** : `atelier_turn_measures.stop_cause` = `emballement coupé et relancé` (tour rendu
   après relance) ou `emballement coupé deux fois` (échec). Les autres causes d'arrêt (interruption,
   plafond…) priment.
7. **Coût** : l'usage des appels coupés est ajouté au tour (quota et relevé), à son tarif ; rien n'est
   caché.

### Réglages (`app.agent.api-features`)

| Clé | Variable | Défaut |
|-----|----------|--------|
| `runaway-enabled` | `APP_AGENT_RUNAWAY_ENABLED` | `true` (coupe-circuit) |
| `runaway-tokens` | `APP_AGENT_RUNAWAY_TOKENS` | `16000` |
| `runaway-tokens-per-second` | `APP_AGENT_RUNAWAY_TOKENS_PER_SECOND` | `150` |

Le détecteur ne s'arme que sur un appel en flux dont le plafond dépasse le seuil et dont le
raisonnement est actif (le cerveau rapide de Jarvis — raisonnement éteint, 600 jetons — n'est jamais
concerné).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Long raisonnement légitime puis texte avant le seuil | Jamais coupé, ensuite plus jamais surveillé |
| Outil en cours d'écriture (entrée JSON qui s'allonge) | Jamais coupé |
| Emballement pur | Coupé au seuil, relancé une fois |
| Relance qui s'emballe aussi | `AgentRunawayException` → message clair, usage compté, rien d'exécuté |
| Relance qui échoue en flux (refus, coupure) | Repli non streamé existant, usage de l'appel coupé ajouté |
| Coupe-circuit `false` | Comportement strictement antérieur (lecture du flux comprise) |
| Arrêt `max_tokens` (détecteur éteint ou seuil non atteint) | Inchangé + journal de diagnostic |

---

## Critères d'acceptation

- [ ] Flux simulé « emballement pur » (horloge simulée) : appel coupé au-delà de 16 000 jetons estimés.
- [ ] Flux simulé « raisonnement long puis texte » : jamais coupé ; « outil en cours » : jamais coupé.
- [ ] Intégration : premier flux emballé, second normal → tour rendu, 2 requêtes au corps identique,
      `runawayRelaunches = 1`, usage de l'appel coupé ajouté (entrée exacte, sortie estimée).
- [ ] Intégration : deux flux emballés → `AgentRunawayException`, 2 requêtes exactement (jamais 3).
- [ ] Coupe-circuit à `false` : aucune coupure.
- [ ] Forge : sur `AgentRunawayException`, réponse = message d'emballement, aucun outil exécuté,
      `stop_cause` = `emballement coupé deux fois` ; sur relance réussie, `emballement coupé et relancé`.
- [ ] Aucune consigne système (terminal, Jarvis) modifiée ; effort et plafond inchangés.
- [ ] Suite backend complète verte.

---

## Contraintes de validation

| Champ | Règle |
|-------|-------|
| `runaway-tokens` | > 0, sinon défaut 16 000 |
| `runaway-tokens-per-second` | > 0, sinon défaut 150 |

Seuil retenu : 16 000 = au-dessus du p99 des appels réussis (14 917), sous le plafond prod (32 768).
Avec 150 jetons/s, coupure après ≈ 107 s de silence total, contre ≈ 220 s pour atteindre le plafond.

---

## Plan de test minimal

- **Unitaires** `RunawayDetectorTest` : emballement pur ; raisonnement long légitime puis texte ; note
  de progression ; outil en cours ; sortie rapportée prioritaire sur l'estimation ; seuil non armé.
- **Intégration** `AnthropicAgentProviderTest` (serveur simulé + horloge simulée) : relance unique
  réussie (corps identiques, usage cumulé, compteur) ; double emballement → exception, 2 requêtes ;
  coupe-circuit ; flux normal inchangé.
- **Forge** : stub fournisseur levant `AgentRunawayException` → message clair, aucun outil, cause
  d'arrêt mesurée.
- **Isolation utilisateur** : sans objet — aucune donnée lue ni écrite hors du tour de l'utilisateur ;
  la mesure reste rangée par `user_id` comme avant.

---

## Impacts

- **Tables** : aucune migration — `atelier_turn_measures.stop_cause` (texte) reçoit deux libellés.
- **Endpoints** : aucun.
- **Composants** : `AnthropicAgentProvider`, `RunawayDetector` (nouveau), `AgentRunawayException`
  (nouveau), `AgentTurn` (+ `runawayRelaunches`), `AgentApiFeaturesProperties`, `application.yml`,
  `AtelierChatService` (message + cause d'arrêt).
- **Préoccupations transversales** : aucune (ni auth, ni tenant, ni plans/limites, ni routage).

## Hors périmètre

- Baisser l'effort, plafonner le raisonnement d'un appel qui avance, modifier une consigne.
- Couper un appel qui a déjà émis du texte, une note ou un outil (même s'il s'emballe ensuite).
- Le chemin non streamé (plafond 16 k, inchangé).
- Toute relance au-delà d'une seule par appel.
