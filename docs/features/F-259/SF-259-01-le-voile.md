# Mini-spec — F-259 / SF-259-01 Le voile

## Identifiant
`F-259 / SF-259-01`

## Feature parente
`F-259` — Le voile discret (cadrage `CADRAGE-F-259-le-voile-discret.md`)

## Statut
`done` · **Estimation :** 1 j · **Branche :** `feat/SF-259-01-le-voile`

## Objectif
Qu'un regard de passage sur l'écran du PO ne voie qu'une page banale, posée par un raccourci ou après une courte
inactivité, et enlevée d'un clic sans rien perdre.

## Comportement attendu

### Cas nominal
1. **Réglage** (`/settings`, section « Voile discret », stocké par appareil) : activé/désactivé (défaut : désactivé),
   délai d'inactivité 1 / 2 / 5 / 10 min (défaut : 2).
2. **Raccourci** : réglage activé, deux appuis sur Échap à moins de 500 ms posent le voile. Le premier Échap garde son
   effet actuel (fermer le panneau ouvert).
3. **Inactivité** : réglage activé, aucun mouvement de souris, clic, touche, molette ni toucher pendant le délai →
   le voile se pose. Un onglet en arrière-plan compte aussi (le temps court).
4. **Le voile** : calque plein écran au-dessus de tout (dialogues compris), aspect d'une page de documentation
   neutre (gris, texte de remplissage générique, aucun logo, aucun nom de produit ni de client, pas de mot
   « verrouillé »). Couleurs et police tirées de `DESIGN_SYSTEM.md` (gris neutres).
5. **Titre** : à la pose, le titre de l'onglet devient « Documentation » et l'icône de l'onglet une icône neutre ; au
   retrait, titre et icône d'avant sont rétablis à l'identique (y compris un titre changé entre-temps par une page).
6. **Retrait** : un **clic** (ou un toucher) sur le voile l'enlève ; l'écran réapparaît tel quel (défilement, saisie
   en cours, panneaux). Le clic ne traverse pas. Le mouvement de souris et le clavier **n'enlèvent pas** le voile.
7. **Rien ne s'arrête** : tour d'agent, flux SSE, Jarvis, minuteries continuent derrière ; aucun rechargement.
8. **Portée** : cadre principal (`ShellComponent`, toutes ses routes) et écran `/jarvis`. Absent des pages publiques.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Réglage désactivé | ni raccourci ni inactivité ; Échap inchangé |
| Stockage local indisponible (navigation privée, accès refusé) | réglage lu comme « désactivé », aucune erreur ; la page de réglages le signale |
| Touches tapées pendant le voile | ignorées : rien n'est saisi dans le terminal ni ailleurs |
| Échap pendant le voile | sans effet (ne retire pas, ne ferme rien dessous) |
| Déconnexion ou expiration de session pendant le voile | la page de connexion habituelle remplace l'écran au retrait (comportement existant) |
| Écran Jarvis en 3D | le voile couvre aussi le canvas ; la voix continue |

## Contraintes de validation
| Élément | Règle |
|---|---|
| Fenêtre du double Échap | 500 ms |
| Délais proposés | 1, 2, 5, 10 min |
| Clé de stockage | une clé locale dédiée, lecture/écriture en `try/catch` |
| Titre neutre | « Documentation » |

## Critères d'acceptation
- [x] Réglage désactivé par défaut ; activé, il est retrouvé après rechargement sur le même appareil.
- [x] Double Échap (< 500 ms) pose le voile ; deux Échap espacés de plus de 500 ms ne le posent pas.
- [x] Après le délai sans activité, le voile se pose ; une activité avant l'échéance relance le compte.
- [x] Voile posé : titre « Documentation » et icône neutre ; retrait : titre et icône rétablis.
- [x] Mouvement de souris et touches n'enlèvent pas le voile ; un clic l'enlève et ne déclenche rien dessous.
- [x] Un tour d'agent en cours continue de s'afficher derrière et est visible, à jour, au retrait.
- [x] Aucun texte du voile ne contient « Claude », « NG Portal », « Atelier » ni un nom de client.
- [x] Voile présent sur `/atelier` et `/jarvis`, absent de `/login` et de l'accueil public.

## Plan de test
- **Unitaires** (service du voile, `fakeAsync`) : double Échap dans/hors fenêtre ; minuterie d'inactivité relancée
  par chaque type d'activité ; réglage désactivé = inerte ; titre/icône sauvegardés puis rétablis ; stockage qui
  lève une exception.
- **Composant** (voile) : le clic retire et ne se propage pas ; clavier et mouvement ignorés ; textes sans nom de
  produit ni de client.
- **Composant** (`/settings`) : la section lit et écrit le réglage.
- **Intégration** : banc navigateur headless sur `/atelier` (pose par raccourci et par inactivité au délai court
  forcé, retrait au clic, titre de l'onglet avant/pendant/après).
- **Isolation utilisateur** : sans objet (aucune donnée serveur ; réglage local à l'appareil).

## Impact
- Frontend : nouveau service + composant du voile (dans `frontend/src/app/layout/` ou `core/`), montés dans
  `ShellComponent` et dans l'écran Jarvis ; section dans `settings.component`.
- Backend : aucun. Tables : aucune. Endpoints : aucun.
- Préoccupations transversales : **Navigation / routing** non touchée (aucune route ni guard) ; Auth, tenant, plans :
  non concernés.

## Hors périmètre
Mot de passe au retour ; notifications ; titre neutre permanent ; masquage du nom du client ; mobile/PWA au-delà du
toucher de retrait ; fenêtre du runner.
