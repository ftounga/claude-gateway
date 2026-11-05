# Mini-spec — F-247 / SF-247-07 Le relevé côté passerelle

## Identifiant
`F-247 / SF-247-07`

## Feature parente
`F-247` — Le registre d'état (cadrage §4.3, §4.4 source (c), D5, D12, portes Z4, Z5)

## Statut
`done` (2026-11-05) · **Estimation :** 2 j (réestimé 1 j : planificateur F-230 réutilisé) ·
**Branche :** `feat/SF-247-07-releve-passerelle` · **Dépend de :** SF-247-02, SF-247-06

## Objectif
Un relevé est une veille `purpose = STATE` : il passe sans IA ni alerte, en basse priorité, seulement quand le poste
est calme, et son instantané — comme celui des veilles d'alerte existantes — devient des faits datés au registre.

## Comportement attendu

### Cas nominal
1. `jarvis_watches.purpose` : `ALERT` (défaut, veilles existantes inchangées) ou `STATE`.
2. **Planificateur** (D5, verrou multi-pod existant) : un relevé dû ne part que si le poste est en ligne **et** si la
   garde `JarvisWatchStateHook.mayRun` l'autorise ; sinon rien n'est pris (le créneau reste dû, retenté au passage
   suivant ; jamais de « manqué » pour un relevé).
3. **Garde** (`JarvisReleveService`) : coupe-circuit `APP_JARVIS_STATE_RELEVE_ENABLED`, client actif et
   `releve_enabled`, runner qui annonce **`low_priority`** (sinon **aucun** relevé), **Z4 côté serveur** : aucun tour
   vivant sur les sujets du poste et aucune entrée du journal du runner hors relevés/veilles depuis **2 min** (index
   `user_id, workspace_id, created_at`), **désarmement** du jour au-delà de **3 préemptions** (D12).
4. **Passage** : la sonde part par `RunnerToolGateway.bashLow` (entrée `priority: low`, **20 s**), journalisée
   `releve_sonde:bash`. Réponse `busy_deferred` ou `preempted` ⇒ passage **`YIELDED`** (ni échec, ni panne), créneau
   rendu (`releaseSlot`) pour être repris.
5. **Instantané → faits** (sources (b) et (c)) : chaque élément `{id, titre, statut, assigné, mis_à_jour}` donne
   `status`, `assignee`, `title`, `updated_at` sur la clé `<PLATEFORME>:<id>` (JIRA, GITLAB, GITHUB… alignées sur
   l'extraction ; famille TICKET / MR / OTHER), confiance `HAUTE`, datés de la fin du passage ; source `RELEVE` pour un
   relevé, `VEILLE` pour une veille d'alerte. Écriture via `JarvisStateRegistry.record` (client vérifié au compte,
   familles, filtre des secrets).
6. Un relevé **ne parle jamais** : ni note au fil, ni alerte, ni IA, ni notification.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Runner sans `low_priority` | aucun relevé |
| Tour vivant / commande du PO < 2 min | rien ne part, retenté plus tard |
| Poste occupé (`busy_deferred`) ou préempté | `YIELDED`, créneau rendu, pas d'échec compté |
| > 3 préemptions aujourd'hui | désarmé jusqu'au lendemain |
| Client coupé / relevé coupé / coupe-circuit | rien ne part, rien n'est rangé |
| Sonde en échec réel (code ≠ 0, sortie illisible) | échec F-230 inchangé (panne au 2ᵉ) |

## Contraintes de validation
20 s par sonde ; fenêtre de calme 2 min ; 3 préemptions/jour ; 500 éléments par instantané (borne F-230).

## Critères d'acceptation
- [x] Garde : calme, capacité, réglage, désarmement.
- [x] `YIELDED` sans échec, créneau rendu.
- [x] Faits RELEVE / VEILLE, HAUTE, clés alignées ; isolation.
- [x] Silence total d'un relevé ; veilles d'alerte inchangées (non-régression).

## Plan de test
- Unitaires : `JarvisWatchReleveTest` (2).
- Intégration : `JarvisReleveIntegrationTest` (6, dont isolation à deux comptes).
- Non-régression : `JarvisWatch*Test`, `JarvisAlert*Test`.

## Impact
- Migration `247-07-jarvis-watches-purpose.xml`.
- Backend : `atelier/veille/JarvisWatch` (`purpose`), `JarvisWatchRun` (`YIELDED`), `JarvisWatchRepository`
  (`releaseSlot`), `JarvisWatchPlanner`, `JarvisWatchPassage`, `JarvisWatchProbeRunner`, `JarvisWatchStateHook`
  (nouveau) ; `runner/exec/RunnerToolGateway.bashLow` ; `jarvis/state/releve/JarvisReleveService` (nouveau).
- Aucun endpoint.
- Préoccupations transversales :
  - **Contexte tenant** : `JarvisWatchPlanner` (sujet relu `findByIdAndUserId`), `JarvisReleveService` (requêtes sous
    `user_id` + `host_id`), `JarvisStateRegistry.record` (client vérifié au compte).
  - **Protocole runner** : `bashLow` n'est émis que si le poste annonce `low_priority`.

## Arbitrages (réversibles)
- Un relevé hors ligne n'est pas noté « manqué » (rien à rattraper : l'âge est dit par Jarvis).
- Le désarmement est **journalier** (réarmé seul le lendemain), compté sur les `YIELDED preempted` du poste.
- Les veilles d'alerte alimentent aussi le registre à chaque passage réussi (source (c)).

## Hors périmètre
La pose des sondes de relevé (SF-247-08), le relevé à l'inactivité et au départ (SF-247-09).
