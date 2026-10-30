# SF-126-03 — Repli de l'essentiel quand l'agent oublie les marqueurs

**Feature :** F-126 · **Statut :** Done (2026-10-30) · **Estimation :** < 0,5 j · **Front seul**

## Objectif
Afficher le bloc « L'essentiel » même quand l'agent a ouvert sa réponse par la réponse directe sans poser `<<essentiel>>`.

## Constat (prod, 2026-10-30)
Sujet data-platform, chantier 9 : les deux dernières réponses (02:15, 02:19) commencent par une phrase d'essentiel mais sans marqueurs → rendu plat. Le serveur ne retire rien (vérifié) ; la doctrine `ESSENTIAL_ANSWER_DOCTRINE` est toujours au système. Taux d'oubli habituel : 1 à 3 par jour (9 le 27/10).

## Comportement nominal
Message **persisté** sans marqueur, ≥ 400 caractères, avec au moins deux paragraphes : le premier paragraphe devient l'essentiel s'il fait ≤ 450 caractères et n'est pas structuré (titre, liste, tableau, code, citation, gras seul) ; le reste devient le détail.

## Pas de repli (rendu inchangé)
- marqueurs présents (découpage SF-126-01) ;
- réponse découpée par question (F-210) ;
- réponse courte, paragraphe unique, premier paragraphe long ou structuré ;
- texte vivant en cours de flux (incomplet).

## Critères d'acceptation
- [x] Les deux messages réels de data-platform affichent « L'essentiel ».
- [x] Aucun changement pour une réponse balisée ou découpée par question.
- [x] Aucune régression des specs du terminal (7 échecs préexistants identiques sur main).

## Plan de test
Unitaires `essential.spec.ts` (7 cas : balisé, repli, court, structuré ×3, trop long, par question, paragraphe unique). Pas de backend, pas de donnée : isolation `user_id` non concernée.

## Impact
`essential.ts` (`splitEssentialWithFallback`), `atelier-terminal.component.{ts,html}` (messages persistés). Préoccupations transversales : aucune.

## Hors périmètre
Faire poser les marqueurs par l'agent (doctrine inchangée, cache F-134 intact) ; chercher si l'en-tête de fil F-221 favorise l'oubli (surveillance).
