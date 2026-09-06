# Mini-spec — F-39 / SF-39-17 — Un tour long ne se perd plus

## Identifiant

`F-39 / SF-39-17`

## Feature parente

`F-39` — L'Atelier comme harnais (**rouverte** le 2026-09-06)

## Statut

`ready`

## Date de création

2026-09-06

## Branche Git

`feat/SF-39-17-un-tour-long-ne-se-perd-plus`

---

## Objectif

> Faire qu'un tour de plusieurs minutes reste **visible du début à la fin** : la connexion ne doit
> plus être coupée par l'ingress, la transcription doit survivre à un rechargement, et le serveur
> doit laisser une trace de ce qu'il a fait.

---

## Déclencheur

**Banc d'essai du 2026-09-06, troisième et quatrième défauts.** L'agent construisait le projet
correctement — sept étapes de procédure, 2,9 Mo écrits sur la machine — puis l'écran s'est figé.
Aucun message, aucune erreur, plus rien. Un rechargement n'a rien rendu : la dernière ligne visible,
et c'est tout.

Trois causes distinctes, un seul symptôme, et aucune ne se voit en CI.

### 1. L'ingress coupe la connexion à deux minutes

| Délai | Valeur | Posé par |
|---|---|---|
| Budget d'un tour | 600 s | la boucle (`TURN_BUDGET_MS`) |
| Flux SSE | 900 s | le contrôleur (`STREAM_TIMEOUT_MS`) |
| **`proxy-read-timeout`** | **120 s** | **l'ingress nginx** |

Le budget de tour avait été calibré pour rendre la main **avant** l'expiration du flux — c'est écrit
dans le code : *« la boucle rend la main avant que le flux expire »*. Personne n'a vu qu'un tiers
coupait à 120 s. Or un flux SSE reste silencieux entre deux événements : pendant un `npm install`,
pendant que le modèle réfléchit. Deux minutes de silence suffisent, et nginx ferme.

Conséquence : **tout tour dépassant deux minutes perd son écran**, quel que soit le moteur. Ce qui
rend inutilisable exactement ce que F-39 venait de rendre possible — les tours longs.

### 2. La transcription de la boucle maison n'est pas persistée

Le code le dit lui-même :

> *« La liste de blocs est **vide** : persister la transcription de la boucle maison est un autre
> sujet qui n'appartient pas à ce lot. »* (`AtelierTurnReport`)

Seul le chemin Managed Agents renseigne `terminal_json.blocks`. Sur la boucle maison — **celle qui
exécute réellement** — les commandes et leurs sorties ne vivent que dans le flux SSE. Il tombe, elles
sont perdues.

C'est **l'acquis §4 n°7** (« transcription conservée, survit au rechargement ») qui ne vaut pas pour
le moteur qui travaille. Même défaut que les acquis n°5 et n°6, corrigés par SF-39-15 sans que
celui-ci le soit. Le cadrage est explicite : *« toute régression y est bloquante »*.

### 3. Le serveur ne journalise rien pendant un tour

Les logs du pod s'arrêtent à son démarrage. Diagnostiquer l'incident a demandé de lire l'horodatage
des fichiers sur la machine de l'utilisateur — la seule trace disponible.

C'est mot pour mot le défaut que **SF-08-03** avait corrigé pour le dépôt de documents : *« les refus
étaient journalisés en `debug` : invisibles en production. Un "ça ne marche pas" se diagnostiquait à
l'aveugle. »* La boucle d'agent a le même trou.

---

## Comportement attendu

### 1. La connexion tient

`proxy-read-timeout` et `proxy-send-timeout` passent à **900 s**, alignés sur le flux SSE, qui reste
la borne. La chaîne de délais redevient cohérente : **tour (600 s) < flux (900 s) = ingress (900 s)**.

L'ordre importe : c'est la boucle qui doit rendre la main la première, en disant pourquoi.

### 2. La transcription survit

La boucle maison enregistre ses blocs dans `terminal_json`, comme le fait déjà le chemin Managed
Agents : une entrée par appel d'outil — l'outil, la commande ou le chemin, la sortie, l'issue.

**Bornée**, parce qu'un tour de trente étapes qui installe un projet entier produit des mégaoctets :

| Borne | Valeur | Pourquoi |
|---|---|---|
| Blocs conservés | 200 | Au-delà, on ne relit plus, on cherche |
| Sortie par bloc | 4 000 caractères, **la fin conservée** | Le code de sortie et le message d'erreur sont à la fin ; garder le début reviendrait à mémoriser la question sans la réponse |
| Blocs écartés | comptés et **dits** | `omittedBlocks`, champ déjà présent et déjà lu par l'écran |

### 3. Le serveur dit ce qu'il fait

Au niveau `info`, une ligne par tour à son **ouverture** et à sa **fermeture** — jamais une par
itération, ce qui noierait le journal :

```
Tour d'atelier ouvert (workspace=…, moteur=RUNNER, plafond=30 étapes)
Tour d'atelier terminé en 8 étapes, 412 s, 1,2 M tokens — arrêt : budget de temps
```

Aucun contenu : ni commande, ni sortie, ni chemin. Le journal dit **ce qui s'est passé**, pas ce qui
a été lu — c'est la règle posée par SF-38-08 pour l'audit, et elle vaut ici.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Transcription trop volumineuse | Bornée, blocs écartés comptés, jamais tronquée en silence |
| Échec de sérialisation | Le tour est conservé sans transcription ; jamais d'échec du tour |
| Tour interrompu ou arrêté sur une borne | La transcription **partielle** est conservée : c'est justement là qu'on veut la relire |
| Client déconnecté en cours de tour | Le travail continue ; au rechargement, la transcription est là |

