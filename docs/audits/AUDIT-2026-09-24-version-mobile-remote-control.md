# Audit — Version mobile / « Remote Control » de l'Atelier

> Demandé par le PO le 2026-09-24 : évaluer, **sans se fermer**, une **version mobile**. Besoin cœur :
> intervenir chez **plusieurs clients en se déplaçant** — depuis le **téléphone**, piloter les terminaux
> qui tournent sur le PC (qui reste allumé/connecté), à la manière du **Remote Control de Claude Code** ;
> et recevoir des **notifications** (« la réponse est prête », « une autorisation est demandée »).
> À l'époque, le **natif** avait été jugé *overkill* (aucun audit persisté retrouvé — décision verbale).
>
> **Méthode :** 2 sous-agents lecture-seule (pilotage à distance · UX mobile/PWA/notifications) + code
> cité `fichier:ligne`.

## 0. Verdict

**Le « Remote Control » est déjà à ~90 % construit.** L'architecture serveur est **déjà** celle d'un
pilotage multi-appareils : il ne manque **ni brique de pilotage, ni réécriture native**. Il manque **deux
choses** : rendre l'écran **confortable au doigt** (UX mobile) et **pouvoir alerter** l'utilisateur quand
il n'a pas l'écran ouvert (**notifications push**). **Reco : PWA installable + Web Push, pas de natif.**

## 1. Ce qui marche DÉJÀ depuis un téléphone (navigateur, même compte) — rien à écrire

Grâce à **F-84 « le tour vit côté serveur »** (le flux SSE n'est qu'une *vue*, le tour tourne sur la
gateway, l'attente d'autorisation est un **état** interrogeable) :
- **Se connecter** (JWT/OAuth) et retrouver **tous ses postes/projets** — le runner est lié à
  l'**utilisateur, pas à l'appareil** (`RunnerConnection` clé `(hostId,userId)`), JWT **sans
  device-binding**.
- **Voir en direct** un tour lancé depuis le PC, **même sur un autre pod** (relais cross-pod
  `RemoteTurnSource`) et **même derrière un proxy** (suivi par fenêtres, contournement Netskope).
- **Piloter** : envoyer une **précision** pendant le tour (`/steer`), **interrompre** (`/interrupt`),
  **répondre à une demande d'autorisation** (`/confirm`), lancer une nouvelle demande, changer de
  poste/projet — endpoints `AtelierChatController` (`/attach` `/steer` `/interrupt` `/confirm` `/resume`).
- **Dicter** au micro (F-145) et **téléverser** un fichier — marchent au navigateur mobile.
- **Prérequis = exactement celui du Remote Control** : le **PC allumé + runner connecté (WSS)**. Le tour
  vit sur la gateway ; seuls les appels d'outil (bash…) sont relayés au poste.

**Conclusion 1 :** un téléphone connecté au compte **voit et pilote déjà** les terminaux du PC. La note
mémoire « quitter l'écran tue le tour » est **levée par F-84**.

## 2. Ce qui manque — seulement deux lots

### Lot A — UX mobile (l'essentiel du travail visible, pur front)
- **Shell global (nav)** : `shell.component` — 7 liens horizontaux, **0 media-query, pas de hamburger**
  → déborde à 400px. C'est la porte d'entrée de l'app en mobile.
- **Shell Atelier** : `atelier.component.scss:9` = `grid-template-columns: 280px 1fr`, **0 `@media`** →
  la sidebar fixe de 280px laisse ~120px au terminal. **C'est l'écran du besoin.**
- **Barre d'outils du terminal** : nombreux boutons (slash, mentions, dictée, dépôt, Teams…), pas
  dimensionnée pour 400px.
- **Bonne nouvelle** : la charte **prévoit déjà** le mobile pour `/forge` (<820px), la Mosaïque et la
  Vigie (`_forge-layout-shell.scss:148`, `DESIGN_SYSTEM.md:686/777`). **Précédent à étendre, pas à
  inventer.**

### Lot B — Notifications (la seule vraie brique nouvelle)
- **Il n'existe AUCUNE notification aujourd'hui** — pas même en onglet ouvert (grep front : zéro
  `Notification`, `document.title=`, `visibilitychange`, favicon dynamique). **F-126 « questions
  repérables navigateur » = navigation intra-page** (rail « Vos questions », ancres), **pas** une
  notification.
- **Aucune infra push** (backend + front) : zéro VAPID / web-push / FCM / service worker / endpoint
  d'abonnement.
