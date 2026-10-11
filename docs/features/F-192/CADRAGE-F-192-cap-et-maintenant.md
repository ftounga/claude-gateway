# Cadrage F-192 — Cap : « Et maintenant ? »

**Demande PO du 2026-10-11** (vidéo « mods », mod « Cap »). Retour PO sur l'existant : *« ce que tu as implémenté, j'ai du mal à l'utiliser. Je ne comprends pas très souvent ce qu'il propose. Souvent pas très pertinent. J'aurais préféré un truc vraiment puissant : il a vu le contexte, ce qu'on a fini, et il propose visuellement les options. »*

## Diagnostic de l'existant

- **F-144 / SF-144-02 « suite prédite »** : appel **séparé** au modèle rapide (`ANTHROPIC_FAST_MODEL`) après chaque tour, `POST /workspaces/{id}/next-prompt` (`backend/.../atelier/nextprompt/`), qui ne voit qu'un extrait → devine. Rendu en texte fantôme dans la saisie + puce unique sur mobile.
- **F-144 / SF-144-01** : puces calculées sans modèle (`frontend/src/app/shared/turn-suggestions/turn-suggestions.ts`), pauvres.
- **F-164** : questions structurées posées par l'agent quand IL le décide — à conserver.

## Cible (maquette validée : `design/maquette-cap-plume.html`, partie Cap)

**C'est l'agent principal qui vient de travailler qui propose la suite**, dans la même réponse : il a vu le travail, le plan, `STATE.md`, les échecs. Coût : quelques lignes de sortie ; l'appel séparé disparaît.

- Consigne système (bloc **stable**, cf. mémoire « préfixe stable = rien de volatil ») : en fin de réponse, quand une suite a du sens, émettre un bloc balisé (sur le modèle du marqueur `<<essentiel>>` de F-126, `AtelierChatService.java:480`) de **2 à 4 options**, chacune : `titre` (court), `pourquoi` (fondé sur un fait du tour — option sans motif interdite), `étiquette` ∈ {Vérifier, Continuer, Livrer, Risque}, `demande` (le texte complet qui ira dans la saisie). Aucune option si aucune suite évidente.
- Backend : extraire le bloc du texte affiché (jamais montré brut), le **persister avec le message** (survit au rechargement, comme `terminal_json` SF-30-09), le transmettre au front (flux + historique).
- Front : panneau **« Et maintenant ? »** sous la dernière réponse : options numérotées, « pourquoi », étiquette colorée (Vérifier = vert, Continuer = or, Livrer = orange, Risque = ambre), ligne « Autre chose : tapez votre suite », **touches 1-4**, Échap/« Masquer ». **Un clic remplit la saisie, n'envoie rien.** Se replie sur une ligne « N suites proposées » dès que l'utilisateur écrit.
- **Ne s'affiche pas** si : l'agent pose une question F-164 en cours, tour en échec/coupé (le terminal propose déjà « Reprendre »), aucune option émise, lecture seule.
- **Retrait de F-144** : texte fantôme, puces SF-144-01 et appel `next-prompt` retirés (code et route supprimés ou coupés par défaut puis supprimés — au choix du dev, à documenter). Mettre à jour PRODUCT_SPEC F-144 (« remplacée par F-192 »).

## Justesse avant coût

La consigne ne doit pas dégrader la réponse : le bloc vient **après** la réponse, borné (≤ 4 options, ~600 caractères). Tester qu'une réponse sans bloc reste valide.

## Découpage

- **SF-192-01** — Backend : consigne + extraction/persistance du bloc de suites, exposé au flux et à l'historique ; tests (extraction, absence, bloc malformé ignoré, isolation).
- **SF-192-02** — Frontend : panneau « Et maintenant ? » (options, touches, Autre, Masquer, repli, cas d'absence, mobile).
- **SF-192-03** — Retrait de la suite prédite F-144 (front + back + config), PRODUCT_SPEC F-144 mis à jour.

## Hors périmètre

Envoi automatique d'une option ; options ailleurs que dans le terminal.
