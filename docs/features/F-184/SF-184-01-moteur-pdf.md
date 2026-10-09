# Mini-spec — [F-184 / SF-184-01] Le service de rendu imprime un lot en PDF A4 clair

---

## Identifiant

`F-184 / SF-184-01`

## Feature parente

`F-184` — Les pages en PDF, même charte (`CADRAGE-F-184-pages-en-pdf.md`)

## Statut

`in-review`

## Date de création

2026-10-10

## Branche Git

`feat/SF-184-01-moteur-pdf`

---

## Objectif

Le service `diagram-renderer` reçoit un **lot** (HTML d'une page, ressources) et renvoie le **PDF A4, thème clair**, rendu par Chromium **sans jamais sortir sur le réseau**.

---

## Comportement attendu

### Cas nominal

1. `POST /pdf`, corps JSON : `{ "html": "<!doctype html>…", "resources": [ { "url": "https://…", "contentType": "…", "body": "<base64>" } ] }`.
2. Le service ouvre la page dans Chromium (puppeteer-core, chromium du système) à l'adresse virtuelle `https://page.cg.local/index.html`. Une pièce jointe référencée par son nom (`capture.png`) se résout donc en `https://page.cg.local/capture.png`, et la bibliothèque Mermaid de la gateway en `https://page.cg.local/api/pages/lib/…`.
3. **Chaque requête est interceptée**. Si l'URL correspond à la page ou à une ressource du lot, le service la sert. Sinon, il l'interrompt et note l'URL comme manquante. Les `data:` ne passent pas par le réseau.
4. Le rendu impose `prefers-color-scheme: light` et ajoute une feuille d'impression minimale : couleurs de fond imprimées ; pas de coupure à l'intérieur de `img`, `svg`, `figure`, `pre`, `table tr`, `.mermaid` ; pas de saut juste après un titre.
5. Il attend le chargement, les polices (`document.fonts.ready`) et la fin du rendu Mermaid (aucun `pre.mermaid` non traité, borné à 5 s).
6. Réponse `200 application/pdf` : A4, `printBackground`, marges de 16 mm en haut et en bas et de 14 mm sur les côtés. L'en-tête `X-Cg-Missing-Resources` liste les URL refusées (tronqué à 1 000 caractères), s'il y en a.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| JSON invalide ou `html` absent/vide | Message explicite | 400 |
| `html` > 8 Mo, plus de 60 ressources, corps > 32 Mo | Refus borné | 413 |
| Ressource mal formée (url non http(s), body non base64) | Message explicite | 400 |
| Rendu dépassant 45 s | Échec propre, Chromium fermé | 504 |
| PDF produit > 20 Mo | Refus | 413 |
| Ressource référencée absente du lot | PDF produit quand même, URL dans `X-Cg-Missing-Resources` | 200 |
| Plus de 2 impressions en parallèle | Mise en file (sémaphore) | — |

---

## Critères d'acceptation

- [ ] CA1 — Un lot nominal produit un PDF (`%PDF-` en tête) au format A4 (595 × 842 pt).
- [ ] CA2 — Une page dont le fond est sombre sous `prefers-color-scheme: dark` sort sur fond **clair**.
- [ ] CA3 — Une ressource du lot (image, script) est servie et rendue ; une URL hors lot est refusée et listée dans `X-Cg-Missing-Resources`.
- [ ] CA4 — **Aucune requête ne quitte le service** : toute URL hors lot est interrompue (vérifié par l'interception, et la NetworkPolicy reste DNS seul, inchangée).
- [ ] CA5 — Les bornes (taille, nombre, délai) renvoient 400/413/504 sans laisser de Chromium ouvert.
- [ ] CA6 — Les routes existantes (`/render`, `/presentation`, `/document`, `/health`) sont inchangées.

---

## Périmètre

### Hors scope (explicite)

- L'assemblage du lot et la route gateway (SF-184-02), le bouton (SF-184-03), l'outil agent (SF-184-04).
- En-têtes et pieds de page, numéros de page, autres formats qu'A4, thème sombre.

---

## Valeurs initiales

| Champ | Valeur | Règle |
|-------|--------|-------|
| Origine virtuelle | `https://page.cg.local/` | fixe ; la gateway résout les noms relatifs sur cette base |
| Concurrence | 2 | sémaphore en mémoire |

---

## Contraintes de validation

| Champ | Obligatoire | Max | Format | Normalisation |
|-------|-------------|-----|--------|---------------|
| html | Oui | 8 Mo | texte non vide | — |
| resources | Non | 60 entrées | url http(s), contentType texte, body base64 | url sans fragment |
| corps total | — | 32 Mo | JSON | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| POST | `diagram-renderer:8080/pdf` (interne, ClusterIP, NetworkPolicy : seul le backend) | — | — |

### Tables impactées

Aucune. Pas de migration Liquibase.

### Composants

- `diagram-renderer/pdf.js` — logique **pure** testable : validation du lot, table des ressources, feuille d'impression, en-tête des manquants.
- `diagram-renderer/server.js` — route `/pdf`, puppeteer-core, interception, sémaphore.
- `diagram-renderer/Dockerfile` — `puppeteer-core` épinglé, installé dans `/app`.

### Préoccupations transversales

Aucune cochée.

---

## Plan de test

### Tests unitaires (`node --test diagram-renderer/tests/`)

- `test_pdf.js` : validation (html absent, trop gros, trop de ressources, url invalide, base64 invalide) ; table des ressources (normalisation, page à l'origine virtuelle) ; feuille d'impression ; en-tête des manquants tronqué.

### Tests d'intégration

- Image construite localement, conteneur lancé, `POST /pdf` avec : une page claire et sombre (fond vérifié en clair), une image du lot, un script hors lot (listé manquant). Vérifier `%PDF-`, la taille A4 (`/MediaBox [0 0 595 842]` à ±1) et l'en-tête.
- Bornes : html vide → 400 ; 61 ressources → 413.

### Isolation workspace

Non applicable : service interne sans compte. L'isolation propriétaire est portée par la route gateway (SF-184-02).

---

## Dépendances

- F-142 (service de rendu, Chromium) — done.
- Questions ouvertes : aucune.

---

## Notes et décisions

- `puppeteer-core`, et non la ligne de commande `--print-to-pdf` : il faut **intercepter** les requêtes (rester hors ligne en servant le lot) et **attendre** Mermaid ; la ligne de commande ne permet ni l'un ni l'autre.
- Le service reste hors ligne (cadrage §3) : la NetworkPolicy n'est pas modifiée.
