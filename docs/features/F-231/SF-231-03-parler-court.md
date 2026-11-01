# Mini-spec — F-231 / SF-231-03 Parler court

## Identifiant
`F-231 / SF-231-03` — Feature parente : F-231 « Le mode Jarvis » (cadrage, décisions **D3, D11**).
Statut : `ready` — 2026-11-01 — Branche `feat/SF-231-03-parler-court`. Dépend de SF-231-02 et SF-231-06 (mergées).

## Objectif
Jarvis répond à l'oral en une phrase courte, accuse réception d'un travail long puis en annonce la fin, et s'arrête quand on lui dit « stop ».

## Comportement attendu

### Cas nominal
1. **Consigne orale dans le message.** Un tour né à la voix (marque SF-231-06) reçoit, **après la parole de l'utilisateur, dans le message du tour**, un texte **fixe** (`JarvisOralNote`) : commencer la réponse par une ligne « 🎙 » d'au plus deux phrases, sans liste ni code ; un message pour un tiers est préparé **sans jamais être envoyé** et la ligne 🎙 dit « Message pour X prêt, à envoyer depuis le terminal » ; sur « envoie-le-moi », l'adresser au PO par `email_me`.
   - **Jamais dans la consigne système** : le préfixe reste stable, le cache intact (règle « préfixe stable = rien de volatil »).
   - Le message **rangé dans le fil** reste la parole de l'utilisateur. Un tour tapé : consigne inchangée à l'octet près.
2. **Accusé** : si la réponse n'est pas arrivée **5 s** après l'envoi, Jarvis dit « Je m'en occupe. » sans quitter l'état réflexion ; la réponse coupe l'accusé s'il parle encore.
3. **Annonce de fin** : un tour de **30 s ou plus** est dit « C'est terminé. <phrase> ».
4. **Interruption par la parole** : « stop », « arrête », « annule », « laisse tomber », « interromps » (dit seul, « Jarvis » toléré devant) interrompt le travail en cours (`POST /chat/interrupt`, comme le bouton du terminal) et Jarvis dit « J'arrête. » ; rien ne tourne ⇒ « Rien ne tourne en ce moment. ». Un appui pendant que Jarvis parle coupe sa voix (SF-231-02).

### Contrat API
Aucun nouvel endpoint. Consommés : `POST /chat/stream` (+ `via`), `POST /chat/interrupt`, `POST /api/jarvis/speech`.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| L'agent ne pose pas de ligne 🎙 | repli : deux premières phrases de prose (SF-231-02) |
| La voix échoue pendant l'accusé | silencieux (la réflexion continue) |
| « arrête le serveur de recette » | **pas** un ordre d'arrêt : la phrase part au cerveau |
| Interruption refusée par la passerelle | ignorée ; le tour finit normalement |

## Contraintes de validation
Accusé à 5 s, annonce au-delà de 30 s (constantes `ACK_DELAY_MS`, `LONG_TURN_MS`). Ordre d'arrêt : correspondance exacte d'une liste fermée.

## Critères d'acceptation
- [ ] Tour né à la voix : la consigne orale est dans le message envoyé au modèle, absente du système, absente du fil.
- [ ] Tour tapé : aucune consigne orale.
- [ ] Accusé après 5 s ; « C'est terminé. » devant la phrase d'un tour ≥ 30 s.
- [ ] « Stop » interrompt le tour en cours.

## Plan de test
- **Unitaires (back)** : `AtelierChatServiceVoiceTurnTest` (consigne dans le message, pas dans le système, fil intact ; tour tapé sans consigne).
- **Front** : `jarvis-oral.spec` (annonce, ordre d'arrêt), `jarvis.component.spec` (accusé, annonce de fin, stop).
- **Isolation** : aucune donnée nouvelle ; l'interruption passe par la route existante (`requireOwned`).

## Tables / endpoints / composants impactés
Aucune table, aucun endpoint. Back : `AtelierChatService` (ajout de la consigne pour un tour marqué), `JarvisOralNote`. Front : `JarvisComponent`, `jarvis-oral`.

## Préoccupations transversales
Aucune cochée : ni auth, ni tenant (routes existantes), ni plans, ni navigation.

## Hors périmètre
La confirmation orale des actions qui engagent (SF-231-04) ; l'interruption « mains libres » par le mot d'éveil (SF-231-05). La détection de voix **par-dessus** la voix de Jarvis (barge-in acoustique) : écartée (faux déclenchements par le haut-parleur) — l'appui ou le mot d'éveil interrompent.
