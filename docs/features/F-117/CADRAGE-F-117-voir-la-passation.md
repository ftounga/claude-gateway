# Cadrage F-117 (réouverture) — Voir la passation, et retrait du bandeau des 40 tours

**Demande PO du 2026-10-11** (vidéo « mods », mod « Relais »). Audit : le relais existe **déjà, en automatique** — la compaction (SF-117-01, seuil 120 000 tokens, `AtelierCompactionService.java`) rédige un résumé au gabarit **Objectif · Fichiers · Décisions et faits établis · État courant · Prochaines étapes** (`SUMMARY_SECTIONS`, l.73), stocké dans `workspaces.chat_thread_summary` et réinjecté en tête du rejeu. En prod, 7 projets actifs sur 25 en portent un (10 000 à 17 000 caractères). **Mais il n'est jamais visible** : `/contexte` affiche seulement « Résumé ancré : Présent ».

## SF-117-09 — Voir la passation

- Route `GET` du résumé du fil (isolation `user_id`) et `PUT` pour le **corriger** (texte borné, même gabarit, journalisé). Le résumé corrigé remplace celui réinjecté au tour suivant.
- Front : lien **« Voir la passation »** dans le marqueur de compaction du fil et dans le panneau `/contexte` ; panneau lisible (5 rubriques en Markdown, ancres « (tour N) » cliquables si simple) avec bouton **« Corriger »** (édition, Enregistrer/Annuler).
- Absent si aucun résumé ; lecture seule = consultation sans correction.

## SF-117-10 — Retrait du bandeau des 40 tours

- Supprimer le bandeau « Cette conversation est longue — l'alléger ? » (`suggestRestart`, `LONG_THREAD_TURNS = 40`, `atelier-terminal.component.ts:194` et `.html:391`) : la compaction automatique fait ce travail au bon moment.
- Garder le bouton d'en-tête **« Nouveau départ »** avec un libellé/infobulle clair : *repartir à blanc pour changer de sujet*. Garder « Compacter maintenant » accessible (menu ou `/compacter` s'il existe).
- Mettre à jour tests et docs (SF-117-03 marquée retirée).
