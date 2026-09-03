# Mini-spec — F-08 / SF-08-03 — Un dépôt de document qui se comprend et se diagnostique

## Identifiant
`F-08 / SF-08-03` · Statut `done` · 2026-09-04 · `fix/SF-08-03-upload-lisible`

## Déclencheur

Un utilisateur découvrant l'outil rapporte n'avoir pas réussi à déposer un PDF, **sans aucun message
d'erreur**. Vérification faite en production : aucun document, aucun fichier, aucune trace serveur
pour son compte. Rien n'était donc parti — et rien n'expliquait pourquoi.

## Les trois causes traitées

1. **L'écran demandait deux gestes.** Choisir un fichier, puis cliquer « Lancer l'OCR ». Qui choisit
   son fichier et attend ne déclenche rien — donc ne voit aucune erreur non plus, ce qui est le
   comportement le plus déroutant possible.
2. **Les refus étaient invisibles côté serveur.** Type non supporté et fichier trop volumineux
   étaient journalisés en `debug` ; en production (`info`), ils ne laissaient aucune trace. Un « ça
   ne marche pas » se diagnostiquait alors à l'aveugle, en interrogeant la base.
3. **Les messages ne disaient pas la règle.** « Type de document non supporté » sans dire lesquels le
   sont — alors qu'un PDF déclaré `application/octet-stream` par le navigateur tombe précisément ici.

## Ce qui est livré

- **Un seul geste** : choisir un fichier lance l'extraction. Le champ est vidé ensuite, pour que
  re-choisir le même fichier redéclenche bien l'envoi.
- L'écran annonce **la règle avant l'échec** : « PDF, PNG, JPEG ou TIFF — 20 Mo au maximum », et
  affiche la progression pendant l'envoi.
- Les refus sont journalisés en **`info`** avec le type et la taille — jamais le nom du fichier, qui
  est une donnée personnelle.
- Les messages nomment **le type reçu** et **le plafond réel** : « Format refusé
  (« application/octet-stream »). Formats acceptés : … » et « Document trop volumineux : 34 Mo,
  maximum 20 Mo. » Le frontend affiche le message du serveur quand il existe.
- Une session expirée (401) est dite comme telle, plutôt que fondue dans un échec générique.

## Les trois plafonds, désormais explicites

Ce n'était pas une incohérence mais trois bornes distinctes, dont aucune n'était posée dans la
configuration — donc impossible de savoir laquelle s'appliquait :

| Plafond | Valeur | Ce qu'il borne |
|---|---|---|
| `APP_UPLOAD_MAX_FILE_SIZE` | 150 Mo | l'enveloppe multipart, commandée par l'import ZIP de l'Atelier (F-28) |
| `APP_UPLOAD_MAX_SIZE` | 32 Mo | une pièce jointe de conversation (F-04) |
| `APP_OCR_MAX_SIZE` | **20 Mo** | un document soumis à l'OCR — **le plus bas, donc celui qu'on rencontre** |

## Hors périmètre
Accepter un PDF mal typé par le navigateur en reniflant ses octets d'en-tête (`%PDF`) : ce serait
utile, mais c'est un changement de règle de validation, pas un correctif d'affichage. À arbitrer.

## Tests
Backend 1124 ; frontend 550 (+2 : envoi sans second clic, message serveur affiché).
