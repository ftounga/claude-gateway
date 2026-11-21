# Mini-spec — [F-271 / SF-271-01] Ouvrir un chantier à la voix, titré par son ticket

## Identifiant
`F-271 / SF-271-01`

## Feature parente
`F-271` — Jarvis lance le travail : du ticket au chantier qui tourne, à la voix
(cadrage `CADRAGE-F-271-jarvis-lance-le-travail.md`, D2 + D3, maillons M1 et M2)

## Statut
`in-progress`

## Date de création
2026-11-21

## Branche Git
`feat/SF-271-01-chantier-a-la-voix`

---

## Objectif
Quand l'utilisateur demande explicitement d'ouvrir un chantier (« prends LDIC-325 et pose-moi le plan »), l'agent
l'ouvre sans clic, titré par la clé du ticket, et Jarvis retrouve ensuite ce chantier quand on lui dit « LDIC-325 ».

## Comportement attendu

### Cas nominal
1. `propose_guided_mode` reçoit deux paramètres **facultatifs** (pas de nouvel outil) :
   - `user_words` : les mots exacts de l'utilisateur, dans ce tour, qui demandent d'ouvrir / prendre / planifier ;
   - `ticket` : la clé du ticket que le chantier traite (`LDIC-325`).
2. `user_words` présent **et** cité mot pour mot dans ce que l'utilisateur a écrit (ou dit, transcrit) pendant ce tour,
   tour avec utilisateur (jamais une course autonome ni une routine) ⇒ le chantier **s'ouvre directement** :
   - sujet en Libre ⇒ passage en Guidé, chantier en Investigation (même effet que [Passer en guidé]) ;
   - sujet déjà guidé ⇒ un chantier **de plus** (même effet que [Ouvrir le chantier]) ; les autres restent ouverts ;
   - le refus antérieur « Rester libre » ne bloque pas une demande explicite ;
   - l'événement `GUIDED_ACCEPTED` est journalisé avec le canal `écrit « citation »`.
   Le résultat d'outil donne le numéro du chantier ouvert et la suite (investiguer, diagnostic, plan).
