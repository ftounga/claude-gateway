# F-84 — Le tour survit à son flux

> Cadrage du 2026-09-12, après un constat du PO en production : *« si je sors du terminal pendant
> qu'il réfléchit, quand je reviens il a arrêté de réfléchir. C'est un gros bug, sinon je ne pourrai
> pas montrer le travail en parallèle. »*

## 1. Le défaut, vérifié de bout en bout

1. Changer de route détruit `AtelierComponent` (`ngOnDestroy`) → le flux SSE se ferme.
2. À la première émission suivante, `emitter.send()` lève une `IOException`.
3. `AtelierAgentController:292` la traduit en **`StreamAbortedException`**.
4. Elle est attrapée en `catch (StreamAbortedException | IOException)` → `emitter.complete()`.

**Le tour s'arrête.** Pas « cesse de s'afficher » : il s'arrête. Un clic sur « Forge » tue le travail
en cours.

**Ce n'est pas une découverte.** Le commentaire de SF-39-18, dans `atelier.component.ts`, le décrit
mot pour mot :

> « changer de route détruisait `AtelierComponent`, donc le flux SSE du tour en cours — et une
> demande d'autorisation partie dans un flux mort était refusée au bout de 120 s, sans que personne
> l'ait vue. »

Le diagnostic était juste. **Le correctif s'est arrêté à son propre symptôme** : SF-39-18 a remplacé
un changement de route par un paramètre d'URL **pour l'explorateur de fichiers seulement**. Tous les
autres départs de l'écran tuent toujours le tour.

## 2. Ce que ce seul défaut explique

Trois problèmes rapportés séparément le 2026-09-12 n'en font qu'un.

| Symptôme | Explication |
|---|---|
| L'agent « s'arrête de réfléchir » quand on quitte l'écran | Le tour est abandonné (ci-dessus). |
| **L'invite d'autorisation ne s'affiche pas** | Très probablement **aucun défaut de rendu** : dans un flux mort, la demande n'atteint jamais le navigateur. Les journaux sont **identiques** dans les deux cas — « Autorisation demandée », puis 120 s de silence. C'est ce défaut qui a motivé SF-73-04 (porte désarmée par défaut), mesure d'attente qui pourra être levée. |
| La vue 360 ne montre que les onglets ouverts | Un terminal n'existe que tant qu'un onglet tient son flux. |

**Racine commune : le tour vit dans le flux du navigateur.** Tant que c'est vrai, quitter un écran
tue le travail, une invite peut partir dans le vide, et rien ne peut être observé d'ailleurs.

## 3. Ce que F-84 livre

**Le tour tourne côté gateway ; le flux n'en est qu'une vue** — qu'on peut fermer, rouvrir, et
regarder depuis une autre page.

### SF-84-01 — Fermer un flux n'arrête plus le tour

Un envoi qui échoue **détache le spectateur**, il n'interrompt rien. `StreamAbortedException` cesse
d'être un signal d'arrêt pour devenir un signal de **déconnexion**.

Les événements du tour vont dans un **tampon ordonné** attaché au tour, et l'émetteur n'en est qu'un
consommateur. Le tour se termine parce qu'il a fini, parce qu'il a atteint son plafond, ou parce que
l'utilisateur l'a **interrompu** (F-32 / SF-38-07, geste explicite qui ne change pas) — **jamais**
parce qu'un navigateur est parti.

### SF-84-02 — Revenir sur un tour en cours

En rouvrant le terminal, l'écran **se rebranche** sur le tour : il rejoue ce qu'il a manqué depuis un
**curseur**, puis reprend le direct. Ce qui s'est passé pendant l'absence n'est pas perdu.

Et c'est ce qui rend la mosaïque (F-83) possible : **plusieurs vues, un seul tour.**

### SF-84-03 — L'autorisation ne vit plus dans le flux

Une demande d'autorisation devient un **état du tour**, interrogeable — et non un événement qu'on
rate si l'on n'était pas branché au bon moment. Un écran qui arrive après coup la voit **encore en
attente**.

