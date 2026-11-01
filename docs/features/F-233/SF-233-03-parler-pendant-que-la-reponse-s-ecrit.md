# Mini-spec — F-233 / SF-233-03 — Parler pendant que la réponse s'écrit (D4, D-PO-7, D-PO-8)

## Identifiant
`F-233 / SF-233-03` · Feature parente `F-233` (cadrage D4, D-PO-7, D-PO-8) · Statut `in-review` · 2026-11-01
Branche `feat/SF-233-03-parler-pendant-l-ecriture`

## Objectif
> Jarvis dit chaque phrase de la ligne « 🎙 » **dès qu'elle est complète** dans le flux de la réponse (au lieu
> d'attendre la fin du tour), annonce ce qu'il va faire avant d'utiliser des outils, et s'adresse au PO en le
> vouvoyant et en l'appelant « Monsieur ».

## Comportement attendu
- **Écran** : les deltas `onText` sont lus. Toute ligne qui commence par « 🎙 » (hors bloc de code) est découpée en
  phrases ; une phrase est **complète** quand sa ponctuation finale est suivie d'un blanc, ou que la ligne est
  close. Chaque phrase complète part **une seule fois** dans une **file de phrases** : la synthèse de la suivante
  est demandée pendant que la précédente joue (préchargement d'une phrase). Au plus deux phrases par ligne 🎙.
- La première phrase dite coupe l'accusé, passe l'orbe en « parole », pose les marques `firstOralSentence`,
  `firstAudioByte`, `firstSound` du chronomètre (SF-233-01).
- **Fin du tour** (`onDone`) : la file se vide jusqu'au bout ; ce qui reste de la phrase orale finale non encore dit
  est dit ; si aucune ligne 🎙 n'a été écrite, la phrase orale de la réponse est dite comme avant (repli F-231).
- **Tour long** (≥ 30 s) : on ne dit plus au fil de l'eau ; la phrase finale est dite à la fin précédée de « C'est
  terminé. » (règle F-231 conservée).
- Les confirmations F-231 sont **inchangées** : une action qui engage n'est confirmée que sur la phrase finale
  complète et un « oui » net ; l'écoute de la réponse ne démarre qu'à la fin du tour.
- **Interruption** (appui, mot d'éveil, « stop ») : la phrase en cours est coupée et **la file vidée**.
- **Consigne orale** (dans le message, jamais dans la consigne système — `JarvisOralNote`, texte fixe) complétée :
  - « Si tu dois utiliser des outils, commence par une ligne 🎙 qui dit en une phrase ce que tu vas faire ; ta réponse
    finale commence aussi par sa ligne 🎙. » (D-PO-7) ;
  - « Adresse-toi à l'utilisateur en le vouvoyant et en l'appelant « Monsieur », avec parcimonie (début d'échange,
    annonce, interpellation ; pas à chaque phrase). » (D-PO-8) ;
  - la demande de confirmation devient « Vous confirmez ? ».
- **Phrases fixes de l'écran** passées au vouvoiement (D-PO-8) : confirmation d'outil (« … Vous confirmez ? »),
  doute, question en attente, alerte de veille (« Que voulez-vous faire ? »). `asksConfirmation` reconnaît « Vous
  confirmez ? » **et** « Tu confirmes ? » (anciens tours).

## Cas d'erreur
| Situation | Comportement |
|---|---|
| Voix absente / plafond (503 / 429) sur une phrase de la file | la file s'arrête, la phrase reste écrite, message habituel |
| Phrase sans ponctuation finale en cours d'écriture | attend la fin de ligne ou la ponctuation suivie d'un blanc |
| Ponctuation non finale (« 3.5 ») | pas de coupure |
| Bloc de code contenant « 🎙 » | ignoré |
| Une précision envoyée pendant le tour | seul le dernier envoi parle (règle existante) |

## Critères d'acceptation
1. Une phrase 🎙 complète arrivée par delta est dite avant `onDone`.
2. Une phrase déjà dite n'est jamais redite à la fin.
3. Une réponse sans 🎙 est dite à la fin comme avant.
4. La consigne orale porte l'annonce avant outils et l'adresse « Monsieur » + vous ; elle reste dans le message.
5. « Vous confirmez ? » déclenche l'écoute de la réponse.

## Plan de test
- Unitaires (pur) : `OralStream` (phrases complètes au fil des deltas, ponctuation suivie d'un blanc, décimales, ligne
  close, fence, deux phrases max, `remaining`) ; `asksConfirmation` (vous/tu).
- Écran : delta 🎙 complet ⇒ `enqueue` avant `onDone` ; pas de redite ; repli sans 🎙.
- Backend : `JarvisOralNoteTest` (annonce avant outils, Monsieur/vouvoiement, « Vous confirmez ? »), non-régression
  du tour Jarvis (consigne dans le message seulement).

## Impacts
`JarvisOralNote` ; écran `JarvisComponent`, `jarvis-oral.ts` (`OralStream`, `oralSentences`), `jarvis-confirm.ts`,
`JarvisVoiceService` (front : `enqueue`, `idle`, `stop` vide la file). Aucune table, aucun endpoint.

## Préoccupations transversales
Aucune (pas d'auth, de tenant, de limites — la synthèse reste plafonnée par la route existante —, ni de navigation).

## Hors périmètre
Lecture des deltas hors ligne 🎙 ; découpe côté serveur ; changement de la consigne système.
