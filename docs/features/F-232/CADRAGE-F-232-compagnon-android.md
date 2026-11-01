# CADRAGE F-232 — Le compagnon Android de Jarvis

**Statut :** cadrage du 2026-11-01 · **Porte d'usage levée par le PO le 2026-11-01** (« je vais vraiment l'utiliser ») : livrée dans la vague Jarvis, après F-231 · Famille « Jarvis » : F-230, F-231, **F-232**.

**Demande PO (2026-11-01)** : dire « Jarvis » sans toucher le téléphone, même verrouillé et en poche (Android).

## Pourquoi une feature à part

Une PWA ne peut pas garder le micro ouvert en arrière-plan. F-231 couvre :
- l'écran Jarvis ouvert ;
- le téléphone verrouillé, via « Ok Google, ouvre Jarvis ».

Le vrai mot d'éveil, téléphone verrouillé, exige un petit composant **natif**. Sur Android, c'est réaliste sans Play Store : un APK installé soi-même, comme les paquets du runner (F-44). Cela revient partiellement sur la décision « natif écarté » de la version mobile, d'où la porte d'usage.

## Décisions

| # | Décision |
|---|---|
| D1 | **Compagnon minimal** : service de premier plan de type micro (notification permanente « Jarvis écoute »), détection du mot d'éveil **sur l'appareil**. Aucun son ne sort avant le mot. Au mot, il ouvre la PWA en mode Jarvis (F-231). |
| D1b | **Il parle aussi téléphone verrouillé.** Quand un tour lancé via Jarvis se termine (ou qu'une alerte de veille arrive), le compagnon reçoit l'annonce et la **dit à voix haute** même écran éteint (« Le message pour X est prêt », « C'est fait : … »), puis écoute la réponse sans mot d'éveil pendant quelques secondes. Sans compagnon, la PWA en arrière-plan ne peut que sonner une notification. | Conversation continue toute la journée, sans relancer |
| D1c | **Installation** : téléchargement de l'APK depuis l'application, autorisation « sources inconnues » pour Chrome, permissions micro, notifications et exemption d'optimisation de batterie demandées au premier lancement avec explication, puis appairage par code. Démarrage automatique avec le téléphone. Mise à jour proposée par l'application, confirmée par Android. |
| D2 | **Distribution** : APK signé, téléchargeable depuis l'application (même chemin que les paquets du runner), mise à jour proposée. Pas de Play Store. |
| D3 | **Interrupteur** : marche/arrêt depuis la notification, arrêt automatique sous un seuil de batterie. |
| D4 | ~~Porte d'usage~~ levée par le PO le 2026-11-01. La mesure D9 de F-231 reste lue à J+14 pour juger l'usage réel. |

## Découpage prévisionnel

| SF | Objet | Estim. |
|---|---|---|
| SF-232-01 | L'APK : service micro, mot d'éveil, ouverture de Jarvis, interrupteur. | 2 j |
| SF-232-03 | Les annonces téléphone verrouillé (D1b) : réception des fins de tour Jarvis et alertes de veille, lecture à voix haute, écoute courte de la réponse. | 1,5 j |
| SF-232-02 | Le servir : téléchargement depuis l'application, signature, mise à jour. | 1 j |

## Préoccupations transversales

- **Navigation** : ouverture de `/jarvis` depuis le compagnon (lien profond F-231).
- **Auth** (corrigé le 2026-11-01) : pour recevoir les annonces téléphone verrouillé (D1b), le compagnon est **appairé** au compte par un code affiché dans l'application (même geste que l'appairage d'un poste). Il reçoit un **jeton dédié**, limité à la réception des annonces Jarvis (aucune autre route), révocable depuis l'application et visible dans la liste des appareils. Les écrans restent ceux de la PWA, avec sa propre session. Composants à vérifier : filtre d'authentification, liste des appareils et révocation, isolation `user_id` des annonces.

## Hors périmètre

- iPhone.
- Montre.
- Écoute continue envoyée à un service distant.
