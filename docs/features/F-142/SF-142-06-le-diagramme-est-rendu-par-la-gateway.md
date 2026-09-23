# Mini-spec — F-142 / SF-142-06 — Le diagramme est rendu par la gateway

## Identifiant
`F-142 / SF-142-06` — feature parente `F-142`

## Objectif
Qu'un diagramme devienne une **image** sans que le poste du client installe quoi que ce soit — la
gateway le rend, et dépose le fichier là où vit le projet.

## Le défaut
> PO, 2026-09-23 : *« Je pensais que notre application pouvait faire ça à son niveau. Mais s'il faut
> installer des choses chez le client, non. »*

Aujourd'hui, la chaîne des slides tourne **sur le poste** — c'est écrit dans le skill : *« elle tourne
entièrement sur le terminal (sandbox), jamais sur le serveur/cluster »*. Elle exige :

```bash
npm install -g @mermaid-js/mermaid-cli   # registre npm + droits d'installation globale
                                          # + puppeteer télécharge chromium (~150 Mo)
```

Trois barrières simultanées sur un poste de banque : le **proxy**, les **droits**, le **téléchargement**.
Même chose pour les icônes cloud officielles (SF-142-03 : `diagrams` + graphviz). Résultat chez CAGIP :
le deck part **sans** ses diagrammes, avec un échec nommé — honnête, mais inutilisable.

## Ce qu'on écarte, et pourquoi
**Générer les schémas par IA (DALL·E).** Envisagé par le PO le 2026-09-23, écarté après vérification :
c'est la décision de fond de F-142 (*« pas de génération d'images IA pour les schémas : icônes
inventées, texte en charabia, inutilisable en livrable »*). DALL·E **reste** branché pour les images
**décoratives** (SF-142-04) — sa place, inchangée.

## Comportement attendu
1. Un outil **`render_diagram`** prend du **code Mermaid** et rend une image **PNG ou SVG**.
2. Le rendu a lieu **dans notre cluster**, sur un service dédié — le poste n'exécute rien.
3. L'image est **déposée là où vit le projet** (poste ou hébergé) par le chemin **déjà existant** des
   images décoratives ; l'outil rend le **chemin** à l'agent.
4. Le fichier sert partout : **slide** (`add_picture`), **page** (balise `img`), **document** (`.docx`).
5. Le service **indisponible** est un **échec nommé** : l'agent le dit et propose le rendu en page
   (qui, lui, ne dépend de rien depuis SF-142-05). **Jamais** de fausse image.

| Cas d'erreur | Comportement |
|---|---|
| Code Mermaid invalide | échec nommé **de ce diagramme**, avec le message du moteur ; le reste du livrable est produit |
| Service de rendu injoignable / trop lent | échec nommé + repli proposé (rendu en page) ; borné par un délai |
| Diagramme démesuré (code ou image) | refus borné et dit, avant de fabriquer quoi que ce soit |
| Dépôt dans le projet impossible (runner trop ancien) | l'image existe, le dépôt est dit en échec — comme pour les images décoratives |

## Critères d'acceptation
- [x] `render_diagram` rend une image depuis du code Mermaid, **sans rien exécuter sur le poste**.
- [x] Les deux formats sont servis : **PNG** (slides, documents) et **SVG** (pages).
- [x] L'image est déposée dans le projet et l'outil rend son **chemin**.
- [x] Un code invalide échoue **nommément**, sans casser le reste du livrable.
- [x] Le service injoignable est **dit**, avec le repli ; aucune image fabriquée.
- [x] Les bornes (taille du code, du rendu, délai) sont **appliquées et testées**.
- [x] **ISOLATION** : le dépôt passe par le workspace du tour ; aucun chemin venu du modèle.

## Hors scope
Le moteur **`diagrams`** / icônes cloud officielles (**SF-142-07**) · la bascule des skills et le
retrait des consignes d'installation (**SF-142-08**) · le rendu **dans la page** par le navigateur
(SF-142-05, livré, inchangé).

## Technique
| Élément | Changement |
|---|---|
| `diagram-renderer/` *(nouveau)* | image du service : `mermaid-cli` + chromium + un serveur HTTP minimal (`POST /render`) |
| `k8s/base/diagram-renderer/` | déploiement + service `ClusterIP`, une réplique, ressources bornées |
| `DiagramRenderer` *(interface)* + `HttpDiagramRenderer` | **Provider Independence** : le métier ne dépend pas du moteur ; un autre rendu se branche sans réécriture |
| `DiagramToolCatalog` / `DiagramToolExecutor` | l'outil, et le dépôt **réutilisé** de `ImageToolExecutor` |
| `AtelierChatService` | l'outil est proposé comme les autres outils de la gateway |

**Ce qu'on ne construit pas** : le dépôt dans le projet. Il existe (`ImageToolExecutor`, SF-142-04) —
poste par tranches (`write_file_bytes`), hébergé par le stockage objet. On l'emprunte.

**Bornes** : code ≤ **20 000** caractères · image ≤ **8 Mio** · délai de rendu ≤ **30 s** · largeur
par défaut **1600 px**.

**Ce qui sort du poste** : le **code du diagramme** (du texte) monte à la gateway pour être rendu.
C'est le prix de « zéro installation », accepté explicitement par le PO le 2026-09-23. La variante sans
transfert (navigateur déjà présent sur le poste) reste possible si l'arbitrage change.

## Plan de test
### Gateway — `DiagramToolExecutorTest` (7) + `HttpDiagramRendererTest` (6)
- [x] Rendu nominal PNG **et** SVG ; le format demandé est honoré jusqu'au type de contenu du dépôt.
- [x] Code invalide → la **raison du moteur** remonte, **aucun** dépôt.
- [x] Service muet ou en panne → **autre phrase, autre suite** : le repli par la page est proposé.
- [x] Bornes appliquées **avant** l'appel (code vide, code trop long) et après (image trop lourde).
- [x] Le dépôt emprunte `ProjectFileDeposit`, **extrait** du chemin des images décoratives — une seule
      implémentation, vérifiée par les tests des deux outils.
- [x] **ISOLATION** : un nom venu du modèle est nettoyé (`../../etc/passwd` → `passwd.png`).

### Service de rendu — éprouvé sur l'image réelle
- [x] `flowchart` → PNG 510×522 · **`architecture-beta` → PNG 1338×786** · `sequenceDiagram` → 1350×675.
- [x] SVG servi en `image/svg+xml`.
- [x] Code invalide → **422** avec l'erreur du parseur ; requête sans code → **400** nommé.
- [x] **Échelle ×3** : `-w` fixe la largeur de la page, pas celle de l'image — sans le facteur d'échelle,
      un petit diagramme sortait en 170 px, illisible une fois posé dans une slide.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | le service n'est **pas exposé** hors du cluster (`ClusterIP`) ; aucun accès direct depuis Internet |
| **Contexte tenant** | **oui** | Le dépôt passe par le `Workspace` **du tour** (`ImageToolExecutor` déjà isolé `user_id`) ; le nom de fichier est nettoyé ; aucun chemin absolu accepté. |
| **Plans / limites** | **oui** | Le rendu **ne coûte aucun jeton** (pas d'appel fournisseur) — contrairement aux images décoratives. À dire dans la description de l'outil, sinon l'agent croira que c'est payant et l'évitera. |
| Navigation / routing | non | aucun écran |
