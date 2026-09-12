# La carte du poste

> **Ce fichier et ses voisins sont la carte.** Ils vivent à la **racine du poste**, à côté des
> dossiers de projets. Les projets sont des **dossiers**, la carte des **fichiers** : ils ne se
> confondent jamais.
>
> **Le travail est jetable, le savoir est durable.** Un projet meurt avec son sujet ; ce qu'il a fait
> apparaître de durable se range ici, et sert au projet suivant. **À chaque projet qu'on ajoute, la
> connaissance de l'infrastructure augmente.**

## Comment on écrit ici

**N'y mettre que des faits, datés, avec leur source.** Un fait porte donc trois choses, sans
exception :

| Ce qu'il faut | Exemple de ce qui se note | Exemple de ce qui ne se note pas |
|---|---|---|
| **le fait**, vérifiable | « le bastion n'accepte que les clés `ed25519` » | « l'accès est compliqué » |
| **la date**, sous la forme `constaté le AAAA-MM-JJ` | « constaté le 2026-03-14 » | rien |
| **la source** : qui l'a dit, ou ce qui l'a montré | « courriel de l'équipe réseau » / « erreur du client SSH » | « il paraît que » |

Trois règles de plus, et elles comptent autant :

1. **Pas de duplication.** Quand la source de vérité existe ailleurs — un wiki, un dépôt, un ticket —
   on **pointe** vers elle. Une copie devient fausse sans prévenir, et sans qu'on l'apprenne.
2. **Pas de secret.** Aucun mot de passe, aucune clé, aucun jeton. On note **où** le secret se
   trouve et **qui** l'accorde, jamais sa valeur.
3. **Pas de supposition.** Ce qui n'a pas été vérifié se note comme non vérifié, ou ne se note pas.

## Le poste en une phrase

_Chez qui, pour quoi faire, depuis quand._

## Les grands domaines

_Une ligne par domaine. Le fichier reste le lieu du détail ; ce tableau sert à savoir où chercher._

| Domaine | Fichier | Ce qu'il porte | Dernier fait ajouté |
|---|---|---|---|
| Accès | `acces.md` | VPN, bastions, forges, droits, pièges | |
| Réseau | `reseau.md` | plages, DNS, domaines, flux, certificats | |
| Plateformes | `plateformes.md` | clusters, serveurs, hébergements, stockage | |
| Données | `donnees.md` | bases, schémas, sauvegardes, restaurations | |
| Exploitation | `exploitation.md` | supervision, alertes, astreinte, procédures | |

## Annuaire des projets

_Un projet = un dossier de cette racine. On l'inscrit ici quand il s'ouvre, et on écrit ce qu'il a
appris à la carte quand il se ferme._

| Projet (dossier) | Sujet en une phrase | Ouvert le | État |
|---|---|---|---|
| | | | |

## Contacts

_Qui accorde quoi, par quel canal, et sous quelle forme la demande doit arriver. **C'est la section
qui fait gagner le plus de temps** : sans elle, chaque demande recommence par « à qui je m'adresse
pour ça ? »._

| Rôle / équipe | Ce qu'ils accordent ou détiennent | Comment les joindre | Convention d'échange (formulaire, ticket, objet imposé, délai) | Source et date |
|---|---|---|---|---|
| | | | | |

## Conventions du poste

_Ce que le client impose et qu'aucune documentation ne dit : nommage, fenêtres d'intervention,
langue des tickets, format des demandes, personnes à mettre en copie._

| Convention | Ce qu'elle impose | Source et date |
|---|---|---|
| | | |

## Ce qui reste à cartographier

_Les trous connus de la carte. Une case cochée y disparaît ; une case qui reste dit ce qu'on ne sait
pas encore, et c'est déjà un savoir._

- [ ]
