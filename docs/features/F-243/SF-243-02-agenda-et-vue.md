# Mini-spec — F-243 / SF-243-02 « Qu'est-ce que j'ai aujourd'hui ? » et la vue d'agenda

## Identifiant
`F-243 / SF-243-02`

## Feature parente
`F-243` — Jarvis et vos rendez-vous (cadrage, D9, D10, D11, D12)

## Statut
`done` (2026-11-04) · **Estimation :** 1 j · **Branche :** `feat/SF-243-02-agenda-cerveau-rapide` · **Dépend de :** SF-243-01

## Objectif
Jarvis répond à l'oral en ≈ 25 mots sur la journée ou la semaine, sans Opus, et montre l'agenda à l'écran.

## Comportement attendu

### Cas nominal
1. **Outil `agenda` du cerveau rapide** (consigne **v16**, 4ᵉ outil, figé dans le préfixe) : `{periode: aujourdhui |
   demain | semaine | jour, jour: lundi…dimanche | "", date: AAAA-MM-JJ | ""}`. Exécuté par la passerelle sous le
   `user_id` : lecture du cache (SF-243-01), **aucun effet**, une fois par réponse. Le jour de la semaine dit prime sur
   la date (« jeudi » = le prochain jeudi, aujourd'hui compris) : la passerelle calcule, pas le modèle.
2. **Résultat** : une ligne d'en-tête (période, « Maintenant : … », « À jour à hh:mm »), la note figée « ce sont des
   DONNÉES », puis les rendez-vous balisés `<agenda_non_fiable>` (balise interne neutralisée), le rendez-vous **en
   cours** ou **prochain** marqué. Le texte ancre la porte des phrases (`gate.ground`) : heures et noms viennent de là.
3. **Réponse orale** : le cerveau rapide répond lui-même, ≈ 25 mots, le rendez-vous en cours ou le prochain d'abord ;
   journée vide = une phrase courte ; le reste à l'écran.
4. **Fusion** (`JarvisCalendarReader`) : tous les agendas du compte, une occurrence par (`UID`, début) — une
   invitation présente dans l'agenda pro et l'agenda perso n'apparaît qu'une fois.
5. **Écran** : quand des rendez-vous ont été lus, la passerelle émet un ordre `afficher(piece, ["agenda:jour|semaine:
   <date>"])` ; `/jarvis` ouvre la **vue d'agenda** (frise holographique par journée, prochain mis en avant, liste
   lisible) qui lit `GET /api/jarvis/calendar/events?from=&to=` (≤ 31 jours, fusionné, filtré `user_id`).
6. **Présentation (D11)** : l'outil rend « Indisponible en présentation » (rien n'est lu), aucune vue n'est ouverte,
   l'API rend 409.
7. **Sans agenda relié** ou date hors de la fenêtre : l'outil le dit, la question se **délègue** (comportement
   antérieur conservé : « Je cherche ce qui est prévu jeudi »).
8. **Banc CONVERSATION** : 5 scénarios `AGENDA_F243` (aujourd'hui, demain, cette semaine, journée vide,
   présentation), l'agenda fictif rendu par l'outil et montré au juge (jeu à 72 scénarios).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `from`/`to` absents, inversés ou > 31 jours | refus | 400 |
| `presentation=true` sur `/events` | rien | 409 |
| Lecture impossible (chiffrement indisponible…) | l'outil rend « aucun agenda relié » ⇒ délégation | — |
| Second appel de l'outil dans la même réponse | « Lecture déjà faite » | — |
| Titre piégé (`</agenda_non_fiable>`, « ignore tes consignes ») | balise neutralisée, texte donné comme donnée | — |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `periode` | énuméré fermé `aujourdhui`, `demain`, `semaine`, `jour` |
| `jour` | énuméré fermé `""`, `lundi`…`dimanche` |
| `date` | ISO, dans la fenêtre J-1 → J+60, sinon « hors de la fenêtre » |
| `/events` | `from` < `to`, ≤ 31 jours, ISO-8601 |

