# Audit — Notre Atelier face à Claude Code (Anthropic)

**Date** : 2026-09-06 · **Portée** : F-28 (Atelier), F-30 (Terminal), F-38 (Runner)
**Question posée** : notre agent est-il aussi digne de confiance que Claude Code, notamment sur la
gestion du contexte ? **Hors périmètre assumé** : commandes `/`, plugins, hooks, marketplace.

---

## 0. Verdict en une page

Il n'existe pas *un* agent chez nous mais **deux moteurs différents**, et leur écart de fiabilité est
considérable. C'est le résultat central de cet audit.

| | Mode **Terminal** (cible `SANDBOX`) | Mode **Assistant** (cible `SANDBOX` ou **`RUNNER`**) |
|---|---|---|
| Moteur | **Managed Agents d'Anthropic** — la boucle et le contexte tournent chez le fournisseur | **Boucle maison** (`AtelierChatService`), 340 lignes |
| Modèle | `claude-opus-5`, effort `xhigh` | `claude-opus-4-8`, **sans effort ni thinking** |
| Contexte | Géré par Anthropic (session persistante, compaction serveur) | **Aucune gestion** : historique brut, jamais compacté, jamais mesuré |
| Sortie max | Gérée par le fournisseur | **4 096 tokens** |
| Outils | bash, fichiers, code exec, sous-agents | 4 outils fichiers + `bash` (runner seulement) |
| Écart avec Claude Code | **Faible** — c'est le même harnais, moins les commandes `/` et les hooks | **Important** — voir §2 |

**Le mode Terminal est proche de Claude Code par construction** : nous n'avons pas réimplémenté le
harnais, nous l'appelons. Ce qui manque y est essentiellement de la surface (commandes `/`, hooks,
plugins) — donc hors question posée.

**Le mode Assistant, non.** Et c'est exactement celui que le banc d'essai runner va exercer : en cible
`RUNNER`, le mode Terminal est **désactivé** (`atelier.component.ts:247`, refus backend
`ExecutionTargetModeException`) parce que les Managed Agents exécutent chez Anthropic, hors de portée
de la machine de l'utilisateur. **Le runner passe donc obligatoirement par la boucle maison.**

La crainte exprimée — « il s'emmêle les pinceaux parce qu'il ne gère pas bien son contexte » — est
**fondée pour ce mode**, et deux défauts sont pires que de la dérive : ils produisent un échec
silencieux, et l'un d'eux rend un projet **définitivement inutilisable**.

---

## 1. Ce qui est vérifié, et comment

Deux hypothèses ont été confirmées par appel réel à l'API (`claude-opus-4-8`, coût négligeable) —
elles ne sont pas déduites du code, elles sont observées.

### Preuve 1 — un bloc texte vide dans l'historique est refusé

```
POST /v1/messages  messages:[user, assistant(text:""), user]
→ 400 invalid_request_error : "messages: text content blocks must be non-empty"
```

### Preuve 2 — `max_tokens` atteint pendant un tour d'outil

```
POST /v1/messages  max_tokens:40, tools:[write_file], "écris un poème de 200 lignes"
→ stop_reason = "max_tokens"
→ content = [ text: "Je vais créer ce fichier avec un poème de 200 lignes sur la mer." ]
   (aucun bloc tool_use : la génération a été coupée avant)
```

Ces deux observations se combinent en un défaut en chaîne, décrit au §2.1.

---

## 2. Écarts par rapport à Claude Code — mode Assistant / Runner

### 2.1 🔴 BLOQUANT — le tour coupé par `max_tokens` est traité comme un succès, puis tue le projet

**Le mécanisme, en trois temps :**

1. `AnthropicAgentProvider:137` — `boolean finished = !"tool_use".equals(stopReason);`
   Tout `stop_reason` autre que `tool_use` vaut « terminé ». Or `max_tokens` en fait partie : un tour
   **tronqué** est donc lu comme un tour **fini**.
2. `AtelierChatService:~195` — la boucle sort, garde `turn.text()` (souvent « Je vais créer ce
   fichier… », parfois **vide** si le modèle attaquait directement par un `tool_use`), et **jette les
   appels d'outils**. L'utilisateur voit une phrase d'intention, et rien ne s'est produit. Aucune
   erreur, aucune trace.
3. `AtelierChatService:255` — cette réponse, **vide comprise**, est persistée
   (`content(finalText == null ? "" : finalText)`). Au message suivant, `AtelierChatService:168`
   la relit et la renvoie telle quelle à l'API → **400 à chaque tour** (Preuve 1), rendu à
   l'utilisateur en « Échec de l'appel au fournisseur IA. »

**Conséquence** : le projet est mort. Aucun geste de l'interface n'en sort — il faut supprimer la
conversation en base. Claude Code, dans la même situation, poursuit le tour tronqué et ne persiste
jamais un tour vide.

