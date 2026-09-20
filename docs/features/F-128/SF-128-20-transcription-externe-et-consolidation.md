# SF-128-20 — Transcription externe (client) + consolidation multi-sources

> Cadrage du 2026-09-20 (PO). **Cadrage seul : livraison sur go.** Virage stratégique validé : plutôt que
> de construire notre propre diarisation (qui parle), on **apporte la transcription du client** (déjà
> attribuée aux vrais noms) et on **consolide** avec notre transcription + le **visuel capté** — le seul
> élément que le client ne donne pas.

## 1. Le besoin (raisonnement PO)
- Chez la plupart des clients, **la transcription Teams existe et est affichée** (avec les vrais noms), mais
  **non téléchargeable** faute de droits. Le PO peut la **récupérer** (copier ce qui est à l'écran).
- Ce que le client **ne donne pas** : le **visuel exploitable** (decks, slides, partage d'écran) — que
  **notre app capture déjà** (F-128).
- **Idée** : notre app devient le point où l'on **réunit tout** — transcription du client (vrais noms) +
  notre transcription (comble les trous, réunions sans transcript client) + **images/deck** → une analyse
  **plus riche et plus précise** que n'importe quelle source seule. C'est notre valeur différenciante.

## 2. Objectif (une phrase)
Permettre d'**attacher une transcription externe** (celle du client) à une réunion capturée, et faire en
sorte que l'**exploitation par l'agent consolide** transcription externe + notre transcription + images,
**en traçant la provenance** de chaque élément.

## 3. Comment on apporte la transcription externe (décision : robuste, pas de scraping)
- **Coller le texte** (le PO sélectionne la transcription affichée dans Teams et la colle), **et/ou
  déposer un fichier** (`.txt`, `.vtt`, `.docx` quand il l'a). **Pas** de lecture automatique de l'écran
  (fragile, DOM v2 — écarté sciemment).
- Une transcription externe = **du texte attribué** (souvent « Nom : … » ou format `.vtt` horodaté). On la
  stocke **telle quelle** (on ne la re-parse pas agressivement) ; le modèle sait lire « Nom : texte ».
- **v1** : **une** transcription externe par réunion (remplaçable). *(Plusieurs sources externes =
  évolution si besoin.)*

## 4. Consolidation (le cœur de la valeur)
L'exploitation (`MeetingExploitationService`, SF-128-05) reçoit désormais **jusqu'à trois matières** :
1. **Transcription externe (client)** — **prioritaire** : porte les **vrais noms** → « qui parle à qui ».
2. **Notre transcription** (OpenAI, SF-128-04) — **complément** : comble les trous, ou seule source si pas
   de transcript client.
3. **Images / deck** (multimodal) — le **visuel** que le client ne donne pas.

**Règles de consolidation :**
- **Provenance obligatoire** dans la sortie : « d'après la transcription client… », « d'après le partage
  d'écran… », « (non présent dans la transcription client, vu dans la nôtre) ». Jamais de mélange muet —
  c'est l'exigence du PO (rigueur « qui dit quoi »).
- **Ne rien inventer** (consigne anti-injection existante conservée) ; si les sources se contredisent, le
  dire.
- Les **vrais noms** viennent de la transcription externe ; notre transcription (sans noms) sert au fond,
  pas à l'attribution.
- Bornes de dépense conservées (troncature, images plafonnées, sortie bornée) — appliquées à l'ensemble.

## 5. Découpage
| SF | Titre | Contenu |
|---|---|---|
| **SF-128-20a** | **Attacher une transcription externe** | Coller du texte **ou** déposer `.txt`/`.vtt`/`.docx` sur une réunion ; stockage (nouvelle colonne(s)/table) + libellé de source (« Transcription Teams (client) ») ; affichage dans l'écran réunion ; remplaçable. Isolation `user_id`+`host_id`. |
| **SF-128-20b** | **Consolidation à l'exploitation** | `MeetingExploitationService` assemble externe (prioritaire) + notre transcript (complément) + images ; **provenance** dans la synthèse/Q&A ; règles de contradiction ; bornes. |

**Ordre** : 20a → 20b.

## 6. Modèle de données (esquisse)
- Sur `meetings` : `external_transcript` (text), `external_transcript_source` (varchar, ex. « Teams client »),
  `external_transcript_format` (`text`/`vtt`/`docx`), `external_transcript_added_at`. **Migration** au
  prochain numéro libre. *(Ou petite table `meeting_transcripts` si on veut plusieurs sources — à trancher
  en mini-spec ; v1 colonnes suffit.)*
- Notre transcription (`transcript`, SF-128-04) **inchangée**.

## 7. Critères d'acceptation
- On peut **coller** ou **déposer** une transcription externe sur une réunion ; elle s'affiche et est
  **remplaçable** ; isolée `user_id`+`host_id`.
- L'exploitation **utilise les deux transcripts + les images** et **cite la provenance** de chaque point
  (décisions/actions/résumé/Q&A).
- Sans transcript externe : comportement actuel inchangé (notre transcript + images).
- Sans **aucune** transcription : exploite images + métadonnées et dit ce qui manque (inchangé).
- Rien d'inventé ; contradictions signalées.

## 8. Hors périmètre
- **Lecture automatique** de la transcription à l'écran (scraping DOM) — écarté (fragile).
- **Diarisation maison** sur notre audio — écartée (on s'appuie sur les noms du client).
- Fusion/normalisation fine des formats `.vtt`/`.docx` en structure canonique — v1 stocke le texte tel
  quel ; parsing avancé = évolution.
- Le **live** (SF-128-08, non retenu).

## 9. Préoccupations transversales
- **Confidentialité** : la transcription externe est du **texte apporté par le PO** (pas d'audio hors
  poste pour cette partie) ; isolation `user_id`+`host_id` ; contenu = donnée (anti-injection).
- **Plans / limites** : la consolidation envoie plus de matière au modèle → bornes de dépense (troncature).
- **Composants** : `Meeting` (+ champs), migration, `TeamsMeeting*Controller`/service (attacher), écran
  détail réunion (coller/déposer + affichage), `MeetingExploitationService` (consolidation + provenance),
  `AIProvider` (inchangé). Aucun runner.
