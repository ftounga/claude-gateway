# Mini-spec — F-234 / SF-234-08 — S6 web, S10 passation, S12 mémoire, S13 essaim, S15 le point, S16, S17

## Identifiant
`F-234 / SF-234-08`

## Feature parente
`F-234` — L'écran vivant de Jarvis (cadrage §5, §8.2 ; vague C, usage moyen : web 127, mémoire/savoir 153,
délégation 87, passation 18 appels sur 30 j)

## Statut
`in-review`

## Date de création
2026-11-01

## Branche Git
`feat/SF-234-08-web-passation-memoire-essaim`

---

## Objectif

> Les actions d'usage moyen ont leur scène : la recherche web dans une barre d'adresse, la passation qui fait glisser
> le fil vers l'autre sujet, le souvenir qui remonte, l'essaim de sous-agents, le point sur tous les sujets, le
> besoin noté, la veille ou la routine posée.

## Comportement attendu

- **Registre** : `web_search`, `web_fetch`, étape `web` → `S6` ; `recall` (outil et étape), `carte_chercher`,
  `reclass_entry`, `gouvernance_proposer`, `skill` → `S12` ; `explore`, `task` (outils et étapes) → `S13` ;
  `ouvrir_sujet`, `create_subject` → `S10` ; `sujets_etat`, `compte_consommation`, `radar_resume`, `radar_sujets`
  (outils « poste », F-178) → `S15` ; `signaler_besoin` → `S16` ; `veille_poser`, `routine_proposer` → `S17`.
  Préfixes `radar_` / `teams_` **restent `GENERIQUE`** (vague D non livrée, D-PO-7).
- **S6** : barre d'adresse stylisée — la requête (`web_search`) ou le **domaine seul** (`web_fetch`, étiquette SF-234-04).
- **S10** : `ouvrir_sujet` étiqueté (nom du sujet) ou l'événement `handoff` (F-179) ⇒ « Sujet courant ── Sujet
  ouvert » ; le sujet ouvert s'allume et, dans la constellation, un **fil** part du sujet courant vers lui.
- **S12** : `recall` (la requête) puis l'événement `recall` (F-162) ⇒ le badge « tour 34 » remonte ; `compaction`
  (F-117) ⇒ « Le fil se résume · n tours pliés » ; gouvernance / skill / carte : libellé + étiquette.
- **S13** : une orbe satellite par `explore` / `task` (rafale), avec sa question tronquée (40 car., masquée), 4 au plus.
- **S15** : `sujets_etat` & co. ⇒ « n sujets chez CAGIP » et **vague** sur tous les sujets du client dans la
  constellation.
- **S16** : « Besoin noté » + type et titre (générique libellée, dédiée au registre).
- **S17** : une horloge s'attache au sujet, « Jira · nom » de la veille ou de la routine.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Sujet de passation inconnu de l'univers | nom écrit, aucun fil dessiné |
| `recall` sans événement `recall` (rien retrouvé) | requête seule |
| Sujet cible non dessiné (téléphone, autre client) | scène écrite, pas de fil |

## Critères d'acceptation
1. `web` ⇒ barre d'adresse avec domaine.
2. `ouvrir_sujet` étiqueté ⇒ le fil glisse vers ce sujet.
3. `recall` + événement `recall` ⇒ « tour 34 ».
4. 2 `explore` ⇒ 2 satellites.
5. `sujets_etat` ⇒ vague sur les sujets du client.

## Plan de test
- `jarvis-scenes-moyennes.spec.ts` : registre (dont Radar/Teams toujours génériques), S6, S10 (action et
  événement), S12 (recall + repère, compaction), S13 ; rendu constellation (vague, fil) + scène (adresse, passation).
- Non-régression : suite `jarvis/**`, garde passerelle, build.

## Impacts
- **Tables / endpoints** : aucun. **Back** : aucun.
- **Front** : registre, lecteur (`recalled`, `compaction`, `handoff`, données S6/S10/S13), fil, scène, constellation
  (vague S15, fil S10), `jarvis.component.ts` (relais `onRecalled`, `onCompaction`, `onHandoff`).

## Préoccupations transversales
Aucune (toucher un sujet reste `chooseSubject` ; aucune route).

## Arbitrages (réversibles)
- `radar_resume` / `radar_sujets` sur S15 (outils « poste » du cadrage) ; le reste de Radar reste générique (D-PO-7).
- La passation ne change **pas** de sujet à l'écran (elle est proposée, F-179) : le fil la montre, rien de plus.

## Hors périmètre
Radar / Teams / Vigie / réunions (SF-234-12, non livrée) ; lecture du contenu web.
