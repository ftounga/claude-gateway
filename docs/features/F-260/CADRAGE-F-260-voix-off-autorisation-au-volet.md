# Cadrage court — F-260 L'autorisation arrive dans le volet de droite

**Origine** : besoin BUG enregistré par le PO le 2026-11-10 (`app_requests` `8c14bd32-…`) : « `voix_off` renvoie
systématiquement *Voix off refusée : aucune autorisation n'a été donnée dans le délai imparti* ».

## Constat prouvé (prod, 2026-11-10, sujet data-platform)

| Source | Fait |
|---|---|
| `atelier_messages` | Les **4** tours refusés (08:42, 08:45, 08:50, 08:53) sont **tous** dans le fil du **chantier 6** ; un autre volet (chantier 9 puis 7) était ouvert à côté. |
| CloudWatch (backend) | 4 × « Autorisation demandée … sous 120000 ms » puis, 120 s pile après, « Aucune décision … : commande refusée ». Aucune décision reçue, sur aucun pod. |
| `runner_audit` | 5ᵉ essai à 09:00:22 (« reessaie ») : `voix_off` **OK** (mp3 + mots déposés à 09:00:48). |
| Message PO 09:02 | « la carte de validation de la voix off ne se présentait pas quand j'avais les 2 volets ouverts. Maintenant que j'ai fermé l'autre volet et retenté ça a marché. » |

## Cause

Le chantier 6 était dans le **volet de droite** (`TerminalLanePaneComponent`). Ce volet ne reçoit la demande
d'autorisation que par son **flux SSE** (`chat/stream`, puis `chat/attach`). Derrière le proxy d'entreprise du
poste client, qui **retient** un corps `text/event-stream` jusqu'à sa fin (constaté F-84 / SF-84-04, Netskope), rien
n'arrive avant la fin du tour — donc la carte n'apparaît jamais pendant les 120 s, et le silence vaut refus.
L'écran principal (volet de gauche), lui, a depuis F-84 une **sonde** de 4 s et un **suivi par fenêtres** qui
traverse le proxy : fermer l'autre volet a ramené le chantier 6 dans l'écran principal, d'où le succès du 5ᵉ essai.
SF-226-04 avait déjà noté ce manque (« le direct complet du volet derrière un proxy reste un sujet à part ») et ne
l'avait réglé qu'à la **réouverture** du volet.

Ce n'est pas propre à `voix_off` : toute autorisation (bash, page, vidéo…) et toute question (`demander`) posée en
cours de tour dans le volet de droite est touchée. Les épisodes 1-8 et 10 (25/10) précèdent l'écran partagé (F-221,
29/10).

## Décision

Donner au volet de droite **le même repli que l'écran principal** : sonde de 4 s après l'envoi et après le
rebranchement ; sans nouvelle du flux, suivi par fenêtres (`followTurnInWindows`, fil du chantier) ; chaque événement
appliqué une seule fois (curseur `acceptSeq`). Aucun changement backend.

## Découpage

- **SF-260-01** — le volet de droite suit son tour par fenêtres derrière un proxy (0,5 j).

## Hors périmètre

Son et clignotement d'onglet pour une autorisation du volet de droite (l'écran principal les a) ; refonte du
transport ; délai d'autorisation (120 s inchangé).