La **décision**, elle, passe déjà par HTTP et ne change pas.

Conséquence : la porte de confirmation redevient tenable, et **SF-73-04 (le désarmement par défaut,
décidé en urgence le 2026-09-12) pourra être levé** — c'est la condition écrite dans son propre
commentaire.

## 4. La question qui décide de l'architecture

**Où vit le tampon, et comment un spectateur sur un autre pod le lit-il ?**

Sous HPA, le tour tourne sur **un** pod ; une vue rouverte peut arriver sur un **autre**.

**La réponse existe déjà dans le produit, et elle est éprouvée.** `PgNotifyRunnerRegistry`
(SF-38-02 / SF-38-12, ADR-016) fait exactement ce raisonnement pour les runners : chaque replica
tient ses connexions locales, annonce sa **présence et son adresse** (`http://{POD_IP}:8081`) sur un
canal Postgres `NOTIFY`, et les autres pods **relaient** l'appel au pod propriétaire. Trois
mécanismes de convergence sont déjà écrits — ré-annonce périodique, péremption, `SYNC_REQUEST` au
démarrage — et **aucun composant d'infrastructure supplémentaire** n'est requis (pas de Redis dans la
pile).

**Recommandation** : réutiliser ce dispositif. Le tampon vit **en mémoire du pod qui exécute le
tour** ; sa présence et son adresse sont annoncées ; un spectateur arrivé ailleurs est relayé.
La persistance en base reste ce qu'elle est aujourd'hui — `atelier_messages` et sa frontière de
rejeu —, et n'a pas à devenir un journal d'événements.

Écarté : écrire chaque événement en base. C'est le chemin chaud d'un tour, chaque sortie de
commande y passerait, et F-76 avait déjà écarté cette voie pour l'aperçu.

**À CONFIRMER PAR LE PO** — c'est la seule décision de cadrage.

## 5. Hors périmètre

- **Un tour qui démarre sans navigateur** (agent programmé, tour lancé par une API). F-84 fait
  survivre un tour à la fermeture de sa vue, elle n'en crée pas d'autre façon de le lancer.
- Retirer l'interruption explicite : elle reste, c'est le geste par lequel on arrête vraiment.
- Changer le plafond de quatre flux vivants, ni la facturation.
- **F-83 (la mosaïque)**, suspendue jusqu'à F-84 : bâtie sur des flux qui meurent en naviguant, elle
  hériterait du même défaut.

## 6. Impact transversal

| Préoccupation | Composants à vérifier **un par un** |
|---|---|
| **Cycle de vie du tour** | `AtelierAgentController`, `AtelierChatController` (même mécanique, mêmes `StreamAbortedException`), `SseStreamDispatch`, `chatStreamExecutor` |
| **Autorisation** | `RunnerConfirmationGate`, `AtelierChatService.askPermission`, la décision HTTP, le compte à rebours de l'écran |
| **Multi-pod** | `PgNotifyRunnerRegistry`, `RunnerCallDispatcher`, l'adresse `POD_IP` |
| **Places / plafond** | `LiveTerminalService` — une vue rouverte ne doit pas compter comme un flux de plus |
| Frontend | `atelier.component` (`ngOnDestroy`, reprise), le compte à rebours d'autorisation |

## 7. Plan de test minimal

- **Le test qui prouve la feature** : un tour en cours, le flux est fermé brutalement, et le tour
  **va jusqu'au bout** — vérifié côté serveur, pas à l'écran. Il doit échouer contre le code actuel.
- Se rebrancher après une absence : ce qui a été manqué est **rejoué**, sans doublon ni trou.
- Deux vues sur le même tour reçoivent la **même** suite d'événements.
- Une demande d'autorisation **posée pendant l'absence** est encore visible au retour, avec son
  temps restant exact.
- L'interruption explicite arrête toujours le tour.
- Isolation `user_id` sur la reprise : on ne se rebranche jamais sur le tour d'autrui.
