# Mini-spec — F-147 / SF-147-01 — Transcrire tout de suite, et le montrer

## Identifiant
`F-147 / SF-147-01` — feature parente `F-147`

## Objectif
Qu'un enregistrement déposé soit **transcrit immédiatement**, et qu'on **voie** le travail avancer.

## Le défaut
> *« On ne sait pas quand le runner va venir prendre, de manière asynchrone. Et ensuite une
> synchronisation doit se déclencher. »*

Aujourd'hui, `finish` pose le fichier dans le dépôt, écrit son compagnon, et **s'arrête là**. C'est
un relevé périodique qui le prendra — plus tard, sans qu'on sache quand, et sans rien dire.

Or **le runner sait déjà tout faire** : `TranscriptionWorker.startOrResumeFile` transcrit un fichier
désigné, et `TranscriptionJob` porte ses phases **avec leurs libellés**, écrits pour être lus :
*« j'extrais le son de l'enregistrement — il ne quitte pas cette machine »*, *« je transcris, ici,
sans rien envoyer nulle part »*. Rien de tout cela n'était branché sur le dépôt.

## Comportement attendu
1. À la fin d'un dépôt, le runner **démarre la transcription** sans attendre quoi que ce soit, et
   rend l'**identifiant du travail**.
2. L'écran **suit la progression** : la phase en cours, écrite en toutes lettres.
3. Le travail **survit au dialogue** : fermer l'écran n'interrompt que le **suivi**, jamais le travail —
   il vit sur le poste. Le **retrouver** en rouvrant l'écran suppose de lister les travaux en cours du
   poste : c'est **SF-147-02** qui apporte le bon endroit pour cela (la réunion, dans la liste), et le
   critère est reporté là plutôt que doublé ici.
4. Une transcription **déjà faite** n'est pas refaite (le travail est repris, pas redémarré).
5. **Rien ne change au transport** : le fichier part toujours par morceaux vers le runner, et la
   gateway ne le stocke jamais.

| Cas d'erreur | Comportement |
|---|---|
| `ffmpeg` absent et non rapatriable | phase **échouée**, avec la phrase du runner — pas un silence |
| Fichier muet ou illisible | échec nommé, le fichier reste en place |
| Runner éteint pendant le travail | au retour, l'état est **repris** ; la transcription se recommence, elle ne coûte que du temps |
| Modèle de transcription absent | phase « je m'assure d'avoir le modèle », puis reprise |

## Critères d'acceptation
- [x] `finish` **déclenche** la transcription et rend un identifiant de travail.
- [x] Une route de la gateway rend **la phase en cours** d'un travail, en toutes lettres.
- [x] L'écran affiche cette phase et se met à jour jusqu'à la fin.
- [x] Fermer l'écran **n'interrompt pas** le travail (le suivi seul s'arrête) — *retrouver* le travail
      à la réouverture est reporté à **SF-147-02**.
- [x] Un travail déjà terminé n'est **pas** relancé : le moteur **reprend** (`startOrResumeFile`), et un
      dépôt déjà fini n'est plus connu du receveur — `finish` rejoué répond « dépôt inconnu ».
- [x] Un échec est **dit**, avec la phrase du runner, et le fichier n'est pas perdu.
- [x] Le transport par morceaux est **inchangé** — non-régression vérifiée.

## Hors scope
Le rattachement à un sujet et la création d'une réunion (**SF-147-02**) · le retrait de la
surveillance périodique (**SF-147-05**, une fois que plus rien n'en dépend).

## Technique
| Élément | Changement |
|---|---|
| `RadarDepositReceiver.finish` | déclenche `TranscriptionWorker.startOrResumeFile` et rend le `job_id` |
| `RadarDepositReceiver` | nouvel `op` **`status`** : la phase d'un travail |
| `RadarRecordingDepositService` | relaie le déclenchement et l'état |
| `RadarRecordingController` | `GET /recordings/{id}/status` |
| `radar-deposit-dialog` | affiche la phase, et ne se ferme pas sur le dépôt |

Aucune table, aucune migration. **Contrat runner** : un `op` ajouté, aucun retiré — compatible avec
un runner antérieur, qui se comporte comme aujourd'hui (F-81).

## Plan de test
### Runner — `RadarDepositReceiverTest`, 10 verts
- [x] `finish` déclenche la transcription et rend un identifiant, **sans aucune synchro**.
- [x] `status` rend la phase en toutes lettres ; un dépôt inconnu répond `known=false` — il n'invente rien.
- [x] Un moteur qui **refuse** ne fait pas échouer le dépôt : le fichier est arrivé entier.
- [x] **Sans moteur**, le dépôt se comporte comme avant — et le dit (`transcription=unavailable`).

### Gateway — `RadarRecordingApiIntegrationTest`, 5 verts
- [x] `finish` rend `transcription`, `jobId` et la phrase de phase.
- [x] La route rend la phase, puis la fin ; un dépôt sans travail rend `known=false`.
- [x] **ISOLATION** : le poste d'un autre compte → 404 ; le périmètre vient de `RadarScope`, jamais de la requête.

### Écran — `radar-deposit-dialog.component.spec.ts`, 7 verts
- [x] La phase s'affiche, se met à jour, puis la fin est dite.
- [x] Un poste qui ne sait pas transcrire est **dit**, sans faire croire que c'est en cours.
- [x] Un poste muet : on cesse de demander au bout de trois essais, et on le dit.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | La route de progression passe par le **périmètre Radar** déjà résolu (`RadarScope`), comme le dépôt lui-même : le poste ne vient jamais de la requête. |
| Plans / limites | non | la transcription est **locale** : aucun jeton, aucun appel fournisseur |
| **Navigation / routing** | **oui** *(un écran)* | Seul le dialogue de dépôt change ; aucune route d'écran. |
