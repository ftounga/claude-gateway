# F-122 — Mise en service automatique et guidée de la Vigie (zéro commande manuelle)

> Cadrage du 2026-09-15, à la demande du PO. **Cadrage seul, à livrer PLUS TARD** (après le débogage
> Teams en cours, SF-89-13). Décisions PO prises via AskUserQuestion (voir §2).

## 1. Le problème (vécu pendant le débogage)

Aujourd'hui, pour que la Vigie lise Teams, il faut, à la main : tuer tous les Chrome, en relancer un
avec `--remote-debugging-port=9222` **et** `--user-data-dir=...` (Chrome refuse le débogage sur le
profil par défaut), vérifier le port (`curl localhost:9222/json`), ouvrir puis se connecter à Teams,
enfin lancer le runner. Ce parcours est né du **débogage** de SF-89-12/13 — mais tel quel, il est
**impossible à commercialiser** :

> « Je sais que c'est pas possible de commercialiser une solution avec autant d'opérations manuelles.
> La plupart des personnes qui prendront la Vigie seront des managers, responsables, bien loin de la
> technique. Leur demander de démarrer des navigateurs en debug, c'est chaud. »

**La Vigie n'est pas vendable en l'état.** C'est un bloqueur go-to-market, pas un confort. La cible
(managers/responsables) ne doit taper **aucune** commande.

## 2. Décisions PO (2026-09-15)

- **Navigateur : un Chrome dédié géré par l'application** (profil séparé, contrôlé par le runner). Le
  manager ne touche jamais son navigateur personnel ; login Teams **une seule fois**, il persiste.
- **Fonctionnement en arrière-plan : la Vigie tourne seule.** Le manager ne voit aucune fenêtre au
  quotidien ; le navigateur guidé n'apparaît que pour se **(re)connecter** à Teams quand la session
  expire.

## 3. La cible

Le **runner lance et gère lui-même** le Chrome de débogage — l'utilisateur ne tape rien. Idéalement
déclenché **quand l'utilisateur active la Vigie dans l'application**, avec un **parcours UX guidé** qui
vérifie tout **avant de commencer**. C'est l'extension de **F-45** (mise en service guidée du runner)
au volet Teams/Vigie.

Précision technique honnête : « arrière-plan » ne veut pas dire *headless*. La connexion Teams (SSO +
MFA) exige une vraie session interactive ; le Chrome managé est donc un Chrome **normal mais discret**
(fenêtre minimisée / hors champ), piloté par CDP, surgi seulement pour le login. Un headless ne
passerait pas l'authentification d'entreprise.

## 4. Découpage proposé

| SF | Titre | Contenu |
|---|---|---|
| **SF-122-01** | **Le runner gère un Chrome dédié, tout seul** | Détection du chemin de Chrome par OS (macOS/Windows) ; lancement avec port de débogage + `--user-data-dir` dédié (dossier géré par le runner) ; cycle de vie (démarrer / vérifier le port / relancer s'il est mort / arrêter) ; **aucune commande utilisateur**. Fenêtre discrète (minimisée/hors champ). Un seul point d'attache stable pour l'observation réseau (SF-89-*). |
| **SF-122-02** | **Parcours UX guidé à l'activation de la Vigie** | Quand le manager active la Vigie pour un client, un assistant vérifie, en vert/rouge, **avant de commencer** : (1) runner connecté, (2) Chrome managé lancé et joignable, (3) Teams connecté (sinon ouvre la fenêtre managée pour le login **une fois**), (4) **test de lecture Teams de bout en bout** (trouver une réunion → source « réseau »). Tant que tout n'est pas vert, on ne « démarre » pas. Charte, aucune couleur nouvelle. |
| **SF-122-03** | **Tourne seule + relogin guidé à l'expiration** | La Vigie fonctionne en arrière-plan (synchro du soir + à la demande, F-100) sans fenêtre visible ; détection d'une **session Teams expirée** (redirection login / 401) → notification claire « reconnectez-vous à Teams » qui rouvre la fenêtre managée le temps du login, puis se remet en arrière-plan. |
| **SF-122-04** | **Cas d'entreprise nommés** | Diagnostics et messages clairs quand : le **remote-debugging est bloqué par une policy** d'entreprise (détecter et l'expliquer, pas un échec muet) ; Chrome absent/chemin introuvable ; proxy NTLM (réutiliser F-45/F-59). Chaque échec **nommé**, jamais un silence. |

**Ordre** : SF-122-01 (socle) → SF-122-02 (parcours) → SF-122-03 (arrière-plan/relogin) → SF-122-04
(robustesse entreprise).

## 5. Points durs à traiter (identifiés, à trancher au moment du dev)
- **Policies d'entreprise** : certaines DSI désactivent le remote-debugging de Chrome par GPO/plist →
  détecter tôt et le dire (SF-122-04). Prévoir un repli documenté (Edge Chromium ? autre canal ?).
- **Expiration/MFA Teams** : fréquence variable selon le tenant ; le relogin doit être aussi indolore
  que possible (SF-122-03).
- **Sécurité** : un port de débogage local ouvert est une surface ; le limiter à `localhost`, profil
  isolé, pas de rapatriement de cookies (règle projet inchangée).
- **Multi-clients Vigie** : un seul Chrome managé multi-onglets vs un par client — à trancher (impact
  sur l'observation réseau et l'isolation).

## 6. Hors périmètre
- Le débogage Teams en cours (SF-89-12/13) : distinct, prérequis.
- La fiche DSI dédiée (F-114) : annulée par le PO.
- Un navigateur autre que Chromium : à évaluer seulement si une policy l'impose (SF-122-04).

## 7. Préoccupations transversales
- **Navigation** : SF-122-02 ajoute un assistant à l'activation de la Vigie → vérifier le parcours.
- **Auth / tenant** : le Chrome managé et sa session Teams sont **par utilisateur/poste** ; isolation
  stricte, jamais de session partagée.
- **Composants** : runner (gestion de process Chrome, CDP), volet Vigie frontend (assistant), catalogue
  Teams, F-100 (synchro), F-45 (mise en service guidée, à étendre).
