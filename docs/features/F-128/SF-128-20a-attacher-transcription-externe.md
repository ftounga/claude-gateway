# Mini-spec — F-128 / SF-128-20a — Attacher une transcription externe (client)

## Identifiant
`F-128 / SF-128-20a`

## Feature parente
`F-128` — Capturer et exploiter une réunion Teams

## Statut
`ready`

## Date de création
2026-09-20

## Branche Git
`feat/SF-128-20a-attacher-transcription-externe`

---

## Objectif
Permettre d'attacher à une réunion capturée **la transcription du client** (celle affichée dans
Teams, avec les vrais noms) — par **collage de texte** ou **dépôt d'un fichier** (`.txt`/`.vtt`/`.docx`) —
stockée telle quelle, remplaçable, isolée `user_id`+`host_id`.

---

## Comportement attendu

### Cas nominal
1. **Coller du texte** — `PUT …/external-transcript` (JSON `{text, source?}`) : le texte est validé
   (non vide, borné), la source libellée (défaut « Transcription externe (client) »), le format `TEXT`,
   `external_transcript_added_at = now()`. Remplace toute transcription externe déjà présente.
2. **Déposer un fichier** — `POST …/external-transcript` (multipart `file`, `source?`) : selon le
   contenu, `.docx` → **extraction de texte** (réutilise `DocxTextExtractor`, F-86), `.vtt`/`.txt` →
   texte décodé UTF-8 **tel quel**. Format `VTT`/`DOCX`/`TEXT`, source, `added_at`. Remplace l'existant.
3. **Lire** — `GET …/external-transcript` (text/plain) : rend le texte, 404 si aucune.
4. **Retirer** — `DELETE …/external-transcript` : vide les 4 colonnes (204). Idempotent.
5. La réunion (`MeetingResponse`) porte `hasExternalTranscript`, `externalTranscriptSource`,
   `externalTranscriptFormat`, `externalTranscriptAddedAt` pour l'affichage.

### Cas d'erreur
| Situation | Comportement | Code |
|-----------|--------------|------|
| Texte collé vide / blanc | `invalid_meeting` message explicite | 400 |
| Texte / fichier trop volumineux (> 1 000 000 car.) | message explicite | 400 |
| Fichier vide | message explicite | 400 |
| `.docx` corrompu / hostile (zip-bomb, XXE) | `invalid_document` (via `DocxTextExtractor`) | 422 |
| Réunion inconnue ou d'un autre couple `user_id`/`host_id` | introuvable (indiscernable) | 404 |
| Sans droit Teams | refus | 403 |
| Poste hors Vigie | conflit | 409 |
| `GET` sans transcription externe | introuvable | 404 |

---

## Critères d'acceptation
- [ ] Coller du texte l'attache ; il se relit via `GET` ; `MeetingResponse.hasExternalTranscript = true`.
- [ ] Déposer un `.txt` et un `.vtt` stocke le texte tel quel (format `TEXT`/`VTT`).
- [ ] Déposer un `.docx` valide en extrait le texte (format `DOCX`) ; un `.docx` corrompu → 422.
- [ ] Un second envoi **remplace** la transcription externe (une seule par réunion).
- [ ] `DELETE` vide la transcription externe ; `GET` renvoie alors 404.
- [ ] Texte vide → 400 ; fichier vide → 400 ; > borne → 400.
- [ ] **Isolation** : Bob ne peut ni attacher, ni lire, ni supprimer la transcription externe d'une
      réunion du poste d'Alice → 404.
- [ ] Migration 121 boote sur H2 **et** Postgres (schéma Hibernate validé).

---

## Périmètre

