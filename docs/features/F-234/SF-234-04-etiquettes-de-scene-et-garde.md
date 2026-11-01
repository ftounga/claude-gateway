# Mini-spec — F-234 / SF-234-04 — Étiquettes de scène (passerelle) et garde du registre (D5, D17)

## Identifiant
`F-234 / SF-234-04`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage `CADRAGE-F-234-ecran-vivant-jarvis.md`, vague A « socle »)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-04-etiquettes-de-scene`

---

## Objectif

> Les outils métier que l'écran montrait sans cible (chantier, étape, attente, passation, skill, veille, document)
> et les outils serveur du fournisseur (web) reçoivent une **étiquette lisible** dans l'événement `action` — pour
> l'écran seulement — et une garde empêche de merger un outil sans scène au registre.

## Comportement attendu

- **`SceneStepLabels.label(tool, input)`** (nouveau, pur) appelé par le cas `default` de `stepFor` ; si `null`,
  l'étape garde la cible d'audit comme avant :

| Outil | Étiquette |
|---|---|
| `update_plan_step` | `chantier 3 · étape 2 · faite` (chantier si fourni ; état FAIT/VERIFIE/ECHEC/BLOQUE/A_FAIRE en clair) |
| `update_sub_steps` | `chantier N · étape N · sous-étapes` |
| `set_subject_plan` | `chantier N · plan en N étapes` |
| `valider_plan`, `confirmer_diagnostic` | `chantier N` |
| `lancer_chantier_autonome` | `chantier N · jusqu'au blocage` |
| `submit_diagnosis` | `chantier N · confiance élevée` (jamais le diagnostic) |
| `record_blocker` | `Zahi · validation` / `Karim · message` (jamais la description) |
| `update_blocker` | `Karim · demandé` / `à refaire` |
| `close_blocker` | `levée` / `annulée` |
| `ouvrir_sujet`, `create_subject` | nom du sujet |
| `skill` | nom du skill |
| `gouvernance_proposer` | `type · nom` |
| `veille_poser` | `plateforme · nom` |
| `routine_proposer` | `nom · rythme` |
| `signaler_besoin` | `type · titre` |
| `build_presentation`, `build_document`, `build_spreadsheet`, `render_diagram` | nom de fichier |

  Bornée à 120 caractères.
- **Étape `web`** : après chaque réponse du fournisseur, une étape `action` `{type:"web", path}` par
  `server_tool_use` — la **requête** d'un `web_search`, le **domaine seul** d'un `web_fetch`. Les résultats ne font
  pas d'étape.
- **Terminal** : `chat-steps.ts` nomme l'étape `web` (« web · requête ») ; les autres étiquettes passent par le cas
  par défaut (l'étiquette remplace l'ancien nom brut de l'outil, qui reste dans le badge `tool`).
- **Inchangés (gardes explicites)** : `auditTarget` (journal d'audit), consigne système, descriptions d'outils,
  messages rejoués ⇒ **préfixe de cache byte-stable**.
- **Garde du registre** `JarvisSceneRegistryGuardTest` : lit le source de production, résout chaque
  `new AgentTool(…)` (littéral, constante, constante d'énumération `OfficeFormat.X.tool()`), et échoue si un outil
  n'est ni dans `tools` ni couvert par un `prefixes` de `frontend/src/app/jarvis/scenes/jarvis-scene-registry.json`.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Entrée sans le champ attendu | étiquette partielle, ou `null` ⇒ cible d'audit comme avant |
| URL illisible pour `web_fetch` | étape `web` sans cible |
| Outil ajouté sans entrée de registre | **le test échoue** avec la liste des outils manquants |
| Constante d'outil non résoluble | le test « la lecture trouve bien les outils » échoue (pas de passage à vide) |

## Critères d'acceptation
1. `update_plan_step` ⇒ `chantier 3 · étape 2 · faite` ; `record_blocker` ⇒ personne + catégorie.
2. `web_fetch` ⇒ étape `web`, domaine seul ; `web_search` ⇒ la requête.
3. `auditTarget` inchangé pour `update_plan_step`, `record_blocker`, `ouvrir_sujet` (test).
4. Préfixe byte-stable : `AtelierChatServiceSystemPromptTest` et la suite complète restent verts (aucune consigne ni
   description touchée).
5. Un outil ajouté sans entrée de registre fait échouer la garde.
6. Terminal sans régression (`chat-steps.spec.ts`).

## Contraintes de validation
Étiquette ≤ 120 caractères ; aucun champ de contenu (description, diagnostic, consigne, corps) n'est lu.

## Plan de test
- Unitaires : `SceneStepLabelsTest` (5), `JarvisSceneRegistryGuardTest` (3), `chat-steps.spec.ts` (+1).
- Non-régression : `AtelierChatServiceTeamsToolsTest` (+1 : audit inchangé), suite backend complète (préfixe,
  boucle d'agent, parallélisme).

## Impacts
- **Tables / endpoints** : aucun (champ `path` de l'événement SSE `action` existant, additif).
- **Composants** : `AtelierChatService.stepFor` (cas `default`), boucle d'agent (émission `web`), nouveau
  `atelier/scene/SceneStepLabels`, `chat-steps.ts`.

## Préoccupations transversales
- Auth / tenant / plans / navigation : non.
- **Préfixe de cache / consigne** : non touchés — garde = suite `AtelierChatServiceSystemPromptTest` + suite complète.
- **Journal d'audit** : non touché — garde = test dédié.

## Arbitrages (réversibles)
- `update_plan_step` sans `chantier` explicite ⇒ « étape 2 · faite » (pas de lecture en base dans `stepFor` pour
  retrouver le chantier au focus : l'écran le déduit du sujet courant via l'univers).
- La garde lit le **source** plutôt que les catalogues instanciés : les outils sont assemblés selon le sujet, le
  poste et les droits ; seul le source les voit tous.
- L'étape `web` part **après** la réponse du fournisseur (les outils serveur s'exécutent chez lui) : seul instant où
  la passerelle les connaît.

## Hors périmètre
Les scènes dédiées qui consomment ces étiquettes (vagues B/C) ; toute modification de la consigne ou du journal.
