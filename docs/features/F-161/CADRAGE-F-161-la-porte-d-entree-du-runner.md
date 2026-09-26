# Cadrage — F-161 — La porte d'entrée du runner

> Demande du PO, 2026-09-26, après l'étude de la session KPMG :
> *« Par exemple on peut tester que le runner répond en amont ? Avant de consommer les tokens ? »*

## 1. Ce que la mesure dit

Session KPMG du 25/09 (poste CAGIP, projet `agenor`, 74 tours, 81,70 $) :

| Code d'échec | Nombre | Ce que c'est |
|---|---:|---|
| `runner_unavailable` | **20** | le runner n'est pas connecté |
| `unsupported_tool` | **6** | il **est** connecté mais **refuse** d'exécuter (`--no-bash`) |
| `timeout` | 3 | il répond trop tard |
| `cancelled` / `not_found` | 2 | |

**8 tours « Non concluant », ≈ 9 $, soit 11 % de la facture** — dépensés pour *découvrir* que la
machine ne répondait pas.

Le coût est engagé **avant** qu'on le sache : le message part, les 130 000 jetons de contexte sont
envoyés, le modèle raisonne, appelle `bash`, et c'est **là** que l'échec apparaît. Le tour est payé
entier pour produire « je ne peux pas ».

## 2. Les deux savoirs existent déjà — ils sont consultés trop tard

| Savoir | Où | Quand il est lu aujourd'hui |
|---|---|---|
| Le runner est-il **vivant** ? | `RunnerRegistry.isConnected()` + battement (30 s / 90 s) | **au moment d'exécuter un outil** |
| Que **sait-il faire** ? | `RunnerCallDispatcher.capabilities`, déclarées à la connexion | **idem** |

`AtelierChatService` n'appelle **ni l'un ni l'autre** avant d'ouvrir un tour.

**C'est une capacité dormante au sens exact de F-156** : rien à créer, un branchement à poser. Le
diagnostic l'aurait signalée si ces deux capacités figuraient sur la carte — et il faudra les y
ajouter.

## 3. Ce que la porte attraperait

| Contrôle | Attrape | Coût |
|---|---:|---|
| Vivant (socket + battement frais) | **20** | 0 jeton |
| Capacités requises déclarées | **+6** | 0 jeton |
| **Total** | **26 / 31 = 84 %** | **0 jeton** |

Les 6 `unsupported_tool` sont le cas le plus instructif : ce **n'est pas** une instabilité. Le runner
tournait, lancé avec `--no-bash`. Un test de socket ne l'aurait pas vu ; la **déclaration de
capacités**, si. C'est un problème de **lancement**, et il recoupe la note « onboarding trop manuel ».

## 4. L'arbitrage à trancher — et il est structurant

**Tous les tours n'ont pas besoin du runner.** « Donne-moi un message plus court » n'y touche pas.
Bloquer ces tours-là serait insupportable.

| Option | Ce qu'elle donne | Ce qu'elle coûte |
|---|---|---|
| (a) Refuser tout tour sur cible RUNNER quand il est mort | maximum d'économie | bloque les questions pures — inacceptable |
| (b) Laisser passer en prévenant le modèle | réponse utile | **on paie quand même le tour** |
| (c) **Refuser, avec « demander quand même » d'un clic** | économie, et l'utilisateur garde la main | un clic quand la question n'avait pas besoin de la machine |

**Recommandation : (c).** Un runner mort est presque toujours la raison pour laquelle le tour va
décevoir ; un clic coûte moins cher qu'un euro. Mais c'est un choix produit — **à confirmer**.

## 5. Le gain que le PO n'a pas demandé, et qui est le plus gros

Quand le runner tombe **en plein tour**, l'agent reçoit l'erreur, raisonne sur tout le contexte, et
écrit « Non concluant ». **On paie un appel complet pour apprendre ce que la gateway savait déjà.**

