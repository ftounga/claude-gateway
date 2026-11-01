# Mini-spec — F-231 / SF-231-04 Confirmer à voix haute

## Identifiant
`F-231 / SF-231-04` — Feature parente : F-231 « Le mode Jarvis » (cadrage, décision **D4**).
Statut : `ready` — 2026-11-01 — Branche `feat/SF-231-04-confirmer-a-voix-haute`. Dépend de SF-231-02/03 (mergées).

## Objectif
Une action qui engage n'est jamais déclenchée par une erreur de transcription : Jarvis la reformule, attend un « oui » net, et ne fait rien sur « non », le silence ou le doute.

## Comportement attendu

### Cas nominal
1. **Outils en `ask`** (F-121-02, F-38) : l'événement `confirm_request` du tour est **dit** — « Je vais lancer la commande « git push ». Tu confirmes ? » (commande, modification de fichier, courriel à soi, autre outil) — puis Jarvis **écoute** la réponse.
   - « oui » net ⇒ `POST /chat/confirm` `{decision: "allow"}` ⇒ « C'est parti. »
   - « non » net ⇒ `{decision: "deny", reason: "Refusé à voix haute (Jarvis)."}` ⇒ « D'accord, je n'y touche pas. »
   - doute ou silence ⇒ **rien** n'est transmis : « Je n'ai pas compris : je ne fais rien. Tu peux confirmer depuis le terminal. » — la demande reste ouverte au terminal, et l'échéance de la passerelle vaut refus (le silence ne vaut jamais accord).
   - Tranchée ailleurs (`confirm_resolved`) ⇒ la question est oubliée.
2. **Actions qui engagent décidées par l'agent** (lancer un chantier en autonome, envoyer un courriel, valider une étape, publier, pousser du code) : la consigne orale (SF-231-03, **dans le message**) demande de les **reformuler** dans la ligne 🎙 terminée par « Tu confirmes ? » et de **n'agir qu'au tour suivant sur un « oui »**. L'écran repère « Tu confirmes ? » en fin de phrase, écoute, et **n'envoie au cerveau qu'un « oui » ou un « non » nets** ; un doute n'est jamais envoyé (« Je n'ai pas compris : je ne fais rien. »).
3. **Question structurée** (`question_request`, F-164) : « J'ai une question pour toi, à l'écran du terminal. » (non répondue à la voix).

### Classement de la réponse (`classifyConfirmation`)
- **oui** : « oui », « ouais », « oui vas-y », « oui c'est bon », « oui je confirme », « je confirme »… — sans aucune négation (« pas », « mais », « attends », « non »…).
- **non** : « non », « non merci », « surtout pas », « pas question », « annule », « ne fais rien »…
- **doute** : tout le reste (« d'accord », « vas-y » seul, « oui mais attends », phrase vide).

### Contrat API
Aucun nouvel endpoint. Consommés : `POST /api/workspaces/{id}/chat/confirm` (existant, isolé), événements SSE `confirm_request`, `confirm_resolved`, `question_request`.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| Transmission de la réponse en échec | message « confirme depuis le terminal » |
| Réponse ambiguë / silence | rien n'est transmis ni envoyé |
| « oui » transcrit d'une phrase plus longue (« oui pas maintenant ») | doute |

## Contraintes de validation
Liste fermée des « oui » et « non » ; toute négation dans la phrase annule un « oui ». Détail d'outil dit : ≤ 140 caractères.

## Critères d'acceptation
- [ ] Une autorisation d'outil est dite puis accordée sur « oui », refusée sur « non ».
- [ ] Un doute ou un silence ne transmet rien et n'envoie rien.
- [ ] La consigne orale demande de reformuler et de confirmer les actions qui engagent (dans le message, jamais le système).
- [ ] Après « Tu confirmes ? », seule une réponse nette repart au cerveau.

## Plan de test
- **Front** : `jarvis-confirm.spec` (classement oui/non/doute, reformulation, détection de « Tu confirmes ? »), `jarvis.component.spec` (oui ⇒ allow ; doute ⇒ rien ; réponse verbale filtrée).
- **Back** : `AtelierChatServiceVoiceTurnTest` (la consigne du tour né à la voix contient la règle de confirmation, hors système).
- **Isolation** : la décision passe par la route existante `chat/confirm` (`requireTerminalAccess` + `requireOwned`).

## Tables / endpoints / composants impactés
Aucune table, aucun endpoint. Back : `JarvisOralNote` (une ligne). Front : `JarvisComponent`, `jarvis-confirm`.

## Préoccupations transversales
Aucune cochée.

## Hors périmètre
Répondre à voix haute à une question structurée à choix (F-164) ; la confirmation d'un geste fait à l'écran du terminal (inchangé).
