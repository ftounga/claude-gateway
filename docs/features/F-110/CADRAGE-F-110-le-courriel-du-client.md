# F-110 — L'application m'envoie des courriels, à l'adresse de chaque client

> Cadrage du 2026-09-13, à la demande du PO. **Cadrage seul : la livraison attend le go du PO.**

## 1. Le besoin

> « Je veux aussi que mon application soit capable de m'envoyer des mails. Il faut la possibilité de
> configurer un mail pour chaque client. Comme ça, quand je demande de m'envoyer des mails, c'est
> directement au mail du client rattaché au poste. »

Un consultant a **une adresse par client** (celle que le client lui a ouverte). Ce qu'il produit pour
un client — un compte rendu, une procédure, un brouillon de relance, une page — doit arriver **dans
cette boîte-là**, où il travaille et d'où il le transmettra lui-même.

## 2. Le principe : on s'écrit à soi, jamais à un tiers

**L'application n'envoie qu'à l'utilisateur lui-même**, à l'adresse qu'il a déclarée et **vérifiée**
pour le client du poste (ou, à défaut, à l'adresse de son compte). Elle n'écrit **jamais** à un
collègue du client, à un tiers, ni à une adresse fournie dans la conversation.

Pourquoi c'est la bonne limite :
- **Pas d'exfiltration ni d'envoi au nom de l'utilisateur** : un modèle qui peut écrire à n'importe
  qui peut être amené à envoyer des données du client dehors. Écrire à sa propre boîte ne sort rien de
  plus que ce que l'utilisateur voit déjà.
- **Cohérent avec le reste** : le Radar prépare les relances, il ne les envoie pas (F-104) ; on ne
  poste pas de message dans Teams (F-108). **Transmettre reste un geste de l'utilisateur**, depuis sa
  boîte.

## 3. L'adresse du client

- Dans l'en-tête du client (Forge et Vigie) : **« Adresse de réception »**, une par poste.
- **Vérification obligatoire** : à la saisie, un code à 6 chiffres est envoyé à cette adresse ; tant
  qu'il n'est pas saisi, rien n'y est envoyé. Sans cela, une faute de frappe enverrait des données du
  client à un inconnu. Changer l'adresse relance la vérification.
- **Repli** : un poste sans adresse vérifiée utilise l'adresse du compte, et **le dit** avant l'envoi
  (« aucune adresse vérifiée pour CAGIP : j'envoie à ntounga@… »).
- Isolation `user_id` + `host_id`.

## 4. Ce que fait l'agent

- **Un outil `email_me`** : objet, corps (Markdown rendu en HTML sobre, avec version texte), pièces
  jointes facultatives. **Aucun champ destinataire** : le destinataire est résolu par la gateway
  depuis le poste du projet ou du terminal.
- **Pièces jointes** : un fichier du poste (lu par le runner), une page de F-109 (fichier HTML joint et
  lien privé), l'export Markdown du Radar. **10 Mo au total** ; au-delà, l'agent propose un lien.
- **Règles de contenu** : pas de secret (mot de passe, jeton, clé) dans un courriel — l'outil refuse
  un corps ou une pièce qui en contient manifestement, et le dit ; la règle des transcriptions
  bloquées s'applique (F-87 §9 bis : résumé oui, transcription brute non).
- **Aucune confirmation par envoi** (on s'écrit à soi), mais **chaque envoi apparaît dans le terminal** :
  « Courriel envoyé à franck.tounga@… — objet — 2 pièces jointes », avec l'état de remise.
- Exemples : « envoie-moi le compte rendu de la réunion », « mets la procédure de déploiement LZI dans
  un mail », « envoie-moi la page du sujet MFA ».

## 5. Le résumé du matin par courriel

Le Radar sait déjà produire un résumé du matin (F-102). **Option par client** dans le réglage de la
synchro (SF-100-07) : **« recevoir le résumé du matin par courriel »**, envoyé après la synchro du soir
à l'adresse vérifiée du client, avec les compteurs et les relances dues, et un lien vers la Vigie.
(Retire de F-99 §15 le hors-périmètre « résumé envoyé par courriel ».)

## 6. La remise, et les boîtes d'entreprise

- **Expéditeur** : l'adresse transactionnelle existante (`MAIL_FROM`, relais SMTP déjà en production),
  **nom affiché** « claude-gateway pour <client> ». SPF, DKIM et DMARC du domaine d'envoi à vérifier au
  déploiement : une boîte bancaire met en quarantaine un courriel mal authentifié.
- **Délais bornés** (règle F-77) et **envoi asynchrone** avec reprise : un relais lent ne bloque pas le
  tour.
- **État de remise** : accepté par le relais, ou refusé avec le motif ; une quarantaine côté client
  n'est pas visible, et l'écran le dit (« vérifiez vos courriers indésirables la première fois »).
- **Limite** : 50 courriels par jour par compte, pour qu'une boucle de l'agent ne remplisse pas une
  boîte.
- **Journal** : date, poste, objet, taille, état — jamais le corps.

## 7. Le droit

Inclus dans la Forge et la Vigie ; le rôle `ADMIN` l'a d'office (F-107 §3 bis). Aucun coût propre
au-delà du quota consommé par le tour.

## 8. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-110-01 | L'adresse de réception d'un client | Colonne ou table par poste (migration au-dessus du dernier numéro sur main), saisie dans l'en-tête du client (Forge et Vigie), code de vérification par courriel, repli sur l'adresse du compte, isolation |
| SF-110-02 | L'outil `email_me` | Destinataire résolu par la gateway, Markdown → HTML sobre + texte, envoi asynchrone avec reprise et délais bornés, refus des secrets, limite quotidienne, journal, bloc « Courriel envoyé » dans le terminal |
| SF-110-03 | Les pièces jointes | Fichier du poste via le runner, page F-109, export du Radar ; plafond 10 Mo ; lien au-delà |
| SF-110-04 | Le résumé du matin par courriel | Option par client dans le réglage de la synchro, envoi après la synchro, gabarit sobre, lien vers la Vigie |

**Ordre** : 01 → 02 → (03 ∥ 04). SF-110-03 dépend de F-109 pour les pages (le reste peut partir sans).

## 9. Préoccupations transversales

- **Sécurité : oui** — destinataire jamais choisi par le modèle, adresse vérifiée, refus des secrets,
  limite d'envoi. Composants : `SmtpEmailService`, catalogue d'outils (`buildTools`), runner (lecture
  des pièces), journal.
- **Contexte tenant : oui** — adresse par `user_id` + `host_id`.
- **Plans / limites : oui** — droit Forge ou Vigie, limite quotidienne.
- **Navigation : non** (réglage dans l'en-tête existant).

## 10. Hors périmètre

- Écrire à un tiers ou répondre à un courriel au nom de l'utilisateur.
- Lire une boîte de réception (Outlook retiré, F-105).
- Un domaine d'envoi propre à chaque client.
