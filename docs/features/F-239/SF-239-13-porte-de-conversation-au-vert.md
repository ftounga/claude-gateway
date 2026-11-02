# Mini-spec — F-239 / SF-239-13 — La porte de conversation au vert

## Identifiant
`F-239 / SF-239-13` · Feature parente `F-239` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-239-13-porte-de-conversation`

## Objectif
> Faire passer la porte du banc de conversation en prod (82,4 % le 02/11, run 50344afc) avec un pré-banc qui dit la
> même chose que la prod, une consigne v6 et des gardes ciblées sur les échecs observés.

## Constat (banc prod, staging-25dd7b6e, Sonnet 5.5, juge Opus 5.5)
82,4 % ; échecs : C03#2 « Les systèmes sont en ordre de marche » et C07#1 « malgré la pluie » (états inventés),
C09#2 « chantier 3 du portail Orion » (c'est le chantier 1), C14#1 et R03#1 (4 phrases dont une offre de détail
automatique), C30#3 « Le contexte ne m'en dit pas plus » (robotique). Coût moyen 0,1467 €/échange, 14 % de délégation.

## Pourquoi le pré-banc disait 94,1 %
1. **Ce n'était pas le même code** : le pré-banc JavaScript appelait le modèle directement — sans les gardes de la
   passerelle, sans la forme d'appel exacte (raisonnement `between_tools`, effort `low`, plafond de sortie), sans le
   contexte construit par le service.
2. **Variance** : un seul passage par mesure, alors que modèle et juge tirent au hasard ; quatre scénarios d'écart sur
   34 tiennent dans la variance d'un passage.
3. **Un délai trop court** révélé par le pré-banc fidèle : le premier jeton du cerveau rapide est attendu 3 s (calibré
   pour Haiku) ; Sonnet 5.5 (modèle de prod) dépasse parfois ce délai ⇒ délégation d'office à Opus — plus lent
   (≈ 8,7 s) et ≈ 1 € — avec une annonce vide (« Où en est le portail d'Orion ? », « Par Orion. » délégués ; 2
   délégations `timeout` en prod sur deux jours). Défaut porté à **8 s**.
4. **La porte comptait chaque scénario échoué comme un échec de porte** (SF-239-07) alors que la porte définie est
   « ≥ 90 % et zéros » : corrigé, le détail reste dans `scenarios`.
⇒ Le pré-banc devient `JarvisConversationBenchLiveTest` : **le code même de la prod** (`JarvisFastBrainBenchService`
jeu `CONVERSATION`) contre la vraie API, **N passages** (`JARVIS_LIVE_BENCH=N`), désactivé par défaut.

## Comportement attendu
- **Consigne du cerveau rapide v6** (fixe, versionnée) : jamais d'état inventé (systèmes, météo, humeur, ambiance),
  même pour être aimable ; numéros (chantier, étape, ticket) recopiés exactement avec leur sujet ; trois phrases au
  plus, **sans** offre de détail ni question de suivi automatiques ; ne jamais parler de « contexte », de « données »
  ni de ce qu'il sait ou ne sait pas — une information absente se délègue.
- **Gardes déterministes** (`SentenceGate`) : au plus **3 phrases** dites ; phrase méta (« le contexte… »,
  « mes notes… », « mes données… ») retirée ; offre de détail en fin de réponse (« Je peux détailler… », « Souhaitez-vous
  le détail… ») retirée ; « chantier N » absent tel quel du contexte et de la parole ⇒ fait non ancré (délégation,
  comme tout fait non ancré).
- **Délai du premier jeton** : 8 s par défaut (`APP_JARVIS_FAST_FIRST_TOKEN_TIMEOUT`, non défini en prod).
- **Au revoir / bonne soirée** : la formule est rendue telle quelle, sans « Bonjour » ni commentaire sur l'heure.
- **Porte** : ≥ 90 % de scénarios réussis et zéro délégation inutile / formule robotique / fait non ancré / action
  non déléguée ; un scénario échoué pour une autre raison (sens, longueur) reste dans le détail du rapport.
- **Banc persistant** : les passages du banc sont rangés en base (`jarvis_bench_runs`, `user_id`, rapport JSON) ;
  le `GET` répond quel que soit le pod (aujourd'hui l'état vit dans un seul pod ⇒ 404 une fois sur deux).

## Mesure (pré-banc fidèle, Sonnet 5.5, juge Opus 5.5, 34 scénarios / 43 tours)
| Version | Passage 1 | Passage 2 | Porte | €/échange | Délégation |
|---|---|---|---|---|---|
| v5 (en prod) | 91,2 % — rouge (2 délégations inutiles sur délai, 1 trop long) | — | rouge | 0,1923 | 18,6 % |
| v6 + gardes | 94,1 % — rouge (« Par Orion. » délégué sur délai) | 97,1 % (« Bonne soirée » ⇒ « Bonjour ») | rouge (définition) | 0,17 / 0,15 | 16 / 14 % |
| **v6 + gardes + délai 8 s + porte corrigée** | **100 %** | **100 %** | **verte** | **0,1467** | **14 %** (6/43, toutes attendues) |
Le coût restant est celui des 6 délégations métier attendues (≈ 0,98 € l'une) ; le cerveau rapide seul ≈ 0,01 €.

## Critères d'acceptation
- [ ] ≥ 2 passages du pré-banc fidèle à ≥ 90 % **et** porte au vert (zéro formule robotique, fait non ancré, action
      non déléguée, délégation inutile) avant merge ; variance rapportée.
- [ ] `GET /api/admin/jarvis/fast-brain/bench/{id}` rend le rapport depuis la base (autre pod), sous `user_id`.
- [ ] Gardes testées (3 phrases, méta, offre de détail, numéro de chantier).

## Hors périmètre
Changer de modèle ; changer la grille du juge.

## Technique
`JarvisFastBrainPrompt` (v6), `JarvisFastGuards` (méta, offre de détail, chantier), `JarvisFastBrainService`
(`SentenceGate` : plafond 3 phrases), `JarvisFastBrainBenchService` + `JarvisBenchRun(Repository)` + migration
`239-jarvis-bench-runs.xml`, `JarvisConversationBenchLiveTest`.

## Plan de test
Unitaires (gardes), service (plafond, retraits), intégration (rapport relu depuis la base, isolation), pré-banc live ×2.
