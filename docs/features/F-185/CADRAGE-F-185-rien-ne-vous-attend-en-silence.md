# Cadrage F-185 — Rien ne vous attend en silence

**Demande du PO, 2026-10-10** : *« Que penses-tu de rajouter des notifications pour les réponses aux questions arrivées, les attentes de confirmation… Sois complet et cadre bien la feature. »* Puis : *« Je veux aussi une notification quand la réponse du message dans le terminal est arrivée. »*

## 1. Existant vérifié

| Brique | État |
|---|---|
| F-153 | Web Push (VAPID) **configuré en prod**. Trois événements : tour terminé (« Une réponse est prête »), autorisation demandée, question posée (F-164-05). Charge **neutre** (D4) ; le clic ouvre le bon terminal. |
| F-153 / SF-153-01 | Signal dans l'onglet (préfixe de titre + favicon) quand l'onglet est caché : tour terminé et autorisation **seulement**. |
| Activation | Un seul point d'entrée : **Paramètres > Notifications** (`PushActivationService`). |
| F-42 | Alerte de quota (hors périmètre ici). |
| F-175 | Fil des attentes, relance à J+3, visible **seulement dans l'écran**. |

**Constat en prod (2026-10-10)** : la table `push_subscriptions` est **vide**. Aucun appareil n'est abonné, donc la notification « Une réponse est prête », qui existe déjà, n'arrive nulle part. L'activation est enfouie dans les paramètres et n'est jamais proposée.

**Engagement F-153 non tenu** : D7 promettait « pas de notification si l'onglet de ce tour est au premier plan ; anti-doublon par (tour, transition) ». L'émetteur envoie **toujours**.

## 2. Événements à notifier (catalogue)

| Événement | Titre (neutre, D4) | Source |
|---|---|---|
| Réponse arrivée (tour terminé) | Une réponse est prête | existant |
| Autorisation demandée | Une autorisation est demandée | existant |
| Question posée | Une question vous attend | existant |
| **Plan à approuver** (`exit_plan_mode`) | Un plan attend votre accord | **nouveau** (aujourd'hui confondu avec « réponse prête ») |
| **Validation en fin de tour** (gouvernance, passation, attente à fermer) | Une validation vous attend | **nouveau** |
| **Délai écoulé** : question décidée par défaut, commande refusée faute de réponse | L'agent a continué sans vous | **nouveau** |
| **Rappel** d'une question, 2 min avant l'expiration | Une question attend toujours | **nouveau** |
| **Tour arrêté** : erreur, quota épuisé, coupure | Le travail s'est arrêté | **nouveau** (exclu volontairement aujourd'hui) |
| **Poste déconnecté** pendant un tour | Votre poste ne répond plus | **nouveau** |
| **Attentes à relancer** (F-175) | Des attentes sont à relancer | **nouveau**, au plus un récapitulatif par jour |

## 3. Décisions PO (2026-10-10, recommandations validées)

- **D1 — Charge push neutre** (D4 de F-153 maintenue) : aucun nom de sujet ni contenu sur l'écran verrouillé. **Dans l'app** (centre, bandeau), authentifiée, le nom du sujet **est** affiché.
- **D2 — Centre de notifications maintenant** : une cloche, l'historique et les non-lus, avec un lien vers le terminal.
- **D3 — Ordre** : F-184 (PDF) est terminée d'abord, F-185 ensuite.
- **D4 — Pas d'action depuis la notification** (pas de « Autoriser / Refuser » sur l'écran verrouillé). Le clic ouvre le terminal, rien de plus.
- **D5 — Ce qui coûte une décision passe toujours** : autorisation et question ne sont **jamais** coupées par les heures calmes. Seul leur canal se règle.
- **D6 — Réponse arrivée dans l'app** : si vous êtes ailleurs dans l'application (autre terminal, autre écran), un bandeau « Réponse arrivée — <sujet> » avec un lien s'affiche, en plus de la cloche.

## 4. Découpage

| SF | Titre | Estimation |
|---|---|---|
| SF-185-01 | **Activer les notifications là où on les attend** : proposition d'activation sur cet appareil (bandeau refermable dans le terminal, rappel au premier tour long) et état visible « notifications actives sur cet appareil » | 1 j |
| SF-185-02 | **Le catalogue des événements** : un type par événement, titres précis, branchement des nouveaux (plan, validation, délai écoulé, arrêt, poste déconnecté) | 1,5 j |
| SF-185-03 | **D7 enfin tenue** : pas de push si le terminal est regardé (présence, onglet visible) ; anti-doublon par (tour, événement) | 1 j |
| SF-185-04 | **Le centre de notifications** : persistance (`user_id`), cloche, non-lus, historique, lien vers le terminal ; bandeau « Réponse arrivée — <sujet> » quand on est ailleurs dans l'app | 2 j |
| SF-185-05 | **Rappel avant expiration** + alerte d'onglet pour la question et le plan | 1 j |
| SF-185-06 | **Préférences** : activer ou couper chaque événement, heures calmes (sauf D5) | 1,5 j |
| SF-185-07 | **Récapitulatif quotidien** des attentes à relancer (F-175) | 1 j |

## 5. Préoccupations transversales

- **Contexte tenant** : nouvelle table de notifications filtrée par `user_id`. Composants qui résolvent le tenant : `PushNotificationService`, le futur `NotificationCenterService`, `PushSubscriptionRepository`.
- **Navigation** : cloche dans l'en-tête de la coquille authentifiée ; lien profond `/atelier/{workspaceId}` (existant, SF-153-03).

## 6. Hors périmètre

- Notifications natives ou de store, courriel, SMS.
- Contenu du tour dans la notification push (D1).
- Boutons d'action dans la notification (D4).
- Notifications Vigie / Teams (autre domaine).
- Routines F-183 : elles **utiliseront** ce catalogue quand elles seront livrées.
