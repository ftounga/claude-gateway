# Cadrage — F-47 — L'autorisation qu'on ne peut pas manquer

## Le fait observé

2026-09-08, deuxième poste client (Mac), premier usage après un appairage réussi. « Liste les
documents du dossier » met **deux minutes** à répondre, puis parle d'un problème de permissions.
Les journaux de production sont sans ambiguïté : la porte de confirmation s'est déclenchée deux
fois et a **expiré** deux fois (`RunnerConfirmationGate : Aucune décision d'autorisation dans le
délai`), les deux tours se terminant normalement, flux **jamais coupé**.

## La cause racine (établie le 2026-09-09, par instrumentation en production)

**Aucun cycle de détection de changement n'est déclenché après la pose de l'invite.** La chaîne est
saine de bout en bout — la gateway émet `confirm_request`, le réseau le transporte, l'écran le
reçoit, le handler est appelé, le signal est positionné — mais le gabarit n'est jamais relu.

Mesuré en production : `NgZone.isInAngularZone() = true`, `1297` lectures du gabarit pendant le
tour, **`1297` encore après le `set`**, `1298` dès qu'un `ApplicationRef.tick()` est forcé.

La raison : le flux **se tait** juste après. Le serveur attend la décision, donc plus aucune
microtâche ne survient, et Angular (zone.js, `provideZoneChangeDetection`) ne relit les gabarits
qu'à la **fin d'un lot de microtâches**. L'invite est le seul événement du flux suivi d'un silence :
tous les autres (`progress`, `text`, `action`, `output`) sont suivis d'un événement dont la lecture
relance le rendu. Ce n'est donc pas une régression franche mais une **condition de course** — il
suffit qu'un événement arrive derrière pour que l'invite apparaisse.

Deux minutes plus tard, la porte expire, le serveur émet `confirm_resolved`, et l'écran efface une
invite jamais peinte (pile relevée : `traceClear ← clearConfirmation ← onConfirmResolved ←
dispatchSseEvent`).

**Pistes écartées** : la superposition du panneau de SF-39-18 (l'utilisateur atteste être resté dans
le terminal) et la bufferisation du flux (`proxy-buffering` vaut `off` par défaut sur ingress-nginx).

## Ce que la feature ne remet pas en cause

L'invite **reste dans le flux** (décision F-33 / SF-33-03, délibérée : une modale masquerait les
commandes précédentes, qui sont ce qui permet de juger). On la rend impossible à manquer, on ne la
déplace pas.

## Hors périmètre

- Supprimer la porte de confirmation.
- Allonger le délai sans le dire.
- Toute forme d'autorisation implicite.

## Découpage

| SF | Titre | Portée |
|----|-------|--------|
| SF-47-01 | L'invite est peinte, et rappelée tant qu'elle attend | Frontend |
| SF-47-02 | Le temps restant, et l'expiration dite pour ce qu'elle est | Backend + Frontend |

## Question laissée ouverte au PO (non tranchée ici)

En cible `RUNNER`, sur une machine que l'utilisateur a lui-même connectée, la porte doit-elle rester
activée **par défaut** ? SF-38-19 a déjà jugé que l'exécution devait l'être. **Aucune des deux SF ne
touche à cette valeur par défaut** : la trancher serait une décision de sécurité, elle revient au PO.
