# Cadrage — F-176 Le parcours du sujet (investigation → plan → exécution → vérification)

> **Cadrage validé par le PO le 2026-10-04** (décisions Q1→Q4 tranchées, §6). Livré après F-175. Source de vérité produit : `docs/PROJECT.md`.

## 1. La question du PO

*« Est-ce que ça n'a pas de sens de dire que pour un sujet il faut obligatoirement un plan d'action ?
Une première partie d'investigation, jusqu'à être sûr de manière assez élevée de comment fixer le
problème, et avant de faire toute chose, un plan d'action. »* Puis les deux objections du PO :
1. **On n'a pas tous les inputs au départ** (ex. le sujet avec Gino) : impossible de planifier dès le début.
2. **Certains sujets ne sont qu'un petit geste** : un plan serait disproportionné.
Et sa proposition : un **menu dans le terminal** pour choisir le parcours guidé ou le travail libre.

## 2. Ce que disent les données

- Audit des incidents (F-172, 194 incidents) : vérification 56, compréhension 33, raisonnement 22.
  Une bonne part relève d'une action engagée **avant** d'avoir compris (défaut connu « suppose au
  lieu de vérifier »).
- Plans réels : sur 30 jours, **6 terminaux actifs sur 19** ont écrit un `PLAN-ACTION.md`. Le plan
  existe comme **doctrine** (F-166) et comme mode Plan (réponse sans outils d'écriture), jamais
  comme **règle** : une doctrine se fait ignorer.

## 3. Le principe : le plan se construit, il ne se décrète pas

Les deux objections tombent si l'on change deux choses :

**a) La porte est posée devant l'action, pas devant le sujet.** On n'exige pas un plan pour
*ouvrir* un sujet, ni pour *investiguer* (la lecture reste libre, sans limite). On l'exige au
moment où l'agent veut **modifier** quelque chose chez le client. Un petit geste passe donc par un
**plan d'une ligne**, et une question n'en demande aucun.

**b) Le plan peut être partiel.** Un sujet sans tous les inputs ne reste pas bloqué : le plan dit
ce qui est sûr, et les inconnues **sont des étapes** (« obtenir de Gino X », reliée à une attente
F-175 en état *Demandé*). On exécute les étapes sûres, le reste attend son input. Le sujet affiche
« Investigation · en attente de 2 inputs » au lieu de faire semblant d'avancer.

## 4. Le parcours proposé

**Deux modes par sujet**, visibles et changeables à tout moment dans le menu du terminal :

| Mode | Pour quoi | Ce qui change |
|---|---|---|
| **Libre** | question, petit geste, exploration | Comportement actuel. Garde-fous existants (confirmations F-33/F-47). |
| **Guidé** | chantier, incident, changement d'infra, sujet de plusieurs jours | Les quatre phases et la porte de plan ci-dessous. |

**Le choix du mode** : au premier tour d'un sujet, l'agent **qualifie** la demande (question /
geste / chantier). S'il juge que c'est un chantier, il le dit et propose le mode Guidé par une
carte *[Passer en guidé] [Rester libre]*. L'utilisateur peut aussi le choisir lui-même à tout moment.

**Les phases (mode Guidé)**, affichées dans l'en-tête du terminal :

```
Investigation  →  Plan  →  Exécution  →  Vérification  →  Clos
      ↑______________|__________|______________|   (une découverte qui contredit le diagnostic
                                                     renvoie en Investigation ; le plan v1 est gardé)
```

| Phase | Ce qui s'y fait | Pour en sortir |
|---|---|---|
| **Investigation** | Lecture libre. Le `PLAN-ACTION.md` démarre en *plan d'investigation* : objectif, ce qu'on sait (avec preuves), hypothèses, questions ouvertes. Les inputs manquants deviennent des attentes F-175. | L'agent pose un **diagnostic** avec ses preuves et un **niveau de confiance** ; plus d'inconnue bloquante (ou elle est isolée dans une étape). Il propose « prêt à planifier » ; l'utilisateur confirme. |
| **Plan** | Étapes, chacune avec : l'action, sa **classe de risque**, comment la **vérifier**, comment **revenir en arrière**, et ce dont elle dépend (attente). | L'utilisateur **valide le plan** (un clic). |
| **Exécution** | L'agent n'exécute que des étapes du plan validé. Une modification hors plan = un **amendement** montré et validé. | Toutes les étapes exécutables faites (les étapes bloquées restent visibles). |
| **Vérification** | Chaque étape est vérifiée comme le plan le dit, preuves à l'appui. | Vérifs vertes → **Clos** ; une vérif rouge → retour Investigation ou amendement. |

**La porte, par classe de risque** (mode Guidé ; lecture toujours libre) :

| Classe | Exemples | Avant plan validé |
|---|---|---|
| Lecture | `read_file`, `grep`, `kubectl get`, `terraform plan`, API en GET | libre |
| Notes du sujet | `STATE.md`, `PLAN-ACTION.md`, notes, carte | libre |
| Modification réversible | édition dans une branche, fichier local | bloquée (*cf. décision ouverte Q2*) |
| Externe ou irréversible | `push`, merge, `apply`, `kubectl apply/delete`, envoi de mail, prod | bloquée |

La porte est **tenue par le harnais** (comme la porte de fin de tour), pas par la consigne. La
classification réutilise ce qui existe (politique de permission `bash` F-33, garde de commande) et
reste prudente : une commande non classée est traitée comme une modification.

## 5. Découpage pressenti (après validation)

| SF | Titre |
|---|---|
| SF-176-01 | Le mode et la phase d'un sujet (état en base, menu, en-tête) |
| SF-176-02 | La qualification au premier tour et la carte *[Passer en guidé] [Rester libre]* |
| SF-176-03 | Le plan structuré (`PLAN-ACTION.md` + panneau : étapes, risque, vérif, retour arrière, dépendances ↔ attentes F-175) |
| SF-176-04 | La porte par classe de risque, amendements |
| SF-176-05 | Les transitions (diagnostic + confiance, validation, retour en Investigation) |
| SF-176-06 | La mesure (incidents, relances du PO, retours arrière, avant/après) |

Dépend de F-175 (les attentes) pour les inputs manquants.

## 6. Décisions (tranchées par le PO le 2026-10-04)

- **Q1 — Mode par défaut** : **Libre**, et l'agent propose le mode Guidé par une carte *[Passer en guidé] [Rester libre]* quand il qualifie la demande de chantier.
- **Q2 — La porte (mode Guidé)** : bloque **toute modification hors des notes du sujet** (édition en branche comprise) tant que le plan n'est pas validé ; lecture et notes restent libres.
- **Q3 — Validation** : **un clic valide le plan entier** ; chaque amendement (modification hors plan) est montré et revalidé.
- **Q4 — Mode Libre** : **inchangé** (garde-fous existants seulement).
