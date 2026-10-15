# Cadrage F-202 — Plume refondue : une demande vraiment meilleure

**Demande PO (2026-10-15)**, livraison autonome (vague). Constat vérifié sur `origin/main` 6e16f2c8 et en prod. Justesse avant coût ; charte `DESIGN_SYSTEM.md` (aucune couleur nouvelle) ; préfixe système stable (rien de volatil).

## Besoin
« ✨ Enrichir » (F-193) ne marche pas : la sortie ressemble à du JSON (accolades visibles), est très souvent **coupée**, retranscrit mal le contexte, et n'est pas plus méthodique. Attendu : à partir de ma phrase, un prompt **bien plus élaboré** (méthode, techniques, précisions) pour un résultat optimal, fidèle au besoin.

## Causes (code, `atelier/plume/PlumeService.java`)
- `MAX_TOKENS = 1_200` (l.65) pour un JSON à 6 champs × 8 éléments + double recopie des « à confirmer » ⇒ troncature fréquente ; `stop_reason` jamais lu (`AnthropicProvider.toResult`).
- JSON coupé ⇒ `jsonObjectOf` échoue ⇒ repli `new Enrichment(raw, …)` (l.236) ⇒ **JSON brut dans la saisie** ; objets imbriqués ⇒ `node.toString()` (l.309).
- Matière : 6 derniers messages, **fin** des réponses de l'agent (`tail(…,1500)`, l.175) alors que l'essentiel F-126 est au **début** ; `STATE.md` racine ; rien sur fichiers touchés. `oneLine()` aplatit tout ⇒ puces télégraphiques.
- Front : `closePlume()` remet un `<input>` mono-ligne ⇒ retours à la ligne perdus.

## Décisions
- **Sortie en texte balisé Markdown**, plus de JSON : un prompt rédigé et structuré (Objectif, Contexte établi, Ce qu'il faut faire / méthode, Contraintes et garde-fous, Vérification attendue / critère de fin, Points à confirmer), en phrases complètes ; « détailler sans inventer » conservé (supposé ⇒ « à confirmer »).
- Plafond relevé (≥ 4 000 jetons) ; **troncature détectée** (`stop_reason = max_tokens`, à remonter via `AIProvider` sans dépendance Anthropic dans le métier) ⇒ un nouvel essai, sinon erreur claire — jamais un fragment.
- Matière enrichie : essentiel (début) des réponses, plus de messages (bornés), passation F-117 si présente, plan/chantier F-176 si guidé, fichiers touchés récemment ; tout borné.
- Modèle principal, effort suffisant (justesse avant coût) ; usage compté.
- Front : la saisie reste multiligne tant que le texte enrichi est multiligne ; « Revenir à mon texte » inchangé.
- Jeu d'essai : 5 phrases réelles du PO (prod) avant/après, jointes à la PR.

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-202-01 | Backend : sortie Markdown, plafond + détection de troncature via AIProvider, matière enrichie, tests | 🟠 |
| SF-202-02 | Front : saisie multiligne conservée, rendu, jeu d'essai avant/après | 🟢 |