**Probabilité, pas théorie** : `max_tokens` vaut **4 096** (`AnthropicProperties:44`, non surchargé
en production — vérifié dans la configmap déployée). Or `write_file` transporte **le contenu entier
du fichier dans la sortie du modèle**. Écrire un fichier de plus de ~12 Ko est donc structurellement
impossible, et c'est un geste banal. Claude Code utilise 32 K–64 K de sortie **et** un outil `Edit`
qui n'envoie que le fragment modifié.

### 2.2 🔴 MAJEUR — l'agent perd la mémoire de ses propres actions entre deux messages

`AtelierChatService:166-169` reconstruit l'historique **en texte seul** :

```java
for (AtelierMessage past : messageRepository.findBy...) {
    messages.add(new AgentMessage(role, List.of(new AgentContentBlock.Text(past.getContent()))));
}
```

Les `tool_use` et `tool_result` des tours précédents ne sont **jamais** rejoués. À chaque nouveau
message, l'agent oublie quels fichiers il a lus, ce qu'ils contenaient, quelles commandes il a
lancées et ce qu'elles ont répondu. Il ne lui reste que sa propre phrase de conclusion.

C'est précisément « s'emmêler les pinceaux » : il relit les mêmes fichiers, refait les mêmes
commandes, ou pire — affirme un état du projet qu'il a cessé de voir. Claude Code conserve la
trajectoire complète (et ne la résume que par compaction explicite, en le disant).

### 2.3 🔴 MAJEUR — aucune gestion de fenêtre de contexte, nulle part

Recherche exhaustive sur le backend : **zéro** occurrence de `compact`, `context_management`,
`cache_control`, `count_tokens`. Il n'existe ni compaction, ni édition de contexte, ni mesure de la
taille envoyée. L'historique croît indéfiniment ; le jour où il dépasse la fenêtre, l'appel échoue en
400 générique et le projet entre dans le même cul-de-sac qu'au §2.1.

