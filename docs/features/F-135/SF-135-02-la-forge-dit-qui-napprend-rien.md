# Mini-spec — F-135 / SF-135-02 — La Forge dit quels clients n'apprennent rien

## Identifiant
`F-135 / SF-135-02` — feature parente `F-135`

## Objectif
Voir, **depuis l'écran de travail**, les postes qui n'accumulent aucun savoir — et les mettre en
mémoire d'un clic.

## Le défaut
SF-135-01 rend l'état lisible par l'API. Tant qu'aucun écran ne l'affiche, le constat de l'audit
reste vrai : trois postes sur quatre n'apprenaient rien **et personne ne pouvait le savoir**.
L'information doit être là où l'on travaille — la Forge — et pas dans une console d'administration
qu'on n'ouvre pas (leçon de SF-133-12).

## Comportement attendu
1. Un bandeau en tête de `/forge` nomme les postes dont la mémoire est **absente** ou **en attente
   de dépôt**, et porte le geste « Mettre en mémoire ».
2. Tous les postes apprennent ⇒ **aucun bandeau**.
3. Le geste fait, le bandeau se met à jour tout seul : le poste disparaît de la liste, ou y reste
   marqué « en attente » si la machine n'a pas répondu.
4. Le poste « Hébergé » n'y figure jamais : il n'a pas de racine, donc pas de carte.

| Cas d'erreur | Comportement |
|---|---|
| API en échec | **aucun bandeau**, aucune erreur à l'écran — c'est un confort, pas un service |
| Machine éteinte pendant le geste | le poste reste listé « en attente de dépôt » ; reprendre est sans risque |
| Geste refusé (403/404) | le bandeau ne casse pas, le poste reste listé |

## Critères d'acceptation
- [ ] Un poste `ABSENT` ou `PENDING` apparaît, nommé.
- [ ] Un poste `ACTIVE` n'apparaît pas ; tous actifs ⇒ pas de bandeau.
- [ ] Le poste « Hébergé » (`UNSUPPORTED`) n'apparaît jamais.
- [ ] Le geste appelle `POST /api/governance/hosts/{ref}/memory` et rafraîchit l'état.
- [ ] Une erreur d'API n'affiche rien et ne casse pas l'écran.
- [ ] Design system : jetons `--cg-*` uniquement, aucun `window.alert`.

## Hors scope
Le choix du paquet (le geste embarque les défauts) · le détail de la carte (écran Gouvernance) ·
le compte de faits par fichier (F-140).

## Technique
| Fichier | Changement |
|---|---|
| **`ForgeMemoryNoticeComponent`** *(nouveau)* | le bandeau et son geste |
| `governance.service.ts` | `rememberHost(ref)` |
| `governance.models.ts` | `memory`, `facts` sur `GovernanceHostSummary` |
| `postes.component.html` | l'insère sous le bandeau d'alerte de dépense |

Aucune table, aucune migration, aucune route nouvelle.

## Plan de test
- [ ] Bandeau présent avec un poste sans mémoire, absent sinon.
- [ ] « Hébergé » exclu.
- [ ] Le geste appelle la bonne route et relit l'état.
- [ ] API en échec ⇒ rien.

## Préoccupations transversales
| Préoccupation | Cochée | Composants |
|---|---|---|
| Auth / Principal | non | — |
| Contexte tenant | non | l'appel ne porte aucun identifiant, l'isolation est côté gateway |
| Plans / limites | non | — |
| **Navigation / routing** | **non** | aucun changement de route : un composant de plus dans un écran existant |
