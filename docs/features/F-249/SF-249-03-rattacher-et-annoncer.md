# Mini-spec — F-249 / SF-249-03 Rattacher aux sujets, annoncer, récap du matin

## Identifiant
`F-249 / SF-249-03`

## Feature parente
`F-249` — Les mails et l'agenda Outlook de vos clients (cadrage D9, D10, D11)

## Statut
`done` (2026-11-07)

## Date de création
2026-11-07

## Branche Git
`feat/SF-249-03-sujets-annonces`

## Objectif
Chaque mail déposé est rattaché, sans IA, aux sujets en cours de son client, et l'application l'annonce par une
notification d'interface (jamais un message dans le fil d'un sujet), avec un récapitulatif chaque matin.

## Comportement attendu

### Cas nominal
1. **Rattachement** (dans le travail `MAIL_DEPOSIT`, après le tri) d'un courrier déposé **non « à ignorer »**, parmi
   les sujets **du poste du dépôt** (hors terminal de poste, terminal Teams, fil Jarvis, sujet de relevé), 3 au plus :
   1. **même fil** : un courrier déposé de ce client dont le `Message-ID` est le `thread_ref` de celui-ci, ou de même
      objet normalisé (préfixes `RE:`/`TR:`/`FW:` retirés, casse et accents ignorés) reçu sous 30 jours, transmet ses
      sujets (raison `FIL`) ;
   2. **clé de ticket** (`ABC-123`) de l'objet ou de l'extrait, présente dans le **nom** du sujet ou dans une clé de
      fait F-247 du sujet (`jarvis_state_facts.object_key`) (raison `TICKET`) ;
   3. **mots du nom** : mots distinctifs du nom du sujet (≥ 4 lettres hors mots vides, ou sigle en capitales de 2 à
      5 lettres) — au moins la moitié présents dans l'**objet**, ou tous dans objet + extrait (raison `MOTS`).
   Liens écrits dans `jarvis_mail_subject_links` (un par courrier et sujet).
2. **Annonce** : pour chaque sujet rattaché, une annonce `MAILS_ON_SUBJECT` dans `jarvis_notices` (clé
   `mails:<sujet>`) qui **cumule** tant qu'elle n'est pas vue : texte « 2 mails sur « Alarme Fortec » (EDENRED) »,
   phrase dite « Deux nouveaux mails sur « Alarme Fortec », chez EDENRED. », lien `/atelier/<id>`, échéance 3 jours.
   Une annonce vue puis un nouveau mail ⇒ elle repart à 1. Push F-185 `CLIENT_MAILS_ON_SUBJECT` (titre et corps
   neutres, lien `/atelier/<id>`, cloche).
3. **Récap du matin** (7 h 30, Paris) : si des courriers ont été acceptés depuis le récap précédent (fenêtre de 24 h),
   une annonce `MORNING_RECAP` (clé `recap:<date>`) : « Hier : 14 mails déposés (EDENRED 9, CAGIP 5), dont 3 sur vos
   sujets : « Alarme Fortec » (2), « SSO » (1). » ; échéance à minuit ; pas de push.
4. **Routes** (compte authentifié) : `GET /api/jarvis/notices` (non vues, non échues, 20 au plus, plus récentes
   d'abord ; une annonce dont le sujet n'existe plus est fermée) ; `POST /api/jarvis/notices/{id}/seen` ;
   `POST /api/jarvis/notices/{id}/spoken`.
5. **Jamais** de message ni de tour dans le fil d'un sujet : aucune écriture dans `messages` ni dans la file des tours.
6. L'outil `courriels` du cerveau rapide et la lecture d'Opus disent « client EDENRED — sujets : « Alarme Fortec » »
   pour un courrier déposé (donnée dans le résultat ou le bloc du message, jamais dans une consigne).
7. Coupe-circuit `APP_JARVIS_NOTICES_ENABLED` (aucune annonce créée, la route rend une liste vide).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Courrier « à ignorer » | ni rattaché ni annoncé | — |
| Aucun sujet ne correspond | aucun lien, aucune annonce | — |
| Sujet supprimé depuis | l'annonce est fermée à la lecture | — |
| Annonce d'un autre compte (vue, dite) | introuvable | 404 |
| Deux pods au récap | une seule annonce (clé unique) | — |

## Contraintes de validation
| Champ | Règle |
|---|---|
| sujets par courrier | 3 au plus |
| texte d'annonce | ≤ 300 caractères (nom de sujet tronqué à 60) |
| annonces rendues | 20 au plus |

## Critères d'acceptation
- [ ] Un mail « RE: Alarme Fortec » déposé chez EDENRED est rattaché au sujet « Alarme Fortec » d'EDENRED, jamais au
      sujet homonyme d'un autre client ni d'un autre compte.
- [ ] Une réponse dans le même fil hérite du rattachement, même si l'objet ne dit plus le nom.
- [ ] Une clé `FORT-42` reliée au sujet par un fait F-247 rattache le mail.
- [ ] Deux mails ⇒ une seule annonce « 2 mails sur « Alarme Fortec » (EDENRED) » ; vue puis un 3e ⇒ « 1 mail ».
- [ ] Aucune ligne `messages` n'est créée pour le sujet.
- [ ] Récap du matin avec les comptes par client et par sujet ; aucun récap sans dépôt.
- [ ] **Isolation** : B ne voit ni ne ferme les annonces de A (404) ; le rattachement ne regarde que les sujets du
      compte et du poste.

## Plan de test
- **Unitaires** `ClientMailSubjectLinkerTest` (mots, sigles, mots vides, accents, clé de ticket, objet normalisé),
  `JarvisNoticeTextTest` (textes, nombres en lettres, tronquage).
- **Intégration** `ClientMailNoticesIntegrationTest` : dépôt ⇒ travail ⇒ lien + annonce cumulée ; fil hérité ; fait
  F-247 ; « à ignorer » ; aucune ligne `messages` ; récap ; routes vue/dite ; **isolation A/B**.
- Suite complète backend à la review.

## Impact
- **Tables** : `jarvis_mail_subject_links`, `jarvis_notices` (migration `249-03-jarvis-notices.xml`, réversible).
- **Endpoints** : `GET /api/jarvis/notices`, `POST /api/jarvis/notices/{id}/seen|spoken`.
- **Backend** : `jarvis/clientmail/ClientMailSubjectLinker`, `ClientMailRecap`, `jarvis/notices/*`,
  `ClientMailDepositJobHandler`, `JarvisMailItemService.ItemView` (+ client, sujets), `PushEvent`
  (`CLIENT_MAILS_ON_SUBJECT`), `application.yml`.
- **Consignes** : aucune ne change.

## Préoccupations transversales
- **Contexte tenant** : le rattachement lit `workspaces` par (`user_id`, `host_id`) et `jarvis_state_facts` par
  (`user_id`, `host_id`) ; les routes filtrent par le `user_id` du principal (`CurrentUser`). Composants :
  `ClientMailSubjectLinker`, `JarvisNoticeService`, `JarvisNoticeController`.
- Navigation : le lien d'annonce `/atelier/<id>` existe déjà (route du terminal) ; aucune route ajoutée côté écran ici.

## Hors périmètre
L'affichage (SF-249-08), le rappel du soir (SF-249-05), les réunions (SF-249-04), les règles « ignorer » (SF-249-06).
