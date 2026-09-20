# F-134 — Le cache qui ne prend pas

> Cadrage du 2026-09-20, à la demande du PO, après sa première lecture du coût réel livré par F-133 :
> *« juste la dernière requête m'a coûté 1,60 € ???? Comment c'est possible. »*
>
> **La cause est identifiée** (§3). Ce cadrage ne propose donc pas une enquête, mais un correctif et
> sa vérification.

---

## 1. Le constat, mesuré en production

Le tour à 1,60 €, décomposé depuis `usage_turns` (2026-09-20, 16:36 UTC) :

| Nature | Tokens | Tarif | Coût | Part |
|---|---|---|---|---|
| Entrée au plein tarif | **4** | 5 $/M | 0,00002 $ | ~0 % |
| Lus du cache | 31 870 | 0,50 $/M | 0,016 $ | 1 % |
| **Écrits dans le cache** | **169 506** | **10 $/M** | **1,695 $** | **98 %** |
| Sortie | 978 | 25 $/M | 0,024 $ | 1 % |

**98 % de la facture est de l'écriture de cache.** Écrire coûte **vingt fois** lire.

Les tours du même projet, dans l'ordre :

| Heure | Relu | Écrit | **% relu** | Écart au précédent |
|---|---|---|---|---|
| 15:15 | 0 | 124 454 | 0 % | 201 min — cache expiré, **normal** |
| 15:58 | 2 201 232 | 247 800 | **90 %** ✅ | 44 min |
| 16:00 | 31 870 | 200 758 | **14 %** ❌ | **2 min** |
| 16:02 | 260 639 | 197 447 | 57 % | 2 min |
| 16:04 | 225 359 | 160 004 | 58 % | 2 min |
| 16:36 | 31 870 | 169 506 | **16 %** ❌ | 32 min |

Deux minutes après un tour à 90 %, le taux tombe à 14 %. **Un cache d'une heure n'expire pas en deux
minutes** : il a été invalidé.

Et **31 870** revient à l'identique aux deux tours effondrés : c'est ce qui reste caché quand le
reste est jeté.

---

## 2. Ce que la base a permis, et ce qu'elle ne permettait pas

Question du PO, qui a orienté ce cadrage : *« tu as besoin de rajouter des choses dans le code ?
L'accès à la DB n'est pas suffisant ? »*

- **La base a suffi** pour savoir **quels** tours cachent mal : les colonnes `cache_read_tokens` et
  `cache_write_tokens` sont peuplées depuis F-133, livrée le matin même. Le tableau ci-dessus en
  sort directement, **rétroactivement**, sans une ligne de code.
- **Elle ne disait pas pourquoi** : elle stocke des volumes, jamais les octets envoyés au
  fournisseur.
- **La lecture du code a suffi** pour la cause (§3). Aucune instrumentation n'a été nécessaire, et
  aucun tour futur n'a eu à être attendu.

Une première version de ce cadrage prévoyait une subfeature « livrer un instrument de mesure ».
**Elle est supprimée** : F-133 l'avait déjà livrée.

---

## 3. La cause

`AtelierChatService` rejoue les **`replayedTraceTurns` derniers tours (défaut 12) avec leurs
résultats d'outils**, et les tours plus anciens **en texte seul** (F-119 / SF-119-03,
`AtelierChatService:455-462`).

À chaque nouveau tour, le tour qui occupait la 12ᵉ place recule d'un rang et **change de forme** :
il perd ses traces d'outils. Or ce message se situe **au début** de l'historique envoyé.

L'API met en cache un **préfixe** : `tools` → `system` → `messages`. Un octet qui change invalide
tout ce qui suit. **Le préfixe mute donc à chaque tour, par construction**, et seul le segment
situé avant la mutation — la consigne système, ~31 870 tokens — reste relu.

**Ce n'est pas un bug accidentel** : c'est une conséquence non anticipée d'une décision prise en
F-119 pour une bonne raison (garder le couplage affirmation ↔ preuve plus longtemps, en élargissant
la fenêtre de 5 à 12). Personne n'avait de quoi voir son effet sur le cache — F-133 vient de le
donner.

### Ce qui disculpe les autres suspects

