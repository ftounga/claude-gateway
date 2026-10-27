# CADRAGE F-220 — Le tableau de bord du client

**Statut :** complété le 2026-10-27 par l'agent de livraison (vague autonome) — découpage, contrat d'API et arbitrages ci-dessous
**Demande PO :** 2026-10-27
**Dépend de :** F-219 (API de préflight et de lancement en autonome)

## Le besoin, dans les mots du PO
« J'utilise l'application de manière intense, j'ai beaucoup de chantiers en cours. Chez Edenred, j'oublie même qu'il y a des choses à faire, et il faut que mon responsable me rappelle que je n'ai pas fini ce sujet. Je veux un dashboard par client, une page vraiment élaborée : j'entre dans un client et je navigue entre les différents chantiers. Pas juste un affichage : je veux pouvoir lancer un chantier directement, ou en sélectionner deux ou trois qui manquent de choses à faire et, en un clic, que ça bascule sur le terminal et lance l'action en une fois. Le but : arriver dans l'application et retrouver rapidement ce qui me reste à faire. »

## Mesure
13 chantiers ouverts pour 3 clôturés sur 7 jours (9 CAGIP, 4 EDENRED).

## Décisions PO (2026-10-27)
- **Où :** dans la **Forge**, sur la **page du client** (le poste). Le tableau devient l'onglet d'ouverture du client.
- **Ordre :** livré après F-218 et F-219.

## Proposition UX (à challenger et maquetter par l'agent)
- Tous les chantiers ouverts de **tous les sujets** du client, regroupés par ce qu'ils attendent :
  - **« À toi de jouer »** : une question, une validation de plan, une étape EXTERNE ;
  - **« Prêt à partir en autonome »** : préflight F-219 au vert ;
  - **« Bloqué par quelqu'un »** : attentes F-175 ;
  - **« En cours »** : un tour est vivant, ou le chantier est en autonome.
- **Code couleur « qui débloque ? » — EXIGENCE PO du 2026-10-27, prioritaire sur la proposition ci-dessus.** Chaque chantier, et chaque tâche ou étape d'un chantier, affiche d'un coup d'œil **qui** doit agir pour qu'il avance. Trois couleurs, mutuellement exclusives, calculées par le serveur à partir de l'état du plan (pas devinées) :
  1. **Vert — « L'outil peut avancer seul ».** Rien n'attend personne : la suite est technique et faisable directement dans l'application (préflight F-219 au vert, ou prochaine étape exécutable). Geste principal : continuer / lancer en autonome. Ce sont les chantiers à faire avancer en premier ; le vert doit être « très joli » et dominant visuellement.
  2. **Couleur « J'attends quelqu'un d'autre »** (à choisir dans la palette du `DESIGN_SYSTEM`, par exemple un gris-bleu ou un ambre). Le chantier est bloqué sur une action externe : un message à envoyer, une information à obtenir, un accès, une réponse d'un tiers (attentes F-175, étapes EXTERNE). Afficher **à qui** et **depuis quand** ; gestes : relancer, rédiger le message, marquer reçu.
  3. **Couleur « Ça attend MA décision »** (troisième couleur distincte, par exemple l'orange d'accent). Rien d'externe : l'agent attend une réponse ou un arbitrage du PO (question `demander` en suspens, choix métier, plan à valider). Le PO peut le débloquer **lui-même, tout de suite**. Afficher la question, avec réponse directe depuis le tableau si c'est simple (sinon ouvrir le terminal sur la question).
  - Ces trois états structurent la page, en filtre et en regroupement : on voit d'abord le vert (ce que l'outil fait sans moi), puis « ma décision » (ce que je débloque en une réponse), puis « j'attends quelqu'un ».
  - La même pastille de couleur apparaît dans la vue d'**un sujet** (tableau des chantiers F-208, liste des étapes), pas seulement sur le tableau du client.
  - Un chantier « En cours » (tour vivant ou autonome) garde un indicateur d'activité **en plus** de sa couleur.
  - Accessibilité : la couleur ne porte jamais seule l'information ; libellé et icône l'accompagnent.
- **Ancienneté** visible sur chaque chantier (« dormant depuis 6 j »), mise en avant. C'est le remède à l'oubli.
- **Carte de chantier** :
  - sujet, titre, phase, anneau d'avancement (étapes faites / total) ;
  - prochaine étape ;
  - ce qui manque ;
  - dernier geste ;
  - coût engagé si disponible (F-133 / F-143).
