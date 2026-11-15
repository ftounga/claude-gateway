# Cadrage F-261 — Clore sur la parole de l'utilisateur

**Origine** : bug enregistré par le PO le 2026-11-15 (table `app_requests`, id `11e43803-3619-44de-a2a4-54b0bfa7a9d0`).

## Constat

Sujet *data-platform*, fil du chantier 7 (LDIC-265), 10/11 puis 13/11 : 15 étapes vérifiées, phase Vérification. L'utilisateur écrit trois fois qu'il veut clore (« Il a validé tu peux cloturer et fermer », « je t'ai donné le go pour les 2 ») : l'agent n'a **aucun outil** pour clore — seul le bouton de la carte le fait. Or `valider_plan` (F-222) et `confirmer_diagnostic` (F-176 / SF-176-12) acceptent déjà les mots écrits comme équivalent d'un clic.

Même gêne avec `close_blocker` : il ne fait que **proposer** la fermeture d'une attente ; le « go » écrit ne suffit pas et la carte [Clore] [Garder] est reposée à chaque fois.

## Décision

Un seul mécanisme, celui de `valider_plan` (SF-222-04) :

1. **`cloturer_chantier`** (nouvel outil du parcours) : `chantier` explicite + `user_words` cités. Refusé sans citation, si la citation ne figure pas dans ce que l'utilisateur a écrit **dans ce tour**, et dans un tour sans utilisateur (course autonome, routine). Gardes de la clôture proposée : chantier en **Vérification** avec **toutes** ses étapes vérifiées. Le journal `CLOSED` garde le canal (`écrit « … »`). Ensuite, même geste que le bouton (`SubjectJourneyService.close`).
2. **`close_blocker`** : paramètre facultatif `user_words`. Présent et vérifié de la même façon → l'attente est **fermée** (Fait ou Annulée), la raison est sa parole. Absent → comportement actuel (proposition). Citation invalide → refus explicite, rien ne change (l'agent peut alors proposer sans `user_words`).

Préfixe stable : définitions et guides sont des littéraux ; rien de volatil.

## Découpage

- **SF-261-01** — les deux outils + guides + tests (≈ 1 j). Backend + petit ajout frontend (titre de carte d'attente « Clos sur ta demande »).

## Hors périmètre

- Clore un chantier hors Vérification (en sommeil, en Exécution) par écrit : reste le bouton.
- Durcir `confirmer_diagnostic` (pas de vérification de citation aujourd'hui) : signalé, non traité ici.
