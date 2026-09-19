# SF-128-18 — STT managé auto-hébergé (Whisper sur notre infra)

> Cadrage du 2026-09-19 (PO). **Cadrage seul : livraison sur go.** Décision PO : **un STT managé unique,
> auto-hébergé**, fourni par le produit à tous (comme l'`AIProvider`/Textract) — **pas** de « bring your
> own endpoint ». Remplace la version « config d'endpoint par utilisateur » (abandonnée).

## 1. La décision
- **UN service STT** fourni par le produit, **auto-hébergé sur NOTRE infra** (EKS `legalcase-shared`).
- **Pourquoi** : l'app sert des clients **banque** → l'audio de réunion **ne doit pas partir chez un
  tiers**. Auto-hébergé = **poste → notre gateway → notre Whisper (in-cluster) → transcript** ; **aucun
  tiers**. Qualité **éprouvée** (Whisper large open-source, SOTA).
- **Manuel / à la demande** (déjà acquis) : bouton **« Transcrire »** par réunion → ne coûte du compute que
  quand le PO le déclenche.

## 2. Architecture
- **Service Whisper in-cluster** : un conteneur **faster-whisper** (whisper-large-v3, quantifié **int8**)
  exposant une **API compatible Whisper** (`POST /v1/audio/transcriptions`), en **ClusterIP interne**
  (pas d'Ingress, jamais exposé publiquement).
- **Branchement** : `HttpTranscriptionProvider` (SF-128-04, déjà là) pointé sur ce service via
  `APP_STT_BASE_URL` = l'URL interne du service (+ clé interne si on en met une). **Rien d'autre à changer
  dans le pipeline** (worker async, endpoints `…/transcribe`/`…/transcript` inchangés).
- **Managé** : plus de config par utilisateur ; une fois le service déployé, « Transcrire » marche **pour
  tous**. L'écran réunion reflète l'état (disponible / indisponible).

## 3. Infra & coût — point d'attention HONNÊTE (précédent incident)
Whisper-large est **lourd**, et notre cluster est **partagé avec legalcase** et **proche de sa limite**
(cf. incident RDS/nœuds du 2026-09-18). Donc **prudence obligatoire** :
- **CPU, pas GPU** par défaut : faster-whisper **int8 sur CPU** évite le coût d'un nœud GPU. Plus lent
  (transcription **asynchrone** — déjà le cas via le worker), acceptable pour un usage **manuel/ponctuel**.
  Une réunion de 30 min ≈ quelques minutes de transcription CPU — OK en batch.
- **Bornes strictes** : `requests`/`limits` CPU+mémoire **plafonnés** pour **ne pas affamer legalcase** ;
  idéalement **scale-to-zero** (le pod ne tourne que lorsqu'on transcrit — KEDA/agent, ou un simple
  démarrage à la demande) pour ne rien consommer au repos.
- **Modèle ajustable** : si la capacité est trop juste, replier sur **whisper-medium** (plus léger, qualité
  encore très correcte) — drapeau à décider à la mini-spec selon la mesure.
- **Vérifier la capacité du cluster AVANT de déployer** (nœuds, mémoire) et déployer **au calme** (garde-fou
  « aucun tour actif », comme les autres déploiements).

## 4. Découpage
| SF | Titre | Contenu |
|---|---|---|
| **SF-128-18a** | **Déployer le service Whisper auto-hébergé** | Image faster-whisper (whisper-large-v3 int8) + API compatible Whisper ; manifeste k8s (ClusterIP interne, **requests/limits bornés**, scale-to-zero si possible) sur `legalcase-shared` ; **vérif capacité** + déploiement au calme. Interne only. |
| **SF-128-18b** | **Brancher + refléter l'état** | `APP_STT_BASE_URL` → service interne ; `HttpTranscriptionProvider` (SF-128-04) l'utilise ; l'écran réunion montre « Transcription disponible » ; « Transcrire » marche pour tous ; santé du service surveillée. |

**Ordre** : 18a (déployer) → 18b (brancher).

## 5. Sécurité & cloisonnement
- **Interne only** (ClusterIP, aucun Ingress) : le service Whisper n'est **jamais** exposé publiquement.
- **Aucune donnée vers un tiers** : l'audio reste dans notre cluster ; transcript stocké comme aujourd'hui
  (SF-128-04, rétention SF-128-07). Isolation `user_id`+`host_id` inchangée.
- Le modèle Whisper est **local au conteneur** (téléchargé à la construction de l'image, pas d'appel
  externe au runtime).

## 6. Critères d'acceptation
- Un service Whisper **interne** répond en compatible Whisper ; `HttpTranscriptionProvider` le consomme.
- « Transcrire » sur une réunion capturée produit un **transcript** rattaché (audio → in-cluster → texte),
  **sans** appel à un tiers.
- Le service est **borné en ressources** (requests/limits) et ne dégrade pas legalcase (mesuré).
- Sans le service (arrêté/scale-0-non-démarré) → « Transcrire » dit clairement « indisponible » (pas de
  crash), et **aucun audio ne fuit**.

## 7. Hors périmètre
- STT tiers (Deepgram/OpenAI…) — écarté (données banque).
- Le **live** (SF-128-08) — non retenu.
- GPU dédié — évolution seulement si le volume l'exige.

## 8. Préoccupations transversales
- **Plans / limites** : le compute STT est **notre coût** ; activation manuelle par réunion le borne ;
  compteur/quota à envisager si le volume grandit.
- **Infra partagée** : capacité `legalcase-shared` — **bornes + scale-to-zero + déploiement au calme**
  (précédent incident). **Composants** : nouvelle image + manifeste Whisper, `HttpTranscriptionProvider`
  (config), écran réunion (état), worker SF-128-04 (inchangé).
