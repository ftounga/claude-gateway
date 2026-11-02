# Mini-spec — F-240 / SF-240-02 — Ce qu'il dit : une idée, 25 mots

## Identifiant
`F-240 / SF-240-02` · Feature parente `F-240` « Jarvis va à l'essentiel » · Statut `in-review` · 2026-11-02 ·
Branche `feat/SF-240-02-une-idee-25-mots`

## Objectif
> Que la ligne dite tienne en une idée d'environ 25 mots, verdict d'abord, suivie au plus d'une invitation ciblée —
> sans toucher au raisonnement d'Opus ni à la consigne système du terminal.

## Comportement attendu
### Cas nominal
- **Consigne orale v2** (`JarvisOralNote.TEXT`, `VERSION = "2"`, figée, **dans le message**) : ligne 🎙 = **une phrase,
  une idée, ≈ 25 mots (30 au plus)**, verdict d'abord, jamais le chemin parcouru, ni liste ni chiffres en cascade (D1) ;
  annonce avant outils **≤ 12 mots** (D3) ; **essentiel en 3 à 5 lignes** en mode Jarvis, tout point important y figure
  (D4) ; **invitation ciblée** facultative « ↪ … ? » ≤ 15 mots qui **nomme** le point, jamais générique, jamais après
  « Vous confirmez ? » (D6). Les règles existantes restent (vouvoiement, « Monsieur », confirmation, message préparé,
  `email_me`, `🖥 montrer:`) ; la ligne `🖥 montrer:` mal jointe (blancs en rafale dans le texte) est réparée.
- **Écran** :
  - **garde de longueur** (D5) : une ligne 🎙 de plus de **35 mots** n'est dite que par sa **première phrase** (au fil de
    l'écriture : la 2e phrase n'est pas dite ; en fin de tour : `oralLine().cut`) ; la coupe est mesurée (`oralCut`) ;
  - **invitation** (D6) : la ligne « ↪ » est dite **après** la phrase 🎙 (dès que sa ligne est close au fil de
    l'écriture, sinon en fin de tour ; après la fin dite d'une délégation), **écartée** si elle est générique
    (`isGenericInvitation`, même règle que la garde `isDetailOffer`, élargie), sans question, > 20 mots, ou si la
    phrase demande une confirmation ; elle est **masquée du détail** comme « 🖥 » ; mesurée (`invitation`).
- `JarvisReplies.oralLine` ignore la ligne « ↪ » (encart « Pendant votre absence », contexte du cerveau rapide).
- **Banc de la parole** (D12) : `POST /api/admin/jarvis/oral-bench` (ADMIN, 202 + `runId`) joue **8 scénarios de
  délégation** (point client, état d'un chantier, diagnostic, conseil A/B, ticket, réponse longue avec tableau, action
  qui engage, point annexe important) sur Opus 5.5 **sans outils**, avec la **consigne système réelle du terminal** d'un
  sujet du compte et la **consigne orale réelle** ; lecture déterministe (mots de la ligne 🎙, invitation, lignes de
  l'essentiel, confirmation) + juge (verdict d'abord, chemin raconté) ; **plafond 3 €** par passage ; rapport en base
  (`jarvis_oral_bench_runs`) lu par `GET /api/admin/jarvis/oral-bench/{runId}` sous `user_id`. Pré-banc fidèle
  `JarvisOralBenchLiveTest` (code de prod, désactivé sans clé).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Banc lancé par un non-ADMIN | Refus | 403 |
| Aucun sujet au compte / sujet d'un autre compte | Refus explicite | 400 |
| Rapport d'un autre compte ou inconnu | Introuvable | 404 |
| Plafond de 3 € atteint en cours de passage | Arrêt avant l'appel suivant, échec de porte « plafond atteint » | — |
| Réponse sans ligne 🎙 / juge muet | Scénario compté en échec de porte | — |
| Ligne 🎙 de 40 mots en 2 phrases | Seule la 1re phrase est dite, `oralCut = true` | — |
| Invitation générique / après « Vous confirmez ? » | Jamais dite | — |

## Critères d'acceptation
- [ ] Banc de la parole : p90 ≤ 30 mots, médiane ≈ 25, verdict d'abord ≥ 7/8, zéro invitation générique, invitation
  présente sur « point annexe », absente sur « confirmation » (porte calculée par le banc ; passage live à faire avec
  une clé, voir Notes).
