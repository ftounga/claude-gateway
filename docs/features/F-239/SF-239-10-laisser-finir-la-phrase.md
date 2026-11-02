# Mini-spec — F-239 / SF-239-10 — Laisser finir la phrase

## Identifiant
`F-239 / SF-239-10` · Feature parente `F-239` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-239-10-fin-de-tour`

## Objectif
> Ne plus couper le PO quand il réfléchit (« Allons chez le client et donnez- »).

## Mesure (banc `--vad`, phrases avec pause de réflexion ≈ 1-1,7 s)
Seuil 0,7 s (prod) : 50/52 coupées ; 1,0 s : 46/52 ; 1,2 s : 28/52. Un seuil seul ne suffit pas.

## Comportement attendu
- Silence de fin de tour du fournisseur : **1,2 s** par défaut (`APP_JARVIS_STT_STREAM_VAD_SILENCE_SECONDS`), et la
  fin de phrase de l'écran au même défaut.
- **Phrase en suspens** (pure, `jarvis-turn-end.ts`) : le texte validé finit par un trait d'union, une virgule, une
  conjonction, un article, une préposition, un possessif ou une hésitation ⇒ l'écoute **continue** jusqu'à
  **1,5 s** de plus ; une nouvelle parole ou un nouveau segment relance l'attente ; sinon fin de tour.
- Le silence mesuré par l'écran ne force la fin qu'après le délai du fournisseur + 0,8 s (le fournisseur décide).
- Latence ajoutée : +0,5 s sur une phrase complète (0,7 ⇒ 1,2 s), jusqu'à +1,5 s sur une phrase en suspens.

## Critères d'acceptation
- [ ] « Allons chez le client et donnez- » validé ⇒ pas de fin de tour avant 1,5 s ; la suite dite est jointe.
- [ ] Une phrase complète finit au silence du fournisseur.

## Technique
`JarvisSttStreamProperties` (1,2 s), `jarvis-mic-meter.ts` (1 200 ms), `jarvis-turn-end.ts`, `jarvis.component.ts`.

## Plan de test
Unitaires (suspens), composant (attente prolongée, jonction, fin au délai).
