# Mini-spec — F-235 / SF-235-02 — Le contexte compact (D5, D6)

## Identifiant
`F-235 / SF-235-02` · Feature parente `F-235` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-235-02-contexte-compact`

## Objectif
> Donner au cerveau rapide un contexte utile, vrai, horodaté et borné — construit côté serveur, sans appel modèle,
> sous `user_id` — avec une mémoire de conversation courte et un pré-chauffage du cache.

## Comportement attendu
### Cas nominal
`JarvisFastContextBuilder.build(userId, workspace, chantier, heardBeforeCut)` rend un texte **dans le message** (jamais
dans la consigne système), blocs dans cet ordre, chacun titré et horodaté (« état à 14:32 ») :
- (a) **Maintenant** (date, heure Europe/Paris), sujet courant (nom, id), chantier courant ;
- (b) **Univers** (`JarvisUniverseService`) : clients (connecté ou non) → sujets (id) → chantiers ouverts (numéro,
  titre, phase, avancement `done/total`, étape en cours, « attend : » personnes) ; interlocuteurs (noms, rôle) ;
- (c) **Attentes ouvertes** du sujet courant (`TerminalActionService.list`, statut) et **À envoyer (n)** du compte avec
  sujet/destinataire/objet (`JarvisReturnService.toSend`) ;
- (d) **Délégations en cours** (sujet, libellé, depuis) — registre mémoire `JarvisDelegationRegistry` alimenté par
  `/converse` ; une délégation reste « en cours » tant que son fil a un tour vivant (`LiveTurnRegistry`) ou pendant
  2 min après son lancement ; au plus 8, 2 h au plus ;
- (e) **Derniers échanges du fil** du sujet courant (fil commun + chantier courant) : 6 derniers messages, chacun
  ≤ 400 caractères ; une réponse d'Opus réduite à sa ligne 🎙 (`JarvisReplies.oralLine`) + son début ;
- (f) **Alertes de veille** des dernières 24 h (phrase F-230, au plus 5) ;
- (g) « **Jarvis a été coupé après** : … » si `heardBeforeCut`.
Chaque bloc a son plafond de caractères ; le total est borné à **20 000 caractères** (≈ 6 000 jetons). Un bloc en
échec (best-effort) est omis, jamais d'exception.

**Mémoire de session** (`JarvisFastMemory`) : les échanges rapides du compte des 30 dernières minutes, au plus 12,
mots seuls (parole / réponse ou annonce), rejoués en messages user/assistant **avant** le dernier message ; le contexte
n'est attaché qu'au **dernier** message.

**Pré-chauffage** `POST /api/jarvis/converse/warm` : un appel `max_tokens: 0` non streamé (consigne + outil), au plus
toutes les 4 min par compte ; compté au coût ; 503 si éteint, 204 sinon (y compris « trop tôt »).

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| Univers / attentes / alertes / fil en échec | bloc omis, le reste part | — |
| Contexte au-delà des bornes | blocs coupés (« … ») puis total coupé | — |
| `warm` éteint | rien ne part | 503 |
| `warm` < 4 min après le précédent | rien ne part | 204 |
| Mémoire > 30 min | oubliée | — |

## Critères d'acceptation
- [x] Les blocs a-g apparaissent avec leur horodatage ; aucun ne contient une donnée d'un autre compte.
- [x] Le contexte ne dépasse jamais 20 000 caractères ; chaque message du fil ≤ 400 caractères.
- [x] La consigne système est **identique octet pour octet** d'un appel à l'autre, quel que soit le contexte.
- [x] La mémoire rejoue au plus 12 échanges de moins de 30 min, puis le dernier message porte le contexte.
- [x] Le pré-chauffage part au plus une fois par 4 min, `max_tokens = 0`.
- [x] Construction sans appel modèle (test : aucun appel fournisseur pendant la construction).

## Plan de test
- Unitaires `JarvisFastContextBuilderTest` : blocs, bornes, horodatage, coupure, bloc en échec omis, isolation (dépôts
  appelés avec le `user_id` du principal seulement).
- Unitaires `JarvisFastMemoryTest` : 12 au plus, 30 min, comptes séparés.
- Unitaires service : messages rejoués + contexte sur le dernier ; consigne identique avec deux contextes différents ;
  pré-chauffage borné.
- Intégration : `warm` 503 éteint.

## Tables / endpoints / composants
Aucune table. Endpoint `POST /api/jarvis/converse/warm`. `jarvis/fastbrain/JarvisFastContextBuilder`,
`JarvisFastMemory`, `JarvisDelegationRegistry` (nouveaux), `JarvisFastBrainService`, `JarvisFastBrainController`.

## Préoccupations transversales
- **Contexte tenant** : le constructeur appelle `JarvisUniverseService.universe(userId)`,
  `TerminalActionService.list(userId, workspaceId, …)`, `JarvisReturnService.toSend(userId, null)`,
  `JarvisWatchAlertRepository.findByUserIdAndCreatedAtGreaterThanEqual(userId, …)`,
  `AtelierMessageRepository.findByWorkspaceIdAndUserId…(workspaceId, userId, …)`, `LiveTurnRegistry.find(userId, …)` —
  **tous** sous le `user_id` du principal ; mémoire et registre clefés par `user_id`.

## Hors périmètre
Garde-fous de phrase (SF-235-03) ; persistance de la mémoire (elle vit en mémoire : un redéploiement l'oublie — sans
gravité, les échanges rapides sont aussi dans le fil, SF-235-04).

## Arbitrages (par défaut, réversibles)
- Délégations en cours tenues **en mémoire** (registre) plutôt qu'en base : elles ne valent que pendant le tour Opus,
  dont l'état vivant est lui-même en mémoire (`LiveTurnRegistry`).
- Borne en **caractères** (20 000) plutôt qu'un comptage de jetons (appel modèle interdit, ≤ 150 ms).
