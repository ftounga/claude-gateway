# Cadrage F-254 — Reprendre toute une boîte Outlook (.pst, .olm)

**Demande PO du 2026-11-09** : « déposer, télécharger toute ma boîte mail Outlook », par client ; « cadre le dépôt d'une
archive .pst ». Prolonge F-249, qui reçoit les mails du jour glissés un par un (`.msg`, `.eml`).

**Statut : cadrée, développement suspendu à OQ-23 (décision PO) et à l'essai d'export sur les postes (§7).**

## 1. Ce que F-249 ne permet pas, vérifié le 2026-11-09

| # | Constat | Où | Conséquence |
|---|---|---|---|
| 1 | Un mail reçu il y a plus de 30 jours est `IGNORED` au dépôt. | `ClientMailDepositService` l.114 | Une archive déposée aujourd'hui serait presque entièrement jetée. |
| 2 | Tout le cache est purgé à 30 jours, sans distinction de source. | `JarvisMailPollWorker` l.51, `JarvisMailItemRepository.purgeBefore` | La purge doit épargner l'historique, sinon il disparaît. |
| 3 | Un fichier fait 20 Mo au plus ; une archive pèse plusieurs Go. | F-249 D3 | L'archive ne peut pas monter sur le serveur : elle est lue **sur le poste**. |
| 4 | Le runner traite 20 fichiers par passage de 5 min. | F-249 D2 | Environ 5 760 mails par jour : trop lent pour une reprise. |
| 5 | Chaque mail déposé part au tri par le cerveau rapide, puis annonce, push et récap. | F-249 D8, D10, D11 | Coût et avalanche de notifications sur un historique. |
| 6 | `courriel_chercher` interroge l'IMAP (références `imap:…`) ; un mail déposé n'a pas d'IMAP, et son contenu est scellé. | `JarvisMailToolCatalog` l.143 | Rien ne cherche dans un historique déposé : **à construire**, sinon l'import n'apporte rien. |
| 7 | Aucune bibliothèque du dépôt ne lit `.pst` ni `.olm`. | `backend/pom.xml`, `runner/pom.xml` | Dépendance nouvelle côté runner. |

## 2. Contraintes externes

