# CADRAGE F-271 — Jarvis lance le travail : du ticket au chantier qui tourne, à la voix

**Statut** : Cadrée (2026-11-18). **Gouvernance** : cadrage seul, aucun code. Mini-specs SF-271-xx à produire au
démarrage de chaque subfeature.
**Dépend de** : F-270 (sans elle, chaque « oui, lance » gèle la conversation pendant tout le tour Opus).

---

## 1. Demande PO (2026-11-18)

> « Depuis que Jarvis tourne, je n'ai jamais pu lui demander de lancer une tâche, on ne fait que du monitoring.
> Le but : lui dire d'aller dans un sujet, regarder s'il y a des tickets que je peux prendre ; il récupère, me
> dit, pose le plan si je le demande, lance les traitements si je le demande, et ça se voit qu'il travaille —
> animation, par ex. le cerveau qui change de couleur, trouve celle qui va bien. Ambition : demain ne même plus
> aller dans le terminal ; quand j'ai des tickets où j'ai tout, juste avec Jarvis lui faire lancer les
> traitements. »

## 2. Constat mesuré en prod (`atelier_messages`, `via = JARVIS`, depuis le 01/11)

- 200 messages Jarvis ; **16 tours Opus** seulement (le reste = cerveau rapide).
- Dans ces 16 tours : **0** `propose_guided_mode`, **0** `valider_plan`, **0** `lancer_chantier_autonome`,
  2 `set_subject_plan`. Le constat du PO est exact : Jarvis n'a jamais lancé de travail.
- La démo du 17/11 23:05 prouve que **la lecture marche déjà** : « Regarde dans data-platform s'il y a des
  tickets du backlog que je pourrais prendre » → délégation → Opus lit Jira **sur le poste** (bash, `cd
  ~/dev/data-platform`) → « quatre tickets du sprint 9 vous sont assignés… LDIC-325 semble porter le besoin
  d'Adam ». La séance s'est arrêtée là (pause à 23:05:41).
- Deux réponses Opus de la démo finissent par « L'amendement v3 du chantier 13 **attend toujours votre clic** » :
  le chemin oral bute sur un geste réservé au clic.

## 3. Existant réutilisé (vérifié sur `origin/main` `4a02f04e`)

Presque toute la chaîne existe ; **il manque des maillons, pas un moteur.**

| Maillon | Où | État |
|---|---|---|
| Délégation à un tour Opus **complet** (bash poste, fichiers, outils du parcours) | `jarvis/fastbrain/JarvisFastBrainService` (`deleguer`), `AtelierChatService` `via=JARVIS` | Marche |
| Tickets Jira lus **sur le poste** (jira/acli en bash), extraits en faits datés | `jarvis/state/extract/TicketExtractors.Jira` → `jarvis_state_facts` (F-247) | Marche |
| Pancartes de tickets (clé, statut, champs courts) | F-248 `jarvis/cards/*` (`JarvisCardCatalog`, `JarvisCardResolver`) | Marche ; à vérifier sur un tour **délégué** |
| Parcours guidé : investigation → plan → exécution → vérification, plusieurs chantiers par sujet, titre de chantier | F-176, F-206 ; `atelier/journey/JourneyToolCatalog` ; `SubjectJourney.chantierTitle` | Marche |
| Gestes dicibles avec `user_words` cités mot pour mot | `valider_plan`, `confirmer_diagnostic`, `cloturer_chantier` (F-222, F-176, F-261) | Marche |
| **Ouvrir un chantier** | `propose_guided_mode` → boutons [Passer en guidé] / [Rester libre] (`SubjectJourneyController.acceptGuided`) | **Clic seulement** — maillon manquant |
| Exécution d'un trait : préflight + course autonome, une par chantier, 4 par compte, worktree par chantier parallèle | F-219 `atelier/autonomy/AutonomyService`, `lancer_chantier_autonome` ; F-221 `LaneWorktree` | Marche |
| Fin / arrêt de course notifiés | `AUTONOMY_DONE` / `AUTONOMY_STOPPED` (F-219) | **Non dits par Jarvis** — maillon manquant |
| Confirmation orale avant ce qui engage | `jarvis/JarvisOralNote.TEXT` (« Vous confirmez ? », « oui » explicite au tour suivant) | Marche |
| Mode offensif : le plan validé vaut feu vert pour ses étapes | F-218, `JourneyToolCatalog.GUIDE` | Marche |
| Poste éteint : Jarvis refuse d'agir | F-262 `offlineInstead()`, `autonomy_host_offline` | Marche |
| Teinte « Opus au travail » | `stage3d/jarvis-motion.ts` `HOLO_COLORS.deep #9B7BFF`, `stage3d/jarvis-brains.ts` | Partiel (délégations seulement) |

