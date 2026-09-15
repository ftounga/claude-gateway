# SF-89-14 — Le relevé « forme » capte aussi le squelette des chemins Microsoft UNKNOWN

> Cadrage du 2026-09-15 (PO). **Cadrage seul, à livrer en autonome sur go.** Sous-feature de F-89,
> extension de SF-89-12 (relevé « forme »).

## Objectif (une phrase)
Étendre le mode `--forme` du relevé (SF-89-12) pour qu'il capte **aussi** le squelette — noms de champs
et types JSON, **jamais une valeur** — des réponses d'hôtes Microsoft classées **UNKNOWN**, afin de
découvrir la forme des endpoints pas encore reconnus (ex. `calendarView`, liste des conversations,
messages) **sans capture brute** de contenu.

## Pourquoi
Le débogage du 2026-09-15 a montré la limite : `teams_find_meetings` interroge
`/api/mt/emea/v2.0/me/calendars/default/calendarView` (la **liste** du calendrier), qui reste **UNKNOWN**
— alors que SF-89-13 a bien recalé `/calendars/events/{id}` (une réunion **ouverte**). Or `--forme` ne lit
aujourd'hui que le corps des chemins **déjà classés** → il **ne peut pas** révéler la forme d'un chemin
UNKNOWN. Poule et œuf : on ne peut ni classer sans la forme, ni obtenir la forme sans classer. Cette SF
casse le cycle, une fois pour toutes, pour **tout** futur endpoint.

## Comportement attendu
- En mode `--forme` uniquement (opt-in, inchangé sinon), pour une réponse : **hôte de la famille
  Microsoft** + **MIME `application/json`** + classification **UNKNOWN** (en plus des genres déjà lus par
  SF-89-12), lire le corps et n'en écrire que le **squelette** (noms + types JSON), regroupé dans une
  **nouvelle section « Squelette des chemins Microsoft non reconnus »**, avec chemin assaini (ids → `{id}`,
  pas de query), origine, version d'API.
- **Garde-fous vie privée identiques à SF-89-12, non négociables** : jamais une valeur de feuille (chaîne
  → `string`, etc.), jamais un en-tête, jamais une chaîne de requête ; nom de clé anormalement long
  tronqué (le nom, jamais la valeur).
- **Bornes** pour ne pas exploser le relevé : nombre de chemins UNKNOWN distincts capturés borné (ex. top
  20), taille de corps lue bornée, profondeur bornée (4), premier élément de tableau seul, nombre de clés
  borné par niveau. Un dépassement est **dit** (« N autres chemins non capturés »).
- Le relevé annonce clairement qu'il a lu des corps UNKNOWN en mode forme (traçabilité/consentement).

## Cas d'erreur
- Corps non-JSON ou indisponible (purgé par Chrome) → compté « indisponible », jamais inventé.
- Aucun chemin UNKNOWN Microsoft JSON → section vide, pas d'erreur.

## Critères d'acceptation
- Un relevé `--forme` ouvrant la **liste du calendrier** produit le squelette de
  `/calendars/default/calendarView` (noms + types), **sans aucune valeur**.
- Un test prouve qu'un corps UNKNOWN contenant des valeurs sensibles ne laisse **aucune valeur** dans la
  sortie.
- Le relevé normal (sans `--forme`) reste **strictement inchangé** (aucun corps lu).

## Plan de test
- Unitaires : capture de squelette sur un corps UNKNOWN Microsoft JSON (noms+types, zéro fuite) ; respect
  des bornes (top N, profondeur, tableau) ; non-`--forme` ⇒ aucun `getResponseBody` sur UNKNOWN ; hôte
  non-Microsoft ignoré.

## Hors périmètre
- **Recaler les adaptateurs** sur les formes découvertes (`calendarView`, conversations, messages) : ce
  sera **SF-89-15**, après un relevé « forme » exerçant la liste du calendrier **et** un fil de messages.
- Lire des corps non-JSON ; capturer des hôtes non-Microsoft.

## Suite prévue
Après livraison : **un** relevé `--forme` sur CAGIP ouvrant (a) la **liste du calendrier** et (b) un
**fil de discussion avec messages** → capture `calendarView` + conversations/messages en une passe →
**SF-89-15** recale `teams_find_meetings` (calendarView) **et** conversations/messages ensemble.