---

## Critères d'acceptation

- [ ] `proxy-read-timeout` et `proxy-send-timeout` valent **900**, et la chaîne tour < flux ≤ ingress
      est vérifiée par lecture des trois constantes.
- [ ] Un tour de la boucle maison persiste ses blocs dans `terminal_json`.
- [ ] Un rechargement après coupure montre les commandes et leurs sorties.
- [ ] La transcription est bornée à 200 blocs et 4 000 caractères par sortie, **fin conservée**.
- [ ] Les blocs écartés sont comptés dans `omittedBlocks`.
- [ ] Un tour **interrompu** conserve sa transcription partielle.
- [ ] Une ligne `info` à l'ouverture et à la fermeture de chaque tour, **sans aucun contenu**.
- [ ] Un échec de sérialisation ne fait jamais échouer le tour.
- [ ] Isolation `user_id` inchangée.
- [ ] Aucune régression sur le chemin Managed Agents, qui persiste déjà sa transcription.

---

## Périmètre

### Hors scope

- Le format d'affichage de la transcription : l'écran sait déjà lire `terminal_json.blocks`
  (SF-30-02, SF-30-09). On lui donne ce qu'il attend, on ne change pas son rendu.
- La reprise d'un flux SSE interrompu (reconnexion, `Last-Event-ID`) : c'est un autre sujet, plus
  large, et la persistance suffit à ne plus rien perdre.

---

## Technique

### Fichiers impactés

| Fichier | Changement |
|---|---|
| `k8s/base/ingress/ingress.yaml` | `proxy-read-timeout` et `proxy-send-timeout` à 900 |
| `atelier/AtelierTurnReport` | Accepte des blocs réels, avec leurs bornes |
| `atelier/AtelierChatService` | Accumule la transcription du tour ; journalise ouverture et fermeture |

### Migration Liquibase

- [x] **Non applicable** — `terminal_json` existe depuis F-30 / SF-30-09 et porte déjà ce format.

---

## Préoccupations transversales

| Préoccupation | Concernée | Composants impactés |
|--------------|-----------|--------------------|
| Auth / Principal | Non | — |
| Contexte tenant | Non | La transcription est écrite sur le message du tour, déjà filtré par `user_id` |
| Plans / limites | Non | Aucune consommation, aucun gate ; la transcription n'est pas renvoyée au modèle |
| Navigation / routing | Non | — |
| **Confidentialité** | **Oui** | Le journal serveur ne porte **aucun contenu** : ni commande, ni sortie, ni chemin. Même règle que l'audit runner (SF-38-08). La transcription, elle, contient les sorties — mais elle vit dans la ligne du message, déjà protégée par l'isolation, et déjà purgée à la suppression de compte (SF-11-03). |

---

## Plan de test

### Tests unitaires

- [ ] Un tour avec appels d'outils persiste ses blocs.
- [ ] Plus de 200 blocs ⇒ 200 conservés, le reste compté dans `omittedBlocks`.
- [ ] Sortie de plus de 4 000 caractères ⇒ **la fin** est conservée.
- [ ] Tour interrompu ⇒ transcription partielle conservée.
- [ ] Échec de sérialisation ⇒ tour conservé, transcription nulle.
- [ ] Le journal ne contient ni commande, ni sortie, ni chemin (capture de log vérifiée).
- [ ] Non-régression : le chemin Managed Agents est inchangé.

### Tests d'intégration

- [ ] Après un tour, `GET /chat` rend la transcription.

### Vérification d'infrastructure

- [ ] Après application : `kubectl get ingress -o yaml` montre 900 sur les deux annotations.

### Isolation workspace

- [x] Applicable — couverte par les tests existants ; aucun nouveau chemin d'accès.

---

## Notes et décisions

**D1 — Aligner l'ingress sur le flux, pas l'inverse.** On aurait pu raccourcir le flux SSE à 120 s
pour respecter l'ingress. Ce serait rendre définitif un plafond qui n'a jamais été un choix produit :
120 s est une valeur par défaut d'ingress, pas une décision sur la durée acceptable d'un tour. La
borne qui compte est le **budget de tour**, et c'est lui qui doit rendre la main en premier, en
disant pourquoi.

**D2 — Garder la fin des sorties, pas le début.** Une commande qui échoue le dit à la fin : code de
sortie, message d'erreur, dernière ligne de pile. C'est la même règle que le rejeu de trajectoire
(SF-39-03) — et l'inverse de ce que fait la sortie de `bash` en cours de tour, qui garde la tête
parce qu'on y trouve la commande qui a démarré.

**D3 — Deux lignes de journal par tour, jamais une par itération.** Une ligne par itération noierait
le journal sous des dizaines d'entrées à chaque message. Ouverture et fermeture suffisent à répondre
aux deux questions qu'on se pose quand ça bloque : est-ce parti, et comment ça s'est terminé.

**D4 — Aucun contenu dans le journal.** L'audit runner (SF-38-08) a posé la règle : on journalise
qu'une commande a eu lieu, jamais ce qu'elle disait. Le journal serveur est lisible par
l'exploitation ; la transcription, elle, appartient à l'utilisateur et vit dans ses données.
