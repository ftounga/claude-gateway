# Cadrage — F-274 · Les demandes hors plan s'exécutent dans un sujet à plusieurs chantiers

> Besoin `8e704ca6-2699-4046-867d-16b8d184561c` (BUG, réservé à l'admin), 2026-11-18, sujet alarm4tech (poste EDENRED).
> Consigne PO : « investigue, cadre et déploie ». Date du cadrage : 2026-11-21.

## 1. Le constat, prouvé en prod (lecture seule)

Sujet `alarm4tech` (`1409eec9…`), mode GUIDÉ, chantiers 1 (vague 2) et 2 (lot secrets RH), tous deux en **Exécution
sur un plan validé** (v2/v2). La vague 1 n'a aucun chantier.

| Heure (18/11, UTC) | Fil | Demande | Mesure du tour (`atelier_turn_measures`) | Réponse |
|---|---|---|---|---|
| 10:02 | ch. 2 | « GO pour clôturer SIP-10567… » | 1 itération, **0 outil**, cause « réponse rendue », 0 refus | « Ce tour s'est terminé avant que je fasse le relevé… » |
| 10:04 | ch. 2 | reformulée | 1 itération, 0 outil | « Relance simplement la demande dans le fil principal » |
| 10:06 | ch. 2 | « Va dans le chantier qu'il faut. Dans le fil principal : … » | 5 itérations | exécuté, Jira 204 ; « mes deux réponses précédentes étaient fausses : j'ai fermé SIP-10536 depuis ce même fil hier » |
| 10:13 | ch. 2 | « Régénère la page État des lieux (vague 1, vague 2, secrets) » | 1 itération, 0 outil | « ce tour s'est terminé avant que je lance quoi que ce soit » |

Aucun événement `GATE_BLOCKED` sur ces tours : **la porte était ouverte** (un chantier en Exécution suffit, F-206).
Rien n'a coupé ces tours. **C'est le modèle qui a choisi de ne rien faire**, puis a décrit ce choix comme une coupure.
Contournement du PO : ouvrir un chantier 3 « reliquat de la vague 1 et pilotage transverse » (11:11) — sitôt validé, la
même page est régénérée (11:19, 22 itérations).

### Les autres cas (toutes conversations de chantier depuis le 25/10)

| Sujet | Date | Fil | Type | Issue |
|---|---|---|---|---|
| alarm4tech | 17/11 10:09 | ch. 2 | geste hors de tout chantier (SIP-10536) | **exécuté** « hors chantier 2 » — même demande refusée le lendemain |
| data-platform | 04/11 18:37 | ch. 11 | amender le plan d'un **autre** chantier (9) | refus : « un fil ne porte qu'un chantier ; ouvre le fil du chantier 9 » |
| data-platform | 04/11 18:48 | ch. 11 | idem, redemandé explicitement | refus : « le texte exact des étapes 1 à 15 n'est consultable ni dans ce fil ni dans les fichiers » |
| data-platform | 10/11 09:54 | ch. 6 | ticket d'aucun chantier (LDIC-366, MR + apply prod) | **exécuté** « hors chantier parce que tu l'as demandé » |
| data-platform | 30/10 10:14 | ch. 9 | ticket classé « hors chantier » | sorti de toutes les listes de ce qui restait (oubli) |
| agenor | 06/11 09:28 | ch. 7 | tickets ouverts hors de tout chantier | signalés, sans place où les suivre |

**Types de demandes bloquées** : (T1) geste ponctuel hors de tout chantier (clôture de ticket, commentaire) ;
(T2) livrable **transverse** à plusieurs chantiers (page de synthèse, preuves, messages consolidés) ; (T3) geste qui
sert un **autre** chantier ouvert que celui du fil (amendement, étape) ; (T4) le suivi de ces gestes (oubliés).

## 2. Causes racines (prouvées par le code, `origin/main` 63232cd5)

- **C1 — Doctrine sans issue.** La note du fil (`AtelierChatService.laneNote`, dans le message) dit « Reste sur ce
  chantier ; si la demande relève d'un autre, dis-le » ; le guide système (`JourneyToolCatalog.GUIDE`) dit
  « EXÉCUTION : n'exécute que des étapes du plan validé. Une modification hors plan est un AMENDEMENT ». Une demande
  qui n'appartient à **aucun** plan, ou à **plusieurs**, n'a aucune voie légale : le modèle tranche au hasard (exécute
  le 17/11, refuse le 18/11 pour la même demande).
- **C2 — Le « fil principal » est introuvable.** Dans un sujet à chantiers, le volet de gauche porte toujours un
  chantier (`pane-layout.ts` : `arrivalLayout`, `prune`) et la carte des volets (`terminal-pane-map`) ne liste que les
  chantiers. Le fil du sujet (chantier `null`, qui relit **tout** le sujet — `ChantierThread.visibleTo`) existe côté
  serveur mais aucun clic n'y mène. L'agent y renvoie quand même.
- **C3 — Le plan d'un autre chantier est illisible.** `JourneyTurnNote.several` ne résume les autres chantiers qu'en
  une ligne ; `set_subject_plan` exige le plan complet : depuis un autre fil, un amendement est impossible sans
  recopier de mémoire.
- **C4 — Une cause d'arrêt inventée.** Les vraies coupures sont déjà dites par la gateway (troncature « Ma réponse a
  dépassé la taille maximale… », budget, plafond, refus de porte rendu au modèle « cette action n'a pas été
  exécutée »). Le modèle, lui, a écrit « ce tour s'est terminé avant… » pour un tour où il n'a rien tenté.