Ces messages avaient pourtant de la valeur (« la branche est créée localement sur `main` à jour »).
D'où le compromis : **arrêter la boucle** et rendre un message **déterministe enrichi des étapes déjà
accomplies** — que la gateway suit déjà (plan, journal d'outils). On garde l'essentiel du contenu,
gratuitement.

## 6. Ce qu'on écarte, et pourquoi

- **Le ping systématique avant chaque tour.** Une socket ouverte ne prouve pas que le runner
  exécutera, c'est vrai — mais un aller-retour à chaque tour ajoute de la latence à tous pour
  rattraper les cas que la déclaration de capacités couvre déjà. **Gardé en réserve**, conditionnel
  (seulement si le dernier appel réussi date de plus de N secondes).
- **La reprise du tour après redémarrage.** Le tour vit dans le flux (F-84) ; le reprendre est un
  autre sujet, bien plus lourd.
- **Corriger la cause des déconnexions.** Le runner bat toutes les **30 s**, la gateway tolère
  **90 s** : trois battements de marge, ce n'est **pas** un problème de réglage. Le runner a
  réellement disparu — veille du poste, processus sorti, coupure réseau. **On ne le sait pas**, et le
  deviner produirait un correctif qui ne corrige rien. D'où une subfeature dédiée à le **mesurer**.

## 7. Découpage proposé

| SF | Objet |
|---|---|
| **SF-161-01** | **La porte** : vivant + capacités requises, refus nommé, **zéro jeton**, « demander quand même » |
| **SF-161-02** | **L'arrêt net** quand le runner tombe en plein tour — sans faire commenter le modèle |
| **SF-161-03** | **Le journal des ruptures** : savoir *pourquoi* le runner disparaît, avant de prétendre le réparer |
| **SF-161-04** | *(réserve)* le **ping conditionnel** qui exécute vraiment, si 01 et 02 laissent un trou |

**Ordre** : 01 → 02 → 03, puis 04 **seulement sur mesure**.

## 7 bis. Clôture du découpage — arbitrage du 2026-09-26

Le découpage annonçait « 01 → 02 → 03, puis 04 **seulement sur mesure** ». Les trois premières sont
livrées. Deux décisions closent la feature.

### SF-161-05 (ajoutée) — les deux savoirs entrent sur la carte
Le §2 s'engageait à ajouter à la carte de F-156 la vivacité et les capacités déclarées : « il
faudra les y ajouter ». C'était plus qu'une formalité — **une capacité hors carte se fait détacher
sans que rien ne casse** (F-157). Livrée, avec un mur instructif : la carte suppose que toute
capacité laisse une trace, or **les deux capacités de F-161 n'en laissent aucune par
construction** — la porte réussit quand un tour *ne s'ouvre pas*, le journal quand il n'y a rien à
consigner. D'où le verdict **BRANCHÉE** : jugée par ses seuls témoins, gratuitement.

### SF-161-04 (ping conditionnel) — **maintenue en réserve, non livrée**
La condition posée au §7 est « **seulement sur mesure** », et le §6 la garde « conditionnelle ». La
mesure existe désormais — c'est SF-161-03 — mais elle est **vide** : le journal date du jour.

**Livrer 04 maintenant serait faire exactement ce que le §6 refuse** : ajouter une latence à *tous*
les tours pour rattraper un trou qu'aucun fait n'atteste. On ne remplace pas une hypothèse sur la
cause par une hypothèse sur le remède.

**Condition de réouverture, écrite pour ne pas être devinée plus tard** : des ruptures consignées
**avec des appels en vol** alors que la porte venait de dire « vivant » — c'est le seul cas que 01
(l'entrée) et 02 (l'arrêt net) ne couvrent pas, et le seul que le ping rattraperait. Tant que
`runner_disconnects` ne le montre pas, 04 reste en réserve.

## 8. Garde-fous
- **Aucun jeton** consommé par la porte : c'est tout son intérêt.
- **Isolation** `user_id` : le poste lu est celui du projet, déjà vérifié par `requireOwned`.
- **Aucune migration** attendue pour 01 et 02 ; SF-161-03 en demandera une (le journal).
- **Ne jamais bloquer un tour qui n'a pas besoin du runner** sans laisser la main à l'utilisateur.