### Maillons manquants (causes)

- **M1** — Ouvrir un chantier exige un clic : aucun chemin oral vers [Passer en guidé].
- **M2** — Un chantier n'est pas rattaché à son ticket : impossible de dire « lance LDIC-325 » et d'être compris
  sans connaître le numéro de chantier ; l'écran ne peut pas étiqueter la course par sa clé.
- **M3** — Certains gestes du plan restent au clic à l'oral (amendement à revalider, étape EXTERNE) — preuve :
  « l'amendement v3 attend toujours votre clic » (17/11).
- **M4** — Les événements de course (étape franchie, blocage, fin) ne reviennent pas dans la voix ni la scène
  Jarvis : le PO doit retourner au terminal pour savoir.
- **M5** — Aucun parcours oral n'enchaîne les maillons ; Opus, à l'oral, ne sait pas qu'on attend de lui
  « ticket → chantier → plan → lancement » (doctrine absente de `JarvisOralNote`, qui ne parle que de
  confirmer).
- **M6** — Le « oui » gèle l'écoute (→ F-270 C1).

## 4. Comportement cible — scénarios vocaux (CAGIP, sujet data-platform, tickets LDIC)

**S1 — Repérer.**
> PO : « Jarvis, va dans data-platform et regarde s'il y a des tickets que je peux prendre. »
> Jarvis : « Je regarde le Jira de data-platform, Monsieur. » (satellite `data-platform`, teinte réfléchit)
> … « Quatre tickets du sprint 9 sont à vous ; LDIC-325 et LDIC-330 sont prêts, LDIC-331 attend une réponse
> d'Adam. » Au centre : **trois pancartes** LDIC-325 / LDIC-330 / LDIC-331, clé + statut + un mot (prêt /
> attend Adam). Aucune phrase écrite.

**S2 — Détailler.** « Qu'est-ce que demande LDIC-325 ? » → réponse orale en une idée ; la pancarte grandit
(champs courts : statut, priorité, rapporteur, composant).

**S3 — Poser le plan** (sur demande).
> PO : « Prends LDIC-325 et pose-moi le plan. »
> Jarvis : « J'ouvre le chantier LDIC-325 et je prépare le plan. » → chantier ouvert **sans clic** (la demande vaut
> accord, M1), titré par la clé (M2), investigation puis `set_subject_plan`.
> … « Plan prêt pour LDIC-325 : six étapes, de la création du topic SNS au test en dev ; une étape externe, la
> MR. » Au centre : le chantier comme constellation de 6 étapes étiquetées par un nom court (« SNS », « IAM »,
> « Terraform », « Test dev », « MR »…).

**S4 — Lancer** (sur demande, un « oui » explicite).
> PO : « Valide et lance-le. »
> Jarvis : « Je valide le plan v1 de LDIC-325 et le lance en autonome jusqu'à la MR. Vous confirmez ? »
> PO : « Oui. » → `valider_plan` + préflight + `lancer_chantier_autonome` ; « C'est parti. » ; le satellite passe
> en teinte **exécute** ; les étapes s'allument une à une. Le micro reste ouvert (F-270).

**S5 — Suivre sans terminal.** À la pause : « LDIC-325 : trois étapes sur six. » (seulement sur demande ou à un
jalon : blocage, fin) ; « Où en est LDIC-325 ? » → réponse depuis l'état. Blocage : « LDIC-325 est bloqué : il
manque le droit IAM sur le compte data-ingestion dev. Je vous le détaille ? » Fin : « LDIC-325 est fini : MR
ouverte, tests verts. » Pancarte MR au centre.

**S6 — Deux tickets en parallèle.** « Lance aussi LDIC-330. » → second chantier, second worktree, seconde course ;
deux satellites (F-219 : 4 courses par compte).

**S7 — Arrêter.** « Arrête LDIC-330. » → « Reprendre la main » de la course nommée, « Arrêté. »

**S8 — Poste éteint.** « Lance LDIC-325. » poste CAGIP éteint → « Le poste CAGIP est éteint, je ne peux pas
lancer LDIC-325. » Rien n'est gardé pour plus tard (décision PO du 15/11).

## 5. Engagements — ce qui exige un « vas-y » oral explicite

