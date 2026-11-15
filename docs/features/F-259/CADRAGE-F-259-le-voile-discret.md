# Cadrage — F-259 Le voile discret

**Demande PO du 2026-11-15.** Chez un client, un collègue qui passe dans le dos du PO (café, coup de téléphone,
ou simplement en arrivant pendant qu'il travaille) voit à l'écran le terminal, les noms de clients (Edenred, CAGIP…)
et, dans la barre des tâches, le titre de l'onglet « NG Portal — passerelle professionnelle vers Claude ». Le but :
qu'un regard de passage ne révèle pas l'usage de l'outil.

## Principe directeur (PO)

**On ne retire rien de l'application.** Le voile se **pose par-dessus** l'écran puis s'**enlève** ; en temps normal,
rien ne change (ni écran, ni titre, ni notifications). Aucune fonction n'est masquée durablement.

## Décisions PO (2026-11-15)

| # | Décision |
|---|---|
| D1 | **Deux façons de poser le voile** : un **raccourci** (Échap deux fois de suite) et l'**inactivité** (2 min sans souris ni clavier, réglable). |
| D2 | **Une seule façon de l'enlever** : un **clic** sur le voile, quelle que soit la façon dont il a été posé. Le mouvement de souris **n'enlève pas** le voile (un collègue qui frôle la souris découvrirait l'écran). |
| D3 | **Le titre de l'onglet devient neutre pendant le voile** (« Documentation »), puis reprend son nom au retrait. |
| D4 | **Écartés** : titre neutre permanent, notifications muettes, masquage du nom du client dans la barre du haut. |

## Décisions par défaut (prises au cadrage, révisables)

| # | Décision | Raison |
|---|---|---|
| P1 | Le voile ressemble à une **page banale** (page de documentation grise et neutre, sans logo, sans « verrouillé », sans nom de produit). | Un écran de verrouillage attire l'œil ; une page terne non. |
| P2 | Le voile est un **calque** au-dessus de l'écran : le tour d'agent en cours, le flux, Jarvis continuent derrière, rien n'est interrompu ni rechargé. | Principe directeur. |
| P3 | **Réglage par appareil**, dans `/settings` : « Voile discret » activé/désactivé (désactivé par défaut) et délai d'inactivité (1, 2, 5, 10 min ; 2 par défaut). Raccourci et inactivité n'agissent que si le réglage est activé. | C'est le poste chez le client qui en a besoin ; ne surprend aucun autre utilisateur. |
| P4 | Couvre les **écrans connectés** : le cadre principal (terminal, Atelier, pages, réglages…) et l'écran **Jarvis**. Pas les pages publiques (accueil, connexion). | Les noms de clients n'apparaissent que là. |
| P5 | Le premier Échap garde son rôle actuel (fermer le panneau ouvert) ; le **second, dans les 500 ms**, pose le voile. | Aucun conflit : fermer un panneau avant de voiler est sans dommage. |
| P6 | Pendant le voile, le clic qui l'enlève **ne traverse pas** jusqu'à l'écran dessous ; le clavier ne tape rien dans le terminal. | Un clic de retrait ne doit pas déclencher une action cachée. |

## Découpage

| SF | Contenu | Estimation |
|---|---|---|
| SF-259-01 | Le voile : raccourci, inactivité, clic de retrait, titre neutre, réglage par appareil | 1 j |

Frontend seul : aucune table, aucun endpoint, aucun impact backend.

## Hors périmètre

Verrouillage réel (mot de passe au retour) — le verrou Windows (Win+L) reste l'outil pour une absence longue ;
notifications ; titre permanent ; application mobile ; fenêtre du runner sur le poste.
