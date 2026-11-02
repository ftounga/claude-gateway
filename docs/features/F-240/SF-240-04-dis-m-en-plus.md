# Mini-spec — F-240 / SF-240-04 — « Dis-m'en plus » descend d'un étage

## Identifiant
`F-240 / SF-240-04` · Feature parente `F-240` « Jarvis va à l'essentiel » · Statut `in-review` · 2026-11-02 ·
Branche `feat/SF-240-04-dis-m-en-plus`

## Objectif
> Descendre d'un étage à la voix dans la réponse d'Opus **déjà calculée** — essentiel, puis détail section par
> section — sans aucun appel à Opus.

## Comportement attendu
### Cas nominal
- **Sur l'appareil, d'abord** (D8) : `isMoreCommand` (liste fermée normalisée, patron `isRepeatCommand` :
  « dis-m'en plus », « dites-m'en plus », « développe », « détaille », « plus de détails », « encore », « la suite »,
  « et ensuite »…, « Jarvis » / « Monsieur » ignorés ; **« continue » exclu**), testé **avant** tout appel au cerveau
  rapide ; un « oui », « vas-y », « dis-le » dans les **30 s** qui suivent une invitation `↪` vaut « dis-m'en plus »
  (consommé une fois).
- **D7 — la lecture** (`MoreReader`, sur la réponse montrée) : étage 1 → 2 : dire l'essentiel (lignes nettoyées,
  ponctuées, ≤ 80 mots) ; 2 → 3 : déplier le détail et en dire la première section (par titre, sinon par paragraphe ;
  ≤ 60 mots ; tableau / code non lus : « Le tableau est à l'écran. ») ; chaque « encore » dit la section suivante ;
  à la fin : « C'est tout pour cette réponse. » L'étage atteint est mesuré (`depth_reached`, `depth_via = voix`).
- **D9 — quelle réponse** : la dernière réponse d'Opus montrée dans la session (tour direct ou fin de délégation) ;
  sinon (rechargement) la dernière réponse d'Opus du **fil courant** relue par l'historique déjà exposé au terminal
  (`GET /api/workspaces/{id}/chat`, filtré `user_id`, aucun nouveau point d'accès) ; sinon tour normal.
- **Cerveau rapide — consigne v9** (figée, une seule nouvelle version) : section « La suite d'une réponse d'Opus » —
  formulation libre et réponse d'Opus dans les derniers échanges ⇒ **la seule ligne `⤵`** (jamais déléguer) ; sans
  réponse d'Opus, répondre sans déléguer ; « vérifie » reste une délégation. **+ D11** (mesuré en SF-240-02 : p90
  35-37 mots) : « une ou deux phrases, environ 25 mots en tout (30 au plus) », point demandé en deux phrases.
- **Serveur** : la porte des phrases relaie `⤵` telle quelle (aucune garde, jamais une répétition creuse) et ne dit
  plus rien après ; **garde D8** : demande de développement (courte, sans chiffre ni identifiant, pas « vérifie ») ou
  `⤵` écrit, **et** réponse d'Opus dans le contexte ⇒ une délégation (choisie ou d'office) devient `⤵` ; `⤵` n'est
  pas tracée dans le fil. Au banc (à blanc), la délégation forcée ne lit plus la mémoire de session.
- **Écran** : `⤵` jamais dite ; elle déclenche la descente (sinon « Je n'ai rien de plus à développer pour l'instant,
  Monsieur. ») ; la réponse d'Opus reste affichée tant que le cerveau rapide n'a rien répondu d'autre.
