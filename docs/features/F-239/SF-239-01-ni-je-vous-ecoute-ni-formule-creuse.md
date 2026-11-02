# Mini-spec — F-239 / SF-239-01 — Ni formule creuse ni tour fantôme (écran)

## Identifiant
`F-239 / SF-239-01` · Feature parente `F-239` « Jarvis entend juste » · Statut `in-review` · 2026-11-02 ·
Branche `feat/SF-239-01-sans-formule-creuse`

## Objectif
> Jarvis ne dit plus de formule de réveil ni d'attente creuse (« Bonjour Jarvis » reçoit seulement sa réponse) et ne
> lance plus aucun tour quand personne n'a parlé.

## Comportement attendu
### Cas nominal
- **Cerveau rapide** (`converse`) : **aucune** formule d'attente (ni clip, ni voix). La réponse est dite quand elle
  arrive ; pendant l'attente, l'état `reflexion` (cœur, orbe) suffit. Une délégation dit son annonce (inchangé).
- **Chemin Opus direct** (`send` : cerveau rapide éteint, repli, confirmation) : une phrase d'attente **au plus une**
  par échange, **seulement** si aucune phrase n'est prête **2,5 s** après la fin de la parole, **seulement** si la
  parole nomme un sujet (nom d'un sujet du compte) ou un chantier (« chantier 3 ») et n'est pas une salutation ou une
  politesse ; texte **lié** : « Je regarde pour {sujet}. », « Je consulte {sujet}. », « Je me penche sur {sujet}. »
  (ou « …le chantier N. ») ; jamais le même gabarit qu'au tour précédent. Sinon : silence.
- **Mot d'éveil** (`onWake`) — le moteur sur l'appareil signale en plus : parole juste avant le mot (écart ≤ 1 s),
  parole qui reprend après, et rend l'audio de la phrase (au plus 4 s avant le mot) :
  - parole avant, ou parole qui reprend dans les **0,6 s** ⇒ **aucune formule** ; l'écoute en flux s'ouvre et reçoit
    d'abord l'audio de la phrase (pré-écoute), puis le micro ; le moteur du mot d'éveil s'arrête une fois l'écoute
    ouverte ;
  - « Jarvis » seul ⇒ une formule courte parmi « Oui ? », « Oui, Monsieur ? », « Dites-moi. », « Allez-y. »
    (« Je vous écoute. » et « À votre écoute. » retirées du tirage), puis l'écoute.
- **Porte de parole réelle** (`speechGate`, pure) avant tout traitement d'une parole écoutée (`finishListening`,
  parole d'interruption) : refus si texte **vide**, **bruit** (hésitations, une lettre), **hallucination typique** du
  STT (« Sous-titres… », « Merci. » / « Merci d'avoir regardé. » isolés, balises seules « (musique) », « [rires] »…),
  **sans voix** (micro : moins de 300 ms au-dessus du seuil de parole pendant l'écoute, pas de pré-écoute, ≤ 6 mots),
  ou **invraisemblable** (plus de mots que 6 × secondes de voix + 4). Refus ⇒ **aucun tour**, ni cerveau rapide ni
  Opus ; `falseTriggers` compté ; la mesure du tour part avec `rejectedKind` (empty, noise, hallucination, no_voice,
  implausible) et `rejectedChars` (longueur) — **jamais le texte** (stockés par SF-239-02, ignorés avant).
  Exception : une confirmation attendue garde son comportement (« le silence ne vaut pas accord »).
- **Écoute bornée** : une écoute dure au plus **30 s** ; au-delà elle se clôt (la porte décide). Une parole refusée ne
  compte pas comme activité de conversation : après 60 s sans parole acceptée, Jarvis se met en veille (existant).
- Les clips des formules retirées restent servis par la passerelle (`JarvisClipService`, inchangé) : rien côté
  serveur, déploiement du front seul.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Écoute en flux indisponible (503, refus, réseau) | Repli sur la dictée batch ; la pré-écoute est perdue (l'audio n'est pas envoyé), aucune formule si la phrase continuait |
| Pré-écoute vide ou illisible | L'écoute s'ouvre normalement, sans audio préalable |
| Nouveau « Jarvis » reconnu pendant le traitement d'un réveil ou une écoute | Ignoré (un seul réveil à la fois) |
| Aucun clip de réveil chargé | Aucune formule (silence), l'écoute s'ouvre |
| Délégation du cerveau rapide **sans sujet engagé** (D19 F-235) | Rien ne partirait : Jarvis demande « Pour quel client ou quel sujet, Monsieur ? » au lieu de dire l'annonce |
| Délégation avec une annonce **vide** (passerelle, SF-239-02) | Silence (le cœur montre le travail), le tour Opus part |

## Critères d'acceptation
- [ ] « Bonjour Jarvis » (réveil avec parole avant le mot, puis transcription « Bonjour Jarvis. ») ⇒ aucun
      `playClip`, aucun `speak` de formule ; seule la réponse du cerveau rapide est dite, même si elle arrive à 3 s.
- [ ] Cerveau rapide : aucune formule d'attente quel que soit le délai.
- [ ] Chemin Opus direct : rien avant 2,5 s ; à 2,5 s une phrase liée si un sujet/chantier est nommé ; rien pour
      « Bonjour » ni pour une demande sans nom ; pas le même gabarit deux tours de suite.
- [ ] « Jarvis » seul ⇒ une formule de réveil, jamais « Je vous écoute. » ni « À votre écoute. ».
- [ ] Écoute en flux qui rend « Sous-titres réalisés par… », « Merci. », « (musique) », ou un texte court sans voix
      au micro ⇒ aucun appel au cerveau rapide ni à Opus ; `falseTriggers = 1`, `rejectedKind`, `rejectedChars`
      dans la mesure, aucun texte.
- [ ] Une écoute de plus de 30 s se clôt ; 60 s sans parole acceptée ⇒ veille.
- [ ] La pré-écoute est envoyée au fournisseur **avant** l'audio du micro, au débit de la session.

## Périmètre — hors scope
Consigne du cerveau rapide, salutation jamais déléguée, annonce automatique « Je vérifie, Monsieur. », stockage de
`rejectedKind` / `rejectedChars` (SF-239-02) ; fin de tour (SF-239-04) ; qualité de transcription (SF-239-05) ;
correction (SF-239-06) ; compagnon Android (son réveil reste « Jarvis » seul).

## Contraintes de validation
| Élément | Règle |
|---|---|
| Pré-écoute | ≤ 4 s avant le début du mot, début de la phrase = première parole après un calme > 1 s ; 16 kHz mono |
| Parole avant | fin de la parole précédente ≤ 1 s avant le début du mot |
| Fenêtre de reprise | 600 ms après la détection |
| Délai de la phrase d'attente (Opus direct) | 2 500 ms après la fin de la parole |
| Voix minimale | 300 ms de niveau ≥ seuil de parole (`SPEECH_LEVEL`) sur l'écoute |
| Écoute maximale | 30 000 ms |

## Technique
- Endpoints / tables / migration : **aucun**.
- Front : `jarvis-wake.ts` (`WakeEvent`, suivi de la parole, mémoire de 6 s), `jarvis-stream-listener.ts`
  (`start(preRoll)` / `open(ticket, handlers, preRoll)`), `jarvis-phrases.ts` (formules retirées, `linkedFiller`,
  `isSmallTalk`), `jarvis.component.ts` (`onWake`, `openListening`, `converse`, `armFiller`).

## Plan de test
- Unitaires (Karma) : `WordSegmenter` / suivi de parole (écart avant, reprise) ; `linkedFiller` (sujet, chantier,
  salutation, sans nom, gabarit différent du précédent) ; tirage de réveil sans les formules retirées ;
  `StreamingListener` envoie la pré-écoute avant le micro.
- Unitaires : `speechGate` (vide, bruit, hallucinations, sans voix, invraisemblable, phrase normale acceptée,
  pré-écoute acceptée sans voix mesurée).
- Composant : écoute en flux qui rend « Merci. » sans voix ⇒ aucun `converse` ni `streamChat`, mesure refusée.
- Composant : « Bonjour Jarvis » ⇒ ni formule de réveil ni d'attente, seulement la réponse ; cerveau rapide lent ⇒
  aucune formule ; Opus direct : phrase liée à 2,5 s, rien pour « Bonjour » ; « Jarvis » seul ⇒ formule de réveil.
- Isolation `user_id` : non applicable (aucune donnée serveur ; les noms de sujets sont ceux déjà chargés pour le
  compte connecté).

## Dépendances
F-233 (SF-233-05, SF-233-11), F-235 (SF-235-05) — livrées. Aucune question ouverte impactée.

## Notes et décisions
- **Confidentialité (D5 F-231)** : rien ne sort avant le mot d'éveil, **sauf** la phrase même qui le contient
  (au plus 4 s, sans pause > 1 s) — sinon « Bonjour Jarvis » est amputé et le PO répète. Rien n'est conservé.
- Les formules d'attente du répertoire (`filler`) ne sont plus tirées ; elles restent dans la liste (clips servis)
  pour ne pas toucher au serveur dans cette SF.