| Geste | Règle à l'oral | Justification |
|---|---|---|
| Lire tickets, détailler, état d'un chantier | **Libre** | Lecture |
| Ouvrir un chantier pour un ticket, investiguer, poser/amender le plan | **La demande vaut accord** (« prends LDIC-325 », « pose le plan ») ; jamais de sa propre initiative | Réversible, rien ne sort du poste |
| Valider un plan / un amendement à objectif changé | **« Oui » explicite** après reformulation nommant ticket + version (`valider_plan` avec `user_words`) | Le plan validé vaut feu vert (F-218) |
| Lancer l'exécution (course autonome) | **« Oui » explicite** après reformulation (ticket, nombre d'étapes, jusqu'où) ; « valide et lance » = **une seule** confirmation pour les deux | Ce qui démarre l'action sur le poste et vers l'extérieur |
| Étapes EXTERNE (MR, transition Jira, apply) **dans un plan validé** | Couvertes par la validation du plan (mode offensif F-218) ; une étape EXTERNE **ajoutée** par amendement repasse par un « oui » oral nommant l'étape | Pas de chemin qui contourne la validation |
| Message à un tiers (mail, Teams, commentaire Jira hors plan) | **Jamais par Jarvis** (inchangé) | `JarvisOralNote` |
| « Oui » ambigu (plusieurs confirmations armées), silence, « euh » | **N'agit pas**, demande laquelle en nommant | F-270 D2 |

Un « vas-y » reconnu à tort est le risque majeur : la reformulation nomme toujours le **ticket** et l'**effet**,
et la transcription du « oui » est rangée au journal (canal journalisé de `valider_plan`).

## 6. Décisions prises par défaut

