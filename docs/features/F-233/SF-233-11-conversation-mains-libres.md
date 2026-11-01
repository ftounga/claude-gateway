# Mini-spec — F-233 / SF-233-11 — Conversation mains libres (et formules variées)

## Identifiant
`F-233 / SF-233-11` · Feature parente `F-233` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-233-11-conversation-mains-libres`
Ajout PO du 2026-11-01 après essai : « robotique, lent, il faut cliquer sur Parler puis J'ai fini… J'avais dit conversation
fluide » ; « un peu simplet » (il répète « Je m'en occupe » et signale l'écoute à chaque fois).

## Objectif
> À l'ouverture de `/jarvis`, Jarvis converse **sans bouton** : il écoute, détecte seul la fin de la phrase, répond, puis
> se remet à écouter ; un interrupteur « Pause / Reprendre la conversation » remplace « Parler / J'ai fini », et ses
> formules d'attente sont variées, au vouvoiement, rares et jamais répétées.

## Comportement attendu
### Conversation mains libres (par défaut)
- **Démarrage** : la première fois, un seul toucher « Commencer » (geste exigé par le navigateur pour le micro et le son) ;
  il est **mémorisé** sur l'appareil (`cg.jarvis.conversation.ready`). Aux ouvertures suivantes, la conversation démarre
  seule si le navigateur a déjà vu un geste sur la page (`navigator.userActivation.hasBeenActive`) ; sinon « Commencer »
  reste proposé (un toucher). Ouvert par le **compagnon Android** (`source=compagnon`), elle démarre directement.
- **Boucle** : écoute (flux ElevenLabs, fin de tour du fournisseur ; repli `EndOfSpeech`) → réponse → **réécoute
  automatique** dès que Jarvis revient au repos et qu'aucun tour ne tourne. Le micro reste ouvert pendant qu'il parle
  (barge-in SF-233-08/09 inchangé). Pendant un tour qui tourne sans parler (réflexion), l'écoute ne se rouvre pas :
  rien n'est envoyé en dehors de la précision existante (orbe / barge-in).
- **Silence pendant l'écoute** : la fin « sans un mot » d'`EndOfSpeech` ne clôt plus l'écoute en conversation (aucun
  clip, aucun envoi, aucune session rouverte) ; l'écoute continue jusqu'à la fin de conversation.
- **Bruits et phrases vides** : un texte vide ou fait seulement d'hésitations (« euh », « hum », « mmh »…) ou d'une
  lettre n'est **jamais envoyé** ; Jarvis se remet à écouter sans rien dire.
- **Pause** : le gros bouton devient « Pause » / « Reprendre la conversation » (micro coupé, visible : icône
  `mic_off`, libellé d'état « En pause »). « Jarvis, pause », « pause », « mets-toi en veille », « mettez-vous en
  pause »… (dit seul) met en pause sans tour ; Jarvis répond sobrement « Je me mets en pause. ». Le mot d'éveil
  (s'il est appris et allumé) ou le bouton reprend la conversation.
- **Fin de conversation** : après **60 s** sans demande (réglable sur l'appareil, `cg.jarvis.conversationIdleMs`,
  borné 15 s – 10 min), Jarvis retourne au repos et le dit une fois : « Je me mets en veille. » (+ « Dites « Jarvis »
  pour reprendre. » si le mot d'éveil est actif). Retour compté (`IDLE_END`).
- **Ne rien dire pour signaler l'écoute** : aucune réécoute, reprise ou démarrage de conversation ne joue de clip ;
  seul l'orbe change. Le mot d'éveil, quand il réveille Jarvis hors conversation, joue une formule courte variée.

### Formules variées (accusé et réactions)
- Un **répertoire** fermé de formules au vouvoiement, sobres : réveil (« Oui ? », « Je vous écoute. », « Dites-moi. »,
  « Allez-y. », « À votre écoute. », « Oui, Monsieur ? ») et attente (« Je regarde. », « Une seconde. », « Un
  instant. », « Je m'en occupe. », « Voyons cela. », « Je vérifie. », « C'est en cours. », « Laissez-moi regarder. »,
  « Je m'en charge, Monsieur. »). Tous pré-synthétisés en clips (`JarvisClipService`, liste fermée).
- **Jamais la même formule deux fois de suite, ni parmi les 5 dernières** (historique mémorisé sur l'appareil).
- **« Monsieur » rarement** : au plus une fois sur les 5 dernières formules, et tiré avec une faible probabilité.
- **Une formule d'attente au plus par échange**, et **aucune si la réponse arrive vite** : elle n'est jouée que si la
  première phrase n'est pas prête 1,2 s après la fin de la parole ; une phrase prête pendant le clip attend la fin du
  clip (plus de clip coupé au milieu d'un mot). L'ancien couple « réaction immédiate + Je m'en occupe à 2 s » disparaît.

## Mesure
- `jarvis_turn_timings.listen_mode` : nouvelles valeurs `CONV_STREAM` / `CONV_BATCH` (tour pris en conversation).
- `jarvis_usage_days.conversation_idle_ends` (migration 199) : retours au repos par fin de conversation ; événement
  `POST /api/jarvis/usage {event: "IDLE_END"}` ; total `idleEnds` dans la mesure d'adoption.

## Cas d'erreur
| Situation | Comportement |
|---|---|
| Micro refusé à la réécoute | conversation en pause, message existant (« Le micro est refusé… ») — pas de boucle |
| Navigateur sans dictée | conversation non démarrée, message existant |
| Pas de sujet | pas de démarrage ; « Choisis d'abord un sujet. » inchangé |
| Clips non chargés (voix non configurée) | formule d'attente dite par la voix ordinaire, sinon rien |
| Son refusé avant le geste | « Commencer » reste affiché |
| `IDLE_END` inconnu d'une ancienne passerelle | 400 ignoré par l'écran (best-effort) |
| `listen_mode` hors liste | 400 (inchangé) |

## Critères d'acceptation
1. Conversation démarrée : après une réponse dite, l'écoute se rouvre seule, sans clip.
2. Une phrase vide ou « euh » n'envoie aucun tour et l'écoute repart.
3. « Jarvis, pause » ⇒ pause sans tour ; le bouton affiche « Reprendre la conversation » ; aucune réécoute.
4. 60 s sans demande ⇒ repos, annonce unique, `IDLE_END` compté ; aucune réécoute ensuite.
5. Le gros bouton n'affiche plus jamais « J'ai fini » ni « Parler ».
6. Deux formules successives ne sont jamais identiques ; aucune n'est reprise parmi les 5 dernières.
7. Aucune formule d'attente si la première phrase est prête avant le délai ; au plus une par échange.
8. Aucune formule (front et clips back) ne tutoie.
9. Ouvert par le compagnon, la conversation démarre directement.

## Plan de test
- Unitaires (`jarvis-conversation.spec.ts`) : `isPauseCommand`, `isNoise`, `conversationIdleMs` (défaut, bornes),
  `Conversation` (démarrage, pause, fin au silence, réécoute seulement au repos sans tour, activité).
- Unitaires (`jarvis-phrases.spec.ts`) : `FormulaPicker` (aucune répétition consécutive sur 500 tirages, rien dans les 5
  derniers, « Monsieur » au plus une fois sur 5), `ExchangeFiller` (aucune formule si réponse déjà là, une seule par
  échange), aucun tutoiement dans le répertoire.
- Écran (`jarvis.component.spec.ts`) : retour au repos ⇒ réécoute en conversation ; pause ⇒ pas de réécoute ; libellés
  du bouton ; « Jarvis, pause » ⇒ aucun envoi.
- Backend : `JarvisClipServiceTest` (aucun tutoiement, noms du répertoire présents), `JarvisAdoptionApiIntegrationTest`
  (`IDLE_END` compté, isolé par `user_id`), `JarvisLatencyApiIntegrationTest` (`CONV_STREAM` accepté).
- Isolation : compteur `IDLE_END` rangé sur la ligne du compte courant (filtre `user_id` existant de `JarvisUsageService`).

## Impacts
- Front : `jarvis-conversation.ts` (nouveau), `jarvis-phrases.ts` (nouveau), `jarvis.component.ts/.html/.scss`
  (branchements minimaux), `jarvis-oral.ts` (constantes de clips), `jarvis-voice.service.ts` (une phrase en file attend
  la fin du clip), `jarvis.service.ts` (`IDLE_END`), `jarvis.models.ts` (`listenMode`), `jarvis-turn-clock.ts`.
- Back : `JarvisClipService` (répertoire), `JarvisAdoptionService` (+`IDLE_END`), `JarvisUsageService`/`JarvisUsageDay`
  (+`conversation_idle_ends`), `JarvisLatencyService` (`CONV_*`). Migration `199-jarvis-usage-conversation-idle.xml`.
- Android : libellés de notification « Jarvis t'écoute » ⇒ « Jarvis vous écoute » (vouvoiement).
- Aucun changement du cerveau, de la consigne système ni du fil.

## Préoccupations transversales
- Navigation / routing : aucune route ajoutée ; `/jarvis` lit `source=compagnon` (existant). Composants vérifiés :
  `JarvisComponent.ngOnInit` (alerte, compagnon), lien retour `/atelier`.
- Auth, tenant, limites : inchangés (événement d'usage additif sur l'endpoint existant filtré par `user_id`).

## Hors périmètre
Écoute continue pendant la réflexion d'un tour ; mot d'éveil sans apprentissage ; fin de tour par un modèle ; réglage
de la durée de conversation dans une interface (localStorage seulement) ; formules générées par le modèle.
