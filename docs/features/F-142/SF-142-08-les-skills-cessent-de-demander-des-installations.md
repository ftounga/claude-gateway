# Mini-spec — F-142 / SF-142-08 — Les skills cessent de demander des installations

## Identifiant
`F-142 / SF-142-08` — feature parente `F-142`

## Objectif
Que l'agent **utilise** le rendu de la gateway au lieu de demander au client d'installer
`mermaid-cli`, `chromium`, `diagrams` ou `graphviz`.

## Le défaut
L'outil existe (SF-142-06 / 07) — mais le skill `pptx`, lui, continue d'enseigner l'ancienne recette :

```bash
npm install -g @mermaid-js/mermaid-cli   # ce que le poste du client refuse
pip install diagrams ; apt-get install graphviz
```

Un agent suit ce qu'on lui écrit. Tant que la recette dit « installe », il installera — ou échouera
en le disant, ce qui ne vaut guère mieux pour le livrable.

## Comportement attendu
1. Le skill `pptx` **ne demande plus aucune installation** : il appelle `render_diagram`.
2. La recette « icônes cloud officielles » devient **`engine=cloud`** — même outil, une description.
3. Les **replis** restent, mais changent d'ordre : gateway d'abord, page ensuite ; l'installation
   locale n'est plus proposée.
4. La doctrine de fond est **inchangée** : diagramme-as-code, jamais d'image IA pour un schéma,
   factuel (rien d'inventé), échec nommé.
5. Les **documents** (`docx`) et les **pages** bénéficient du même outil, dit au même endroit.

| Cas d'erreur | Comportement |
|---|---|
| Rendu indisponible (service éteint) | le skill dit de livrer le diagramme **en page** — jamais d'installer quoi que ce soit |
| Diagramme invalide | corriger le code ou la description, à partir de la raison rendue |

## Critères d'acceptation
- [x] Plus **aucune** consigne d'installation (`npm install`, `pip install`, `apt-get`) dans les skills.
- [x] Le skill `pptx` enseigne `render_diagram`, avec les deux moteurs.
- [x] Les icônes officielles passent par **`engine=cloud`**, avec la forme de la description.
- [x] La doctrine (diagramme-as-code, pas d'image IA pour un schéma, factuel) est conservée mot pour mot.
- [x] Un **test** interdit la réapparition d'une consigne d'installation dans les skills.

## Hors scope
Le moteur de rendu lui-même (**SF-142-06 / 07**, livrés) · les icônes hors des quatre familles.

## Technique
| Élément | Changement |
|---|---|
| `governance/savoir-durable/pptx.md` | la recette des diagrammes est réécrite autour de `render_diagram` |
| `PresentationToolCatalog` | la mention « mmdc dans le sandbox » disparaît |
| `GovernanceSeededPackageIntegrationTest` (ou test dédié) | **garde** : aucune consigne d'installation dans les skills servis |

Aucune table, aucune migration, aucune route.

## Plan de test — `SkillsInstallFreeTest`, 3 verts
- [x] **La garde** : ni `npm install`, ni `apt-get install`, ni `pip install diagrams`, ni le nom des
      moteurs locaux. Elle m'a attrapé sur ma propre rédaction — une phrase explicative citait encore
      l'outil : j'ai corrigé le skill, pas la garde.
- [x] Le skill enseigne `render_diagram`, `engine: "cloud"`, les types (`aws.rds`), et dit **GRATUIT**.
- [x] La doctrine est conservée : jamais `generate_image` pour un schéma, FACTUEL, « (supposé) ».

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | — |
| Plans / limites | **oui** | Le skill doit dire que le rendu est **gratuit** : sans cela, l'agent l'évitera comme il évite `generate_image`, qui est payant. |
| Navigation / routing | non | — |
