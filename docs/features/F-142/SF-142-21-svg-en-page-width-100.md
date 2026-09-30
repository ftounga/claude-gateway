# Mini-spec — F-142 / SF-142-21 Le diagramme SVG s'affiche en `width:100%` (net), pas à taille naturelle débordante

---

## Identifiant

`F-142 / SF-142-21`

## Feature parente

`F-142` — Diagrammes rendus par la gateway (diagram-as-code, icônes cloud officielles)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-142-21-svg-width-100`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Corriger la doctrine d'embarquement en page introduite par SF-142-19 — fausse pour le SVG — pour dire au modèle qu'un diagramme SVG (les archi cloud le sont depuis SF-142-18) s'affiche en `width:100%; height:auto` (il reste NET), et non à taille naturelle dans un conteneur défilable (recette héritée du PNG, qui provoque débordement horizontal et vide vertical).

---

## Comportement attendu

### Cas nominal

> Description précise du flux principal (entrée → traitement → sortie).

C'est une correction de **consigne au modèle** (constantes de prompt), pas de post-traitement HTML. Aucun code d'exécution ni de rendu n'est modifié.

1. Quand l'agent reçoit l'outil `render_diagram`, la consigne (`DiagramToolCatalog.GUIDE`) lui dit désormais : un diagramme **SVG** s'affiche en `width:100%; height:auto` — il reste net à toute échelle ; optionnellement un lien « ouvrir en grand » vers le SVG pour le détail ; **ne pas** le rendre à taille naturelle avec débordement horizontal. Un diagramme **raster (PNG)** large, lui, garde son conteneur défilable à taille naturelle (l'écraser le rend flou).
2. Même correction dans le guide de conception des pages (`PageToolCatalog.DESIGN_GUIDE`), au paragraphe « UNE ARCHI NE S'ÉCRASE PAS ».
3. Le message d'insertion rendu par `DiagramToolExecutor` après un rendu réussi est aligné : pour un rendu SVG il conseille `width:100%; height:auto` (net) ; pour un rendu PNG il conserve le conteneur défilable à taille naturelle.
4. La distinction PNG vs SVG existante (les images ORDINAIRES — captures, logos, ornements — restent en `max-width:100%`) est conservée telle quelle.
5. Le garde-fou de densité par ratio (SF-142-19, `cloud.py`) n'est **pas** touché : il émet une NOTE, utile, indépendante de l'affichage.

### Cas d'erreur

> Lister tous les cas d'erreur identifiés.

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Rendu de diagramme invalide | Inchangé : la raison du moteur est rendue, rien n'est déposé | n/a (tool outcome) |
| Rendu indisponible | Inchangé : repli page `<pre class="mermaid">` proposé | n/a |
| Dépôt en échec | Inchangé : l'image existe mais n'est pas arrivée, message dédié | n/a |

Aucun nouveau cas d'erreur : la subfeature ne modifie que du texte de consigne et le libellé d'un message de succès.

---

## Critères d'acceptation

> Chaque critère est vérifiable. Pas d'ambiguïté.

- [ ] `DiagramToolCatalog.GUIDE` dit qu'un diagramme SVG s'affiche en `width:100%` (`height:auto`) et reste NET ; il ne dit plus « conteneur défilable / taille naturelle » comme recette pour l'archi SVG.
- [ ] `DiagramToolCatalog.GUIDE` conserve la mention « ouvrir en grand » (optionnelle) et interdit explicitement le débordement horizontal pour le SVG.
- [ ] `PageToolCatalog.DESIGN_GUIDE` dit qu'un schéma d'architecture SVG s'affiche en `width:100%` net, plus « taille naturelle débordante ».
- [ ] `PageToolCatalog.DESIGN_GUIDE` conserve la règle « images ORDINAIRES en `max-width:100%` ».
- [ ] Le message d'insertion de `DiagramToolExecutor` conseille `width:100%` pour un rendu SVG et ne recommande plus le conteneur défilable pour ce cas.
- [ ] Le garde-fou de densité par ratio (`cloud.py`, SF-142-19) est inchangé.
- [ ] Les tests qui vérifiaient l'ANCIENNE consigne (`conteneur défilable` + `width:100%` côté « ne pas écraser ») sont adaptés pour vérifier la NOUVELLE (SVG = `width:100%` net).
- [ ] Tests backend ciblés verts + compilation, au premier plan.

---

## Périmètre

### Hors scope (explicite)

- Aucun post-traitement HTML des pages publiées (Gateway-First : on corrige la consigne, pas le rendu).
- Le garde-fou de densité par ratio (`cloud.py`) — conservé tel quel, non modifié.
- La viewBox du SVG (SF-142-20), le moteur cloud/drawio, le format de rendu — inchangés.
- La consigne des images ordinaires (`max-width:100%`) — conservée, non re-rédigée.
- Aucun changement de schéma, d'endpoint, de base de données, ni de frontend Angular.

---

## Valeurs initiales

Non applicable — aucune entité créée ni modifiée.

---

## Contraintes de validation

Non applicable — aucun champ soumis à validation n'est introduit ou modifié. Modification de constantes de prompt (`String`) et d'un message de succès.

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées

Aucune.

### Migration Liquibase

- [ ] Oui
- [x] Non applicable

### Composants Angular (si applicable)

Aucun — la feature n'a pas d'écran ; c'est une consigne interne au modèle.

### Fichiers impactés

| Fichier | Opération |
|---------|-----------|
| `backend/.../diagrams/DiagramToolCatalog.java` (`GUIDE`) | Réécriture du paragraphe « EN PAGE, NE L'ÉCRASE PAS » |
| `backend/.../pages/PageToolCatalog.java` (`DESIGN_GUIDE`) | Réécriture du paragraphe « UNE ARCHI NE S'ÉCRASE PAS » |
| `backend/.../diagrams/DiagramToolExecutor.java` | Message d'insertion rendu format-aware (SVG vs PNG) |
| `backend/.../diagrams/DiagramToolExecutorTest.java` | Adapter l'assertion de la nouvelle consigne |
| `backend/.../pages/PageToolCatalogTest.java` | Adapter l'assertion de la nouvelle consigne |

---

## Plan de test

### Tests unitaires

- [ ] `PageToolCatalogTest.designGuide` — vérifie que `DESIGN_GUIDE` contient la nouvelle doctrine SVG (`width:100%`, « net ») et non plus « conteneur défilable » comme recette d'archi.
- [ ] `DiagramToolExecutorTest.thecloudEngineRendersFromADescription` — vérifie que le message d'insertion d'un rendu SVG (cloud) conseille `width:100%` net (plus « conteneur défilable »).
- [ ] `DiagramToolExecutorTest` — un rendu PNG (mermaid par défaut) conserve un message cohérent (add_picture, rien installé) — non régressé.

### Tests d'intégration

- [ ] `AtelierChatServiceSystemPromptTest` / `AtelierChatServicePageToolTest` — la consigne injectée dans le system prompt reste cohérente (le guide y est présent). Vérifier qu'aucune assertion existante n'est cassée par la nouvelle formulation.

### Isolation workspace

- [x] Non applicable — raison : aucune donnée accédée, aucun changement de garde ni de contexte tenant. Correction de texte de consigne uniquement.

---

## Dépendances

### Subfeatures bloquantes

- `SF-142-18` — done (archi cloud en SVG)
- `SF-142-19` — done (doctrine à corriger)
- `SF-142-20` — done (viewBox cohérente)

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

- **Pourquoi SVG = `width:100%`** : un SVG est vectoriel, donc NET à n'importe quelle échelle. La recette « taille naturelle / conteneur défilable » de SF-142-19 était héritée du PNG (où réduire = flou) ; appliquée au SVG (~3313 px), elle produit un débordement horizontal (texte coupé) et un grand vide vertical — pire qu'un simple ajustement. `width:100%; height:auto` fait tenir le schéma dans la colonne en restant net.
- **Préoccupation transversale** : aucune (pas d'auth, pas de tenant, pas de plan/limite, pas de routing). Il ne s'agit que de constantes de prompt.
- **Provider-First / Gateway-First** : correction de consigne au modèle, jamais de post-traitement du HTML produit.