### Hors scope (explicite)
- Lecture automatique de l'écran Teams (scraping DOM).
- Normalisation/parsing canonique fin des formats `.vtt`/`.docx` (stockage tel quel ; le `.docx` est
  seulement mis à plat en texte par l'extracteur existant).
- **Plusieurs** transcriptions externes par réunion (v1 = une, remplaçable).
- La **consolidation** à l'exploitation (transcription externe + la nôtre + images) → **SF-128-20b**.

---

## Valeurs initiales
| Champ | Valeur initiale | Règle |
|-------|-----------------|-------|
| external_transcript | NULL | rempli au premier attachement |
| external_transcript_source | NULL | libellé fourni, sinon défaut « Transcription externe (client) » |
| external_transcript_format | NULL | `TEXT` / `VTT` / `DOCX` selon la source |
| external_transcript_added_at | NULL | `now()` à chaque attachement |

---

## Contraintes de validation
| Champ | Obligatoire | Longueur max | Format | Normalisation |
|-------|-------------|--------------|--------|---------------|
| text (collage) | Oui | 1 000 000 car. | non vide après strip | strip, tronqué à la borne |
| source | Non | 200 car. | texte libre | strip ; défaut si vide |
| file | Oui (dépôt) | 20 Mo (borne dépôt) | `.txt`/`.vtt`/`.docx` (par contenu) | UTF-8 ; docx → extraction |

---

## Technique

### Endpoints
| Méthode | URL | Auth | Garde |
|---------|-----|------|-------|
| PUT | `/api/vigie/hosts/{hostId}/meetings/{meetingId}/external-transcript` | Oui | Teams + Vigie |
| POST | `/api/vigie/hosts/{hostId}/meetings/{meetingId}/external-transcript` (multipart) | Oui | Teams + Vigie |
| GET | `/api/vigie/hosts/{hostId}/meetings/{meetingId}/external-transcript` (text/plain) | Oui | Teams + Vigie |
| DELETE | `/api/vigie/hosts/{hostId}/meetings/{meetingId}/external-transcript` | Oui | Teams + Vigie |

### Tables impactées
| Table | Opération | Notes |
|-------|-----------|-------|
| meetings | ALTER (ajout 4 colonnes) + UPDATE/SELECT | isolation `user_id`+`host_id` |

### Migration Liquibase
- Oui — `121-meetings-external-transcript.xml` (colonne texte : `text`/`varchar(1000000)` par dbms, cf. 115 ;
  timestamp : `timestamp with time zone`, cf. 116). Réversible (rollback dropColumn).

### Composants Angular
- `MeetingDetailPageComponent` — section « Transcription externe (client) » (collage + dépôt + affichage
  + remplacer + retirer), charte `DESIGN_SYSTEM.md`.
- `TeamsMeetingService` — méthodes `externalTranscript` / `setExternalTranscript` / `uploadExternalTranscript`
  / `clearExternalTranscript`.
- `teams-meeting.models.ts` — champs sur `TeamsMeeting`.

---

## Plan de test

### Tests unitaires (`ExternalTranscriptServiceTest`)
- [ ] attachText : texte stocké, format TEXT, source par défaut, addedAt renseigné.
- [ ] attachText : texte vide → `MeetingValidationException`.
- [ ] attachFile `.txt`/`.vtt` : texte tel quel, format déduit.
- [ ] attachFile `.docx` : texte extrait via `DocxTextExtractor` (format DOCX).
- [ ] attach remplace la transcription externe existante.
- [ ] clear : colonnes vidées.
- [ ] isolation : réunion d'un autre couple → `MeetingNotFoundException`, rien écrit.

### Tests d'intégration (`TeamsMeetingExternalTranscriptApiIntegrationTest`)
- [ ] PUT texte → 200 ; GET → 200 texte ; MeetingResponse.hasExternalTranscript = true.
- [ ] POST `.txt`, `.vtt`, `.docx` → 200 ; `.docx` corrompu → 422.
- [ ] PUT texte vide → 400.
- [ ] Remplacement (2 PUT) : la seconde gagne.
- [ ] DELETE → 204 ; GET → 404.
- [ ] Gardes : sans droit Teams 403 ; hors Vigie 409.

### Isolation
- [ ] Bob (droit Teams) sur une réunion du poste d'Alice → 404 en PUT/POST/GET/DELETE.

### Migration
- [ ] Contexte Spring boote en profil `test` (H2) — colonnes créées, schéma validé. (PG couvert par le
      découpage per-dbms identique à 115/116, déjà éprouvé.)

---

## Dépendances
- SF-128-01 (artefact réunion), SF-128-04 (transcript, inchangé), F-86 (`DocxTextExtractor`) — tous Done.

---

## Préoccupations transversales
- **Contexte tenant** : le nouvel endpoint résout le tenant via `RadarScopeResolver.requireInVigie`
  (`user_id` du JWT + `host_id` du chemin), **exactement** comme les autres controllers réunion
  (`TeamsMeetingController`, `TeamsMeetingMediaController`, `TeamsMeetingTranscriptionController`,
  `TeamsMeetingExploitationController`). Aucun nouveau moyen de résoudre le tenant. Composants qui
  résolvent le tenant réunion : ces 4 controllers + le nouveau — vérifiés, comportement identique.
- **Contenu = donnée** : la transcription externe est du texte fourni par l'utilisateur ; traité en
  donnée, jamais en instruction (l'anti-injection s'applique à l'exploitation en 20b).

---

## Notes et décisions
- Enum `ExternalTranscriptFormat { TEXT, VTT, DOCX }` stocké en `EnumType.STRING` (patron `TranscriptStatus`).
- Le texte externe se lit via un `GET` text/plain dédié (patron du transcript SF-128-04), pas dans le JSON
  de la réunion (cohérence + bornes).
- Format déduit **du contenu/nom** : `DocxTextExtractor.looksLikeDocx` d'abord (docx = zip), sinon
  extension `.vtt` → VTT, défaut → TEXT. Un `.docx` renommé reste détecté par le contenu.
