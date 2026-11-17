# CADRAGE F-270 — Parler à Jarvis pendant qu'il travaille

**Statut** : Cadrée (2026-11-18). **Gouvernance** : cadrage seul, aucun code. Mini-specs SF-270-xx à produire au
démarrage de chaque subfeature (`subfeature-template.md`).
**Liée à** : F-271 (Jarvis lance le travail), qui en dépend pour la fluidité des « oui, lance ».

---

## 1. Demande PO (2026-11-18, après la démo jugée « beaucoup mieux »)

> « Ce qui rend la conversation pas fluide : quand il commence à réfléchir, on ne peut pas lui demander autre
> chose. Je veux pouvoir continuer à parler à Jarvis pendant qu'il fait des choses, lancer une autre commande, et
> qu'il les traite. Et l'animation doit montrer qu'il y a des traitements en cours en arrière-plan. »

Traduction vérifiable : (1) aucune parole du PO n'est ignorée tant qu'un travail tourne ; (2) une deuxième
commande **indépendante** est traitée comme une commande, pas fondue dans la première ; (3) l'écran montre,
sans phrase, combien de travaux tournent, sur quoi, et dans quel état.

## 2. Existant vérifié (`origin/main` `4a02f04e`, prod du 17/11)

Beaucoup est déjà là : **F-235 D10** a posé « le PO continue de converser avec le cerveau rapide pendant
qu'Opus travaille ». Ce qui marche et ce qui coince :

| Élément | Où | État |
|---|---|---|
| Délégation asynchrone (`deleguer` → tour Opus en fond, plusieurs à la fois, ≤ 8/utilisateur) | `jarvis/fastbrain/JarvisFastBrainService`, `JarvisDelegationRegistry` ; écran `jarvis.component.ts` `startDelegation()` | **Marche** : le 17/11 à 23:02-23:04 le PO a parlé deux fois pendant une délégation de 50 s |
| File d'annonces de fin (FIFO, à la pause, `label`, « redis-moi ») | `jarvis.component.ts` `announcements` / `flushAnnouncements()` | Marche |
| Un tour vivant **par fil** ; un envoi sur un fil vivant devient une **précision** (interjection +10 min, +15 étapes) | `atelier/live/LiveTurnRegistry.openOrSteer`, `AtelierChatService` (`INTERJECTION_LABEL`) | Voulu pour une précision ; **piège** pour une commande indépendante |
| Fil par chantier, worktree par chantier parallèle | F-221, `atelier/LaneWorktree` | Marche |
| Petit/gros cerveau, perles par délégation, couleurs `HOLO_COLORS` (`deep #9B7BFF`) | `stage3d/jarvis-brains.ts`, `stage3d/jarvis-motion.ts` | Partiel |
| Liste « Recherches en cours » sous l'orbe | `jarvis.component.html` (`jv__delegations`) | Texte en phrases → contraire à la règle « écran sans phrases » |

### Causes prouvées du « on ne peut plus lui parler »

