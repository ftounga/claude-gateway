# Mini-spec — F-136 / SF-136-02 — Le sommaire de la carte dans la consigne système

## Identifiant
`F-136 / SF-136-02` — feature parente `F-136`

## Objectif
Que l'agent **sache ce qu'il sait** de ce client avant d'explorer sa machine.

## L'arbitrage qui commande tout : le cache
L'API met en cache un **préfixe** : `tools`, puis `system`, puis `messages`. Tout octet modifié dans
la consigne système invalide **la totalité** de ce qui suit — c'est précisément le défaut que F-134
vient de corriger, faisant passer la part relue de 16-23 % à **99 %**.

Conséquence directe, et elle décide du contenu du bloc :

> **Le sommaire injecté ne doit contenir que ce qui bouge peu.**

| Donnée | Rythme | Injectée ? |
|---|---|---|
| Fichiers de carte et leurs **titres** | quelques fois par mois | **oui** |
| **Titres des sections** | quelques fois par semaine | **oui** |
| **Nombre de faits** | **à chaque tour** | **non** |
| Date du dernier apport | à chaque tour | **non** |

Le cadrage annonçait « le nombre de faits et la date du dernier apport ». **C'est écarté, et c'est
délibéré** : ces deux valeurs changent à chaque promotion, donc à presque chaque tour ; les injecter
dans la consigne reconstruirait le cache à chaque demande et annulerait le gain de F-134 — un coût
bien supérieur à ce qu'apporterait le chiffre. Ils restent lisibles à l'écran (F-140).

Les faits eux-mêmes, qui dépendent de la **question**, ne vont pas non plus dans la consigne : F-137
les placera dans le **message du tour**, où ils n'invalident rien.

## Comportement attendu
1. Sur un poste en mémoire, la consigne système porte un bloc **« Ce que tu sais déjà de ce
   client »** : les fichiers de carte, et sous chacun ses sections.
2. Une consigne courte l'accompagne : *avant d'explorer la machine, regarde ce que tu sais déjà, et
   ouvre le fichier concerné plutôt que de partir en exploration.*
3. Sur un poste sans mémoire, **aucun bloc** — la consigne est exactement celle d'avant.
4. Le bloc est **borné** ; au-delà, les sections sont tronquées et la coupe se dit.

| Cas d'erreur | Comportement |
|---|---|
| Magasin vide (premier tour) | aucun bloc ; le tour se déroule comme avant |
| Magasin illisible | aucun bloc, le tour n'échoue **jamais** pour cette raison |
| Carte énorme | bloc tronqué, coupe annoncée |

## Critères d'acceptation
- [ ] Sur un poste en mémoire, la consigne contient les fichiers et les sections de **sa** carte.
- [ ] Le bloc **ne contient ni compte de faits ni date** — vérifié par test, c'est la garantie de cache.
- [ ] **Isolation, prouvée par test** : la carte du poste A n'apparaît **jamais** dans la consigne d'un tour du poste B, y compris entre deux postes du même utilisateur.
- [ ] Deux tours consécutifs sans changement de structure produisent un bloc **identique à l'octet** (cache préservé).
- [ ] Poste sans mémoire ⇒ consigne inchangée.
- [ ] Une panne du magasin ne fait jamais échouer un tour.
- [ ] Le bloc est placé dans le **préfixe stable**, avec les doctrines.

## Hors scope
Les faits eux-mêmes (**F-137**) · la péremption (**F-139**) · tout écran.

## Technique
| Classe | Changement |
|---|---|
| `HostMapStore` | `outlineFor(userId, hostId)` — le sommaire, déjà borné |
| `AtelierChatService.buildSystemPrompt` | insère le bloc et sa consigne |

Aucune table, aucune migration.

## Plan de test
- [ ] Le bloc contient fichiers et sections du bon poste.
- [ ] Il ne contient **aucun** chiffre de faits ni date.
- [ ] Poste B ne voit pas la carte du poste A (isolation).
- [ ] Deux appels successifs ⇒ blocs identiques.
- [ ] Magasin vide ou en panne ⇒ aucun bloc, aucun échec.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | — |
| **Contexte tenant** | **oui** | Le sommaire est lu par `hostId` **du tour** (`workspace.getHostId()`) et filtré `user_id`. Un test d'isolation dédié fige la garantie : deux postes du **même** utilisateur ne voient pas la carte l'un de l'autre. |
| **Plans / limites** | **oui** *(indirect)* | La consigne système grossit : elle reste sous `SYSTEM_MAX_CHARS` (40 000), le bloc porte sa propre borne, et la coupe se dit. Le compteur de contexte (F-117) n'a pas à changer : il mesure ce qui est envoyé, et le bloc en fait partie. |
| Navigation / routing | non | — |
