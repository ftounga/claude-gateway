# Mini-spec — F-239 / SF-239-09 — La mémoire du jour

## Identifiant
`F-239 / SF-239-09` · Feature parente `F-239` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-239-09-memoire-du-jour`

## Objectif
> Jarvis se souvient de la conversation de la journée (pas seulement des 30 dernières minutes), y compris après un
> redémarrage de la passerelle.

## Comportement attendu
- `JarvisFastMemory` : fenêtre = **depuis minuit (Europe/Paris)**, au plus **40** échanges par compte (au lieu de 30 min
  / 12) ; clefée `user_id`.
- **Réhydratation** : mémoire vide pour le compte ⇒ relue une fois depuis le fil (`atelier_messages`, `via = JARVIS`,
  `user_id`, depuis minuit) : chaque parole USER suivie de sa réponse (pour un tour Opus : sa ligne 🎙) ; bornée à 40.
- Le préfixe reste relu en cache (SF-239-11) : l'historique ne grandit que d'un échange par appel.
- « Première salutation du jour » (SF-239-08) = aucune trace de la journée.

## Critères d'acceptation
- [ ] Un échange de 9 h est encore dans la mémoire à 15 h, plus le lendemain.
- [ ] Après redémarrage, la mémoire du jour est reconstituée depuis le fil du compte, jamais d'un autre.
- [ ] Au plus 40 échanges.

## Technique
`JarvisFastMemory` (fenêtre du jour, borne, `hydrate`), `AtelierMessageRepository` (lecture du jour sous `user_id`),
`JarvisFastBrainService` (réhydratation). Aucune table.

## Plan de test
Mémoire (fenêtre du jour, borne 40), réhydratation (paires USER/réponse, 🎙, isolation `user_id`).
