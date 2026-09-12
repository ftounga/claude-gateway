# Accès — comment joindre chaque environnement

> **Faits uniquement, datés, avec leur source.** Voir la règle d'écriture en tête de `README.md`.
> **Aucun secret ici** : on note **où** il vit et **qui** l'accorde, jamais sa valeur.

## Récapitulatif VPN

_Un client VPN par ligne. La colonne « incompatible avec » est celle qu'on relit le jour où deux
tunnels refusent de cohabiter — et ce jour arrive._

| VPN | Client / version | Ce qu'il ouvre | Qui accorde l'accès | Authentification (où vit le secret) | Incompatible avec | Source et date |
|---|---|---|---|---|---|---|
| | | | | | | |

## Bastions et rebonds

| Bastion | Adresse | Ce qu'il dessert | Type de clé accepté | Durée de session | Source et date |
|---|---|---|---|---|---|
| | | | | | |

## Forges et dépôts

| Forge | URL | Ce qu'elle héberge | Mode d'authentification | Qui accorde les droits | Source et date |
|---|---|---|---|---|---|
| | | | | | |

## Comptes et droits

_Qui a quoi, et **par quel geste** on l'obtient. Le délai d'obtention est un fait : il se note._

| Système | Type de compte | Droits portés | Geste pour l'obtenir | Délai constaté | Source et date |
|---|---|---|---|---|---|
| | | | | | |

## Les pièges

**La section la plus précieuse de la carte.** Un piège est un fait que rien ne documente et qu'on
paie deux fois si on ne l'écrit pas. Exemples de ce qui s'écrit ici :

- « le client VPN X coupe la résolution DNS de Y tant qu'il est monté » ;
- « le tunnel est supervisé au volume : prévenir l'équipe réseau avant un transfert de plusieurs
  Go » ;
- « la session du bastion tombe au bout de 15 minutes d'inactivité, sans message » ;
- « le proxy d'entreprise exige une authentification intégrée que la JVM ne porte pas ».

| Piège | Ce qu'il provoque | Contournement | Constaté le | Source |
|---|---|---|---|---|
| | | | | |

## Ce qui reste à vérifier

- [ ]
