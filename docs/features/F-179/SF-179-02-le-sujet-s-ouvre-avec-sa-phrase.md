# Mini-spec — F-179 / SF-179-02 — Le sujet s'ouvre avec sa phrase

## Identifiant
`F-179 / SF-179-02` — feature parente `F-179` *Ouvrir le sujet d'un « go »* (cadrage D2, D3, D4).
Dépend de SF-179-01 (mergée, #1091 : événement `handoff`, champ de transcription `handoff`).
Branche : `feat/SF-179-02-le-sujet-s-ouvre`. Statut : `in-progress`. Date : 2026-10-06.

## Objectif
Au « go » du PO, passer du terminal du poste au terminal du sujet **en un seul geste** : en fin de tour,
l'écran est sur le sujet, la phrase de démarrage est dans la saisie, rien n'est envoyé.

## Comportement attendu

### Cas nominal
1. **Écoute** : `atelier.service` relaie l'événement SSE `handoff` (`onHandoff`) ; un événement sans
   `workspaceId` est ignoré.
2. **Carte dans le fil** (D3) : le bloc `handoff` (au fil de l'eau ou relu de la transcription) affiche
   « Sujet prêt : <nom> », l'aperçu de la phrase (180 car.) et **[Ouvrir le sujet]**.
3. **Navigation de fin de tour** (D2) : si la passation a été posée par un tour **lancé depuis cet écran**
   (envoi de l'utilisateur, pas un rebranchement), à la fin du tour (`onDone` final, pas un tour de suite),
   et si l'utilisateur regarde toujours ce terminal (même terminal actif, onglet visible), n'a **rien tapé**
   et qu'aucun tour ne tourne : l'écran relit la liste des terminaux, ouvre le sujet, **dépose** la phrase
   dans la saisie, met l'adresse à `/atelier/{sujet}`.
4. **Bande « Ouvert depuis <terminal du poste> · la phrase est prête… · [Revenir] »** sous l'en-tête du
   terminal du sujet ; [Revenir] rouvre le terminal d'origine et retire la phrase si elle n'a pas été
   touchée. La bande disparaît dès qu'on change de terminal.
5. **[Ouvrir le sujet]** (carte) fait la même ouverture à la demande (D3), hors tour en cours.
6. **Mosaïque** : la tuile dont le tour a posé une passation offre « Ouvrir <sujet> » (en-tête), qui ouvre
   le sujet en plein écran avec la phrase déposée (état de navigation repris une fois, jamais l'adresse).
7. **Adresse** : `/atelier/A → /atelier/B` réutilise l'écran ; l'écran suit désormais le paramètre de route
   (passation, bouton « précédent »).

### Cas d'erreur / garde-fous (D4)
| Situation | Comportement |
|---|---|
| Tour autonome / rebranché / autre écran | carte seule, aucune navigation |
| L'utilisateur a commencé à taper | carte seule, saisie intacte |
| Un tour tourne (clic sur la carte) | snackbar « le sujet s'ouvrira une fois le tour fini », rien ne bouge |
| Sujet absent de la liste (pas à moi) | snackbar « Sujet introuvable », rien ne bouge |
| Rechargement de la page | la phrase ne revient pas (jamais dans l'adresse ni l'état du navigateur, sauf mosaïque où l'état est consommé à la reprise) |
| Envoi automatique | jamais |

## Critères d'acceptation
- [ ] « go » au poste → en fin de tour, terminal du sujet affiché, phrase dans la saisie, rien envoyé.
- [ ] Tour rebranché ou saisie commencée → aucune navigation, carte [Ouvrir le sujet] présente.
- [ ] [Revenir] rend le terminal du poste.
- [ ] Événement `handoff` sans sujet ignoré.
- [ ] Build et suite front verts.

## Plan de test
- `handoff-card.component.spec.ts` : bloc, aperçu borné, carte + clic, lecture seule sans bouton, bande
  [Revenir], dispatch SSE `handoff` (et ignoré sans sujet).
- `atelier.component.spec.ts` : navigation de fin de tour (phrase déposée, un seul envoi, adresse), pas de
  navigation si saisie commencée, pas de navigation sur rebranchement, [Revenir], sujet absent.
- Suite complète `ng test` + `ng build`.
- Isolation : le sujet n'est ouvert que s'il est dans la liste des terminaux de l'utilisateur (serveur
  isolé `user_id`) ; la résolution serveur est couverte par SF-179-01.

## Impacts
Front : `atelier.models` (événement, bloc), `atelier.service` (dispatch), `handoff-card.component` (carte +
bande, nouveau), `atelier-terminal` (rendu, sorties `openSubject` / `handoffReturn`, entrée
`handoffFromName`), `atelier.component` (navigation, bande, suivi du paramètre de route),
`mosaique` + `live-turn-view` (lien « Ouvrir <sujet> »). Aucun backend, aucune table.

### Préoccupations transversales
- **Navigation / routing** : ✔ — composants : `atelier.component` (dépôt de la phrase, bande, abonnement
  au `paramMap` pour `/atelier/A → /atelier/B` et « précédent »), `atelier-terminal` (carte, bande),
  `mosaique` (lien plein écran via `RADAR_DRAFT_STATE`, repris puis retiré par `takeRadarDraft`), vue
  mobile (même écran Atelier). Vérifié : rechargement (la phrase ne revient pas), « précédent » (l'écran
  suit l'adresse), ouverture initiale (première émission de route ignorée, déjà lue par l'instantané).
- Auth / Tenant / Plans : non.

## Hors périmètre
Fermeture d'un tour en cours ; passation sujet → sujet ; envoi automatique.

## Arbitrages (réversibles)
- Bande d'origine **dans** le terminal (ligne sous l'en-tête) plutôt qu'en surcouche : rien ne couvre la saisie.
- Composants carte + bande dans un fichier dédié (budget de style du terminal).
- « Regarde ce terminal » = même terminal actif + onglet visible.
