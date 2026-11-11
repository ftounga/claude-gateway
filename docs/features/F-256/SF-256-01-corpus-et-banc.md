# Mini-spec — F-256 / SF-256-01 Corpus et banc à vraie voix

## Identifiant
`F-256 / SF-256-01`

## Feature parente
`F-256` — Banc à vraie voix de Jarvis (cadrage `CADRAGE-F-256-banc-a-vraie-voix.md`)

## Statut
`in-review` · **Estimation :** 2 j · **Branche :** `feat/SF-256-01-corpus-et-banc` · **Dépend de :** rien

## Objectif
Faire entendre de vraies voix à la vraie boucle d'écoute de `/jarvis`, contre une passerelle locale branchée sur les
vrais fournisseurs, et mesurer tour par tour ce qui est entendu, compris, dit et affiché, en une commande.

## Comportement attendu

### Cas nominal
1. `lancer.sh` lance la passerelle locale (`passerelle.sh` : jar, profil `dev`, H2 en fichier neuve, configuration non
   secrète de la prod filtrée Jarvis/voix/écoute, clés lues à la volée dans `backend-secrets`, JWT local aléatoire,
   courrier/agenda/relève coupés), attend son démarrage, amorce le compte de test (`amorce.sh` : ADMIN, CAGIP > agenor,
   data-platform ; EDENRED > waf), complète le corpus (`corpus.mjs`, cache) puis joue les scénarios (`banc.mjs`).
2. `banc.mjs` sert la page (build de développement) et relaie `/api` vers la passerelle en gardant les corps utiles à la
   mesure ; injecte `micro.js` avant la page (micro virtuel + sondes) ; ouvre `/jarvis` ; attend que Jarvis écoute
   et se taise ; joue chaque phrase ; attend que Jarvis quitte l'écoute ; à la fin, vide le journal d'écoute.
3. `mesure.mjs` produit par tour : entendu/perdu, texte compris, WER, réponses provisoires jetées, mode d'écoute, repli,
   sessions du flux (code et raison de fermeture, erreurs), premier son et première voix après la fin réelle de
   parole, phrases dites, répétitions, ordres d'écran demandés/affichés/refusés, « Voici » sans affichage, rejets ;
   et les totaux (taux, médiane/p90, replis par type, codes de fermeture, coût en € du passage).
4. `porte.mjs` juge les totaux (≥ 95 % entendu, premier son médian ≤ 2 s, 0 répétition, 0 « Voici » sans image,
   0 repli) ; `banc.mjs` sort 0 si franchie, 1 sinon. `chrono.mjs` affiche la chronologie d'un passage.

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Passerelle qui ne démarre pas | `lancer.sh` s'arrête (code 2) en pointant `passerelle.log` |
| Phrase absente du corpus | arrêt explicite « lancer corpus.mjs » ; clé absente : arrêt explicite, rien synthétisé |
| Jarvis n'écoute pas avant une phrase (40 s) | tour noté `jarvis_n_ecoutait_pas`, la phrase est jouée quand même et comptée |
| Jarvis ne démarre pas l'écoute à l'ouverture | scénario marqué « Jarvis n'a pas démarré l'écoute » |
| Demande d'un fichier audio hors corpus | 404 (liste blanche des fichiers de l'index) |
| Appel relayé en échec | statut 502 consigné, la page continue |

## Critères d'acceptation
- [ ] Une commande rejoue tout le banc et écrit `rapport.json` + `resume.txt` hors du dépôt.
- [ ] Aucune clé n'est écrite dans un fichier, affichée ou journalisée (lues par `kubectl get secret` dans l'environnement
      du processus seulement) ; aucune écriture en prod.
- [ ] Le témoin Chrome (micro factice de Chrome) donne les mêmes fermetures du flux que le micro virtuel.
- [ ] Le rapport donne, par tour : entendu, texte vs attendu, repli et raison de fermeture, premier son après la fin de
      parole, répétition, ordre d'écran demandé vs affiché/refusé, « Voici » sans affichage ; et le coût en €.
- [ ] Les 12 vraies captures et le corpus synthétique sont hors dépôt ; aucune voix ni donnée client dans git.
- [ ] Aucun fichier de production modifié (consigne, outils, modèle, boucle vocale inchangés).

## Plan de test
- Unitaires : `node --test frontend/scripts/banc-jarvis-voix/mesure.test.mjs` (WER, percentile, activité d'un
  enregistrement, fenêtre d'un tour, réponse provisoire jetée, repli, premier son, affichage hors fenêtre, « Voici » sans
  affichage, répétition, phrase perdue, totaux, coût, porte franchie / non franchie, résumé).
- Intégration : passage complet du banc contre la passerelle locale (référence, SF-256-02) et témoin Chrome.
- Isolation utilisateur : sans objet (aucune route, aucune table ; compte de test unique sur H2 locale).
- Suites complètes backend et frontend vertes (aucun code applicatif touché).

## Impact
Nouveaux fichiers `frontend/scripts/banc-jarvis-voix/` (`lancer.sh`, `passerelle.sh`, `amorce.sh`, `avec-cles.sh`,
`corpus.mjs`, `phrases.json`, `micro.js`, `banc.mjs`, `mesure.mjs`, `porte.mjs`, `chrono.mjs`, `relire.mjs`,
`mesure.test.mjs`, `README.md`) ; `docs/features/F-256/` ; `docs/PRODUCT_SPEC.md`. Aucune table, aucun endpoint, aucun composant Angular.
Préoccupations transversales : aucune.

## Hors périmètre
Corriger quoi que ce soit dans la boucle vocale ; la règle de release (SF-256-02) ; un banc en CI (exige les clés et le
cluster) ; la parité Android (compagnon).