- **Banc CONVERSATION** : 4 scénarios (`SUITE_F240`) — « dis-m'en plus » après une délégation et « tu peux
  développer le point Helios ? » ⇒ `descendre` ; « et ensuite ? » sans réponse d'Opus ⇒ `repondre` ; « vérifie »
  ⇒ `deleguer`. Contexte propre au scénario (derniers échanges avec une réponse d'Opus). Une descente est jugée sans
  modèle (rien n'est dit par le cerveau rapide) et ne coûte aucun tour Opus.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| « Dis-m'en plus » sans réponse à l'écran ni dans le fil | Tour normal du cerveau rapide (qui ne délègue pas) | — |
| `⤵` sans réponse à lire | « Je n'ai rien de plus à développer pour l'instant, Monsieur. » | — |
| « Détaille le ticket LDIC-223 » | Nouvelle demande : la délégation reste | — |
| « Vérifie » après une réponse | Délégation forcée (inchangée) | — |
| « Oui » hors de la fenêtre de 30 s ou sans invitation | Parole ordinaire | — |
| Historique indisponible | Tour normal | — |

## Critères d'acceptation
- [ ] « Dis-m'en plus » dit l'essentiel ; « encore » déplie et dit la première section ; **aucune requête vers Opus**
  ni vers le cerveau rapide (test).
- [ ] Un « oui » à l'invitation dit l'essentiel.
- [ ] Une formulation libre passe par `⤵`, sans délégation (tests serveur et écran).
- [ ] « Vérifie » délègue toujours.
- [ ] Banc CONVERSATION ≥ 90 % sans zéro avec les 4 scénarios (passage live à faire, voir Notes).
- [ ] La réponse relue après un rechargement provient du fil de l'utilisateur seulement.

## Périmètre — hors scope
Développer une réponse du cerveau rapide lui-même (tour normal) ; résumer le détail par IA ; la voix (F-233).

## Contraintes de validation
| Champ | Obligatoire | Valeurs | Normalisation |
|---|---|---|---|
| Commande « plus » | — | liste fermée, phrase entière | sans accents, ponctuation, « Jarvis » / « Monsieur » |
| Demande de suite (serveur) | — | ≤ 8 mots, sans chiffre, motif « développe / détaille / dis-m'en plus / la suite… » | normalisée |
| Section dite | — | ≤ 60 mots | phrases entières, sinon coupe « … » |

## Technique
- Aucun endpoint, aucune table, aucune migration (historique : `GET /api/workspaces/{id}/chat` existant).
- Serveur : `JarvisFastBrainPrompt` (v9, `MORE_MARK`), `JarvisFastBrainService` (porte, garde
  `moreWithoutDelegation`, `isMore`, pas de trace, `forced` sans mémoire au banc), `JarvisFastGuards`
  (`isMoreRequest`, `hasOpusAnswer`), `JarvisConversationBench` (route `descendre`, contexte de scénario, verdict
  déterministe), `JarvisFastBrainBenchService`, `conversation-scenarios.json` (+4).
- Écran : `jarvis-more.ts` (nouveau : `MoreReader`, `isMoreCommand`, `isMoreAssent`, `isMoreMark`),
  `JarvisComponent` (`descend`, `lastOpusReply`, réponse montrée et sa mesure gardée).

## Plan de test
- Unitaires serveur : `JarvisFastGuardsTest` (demandes de suite, réponse d'Opus) ; `JarvisFastBrainServiceTest`
  (`⤵` relayée, rien après, pas de trace, 2e `⤵` non retirée ; garde sans délégation ; nouvelle demande et « vérifie »
  délèguent ; banc sur contexte figé ; consigne v9) ; `JarvisConversationBenchTest` (4 scénarios, descente sans
  modèle) ; `JarvisFastBrainBenchServiceTest`.
- Écran : `jarvis-more.spec.ts` (variantes, accents, « Jarvis », « continue » exclu ; 1 → 2 → 3 → sections → fin ;
  60 mots) ; composant (aucun appel Opus ni cerveau rapide, « oui » à l'invitation, relecture du fil, `⤵` jamais dite).
- Isolation : relecture par l'historique existant (filtré `user_id` côté passerelle) ; aucune réponse d'un autre
  compte (aucun nouveau point d'accès).
- Pré-banc fidèle CONVERSATION : `JarvisConversationBenchLiveTest` (clé requise).

## Dépendances
SF-240-02 (invitation `↪`, #1535) et SF-240-03 (étages, #1536) mergées ; SF-237-08 mergée. Aucune question ouverte.

## Notes et décisions
- **Consigne du cerveau rapide changée une fois** (v8 → v9) avec D11 (longueur) et `⤵` réunis : une seule nouvelle
  entrée de cache, un seul passage de banc.
- **Porte de déploiement** : le banc CONVERSATION (38 scénarios) **doit** être passé au vert avant de déployer Jarvis
  (`POST /api/admin/jarvis/fast-brain/bench {"set":"CONVERSATION"}` ou pré-banc avec une clé) — il n'a pas pu être
  joué par l'agent (aucune clé fournisseur dans son environnement).
- La réponse d'Opus reste à l'écran pendant un échange rapide et n'est remplacée que par une vraie réponse (sinon
  « ⤵ » n'aurait plus rien à lire) ; la mesure de son tour n'est déclarée qu'à son remplacement (ou sortie, 10 min).
