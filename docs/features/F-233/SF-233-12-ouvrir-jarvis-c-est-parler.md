# Mini-spec — F-233 / SF-233-12 — Ouvrir Jarvis, c'est parler

## Identifiant
`F-233 / SF-233-12` · Feature parente `F-233` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-233-12-ouvrir-jarvis-c-est-parler`
Ajout PO du 2026-11-02 après essai (PWA Android et Chrome) : il voit encore un bouton « Commencer » avant de parler et
croit que rien n'a été corrigé (ancienne version servie par le service worker, ou geste demandé alors qu'inutile).

## Objectif
> À l'ouverture de `/jarvis`, la conversation démarre **directement** dès que le navigateur permet le son ; une invite
> plein écran « Touchez l'écran pour réveiller Jarvis » n'apparaît **que** si le son est réellement bloqué ; l'appli se
> met à jour seule après un déploiement et affiche discrètement sa version.

## Comportement attendu
### Démarrage sans geste
- Sujets chargés (≥ 1), pas d'alerte ni d'ouverture par le compagnon : l'écran **teste réellement le son**
  (`JarvisVoiceService.audioAllowed()`) : `navigator.getAutoplayPolicy('audiocontext')` s'il existe ; sinon
  `AudioContext.resume()` attendu au plus 400 ms puis `state === 'running'`.
  - **Son permis** (PWA installée, site à fort engagement média, geste déjà fait) → `startConversation()` : l'écoute
    s'ouvre ; le micro est demandé directement par `getUserMedia` si la permission n'a jamais été accordée.
  - **Son bloqué** → invite **plein écran** (calque cliquable sur tout l'écran, texte « Touchez l'écran pour réveiller
    Jarvis ») ; **n'importe quel toucher** (ou Entrée/Espace) débloque le son et démarre la conversation.
- Le bouton « Commencer » disparaît : l'interrupteur dit « Pause » / « Reprendre la conversation » (« Parler » s'il
  n'a jamais démarré, ex. pas de sujet). Le libellé d'état « Touchez Commencer pour converser » disparaît.
- `canAutoStart` / `cg.jarvis.conversation.ready` ne gouvernent plus le démarrage (fonctions conservées, sans effet).

### Mise à jour automatique (service worker Angular)
- `JarvisAppUpdate` (nouveau, `jarvis/jarvis-app-update.ts`) : si `SwUpdate.isEnabled`, `checkForUpdate()` à
  l'ouverture puis toutes les **10 min** ; sur `VERSION_READY`, la nouvelle version est **activée et la page
  rechargée** dès que Jarvis est au repos (ni écoute, ni parole, ni réflexion, ni tour, ni délégation, ni
  apprentissage du mot d'éveil) ; sinon **différée** au prochain retour au repos.
- La bannière globale SF-152-05 (`VersionUpdateBannerComponent`) est inchangée (autres écrans).

### Version affichée
- Hash court (7 caractères) de la version servie, en petit dans le coin bas droit de l'écran Jarvis (issu des
  événements `NO_NEW_VERSION_DETECTED` / `VERSION_READY`) ; « → nouvelle » quand une version attend le repos.
  Rien hors production (service worker absent).

## Cas d'erreur
| Situation | Comportement |
|---|---|
| Son bloqué (pas d'engagement, pas de geste) | invite plein écran, un toucher n'importe où suffit |
| Pas d'`AudioContext` (navigateur ancien) | considéré permis : démarrage direct (la lecture échouera comme avant) |
| `resume()` ne se résout jamais (Chrome sans geste) | délai 400 ms puis état lu → bloqué → invite |
| Micro refusé | comportement SF-233-11 inchangé (pause + message) |
| `checkForUpdate` / `activateUpdate` en échec | ignoré ; rechargement tenté quand même après activation échouée |
| Service worker absent (dev, tests) | ni vérification, ni rechargement, ni version affichée |

## Critères d'acceptation
1. Son permis → à l'ouverture, la conversation démarre (état `ecoute`) sans aucun toucher ; aucune invite.
2. Son bloqué → l'invite plein écran est affichée ; un toucher la retire et démarre la conversation (`unlock` appelé).
3. Aucun texte « Commencer » à l'écran, en aucun cas.
4. `VERSION_READY` au repos → `activateUpdate` puis rechargement ; pendant un tour → rien, puis au retour au repos.
5. `checkForUpdate` appelé à l'ouverture puis toutes les 10 min ; arrêté à la destruction de l'écran.
6. Le hash court de la version est affiché en petit quand le service worker le connaît.

## Plan de test
- **Unitaires** `jarvis-app-update.spec.ts` : SW désactivé → rien ; check à l'ouverture + 10 min ; `VERSION_READY`
  au repos → activation + rechargement ; occupé → différé puis appliqué à `settle()` ; version courte.
- **Composant** `jarvis.component.spec.ts` : son bloqué → invite, toucher → conversation ; son permis → démarrage
  direct sans invite ; jamais « Commencer ».
- **Service voix** : `audioAllowed()` (contexte `running` → vrai ; `suspended` qui reste suspendu → faux).
- **Isolation utilisateur** : sans objet (aucun accès données, aucun endpoint).

## Impact
- Tables : aucune. Endpoints : aucun. Backend : non touché.
- Composants : `jarvis.component.ts/html/scss`, `jarvis-voice.service.ts` (`audioAllowed`), nouveau `jarvis-app-update.ts`.
- **Préoccupations transversales** : Navigation/routing — non (aucune route). Auth/tenant/plans — non.

## Hors périmètre
- Supprimer la bannière globale de mise à jour ; forcer le son quand le navigateur l'interdit (impossible) ;
  écran 3D F-236.
