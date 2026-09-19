# SF-128-08 — Transcription en direct & assistance pendant la réunion

> **⛔ NON RETENU — décision PO du 2026-09-19.** Le PO préfère **activer la transcription MANUELLEMENT**
> (le STT est un service **payant**), au cas par cas — typiquement chez un client / dans une réunion **sans
> transcription native** — via le bouton « Transcrire » (SF-128-04) + la config STT (SF-128-18), **pas** en
> continu/live. Cadrage conservé pour trace ; **ne pas implémenter** sans réouverture explicite du PO.
>
> *(Cadrage initial ci-dessous.)* Le PO avait rouvert l'option « live » (D4 initial = « après-coup »), puis
> l'a **écartée** au vu du coût du service STT et de sa préférence pour une activation manuelle.

## 1. Le besoin
Pendant une réunion capturée (le 2-temps SF-128-16 fonctionne : Rejoindre → « En réunion ✅ » →
Enregistrer), pouvoir :
- voir la **transcription défiler en direct**,
- **demander à l'agent en temps réel** (« note cette décision », « résume ce qui vient d'être dit »,
  « quelle est l'action pour moi ? ») — une assistance **pendant** le call, pas seulement après.

## 2. Dépendance dure (à dire d'emblée)
La transcription actuelle (SF-128-04) est **par lot** : audio complet → transcript **à l'arrêt**. Le live
exige un **STT en STREAMING** : envoyer l'audio **par fragments** et recevoir des **transcripts partiels**
en continu (API temps réel / websocket du service STT). **Ce n'est pas le même connecteur.**
→ **SF-128-08 dépend de :**
1. **SF-128-18** (configuration d'un service STT) **livré et configuré** ;
2. un **variant STREAMING** du `TranscriptionProvider` (le HTTP batch ne suffit pas) ;
3. **DRAPEAU FORT confidentialité** : en live, l'audio quitte le poste **en continu** vers le service STT
   — encore plus sensible en banque que le batch. Opt-in explicite, par poste/client.

**Sans un STT streaming configuré, SF-128-08 ne peut pas être validé.** On le livre **quand** le service
STT (et son mode streaming) est choisi.

## 3. Ce qu'on livrerait
- **Flux audio en cours de capture** : la capture (qui écrit déjà l'audio) **émet aussi des fragments**
  au fil de l'eau (sans casser l'enregistrement complet existant, qui reste la source de vérité).
- **`StreamingTranscriptionProvider`** (abstraction + une impl temps réel compatible avec le service
  choisi), **asynchrone**, bornée.
- **Transcript live** à l'écran de la réunion en cours (partiels + consolidés), horodaté.
- **Assistance live** : un fil « demander à l'agent » **pendant** le call, alimenté par le transcript
  partiel + les images récentes (multimodal), via l'`AIProvider` (Gateway-First) — quota + BYOK ; contenu
  = donnée (anti-injection). « Note cette décision » → engagement Radar (réutilise SF-128-06 / F-104).
- **Repli** : si le streaming tombe, on retombe sur la transcription **batch** (SF-128-04) à l'arrêt.

## 4. Découpage
| SF | Titre | Contenu |
|---|---|---|
| **SF-128-08a** | **Transcript live** | Fragments audio pendant la capture → `StreamingTranscriptionProvider` → transcript partiel affiché en direct. Repli batch. |
| **SF-128-08b** | **Assistance live** | Fil « demander à l'agent » pendant le call (transcript partiel + images), « note cette décision » → engagement Radar. |

**Ordre** : 08a → 08b. **Précondition** : SF-128-18 + service STT **streaming** configuré.

## 5. Risques / points durs
- **STT streaming** : coût continu + latence + le service doit exposer un mode temps réel ; l'audio sort en
  continu (confidentialité banque).
- **Charge** : capture + streaming STT + appels agent en direct = plus de ressources ; bornes.
- **À valider sur call réel** (comme tout ce volet).
- **Ne pas déstabiliser** la capture 2-temps qui marche : le flux live est **additif**, l'enregistrement
  complet + l'exploitation après-coup restent la base.

## 6. Hors périmètre
- Remplacer l'exploitation après-coup (elle reste).
- Un STT maison.

## 7. Préoccupations transversales
- **Confidentialité** : opt-in par poste/client ; audio en continu hors poste = à valider avec le client.
- **Plans / limites** : streaming STT + agent live consomment fortement — bornes + quota.
- **Composants** : capture (émission de fragments), `StreamingTranscriptionProvider`, écran réunion (live),
  `AIProvider` (assistance), Radar (SF-128-06). Dépend de **SF-128-18**.
