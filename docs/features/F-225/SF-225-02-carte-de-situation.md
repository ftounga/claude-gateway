# SF-225-02 — La carte « Point de situation »

**Feature :** F-225 · **Statut :** done (2026-10-31) · **Estimation :** < 1 j · **Branche :** `feat/SF-225-02-carte-de-situation` · **Dépend de :** SF-225-01 (mergée)

## Objectif
En revenant dans un terminal, le PO voit d'un coup d'œil où on en était, sans rien demander à l'agent.

## Comportement
1. **Carte** (`app-terminal-situation`), panneau du fil : « Dernier échange il y a N h » ; chantiers (étape suivante, plan à valider, diagnostic à confirmer, qui débloque, en sommeil) ; **mails et messages des 7 jours** — un encart non marqué devient « Mail à X : pas marqué envoyé » avec **[Marquer envoyé]** (le geste F-211 existant) et **[Voir]** (objet et début du texte) ; ce qui attend (qui, à qui, depuis quand) ; dernier échange (demande + **essentiel** F-126, repli premier paragraphe) ; pièces fournies ; suites proposées.
2. **Ouverture** : d'elle-même à l'entrée d'un terminal inactif depuis au moins **N heures** (réglable sur la carte : 2, 4 — défaut —, 8, 24 h ou jamais ; mémorisé sur l'appareil) ; par le menu de l'en-tête **[Point de situation]** ; par la commande **`/situation`** (vue, aucun tour). Jamais en double ; jamais en lecture seule à l'entrée ; le brouillon n'est pas touché.
3. **Fond sombre** : encres du terminal sombre (§21) — `--cg-divider`, `--cg-terminal-ink-2`, `--cg-orange-2` ; contrastes AA ; lisible sur téléphone (texte qui passe à la ligne, boutons qui s'empilent).

## Erreurs
| Situation | Comportement |
|---|---|
| Lecture en échec | « Le point de situation n'a pas pu être lu. » |
| Lecture en échec à l'entrée | rien ne s'ouvre ; menu et `/situation` restent |
| Lecture seule | pas de [Marquer envoyé] |

## Critères d'acceptation
- [x] Chantier et étape, mail « pas marqué envoyé », attente, essentiel (sans marqueurs), pièces, suites.
- [x] [Voir] montre le texte ; [Marquer envoyé] émet le geste et retire la ligne « pas marqué ».
- [x] Seuil réglable ; « jamais » coupe l'ouverture automatique ; inactivité ≥ seuil → ouverture.
- [x] `/situation` est une commande vue du registre.

## Plan de test
`terminal-situation.component.spec` (5 tests) ; suite front complète (3621/3621), build.

## Impact
`terminal-situation.component` (nouveau), `situation.models`, `situation.service`, `slash-panel-commands` (+`/situation`), `atelier-terminal` (menu, panneau, ouverture à l'entrée, relais du geste). Préoccupations transversales : navigation — aucune route ; le menu gagne une entrée.

## Hors périmètre
Récit rédigé par le modèle (cadrage).