- **La panoplie d'outils** (`tools`, sans marqueur de cache, rendue en tête) : si elle bougeait,
  elle invaliderait **aussi** la consigne système, et le taux tomberait à **zéro**. Or 31 870 tokens
  restent relus. Elle est donc stable. *(Elle mérite tout de même un marqueur — voir SF-134-02.)*
- **L'expiration du TTL** : deux minutes entre deux tours, contre une heure de TTL.
- **La compaction** : aucune trace dans les journaux sur la période.

---

## 4. La contrainte de F-130 reste entière

F-130 posait une règle non négociable : **certitude d'avoir toujours le meilleur résultat**.

C'est là le point délicat de cette feature, et il doit être dit franchement : **la fenêtre de rejeu
sert la qualité**. La réduire pour gagner du cache reviendrait à rouvrir un arbitrage que F-119 a
tranché dans l'autre sens, et à échanger de la justesse contre de l'argent. **Ce cadrage ne le
propose pas.**

La correction cherchée est celle qui ne change **pas un octet** de ce que le modèle voit : garder
les douze tours **et** cesser de faire muter le préfixe.

---

## 5. Découpage

| # | Subfeature | Ce qu'elle fait | Gain attendu |
|---|---|---|---|
| **SF-134-01** | **Le préfixe cesse de muter** | Figer la forme d'un tour **une fois pour toutes** : un tour rejoué en texte seul le reste, et un tour rejoué avec ses traces garde ses traces. La fenêtre ne « glisse » plus sur le passé — elle ne décide que de la forme des tours **au moment où ils entrent** dans l'historique | **L'essentiel** : le préfixe redevient stable, le cache se relit |
| **SF-134-02** | **Un marqueur sur la panoplie** | Poser un `cache_control` sur `tools`, aujourd'hui sans marqueur alors qu'il est rendu en tête | Petit, mais protège le segment le plus en amont |
| **SF-134-03** | **La part relue à l'écran** | Afficher, à côté du montant (F-133), la part du contexte relue | Rend le gain **visible** et toute régression future détectable |

**Ordre** : 01 → 03 → 02. La 03 avant la 02 pour **mesurer** l'effet de la 01 avant d'y toucher
encore.

### Le point à trancher en SF-134-01

Figer la forme d'un tour soulève une question : **un tour ancien garde-t-il ses traces d'outils pour
toujours ?** Si oui, l'historique grossit — et c'est précisément ce que la fenêtre évitait.

Deux options, à arbitrer sur mesure lors de la mini-spec :

- **A — Forme figée à l'entrée** : un tour entre avec ses traces, et les garde. La compaction (F-117)
  reste le mécanisme qui borne la taille. *Préfixe parfaitement stable.*
- **B — Fenêtre par paliers** : la forme ne change qu'à des frontières rares (tous les 12 tours, par
  exemple), au lieu de changer à chaque tour. *Préfixe stable la plupart du temps, invalidation rare
  et prévisible.*

L'option A est plus simple et plus efficace ; l'option B est plus prudente sur la taille. Le choix
se fera sur les volumes réels, que F-133 permet désormais de mesurer.

---

## 6. Critère de réussite

> Sur dix tours consécutifs d'un même projet, espacés de moins d'une heure et **au-delà du douzième
> tour du fil**, la part **relue** dépasse **80 %** — contre 14 à 16 % aujourd'hui dans cette
> situation.

Traduit en argent, sur le profil mesuré : un tour à 1,74 $ tomberait autour de **0,15 $**.

---

## 7. Hors périmètre

- Toute optimisation qui change ce que le modèle voit (contrainte F-130) — **y compris réduire la
  fenêtre de rejeu**.
- Le choix du modèle, le routage, la cascade (abandonnés en F-130, lot B).
- Le niveau d'effort.
- Les Managed Agents, dont le fournisseur gère le cache lui-même.

---

## 8. Question ouverte

**OQ-22** — La compaction (F-117) invalide **nécessairement** le préfixe, puisqu'elle réécrit
l'historique. C'est normal et acceptable. Mais il faudra vérifier, une fois SF-134-01 livrée, qu'elle
ne se déclenche pas plus souvent que nécessaire : une compaction fréquente annulerait le gain.
