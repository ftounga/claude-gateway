# Mini-spec — F-237 / SF-237-12 — Le retour vocal horodaté d'une vidéo, épingler au cockpit

## Identifiant
`F-237 / SF-237-12` · Feature parente `F-237` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-237-12-commentaire-vocal-cockpit`
Cadrage : §8.6, §14 idées 3 et 4, §1.1 (« une action qui engage = reformulée + oui »). Dépend de SF-237-06 (visionneuse
vidéo) et F-236 SF-236-24 (canal cockpit).

## Objectif
> Pendant qu'une vidéo est ouverte, « note ici : le logo est trop petit » pose un **retour horodaté** dans
> `video_comments` après un « oui » ; « épingle au cockpit » envoie la pièce héros sur le **2ᵉ écran**.

## Comportement attendu
- **Retour vocal** : une vidéo ou un audio publié (`video:<uuid>@vN`) est en héros ; phrase « note (ici) / commentaire
  / retour / remarque : <texte> » ⇒ la lecture se met en pause, Jarvis **reformule** : « Je note à 0:42 : « … ». Vous
  confirmez ? ». « Oui » net ⇒ `POST /api/videos/{id}/comments` (route **existante** F-215, `user_id` vérifié côté
  passerelle) avec la version et l'instant de lecture courant ⇒ « C'est noté. » ; « non » ⇒ rien n'est écrit ;
  ambigu ⇒ rien n'est écrit, la parole suit son chemin normal. Le retour rejoint la liste que l'agent lit déjà
  (`video_retours`, « Envoyer à l'agent » de F-215).
- L'instant de lecture vient de la visionneuse (`timeupdate` ⇒ `JarvisPieceStore.mediaTime`).
- **Cockpit** : message `piece` ajouté au canal local `jarvis-cockpit` (aucun réseau). Meneur : « épingle(-la) (au
  cockpit) », « mets-la sur le grand écran », ou bouton « Cockpit » de la galerie (visible si un cockpit écoute) ;
  « désépingle » / « retire du cockpit » la retire. Le cockpit qui arrive après reçoit la pièce épinglée. Suiveur :
  la pièce s'affiche seule, en héros (même visionneuse, mêmes chemins passerelle, son JWT).

### Cas d'erreur
- Création refusée (vidéo d'un autre compte, version inconnue, texte vide) ⇒ « Je n'ai pas pu noter ce retour. »
- Aucun cockpit ouvert ⇒ « Aucun cockpit n'est ouvert. » ; mode présentation côté cockpit ⇒ rien n'est rendu.

## Contraintes de validation
| Champ | Règle |
|---|---|
| texte du retour | 1-500 caractères (coupé au-delà) |
| instant | ≥ 0 ms, arrondi à la ms |

## Critères d'acceptation
- [ ] Aucun retour écrit sans « oui » (test pur + flux).
- [ ] Retour horodaté à l'instant de lecture, version de la pièce.
- [ ] Épingler / désépingler ; un cockpit arrivé après reçoit la pièce.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Contexte tenant | Non (route existante) | `VideoCommentController` → `VideoCommentService.create(userId, …)` : isolation existante F-215 |
| Préfixe de cache | Non | aucun outil ni consigne modifiés (ni Opus, ni cerveau rapide) |

## Plan de test
`jarvis-piece-comment.spec.ts` : analyse (vidéo / image / rien), horodatage, question, épingler / désépingler, canal
meneur ⇄ cockpit (arrivée tardive, retrait).

## Hors périmètre
Retour sur une plage (début-fin) à la voix ; partage d'une pièce (idée 10, plus tard).
