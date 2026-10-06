# Cadrage — F-176 rouverture du 2026-10-06 : le parcours qui avance d'un clic et se referme

> Retour PO le 2026-10-06 : *« pendant le processus je dois cliquer sur des options, mais derrière je
> ne sais pas comment passer à l'étape suivante — message ou bouton ? Il faudra que ce soit plus clair.
> Et à la fin, quand le sujet est clos, le bloc ne disparaît pas ; il y a même une porte fermée parce
> que le mode guidé est clos, il n'a pas pu faire `aws sso login`. »*
> Audit : `docs/audits/AUDIT-2026-10-06-terminal-central-gouvernance-parite.md` §2 (défauts G1, G2, G3,
> prouvés en prod). F-176 reste la base ; cette rouverture ajoute SF-176-07 → 10.

## 1. Objectif

Un geste dans le parcours guidé **fait avancer** le travail sans deviner quoi faire ensuite, et un
sujet clos **libère** le terminal : plus de bande résiduelle, plus de porte fermée.

## 2. Décisions (par défaut, réversibles)

| # | Décision | Pourquoi |
|---|---|---|
| D1 | **Clore = revenir en Libre.** `close()` passe `phase=CLOS` (historisé pour la mesure) **et** `mode=LIBRE`. Le plan clos reste consultable. | G2, G3 |
| D2 | **La porte ne s'applique qu'en Guidé actif** (phases Investigation → Vérification). Hors de là (`LIBRE`, `CLOS`), `JourneyGate` laisse passer — les autres protections (permissions, contrôles F-50) restent. Reprise des lignes existantes : migration `CLOS` + `GUIDE` → `LIBRE`. | G3 (11 refus après clôture) |
| D3 | **L'authentification du poste n'est jamais une modification** : `aws sso login/logout`, `aws configure sso`, `az login`, `gcloud auth login`, `gh auth login`, `glab auth login`, `kubectl config use-context` → classe `NOTES` (ou nouvelle classe `AUTH_POSTE`). | G3 |
| D4 | **Inconnu ≠ modification pour une lecture évidente** : une commande dont tous les segments sont des lecteurs connus (`cat`, `tail`, `grep`, `aws … describe/get/list`, `kubectl get/logs/describe`) reste `LECTURE`. Mesure des refus « inconnu » journalisés. | G3 (lecture de journaux bloquée) |
| D5 | **Message de refus exact** : *« Ce terminal est en mode Guidé, phase X : cette action attend la validation du plan. »* ; le front affiche le verrou **chaque fois** que le back refuse (même logique, une seule source : `JourneyGate.refusal`). | G3 |
| D6 | **Un clic = la décision ET la reprise.** [Planifier], [Valider le plan et lancer], [Valider l'amendement et reprendre], [Clore] : le back enregistre le geste **puis démarre un tour** avec un message visible dans le fil (« ✓ Plan v2 validé — exécution lancée »). Variante secondaire « Valider sans lancer ». Désactivés pendant un tour en cours. | G1 (le PO tape « go » après chaque clic) |
| D7 | **Une seule règle visible : l'indicateur « à qui la main ».** Sous la saisie : « **À vous** — écrivez ou cliquez une option », « **L'agent attend votre validation** ↑ » (avec ancre vers la carte), « **L'agent travaille…** ». Les cartes `demander` (F-164) et du parcours reprennent le même vocabulaire de bouton (verbe d'action + « et continuer »). | G1, deux règles qui cohabitent |
| D8 | **Bande repliée après clôture** : une ligne « Chantier clos le … · voir le plan », masquable ; la bande des phases n'est rendue qu'en Guidé actif. | G2 |
| D9 | **Plusieurs chantiers par sujet** (précision PO du 2026-10-06 : *« beaucoup de mes sujets sont tellement vastes qu'ils auront beaucoup de chantiers, donc beaucoup de plans d'action »*). Un sujet porte une **suite de chantiers**, **un seul actif** à la fois. Repasser en Guidé après une clôture ouvre un **nouveau chantier** (titre, Investigation, plan v1, diagnostic vide) — aujourd'hui la même ligne est réutilisée et le nouveau chantier hériterait de l'ancien plan. Les chantiers clos sont **archivés** (titre, dates, diagnostic, plan validé final) et consultables depuis l'en-tête (« Chantiers : 3 — voir »). L'agent propose « nouveau chantier » quand il détecte un chantier distinct (même proposition que le Guidé, SF-176-02). Le `PLAN-ACTION.md` du sujet reçoit une section par chantier. Chantiers **simultanés** dans un même terminal : hors périmètre (une seule porte par terminal ; pour paralléliser, un autre sujet). | Précision PO ; défaut de réutilisation de ligne (`SubjectJourneyService.applyMode`) |

## 3. Découpage

| SF | Titre | Contenu | Estim. |
|---|---|---|---|
| SF-176-07 | La porte s'ouvre à la clôture | D1, D2, D3, D4, D5 (back + migration + message + verrou front). **Bug bloquant, en premier.** | 1 j |
| SF-176-08 | La bande se replie | D8 (front). | 0,5 j |
| SF-176-09 | Un clic fait avancer | D6 : endpoint de geste qui démarre le tour (réutilise le démarrage de tour existant, F-84) + message système visible + boutons renommés. | 1,5 j |
| SF-176-11 | Plusieurs chantiers par sujet | D9 : table `subject_journey_chantiers` (archive, `user_id` + `workspace_id`), remise à zéro du plan/diagnostic à l'ouverture d'un chantier, titre, liste des chantiers clos dans l'en-tête, proposition « nouveau chantier » par l'agent. | 2 j |
| SF-176-10 | À qui la main | D7 : indicateur sous la saisie (états dérivés : tour en cours, porte/question en attente, sinon à vous) + harmonisation des libellés F-164. | 1 j |

## 4. Critères d'acceptation (extraits)

- Clore un chantier puis repasser en Guidé → nouveau chantier en Investigation, plan vide ; l'ancien est consultable dans la liste des chantiers.
- Clore un parcours → le terminal est en Libre, `aws sso login` s'exécute, aucune bande des phases.
- En Guidé, phase Plan : `aws sso login` passe ; `terraform apply` est refusé avec le message D5, et le verrou est visible.
- Cliquer [Valider le plan et lancer] → un tour démarre sans rien taper ; le fil montre « ✓ Plan vN validé ».
- À tout instant, l'indicateur D7 dit qui a la main ; il ne contredit jamais l'état serveur.

## 5. Préoccupations transversales

- **Navigation / routing** : non.
- **Auth / tenant** : non (isolation `user_id` + `workspace_id` inchangée sur `subject_journeys`).
- **Composants impactés** : `SubjectJourneyService`, `JourneyGate`, `JourneyRiskClassifier`, contrôleur du parcours, `terminal-journey-strip.component`, `atelier-terminal.component`, `atelier-terminal-demande.component`, `journey.models.ts`.

## 6. Hors périmètre

Rendre le parcours multi-sujets sur un même terminal (un terminal = un parcours à la fois reste la règle) ;
toute modification de la logique de plan elle-même.
