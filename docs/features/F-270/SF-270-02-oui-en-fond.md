# Mini-spec — [F-270 / SF-270-02] Le « oui » et le tour direct ne ferment plus l'écoute

## Identifiant
`F-270 / SF-270-02`

## Feature parente
`F-270` — Parler à Jarvis pendant qu'il travaille

## Statut
`in-progress`

## Date de création
2026-11-18

## Branche Git
`feat/SF-270-02-oui-en-fond`

---

## Objectif
En conversation mains libres, un tour Opus né à la voix (réponse à « Vous confirmez ? », repli quand le cerveau rapide
est indisponible ou éteint) part en fond : le micro est rouvert aussitôt, et chaque « oui » va au travail qui l'a demandé.

## Causes traitées (cadrage §2)
- **C1** — `send()` met `turnRunning` : `shouldRelisten` refuse d'écouter pendant tout le tour Opus (30-90 s). Chemin de
  chaque « oui », du repli 503 et du cerveau rapide éteint.
- Confirmation non identifiée : `awaitingVerbal` + `verbalSubject` (un seul, sans chantier) ; le « oui » part au fil
  commun du sujet même si la question venait du chantier 4. Les autorisations d'outil (`pendingConfirm`) sont tranchées
  sur le **sujet en vue** et sans chantier ; une deuxième demande écrase la première.

## Comportement attendu

### Cas nominal
1. **D1** — conversation active : le tour direct est annoncé à la passerelle (`POST /api/jarvis/works`, SF-270-01) puis
   ouvert par le chemin des délégations (`startDelegation`) : lignes 🎙 intermédiaires muettes, fin dans la file
   d'annonces (à la pause), perle de travail. `turnRunning` reste faux ; l'écoute se rouvre dès la phrase courte dite.
   - réponse à une confirmation : « C'est parti. » (oui) / « Entendu. » (non), puis le tour part en fond ;
   - repli (cerveau rapide 503, échec, repli demandé) ou cerveau rapide éteint : « Je m'en occupe. », puis en fond.
2. **D2** — chaque « Vous confirmez ? » (fin de délégation annoncée, fin de tour direct) **arme** une confirmation
   identifiée : travail, sujet, chantier, **nom court** (clé de ticket, « chantier N », nom du sujet). Une par fil (la
   plus récente remplace) ; expire après 2 min.
   - « oui » / « non » avec une seule confirmation armée ⇒ elle ;
   - plusieurs armées : la parole qui nomme l'une (« oui pour LDIC-325 ») ⇒ celle-là ; sinon Jarvis demande laquelle en
     nommant (« LDIC-325 ou agenor ? ») et **n'exécute rien** ; la réponse qui nomme l'une applique le oui/non retenu ;
   - une phrase de 4 mots ou plus qui n'est ni oui ni non est une **autre demande** : elle suit le chemin normal (cerveau
     rapide), la confirmation reste armée ; plus courte et ambiguë : « Je n'ai pas compris : je ne fais rien. »
3. Autorisation d'outil (`ask`) : tranchée sur le **sujet et le chantier du tour qui l'a demandée** ; une deuxième
   demande arrivée pendant la première attend son tour (dite après la réponse), jamais écrasée.
