# Mini-spec — F-233 / SF-233-09 — Comprendre l'interruption (D9, D10, D11, D-PO-5)

## Identifiant
`F-233 / SF-233-09` · Feature parente `F-233` · Statut `in-review` · 2026-11-01 · Branche `feat/SF-233-09-comprendre-l-interruption`

## Objectif
> Quand le PO coupe Jarvis, Jarvis comprend sans appel modèle : un acquiescement le fait reprendre à la phrase
> coupée, un ordre de silence le fait taire, une annulation interrompt le travail, et tout le reste devient une
> demande dont le message dit ce qui a **réellement été entendu** de la réponse coupée.

## Comportement attendu
- **Classement local** (`classifyInterruption`, liste fermée, normalisation existante) :
  - `ack` — « ok », « mmh », « oui oui », « d'accord », « je vois », « continue »… ⇒ **reprise** : la phrase coupée
    (entière) puis la file restante sont redites ; rien n'est envoyé ; compteur `acknowledgements`.
  - `silence` — « stop », « chut », « tais-toi », « c'est bon », « ça suffit »… ⇒ Jarvis se tait, **rien d'autre**
    (le travail en cours continue).
  - `cancel` — « laisse tomber », « annule », « arrête ça », « arrête tout », « arrête », « interromps » ⇒
    l'interruption existante (`interruptChat`) si un tour tourne (D11 / D-PO-5) ; `isStopCommand` reconnaît aussi
    « arrête ça ».
  - `request` — tout le reste, et **dans le doute** ⇒ une demande (jamais une action).
- **Note de coupure** (D10) : pour une demande qui ouvre un **tour neuf**, la requête porte `heardBeforeCut` (la part
  entendue : phrases finies + début de la phrase coupée, SF-233-08) ; la passerelle ajoute **au message** du tour la
  note fixe *« [Jarvis — ta réponse orale a été coupée après : « … ». Le reste n'a pas été entendu.] »* (≤ 600
  caractères, guillemets neutralisés) — **jamais à la consigne système** ; le message rangé dans le fil reste la parole
  du PO ; le texte écrit du tour précédent reste intact.
- **Parole pendant un tour qui tourne** (D11, D-PO-5) : précision (mécanisme existant) par défaut, sans note ;
  annulation sur phrase explicite.

## Contrat API (additif)
`POST /api/workspaces/{id}/chat` et `/chat/stream` : champ optionnel `heardBeforeCut` (≤ 2 000 caractères, 400 au-delà),
pris en compte **seulement** avec `via = "JARVIS"`.

## Cas d'erreur
| Situation | Comportement |
|---|---|
| `heardBeforeCut` sans `via=JARVIS` | ignoré |
| `heardBeforeCut` > 2 000 caractères | 400 (validation) |
| Coupure sans rien à reprendre (« ok » après la fin) | repos, rien d'autre |
| Phrase ambiguë (« ok mais vérifie… ») | demande |

## Critères d'acceptation
1. « mmh » ⇒ reprise à la phrase coupée, aucun envoi, acquiescement compté.
2. « chut » ⇒ silence, aucun envoi, aucune interruption du travail.
3. « laisse tomber » pendant un tour ⇒ interruption du tour.
4. Une demande après coupure ⇒ le message du tour suivant porte la note « coupée après : … » ; la consigne système
   ne la porte pas ; le fil garde la parole du PO.
5. Un message tapé ne porte jamais de note.

## Plan de test
- Unitaires : `classifyInterruption` (les quatre classes, doute ⇒ demande), `appendCut` (vide ⇒ inchangé, guillemets).
- Backend : `AtelierChatServiceVoiceTurnTest` (note dans le message, pas dans le système, fil intact),
  `AtelierChatControllerForceTest` (la part entendue voyage avec la marque ; message tapé sans note).
- Écran : « mmh » ⇒ reprise ; « chut » ⇒ silence ; demande ⇒ `heardBeforeCut` envoyé (SF-233-08).

## Impacts
`AtelierChatRequest` (+ `heardBeforeCut`), `AtelierChatController` (marque), `AtelierChatService` (`VoiceMark`,
`consumeVoiceMark`, note jointe au message), `JarvisOralNote.appendCut` ; écran `jarvis-oral.ts`
(`classifyInterruption`), `JarvisComponent`, `AtelierService.streamChat` (paramètre optionnel). Aucune table.

## Préoccupations transversales
Aucune (le champ est additif ; auth, tenant, limites et navigation inchangés ; préfixe système intact).

## Hors périmètre
Classement par un modèle ; reprise au mot près (on reprend à la phrase coupée).
