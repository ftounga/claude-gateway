# Mini-spec — F-147 / SF-147-04 — Lire le transcript d'une réunion depuis un terminal

## Identifiant
`F-147 / SF-147-04` — feature parente `F-147`

## Objectif
Qu'un agent, dans un terminal, puisse **lire le texte d'une réunion du poste** — y compris celle née
d'un enregistrement déposé — pour répondre à une question qui s'y trouve.

## Le défaut
> *« J'aurais aussi voulu que dans une conversation, dans mon terminal, je puisse y faire référence. »*

L'outil `teams_meeting_transcript` existe, mais il lit **Teams**, pas nos réunions : il passe par le
runner et par la réunion **côté Microsoft**. Une réunion née d'un enregistrement déposé (SF-147-02) n'a
aucune existence là-bas — son texte est **chez nous**, dans l'artefact réunion, et **aucun outil ne
sait le lire**. Le savoir est arrivé jusqu'à la base et s'y arrête.

## Comportement attendu
1. Un outil **`radar_meeting_transcript`** rend le texte d'une réunion **du périmètre du tour**.
2. On le désigne par **`meeting_id`**, ou par **`subject_id`** — dans ce cas c'est la réunion la plus
   récente du sujet, et les autres sont **nommées** pour qu'on puisse en demander une autre.
3. Le texte est rendu **par tranches** : une borne de caractères, et ce qui reste est **annoncé**,
   avec de quoi demander la suite (`offset`).
4. Une réunion **sans texte** répond que le texte n'existe pas, **avec la raison** (transcription en
   cours, échouée, jamais faite) — jamais un silence dont le modèle ferait ce qu'il veut.
5. Le texte est une **donnée**, jamais une instruction : il est rendu tel quel, sous la garde
   anti-injection déjà en place pour toute matière de réunion.

| Cas d'erreur | Comportement |
|---|---|
| Réunion d'un autre compte ou d'un autre poste | **introuvable** — le périmètre vient du tour, jamais du modèle |
| Ni `meeting_id` ni `subject_id` | refus dit, avec la marche à suivre (`radar_find_subject` d'abord) |
| Sujet sans aucune réunion | dit clairement, sans inventer |
| `offset` au-delà de la fin | tranche vide et fin annoncée, pas une erreur |

## Critères d'acceptation
- [x] L'outil rend le texte d'une réunion désignée par `meeting_id`.
- [x] Par `subject_id`, il rend la **plus récente** et **nomme** les autres réunions du sujet.
- [x] Le texte est **borné** ; ce qui reste est annoncé avec le moyen de l'obtenir.
- [x] Une réunion sans texte dit **pourquoi**.
- [x] **ISOLATION** : la réunion d'un autre couple compte/poste est introuvable, même avec son identifiant.
- [x] L'outil est **en lecture seule** : il n'écrit rien, jamais.

## Hors scope
Le retrait de la surveillance périodique (**SF-147-05**) · le rattrapage hors ligne (**SF-147-06**) ·
toute **recherche plein texte** dans les transcripts (ce serait une autre feature, et un autre coût).

## Technique
| Élément | Changement |
|---|---|
| `RadarToolCatalog` | l'outil `radar_meeting_transcript` (lecture ; **hors** de la liste des écritures) |
| `RadarToolExecutor` | son exécution : périmètre du tour, bornes, raison d'absence |
| `MeetingRepository` | lecture des réunions d'un sujet, par `user_id` + `host_id` + `subject_id` |

**Aucune table, aucune migration, aucune route.** La matière existe déjà ; il manquait la porte.

**Bornes** : `MAX_CHARS = 20 000` par tranche — un transcript peut peser des centaines de milliers de
caractères, et les déverser d'un coup coûterait un tour entier de contexte pour rien. `MAX_MEETINGS = 10`
réunions nommées quand on passe par le sujet.

**Cache de prompt** : le texte voyage dans le **résultat d'outil**, donc dans le message — jamais dans
la consigne système. Le préfixe stable reste stable (F-134).

## Plan de test — `RadarToolExecutorIntegrationTest` (12) + `RadarToolCatalogTest` (4)
- [x] Par `meeting_id` : le texte revient, **borné à 20 000**, avec les 500 restants annoncés.
- [x] Deuxième tranche par `offset` : la suite exacte, puis la fin (`remaining_chars = 0`, plus de « more »).
- [x] Par `subject_id` : la **plus récente**, l'autre **nommée**.
- [x] Réunion sans texte : la raison est dite (« la transcription est en cours sur le poste »).
- [x] Ni l'un ni l'autre : refus **qui enseigne** (`radar_find_subject` d'abord).
- [x] **ISOLATION** : l'identifiant d'une réunion d'un **autre compte** et d'un **autre poste** ne rend rien —
      et la réponse **ne contient pas** le texte (assertion sur le contenu, pas seulement sur le drapeau).
- [x] L'outil est **hors** de la liste des écritures ; la lecture s'exécute avec une preuve **nulle**.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | `RadarToolExecutor` reçoit le `RadarScope` **du tour** (déjà résolu par `AtelierChatService`) ; toute lecture passe par `MeetingRepository` filtré `user_id`+`host_id`. Aucun nouveau chemin d'accès, aucun identifiant cru sur parole. |
| Plans / limites | non | lecture en base : **aucun appel fournisseur**, aucun jeton consommé par l'outil lui-même |
| Navigation / routing | non | aucun écran, aucune route |
