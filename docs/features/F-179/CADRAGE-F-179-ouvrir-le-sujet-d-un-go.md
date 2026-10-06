# Cadrage — F-179 Ouvrir le sujet d'un « go »

> Demande PO le 2026-10-06 : *« Quand il crée un nouveau sujet et que je dis go, je voudrais qu'il
> ferme le terminal central et ouvre directement le terminal du nouveau sujet, avec la phrase qu'il me
> propose déjà dans l'espace de chat ; j'ai juste à valider. Au lieu de fermer, aller sur la Forge,
> cliquer sur le sujet, l'ouvrir, copier-coller la phrase, entrer. »*
> Suite prévue de SF-141-06 (« ouverture automatique = subfeature front ultérieure »).
> Audit : `docs/audits/AUDIT-2026-10-06-terminal-central-gouvernance-parite.md` §5.

## 1. Objectif

Passer du poste au nouveau sujet en **un seul geste** : le « go » du PO.

## 2. Décisions (par défaut, réversibles)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Outil `ouvrir_sujet(sujet, phrase)`** (poste seulement) : résout le sujet du poste (filtré `user_id` + `host_id`, `requireOwned`), après mise à jour des `.md` du sujet ; émet un **bloc de passation** `{workspaceId, nom, phrase}` dans le flux (événement `handoff`, même modèle que `attente`) et le garde dans la transcription. `create_subject` rend désormais l'`id` du sujet créé. | Aucun outil ne rend l'id aujourd'hui |
| D2 | **Navigation automatique en fin de tour** quand l'outil a été appelé dans un tour **lancé par l'utilisateur** (son « go » est le geste) et qu'il regarde ce terminal : le front ouvre `/atelier/{id}` avec la phrase **déposée dans la saisie, non envoyée** (mécanisme existant `RADAR_DRAFT_STATE` / `takeRadarDraft`). Un bandeau « Ouvert depuis le Terminal du poste · [Revenir] ». | Demande PO |
| D3 | **Sinon, une carte** dans le fil : nom du sujet, aperçu de la phrase, bouton **[Ouvrir le sujet]** (tour autonome, autre écran, mosaïque, ou PO revenu plus tard). Dans la mosaïque, le bouton ouvre le sujet en plein écran. | Jamais de navigation surprise |
| D4 | **Jamais d'envoi automatique** de la phrase ; jamais de navigation pendant un tour en cours (attendre la fin) ; rien ne se passe si l'utilisateur a commencé à taper. | Garde-fous |
| D5 | **Doctrine de passation** (`SUBJECT_HANDOFF_DOCTRINE`) : « phrase à coller » remplacée par « après le go, appelle `ouvrir_sujet` » ; la question `demander` de SF-141-06 reste pour proposer la passation. | Cohérence |

## 3. Découpage

| SF | Titre | Contenu | Estim. |
|---|---|---|---|
| SF-179-01 | Le bloc de passation | D1, D5 (back) : outil, id rendu par `create_subject`, événement `handoff`, transcription, doctrine. | 1 j |
| SF-179-02 | Le sujet s'ouvre avec sa phrase | D2, D3, D4 (front) : écoute `handoff`, navigation de fin de tour, carte, bandeau Revenir, mosaïque, mobile. | 1 j |

## 4. Critères d'acceptation (extraits)

- Au poste : « crée le sujet X » → l'agent crée et propose ; « go » → en fin de tour, l'écran est sur le terminal de X, la phrase est dans la saisie, rien n'est envoyé.
- Tour autonome ou autre écran → carte [Ouvrir le sujet], aucune navigation.
- Un sujet d'un autre poste ou d'un autre utilisateur ne peut pas être ciblé.

## 5. Préoccupations transversales

- **Navigation / routing** : ✔ — composants : `atelier.component` (dépôt du brouillon, bandeau), `atelier.service.ts` (événement), `forge/mosaique`, rail de la Forge (sélection du sujet), vue mobile. Vérifier : rechargement de page (le brouillon ne revient pas), bouton retour du navigateur.

## 6. Hors périmètre

Fermeture d'un tour en cours ; passation d'un sujet vers un autre sujet (seul le poste aiguille).
