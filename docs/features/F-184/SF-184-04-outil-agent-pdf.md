# Mini-spec — [F-184 / SF-184-04] L'agent produit le PDF d'une page à la demande

---

## Identifiant

`F-184 / SF-184-04`

## Feature parente

`F-184` — Les pages en PDF, même charte

## Statut

`in-review`

## Date de création

2026-10-10

## Branche Git

`feat/SF-184-04-outil-agent-pdf`

---

## Objectif

Quand l'utilisateur demande « fais-moi le PDF » dans le terminal, l'agent appelle un outil `page_pdf`. Le PDF est imprimé, et un bloc « PDF prêt » apparaît dans le fil, avec un bouton de téléchargement.

---

## Comportement attendu

### Cas nominal

1. Le nouvel outil `page_pdf`, de schéma `{ page_id (requis), version (optionnel) }`, est donné **avec** `page_publish`, sous la **même garde** (`PageToolCatalog.isOpenFor`).
2. La gateway imprime la page via `PagePdfService.print(userId, …)`. L'isolation par propriétaire s'applique, et l'impression **vérifie réellement** que le PDF se produit.
3. Le PDF imprimé est gardé dans un **cache court**, borné en mémoire (clé : compte, page, version ; 10 min ; 20 entrées ; 40 Mo). Le téléchargement qui suit est donc instantané et ne relance pas l'impression.
4. Un bloc de page est émis dans le fil (`listener.onPage`) avec `pdf = true`. Le terminal affiche « PDF prêt — <titre> (vN) » et un bouton **« Télécharger le PDF »**, qui passe par `PagePdfDownloadService` (SF-184-03).
5. L'outil répond à l'agent : « PDF prêt (A4, thème clair) : l'utilisateur le télécharge depuis le bloc. » S'il manque des ressources, il ajoute : « Éléments non inclus : … », pour que l'agent le dise.
6. **Pas d'accord d'un clic** : contrairement à `page_publish`, rien n'est publié ni rangé. C'est une lecture privée de la page de l'utilisateur.
7. Le guide de conception de la consigne apprend à l'agent que `page_pdf` existe : publier d'abord la page si elle n'existe pas, puis appeler `page_pdf`. Ce texte est **stable** (préfixe de cache, aucune donnée variable).

### Cas d'erreur

| Situation | Retour à l'agent |
|-----------|------------------|
| `page_id` absent ou invalide | Erreur : « Donne le page_id d'une page publiée (publie-la d'abord avec page_publish). » |
| Page d'un autre compte ou inexistante | Erreur : « Page introuvable dans ce compte. » |
| Moteur indisponible | Erreur : « Le PDF n'a pas pu être produit pour le moment ; propose de réessayer. » |
| Refus du moteur | Erreur avec la raison du moteur |
| Garde fermée (pas de droit aux pages) | Erreur : « Les pages ne sont pas ouvertes dans ce terminal. » |

---

## Critères d'acceptation

- [ ] CA1 — `page_pdf` est déclaré avec `page_publish` sous la même garde, et absent quand la garde est fermée.
- [ ] CA2 — Nominal : PDF imprimé, bloc `pdf = true` émis, retour **non-erreur** à l'agent.
- [ ] CA3 — Ressources manquantes : elles figurent dans le retour à l'agent.
- [ ] CA4 — Isolation : la page d'un autre compte donne une erreur, sans impression ni bloc.
- [ ] CA5 — Téléchargement après l'outil : servi depuis le cache, sans seconde impression. Cache borné et expirant.
- [ ] CA6 — Front : le bloc `pdf = true` affiche « PDF prêt » et « Télécharger le PDF » ; un bloc de page normal reste inchangé.
- [ ] CA7 — Aucune donnée variable ajoutée à la consigne système.

---

## Périmètre

### Hors scope

- Pièce jointe à un courriel (reportée).
- Dépôt du PDF dans les fichiers du projet.

---

## Valeurs initiales

| Champ | Valeur |
|-------|--------|
| `PageBlock.pdf` | `false` (rétrocompatible) |
| Cache PDF | 10 min, 20 entrées, 40 Mo |

## Contraintes de validation

| Champ | Obligatoire | Format |
|-------|-------------|--------|
| page_id | Oui | UUID |
| version | Non | entier > 0 |

---

## Technique

### Endpoint(s)

Aucun nouveau. `GET /api/pages/{id}/pdf` sert depuis le cache quand il est chaud.

### Tables impactées

Aucune.

### Composants

- Backend : `PageToolCatalog` (définition `page_pdf`, `isPdfTool`, guide), `PageToolExecutor.pdf(…)` (`PagePdfService` injecté), `PagePdfService` (cache court), `PageBlock.pdf`, routage dans `AtelierChatService` (branche `isPdfTool` avant `isPageTool`).
- Frontend : modèle du bloc de page (`pdf?: boolean`), `page-block.component` (libellé et bouton).

### Préoccupations transversales

Aucune cochée.

---

## Plan de test

### Tests unitaires

- `PageToolCatalogTest` : les deux outils déclarés ensemble, aucun sous une garde fermée.
- `PageToolExecutorTest` : `pdf` nominal (contenu, manquants) ; `page_id` invalide ; page introuvable ; moteur indisponible.
- `PagePdfServiceTest` : un second appel identique sert le cache (une seule impression) ; expiration ; bornes.
- Front : `page-block.component.spec` (bloc `pdf`).

### Tests d'intégration

- `AtelierChatService…` (tour simulé) : appel `page_pdf` → bloc émis avec `pdf = true`, retour non-erreur.
- Suite complète backend et frontend.

### Isolation workspace

- [x] Applicable : la page d'un autre compte donne une erreur, sans impression.

---

## Dépendances

- SF-184-02 et SF-184-03 : done.