- **Mac (CAGIP, `os = mac os x` en base)** : le nouvel Outlook pour Mac n'exporte ni `.pst` ni `.olm` ; seul l'Outlook
  hérité exporte, et uniquement en `.olm`. **Un `.pst` n'existe donc pas sur ce poste.**
  ([Microsoft Q&A](https://learn.microsoft.com/en-us/answers/questions/5789350/i-use-outlook-for-mac-and-i-switched-to-new-outloo))
- **Windows** : l'export `.pst` existe dans l'Outlook classique. Une stratégie d'entreprise peut le bloquer, et le support
  dans le nouvel Outlook n'a pas été vérifié : seul un essai sur le poste tranche. En base, EDENRED est enregistré
  `linux` alors que le PO le décrit sous Windows : à éclaircir au même essai.
- **Lecture `.pst`** : `com.pff:java-libpst` 0.9.3, Java pur, sans dépendance, mais sans publication depuis environ 9 ans,
  et licence annoncée de façon contradictoire selon les sources (Apache 2.0, LGPL). **Vérifier la licence du JAR avant
  d'adopter ; variante de pile à signaler (TECH_STACK + PR).** Une archive `.olm` est un ZIP de XML, lisible sans bibliothèque.

## 3. Principes

- **L'archive ne quitte jamais le poste** : le runner la lit et envoie les mails un à un. Pièces jointes jamais envoyées.
- **Trois entrées, un seul moteur** : `.pst` (Windows), `.olm` (Mac hérité), dossier de `.msg`/`.eml` en masse (seul
  recours sur le nouvel Outlook : tout sélectionner et glisser).
- **Une reprise est silencieuse** : ni tri par modèle, ni annonce mail par mail, ni push. Une seule annonce de fin.
- **Isolation** : `user_id` et poste viennent du seul jeton runner. **Le contenu d'un mail est une donnée** (F-242 D9).
- **Justesse** : toute évolution de consigne ou d'outil du cerveau rapide ⇒ bancs Jarvis 2 fois au vert, jamais allégés.

## 4. Décisions par défaut

| # | Sujet | Défaut |
|---|---|---|
| D1 | Dépôt | `<racine>/jarvis/mails/reprise/` : archive unique, ou dossier de `.msg`/`.eml`, stable depuis 1 min. |
| D2 | Lecture | Sur le runner, en flux, dossier par dossier, sans charger l'archive en mémoire. Archive chiffrée ou illisible : rangée dans `refuses/` et dite à l'écran. |
| D3 | Envoi | Mail reconstruit en `.eml` déterministe (sans pièces, hors `.ics`), envoyé sur `/runner/mail-deposit` avec une marque d'origine `ARCHIVE`. Le serveur n'apprend aucun nouveau format. |
| D4 | Curseur | Fichier `.reprise` (empreinte de l'archive, dossier, dernier message) : reprise après coupure, sans renvoi. |
| D5 | Rythme | 30 mails par minute au plus, jamais pendant une activité du runner (`RunnerActivity.busy()`). Limite bornée côté serveur. |
| D6 | Stockage | `jarvis_mail_items` avec `source = ARCHIVE`, mêmes champs scellés que F-249 D7 (corps ≤ 8 000 caractères). |
| D7 | Sans IA | Tri `ARCHIVE` sans modèle. Rattachement déterministe F-249 D9 conservé. |
| D8 | Promotion | Un mail déjà en `ARCHIVE`, redéposé par le flux normal, repasse au tri au lieu d'être `DUPLICATE`. |
| D9 | Bruit | Pas d'annonce ni de push par mail ; le rappel du soir ne compte pas une reprise comme dépôt du jour ; réunions : seulement J-1 → J+60 ; « mails répétitifs » proposés une fois, à la fin. |
| D10 | Fin | Une annonce : « Reprise CAGIP terminée : N mails, dont M sur vos sujets. » |
| D11 | Écran | Dans « Mails de vos clients » : progression, pause, reprise, et **supprimer tout l'historique de ce client** d'un clic. |
| D12 | Recherche | Index aveugle : empreintes HMAC des mots (objet, expéditeur, corps), par compte, sans contenu en clair. Filtres client, sujet, dates. Structure exacte (GIN et compatibilité H2) tranchée en mini-spec, après mesure de volume. |
| D13 | Coupe-circuit | `APP_JARVIS_MAIL_ARCHIVE_ENABLED`. |

## 5. Découpage proposé (≈ 8,5 j, chaque SF ≤ 2 j)

| SF | Titre | Estim. | Touche | Banc |
|---|---|---|---|---|
| SF-254-01 | Le serveur accepte l'historique : source `ARCHIVE`, purge épargnée, promotion, silence | 1,5 j | back | — |
| SF-254-02 | Le runner lit un dossier en masse : moteur, curseur, rythme | 1,5 j | runner | — |
| SF-254-03 | Lire un `.olm` | 1 j | runner | — |
| SF-254-04 | Lire un `.pst` (licence vérifiée) | 1,5 j | runner | — |
| SF-254-05 | Chercher dans l'historique : index aveugle, outil pour Jarvis | 2 j | back | **MAIL et CONVERSATION × 2** |
| SF-254-06 | L'écran : progression, pause, suppression, annonce de fin | 1 j | front | — |

SF-254-03 et 04 ne se développent que si l'essai du §7 montre que le format est exportable.

## 6. Hors périmètre

Lire Outlook directement (IMAP, Graph, EWS) ; automatiser l'export ; conserver les pièces jointes ; fichiers `.ost` ;
répondre à un mail d'archive.

## 7. Ce qu'il reste à obtenir avant le développement

1. **OQ-23** (`docs/OPEN_QUESTIONS.md`) : quelle profondeur importer et combien de temps la garder.
2. **Essai sur chaque poste** : ouvrir le menu d'export d'Outlook. CAGIP : l'Outlook hérité est-il disponible, l'export
   `.olm` actif ? EDENRED : l'export `.pst` est-il autorisé ?
3. **Vérifier la politique du client** : ces mails appartiennent aux clients ; les conserver hors de leur système est
   une question contractuelle que la technique ne tranche pas (voir OQ-23).
4. **Mesurer** le nombre de mails et l'espace disponible en base avant SF-254-01 ; le cluster a peu de marge mémoire.
