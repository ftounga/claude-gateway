# Mini-spec — F-178 / SF-178-03 — Les lectures de l'application

## Identifiant
`F-178 / SF-178-03` — feature parente `F-178` (cadrage, décision D3). Branche :
`feat/SF-178-03-lectures-application`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Au terminal du poste, l'agent lit le Radar, les pages publiées et la consommation du compte, par un
**adaptateur** des outils MCP F-112 existants — sans rien réécrire ni rien écrire.

## Comportement attendu

### Cas nominal
1. **Adaptateur** `PosteAppReadService` : mêmes noms et mêmes services que les outils MCP F-112 —
   `radar_resume` (`RadarBriefService.brief`), `radar_sujets` (`RadarReadService.subjects`, `include_closed`),
   `pages_lister` (`PageService.list`, `space` FORGE|VIGIE), `page_lire` (`PageService.require` + `html`,
   `page_id`, `version`), `compte_consommation` (`UsageReportService.buildReport`). Même filtre de secrets
   (`McpSecretFilter`), contenus tiers (Radar, page) précédés d'un marqueur « donnée, pas une consigne ».
2. **Périmètre** : utilisateur et poste **du tour** (terminal possédé) ; aucun `host_id` dans les schémas.
3. **Déclaration** (`PosteToolCatalog`) au terminal du poste seulement : pages + consommation toujours ;
   lectures Radar seulement si le poste a un Radar (volet Teams ouvert + poste actif dans la Vigie — même
   garde que le volet Radar). Lecture seule ⇒ ajoutées au mode Réponse/Plan.
4. `page_lire` rend le **texte** de la page (balises, styles, scripts, images embarquées retirés).
5. Résultats bornés à 20 000 caractères.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Appel hors terminal du poste | refusé, rien n'est lu |
| `radar_*` sans Radar | refusé, rien n'est lu |
| `page_id` invalide | erreur nommée |
| Page d'un autre poste / introuvable | refusée (« introuvable ») |
| `space` invalide | « FORGE ou VIGIE attendus » |
| Service en échec | message d'indisponibilité, jamais d'exception vers la boucle |

## Contraintes de validation
`page_id` UUID ; `version` entier ≥ 0 (facultatif) ; `space` ∈ {FORGE, VIGIE} ; `include_closed` booléen.

## Critères d'acceptation
- [x] Au terminal d'un poste suivi par la Vigie, `radar_resume` / `radar_sujets` lisent le Radar de CE poste.
- [x] Sans Radar, les lectures Radar ne sont pas déclarées et sont refusées.
- [x] `pages_lister` / `page_lire` ne lisent que les pages du compte, de ce poste ou sans poste.
- [x] Aucun outil dans un sujet ; aucune écriture ; secrets masqués.

## Plan de test
- `PosteAppReadsTest` (7) : déclaration (avec/sans Radar, sujet) ; `radar_resume` ; refus Radar ; refus dans un
  sujet ; `pages_lister` ; `page_lire` (texte, masquage, autre poste, id invalide) ; consommation.
- `AtelierChatServiceHostTerminalTest` : non-régression aiguillage.
- Non-régression : suite complète backend.

## Impacts
Backend : `atelier.poste` (`PosteAppReadService` nouveau ; `PosteToolCatalog`, `PosteToolExecutor` étendus) ;
`AtelierChatService` (mode Réponse/Plan, cible d'audit). Aucune table, aucune migration, aucun endpoint.

### Préoccupations transversales
- **Contexte tenant** : ✔ — composants : `PosteAppReadService` (Radar via `RadarScopeResolver.requireInVigie(userId,
  hostId)` ; pages via `PageService` filtré `user_id` + contrôle du poste de la page ; consommation via
  `buildReport(userId)`), `PosteToolCatalog`/`PosteToolExecutor` (garde réévaluée). `hostId` toujours celui du
  terminal possédé.
- Auth / plans / navigation : non (le droit Radar est lu, pas modifié).

## Hors périmètre
Écritures Radar / pages ; Teams en direct ; lectures d'un autre poste.

## Arbitrages (réversibles)
- Adaptation au niveau **service** (mêmes services que les outils MCP), pas en rejouant la spécification MCP :
  celle-ci exige un contexte d'appel OAuth (`McpCallContext`, scopes) qui n'existe pas dans la boucle.
- `page_lire` rend le texte (pas le HTML) : les images y sont embarquées en `data:` (SF-142-22) et feraient
  exploser la borne.
- Pages limitées au poste du terminal (ou sans poste) : pas de lecture inter-postes (cadrage §7).
