# SF-125-06 — La promotion est un effet de bord serveur ; le modèle ne la comptabilise plus

> Cadrage du 2026-09-17 (PO). **Cadrage seul : livraison sur go.** Correctif **de fond** (racine) du
> thème F-125 — il **remplace** l'approche « durcir cas par cas » (SF-125-01/05), qui traitait des
> symptômes.

## 1. La cause racine (confirmée dans le code)
Le paquet de gouvernance injecté à l'agent — `backend/src/main/resources/governance/savoir-durable/GOUVERNANCE.md:30-31` — applique **à CHAQUE fin de tour** :
- **`juge-fin-de-tour`** : sanctionne « une réponse **sans marqueur de fin de tour**, ou qui déclare du durable non promu » ;
- **`promotion-dette-bloquante`** : sanctionne « une clôture alors qu'une case reste non cochée ».

Conséquence : **on OBLIGE le modèle, à chaque tour, à émettre un marqueur et à faire sa comptabilité de
promotion/dette.** Sa « pensée de clôture » tourne donc **toujours** autour de la promotion — quel que
soit le type de question. Quand il n'y a rien à ranger, cette comptabilité **devient la réponse**
(« rien à ranger », « aucun fait durable », « ce tour n'était qu'un conseil/une explication »). C'est
**systémique**, pas lié au type de demande — d'où l'échec de l'approche par cas.

## 2. Objectif (une phrase)
Faire de la tenue de la carte un **effet de bord serveur, silencieux** : le modèle **répond à la
question et écrit dans la carte quand il y a lieu** — **plus aucune comptabilité de promotion imposée**
(ni marqueur `fin-de-tour`, ni déclaration de dette) dans sa sortie.

## 3. Ce qui change
1. **Côté modèle (prompt + paquet `savoir-durable`)** : retirer l'obligation d'**émettre un marqueur
   `fin-de-tour`** et de **déclarer promotion/dette**. Reformuler `GOUVERNANCE.md`, `plan-dashboard.md`,
   les gabarits `STATE.md`/`PLAN-ACTION.md` pour qu'ils **n'instruisent plus** le modèle de comptabiliser.
   Le rôle du modèle = **répondre + écrire dans la carte** quand un fait durable apparaît.
2. **Côté contrôles de fin de tour** : `juge-fin-de-tour` **ne sanctionne plus l'absence de marqueur** ;
   `promotion-dette-bloquante` ne s'appuie plus sur un marqueur émis par le modèle. Le suivi de la
   **promotion et de la dette se fait uniquement côté serveur, à partir des écritures de fichiers
   réelles** (généralise SF-125-02). Aucune sortie modèle liée à la promotion.
3. **Effet garanti** : il **n'existe plus aucun mécanisme** qui produise un « rien à ranger » — pour
   **aucun** type de question.

## 4. Ne pas jeter le bébé avec l'eau du bain
La **fonction utile** (que le savoir durable finisse bien **rangé dans la carte**) **reste** — mais elle
est garantie **par la détection serveur des écritures**, pas par un **rituel du modèle**. Si un fait
durable n'est pas écrit, c'est un manque **de fond** (couvert par la discipline F-119 : vérifier, ranger
ce qui est établi), pas un manque de marqueur. Au besoin, un **rappel doux, non bloquant, côté serveur**
(jamais dans la réponse à l'utilisateur) peut subsister — décision de la mini-spec.

## 5. Coexistence
- **F-126** : le marqueur **`<<essentiel>>`** (contenu à afficher) est **différent** du marqueur
  `fin-de-tour` (métadonnée de gouvernance) — il **reste** intact.
- **F-125-01/02/03/04** : cohérents et conservés (SF-125-02, suivi serveur, est le socle de ce lot).
- **SF-125-05** (durcissement conseil) : devient une **ceinture redondante** — on la **garde**
  (inoffensive), mais elle n'est plus la ligne de défense principale.

## 6. Découpage
| SF | Titre | Contenu |
|---|---|---|
| **SF-125-06a** | **Le modèle ne comptabilise plus** | Prompt (`buildSystemPrompt`) + paquet `savoir-durable` (`GOUVERNANCE.md`, `plan-dashboard.md`, gabarits) : retrait de l'obligation de marqueur `fin-de-tour` et de déclaration promotion/dette ; le rôle se limite à répondre + écrire. Version du paquet incrémentée (re-seed). |
| **SF-125-06b** | **Suivi promotion/dette 100 % serveur** | `juge-fin-de-tour` ne requiert plus de marqueur ; `promotion-dette-bloquante` + `GovernanceMapGrowth`/`GovernanceMapDestinations` s'appuient **exclusivement** sur les écritures réelles (généralise SF-125-02) ; rappel éventuel **non bloquant** et **hors réponse**. |

**Ordre** : SF-125-06a → SF-125-06b (en séquence, mêmes fichiers).

## 7. Critères d'acceptation
- Aucune consigne, dans le prompt ni le paquet `savoir-durable`, n'oblige le modèle à émettre un marqueur
  `fin-de-tour` ou à déclarer promotion/dette.
- `juge-fin-de-tour` ne sanctionne plus une réponse « sans marqueur ».
- La promotion/dette est calculée depuis les écritures de fichiers (test : un fait écrit dans une fiche →
  promu ; rien écrit → aucune sortie « rien à ranger » possible).
- Non-régression : la carte est toujours **alimentée** (les écritures sont détectées) ; F-126
  (`<<essentiel>>`) intact ; discipline F-119 intacte.

## 8. Hors périmètre
- Le **fond** de la cartographie (F-119) et la distinction question/action (F-120) : inchangés.
- Retirer la carte du poste elle-même : non — on garde la carte, on retire seulement le **rituel de
  comptabilité imposé au modèle**.

## 9. Préoccupations transversales
- **Composants** : `savoir-durable/*` (paquet re-seedé, version incrémentée — `GovernancePackageSeeder`),
  `GovernanceEndOfTurnCheckpoint`, `control/JugeFinDeTourControl`, `control/PromotionDetteBloquanteControl`,
  `GovernanceMapGrowth`, `GovernanceMapDestinations`, `buildSystemPrompt`. Aucune migration de données
  (paquet re-seedé au démarrage). Auth/tenant/routing : non touchés.
