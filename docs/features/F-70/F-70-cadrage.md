# Cadrage — F-70 — Plusieurs terminaux, et l'on voit lesquels vivent

**Date** : 2026-09-12
**Source de vérité** : `docs/PRODUCT_SPEC.md`, ligne F-70 — les décisions du PO y sont **déjà prises**
et ne sont pas rouvertes ici.

---

## Ce que le PO a tranché (recopié, non discuté)

1. **Plusieurs flux réellement vivants, quatre au maximum.**
2. Au-delà : **refus explicite** — « quatre terminaux actifs au maximum, fermez-en un » — **jamais**
   une mise en veille silencieuse. Un agent qu'on croit actif et qui dort est pire qu'un refus.
3. **Ce que cela engage, dit à l'écran** : quatre flux vivants = **quatre consommations simultanées**,
   donc quatre tours facturés en parallèle. Le plafond est un **garde-fou de dépense**, pas une
   limite technique.
4. **Le signe de vie** : une **pastille** *et* le mot « connecté » dans la barre du terminal, **et la
   même pastille sur la carte du poste**. Même signe aux deux endroits, **jamais la couleur seule**.
5. Le signe de connexion **n'entre en concurrence** ni avec la couleur d'identité du client
   (SF-49-03, §9) ni avec celle de l'état de mission (F-60, §10).
6. **Hors périmètre** : un agent qui travaille pendant que l'onglet est fermé.

---

## Ce qui existe déjà (constaté dans le code)

| Fait | Où | Conséquence pour F-70 |
|---|---|---|
| Un terminal = `/atelier/:id`, un seul composant monté ; changer de route le détruit et tue son flux | `atelier.component.ts` (`ngOnDestroy`, commentaire SF-39-18) | « Plusieurs terminaux » = **plusieurs onglets navigateur**, pas plusieurs panneaux dans la page. Rien ne les coordonne aujourd'hui. |
| Le flux d'un tour est un `SseEmitter` servi sur `chatStreamExecutor` | `AtelierChatController.stream`, `ChatStreamConfig` | Voir le **constat technique** ci-dessous : le pool ne laisse pas vivre quatre flux. |
| `GET /runner-hosts/overview` agrège postes + projets + activité, rafraîchi toutes les 15 s, **sans** aucun canal ouvert | `RunnerHostOverviewService` | C'est le porteur naturel de la pastille sur la **carte du poste**. |
| Trois registres de couleur cohabitent : statut §5, identité de poste §9, état de mission §10 | `DESIGN_SYSTEM.md` | **Interdit d'en ajouter un quatrième.** |
| L'écran `/forge` (ex-`/postes`) affiche déjà une pastille « Connecté / Hors ligne » — celle du **runner** | `postes.component.html` | Le mot « connecté » y est déjà pris par autre chose : le libellé du signe de vie doit lever l'ambiguïté (« Terminal connecté »). |

---

## Constat technique demandé — quatre flux simultanés

### 1. Le pool SSE ne laisse pas vivre quatre flux — le défaut le plus grave, et il est déjà là

`ChatStreamConfig` : `corePoolSize = 2`, `queueCapacity = 50`, `maxPoolSize = 8`.
Sémantique de `ThreadPoolExecutor` : on crée des threads **jusqu'à `core`**, puis **on remplit la
file**, et on ne monte vers `max` **qu'une fois la file pleine**. Avec une file de 50, la 3ᵉ et la 4ᵉ
requête de flux sont donc **mises en attente silencieuse** : l'`SseEmitter` est bien rendu au
navigateur, la connexion est ouverte, l'écran affiche « en cours »… et **rien ne démarre** tant qu'un
des deux flux en tête n'a pas fini. C'est **exactement** le « agent qu'on croit actif et qui dort »
que le PO refuse — et c'est aujourd'hui le comportement par défaut, pour le chat comme pour la Forge
(le pool est partagé avec `ChatController`).

**Conséquence retenue** : F-70 ne peut pas se contenter d'un compteur ; elle doit rendre les quatre
flux **réellement** parallèles. Le pool passe en remise directe (file de capacité 0 ⇒
`SynchronousQueue`), `core` à 8 et `max` à 32, threads au repos recyclés. Un dépassement devient un
**refus dit dans le flux** (`error: stream_busy`), jamais une attente muette.

### 2. Relais inter-pods (SF-38-12/13) — pas de mur, une file à connaître

- Les appels d'outil (`tool_call`) sont **dirigés** vers le pod qui tient la socket du runner, et
  chacun consomme une connexion HTTP sortante avec un *read timeout* de 135 s. Quatre flux vivants =
  au plus quatre appels dirigés en vol par utilisateur : `RelayPeerClient` / `RunnerRelayClient`
  ouvrent une connexion par appel (`SimpleClientHttpRequestFactory`), sans pool borné — aucun
  plafond n'est atteint à cette échelle.
- Les **gestes diffusés** (annuler, confirmer, interrompre) passent par `RunnerRelayBroadcaster`, qui
  tient un `newFixedThreadPool(4)` **par pod, tous utilisateurs confondus**, chaque tâche bornée à
  `broadcastTimeoutMs = 3 s`. Avec 2 pairs, une confirmation = 2 tâches. Quatre confirmations
  simultanées = 8 tâches sur 4 threads = **2 tours de 3 s au pire, soit ~6 s** avant que la porte ne
  soit tranchée partout. La fenêtre de confirmation étant de 120 s, cela ne casse rien — mais la
  dégradation suit le nombre **total** de terminaux vivants sur le pod, pas celui d'un utilisateur.
  **Constat noté, non corrigé dans F-70** : le remède (pool dimensionné sur le nombre de pairs) est
  une modification du relais, hors sujet ici, et rien ne le rend urgent à 4.
