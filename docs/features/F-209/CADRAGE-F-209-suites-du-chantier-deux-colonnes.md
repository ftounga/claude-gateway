# Cadrage F-209 — Les suites du chantier en deux colonnes, prérequis cochables

**Demande PO du 2026-10-24** — deux besoins du registre, qui complètent F-208 :
- `bc933860` « Propositions de fin de réponse en deux colonnes quand un chantier est ouvert : « dans le chantier » / « hors chantier » » ;
- `3a181012` « Prérequis d'une proposition en cases à cocher, avec dépendances visibles (parallèle ou séquentiel) ».

Dépend de **F-208 / SF-208-01** (champ `depends_on` du plan). Justesse avant coût ; préfixe stable ; charte.

## Besoin (mots du PO)
« Quand je suis dans un chantier, je veux vraiment que la proposition qui suit va dans ce chantier-là […] je dois sentir que je suis dans l'exécution de ce chantier. » « Les propositions plutôt plus élevées et on diffuse ça en 2 colonnes : à gauche ce qui va dans le chantier, à droite hors chantier — dans un chantier tu peux envoyer des mails, des messages qui n'étaient pas dans le chantier. » « S'il y a des choses qui doivent être faites avant, tu mets des checkboxes […] en parallèle ou en séquentiel, qu'on voie les dépendances. »

## Existant vérifié
- Consigne `NEXT_STEPS_DOCTRINE` (`AtelierChatService` l.705) : **2 à 4 options au total**, en guidé `etape`/`chantier`, **au plus UN approfondissement**. Parseur `NextStepsBlock` (`MAX_STEPS = 4`), rattachement `ChantierAnchor` (`MAX_DEEPENINGS = 1`), `NextStep.scope` CHANTIER|APPROFONDISSEMENT, `Progress(done,total)`.
- Composant `terminal-next-steps` : **une colonne**, touches 1-4, sélection multiple, « Basculer sur le chantier N » ; **un clic remplit la saisie**, n'envoie rien.
- Conséquence observée : dans un chantier, les mails/messages hors plan sont évincés.

## Décisions
1. **Plafonds par colonne, sans plafond commun** : chantier ouvert ⇒ colonne **Chantier** (focus) **2 à 4** options rattachées à une étape ; colonne **Hors chantier** **0 à 3** (messages/mails hors plan, approfondissements, **au plus une** vers un autre chantier ouvert, badge « Ch.3 »). Sans chantier ouvert : une seule colonne, 2 à 4, au choix de l'agent (inchangé). Parseur : 7 options au plus ; `ChantierAnchor` applique les plafonds par colonne ; `NextStep` gagne `column` (CHANTIER|HORS).
2. **Ordre de la colonne Chantier** (imposé côté serveur après rattachement, stable) : prochaine étape débloquée, puis ce qui débloque une étape en attente (relance), puis vérification de ce qui vient d'être fait. Une étape **bloquée** n'est pas proposée comme « à faire » : l'option porte le nom de son attente / de ses dépendances.
3. **Badge** « Ch.4 · étape 4/14 » par option, **mini-barre « 5/14 faites »** en tête de colonne « Chantier 4 — titre ».
4. **Prérequis** : pour une option rattachée à l'étape S, `prerequisites` = dépendances de S (`depends_on`, F-208) **non faites**, calculées **côté serveur depuis le plan** (jamais devinées). Groupement : prérequis indépendants entre eux ⇒ « en parallèle » ; prérequis dépendant d'un autre prérequis ⇒ ordre numéroté « 1 → 2 » (niveaux topologiques).
5. **Cases à cocher + « Lancer »** : cochées par défaut sauf étapes **EXTERNE** (jamais pré-cochées, confirmation explicite) ; « Lancer » **envoie une seule demande visible** dans le fil : prérequis parallèles ensemble, séquentiels dans l'ordre, puis l'étape. Deux EXTERNE ne sont jamais composées « en parallèle » (rétrogradées en séquentiel). Le clic simple sur une option garde le comportement actuel (remplir la saisie).
6. **Téléphone** : colonnes empilées, Chantier en premier.
7. Critère : dans un sujet guidé, la colonne Chantier n'est **jamais vide** tant qu'une étape reste à faire (repli serveur : si le modèle n'a rien rattaché, une option dérivée « Continuer : étape M » est ajoutée) ; une proposition hors plan reste possible à droite.

## Préoccupations transversales
Aucune (pas d'auth, de tenant, de plan tarifaire ni de route). Préfixe stable : la consigne modifiée reste un texte fixe.

## Découpage
| SF | Objet | Risque |
|---|---|---|
| SF-209-01 | Backend : consigne, parseur 7, `column`, plafonds par colonne, ordre, repli « jamais vide », `prerequisites` (niveaux) ; tests | 🟠 |
| SF-209-02 | Front : deux colonnes + en-tête/mini-barre + badges ; prérequis cochables (parallèle / 1 → 2), EXTERNE non pré-cochée + confirmation, « Lancer » ; empilement mobile | 🟠 |

## Hors périmètre
Exécution réellement parallèle imposée au modèle (il reçoit la demande ; le harnais F-195 décide ce qui part en parallèle).