- **Gestes :**
  - ouvrir le chantier dans son terminal, au focus ;
  - lancer en autonome ;
  - **multi-sélection**, puis « Lancer en autonome » sur plusieurs chantiers à la fois : chacun dans son sujet, dans la limite des 4 terminaux vivants (refus explicite au-delà), avec bascule vers la mosaïque (F-83) pour les voir travailler ;
  - clore, ou reporter.
- **Rappel des chantiers dormants :** à proposer (notification F-185 ou récapitulatif), sans spam.
- Mise à jour en direct si c'est simple (SSE `journey` existant, F-208).
- Responsive (téléphone) et `DESIGN_SYSTEM.md` obligatoires.

## Existant à réutiliser (vérifier avant de développer)
- Tableau des chantiers d'un sujet, en overlay (F-208) : modèle de plan, états, `depends_on`.
- Plusieurs chantiers par sujet et focus (F-206).
- Attentes (F-175) ; `sujets_etat` (F-178).
- Fil (F-191) ; Radar (F-99 à F-104) dans la Vigie : ne pas le dupliquer.
- Mosaïque (F-83) ; limite de 4 terminaux (F-70).
- Page client de la Forge refondue (F-98, F-68).

## Règles
- Isolation `user_id` sur toute agrégation.
- Pas de dashboard dupliquant le Radar : le Radar suit l'organisation (ce que les autres attendent) ; ce tableau suit **mes chantiers**.

---

## Complément de l'agent de livraison (2026-10-27)

### Existant vérifié avant de cadrer
- **Le préflight et le lancement** (F-219) existent : `GET …/journey/autonomy`, `POST …/chantiers/{n}/autonomy`,
  `GET /atelier/autonomy/runs` ; 4 courses autonomes au plus par compte, refus explicite. F-220 **les réutilise tels
  quels** : la multi-sélection appelle le lancement d'F-219 chantier par chantier, sans nouvelle route.
- **L'état du plan** (F-176/F-206/F-208) porte tout ce qu'il faut pour dire « qui débloque » : phase, plan validé ou
  non, statut des étapes (`BLOQUE` de F-219 compris), `depends_on`, `waits_on` relié aux attentes F-175, diagnostic et
  clôture proposés, question en suspens sur le tour vivant.
- **Les attentes F-175/F-180** disent **qui** doit agir (`qui` = `MOI`, `CLIENT`, `TIERS`), **à qui** (`requested_to`,
  `person`) et **depuis quand** (`requested_at`, `created_at`) : c'est la matière de « J'attends quelqu'un ».
- **`sujets_etat`** (F-178) agrège déjà les sujets d'un poste (dernière activité par `lastActivityByWorkspace`) : même
  schéma de lecture, sous `user_id` + `host_id`.
- **La page du poste** (F-98) a des onglets (`?onglet=`) : le tableau devient un onglet de plus, ouvert par défaut.
- **Le Radar** (Vigie) suit ce que l'organisation attend : non dupliqué ici, aucune lecture du Radar.

