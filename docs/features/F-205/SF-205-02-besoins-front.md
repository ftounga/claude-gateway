# Mini-spec — F-205 / SF-205-02 — Registre des besoins : carte du fil et section Besoins

## Identifiant
`F-205 / SF-205-02` — feature parente `F-205` *Registre des besoins*. Dépend de SF-205-01 (mergée, #1200).
Branche : `feat/SF-205-02-besoins-front`. Statut : `in-progress`. Date : 2026-10-16.
**Contrat importé de SF-205-01** (`SF-205-01-registre-besoins-backend.md` §Contrat API).

## Objectif
Voir dans le fil la carte « Enregistrer ce besoin ? » posée par l'agent et la décider d'un clic ; relire,
filtrer et faire avancer les besoins enregistrés dans l'administration, pièces comprises.

## Comportement attendu

### Cas nominal
1. **Carte du fil** (`app-besoin-card`) — reçue au fil de l'eau (événement SSE `besoin`) ou relue dans la
   transcription (`block.besoin`) : type (Bug / Évolution), titre, mots de l'utilisateur, reformulation,
   contexte, pièces (nom, taille). Statut relu (`GET …/app-requests/{id}`) :
   - `PROPOSE` → [Enregistrer] (`POST …/record`) et [Non] (`POST …/dismiss`) ;
   - `EN_ATTENTE`/`CADRE`/`LIVRE` → « Enregistré au registre des besoins » ;
   - `REJETE` → « Écarté — rien n'a été enregistré. »
   Lecture seule (mosaïque) : pas de bouton.
2. **Section Besoins** de l'administration (`app-admin-besoins`, après le Diagnostic) :
   - filtre par statut (`EN_ATTENTE` par défaut ; Tous, Cadrés, Rejetés, Livrés) → `GET /api/admin/app-requests?status=` ;
   - liste : type, titre, date, statut, feature ; un clic déplie le détail (mots, reformulation, contexte) ;
   - pièces : aperçu des images (blob lu par `HttpClient`, URL d'objet), lien d'ouverture / téléchargement
     pour les autres ; état de copie (`En copie…`, `Copie échouée : motif`) ;
   - changement de statut (`mat-select`) et référence de feature (`F-123`) → `PATCH`, confirmation par
     `MatSnackBar`.

### Cas d'erreur
| Situation | Comportement |
|---|---|
| 409 sur [Enregistrer]/[Non] (déjà décidé ailleurs) | message + statut relu |
| Statut non relisible (terminal hors ligne, ancien backend) | carte considérée `PROPOSE` |
| Liste admin en échec | snackbar « Impossible de charger les besoins. » |
| `PATCH` refusé (400) | snackbar avec le message serveur |
| Pièce non copiée | pas d'aperçu, état de copie affiché |

## Contraintes de validation
| Élément | Règle |
|---|---|
| Référence de feature | `^F-\d{1,4}$` ou vide (contrôlée aussi côté serveur) |
| Aperçu | images seulement (`image/*`), chargé à l'ouverture du détail |

## Critères d'acceptation
- [ ] La carte apparaît pendant le tour et après rechargement ; [Enregistrer] la passe « Enregistré ».
- [ ] [Non] l'écarte ; un double clic concurrent n'enregistre pas deux fois (409 relu).
- [ ] La section Besoins liste les besoins en attente avec leurs pièces ; l'aperçu d'une capture s'affiche.
- [ ] Changer le statut en « Cadré » avec `F-210` est persisté et confirmé.
- [ ] Couleurs et polices du design system uniquement (tokens `--cg-*`).

## Plan de test
- **Front (Karma)** : `besoin-card.component.spec.ts` (rendu, statut relu, [Enregistrer]/[Non], 409, lecture
  seule, `besoinBlock`), `admin-besoins.component.spec.ts` (chargement filtré, filtre, détail + aperçu,
  `PATCH`, erreurs), `atelier.service` (événement `besoin` → `onAppRequest`).
- **Isolation** : portée par le backend (SF-205-01) ; le front n'envoie jamais d'identifiant d'utilisateur.

## Impacts
- **Composants** : `core/models/atelier.models.ts`, `core/services/atelier.service.ts`,
  `core/services/app-request.service.ts` (nouveau), `atelier/terminal/besoin-card.component.ts` (nouveau),
  `atelier-terminal.component.{ts,html}`, `atelier.component.ts`, `admin/besoins/*` (nouveau),
  `admin.component.{ts,html}`.
- **Endpoints consommés** : ceux de SF-205-01. Aucun nouvel endpoint.

### Préoccupations transversales
- **Navigation / routing** : non (section dans l'écran `/admin` existant, même garde).
- Auth / tenant / plans : non.

## Hors périmètre
Ouverture aux non-admins ; édition du texte d'un besoin ; relance d'une copie échouée.

## Arbitrages (réversibles)
- L'écran d'administration est fait de **sections**, pas d'onglets : « onglet Besoins » du cadrage =
  section **Besoins** placée après le Diagnostic (même thème : ce que l'application doit corriger).
- Statut `PROPOSE` non proposé dans le filtre admin (une carte non décidée vit dans le fil).
