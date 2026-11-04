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

---

## Amendement du 2026-11-04 — « Comme OK Google » : Jarvis s'ouvre dans le compagnon

**Demande PO (2026-11-04, Galaxy S23 Ultra, Android 14, compagnon appairé, profil vocal appris) — décision actée, non
rediscutée** : téléphone **verrouillé**, il dit « Jarvis » et donne sa commande directement, sans déverrouiller.

**Constat sur l'appareil** (SF-232-01 → 04) : au mot, `WakeActivity` s'affichait par-dessus le verrou, appelait
`requestDismissKeyguard` puis ouvrait `/jarvis` dans le **navigateur**. Verrouillé : l'écran clignote et rien (la
demande de déverrouillage est annulée, l'activité n'a aucun contenu visible). Déverrouillé : Chrome s'ouvre (la PWA
installée ne capte pas le lien). Jugé « bricolé ».

Cet amendement **revient sur** D1 (« au mot, il ouvre la PWA ») et sur la phrase de §Auth « les écrans restent ceux de
la PWA, avec sa propre session ».

| # | Décision |
|---|---|
| D5 | **Jarvis s'ouvre DANS le compagnon** : une activité plein écran avec WebView, affichée par-dessus le verrou (`setShowWhenLocked` + `setTurnScreenOn`, **sans** `requestDismissKeyguard` à l'ouverture), qui charge l'écran `/jarvis` existant (même conversation, même voix, même 3D). Micro accordé à la page par `onPermissionRequest` (origine de la gateway seulement, `RECORD_AUDIO` déjà accordé), lecture audio sans geste, écran gardé allumé pendant l'échange. Le service du mot d'éveil **rend** le micro à l'ouverture et le **reprend** à la fermeture (jamais deux captures). Navigation bornée à l'origine de la gateway. |
| D6 | **Le mot enchaîne sur la commande** : l'URL porte `?source=compagnon&ecoute=1` ; l'écran démarre aussitôt la conversation mains libres (aucun bouton « Parler »). Le mot d'éveil du navigateur est coupé dans le compagnon (le natif l'assure). La conversation finie (silence, « Jarvis, pause »), l'écran se ferme et l'écoute du mot reprend. |
| D7 | **Session Jarvis du compagnon** : le compagnon échange son **jeton dédié** (qui ne quitte jamais le code natif) contre une **session JWT courte (30 min) à portée `jarvis-compagnon`** (`POST /runner/jarvis/session`). Elle porte le compte (`sub`, `tv`), le compagnon (`cid`) et l'état verrouillé (`lk`). À **chaque requête** : route dans une **liste fermée** (celle de l'écran `/jarvis`, 403 `jarvis_companion_scope` sinon), compagnon encore appairé (révoquer l'appareil coupe la session), version de jeton (« déconnecter toutes les sessions » la coupe). La page reçoit la session par le **pont natif** (jamais dans l'URL ni le `localStorage`). Restent hors portée : administration, compte, terminal hors Jarvis, et les **réglages** qui engagent (relier/délier une boîte, un agenda, un compagnon ; effacer le portrait ; changer le budget). |
| D8 | **Garde-fou verrouillé (défaut sûr)** : téléphone verrouillé, la conversation, la lecture (agenda, courrier, chantiers) et la délégation marchent. Toute action **exécutée sur un « oui »** — envoyer, supprimer ou archiver des courriels, poser un rendez-vous, accepter ou refuser une invitation — et toute **autorisation d'outil** (commande du poste, écriture de fichier) exigent le **déverrouillage**. La règle est appliquée **par la passerelle** à partir de `lk` (jamais confiée au modèle) : verrouillé, le « oui » n'exécute rien, l'action reste en attente et une note fixe le dit ; l'autorisation d'outil est refusée (423 `jarvis_phone_locked`). Côté écran : au « oui », le compagnon affiche la demande de déverrouillage (`requestDismissKeyguard`, écran Jarvis visible : plus de clignotement), émet une session déverrouillée, puis le « oui » part. Annulé : rien n'est fait, Jarvis le dit. Session sans `lk` = verrouillée. |
| D9 | **Mesure** : chaque session émise est tracée (`jarvis_companion_openings` : ouverture au mot, déverrouillage pour un « oui », renouvellement ; verrouillé ou non) et journalisée (`jarvis_companion_session purpose=… locked=…`, CloudWatch). Lecture à **J+7 (2026-11-11)** : ouvertures verrouillé / déverrouillé, déverrouillages pour « oui ». |
| D10 | **La PWA hors compagnon est inchangée** : sans pont natif, l'écran garde sa session, son mot d'éveil et son démarrage (SF-233-12). |

### Découpage complémentaire

| SF | Objet | Estim. |
|---|---|---|
| SF-232-05 | La session Jarvis du compagnon : échange du jeton, portée fermée, révocation, mesure (D7, D9). | 1 j |
| SF-232-06 | Le garde-fou verrouillé côté passerelle : « oui » et autorisations d'outil refusés sous `lk` (D8). | 0,5 j |
| SF-232-07 | L'écran `/jarvis` dans le compagnon : session par le pont, écoute immédiate, déverrouiller avant le « oui », fermeture (D6, D8, D10). | 1 j |
| SF-232-08 | L'APK : activité WebView par-dessus le verrou, micro rendu/repris, pont natif, version 2 signée (D5, D7, D8). | 1,5 j |

### Préoccupations transversales (amendement)

- **Auth / Principal** : nouvelle portée de JWT. Le filtre JWT de la chaîne principale borne la portée ; liste exhaustive
  des routes ouvertes dans la mini-spec SF-232-05 (relevé du code de l'écran `/jarvis`, 2026-11-04). Sessions
  ordinaires inchangées (test de non-régression).
- **Contexte tenant** : le `user_id` vient du `sub` du JWT, comme partout ; le compagnon doit appartenir à ce compte.
- **Navigation** : `/jarvis?source=compagnon&ecoute=1` ; aucune route Angular nouvelle.