3. Sans `user_words` : comportement actuel (proposition au clic [Passer en guidé] / [Ouvrir le chantier]).
4. **Titre à clé** : avec `ticket` valide (`[A-Z][A-Z0-9]{1,9}-\d{1,6}`), le titre du chantier est
   `LDIC-325 — <raison>` (la raison n'est pas répétée si elle commence déjà par la clé) — par le chemin direct comme
   par la proposition au clic (la raison proposée porte la clé, donc le titre du chantier ouvert au clic aussi).
5. **Pas de doublon** : un chantier **ouvert** du même sujet dont le titre porte déjà la clé ⇒ rien n'est ouvert ; le
   résultat dit « Le chantier N (LDIC-325) est déjà ouvert » et invite à travailler sur lui (`chantier: N`) ; le focus
   ne bouge pas (un volet ne vole jamais le focus d'un autre, F-221).
6. **Résolution « LDIC-325 » → (sujet, chantier)** : `SubjectJourneyService.openChantiersWithTicket(userId, key)` rend
   les chantiers **ouverts** du compte dont le titre porte la clé (focus de chaque sujet + chantiers ouverts à côté).
7. **Jarvis vise le bon fil** : quand le cerveau rapide délègue **sans** numéro de chantier et que la demande (ou son
   libellé) cite une clé qui désigne **un seul** chantier ouvert du compte, la délégation part au fil de ce chantier
   (son sujet, son numéro). Un numéro donné par le cerveau rapide prime ; une clé ambiguë (deux chantiers) ou inconnue
   ne change rien.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| `user_words` absent | proposition au clic, comme aujourd'hui |
| `user_words` qui ne figure pas dans ce tour | **proposition au clic** + le résultat dit pourquoi le chantier n'est pas ouvert |
| Tour sans utilisateur (course autonome, routine, contexte inconnu) | proposition au clic, jamais d'ouverture |
| `ticket` illisible (`ldic325`, phrase) | ignoré : titre = raison seule |
| `reason` vide | erreur d'outil (inchangé) |
| Même clé déjà ouverte dans le sujet | rien n'est ouvert, le chantier existant est nommé |
| Clé ouverte dans deux chantiers du compte | aucune redirection de la délégation |
| Chantier d'un autre compte portant la clé | jamais trouvé (filtre `user_id`) |
| Poste du sujet éteint | Jarvis ne délègue pas (F-262 `offlineInstead`, inchangé) : rien n'est ouvert |

## Critères d'acceptation
- [ ] `propose_guided_mode` avec `user_words` cité ⇒ sujet Libre passé en Guidé, chantier 1 titré `LDIC-325 — …`, en
      Investigation ; journal `GUIDED_ACCEPTED` avec `écrit « … »`.
- [ ] Sujet guidé avec chantier ouvert ⇒ chantier de plus ouvert sans clic, l'autre intact.
- [ ] `user_words` non cité, ou tour sans utilisateur ⇒ proposition au clic (aucun chantier ouvert).
- [ ] Sans `user_words`, avec `ticket` ⇒ proposition ; [Passer en guidé] ouvre un chantier titré par la clé.
- [ ] Clé déjà ouverte dans le sujet ⇒ aucun nouveau chantier, le numéro existant est rendu.
- [ ] `openChantiersWithTicket` : focus et chantiers à côté trouvés, chantiers clos ignorés, Bob ne voit rien d'Alice.
- [ ] Délégation sans chantier citant `LDIC-325` (un seul chantier ouvert) ⇒ sujet + chantier de ce chantier ;
      ambiguë ou numéro donné ⇒ inchangée.

## Périmètre

### Hors scope (explicite)
- La doctrine orale qui dit à Opus quand ouvrir (SF-271-02).
- Valider et lancer (SF-271-03), la course qui parle (SF-271-04), l'écran (SF-271-05).
- Aucune migration : le titre existant (`chantier_title`, 200 caractères) porte la clé (D3).
- Liste d'outils du **cerveau rapide** : inchangée (la résolution est côté passerelle).

## Valeurs initiales
| Paramètre | Valeur |
|---|---|
| Format de clé | `[A-Z][A-Z0-9]{1,9}-\d{1,6}` (même motif que `JarvisWorks.TICKET`) |
| Titre | ≤ 200 caractères (inchangé) |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `user_words` | texte, cité mot pour mot dans le tour (casse, accents, ponctuation ignorés — `quoted`) |
| `ticket` | clé au format ci-dessus, mise en majuscules ; sinon ignorée |
| `reason` | obligatoire, ≤ 300 caractères (inchangé) |

## Technique

### Endpoint(s)
Aucun nouveau. `POST /api/workspaces/{id}/journey/guided/accept` inchangé (le titre vient de la raison, qui porte la clé).

### Tables impactées
`subject_journeys`, `subject_journey_chantiers` (lecture/écriture existantes, aucune colonne) ; `subject_journey_events`.

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
Aucun.

### Composants backend
- `JourneyToolCatalog.proposeGuidedDefinition` : `user_words`, `ticket` (littéraux stables ; la liste d'outils d'Opus
  change **une fois**, cadrage §8 « Cache »).
- `JourneyToolExecutor.proposeGuided` : chemin direct, repli proposition.
- `SubjectJourneyService` : `openOnUserWords`, `openChantiersWithTicket`, titre à clé (`ChantierTicket`).
- `SubjectJourneyRepository` / `SubjectJourneyChantierRepository` : lectures `user_id` (+ mode / statut).
- `JarvisFastBrainService.delegation` : redirection par clé (`ChantierTicketResolver`, facultatif).

## Plan de test

### Tests unitaires
- `ChantierTicketTest` : clé lue/normalisée, titre composé (sans doublon de clé), clé dans un texte libre.
- `JarvisFastBrainServiceTicketTargetTest` (ou extension) : redirection unique, ambiguë, numéro donné.

### Tests d'intégration
- `SubjectJourneyChantiersIntegrationTest` (étendu) : ouverture directe Libre → Guidé, chantier de plus, citation
  absente / inventée / tour sans utilisateur ⇒ proposition, doublon de clé, titre à clé via le clic,
  `openChantiersWithTicket` (focus, à côté, clos ignorés).

### Isolation workspace
- Même classe : un chantier `LDIC-325` de Bob n'est jamais rendu à Alice ; l'outil n'agit que sur le sujet du tour.

## Dépendances

### Subfeatures bloquantes
F-270 (livrée, prod). Aucune SF de F-271 avant celle-ci.

### Questions ouvertes impactées
Aucune.

## Préoccupations transversales
- Auth / Principal : non. Contexte tenant : non (lectures existantes sous `user_id`). Plans / limites : non.
  Navigation : non.

## Notes et décisions
- **D1** `ticket` en plus de `user_words` (le cadrage nomme seulement `user_words`) : composer le titre côté passerelle
  est plus sûr que de compter sur la forme de `reason`, et c'est un champ du même outil (pas un outil de plus).
- **D2** Une citation refusée retombe sur la proposition au clic (jamais une erreur sèche) : l'utilisateur garde le geste.
