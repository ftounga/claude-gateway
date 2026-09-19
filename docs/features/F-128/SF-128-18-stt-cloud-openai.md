# SF-128-18 — Transcription via STT cloud (OpenAI), activation manuelle par réunion

> Cadrage du 2026-09-19 (PO). **Livraison demandée.** Remplace la version « auto-hébergé » : après
> chiffrage (auto-hébergé ≈ 120 €/mois nœud dédié, ou effort d'infra pour le à-la-demande ; cloud ≈ 3 €/mois),
> le PO choisit **le cloud OpenAI**, avec **activation manuelle réunion par réunion** et **avertissement de
> confidentialité** avant tout envoi.

## 1. La décision
- **Service** : **OpenAI** (Whisper / `gpt-4o-transcribe`), **compatible avec le pipeline SF-128-04** déjà
  livré (`HttpTranscriptionProvider`, API compatible Whisper) → intégration directe.
- **Manuel, par réunion** : bouton **« Transcrire »** (SF-128-05) sur une réunion précise. **Rien
  d'automatique.** Ne coûte que quand le PO le déclenche.
- **Confidentialité assumée (DRAPEAU FORT)** : l'audio de la réunion **quitte le poste vers OpenAI**. Le PO
  a explicitement choisi le cloud **en pilotant au cas par cas** : il ne transcrit **que** des réunions non
  sensibles, **jamais** du CAGIP confidentiel. → **Avertissement clair AVANT envoi**, à confirmer.

## 2. Ce qu'on livre
- **Config serveur** : brancher `HttpTranscriptionProvider` sur OpenAI — `APP_STT_BASE_URL` =
  `https://api.openai.com/v1`, modèle configurable (`whisper-1` / `gpt-4o-transcribe`), **clé API OpenAI en
  secret k8s** (`backend-secrets`, jamais en clair, jamais côté client). Éteint si la clé absente
  (`503 stt_not_configured`, inchangé).
- **Avertissement + confirmation avant envoi** : au clic « Transcrire », un dialogue dit **« L'audio de
  cette réunion sera envoyé à OpenAI (hors du poste). Ne pas utiliser pour une réunion confidentielle. »**
  + case/confirmation explicite. **Aucun envoi sans ce consentement** (par réunion).
- **Le reste est déjà là** (SF-128-04) : worker async, `POST …/transcribe`, `GET …/transcript`, transcript
  horodaté + langue rattachés, puis exploitable par l'agent (SF-128-05) et rangeable dans la carte
  (SF-128-11).
- L'écran réunion reflète l'état : « Transcription disponible » / « en cours » / « indisponible ».

## 3. Sécurité & cloisonnement
- **Clé OpenAI** : secret k8s côté backend uniquement ; l'appel STT part **de la gateway**, pas du poste ni
  du navigateur.
- **Consentement par réunion** : trace de qui/quand a lancé la transcription (avertissement accepté).
- Isolation `user_id`+`host_id` inchangée ; transcript soumis à la rétention SF-128-07.
- **Bornes de coût** : durée/taille d'audio plafonnées ; ~0,006 $/min → une réunion 30 min ≈ 0,15 $.

## 4. Critères d'acceptation
- Clé OpenAI configurée → « Transcrire » produit un transcript rattaché (audio → OpenAI → texte), puis
  exploitable (SF-128-05).
- **Aucun envoi sans l'avertissement + confirmation** ; le message nomme explicitement OpenAI et « hors du
  poste ».
- Sans clé → « indisponible » propre (503), aucun envoi.
- Isolation, rétention, quota respectés.

## 5. Hors périmètre
- Le **live** (SF-128-08) : non retenu.
- STT auto-hébergé : écarté (coût), réactivable si un besoin banque strict apparaît.

## 6. Préoccupations transversales
- **Confidentialité** : audio hors poste — avertissement + confirmation **par réunion** (invariant).
- **Plans / limites** : coût OpenAI par minute — bornes + activation manuelle.
- **Composants** : `HttpTranscriptionProvider` (config OpenAI), secret k8s, écran réunion (dialogue
  d'avertissement/confirmation), worker SF-128-04 (inchangé).
