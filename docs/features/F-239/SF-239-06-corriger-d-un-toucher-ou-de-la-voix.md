# Mini-spec — F-239 / SF-239-06 — Corriger d'un toucher ou de la voix

## Identifiant
`F-239 / SF-239-06` · Feature parente `F-239` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-239-06-corriger`

## Objectif
> Le texte compris s'affiche ; s'il est faux, le PO le corrige d'un toucher (édition du texte) ou par « non, j'ai
> dit … », et la correction repart comme la demande corrigée.

## Comportement attendu
- **Toucher** : le texte compris sous l'orbe est un bouton « Corriger » ; il ouvre un champ (`mat-form-field`
  outline) prérempli ; Entrée envoie la demande corrigée ; Échap annule.
- **Voix** : « non, j'ai dit X », « je voulais dire X », « j'ai dit X » (liste fermée) dans les 30 s après une parole
  comprise : X de 4 mots ou plus ⇒ remplace toute la demande ; sinon X remplace le mot le plus proche de la parole
  précédente (distance d'édition), par ex. « Où en est Cagibi ? » + « non, j'ai dit CAGIP » ⇒ « Où en est CAGIP ? ».
- La demande corrigée part comme une nouvelle parole (`handle`) ; la mesure porte `correction = true`.
- **Banc** : si la capture est allumée (SF-239-03), la correction devient `corrected_text` de la dernière capture
  (`PUT /api/jarvis/captures/latest/correction`, ADMIN, ≤ 2 min après la capture, sous `user_id`).

## Critères d'acceptation
- [ ] Toucher ⇒ champ prérempli ⇒ Entrée ⇒ demande corrigée envoyée.
- [ ] « non, j'ai dit CAGIP » corrige le mot proche ; une longue correction remplace tout.
- [ ] La correction remplit `corrected_text` de la dernière capture du compte seulement.

## Technique
Front `jarvis-correction.ts` (pur), `jarvis.component` ; back `JarvisCaptureService.correctLatest`. Aucune table.

## Plan de test
Unitaires (reconnaissance « j'ai dit », remplacement du mot), composant (toucher, voix), intégration (correction de
la dernière capture, isolation, 2 min).
