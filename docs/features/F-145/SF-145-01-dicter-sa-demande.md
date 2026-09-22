# Mini-spec — F-145 / SF-145-01 — Dicter sa demande

## Identifiant
`F-145 / SF-145-01` — feature parente `F-145`

## Objectif
Parler au lieu de taper : maintenir une touche, dicter, et voir ses mots arriver dans la zone de
saisie.

## Ce qui existe déjà — et qu'on ne refait pas
Vérifié avant d'écrire :

| Existe | Manque |
|---|---|
| `TranscriptionProvider` — relais compatible Whisper, **Provider Independence** (F-128 / SF-128-04) | Le micro, côté écran |
| Configuré **en production** : `APP_STT_BASE_URL`, `APP_STT_MODEL=whisper-1`, clé en secret | Une route qui accepte un extrait audio court |
| Borne de taille, délai, langue, drapeau d'extinction | — |

**Aucun fournisseur nouveau, aucune clé à obtenir.** La gateway **relaie** — elle ne transcrit pas
(Provider-First).

## Les trois points tranchés

### 1. La voix sort du poste
**Claude ne transcrit pas l'audio.** C'est Whisper (OpenAI) qui le fait, et l'extrait dicté quitte
donc la machine. C'est la décision **D2 de F-128**, prise pour l'audio des réunions — elle s'applique
ici à un cas **nouveau** : la voix du consultant, chez un client. Elle est rappelée à l'écran, et le
drapeau d'extinction existant la coupe entièrement (sans clé : aucun octet ne part).

### 2. La barre d'espace seule est impossible
Elle sert à écrire. Deux gestes à la place :
- un **bouton micro** dans la barre de saisie — découvrable, et le seul qui marche au doigt ;
- le raccourci **`Ctrl/⌘ + Espace` maintenu** — enregistre tant qu'on tient, transcrit au relâchement.

### 3. Le coût n'entre pas dans F-133
~0,006 $ la minute, facturé chez **OpenAI**. F-133 mesure la dépense **Anthropic** : y mêler une
autre facture donnerait un total qui ne correspond à aucune ligne réelle. **Limite connue, écrite.**

## Comportement attendu
1. Un appui maintenu (bouton ou raccourci) démarre l'enregistrement ; le relâchement l'arrête.
2. L'écran **dit qu'il enregistre**, sans ambiguïté, pendant toute la durée.
3. Au relâchement, l'extrait part, et le texte revient **dans la zone de saisie** — **ajouté** à ce
   qui s'y trouve déjà, jamais à la place.
4. **Rien n'est envoyé à l'agent** : la dictée écrit, elle ne déclenche pas.
5. L'audio n'est **jamais conservé** : ni sur le disque, ni en base. Il vit le temps de l'appel.

| Cas d'erreur | Comportement |
|---|---|
| Micro refusé par le navigateur | un message le dit, une fois, et propose de réessayer |
| Enregistrement trop court (< 0,5 s) | ignoré en silence — c'est un clic, pas une dictée |
| Enregistrement trop long | arrêté à la borne, et transcrit quand même |
| Transcription indisponible (pas de clé) | message clair, aucun octet envoyé |
| Échec du service | message clair, le texte déjà saisi **n'est jamais perdu** |

## Critères d'acceptation
- [ ] `POST /api/atelier/transcription` accepte un extrait audio et rend son texte.
- [ ] La route **refuse** ce qui n'est pas de l'audio, et ce qui dépasse la borne.
- [ ] L'audio n'est **ni stocké ni journalisé** — vérifié par test.
- [ ] Le texte transcrit **s'ajoute** au brouillon, sans l'écraser.
- [ ] Aucune dictée **n'envoie** de demande à l'agent.
- [ ] Sans clé STT, la route répond clairement et **rien ne part**.
- [ ] L'écran dit qu'il enregistre, et cesse de le dire à l'arrêt.
- [ ] Un refus de micro est dit **une fois**, sans casser l'écran.

## Hors scope
La dictée **en continu** (mot à mot pendant qu'on parle) — un aller-retour par extrait suffit et
coûte dix fois moins · la transcription **locale** sur le poste (révision possible, D2 de F-128) ·
la dictée hors du terminal.

## Technique
| Élément | Changement |
|---|---|
| **`DictationController`** *(nouveau)* | `POST /atelier/transcription`, multipart, borné |
| **`DictationService`** *(nouveau)* | valide, relaie au `TranscriptionProvider`, ne garde rien |
| **`dictation.service.ts`** *(nouveau)* | micro, `MediaRecorder`, bornes |
| **`DictationButtonComponent`** *(nouveau)* | le bouton, son état, le raccourci |
| `atelier-terminal.component` | l'insère dans la barre de saisie |

Aucune table, aucune migration, **aucun fournisseur nouveau**.

## Plan de test
### Backend
- [ ] Un extrait valide est relayé et son texte rendu.
- [ ] Type non audio, ou taille excessive ⇒ refus nommé, **aucun appel** au fournisseur.
- [ ] Sans clé ⇒ message clair, aucun appel.
- [ ] L'audio n'apparaît dans aucun journal.
- [ ] Un compte non authentifié est refusé.

### Frontend
- [ ] Démarrage / arrêt, état visible.
- [ ] Le texte s'ajoute au brouillon existant.
- [ ] Micro refusé ⇒ message, pas de plantage.
- [ ] Extrait trop court ⇒ rien.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | Route **authentifiée** comme le reste de l'Atelier (`anyRequest().authenticated()`), et l'accès Atelier est exigé — la dictée est une fonction du produit, pas un service ouvert. |
| Contexte tenant | non | aucune donnée de client n'est lue ni écrite ; l'audio ne touche ni base ni disque |
| **Plans / limites** | **oui** | Le coût de la transcription est **hors F-133** (facture OpenAI), et écrit comme tel. Aucun quota Anthropic n'est touché. La borne de taille de l'audio est celle, existante, de F-128. |
| **Navigation / routing** | **oui** *(un écran)* | Seul le terminal reçoit le bouton ; aucune route d'écran ne change. |