### Découpage
| SF | Contenu | Couche |
|----|---------|--------|
| SF-220-01 | **Qui débloque ?** — calcul serveur, pur, de la couleur d'un chantier et de chaque étape (Outil / Ma décision / Quelqu'un d'autre) ; exposé dans le parcours du sujet (`GET …/journey`) | backend |
| SF-220-02 | **Le tableau du client** — `GET /runner-hosts/{hostId}/chantiers` : tous les chantiers ouverts de tous les sujets du poste, couleur, ancienneté, prochaine étape, manque, dernier geste, course autonome, préflight, compteurs | backend |
| SF-220-03 | **L'écran du tableau** — onglet « Chantiers » d'ouverture de la page du poste : trois groupes, cartes, ancienneté, gestes ouvrir / lancer / **multi-sélection → lancer plusieurs** (refus au-delà de 4) puis mosaïque, clore | frontend |
| SF-220-04 | **La pastille dans le sujet** — même couleur dans le tableau des chantiers F-208 (onglets et liste des étapes) | frontend |
| SF-220-05 | **Reporter et rappeler** — reporter un chantier (il sort des compteurs jusqu'à une date ; table `chantier_snoozes`, migration 176), rappel des chantiers dormants dans le récapitulatif quotidien F-185 (une notification par jour au plus) | backend |
| SF-220-06 | **Reporter, à l'écran** — « Reporter » (1 j / 3 j / 1 semaine) sur la carte, section « Reportés » repliée, « Ne plus reporter » | frontend |

### Règle « Qui débloque ? » (calculée, jamais devinée)
**Étape** (non faite) :
1. `BLOQUE` ou `ECHEC` → **Ma décision** (sauf si son `waits_on` est une attente ouverte d'un tiers → **Quelqu'un**).
2. `waits_on` sans attente enregistrée → **Quelqu'un** (« à obtenir — personne ne le suit »).
3. `waits_on` sur une attente ouverte : `qui = MOI` → **Ma décision** ; sinon → **Quelqu'un** (à qui, depuis quand).
4. Bloquée par une étape antérieure non faite → **la couleur de cette étape** (elle l'attend).
5. Sinon → **L'outil**.

**Chantier** (le premier cas qui s'applique) :
1. Question ou autorisation en suspens sur le tour vivant (chantier au focus) → **Ma décision** (la question est montrée).
2. Clôture proposée → **Ma décision**. 3. Diagnostic « Prêt à planifier » en attente → **Ma décision**.
4. Plan posé mais non validé à sa version courante → **Ma décision**.
5. Investigation, ou Plan sans plan posé → **L'outil** (l'agent investigue ou rédige).
6. Toutes les étapes vérifiées → **Ma décision** (« à clore »).
7. Une étape exécutable (à faire, rien ne la bloque) ou faite à vérifier → **L'outil** (prochaine étape).
8. Sinon la première étape **Ma décision**, à défaut la première **Quelqu'un**.

Indicateur « En cours » en plus de la couleur : un tour vivant sur le sujet (chantier au focus) ou une course autonome.

### Contrat d'API
| Méthode | Route | Rôle |
|---------|-------|------|
| GET | `/api/workspaces/{ws}/journey` | inchangé + `chantiers[].unblocker` et `plan.steps[].unblocker` (SF-220-01) |
| GET | `/api/runner-hosts/{hostId}/chantiers` | le tableau du client (SF-220-02) |
| POST | `/api/workspaces/{ws}/journey/chantiers/{n}/autonomy` | F-219, inchangé — appelé une fois par chantier sélectionné |
| POST / DELETE | `/api/workspaces/{ws}/journey/chantiers/{n}/snooze` | reporter / ne plus reporter (SF-220-05) |

`unblocker` = `{ who: OUTIL|MOI|AUTRUI, label, detail, step, waitingOn, since }` — `label` court (pastille), `detail`
la phrase lisible, `step` le rang concerné ou `null`, `waitingOn` à qui (Quelqu'un), `since` depuis quand.

### Arbitrages pris (décider par défaut, réversibles)
1. **Une étape `EXTERNE` n'est pas « J'attends quelqu'un »** : depuis F-218 l'irréversible part sans confirmation,
   l'outil l'exécute. Seules les **attentes** (`waits_on`) disent qu'on attend un tiers. *Écarté* : colorer le risque
   (un push serait « bloqué » alors que l'outil le fait).
2. **Une attente `qui = MOI`** (se reconnecter, redémarrer le runner) est **Ma décision**, pas « Quelqu'un » (F-180 D1).
3. **Une information non suivie** (`waits_on` sans attente) est **Quelqu'un** : il faut l'obtenir ; le détail dit que
   personne ne la suit.
4. **Multi-sélection** = le lancement F-219 appelé chantier par chantier, dans l'ordre affiché ; les refus (pas prêt,
   limite de 4, sujet occupé, poste hors ligne) sont rapportés par chantier, les lancés partent quand même ; puis
   bascule vers la mosaïque (F-83). *Écarté* : une route « lancer N » côté serveur (même garde, doublon).
5. **Ancienneté** = dernier geste connu du chantier : la plus récente de sa mise à jour (plan, phase) et, pour le
   chantier au focus, du dernier message du sujet. « Dormant » au-delà de 2 jours.
6. **Coût** affiché seulement s'il est mesuré **par chantier** : le coût de sa course autonome (F-219). Le coût par
   sujet (F-143) n'est pas attribuable à un chantier : pas montré sur la carte.
7. **Réponse directe à une question depuis le tableau** : non — « Répondre » ouvre le terminal du sujet, où la carte
   de la question est déjà présente (F-164). *Écarté* : dupliquer le composant de réponse.