4. Hors conversation active (bouton, saisie) : comportement inchangé (tour au premier plan, dit au fil de l'écriture).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Annonce `POST /api/jarvis/works` en échec ou > 1,5 s | le tour part quand même (non suivi par la passerelle) |
| « oui » sans aucune confirmation armée | chemin normal (cerveau rapide), rien n'est exécuté d'office |
| « oui » avec deux confirmations armées, sans nom | « LDIC-325 ou agenor ? », rien n'est lancé |
| Réponse au « lequel ? » qui ne nomme rien | « Je n'ai pas compris : je ne fais rien. » |
| Confirmation de plus de 2 min | ignorée (expirée) |
| Téléphone du compagnon verrouillé | « oui » attend le déverrouillage (inchangé, `unlockedForYes`) |
| Budget Jarvis atteint au départ du tour | question du budget (inchangé, `budgetRefused`) |

## Critères d'acceptation
- [ ] Conversation active, « oui » à « Vous confirmez ? » : « C'est parti. » est dit, le tour part sur **le fil
      (sujet + chantier) de la question**, et l'écoute est rouverte pendant que le tour tourne.
- [ ] Repli 503 en conversation : « Je m'en occupe. », tour en fond, écoute rouverte.
- [ ] Deux confirmations armées + « oui » : question nommée, aucun tour ; « LDIC-325 » ensuite : tour sur son fil.
- [ ] « oui pour agenor » avec deux armées : directement agenor.
- [ ] Phrase longue pendant une confirmation armée : traitée comme une demande, la confirmation reste armée.
- [ ] Autorisation d'outil d'une délégation sur le chantier 4 : `confirmChatToolUse(sujet, décision, 4)` ; deuxième
      demande dite après la première.
- [ ] Hors conversation : tests existants inchangés (tour au premier plan).
- [ ] Banc à vraie voix (2 passages, boucle legacy) : aucune régression sur le jeu de référence.

## Périmètre

### Hors scope (explicite)
- Parole gardée pendant la réflexion du cerveau rapide : SF-270-03.
- Précision / nouvelle commande, arrêt ciblé : SF-270-04.
- Satellites, teintes : SF-270-05.
- Aucune consigne système, aucun outil, aucun modèle changés.

## Valeurs initiales
| Paramètre | Valeur |
|---|---|
| Expiration d'une confirmation | 2 min (`CONFIRM_TTL_MS`) |
| Délai max de l'annonce | 1,5 s (`ANNOUNCE_TIMEOUT_MS`) |
| « Autre demande » | ≥ 4 mots, ni oui ni non |

## Contraintes de validation
| Champ | Règle |
|---|---|
| Identifiant de travail | UUID tiré par l'écran (`crypto.randomUUID`) |
| Nom d'une confirmation | nom court du travail, ≤ 24 caractères (SF-270-01) |

## Technique

### Endpoint(s)
- `POST /api/jarvis/works` (SF-270-01), appelé par l'écran. Aucun endpoint nouveau.

### Tables impactées
Aucune.

### Migration Liquibase
Aucune.

### Composants Angular (si applicable)
- `jarvis/jarvis-works.ts` (neuf) : `ArmedConfirmations`, `whichQuestion`, `names`, `JarvisWorksClient.announce`.
- `jarvis.component.ts` : `sendInBackground`, confirmations armées, autorisations d'outil rattachées et en file.

## Plan de test

### Tests unitaires
- `jarvis-works.spec.ts` : armer/remplacer/expirer, cible unique/nommée/ambiguë, question nommée, autre demande.
- `jarvis.component.spec.ts` : conversation active — « oui » ⇒ tour en fond sur le fil de la question et écoute
  rouverte ; deux armées ⇒ question puis choix ; phrase longue ⇒ cerveau rapide ; repli 503 en fond ; autorisation
  d'outil sur son chantier et en file. Tests existants hors conversation inchangés.

### Tests d'intégration
- Banc à vraie voix F-256 (boucle legacy, 2 passages) sur le jeu de référence (non-régression).

### Isolation workspace
- Le tour part sur le sujet de la question, porté par la passerelle sous `user_id` (SF-270-01 : sujet d'un autre
  compte ⇒ 404).

## Dépendances

### Subfeatures bloquantes
SF-270-01 (mergée).

### Questions ouvertes impactées
Aucune.

## Notes et décisions
- **D1** « Tout tour né à la voix part en fond » est appliqué **en conversation mains libres** (là où le micro doit
  rester ouvert, cadrage D1) ; l'appui ponctuel et la saisie gardent le tour au premier plan (la réponse dite au fil de
  l'écriture), sans changement.
