# Mini-spec — F-141 / SF-141-01 Annonce de destination + demande si ambigu

## Identifiant
`F-141 / SF-141-01`

## Feature parente
`F-141` — L'aiguilleur de sujet à la racine

## Statut
`in-progress`

## Date de création
2026-09-22

## Branche Git
`feat/SF-141-01-annonce-destination`

---

## Objectif
Quand l'agent range un **fait durable**, il **nomme la destination** (sujet/fichier) dans sa réponse en une ligne factuelle ; si la destination est **ambiguë**, il **pose la question** au lieu de deviner — sans jamais réintroduire la plomberie de fin de tour (F-125).

---

## Comportement attendu

### Cas nominal
- L'agent range un fait durable (une décision, une contrainte, un piège, un fait d'infra) dans une carte.
- Il ajoute **une ligne factuelle** disant **où** : « rangé dans `data-platform/PLAN-ACTION.md` ».
- La ligne nomme le **fichier/sujet concret**, jamais le vocabulaire de plomberie (« promotion », « fin-de-tour », « dette »…).

### Cas d'ambiguïté
- Plusieurs sujets plausibles, ou incertitude racine vs projet → l'agent **demande** (« Ce journal relève de `data-platform` ou de `lzi` ? ») **au lieu de deviner et ranger en silence**.

### Cas d'erreur / limites
| Situation | Comportement attendu |
|-----------|----------------------|
| Aucun fait durable dans le tour | Aucune annonce de destination (ne pas parler de rangement pour rien — cohérent SF-125-05) |
| Rangement échoué côté serveur | Reste silencieux sur la plomberie (F-125 intact) ; l'annonce ne porte que la destination réelle |
| Terme de plomberie | Toujours interdit dans la réponse (F-125 / SF-125-01 conservé) |

---

## Critères d'acceptation
- La consigne d'annonce de destination est **présente** dans la consigne système sur les **deux** cibles (RUNNER + SANDBOX).
- La consigne dit : nommer le fichier/sujet concret ; demander si ambigu ; ne pas narrer la plomberie.
- **Non-régression F-125** : « Tenue de la carte, en silence » reste présent, le strip `fin-de-tour` (`stripTurnMetadata`) est inchangé, la doctrine « carte silencieuse » n'est pas supprimée — seule la **destination** devient une information autorisée (nuance cadrage §2).
- `regles.md` (paquet `savoir-durable`) est mis à jour pour dire la même nuance, et le semis reste conforme (le contrôle `ruleMissingFrom` du seeder passe toujours).

---

## Plan de test minimal
- **Unitaire (prompt) — SANDBOX** : `theDestinationAnnounceDoctrineIsPresentOnASandboxProject` — la consigne contient l'annonce de destination + la demande si ambigu, et coexiste avec les 6 doctrines existantes.
- **Unitaire (prompt) — RUNNER** : `theDestinationAnnounceDoctrineIsPresentOnARunnerProject` — idem + non-régression du rôle RUNNER (`bash (ls, find, grep -n)`).
- **Non-régression F-125** : les tests `theCardSilenceDoctrineIsPresentOn*` restent verts ; `stripTurnMetadata` inchangé (test existant `theEssentialMarkerDoesNotCollideWithTheFinDeTourMarker`).
- **Doctrine `regles.md`** : test de présence de la nuance destination (paquet `savoir-durable`) — vérifie que le fichier livré porte la consigne et reste semé (via `GovernancePackageSeederTest` existant ou assertion dédiée).
- **Isolation** : inchangée — prompt-only, aucun accès données nouveau ; la consigne système est déjà construite par `user_id`+workspace possédé (`requireOwned`).

---

## Tables / endpoints / composants impactés
- `AtelierChatService` : nouvelle constante `DESTINATION_ANNOUNCE_DOCTRINE` + append dans `buildSystemPrompt` ; ajustement du wording de `CARD_SILENCE_DOCTRINE` pour carver l'exception « destination = info, pas plomberie ».
- `backend/src/main/resources/governance/savoir-durable/regles.md` : section « La promotion » — la nuance destination visible.
- **Aucune** table, **aucun** endpoint, **aucune** migration.

## Préoccupations transversales
- **Auth / Principal** : inchangé.
- **Contexte tenant** : inchangé (prompt construit par `requireOwned`).
- **Navigation / routing** : aucune.
- **F-125 (carte silencieuse)** : composant impacté = `CARD_SILENCE_DOCTRINE` + `stripTurnMetadata` (vérifié inchangé) ; c'est la seule préoccupation cochée, liste ci-dessus.

## Mise à jour runner
**Non.** Prompt/doctrine + ressource `regles.md` uniquement.

## Hors périmètre
- L'aiguillage à la racine (existant/transverse/nouveau/mix) → SF-141-02.
- La création de sujet → SF-141-03. Le reclassement → SF-141-04.