- [ ] Banc CONVERSATION ≥ 90 % sans zéro : **non concerné ici** (le cerveau rapide n'est pas modifié dans cette SF).
- [ ] Consigne système du terminal identique à l'octet (test existant `AtelierChatServiceVoiceTurnTest`).
- [ ] Consigne orale identique d'un tour à l'autre, aucune donnée variable (test).
- [ ] En prod à J+3 : `spoken_words` p90 ≤ 30 (mesure SF-240-01, après déploiement).

## Périmètre — hors scope
La doctrine système du terminal ; le raisonnement et les outils d'Opus ; l'écran en étages (SF-240-03) ; « dis-m'en
plus » (SF-240-04) ; la consigne du cerveau rapide (voir Notes : reportée en SF-240-04).

## Contraintes de validation
| Champ | Obligatoire | Valeurs | Normalisation |
|---|---|---|---|
| `workspaceId` (banc) | Non | UUID d'un sujet du compte | défaut : le sujet le plus récent |
| Ligne 🎙 dite | — | ≤ 35 mots sinon 1re phrase | — |
| Ligne « ↪ » dite | — | finit par « ? », ≤ 20 mots, non générique, pas après confirmation | nettoyée du balisage |

## Technique
- Endpoints créés : `POST /api/admin/jarvis/oral-bench`, `GET /api/admin/jarvis/oral-bench/{runId}` (ADMIN).
- Table créée : `jarvis_oral_bench_runs` (`id`, `user_id`, `started_at`, `status`, `report_json`) — migration
  `240-jarvis-oral-bench-runs.xml` (PostgreSQL + H2, rollback = retrait) ; `ARCHITECTURE_CANONIQUE.md` à jour.
- Code : `JarvisOralNote` (v2, `INVITATION_MARK`), `JarvisReplies`, `AtelierChatService.terminalSystemPrompt`
  (lecture, `requireOwned` d'abord), `jarvis/oralbench/*` (banc) ; écran : `jarvis-oral.ts` (`oralLine`, `guardOral`,
  `isGenericInvitation`, `invitationOf`, `acceptInvitation`, `stripInvitationLines`, `OralStream` garde + invitation),
  `JarvisComponent` (invitation dite, masquée, mesurée), `Announcement` (`cut`, `invitation`).
- Fournisseur via `AiAgentProvider` ; clé du compte via BYOK, jamais loggée ; coût inscrit au journal (annexe).

## Plan de test
- Unitaires : `JarvisRepliesTest` (« ↪ » ignoré ; consigne v2 figée, bornée, sans blancs en rafale, rien de volatil) ;
  `JarvisOralBenchTest` (jeu, lecture, invitations génériques, porte) ; `JarvisOralBenchServiceTest` (sans outils,
  consigne réelle, juge, plafond) ; specs `jarvis-oral` (coupe à 35 mots, invitation lue puis masquée, générique
  écartée, supprimée après « Vous confirmez ? », au fil de l'écriture).
- Intégration : `JarvisOralBenchApiIntegrationTest` (403, 400, 404, rapport relu sous `user_id`) ;
  `AtelierChatServiceVoiceTurnTest` (consigne dans le message, jamais dans le système).
- Isolation : le banc prend un sujet **du compte** (400 sinon) ; ses passages sont lus sous son `user_id` (test).
- Écran : spec du composant (invitation dite après la phrase, absente du détail, mesurée).
- Pré-banc fidèle : `JarvisOralBenchLiveTest` (clé requise).

## Dépendances
SF-240-01 (mergée, #1534) ; SF-237-09 (mergée). Aucune question ouverte.

## Notes et décisions
- **D11 mesuré** (relevé lecture seule des 3 derniers bancs CONVERSATION de prod, 02/11) : p90 des mots du cerveau
  rapide **35 à 37** (> 30). **Arbitrage** : la consigne du cerveau rapide n'est **pas** modifiée ici ; la borne
  « ≈ 25 mots » rejoint la ligne-marque `⤵` de SF-240-04 dans **une seule** nouvelle version (v9) — un seul
  changement de préfixe caché, un seul passage du banc CONVERSATION.
- **Banc live non joué dans cette SF** : aucune clé fournisseur dans l'environnement de l'agent (les secrets de prod ne
  sont pas extraits). Le banc se lance en prod après déploiement (`POST /api/admin/jarvis/oral-bench`) ou en pré-banc
  avec une clé. Réversible : la consigne est un texte figé.
- **Table à part** plutôt que `jarvis_bench_runs` : rapports de forme différente, élagage indépendant (réversible).
