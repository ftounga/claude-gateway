# Mini-spec — F-255 / SF-255-04 Ce qui manque au diagnostic : le sujet brut, l'audio en repli

## Identifiant
`F-255 / SF-255-04`

## Feature parente
`F-255` — Rejeu de séance Jarvis (cadrage §4, SF ajoutée le 2026-11-11)

## Statut
`in-progress` · **Estimation :** 1 j · **Branche :** `feat/SF-255-04-sujet-brut-audio-repli` · **Date :** 2026-11-11 ·
**Dépend de :** SF-255-01 (mergée)

## Objectif
Donner au diagnostic deux pièces qui manquent : ce que le modèle a mis dans `sujet` quand Jarvis répond « Pour quel
client ou quel sujet, Monsieur ? », et l'audio des tours écoutés en dictée de repli.

## Comportement attendu

### Cas nominal
1. **Sujet brut.** Quand une délégation du cerveau rapide n'a pas de sujet cible (issue `no_subject`), la passerelle
   écrit une ligne de journal (INFO) : compte, sujet de l'écran, et la valeur brute du champ `sujet` rendue par le
   modèle avec sa catégorie — `vide`, `illisible` (pas un identifiant), `inconnu` (identifiant hors du compte). Point de
   trace seulement : consigne, outils, modèle et décision **inchangés** (pas de banc).
2. **Audio en repli.** Quand le flux n'a pas servi et que l'énoncé a été dicté (WebM), l'écran le garde comme les énoncés
   en flux : seulement pour un ADMIN qui a allumé l'interrupteur, avec le tour (`turnId`), le texte retenu, 7 jours au
   plus, 200 au plus. La passerelle le range avec son type (`.webm`, `audio/webm`) ; la route d'écoute rend le bon type.
   Le rejeu (SF-255-02) et « Réécouter » (SF-255-03) le servent sans changement.

### Cas d'erreur
| Situation | Comportement attendu | Code |
|---|---|---|
| Valeur brute très longue ou multiligne | coupée à 80 caractères, retours à la ligne retirés | — |
| Audio d'un type ni WAV ni WebM | rangé comme WAV (comportement d'avant) | 204 |
| Interrupteur éteint ou non ADMIN | rien n'est gardé | 204 |
| Stockage en échec | énoncé non gardé, l'écoute continue | 204 |

## Critères d'acceptation
- [ ] Une délégation sans sujet cible produit la ligne de journal avec la valeur brute et sa catégorie ; la réponse
      reste « Pour quel client ou quel sujet, Monsieur ? ».
- [ ] Un énoncé WebM est gardé sous `…/jarvis-captures/{id}.webm` et relu en `audio/webm` ; un WAV reste `audio/wav`.
- [ ] L'écran garde l'énoncé dicté en repli (ADMIN, interrupteur allumé), avec son `turnId`.
- [ ] Isolation : un énoncé n'est relu que par son propriétaire.

## Périmètre — hors scope
Corriger la cause des `no_subject` ; changer la consigne, les outils ou le modèle ; transcoder l'audio.

## Contraintes de validation
| Champ | Règle |
|---|---|
| type d'audio | `audio/webm` ⇒ `.webm` ; tout le reste ⇒ `.wav` |
| valeur brute de `sujet` | ≤ 80 caractères, une ligne |

## Technique
- Backend : `JarvisFastBrainService.delegation` (une ligne de journal, aucune autre modification), `JarvisCaptureService`
  (type de l'audio), `JarvisCaptureController` (type transmis et rendu). Aucune table, aucune migration.
- Écran : `jarvis.component.ts` (`keepForBench` accepte la dictée WebM du repli), `jarvis.service.ts` (nom de fichier
  selon le type).

## Plan de test
- Unitaires : `JarvisFastBrainServiceTest` ou test dédié (catégories du sujet brut, réponse inchangée) ;
  `JarvisCaptureServiceTest` (WebM gardé en `.webm`, WAV inchangé, relecture par le propriétaire seulement).
- Écran : `jarvis.component.spec` (un tour en repli dicté est gardé avec son tour ; sans interrupteur, rien).
- Isolation : `JarvisCaptureServiceTest` (audio d'autrui introuvable).

## Préoccupations transversales
Aucune.
