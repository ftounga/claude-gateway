# Mini-spec — F-255 / SF-255-02 Le rejeu en JSON : tours, verdicts, bilan

## Identifiant
`F-255 / SF-255-02`

## Feature parente
`F-255` — Rejeu de séance Jarvis (cadrage §3 verdicts, §4 découpage)

## Statut
`in-progress` · **Estimation :** 2 j · **Branche :** `feat/SF-255-02-rejeu-json` · **Date :** 2026-11-11 ·
**Dépend de :** SF-255-01 (mergée)

## Objectif
Rendre à l'administrateur, en JSON, chaque séance Jarvis tour par tour — ce qui a été dit, compris, répondu, délégué,
montré, le temps de chaque étape — avec un verdict par tour calculé sans IA et un bilan comparable d'une séance à l'autre.

## Comportement attendu

### Cas nominal
1. `GET /api/admin/jarvis/replay/sessions?days=7` : les séances du compte (tours séparés de moins de 10 min, plusieurs
   pages possibles) sur `days` jours (≤ 7, la rétention du journal d'écoute), les plus récentes d'abord, avec début,
   fin, pages, tours et **bilan**.
2. `GET /api/admin/jarvis/replay?from=…&to=…` (ISO-8601, ≤ 6 h) : la séance tour par tour, le bilan et les seuils.
3. **Tour** : un `listen_open` (ou un nouvel identifiant de tour) qui porte une parole (`listen_end`, `transcript`,
   `speech_rejected`, `barge_in_turn`). Par tour : heure de fin de parole, mode d'écoute, longueur transcrite et refus,
   textes du flux et retenu (capture), texte du message, identifiant de capture (audio par la route existante
   `/api/admin/jarvis/captures/{id}/audio`), étapes (parole, silence de fin, transcription, envoi, premier jeton,
   premier son, fin), cerveau, spéculation, raison de délégation, diagnostic de tour vide, longueur dite, réponse,
   sujet de l'écran, délégations (sujet cible, chantier, état, libellé, réponse rendue), ordres d'écran montrés ou
   refusés, replis de transcription, résumé des événements du journal.
4. **Corrélation** : `IDENTIFIANT` (SF-255-01 : mesure, capture et événements par `turnId`, heure serveur exacte) ;
   `ESTIMEE` sinon (horloge de page recalée sur les mesures : refus et fin de voix ; rattachement par fenêtre).
   Les messages se rattachent par fenêtre autour de la fin de parole ; la réponse d'une délégation par le lien
   (`ref_message_id`), sinon la première réponse de Jarvis dans le sujet cible dans les 20 min.
5. **Verdicts** (cadrage §3) : `ENTENDU`, `PERDU`, `REPETE` (même réponse normalisée qu'un des 5 tours précédents),
   `LENT` (premier son > 3 000 ms), `MAUVAIS_SUJET` (cible ≠ dernier sujet nommé par le PO ou annoncé par Jarvis,
   sinon sujet de l'écran ; jamais si la demande nomme la cible), `MONTRE`, `VOICI_SANS_AFFICHAGE`,
   `DELEGATION_MUETTE` (délégation et `said=0`), `REPLI_TRANSCRIPTION` (raison), `MESURE_ABSENTE`.
6. **Bilan** : tours, entendus, perdus (taux), répétitions, lents, premier son p50/p90, mauvais sujets, montrés,
   « Voici » sans affichage, délégations, délégations muettes, replis (taux), mesures absentes.

### Cas d'erreur
| Situation | Comportement attendu | Code |
|---|---|---|
| Non ADMIN | refus | 403 |
| `from`/`to` absents, illisibles, inversés, ou fenêtre > 6 h | message explicite | 400 |
| Aucun tour dans la fenêtre | rejeu vide, bilan nul | 200 |
| `days` absent, ≤ 0 ou > 7 | 7 par défaut, borné à 7 | 200 |
| Mesure ou capture absente pour un tour | tour rendu, `measured=false` / sans audio | 200 |

## Critères d'acceptation
- [ ] Les séances d'Alice ne contiennent que ses lignes ; Bob (ADMIN) ne voit rien d'Alice ; un non-ADMIN reçoit 403.
- [ ] Deux tours séparés de plus de 10 min donnent deux séances.
- [ ] Avec identifiants de tour : mesure, capture et événements rattachés exactement (`IDENTIFIANT`).
- [ ] Sans identifiants : rattachement par l'heure recalée (`ESTIMEE`), vérifié sur un jeu où l'envoi des lots est
      retardé de plusieurs secondes.
- [ ] Chaque verdict a un test unitaire positif et négatif ; le bilan compte correctement.
- [ ] Sur la séance du 09/11 (export de prod, lecture seule) : ~27 tours, ~10 perdus, la réponse « Pour quel client ou
      quel sujet, Monsieur ? » ×4, 1 « Voici » sans affichage (`galerie NOT_FOUND`), 1 délégation sur le mauvais sujet
      (vers waf alors que data-platform est courant, 17:24 Paris), replis fréquents — résultat reporté dans la PR.

## Périmètre — hors scope
L'écran (SF-255-03) ; garder les bilans au-delà de 7 jours ; l'audio de la dictée de repli ; toute écriture ; toute
modification du cerveau rapide.

## Contraintes de validation
| Champ | Obligatoire | Format | Règle |
|---|---|---|---|
| `from`, `to` | Oui (rejeu) | ISO-8601 avec décalage | `to ≥ from`, écart ≤ 6 h |
| `days` | Non | entier | défaut 7, borné à [1, 7] |

## Technique
- Endpoints : `GET /api/admin/jarvis/replay/sessions`, `GET /api/admin/jarvis/replay` (ADMIN).
- Tables lues (aucune écrite, aucune migration) : `jarvis_listen_traces`, `jarvis_turn_timings`,
  `jarvis_audio_captures`, `atelier_messages` (`via = JARVIS` ou `LINK`), `workspaces`.
- Backend : paquet `jarvis/replay` — `JarvisReplay` (modèle), `JarvisReplayAssembler` (pur), `JarvisReplayVerdicts`
  (pur), `JarvisReplayService`, `JarvisReplayController`, `JarvisReplayMessageRepository` (lecture dédiée) ; méthodes
  de lecture par fenêtre ajoutées aux dépôts du journal, des mesures et des captures.

## Plan de test
- Unitaires : `JarvisReplayAssemblerTest` (découpage, tour muet écarté, corrélation exacte, horloge recalée, messages,
  délégation et sa réponse) ; `JarvisReplayVerdictsTest` (chaque verdict, bilan, percentiles, sujets nommés).
- Intégration : `JarvisReplayApiIntegrationTest` — 200 et contenu, 400 (fenêtre), 403 (non ADMIN), **isolation**
  Alice/Bob.
- Validation sur la séance du 09/11 : export de prod en lecture seule, rejoué hors dépôt (aucune donnée de prod commitée).

## Préoccupations transversales
Aucune (routes admin existantes, même garde `assertAdmin`).