Le §2.2 masque partiellement ce risque (l'historique texte-seul grossit lentement) : les deux
défauts se compensent par accident, ils ne se corrigent pas.

### 2.4 🟠 IMPORTANT — le modèle travaille sans réflexion étendue

`AnthropicAgentProvider:45-57` ne transmet ni `thinking`, ni `output_config.effort`. Sur
`claude-opus-4-8`, **omettre `thinking` désactive la réflexion**. Les blocs `thinking` renvoyés par
l'API sont par ailleurs ignorés à la lecture (`toTurn`) et absents du réémetteur (`toApiBlock`) :
même activés, ils ne survivraient pas d'un tour à l'autre.

Claude Code tourne en thinking adaptatif à effort `xhigh` — c'est aussi ce que fait notre **mode
Terminal** (`AtelierAgentProperties:94,119`). Le mode Assistant est donc le seul à raisonner à nu, sur
un modèle d'une génération antérieure. Une part de la différence de jugement perçue vient de là.

### 2.5 🟠 IMPORTANT — plafond de 12 allers-retours par message

`AtelierChatService:43` — `MAX_ITERATIONS = 12`. Une tâche réelle (lire 5 fichiers, lancer les tests,
corriger, relancer) épuise ce budget avant d'aboutir ; le tour se clôt sur « J'ai atteint la limite
d'étapes ». Claude Code n'a pas de plafond de ce genre : il s'arrête quand la tâche est finie.
Combiné au §2.2, une tâche « reprise » après plafond repart en aveugle.

### 2.6 🟠 IMPORTANT — pas de cache de prompt

Aucun `cache_control`. À chaque itération, tout est renvoyé au tarif plein : consigne système (jusqu'à
40 000 caractères de `CLAUDE.md` + skills), historique, définitions d'outils. Sur 12 itérations, le
coût croît quadratiquement. Claude Code met en cache l'intégralité du préfixe stable. Ce n'est pas un
défaut de fiabilité, c'est une facture — mais une facture multipliée par 3 à 5.

### 2.7 🟡 À corriger — l'outillage est plus pauvre, et de façon dangereuse

| Claude Code | Nous (Assistant/Runner) | Conséquence |
|---|---|---|
| `Edit` (remplacement de chaîne exacte) | **absent** | toute modification passe par une réécriture intégrale → §2.1 |
| `Read` avec `offset`/`limit` + n° de ligne | lecture entière, tronquée à 512 Ko | un gros fichier est illisible au-delà, sans moyen de lire la suite |
| État de fichier suivi (refus d'écrire un fichier modifié depuis la lecture) | **absent** | une modification faite entre-temps par l'utilisateur ou par une commande est **écrasée en silence** |
| `Grep` (ripgrep, regex, filtres) | sous-chaîne, sans regex ; côté stockage, relit **tous** les fichiers | recherche lente et imprécise |
| `TodoWrite` | **absent** | pas de suivi de plan sur une tâche longue |
| Sous-agents | Terminal seulement — et **désactivés en production** (`APP_ATELIER_AGENT_SUBAGENTS_ENABLED=false`) | pas de fan-out |

### 2.8 🟡 À corriger — robustesse réseau

- **Aucun retry** : un `429` (débit) ou un `529` (surcharge) fait échouer le tour entier
  (`RestClientException` → `AIProviderException`). Claude Code réessaie.
- **Aucun timeout appliqué** : `AnthropicProperties.timeout` (`PT120S`) est déclaré mais n'est câblé
  sur aucun `RestClient` — ni pour l'agent, ni pour le chat. Une requête pendue le reste.
- **En-tête beta périmé** : `files-api-2025-04-14` est encore envoyé
  (`AnthropicManagedAgentProvider:43`) alors que la Files API est sortie de beta.

### 2.9 🟡 Signalé — deux historiques pour un seul projet

Les deux moteurs écrivent dans **la même table** `atelier_messages` sans marquer leur mode. Le mode
Assistant relit tout, y compris les tours du Terminal ; le Terminal ne relit rien (son contexte vit
dans la session Anthropic). Alterner les deux modes donne donc deux mémoires divergentes du même
projet — et un tour Terminal à réponse vide contamine le mode Assistant via le §2.1.

### 2.10 🟡 Signalé — la reprise de session Terminal perd le fil, sans le dire

`AtelierSessionService:320-334` : si la session Managed Agents n'est plus jouable, une session neuve
est ouverte et le message rejoué **une** fois. Les fichiers sont bien remontés, mais **la
conversation antérieure n'est pas réinjectée** : l'agent repart à zéro alors que l'écran, lui, affiche
tout l'historique. Rien ne le signale à l'utilisateur.

---

## 3. Ce qui est au niveau, et mérite d'être dit

- **Isolation multi-tenant** : `requireOwned` en premier geste, partout, sans exception observée.
- **Validation d'action** (F-33/F-38) : `bash` est soumis à autorisation **avant** émission, sans
  possibilité de désactivation en mode runner ; le silence vaut refus, jamais autorisation.
- **Journal d'audit** : une ligne par appel, y compris les refus et les tentatives malformées, sans
  jamais journaliser un contenu de fichier.
- **Budget de session** en dollars, borné par le quota restant (F-36) — Claude Code n'a pas
  d'équivalent côté plateforme.
- **Confinement** : racine imposée, `.runnerignore`, coupe-circuit.
- **Interruption** : `Ctrl-C` logique propagé jusqu'à la commande, y compris entre pods.

Sur la **gouvernance de l'exécution**, nous sommes devant Claude Code, pas derrière. L'écart est sur
le **raisonnement** et la **tenue du contexte**.

---

## 4. Remédiation proposée — par ordre de rendement

Rien de ceci n'est engagé : ce sont des subfeatures à cadrer selon le cycle habituel.

| # | Correctif | Effort | Effet |
|---|---|---|---|
| R1 | Traiter `stop_reason = max_tokens` comme une **erreur explicite** ; ne **jamais** persister un message vide, et filtrer les vides existants à la relecture | ~2 h | supprime le cul-de-sac §2.1 |
| R2 | Monter `max_tokens` (16 K non-streamé / 64 K streamé) | 1 ligne + config | débloque l'écriture de fichiers |
| R3 | Ajouter l'outil `edit_file` (remplacement de chaîne exacte), côté stockage **et** runner | ~1 j | supprime la cause racine de §2.1 |
| R4 | Rejouer la **trajectoire** (tool_use/tool_result) dans l'historique, avec bornage | ~1 j | corrige §2.2 |
| R5 | `thinking: adaptive` + `effort` + modèle `claude-opus-5` sur la boucle maison, blocs thinking rejoués | ~0,5 j | corrige §2.4 |
| R6 | `cache_control` sur consigne système + outils + préfixe d'historique | ~0,5 j | −60 à −80 % de coût |
| R7 | Compaction (beta `compact-2026-01-12`) ou édition de contexte, + mesure via `count_tokens` | ~1 j | corrige §2.3 |
| R8 | Retry 429/529 avec backoff, timeout câblé | ~0,5 j | corrige §2.8 |
| R9 | Relever `MAX_ITERATIONS` (30–50) maintenant que le budget de temps borne déjà le tour | 1 ligne | corrige §2.5 |

**R1 + R2 sont à faire avant le banc d'essai runner** : sans eux, le premier `write_file` un peu long
du banc d'essai produira un faux négatif — et pourra condamner le projet de test.

---

## 5. Réponse à la question posée

> « Est-ce que le nôtre est tout autant digne de confiance ? »

- **Pour exécuter, tracer, autoriser, facturer** : oui, et au-delà.
- **Pour le mode Terminal** : oui — c'est le harnais d'Anthropic, pas une imitation.
- **Pour le mode Assistant, celui du runner** : **non, pas encore**. Ce n'est pas une question de
  finesse de modèle mais de quatre mécanismes absents (troncature non détectée, trajectoire non
  rejouée, contexte non géré, réflexion désactivée). Ils sont identifiés, localisés, et corrigeables
  en quelques jours — R1 et R2 en deux heures.