- **Pourquoi c'est critique** : une **demande d'autorisation a un `timeoutMs`** et le **silence vaut
  refus** (`/confirm`). En déplacement, sans alerte, une commande peut être **refusée par expiration**.
- **F-84 rend le push réellement utile** : le tour et l'état d'autorisation persistent côté serveur ;
  la gateway a donc **déjà l'état à notifier** (transitions « terminé » / « autorisation demandée »).

## 3. Les trois options

| Critère | 1. Web responsive | **2. PWA + Web Push** | 3. Natif |
|---|---|---|---|
| Effort | Faible (media-queries) | Moyen (responsive + `ng add @angular/pwa` + VAPID + endpoint abonnement + émetteur push) | Élevé (nouvelle appli, 2 stores, ré-auth, UI réécrite) |
| Notif app fermée / tél verrouillé | **Non** | **Oui** (Android plein ; iOS ≥ 16.4 **si** PWA ajoutée à l'écran d'accueil) | Oui (plus fiable sur iOS) |
| Installable / offline | Non | Oui (sans store) | Oui (store) |
| **Appareil managé banque** | **Le plus permissif** (juste un navigateur) | Installable **si** MDM l'autorise ; push potentiellement bridé sur iOS géré | **Le plus bloqué** (app hors store) |
| Cohérence Gateway-First | Totale | Totale (push émis par la gateway) | Rupture (client lourd hors archi web) |

## 4. Recommandation : **Option 2 (PWA + Web Push), pas de natif**

Le **natif reste overkill** : l'app est 100 % web, **F-84 a mis le tour et l'état d'autorisation côté
serveur** (rien à répliquer côté device), et un **appareil managé de banque** est justement le contexte
où installer une app hors store est le **plus bloqué** — le natif maximiserait la friction pour un gain
quasi nul. Son seul avantage net (push fiable sur **iOS géré**) ne justifie pas le coût.

Le **web responsive seul est nécessaire mais insuffisant** : il rend l'app pilotable au doigt mais ne
répond **pas** au besoin « être prévenu quand la réponse est prête / une approbation est demandée ».

La **PWA + Web Push** coche les deux besoins, sans store, dans l'archi web et Gateway-First.
**Réserve à lever avant de s'engager** : sur **iPhone managé**, le Web Push exige la PWA « sur l'écran
d'accueil » (iOS ≥ 16.4) et le **MDM peut le brider** → **à vérifier sur le parc cible** (CAGIP…).
Android : sans réserve.

## 5. Minimum viable — 3 paliers, de la valeur à chaque étape

1. **Responsive du chemin critique** (pas toute l'app) : shell global (nav → menu repliable), shell
   Atelier (`280px 1fr` → drawer/1 colonne <820px, sur le modèle `_forge-layout-shell.scss:148`), barre
   d'outils du terminal. → *piloter au doigt.*
2. **Signal en onglet ouvert** (quasi gratuit, même sans PWA) : titre d'onglet + favicon qui s'allument
   quand `document.hidden` et qu'un tour **se termine** ou **passe en attente d'autorisation**. L'état est
   déjà exposé par F-84/SF-84-03. → *première alerte, à moindre coût.*
3. **Web Push** (app fermée / tél verrouillé) : `ng add @angular/pwa`, endpoint d'abonnement
   `PushSubscription` **filtré `user_id`**, clés VAPID, émetteur côté gateway branché sur les **deux
   transitions d'état** de F-84 (**terminé** / **autorisation demandée**). → *le vrai Remote Control.*

## 6. Préoccupations transversales / gouvernance
- **Auth / tenant** : le push doit être **scellé par `user_id`** — ne notifier que les appareils du
  propriétaire ; abonnements révocables.
- **Plans / limites** : émission de push bornée ; pas de fuite de contenu dans la notification (titre
  neutre, le détail derrière l'ouverture authentifiée).
- **Multi-features (CLAUDE.md)** : aucune feature mobile/PWA/push n'existe dans `PRODUCT_SPEC.md`. Les
  trois paliers sont des **features distinctes** à créer + mini-specs. Décision iOS-managé (§4) à
  trancher **avant** le palier 3.

## 7. Réponse courte au PO
Oui, une version mobile est **bénéfique et peu coûteuse** — parce que **le plus dur (le pilotage à
distance) est déjà fait** (F-84). Pas de natif : une **PWA installable + Web Push**, livrée en 3 paliers
(responsive → signal in-tab → push), répond pile à ton usage « PC allumé, je pilote et je suis alerté
depuis le téléphone en me déplaçant ». Seul vrai point à vérifier avant le push : le **téléphone managé**
(surtout iOS) sur le parc client.
