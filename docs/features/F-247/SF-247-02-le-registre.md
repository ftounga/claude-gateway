# Mini-spec — F-247 / SF-247-02 Le registre

## Identifiant
`F-247 / SF-247-02`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.1, D2, D3, D4, D12 ; amendements F-248 D14/D15)

## Statut
`done` (2026-11-05) · **Estimation :** 2 j (réestimé 1 j) · **Branche :** `feat/SF-247-02-le-registre` · **Dépend de :** SF-247-01

## Objectif
Poser la mémoire datée de ce qui ne vit que sur le poste : `jarvis_state_facts` (une ligne par client + objet + attribut,
mise à jour en place, valeur scellée) et `jarvis_state_settings` (tout activé par défaut, couper purge), lue par index
en ≤ 10 ms p95 à 100 000 faits.

## Comportement attendu

### Cas nominal
1. `JarvisStateRegistry.record(userId, observations)` : chaque observation est normalisée (vocabulaire fermé
   `JarvisStateVocabulary` : familles dont **`ACCESS`** — amendement F-248 D14 —, attributs dont **`url`** — F-248 D15 —,
   sources `TOUR`/`NOTE`/`RELEVE`/`VEILLE`, confiances `HAUTE`/`MOYENNE` ; clé ≤ 300, valeur ≤ 300 sur une ligne),
   puis **upsert idempotent** sur `(user_id, host_id, object_key, attribute)` :
   - même valeur ⇒ seule `observed_at` avance ;
   - valeur différente ⇒ l'actuelle passe en `previous_value_sealed` / `previous_observed_at`, `changed_at` posé ;
   - observation plus ancienne ⇒ ignorée ;
   - `HAUTE` jamais écrasée par une `MOYENNE` de moins de 5 min plus récente.
2. Valeurs scellées par `PortraitSeal` (KMS en prod) via `JarvisStateSeal` : cache LRU en mémoire du pod (20 000) du
   scellé vers le clair, pour ne pas payer KMS à chaque comparaison ni à chaque lecture (Z10). Clair jamais journalisé.
3. Lectures **sous `user_id` + `host_id`, par index, ≤ 20 lignes** : `recent`, `byKind`, `byKey` (clé exacte ou
   préfixe, jokers neutralisés). On ne descelle que les lignes retenues.
4. Bornes : 10 000 faits par client (au-delà, les plus anciens sont purgés) ; purge nocturne (03:40) des faits non revus
   depuis la rétention du client (30 j par défaut).
5. Réglage par client : ligne absente = **tout activé** (D3) ; `setEnabled(false)` / `save(enabled=false)` **purge** ;
   familles coupées (`disabled_families`) ; `eraseAll` ; `delete` d'un fait (sous `user_id`).
6. Coupe-circuits `APP_JARVIS_STATE_ENABLED`, `…_EXTRACT_ENABLED`, `…_NOTES_ENABLED`, `…_RELEVE_ENABLED`,
   `…_CAPTURES_ENABLED`, `…_BLOCK_ENABLED` (`JarvisStateProperties`, tous vrais par défaut) ; `…_MAX_FACTS_PER_HOST`.
7. `onChange(listener)` : prévient le bloc préparé à l'avance (SF-247-10) qu'un client a changé.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Client d'un autre compte | rien écrit, rien lu |
| Famille, attribut, source ou confiance hors liste | observation ignorée |
| Client coupé, famille coupée, coupe-circuit général | rien écrit (et rien lu pour le général) |
| Scellé illisible à la lecture | le fait est tu (jamais inventé) |
| Abonné en échec | journalisé (classe seule), l'écriture n'est pas annulée |

## Contraintes de validation
Clé ≤ 300 car., valeur ≤ 300 car. (tronquée avec « … »), `source_ref` ≤ 64, 10 000 faits / client, lecture ≤ 20 lignes, rétention ≥ 1 j.

## Critères d'acceptation
- [x] Z11 : 1 000 observations d'un même fait ⇒ 1 ligne ; changement ⇒ valeur précédente gardée.
- [x] Règles d'ancienneté et de confiance respectées.
- [x] Isolation à deux comptes et deux clients.
- [x] Activé par défaut ; couper purge et arrête l'écriture ; famille coupée.
- [x] Z10 : ≤ 10 ms p95 à 100 000 faits sur PostgreSQL, plan sans parcours séquentiel.

## Plan de test
- Intégration H2 : `JarvisStateRegistryIntegrationTest` (7 : Z11, règles, isolation, coupure, préfixe littéral, plafond + purge, coupe-circuit).
- Charge PostgreSQL (Testcontainers) : `JarvisStateRegistryPostgresLoadTest` (100 000 faits, 3 lectures ≤ 10 ms p95, `EXPLAIN` sans `Seq Scan`).

## Impact
- Tables : `jarvis_state_facts`, `jarvis_state_settings` (migration `247-02-jarvis-state-facts.xml`, réversible ; index `recent`, `kind`, unique de clé, index à motifs PostgreSQL pour le préfixe).
- Backend : `jarvis/state/JarvisStateFact`, `JarvisStateSettings`, `…Repository` ×2, `JarvisStateVocabulary`, `JarvisStateSeal`, `JarvisStateRegistry`, `JarvisStateProperties` ; `application.yml` (coupe-circuits).
- Aucun endpoint (l'écran et son API arrivent en SF-247-15).
- Préoccupations transversales : Contexte tenant — `JarvisStateRegistry` (vérifie `RunnerHostRepository.findByIdAndUserId` avant toute écriture), `JarvisStateFactRepository`/`JarvisStateSettingsRepository` (toutes les requêtes portent `user_id` + `host_id`, sauf la purge nocturne qui est globale par construction).

## Hors périmètre
Les producteurs (extraction, relevé, constats), le bloc, l'outil `etat`, l'écran. Un historique au-delà de la valeur précédente.