| # | Décision |
|---|---|
| D1 | **Aucun nouveau moteur** : tout passe par la délégation existante vers un tour Opus du sujet ; Jarvis n'exécute rien lui-même (Gateway-First, Provider-First). |
| D2 | **Ouvrir un chantier à la voix** : `propose_guided_mode` reçoit un `user_words` facultatif (même règle que `valider_plan` : mots cités dans ce tour, demande explicite d'ouvrir/prendre/planifier) ; présent et valide ⇒ chantier ouvert directement, sinon proposition au clic comme aujourd'hui. Pas de nouvel outil. |
| D3 | **Le chantier porte sa clé** : titre `LDIC-325 — <résumé>` via `chantierTitle` existant (pas de migration) ; Jarvis et l'écran résolvent « LDIC-325 » → (sujet, chantier). |
| D4 | **Doctrine orale du parcours** ajoutée à `JarvisOralNote` (texte figé, **dans le message**, version incrémentée) : ticket demandé ⇒ ouvrir le chantier, investiguer, poser le plan, s'arrêter ; « lance » ⇒ reformuler + « Vous confirmez ? » ; jamais d'initiative au-delà de la demande. |
| D5 | **Gestes du plan dicibles** : amendement et étape EXTERNE ajoutée validables par `valider_plan` avec `user_words` nommant la version/l'étape ; le clic reste possible. |
| D6 | **La course parle** : étape franchie → scène seulement ; blocage, fin, arrêt → annonce dans la file F-235 (à la pause, jamais par-dessus le PO) + notification existante si Jarvis est fermé. Progression dite **seulement sur demande** (« où en est… »). |
| D7 | **Pancartes de tickets aussi après un tour délégué** : brancher F-248 sur les faits `JIRA:*` extraits du tour délégué (vérifier, compléter si absent). |
| D8 | **La couleur du travail** (demande PO « le cerveau qui change de couleur, trouve celle qui va bien ») : trois lectures du cerveau — converse (orange de la charte, inchangé), **réfléchit** en fond (`deep #9B7BFF`, existant), **exécute** sur le poste (défaut proposé : **or `#E8B44A`**, déjà dans la palette « or » proposée en F-236 et distinct du vert « fini » et du cyan « attend votre oui »). Le cœur prend la teinte du travail le plus « fort » en cours (exécute > réfléchit) **en halo**, le premier plan (écoute/parle) restant lisible. |
| D9 | Mobile : même parcours à la voix ; scène allégée (satellites sans constellation d'étapes). |

## 7. Découpage (≈ 8 j, dans cet ordre ; F-270 SF-01/02 d'abord)

| SF | Contenu | Estim. |
|---|---|---|
| **SF-271-01** — Ouvrir un chantier à la voix, titré par son ticket | D2 + D3 : `user_words` sur `propose_guided_mode`, ouverture directe, titre à clé, résolution « LDIC-325 » → chantier ; tests (mots absents ⇒ proposition au clic, isolation `user_id`, poste éteint). | 1,5 j |
| **SF-271-02** — La doctrine orale du parcours | D4 : `JarvisOralNote` v4 (ticket → chantier → plan ; « lance » ⇒ confirmer) ; D5 gestes du plan dicibles ; test d'égalité de la consigne système. | 1 j |
| **SF-271-03** — Valider et lancer en un « oui » | Enchaînement `valider_plan` → préflight → `lancer_chantier_autonome` sur une confirmation ; raisons de préflight rouges **dites** (« il manque… ») ; arrêt nommé. | 1,5 j |
| **SF-271-04** — La course parle à Jarvis | D6 : événements de course (étape, blocage, fin, arrêt) dans le registre des travaux F-270 + file d'annonces ; « où en est X ? » répondu depuis l'état. | 1,5 j |
| **SF-271-05** — Tickets et chantier à l'écran | D7 pancartes après délégation ; constellation des étapes du chantier (noms courts) ; D8 couleur exécute + halo ; captures pour visa PO. | 1,5 j |
| **SF-271-06** — Banc et recette guidée « LDIC » | Scénarios S1-S8 à vraie voix, sur un sujet de banc (copie de data-platform ou ticket de test, **jamais** une vraie MR client), 2 passages verts ; recette guidée pour le PO. | 1 j |

## 8. Risques

- **Coût** : un chantier lancé à la voix coûte ce qu'il coûte au terminal (même tour, même course). Plusieurs
  courses en parallèle ⇒ dépense simultanée plus forte ; plafonds F-219 (4/compte, 8 tours/course) et F-244
  inchangés. Justesse avant coût : aucune réduction d'effort pour les tours Jarvis.
- **Cache** : `JarvisOralNote` est dans le **message** ⇒ préfixe intact. Le paramètre `user_words` ajouté à
  `propose_guided_mode` change la liste d'outils **une fois** pour tous les tours (réécriture unique du préfixe) ;
  littéral stable ensuite. À mesurer (`cache_write` du jour du déploiement, puis retour à la normale à J+1).
- **Concurrence** : un tour vivant par fil de chantier, une course par chantier, worktree par chantier
  parallèle (repli sans git F-221 : copie isolée ou refus dit). Ordre oral pendant une course du même chantier :
  règle F-270 D4 (précision seulement si même travail, jamais injecté dans une planification).
- **Mauvaise reconnaissance d'un « oui »** : reformulation nommée + confirmation identifiée (F-270 D2) +
  journal. Une confirmation non reçue en 2 min expire (dit : « Je n'ai pas lancé LDIC-325. »).
- **Jira sur le poste** : la lecture dépend des outils du poste (jira/acli, proxy NTLM chez CAGIP) ; échec dit
  avec sa cause, jamais inventé.
- **Banc sur client réel** : jamais de MR ni de transition Jira réelles en banc (sujet de banc dédié).

## 9. Mesure de succès

- Base (01/11 → 18/11) : **0** chantier ouvert, **0** plan validé, **0** course lancée depuis Jarvis.
- Cible à J+14 après déploiement : ≥ 1 chantier ticket → plan → course lancé **entièrement à la voix** par le PO,
  sans passer par le terminal ; `atelier_messages.via = JARVIS` avec `lancer_chantier_autonome` ≥ 1/semaine.
- 0 action lancée sans « oui » journalisé (audit des courses `via = JARVIS`).
- Verdict PO : « je n'ai pas eu besoin du terminal pour ce ticket ».

## 10. Plan de banc

Banc à vraie voix F-256, 2 passages verts consécutifs avant chaque merge Jarvis, tournée en prod ensuite.
Corpus : S1-S8 + pièges (« oui » pendant deux confirmations armées, « lance » sans ticket nommé, plan avec étape
EXTERNE ajoutée, préflight rouge, poste éteint, Jira injoignable). Critères pré-fixés : aucune action sans
« oui » explicite, cible correcte à 100 %, préflight rouge toujours dit, aucune phrase à l'écran (contrôle des
captures). Coût du banc affiché au PO.

## 11. Dépendances et points de contact

- **F-270** (SF-270-01/02) avant SF-271-03/04.
- **Autre équipe (démo du 17/11)** : délégation perdue entre pods (prérequis de la fiabilité du lancement),
  changement de sujet à la voix (« va dans data-platform » doit cibler le bon sujet), vérification des ordres
  d'écran (pancartes/constellation), diag:/3D dans le vocabulaire d'Opus — aucun recouvrement avec ce cadrage ;
  SF-271-05 ne touche pas aux ordres « 🖥 montrer ».

## 12. Hors périmètre

- Créer ou modifier des tickets Jira depuis Jarvis hors d'une étape de plan validé.
- Agir poste éteint, file d'actions (refus PO du 15/11).
- Sous-agent écrivain maison (F-121-13) : non requis, la course autonome F-219 suffit.
- Retrait des panneaux latéraux de `/jarvis`.

## Question au PO (goût visuel, une seule)

La teinte « **exécute** » du cerveau : **or `#E8B44A`** (défaut retenu), ou une autre ? Le reste est tranché.
Vaut aussi pour F-270 (même teinte).