- **C5 — Pas de rattachement.** Un geste transverse n'est rattaché à aucun chantier : les autres fils l'ignorent
  (le fil d'un chantier ne relit que le commun et le sien).

## 3. Ce qui existe déjà (vérifié, réutilisé)

- La porte agrège les chantiers (F-206) : ouverte dès qu'un chantier est en Exécution. Les outils du parcours prennent
  `chantier: N` (F-206) — viser un autre chantier depuis un fil est déjà possible.
- La validation écrite vérifiée par le harnais (F-222, `valider_plan`, `cloturer_chantier`) : `user_words` cités dans
  le tour, jamais en course autonome. **Même mécanisme** pour le couloir.
- Le fil du sujet (lane `null`) côté serveur et côté écran (`laneLabelOf(null)` = « Fil du sujet »).
- Le journal du parcours (`subject_journey_events`) et sa mesure.

## 4. La réponse (décisions)

1. **Le couloir hors chantier** — un outil `geste_hors_chantier` (mots exacts de l'utilisateur dans ce tour, chantiers
   concernés, objet) : la demande explicite s'exécute **dans le fil où elle est faite**, sans amendement. Le harnais
   vérifie la citation (comme `valider_plan`), ouvre la porte **pour ce tour seulement** (utile quand aucun chantier
   n'est en Exécution) et journalise le geste. Jamais en course autonome ni en routine. Le couloir couvre ce que
   l'utilisateur a demandé, pas plus.
2. **Rattaché à chacun** — le geste est journalisé une fois par chantier concerné (ou « aucun chantier ») ; le fil de
   chacun de ces chantiers le relit au tour suivant (bloc « Parcours du sujet », dans le message), et l'écran le
   montre au chantier.
3. **Jamais de renvoi sans lien** — doctrine : ne jamais renvoyer vers un autre fil ; un geste qui sert un autre
   chantier se fait ici avec `chantier: N` ; le plan d'un autre chantier se lit avec `lire_plan_chantier`. Le fil du
   sujet devient atteignable d'un clic dans la carte des volets.
4. **La cause réelle** — doctrine : un refus est le choix de l'agent, dit comme tel avec sa raison ; seules les
   coupures du harnais (déjà annoncées par la gateway) sont des coupures ; jamais « le tour s'est terminé ».

**Règle absolue du PO (justesse, cache)** : rien n'est retiré au raisonnement — on lève une contradiction. Le guide
reste un **littéral stable** (préfixe du cache) : une phrase de plus, identique à chaque tour. Tout ce qui varie
(gestes rattachés, note du fil) va dans le **message**.

## 5. Découpage

| SF | Titre | Couche | Estimation |
|---|---|---|---|
| SF-274-01 | Le couloir hors chantier (outil vérifié, porte du tour, doctrine du fil et de l'arrêt) | backend | 1 j |
| SF-274-02 | Rattaché à chacun : le chantier relit ses gestes hors plan, le plan d'un autre chantier se lit | backend | 1 j |
| SF-274-03 | Le fil du sujet d'un clic et les gestes hors plan à l'écran | frontend | 1 j |

## 6. Hors périmètre

- Recopier les messages d'un fil dans un autre (risque sur la séquence rejouée et le cache) : le rattachement passe par
  le journal et le bloc du parcours.
- Changer la porte (F-206) ou le classement des risques.
- Un chantier « fourre-tout » automatique.

## 7. À vérifier en usage réel (après déploiement)

- Sur alarm4tech ou data-platform : une demande hors de tout chantier depuis un fil de chantier s'exécute au premier
  essai ; `subject_journey_events` type `OUT_OF_PLAN` présent ; aucune réponse « relance dans le fil principal ».
- Une demande transverse : un événement par chantier cité, relu au tour suivant de l'autre chantier.
- Aucune réponse « ce tour s'est terminé » sur un tour à 0 outil (requête sur `atelier_messages`).
