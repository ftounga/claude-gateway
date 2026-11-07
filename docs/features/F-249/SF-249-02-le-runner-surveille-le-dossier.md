# Mini-spec — F-249 / SF-249-02 Le runner surveille le dossier des mails

## Identifiant
`F-249 / SF-249-02`

## Feature parente
`F-249` — Les mails et l'agenda Outlook de vos clients (cadrage D1, D2, D3, D4, D20)

## Statut
`in-progress`

## Date de création
2026-11-07

## Branche Git
`feat/SF-249-02-runner-surveille`

## Objectif
Le runner d'un poste client surveille `<racine>/jarvis/mails/`, envoie chaque `.msg`/`.eml` déposé une seule fois à la
passerelle, en arrière-plan et sans jamais gêner un tour, puis le range.

## Comportement attendu

### Cas nominal
1. Au démarrage (session appairée), le runner crée `<racine>/jarvis/mails/` et un `LISEZ-MOI.txt` (ce qu'il faut y
   glisser, ce que devient un fichier), et dit une ligne en console : « Mails : glissez vos mails Outlook dans … ».
2. Un fil **démon de priorité minimale** passe **30 s** après le démarrage, puis **toutes les 5 min**.
3. Un passage est **sauté** si le runner a une activité en cours (`RunnerActivity.busy()` : commande, capture,
   synchro, téléchargement) ; il **s'interrompt** entre deux fichiers dès qu'une activité apparaît.
4. Candidats : fichiers réguliers du premier niveau, `.msg`/`.eml` (casse ignorée), non cachés, non vides,
   **non modifiés depuis 1 min** (copie finie), du plus ancien au plus récent, **20 au plus** par passage.
5. Envoi : `POST {gateway}/runner/mail-deposit`, corps brut, en-têtes `X-Runner-Token`, `X-File-Name`, client HTTP du
   runner (proxy et racine d'entreprise, F-190).
6. Réponse `200` ⇒ le fichier est rangé dans `envoyes/AAAA-MM/` (nom gardé, suffixe ` (2)` en cas de collision).
   `400`/`413`/`415`/`422` ⇒ rangé dans `refuses/`. `401`, `5xx`, erreur réseau ⇒ laissé en place, le passage s'arrête,
   le suivant reprendra (curseur = le rangement).
7. Si le rangement échoue (fichier verrouillé par Outlook), l'empreinte SHA-256 du fichier envoyé est notée dans
   `jarvis/mails/.envoyes` (500 dernières) : il n'est jamais renvoyé ; le rangement est retenté au passage suivant.
8. Un fichier de plus de 20 Mo n'est pas envoyé : rangé dans `refuses/`.
9. Le runner annonce la capacité **`mail_deposit`** (trame `ready`) : la passerelle sait que le poste est à jour.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Racine non inscriptible | le dossier n'est pas créé, une ligne le dit, le runner continue |
| Fichier en cours de copie (modifié < 1 min) | ignoré ce passage |
| Passerelle ancienne (404) | laissé en place, passage arrêté |
| Jeton refusé (401) | laissé en place ; le passage s'arrête |
| Fichier verrouillé au rangement | empreinte notée, jamais renvoyé |
| Une commande du PO arrive pendant un passage | passage interrompu avant le fichier suivant |

## Contraintes de validation
| Champ | Règle |
|---|---|
| extension | `.msg`, `.eml` |
| taille | 1 octet à 20 Mo |
| stabilité | dernière modification ≥ 60 s |
| lot | 20 fichiers par passage |
| `.envoyes` | 500 empreintes au plus |

## Critères d'acceptation
- [ ] Trois fichiers déposés ⇒ trois envois puis trois rangements dans `envoyes/AAAA-MM/` ; un passage suivant n'envoie
      rien.
- [ ] Un fichier verrouillé (rangement impossible) n'est jamais renvoyé.
- [ ] `415` ⇒ `refuses/` ; `503` ⇒ laissé en place, passage arrêté.
- [ ] Runner occupé ⇒ aucun envoi ; activité apparue en cours de passage ⇒ arrêt avant le fichier suivant.
- [ ] Fichier récent (< 1 min), caché, vide ou d'une autre extension ⇒ ignoré.
- [ ] La capacité `mail_deposit` est annoncée.
- [ ] La vraie requête HTTP porte le jeton, le nom et le corps exact (serveur HTTP local).

## Plan de test
- **Unitaires** `MailDepositWatcherTest` (dossier temporaire, envoi bouchonné, horloge fixe) : nominal, curseur,
  verrou, codes de refus et de report, occupation, filtres, lot de 20, gros fichier, `LISEZ-MOI`.
- **Intégration** `MailDepositWatcherHttpTest` : `com.sun.net.httpserver.HttpServer` local, en-têtes et corps reçus.
- `ToolRouter` : capacité annoncée.
- Isolation : sans objet côté runner (l'identité vient du jeton, vérifiée par la passerelle — SF-249-01).
- Suite complète du module runner à la review.

## Impact
- **Runner** : `runner/mail/MailDepositWatcher` (nouveau), `RunnerConfig.mailDepositUrl()`, `RunnerMain` (démarrage
  du fil), `ToolRouter` (capacité). **Les postes devront être mis à jour.**
- Aucune table, aucune route nouvelle (route livrée en SF-249-01).

## Préoccupations transversales
- Auth : le jeton du runner est lu à chaque envoi (une référence mise à jour au réappairage) ; aucune nouvelle forme
  d'authentification. Composants : `RunnerMain` (référence du jeton). Aucun autre.
- Tenant : sans objet côté runner.

## Hors périmètre
Le traitement côté passerelle (SF-249-01, 03-06), les écrans (SF-249-07/08).
