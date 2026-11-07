# Mini-spec — F-233 / SF-233-14 — L'écoute en flux : termes du vocabulaire ≤ 20 caractères

## Statut
`in-review` — 2026-11-07 — branche `fix/SF-233-14-keyterms-20-car`

## Objectif
> L'écoute en flux s'ouvre à nouveau : le fournisseur refuse la session si un `keyterm` dépasse 20 caractères.

## Constat (prod, 2026-11-07 13:23 UTC)
Tour `CONV_BATCH`, `stream_fallback=ouverture_refusee` (mesure SF-233-13). Sonde réelle sur un ticket du PO :
`invalid_request` « Each keyterm must be at most 20 characters. 'poseidon-decommissionnement' is 27 characters. »
10 termes sur 50 dépassaient (noms composés de sujets et de contacts). Termes filtrés ≤ 20 : `session_started`.

## Comportement
`streamKeyterms` : un terme > 20 caractères est découpé à `, / ( ) ;` puis `-` ; morceaux de 2 à 20 caractères
gardés, sans doublon (casse ignorée), ordre de priorité conservé, au plus 50. La retranscription batch est inchangée.

## Critères d'acceptation
- [ ] Aucun terme envoyé au flux ne dépasse 20 caractères ; ≤ 50 termes (test unitaire).
- [ ] En prod, les tours reviennent en `CONV_STREAM` sans `stream_fallback`.

## Plan de test
`ElevenLabsStreamingTranscriptionProviderTest` (5, dont 1 nouveau). Isolation : sans objet.

## Impacts
`ElevenLabsStreamingTranscriptionProvider` seul. Aucune table, aucun endpoint.

## Hors périmètre
Le vocabulaire lui-même (sa construction reste celle de SF-239-04).
