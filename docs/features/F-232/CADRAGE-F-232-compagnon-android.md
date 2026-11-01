# CADRAGE F-232 — Le compagnon Android de Jarvis

**Statut :** cadrage du 2026-11-01 · **En attente : porte d'usage** · Famille « Jarvis » : F-230, F-231, **F-232**.

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
| D2 | **Distribution** : APK signé, téléchargeable depuis l'application (même chemin que les paquets du runner), mise à jour proposée. Pas de Play Store. |
| D3 | **Interrupteur** : marche/arrêt depuis la notification, arrêt automatique sous un seuil de batterie. |
| D4 | **Porte d'usage avant tout développement** : F-231 utilisée au moins 5 jours ouvrés par semaine pendant 2 semaines (mesure D9 de F-231). Sinon, F-232 reste en attente. |

## Découpage prévisionnel

| SF | Objet | Estim. |
|---|---|---|
| SF-232-01 | L'APK : service micro, mot d'éveil, ouverture de Jarvis, interrupteur. | 2 j |
| SF-232-02 | Le servir : téléchargement depuis l'application, signature, mise à jour. | 1 j |

## Préoccupations transversales

- **Navigation** : ouverture de `/jarvis` depuis le compagnon (lien profond F-231).
- **Auth** : le compagnon ne porte **aucun** jeton ; la session reste celle de la PWA.

## Hors périmètre

- iPhone.
- Montre.
- Écoute continue envoyée à un service distant.