- **C1 — Le tour direct ferme l'écoute.** `JarvisConversation.shouldRelisten` : `active && state === 'repos' &&
  !turnRunning`. Un tour lancé par `send()` (chemin `streamChat` direct, pas une délégation) met `turnRunning` :
  le micro reste fermé **toute la durée du tour Opus** (30-90 s). Or ce chemin est celui des **confirmations**
  (F-235 D8 : une « Vous confirmez ? » en attente court-circuite le cerveau rapide et envoie la parole suivante
  directement au tour Opus) et du repli 503. Conséquence : **chaque « oui, lance » gèle la conversation** —
  exactement le moment où F-271 veut qu'on continue.
- **C2 — La parole dite pendant « réflexion » est jetée.** `onStreamTurn` : `if (!this.streamListening ||
  this.state() !== 'ecoute') { … 'voice_turn_ignored' }`. Pendant les 2-6 s où le cerveau rapide prépare sa
  réponse, ce que dit le PO est perdu sans retour (en `parole`, l'interruption F-233 coupe la voix, ça va).
- **C3 — Une deuxième commande sur le même fil est fondue dans la première.** Délégation vers un fil vivant ⇒
  précision (`ADDED_TO_RUNNING` « J'ajoute ça à la recherche en cours »). Correct pour « et regarde aussi les
  logs », faux pour « et lance aussi les tests de LDIC-330 » sur le même sujet sans chantier : l'ordre devient
  une note dans un tour qui fait autre chose.
- **C4 — L'état des délégations est en mémoire du pod** (`JarvisDelegationRegistry`, `ConcurrentHashMap`,
  reformulation 2 min, `take()` même pod). Deux pods en prod ⇒ délégation perdue — **défaut traité en ce moment
  par l'autre équipe** (voir §8).
- **C5 — L'écran ne sépare pas premier plan et fond.** Le cœur passe en `reflexion` pour un tour direct, ce qui
  dit « je ne vous écoute pas » ; les perles n'existent que pour les délégations ; la liste en texte est hors règle.

## 3. Comportement cible — scénarios vocaux

**S1 — Deux commandes, deux sujets.**
> PO : « Jarvis, regarde dans data-platform s'il y a des tickets du backlog que je peux prendre. »
> Jarvis (≤ 1,5 s) : « Je regarde le backlog de data-platform, Monsieur. » — une perle `data-platform` part en
> orbite, teinte « réfléchit ».
> PO (5 s après) : « Et sur agenor, où en est le chantier 4 ? » — le cerveau rapide répond depuis le contexte,
> ou délègue : deuxième perle `agenor`.
> … 40 s plus tard, à la première pause : « Pour data-platform : quatre tickets du sprint 9 vous sont assignés,
> dont LDIC-325. » La perle se pose en vert puis s'éteint.

**S2 — Le « oui » ne gèle plus rien.**
> Jarvis : « Je lance LDIC-325 en autonome, sept étapes. Vous confirmez ? » — PO : « Oui. » — Jarvis : « C'est
> parti. » ; la perle `LDIC-325` passe en teinte « exécute ». Le micro est **déjà rouvert** :
> PO : « Montre-moi mes rendez-vous de demain. » → réponse immédiate, la course continue en fond.

**S3 — Précision ou nouvelle commande, même sujet.**
> Travail en cours sur data-platform (lecture du backlog). PO : « Et regarde aussi ceux du sprint 10. » ⇒
> **précision** (même travail) : « J'ajoute le sprint 10. »
> PO : « Et relance le pipeline de dev de data-ingestion. » ⇒ **nouvelle commande** : si un autre chantier du
> sujet est concerné, elle part dans son fil, en parallèle ; sinon elle est **enchaînée** derrière le travail en
> cours du même fil (une seule en attente par fil) et Jarvis le dit : « Je le lance dès que la lecture du
> backlog est finie. » Perle en attente, teinte « attente ».

**S4 — Parler pendant qu'il pense.** Le PO dit une phrase pendant les 3 s de réflexion du cerveau rapide : elle
est **gardée** et traitée juste après la réponse en cours (jamais jetée). Si elle annule (« laisse tomber »),
elle est appliquée tout de suite.

**S5 — « Qu'est-ce qui tourne ? »** Réponse orale courte depuis le contexte compact (délégations en cours :
déjà fourni au cerveau rapide) ; l'écran met les perles en avant, étiquetées.

**S6 — Arrêter un travail précis.** « Arrête agenor. » ⇒ interrompt la délégation/course **nommée** (aujourd'hui :
la dernière seulement), la perle passe « annulé » puis s'éteint.

**S7 — Poste éteint.** Une commande qui agit sur un poste éteint : « Le poste CAGIP est éteint, je ne peux pas
agir. » Aucune mise en attente (décision PO du 15/11, inchangée). Une commande enchaînée (S3) dont le poste
s'éteint avant son départ est **abandonnée et dite**, jamais gardée.

## 4. Décisions prises par défaut

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Tout tour né à la voix part en fond** (même chemin que `startDelegation`), y compris les confirmations et le repli 503 : `turnRunning` ne ferme plus jamais l'écoute en mode mains libres. | Cause C1 ; aucune raison de garder un chemin bloquant |
| D2 | **La confirmation est rattachée à son travail**, pas au « dernier tour » : chaque « Vous confirmez ? » arme une confirmation identifiée (fil + chantier + délégation). Plusieurs confirmations armées ⇒ Jarvis demande laquelle en nommant (« LDIC-325 ou agenor ? »), jamais d'exécution sur une ambiguïté. | Plusieurs travaux en parallèle rendent « le dernier » ambigu ; règle « réponse ambiguë = n'agit pas » (JarvisOralNote) |
| D3 | **Parole pendant `reflexion` gardée** (une seule, la dernière), traitée à la fin de la réponse ; « stop/annule » appliqué immédiatement. | Cause C2 ; pas d'empilement |
| D4 | **Le cerveau rapide qualifie la relation** dans `deleguer` : `precision` (même travail) ou `nouvelle` (autre travail). `nouvelle` sur un fil vivant ⇒ (a) fil d'un autre chantier du sujet s'il est désigné ; (b) sinon **enchaînée** : au plus **une** commande en attente par fil, lancée à la fin du tour vivant, annoncée, annulable, abandonnée si le poste s'éteint. Doute ⇒ `precision` + annonce qui le dit (le PO corrige à la voix). | Cause C3 ; l'enchaînement borné à 1 n'est pas la « file d'actions » refusée par le PO (poste allumé, départ dans la minute) |
| D5 | **L'état des travaux vivants devient durable et partagé entre pods** (table ou registre partagé), alimenté par délégations, tours directs nés à la voix, courses autonomes F-219. **Réutiliser** ce que livre l'autre équipe pour la délégation perdue entre pods ; ne pas créer un second registre. | Cause C4 |
| D6 | **Premier plan ≠ fond à l'écran.** Le cœur ne montre que l'échange en cours (écoute/parle/réfléchit à sa réponse) ; chaque travail en fond est un **satellite** en orbite (perle actuelle enrichie), étiqueté d'**un nom court** (sujet, clé de ticket, « chantier 4 »), teinte selon la phase : réfléchit (`deep #9B7BFF`, existant), exécute sur le poste, attend votre oui (`wait #5FD4FF`), fini (`green`), échec (`alert`). La liste texte « Recherches en cours » est **retirée** (code supprimé, pas masqué). | Règles PO : sans phrases, au centre, lumineux, sans clic |
| D7 | Arrêt ciblé : « arrête <nom> » vise le travail nommé ; sans nom, le dernier (comportement actuel). | S6 |
| D8 | Plafond inchangé : 8 délégations vivantes par utilisateur, 4 courses autonomes par compte (F-219). Au-delà, Jarvis le dit. | Pas de nouveau curseur |

