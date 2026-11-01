# Mini-spec — F-235 / SF-235-09 — La personnalité de Jarvis (D18)

## Identifiant
`F-235 / SF-235-09` · Feature parente `F-235` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-235-09-personnalite`

## Objectif
> Donner à Jarvis le ton d'un majordome britannique (courtois, posé, concis, humour sec au plus une touche), réglable
> par compte (humour aucun / léger), sans jamais toucher la consigne système du terminal ni imiter l'œuvre.

## Comportement attendu
### Cas nominal
- **Cerveau rapide** : consigne système = `CORE` + bloc « Personnalité » en **deux variantes figées**
  (`PERSONALITY_LIGHT`, `PERSONALITY_NONE`) choisies par le réglage du compte ; aucune donnée volatile ; version de la
  consigne passée à 2.
- **Tours Opus vocaux** (délégués ou mode actuel) : `JarvisOralNote.appendTo(consigne, humour, sober)` ajoute **au
  message** la personnalité de la ligne 🎙 (deux variantes figées) et, si besoin, la note `[Jarvis — ton sobre, sans
  humour]`. La consigne système du terminal est **identique** (test d'égalité).
- **Ton sobre forcé** (`JarvisSobriety`, déterministe) : alerte de veille du compte depuis moins de 2 h, ou parole qui
  parle d'incident / erreur / panne / échec / alerte / urgence / sujet sensible (liste fermée) ⇒ note sobre, pour le
  cerveau rapide et pour Opus.
- **Aucune imitation** : une phrase du cerveau rapide qui cite Stark, Tony Stark, Iron Man, Marvel ou Avengers absent
  du contexte et de la parole est **retirée** avant l'écran (compteur `imitationsRemoved` dans `done.guards`).
- **Préférences** : table `jarvis_preferences` (`user_id` clé, `humour` NONE|LIGHT, `updated_at`), défaut « léger »
  sans ligne ; `GET /api/jarvis/preferences`, `PUT /api/jarvis/preferences {"humour": "NONE"|"LIGHT"}` ; purge à la
  suppression du compte ; interrupteur « Humour : aucun / léger » dans `/jarvis` (barre des réglages, à côté du mot
  d'éveil).
- **Jeu de 20 questions types** synthétiques (`backend/src/main/resources/jarvis/personality-questions.json` : 8
  courantes, 5 alertes/incidents/erreurs, 3 sujets sensibles, 4 actions), contexte fictif, aucune donnée client.

### Cas d'erreur
| Situation | Comportement | Code |
|---|---|---|
| `PUT` avec humour inconnu / corps absent | refus | 400 |
| Non authentifié | refus | 401 |
| Préférences illisibles | défaut « léger », jamais d'erreur à l'oral | — |
| Enregistrement en échec côté écran | l'interrupteur revient à l'état précédent | — |

## Critères d'acceptation
- [x] Consigne système du cerveau rapide identique octet pour octet par variante ; les deux variantes diffèrent.
- [x] Consigne système du terminal inchangée pour un tour vocal (test d'égalité avec un tour tapé).
- [x] Note « ton sobre » posée sur incident (parole) et sur alerte récente du compte (pas d'un autre).
- [x] Phrase citant l'œuvre retirée ; nom présent dans le contexte conservé.
- [x] Préférences : défaut léger, par compte, 400 sur valeur inconnue.
- [x] Jeu de 20 questions : ≥ 5 alertes, ≥ 3 sensibles, ≥ 4 actions.

## Plan de test
- Unitaires `JarvisPersonalityTest` (variantes, note orale, sobriété, imitation, préférences par compte, jeu de 20).
- Unitaires `JarvisFastBrainServiceTest` (variante du compte + note sobre dans le message ; imitation retirée).
- Unitaires `AtelierChatServiceVoiceTurnTest` (personnalité + note sobre dans le message, système identique).
- Intégration `JarvisPreferenceApiIntegrationTest` (défaut, PUT, isolation, 400, 401) — migration exécutée.
- Front `jarvis.component.spec.ts` (défaut léger, bascule, retour sur échec).

## Tables / endpoints / composants
- Table **`jarvis_preferences`** (migration `235-jarvis-preferences.xml`).
- Endpoints `GET/PUT /api/jarvis/preferences`.
- Back : `jarvis/preferences/*` (nouveau), `JarvisOralNote`, `JarvisFastBrainPrompt`, `JarvisFastBrainService`,
  `JarvisFastGuards`, `AtelierChatService` (chemin JARVIS seulement), `AccountService` (purge).
- Front : `jarvis.component.{ts,html}`, `jarvis.service.ts`, `jarvis.models.ts`.

## Préoccupations transversales
- **Auth / Principal** : nouveaux endpoints authentifiés (règle JWT par défaut), Principal inchangé ; composants :
  `JarvisPreferenceController`.
- **Contexte tenant** : `JarvisPreferenceService` (clé `user_id`), `JarvisSobriety` (alertes sous `user_id`),
  `AtelierChatService` (lecture du réglage du `user_id` du tour), `JarvisFastBrainService.systemFor(userId)`.

## Hors périmètre
Le jugement par Opus du jeu de 20 questions est porté par l'endpoint du banc (SF-235-08), qui réutilise ce jeu.

## Arbitrages (par défaut, réversibles)
- Jeu de 20 questions en **ressource principale** (et non de test) : le banc admin (SF-235-08) doit pouvoir le lire.
- Sobriété : fenêtre d'alerte de **2 h** et liste fermée de mots ; « erreur de tour » couverte par la parole du PO
  (pas de lecture de l'état du tour précédent).
- Migration numérotée 235 (numéro de la feature).
