# Mini-spec — F-133 / SF-133-09 — Le coût traverse le flux

## Identifiant
`F-133 / SF-133-09`

## Feature parente
`F-133` — Le coût réel Anthropic : par message, par client, par semaine

## Statut
`draft` — correctif de SF-133-02

## Date de création
2026-09-20

## Branche Git
`fix/SF-133-09-le-cout-traverse-le-flux`

---

## Objectif

Faire que le montant apparaisse **là où l'écran le lit vraiment** : dans le flux SSE, où SF-133-02
le laissait disparaître en silence.

---

## Le défaut, et pourquoi il a échappé aux tests

Le PO a lancé un tour depuis le terminal du poste CAGIP et **n'a vu aucun montant**.

Le flux SSE de l'Atelier s'exécute sur un thread d'exécuteur, et le `SecurityContext` de Spring
**n'y est pas propagé**. `TurnCostView.labelFor()` y appelait `AdminService.assertAdmin()`, qui ne
trouvait personne : la garde répondait « pas administrateur », le montant valait `null`, et **rien
ne le signalait** — ni erreur, ni log.

Le contrôleur documentait pourtant le piège, deux lignes au-dessus de l'endroit modifié :

> *« Le gating est résolu ICI (thread de requête) où le SecurityContext est disponible : le relais
> s'exécute sur un thread du pool SSE qui n'hérite pas du contexte de sécurité. »*

Et la mini-spec de SF-133-02 désignait elle-même le flux comme « le point à ne pas oublier ». Le
champ a bien été ajouté au flux ; c'est la **garde** qui n'y fonctionnait pas.

**Pourquoi les tests ne l'ont pas vu** : ils couvraient la réponse **synchrone** et le **relevé
rechargé** — deux chemins qui s'exécutent dans le thread de la requête. Aucun n'exerçait le
changement de thread. Le trou n'était pas dans l'assertion, il était dans le **choix du chemin
testé**.

---

## Comportement attendu

### Cas nominal
1. `POST /chat/stream` résout la qualité d'administrateur **dans le thread de la requête**, comme il
   résout déjà `userId` et l'accès au terminal.
2. Le booléen voyage jusqu'au relais.
3. L'événement `done` porte le montant pour l'administrateur, `null` pour les autres.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Appelant non administrateur | aucun montant, exactement comme avant |
| Contexte de sécurité absent au moment de la décision | aucun montant — jamais d'exception, le tour prime |

---

## Critères d'acceptation

- [ ] Un montant décidé dans le thread de la requête **survit** au passage sur un autre thread.
- [ ] Un montant dont la garde est évaluée **dans** le thread du flux vaut `null` — le défaut est **figé par un test**, pour qu'il ne puisse pas revenir.
- [ ] La nouvelle forme n'est **pas un contournement** : sans décision favorable, aucun montant.
- [ ] La réponse synchrone et le relevé rechargé sont **inchangés**.
- [ ] Aucune exception ne peut remonter d'un calcul de coût : le tour est déjà rendu.

---

## Périmètre

### Hors scope
- Le chemin **MCP** (`AtelierMcpTurnLauncher`, outil `terminal_ecrire`) : le tour y est lancé par un
  agent, sans appelant humain dont on puisse lire les droits. Le montant y reste absent **en
  direct**, et apparaît **au rechargement** grâce au relevé persisté.
- Le chemin **Managed Agents**, qui ne remonte toujours pas son coût (limite connue de SF-133-02).

---

## Technique

| Classe | Changement |
|---|---|
| `TurnCostView` | `labelFor(cost, admin)` et `callerIsAdmin()` — la décision se prend avant le saut de thread |
| `AtelierChatController` | résout `admin` dans `stream()`, le passe à `relay()` |

Aucun endpoint, aucune table, aucune migration.

---

## Plan de test

- [ ] `TurnCostViewThreadTest` — le montant survit au changement de thread.
- [ ] `TurnCostViewThreadTest` — une garde évaluée dans le thread du flux perd le montant (le défaut, figé).
- [ ] `TurnCostViewThreadTest` — sans décision favorable, aucun montant.
- [ ] Suite complète : aucune régression sur les chemins synchrones.

---

## Préoccupations transversales

| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| **Auth / Principal** | **oui** | `TurnCostView`, `AtelierChatController.stream()` et `relay()`. **Toute garde lue depuis un thread d'exécuteur est suspecte** : `AtelierAgentController` et `AtelierMcpTurnLauncher` ont été relus — ni l'un ni l'autre ne lit de droits dans son thread |

---

## Estimation

**0,25 jour.**