**Question au PO (goût visuel seulement)** : la teinte de « exécute sur le poste » (voir F-271 §6, même
question — posée une seule fois). Tout le reste est tranché.

## 5. Découpage (≈ 7,5 j, dans cet ordre)

| SF | Contenu | Estim. | Dépend de |
|---|---|---|---|
| **SF-270-01** — L'état durable des travaux vivants | Registre partagé entre pods (travail = fil, chantier, origine délégation/direct/course, label, phase, confirmation armée) ; API lecture `GET /api/jarvis/works` (filtre `user_id`) ; flux SSE de changement de phase. **Branché** sur la correction « délégation perdue entre pods » de l'autre équipe. | 1,5 j | autre équipe |
| **SF-270-02** — Le « oui » et le tour direct ne ferment plus l'écoute | D1 + D2 : tours vocaux en fond, confirmation identifiée, désambiguïsation nommée ; tests composant (micro rouvert pendant un tour, deux confirmations armées). | 1,5 j | 01 |
| **SF-270-03** — Rien n'est jeté pendant qu'il pense | D3 : parole gardée en `reflexion`, rejouée après ; stop immédiat ; traces `voice_turn_kept`. | 1 j | — |
| **SF-270-04** — Précision ou nouvelle commande | D4 : champ `relation` de `deleguer` (description d'outil du cerveau rapide, préfixe Opus intact), routage autre chantier / enchaînement 1 par fil, abandon poste éteint, annonces ; D7 arrêt ciblé. | 2 j | 01 |
| **SF-270-05** — Le fond se voit | D6 : satellites étiquetés, teintes par phase, cœur au premier plan, retrait de la liste texte ; repli 2D et téléphone allégés ; captures pour visa PO. | 1,5 j | 01 |
| **SF-270-06** — Banc et recette guidée | Corpus vraie voix (scénarios S1-S7), 2 passages verts consécutifs, recette guidée (phrases exactes, attendus) pour l'essai PO. | 0,5 j (+ temps machine) | 02-05 |

## 6. Risques

- **Coût** : aucun surcoût par travail (mêmes tours Opus) ; le PO lancera plus de travaux à la fois, donc la
  dépense instantanée monte. Plafonds existants (D8) et budget Jarvis F-244 inchangés. Justesse avant coût.
- **Cache** : rien de volatil dans les consignes système ; le champ `relation` ne touche que l'outil du cerveau
  rapide (son préfixe change une fois, mesuré via `cache_write` du cerveau rapide). Préfixe Opus intact.
- **Concurrence sur un même fil** : un tour vivant par fil reste la règle (LiveTurnRegistry) ; l'enchaînement est
  la seule nouveauté, borné à 1. Course autonome F-219 et ordre oral sur le même chantier : l'ordre est une
  précision de la course **seulement** si `relation = precision` ; sinon enchaîné après la course (jamais injecté
  dans une planification, cf. bug F-221 agenor).
- **Mauvaise qualification precision/nouvelle** : annonce explicite de ce qui est fait, correction à la voix,
  mesurée au banc (cible ≥ 95 % de bonne relation sur le corpus).
- **Engagement** : inchangé, voir F-271 §5 ; D2 empêche qu'un « oui » parte sur le mauvais travail.
- **Dépendance** : SF-270-01 attend la correction pods de l'autre équipe ; ne pas la dupliquer.

## 7. Mesure de succès

- `voice_turn_ignored` hors état `parole` : **0** sur une séance (base : à relever sur la prochaine séance avant
  livraison, `jarvis_listen_traces`).
- Temps micro fermé pendant un tour Opus né à la voix : **0 s** (aujourd'hui : durée entière du tour direct).
- ≥ 2 travaux simultanés dans au moins une séance réelle du PO, chacun annoncé à sa fin.
- Bonne relation precision/nouvelle ≥ 95 % au banc.
- Verdict PO à l'essai : « je peux lui parler pendant qu'il travaille ».

## 8. Plan de banc

Banc à vraie voix F-256, **2 passages verts consécutifs** avant merge de chaque SF Jarvis, puis tournée en prod
(règle `bancs-jarvis-ne-pas-alleger`). Corpus ajouté : S1 à S7 dits à voix réelle, avec chevauchements
(commande pendant `reflexion`, pendant une délégation, pendant une course autonome, deux confirmations armées,
poste éteint). Critères pré-fixés : aucune parole perdue, bonne cible de chaque « oui », annonces dans l'ordre de
fin, premier son de la deuxième commande ≤ son p50 actuel (pas de régression de latence). Coût du banc affiché.

## 9. Points de contact avec l'autre équipe (correctifs de la démo du 17/11, en cours)

- **Délégation perdue entre pods** → base de SF-270-01 (D5) : brancher, ne pas refaire.
- **Changement de sujet à la voix** → D4 s'appuie sur le sujet courant correct.
- **Vérification des ordres d'écran** et **diag:/3D dans le vocabulaire d'Opus** → sans recouvrement ; SF-270-05
  ne touche pas aux ordres « 🖥 montrer ».

## 10. Hors périmètre

- Agir poste éteint ou garder une file d'actions (refus PO du 15/11).
- Changer le modèle, l'effort ou la consigne système d'Opus.
- Le retrait des panneaux latéraux de `/jarvis` (chantier séparé déjà noté).
