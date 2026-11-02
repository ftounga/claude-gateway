# Mini-spec — [F-238 / SF-238-06] Guidage de l'agent, bascule, planche

## Identifiant
SF-238-06

## Feature parente
F-238 — Architecture en 3D, itération 0 « Le schéma 2D cadré » (cadrage §4.5, §4.8, §16, §17)

## Statut
done — visa PO **délégué** (A1) ; mesure de production à J+7 du déploiement de fin de vague

## Date de création
2026-11-02

## Branche Git
`feat/SF-238-06-guidage-bascule-planche`

---

## Objectif

Apprendre à l'agent à décrire des zones AWS cadrées (consigne fixe modifiée une fois, puis byte-stable), faire du
schéma cadré le moteur par défaut, et livrer la planche avant/après finale.

## Comportement attendu

### Cas nominal
1. **Consigne** `DiagramToolCatalog.GUIDE` (préfixe système mis en cache, F-134) : le paragraphe « UN GROUPE PEUT EN
   CONTENIR UN AUTRE » devient « ZONES AWS » : `kind` (11 valeurs), imbrication réelle Région › VPC › AZ ›
   sous-réseau (6 niveaux), refus d'une zone mal placée avec la raison, `kind` déduit et signalé à défaut, AZ de
   mêmes types ⇒ alignées, jamais de composant ajouté pour la symétrie, TB conseillé en multi-AZ, `bidirectional`.
   La forme de `spec` gagne `kind` et `bidirectional`. Le paragraphe drawio dit la même mise en page cadrée.
   **Rien de volatil** : la valeur des zones va dans l'appel d'outil (message), jamais dans la consigne.
2. **Schéma de l'outil** : la description de `spec` devient le littéral `SPEC_DESCRIPTION` (fixe).
3. **Empreintes épinglées** (SHA-256) du GUIDE et de `SPEC_DESCRIPTION` : toute modification future échoue le test
   tant qu'elle n'est pas décidée (et son coût de cache accepté).
4. **Bascule** : `app.diagrams.cloud-layout` vaut **`elk`** par défaut (yml et classe). Retour arrière sans
   reconstruction : `APP_DIAGRAM_CLOUD_LAYOUT=graphviz` (Graphviz gardé une version).
5. **Planche** `docs/features/F-238/captures/iteration-0/index.html` : moteur final sur 3-tiers (TB et LR), EKS,
   serverless et « tout », tableau des gardes G1–G6, puis l'essai du matin (avant).
6. **Gouvernance** : `review-checklist.md` — « tout rendu de schéma d'architecture passe G1–G6 » ; `DESIGN_SYSTEM.md`
   §24 « Conventions AWS des schémas d'architecture ».

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Le rendu cadré déçoit en production | `APP_DIAGRAM_CLOUD_LAYOUT=graphviz` (configmap backend, redémarrage) |
| G1/G2 échouent sur un schéma réel | SVG livré quand même ; ligne `CADRE_GARDE` dans CloudWatch (groupe `/aws/eks/legalcase-shared/claude-gateway`) |

## Critères d'acceptation
- [x] Préfixe byte-stable après modification (empreintes épinglées, deux définitions successives identiques, aucun
      motif volatil).
- [x] La consigne enseigne `kind`, l'imbrication réelle, l'alignement des AZ, TB, `bidirectional` ; plus aucune
      mention des « cadres frères ».
- [x] Planche publiée (5 captures du moteur final + tableau des gardes).
- [x] Visa : délégué (A1), défaut `elk`.
- [ ] En production, compteur G1/G2 à 0 échec sur 7 j — **à lire à J+7 du déploiement de fin de vague**
      (`fields @message | filter @message like /CADRE_GARDE/`).

## Périmètre — hors scope
Itération 1 (graphe conservé SF-238-07, après SF-237-03) et suivantes ; re-rendu des schémas existants (D3).

## Contraintes de validation
`cloud-layout` : `graphviz` | `elk`, défaut `elk`.

## Technique
- Endpoints / tables / migration : aucun.
- Fichiers : `DiagramToolCatalog.java` (GUIDE, `SPEC_DESCRIPTION`), `DiagramProperties.java` + `application.yml`
  (défaut `elk`), `DiagramToolCatalogTest.java` (nouveau), `HttpDiagramRendererTest.java` ; planche + 5 captures ;
  `review-checklist.md`, `DESIGN_SYSTEM.md` §24.

## Préoccupations transversales
- **Préfixe de cache / consigne : OUI** — composants : `DiagramToolCatalog.GUIDE` (injecté par
  `AtelierChatService` quand l'outil est ouvert) et la définition de `render_diagram` (`DiagramToolCatalog.definition`).
  Modifiés **une fois** ici ; empreintes épinglées. Un seul déchargement du cache au déploiement.
- **Livrables existants : OUI** — aucun re-rendu d'office (D3) : seuls les **nouveaux** schémas `cloud`/`drawio`
  sont cadrés ; pages, slides et documents déjà remis inchangés. Mermaid inchangé.
- Auth / tenant / plans / navigation : non concernés.

## Plan de test
- `DiagramToolCatalogTest` (5) ; `HttpDiagramRendererTest` (défaut elk, retour arrière graphviz) ;
  `AtelierChatServiceSystemPromptTest`, `DiagramToolExecutorTest`, `PresentationToolCatalogTest`,
  `GovernancePackageSeederTest` (non-régression de la consigne) ; suite Maven complète ; `npm test` du renderer.
- Isolation utilisateur : non applicable.

## Dépendances
SF-238-01 → 05 (mergées).

## Notes et décisions
- **A1 Visa PO délégué.** La planche est produite et versionnée ; la bascule est **réversible sans
  reconstruction** (réglage), aucune donnée n'est touchée, et les livrables déjà remis ne changent pas (D3). Règle
  de vague : gate réversible ⇒ décider par défaut et tracer. Le PO peut revenir à Graphviz d'un réglage.
- **A2** Pendant le retour arrière éventuel (`graphviz`), la consigne parle de `kind` : il est simplement ignoré par
  Graphviz (aucune erreur). Pas de seconde consigne : une seule modification du préfixe.
- **A3** Le critère « 0 échec G1/G2 sur 7 j » ne peut se lire qu'après le déploiement unique de fin de vague : tracé
  comme mesure J+7, pas comme condition de merge.