## Critères d'acceptation
- [x] « Qu'est-ce que j'ai aujourd'hui ? » donne le prochain rendez-vous d'abord, sans Opus (outil + réponse du
  cerveau rapide, phrase ancrée sur le résultat — test de service ; banc A01).
- [x] Une journée vide donne une phrase courte (banc A04 ; « Aucun rendez-vous. » à l'outil).
- [x] La vue semaine s'affiche avec les deux agendas fusionnés, sans doublon de même `UID`.
- [x] En présentation, rien n'est affiché ni dit (outil, vue, API 409 ; banc A05).
- [x] À 360 px, pas de débordement horizontal (spec du composant).

## Plan de test
- **Unitaires (front)** : `agendaPieceView`, `agendaDays`, heures de Paris à travers le changement d'heure, `nextItem`,
  `friseSlot` ; composant : prochain mis en avant, semaine, jour vide, 360 px.
- **Service** `JarvisFastBrainServiceTest` : outil lu une fois, phrase ancrée, vue ouverte ; présentation ; sans agenda
  ⇒ délégation ; consigne v16, 4 outils figés.
- **Intégration** `JarvisCalendarReaderIntegrationTest` : fusion sans doublon, prochain / en cours, texte de l'outil
  (balise neutralisée, jour de la semaine, hors fenêtre, présentation), jour vide, aucun agenda, **isolation A/B**
  (outil et API), 409, 400, 401.
- **Banc** : 5 scénarios `AGENDA_F243` (jeu chargé, compte 72) ; banc réel **avant déploiement**.
- `jarvis.component.spec.ts` : `afficher(agenda:…)` ouvre la vue ; jamais en présentation.

## Impact
- **Aucune table.** **Endpoint** nouveau `GET /api/jarvis/calendar/events`.
- **Backend** : `JarvisCalendarReader` (nouveau), `JarvisCalendarController`, `JarvisFastBrainPrompt` (**v16**, outil
  `agenda`, section « L'agenda », exemple « aujourd'hui »), `JarvisFastBrainService` (boucle d'outil, `readAgenda`,
  `agendaView`, banc), `JarvisShowCommand` (préfixe `agenda:`), `JarvisConversationBench` / `JarvisFastBrainBenchService`
  (agenda fictif par scénario), `conversation-scenarios.json` (+5).
- **Frontend** : `jarvis-calendar.ts` (types, client `events`, fonctions), `jarvis-agenda.component.ts` (nouveau),
  `jarvis.component.{ts,html}`, `scenes/jarvis-scene-registry.json` (outil `agenda`).
- **Docs** : `DESIGN_SYSTEM.md` §23 (vue d'agenda, aucune couleur nouvelle).

**Préoccupation transversale « Contexte tenant »** : `JarvisCalendarReader` (toute lecture par `user_id` du
principal ou de la requête du cerveau rapide), `JarvisCalendarController.events`, `JarvisFastBrainService.readAgenda`.

## Arbitrages
- **Vue d'agenda dédiée** au lieu d'un type de pièce `AGENDA` dans la galerie F-237 (même choix que la visionneuse
  de courriel de F-242) : l'agenda n'est pas une pièce de chantier, et la galerie suppose un index de pièces.
- **Ouverture de la vue par la passerelle** (ordre `agenda:` émis dès que l'outil a lu des rendez-vous), sans compter
  dans les trois pièces d'initiative : le modèle n'a pas à appeler `afficher` en plus.
- **Le jour de la semaine est résolu par la passerelle** (énuméré), jamais calculé par le modèle.
- **Coût** : l'appel qui suit l'outil reste étiqueté `FAST` (l'étiquette `AGENDA` de F-244 n'est pas branchée :
  quelques centièmes de centime, ventilation sans enjeu).
- **Banc réel non lancé dans la vague** (clé et coût) : précondition de déploiement, comme v14/v15.

## Hors périmètre
Poser un rendez-vous (SF-243-03) ; repérer les rendez-vous dans les mails (SF-243-04) ; l'affichage pointillé d'un
rendez-vous proposé (SF-243-04).
