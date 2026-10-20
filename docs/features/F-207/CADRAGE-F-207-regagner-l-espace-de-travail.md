# Cadrage — F-207 Regagner l'espace de travail

**Demande PO du 2026-10-21.** « L'application devient de plus en plus difficile à utiliser sur un seul écran ou sur deux petits écrans. Les en-têtes prennent beaucoup trop de place. Le bas du terminal (étapes du chantier) reste affiché tout le temps. Les relances et les attentes doivent pouvoir disparaître. La liste des questions prend de la largeur. Ce qui reste visible : les tâches en cours, les sous-agents, les sous-tâches. Le reste peut apparaître au survol d'une zone de l'écran. L'écran doit rester très joli, sans brader le visuel. » Le PO ajoute : « ça va forcément demander une refonte graphique ».

## Constat (1366 × 768, terminal chargé, captures `captures/avant-*.png`)
Autour du fil, l'écran empile six couches fixes : la barre de navigation globale (64 px), l'en-tête du terminal, la rangée « Où s'exécutent les outils », les bandeaux du bas (parcours guidé, point du matin, attentes), la bande « Mode » et la saisie. À droite, le rail « Vos questions » prend 232 px plus la gouttière. Le fil ne garde qu'une petite partie de la hauteur utile. **Mesure (getBoundingClientRect) : barre globale 64 px, en-tête 57, rangée d'exécution 59, ligne « poste connecté » 57, conversation 137 px seulement, bande Mode 59, parcours guidé 120, point du matin 58, attentes 36, fil des tâches 45, saisie 57. Rail des questions : 232 px de large.**

## Principe retenu : « le fil d'abord, le reste à portée de main »
Chaque bord de l'écran garde une fonction. Ce qui ne sert pas en continu se replie en une **lisière fine et lisible**. Au survol (ou au focus clavier), la lisière s'ouvre **en surimpression**, sans repousser le fil. Ce qui exige une décision (proposition de passer en guidé, plan à valider, clôture, diagnostic, autorisation) **ne se replie jamais**.

| Bord | Aujourd'hui | Après F-207 | SF |
|---|---|---|---|
| Haut (global) | barre de navigation 64 px, toujours là | sur les écrans de travail (terminal Forge et Vigie) : une lisière de 6 px, la barre glisse au survol en surimpression ; une punaise la garde affichée (mémorisé sur l'appareil) | SF-207-01 |
| Haut (terminal) | en-tête + rangée « Où s’exécutent les outils » + ligne « poste connecté » | l’en-tête seul ; la rangée et la ligne d’état nominale deviennent un tiroir qui s’ouvre au survol de l’en-tête (ou par la pastille moteur) ; un poste en difficulté reste affiché | SF-207-04 |
| Droite | rail « Vos questions » 232 px | une lisière de numéros (≈ 32 px) ; le rail complet s'ouvre au survol, par-dessus le fil | SF-207-02 |
| Bas | plan reporté, parcours guidé, point du matin, attentes, bande « Mode » : chacun sur sa ligne | **une seule barre d'état fine** (puces : Chantier · étape, Attentes, Point du matin, Plan) qui s'ouvre vers le haut au survol ; le mode passe dans la ligne de saisie ; chaque puce peut être retirée ; le fil des tâches (tâches, sous-agents, sous-tâches) **reste affiché** | SF-207-03 |

## Décisions (gate 🟠 réversible, prises par défaut)
- **Surimpression plutôt que poussée** : ouvrir un tiroir ne fait jamais sauter le fil (pas de reflow pendant la lecture).
- **Délai de fermeture de 350 ms** et zone de survol tolérante : pas de clignotement quand la souris frôle une lisière.
- **Clavier et tactile** : chaque lisière est un vrai bouton (focus, Entrée) ; sous 820 px, rien ne change (le téléphone a déjà sa mise en page F-158).
- **Les décisions ne se cachent pas** : un parcours qui attend un geste ouvre la barre du bas d'office, et la puce porte « à vous ».
- **Charte inchangée** : aucune couleur ni police nouvelle ; ombres et rayons de la charte ; transitions de 160 à 200 ms, désactivées sous `prefers-reduced-motion`.
- **Préférences par appareil** (`localStorage`, protégé par try/catch) : punaise de la navigation, puces retirées. C'est un confort d'affichage, pas une donnée.

## Découpage
- SF-207-01 Navigation globale escamotable sur les écrans de travail
- SF-207-02 Rail « Vos questions » en lisière
- SF-207-03 Une seule barre d'état en bas du terminal
- SF-207-04 Réglage d'exécution dans un tiroir d'en-tête

## Hors périmètre
Backend, écrans hors terminal (Chat, Gouvernance… gardent la barre), mise en page téléphone (< 820 px), tuiles de mosaïque (lecture seule), contenu des pastilles d'en-tête (F-200).
