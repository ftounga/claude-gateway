# Mini-spec — F-239 / SF-239-03 — La capture réelle et le banc d'écoute

## Identifiant
`F-239 / SF-239-03` · Feature parente `F-239` · Statut `in-review` · 2026-11-02 · Branche `feat/SF-239-03-capture-et-banc`

## Objectif
> Mesurer l'écoute de Jarvis sur la vraie voix du PO : un ADMIN peut, par un interrupteur explicite, faire garder ses
> derniers énoncés (7 jours au plus), et un banc reproductible les rejoue à côté d'énoncés synthétiques propres et
> dégradés.

## Comportement attendu
### Cas nominal
- **Interrupteur** « Garder mes énoncés pour le banc (7 jours) » dans les réglages de `/jarvis`, **visible et
  utilisable par un ADMIN seulement** ; éteint par défaut ; `jarvis_preferences.capture_audio`.
- **Capture** : quand l'interrupteur est allumé, après chaque énoncé retranscrit (SF-239-04), l'écran envoie
  `POST /api/jarvis/captures` (multipart : `audio` WAV, `seconds`, `listenMode`, `streamText`, `finalText`,
  `provider`). La passerelle n'accepte que si le compte est ADMIN **et** a allumé l'interrupteur (sinon 204, rien
  gardé). Audio rangé dans le stockage objet sous `atelier/{userId}/jarvis-captures/{id}.wav`, métadonnées dans
  `jarvis_audio_captures` (`user_id`). Au plus **200** captures par compte (les plus anciennes partent).
- **Purge** : chaque heure, les captures de plus de **7 jours** sont effacées (objet puis ligne) ; éteindre
  l'interrupteur efface **tout de suite** les captures du compte ; suppression du compte ⇒ purge.
- **Lecture (ADMIN, ses propres captures)** : `GET /api/admin/jarvis/captures` (liste sans audio),
  `GET /api/admin/jarvis/captures/{id}/audio` (WAV).
- **Banc** `tools/jarvis-stt-bench` : phrases types (dont phrases réelles du PO avec hésitations) synthétisées
  (George + voix française), variantes propre / **dégradée** (réverbération, bande téléphone, Opus 12 kb/s, bruit) ;
  `--captures` rejoue les captures réelles (jeton ADMIN du PO en variable d'environnement), référence = texte corrigé
  quand il existe (SF-239-06), sinon accord avec la retranscription retenue ; WER, noms manqués, latence, € ; `--vad`
  mesure les coupures par seuil de silence.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Interrupteur demandé par un non-ADMIN | Refus | 403 |
| Capture envoyée sans interrupteur, ou par un non-ADMIN | Ignorée, rien gardé | 204 |
| Capture sans audio / trop grosse | Refus | 400 / 413 |
| Capture d'un autre compte demandée | Introuvable | 404 |
| Stockage objet en échec | Capture ignorée (journal sans contenu), l'écoute continue | 204 |

## Critères d'acceptation
- [ ] Sans interrupteur, aucune capture n'est gardée (test) ; un non-ADMIN ne peut ni l'allumer ni capturer.
- [ ] Une capture gardée est relue par son seul propriétaire (isolation `user_id`).
- [ ] Purge à 7 jours, borne de 200, effacement immédiat à l'extinction, purge à la suppression du compte.
- [ ] Le banc tourne sur synthétique propre / dégradé et sur captures réelles, et rapporte WER, noms manqués,
      latence, coût en €.

## Périmètre — hors scope
La correction par le PO (SF-239-06, qui remplira `corrected_text`) ; la relecture `compris:` (SF-239-05).

## Contraintes de validation
| Champ | Obligatoire | Borne |
|---|---|---|
| `audio` | Oui | ≤ borne de la dictée |
| `streamText`, `finalText` | Non | ≤ 2 000 caractères (tronqués) |
| `listenMode` | Non | liste fermée des modes d'écoute |
| captures par compte | — | ≤ 200, ≤ 7 jours |

## Technique
- Migration `239-jarvis-audio-captures.xml` : table `jarvis_audio_captures` (`id`, `user_id`, `created_at`,
  `storage_key`, `seconds`, `listen_mode`, `provider`, `stream_text`, `final_text`, `corrected_text`), index
  (`user_id`, `created_at`) ; colonne `jarvis_preferences.capture_audio` (faux par défaut).
- Back : `JarvisCaptureService`, `JarvisCaptureController`, `JarvisAudioCapture(Repository)`, purge planifiée,
  `JarvisPreferenceService` (capture), `AccountService` (purge nommée).
- Front : préférence `captureAudio`, interrupteur ADMIN dans les réglages, envoi après chaque retranscription.
- Outil : `tools/jarvis-stt-bench/` (README, `phrases.json`, `bench.mjs`).

## Plan de test
- Unitaires : service (porte ADMIN + interrupteur, borne 200, purge 7 j, extinction).
- Intégration : interrupteur 403 non-ADMIN ; capture 204 ignorée / gardée ; liste et audio 404 chez autrui.
- Front : l'interrupteur n'existe pas pour un non-ADMIN ; capture envoyée seulement allumé.

## Notes et décisions
- **Confidentialité** : exception explicite au régime « rien n'est conservé » (F-145 / D8 F-231), limitée à l'ADMIN
  qui l'allume pour lui-même, 7 jours, effaçable d'un geste. Jamais pour un autre compte.
