# Cadrage F-184 — Les pages en PDF, même charte

**Demande du PO, 2026-10-10** : *« Je souhaite que l'application puisse générer aussi les PDF. Les pages HTML que tu génères déjà, je veux pouvoir générer des PDF pareil, même charte graphique, un outil capable de transformer nos pages HTML générées. »*

## 1. Existant vérifié (rien n'est réinventé)

| Brique | Ce qu'elle fait | Réutilisée pour |
|---|---|---|
| F-109 pages (`pages/`) | Stockage versionné, pièces jointes, CSP, partage, export ZIP | La source du PDF : HTML d'une version + ses pièces jointes |
| `PageMermaidRuntime` + `PageLibraryController` (F-142) | Mermaid servi par la gateway, injecté au service | Mermaid rendu dans le PDF, sans CDN |
| `PageImageInliner` (SF-142-22) | Images de schéma inlinées en `data:` | Images présentes dans le PDF |
| `diagram-renderer` (F-142/F-129) | Service interne avec **Chromium**, sans Internet (NetworkPolicy DNS seul) | **Le moteur PDF** : Chromium imprime la page comme le navigateur l'affiche |
| F-14 `PdfExporter` | PDF des conversations (texte) | **Non réutilisé** : il ne rend ni CSS ni JS, la charte serait perdue |

Aucune sortie PDF des pages n'existe aujourd'hui.

## 2. Décisions PO (2026-10-10)

- **Accès** : un bouton « Télécharger en PDF » sur la page, et l'agent peut produire le PDF à la demande dans le terminal. La pièce jointe à un courriel (F-110) est **reportée** : à rouvrir si le PO la demande.
- **Format** : **A4 paginé**, marges, sans coupure au milieu des blocs (titres, figures, lignes de tableau, schémas).
- **Thème** : **toujours clair**, avec la même charte. Les pages suivent déjà le guide « variables CSS sur `:root` + `@media (prefers-color-scheme: dark)` » : on impose `prefers-color-scheme: light` au rendu.

## 3. Décision technique (par défaut, signalée)

**Le service de rendu reste hors ligne.** Une page charge ses scripts depuis `cdnjs.cloudflare.com` / `cdn.jsdelivr.net` et ses polices depuis Google Fonts. Plutôt que d'ouvrir la sortie Internet du service de rendu, ce qui affaiblirait un contrôle volontaire, c'est **la gateway qui assemble un lot** :
- le HTML servi de la version, avec Mermaid injecté et les images inlinées ;
- ses pièces jointes ;
- la bibliothèque Mermaid locale ;
- les ressources externes **référencées par la page et seulement sur la liste fermée** : les deux CDN de scripts, `fonts.googleapis.com` et `fonts.gstatic.com`. Elles sont bornées en taille et en nombre et mises en cache.

Le service de rendu ouvre la page dans Chromium et **intercepte chaque requête** : il sert une ressource du lot ou il refuse, et rien ne sort. Une ressource introuvable n'empêche pas le PDF ; le résultat signale ce qui manque.

## 4. Découpage

| SF | Titre | Estimation |
|---|---|---|
| SF-184-01 | Le service de rendu imprime un lot en PDF A4 clair (route `POST /pdf`, interception hors ligne, bornes) | 1 j |
| SF-184-02 | La gateway assemble le lot et sert `GET /api/pages/{id}/pdf` (isolation propriétaire, ressources externes sur liste fermée, cache) | 1,5 j |
| SF-184-03 | Bouton « Télécharger en PDF » dans l'aperçu, le plein écran et l'onglet Pages | 0,5 j |
| SF-184-04 | L'agent produit le PDF d'une page à la demande (outil `page_pdf`, bloc « PDF prêt » dans le terminal) | 1 j |

## 5. Hors périmètre

- Pièce jointe à un courriel (reportée).
- PDF depuis un lien de partage public.
- Thème sombre, formats autres qu'A4, en-têtes et pieds de page personnalisés.
- Conversion PDF → HTML ; PDF d'autre chose qu'une page (les conversations ont déjà F-14).