- Le registre des terminaux vivants **ne passe pas par le relais** : il vit en base. C'est ce qui le
  rend juste sous HPA — un compteur en mémoire ne compterait que les terminaux du pod qui répond.

### 3. Limites du navigateur — dépend du transport, et seul le dev est exposé

Le front ne se sert pas d'`EventSource` mais de `fetch` + `ReadableStream` (`atelier.service.ts`).

- **En production** : `portal.ng-itconsulting.com` est servi en TLS par l'ingress nginx
  (`k8s/base/ingress/ingress.yaml`, `cert-manager`), donc **HTTP/2 négocié** : les flux sont
  multiplexés sur une seule connexion TCP et le plafond historique de 6 connexions par origine **ne
  s'applique pas**. Les délais sont déjà alignés pour des flux longs (`proxy-read-timeout: 900`).
- **En développement** : `ng serve` proxie vers `http://localhost:8080` en **HTTP/1.1**. Là, le
  plafond de ~6 connexions par origine mord : 4 flux vivants + le rafraîchissement 15 s de `/forge`
  + le sondage du statut runner saturent la fenêtre, et les appels ordinaires attendent. **C'est
  aussi ce qui interdit de donner au signe de vie son propre flux SSE** : ce serait un 5ᵉ à 8ᵉ canal
  permanent pour transporter un booléen.

**Conséquence retenue** : le signe de vie est porté par un **battement de cœur HTTP court**
(~30 s), pas par un canal ouvert. Coût nul en connexions persistantes, juste sous HPA, et il survit
à une coupure (une fiche non rafraîchie expire d'elle-même).

---

## Découpage

| SF | Titre | Portée |
|---|---|---|
| **SF-70-01** | Le registre des terminaux vivants | Table `live_terminals` (migration **071**), prise / renouvellement / libération d'une place, plafond **4** avec refus explicite `terminal_limit_reached`, enrichissement de la vue d'ensemble, et **remise à plat du pool SSE** pour que quatre flux tournent vraiment. |
| **SF-70-02** | Le signe de vie, aux deux endroits | Battement de cœur depuis le terminal, pastille + « connecté » dans la barre, **même** pastille sur la carte du poste, refus explicite au 5ᵉ avec ce qu'il engage (quatre consommations simultanées), §11 de la charte. |

Backend mergé **avant** le frontend.

---

## Arbitrages (gates réversibles — décidés et tracés)

| # | Gate | Décision | Alternative écartée | Réversible |
|---|---|---|---|---|
| A1 | Où vit le compteur des terminaux vivants | **En base** (`live_terminals`), battement de cœur + expiration | Registre en mémoire + diffusion par le relais : ne survit ni au redémarrage d'un pod, ni à l'HPA ; et il faudrait diffuser un geste à chaque battement | Oui — table additive |
| A2 | Ce qui **prend** une place | **Un onglet de terminal ouvert** (identifié par un `sessionId` de `sessionStorage`) | Un tour en cours : le PO demande un signe de vie **visible quand rien ne tourne** (« connecté »), et le hors-périmètre (« agent qui travaille onglet fermé ») confirme que la vie est liée à l'onglet | Oui |
| A3 | Ce que fait le 5ᵉ terminal | **Il s'ouvre, se lit, mais n'envoie pas** — bandeau de refus nommant les quatre vivants | Refuser l'ouverture de l'écran : on ne pourrait plus relire son historique ; ou laisser envoyer : le plafond ne serait plus un garde-fou de dépense | Oui |
| A4 | Que faire si la prise de place **échoue autrement** (réseau, 500) | **Ne rien bloquer** : pas de pastille, pas de bandeau, l'envoi reste possible. Seul le 409 explicite bloque | Bloquer sur toute erreur : une panne de la gateway interdirait de travailler | Oui |
| A5 | Couleur du signe de vie | **Aucune couleur propre** : pastille **monochrome** (`currentColor`) + le mot écrit, avec une pulsation. Charte §11 | Une 4ᵉ famille de couleur : explicitement interdit par la commande et par §9 / §10 | Oui |
| A6 | Libellé sur la carte du poste | **« Terminal connecté »** / « N terminaux connectés » | « Connecté » seul : le mot est déjà pris sur cette carte par l'état du **runner** | Oui |
| A7 | Pool SSE `core = 2` | **Remise directe** : `queue = 0`, `core = 8`, `max = 32`, rejet dit dans le flux | Laisser tel quel : les flux 3 et 4 dormiraient, ce que le PO refuse nommément | Oui — trois propriétés de configuration |

Aucun gate irréversible, aucun gate de sécurité : rien n'est supprimé, aucune clef n'est exposée,
la table est additive et son changeSet porte son rollback.

---

## Hors périmètre (rappelé)

- Un agent qui travaille pendant que l'onglet est fermé.
- Plusieurs terminaux **dans la même page** (panneaux côte à côte) : le PO parle de flux vivants, pas
  de disposition d'écran.
- Agir sur plusieurs postes à la fois (déjà hors périmètre de F-49).
- Redimensionner le pool de diffusion du relais (constat noté, § ci-dessus).
