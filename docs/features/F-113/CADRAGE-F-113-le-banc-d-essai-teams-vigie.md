# F-113 — Le banc d'essai Teams et Vigie, sur le poste CAGIP

> Cadrage du 2026-09-13, **révisé le 2026-09-14** sur correction du PO. **Cadrage seul : la livraison
> attend le go du PO.** S'appuie sur **F-112** (le serveur MCP), qui en est le moyen d'exécution.
> **Calendrier (PO, 2026-09-14)** : « le test du serveur MCP doit se faire plus tard » — le banc s'exécutera
> par MCP une fois F-112 livrée et éprouvée ; d'ici là, les essais sur CAGIP restent manuels (atelier de test).

## 1. Le besoin, et la correction du PO

> « On doit véritablement tester franchement toute la partie Teams et Vigie. Donc tu devras mettre le
> banc d'essai pour qu'il permette de littéralement tout tester dessus. »

> **Correction** : « Quand je disais tester Teams, Vigie, je parlais toujours de CAGIP bien sûr.
> L'atelier doit toujours être sur lui. »

La première version de ce cadrage proposait un tenant Microsoft 365 et une machine de test à nous.
**Elle est abandonnée** : le banc d'essai se déroule **sur le vrai poste CAGIP**, avec son vrai Teams,
son vrai proxy, sa vraie politique Microsoft (transcription au téléchargement bloqué, Netskope). C'est
là que les défauts sont apparus, et c'est là qu'on sait qu'une chose marche.

**Ce que le banc apporte par rapport à l'atelier de test manuel du 2026-09-13** : le PO n'a plus à
recopier les consignes d'un PC à l'autre. **L'IA déroule les scénarios elle-même par le serveur MCP**
(F-112) sur le poste CAGIP, et ne sollicite le PO que pour ce qui exige ses mains.

## 2. Ce que le PO fait, et seulement ça

1. Sur le Mac CAGIP : runner lancé, fenêtre Chrome de Teams ouverte et connectée (la double
   authentification Microsoft ne se délègue pas).
2. Dans Claude Code (ou toute IA connectée par F-112) : « lance le banc d'essai CAGIP » — tout, ou un
   domaine.
3. **Accorder dans l'application** les autorisations que l'IA a déclenchées (écritures Microsoft 365,
   commandes) : **une IA n'accorde jamais une autorisation, y compris sur le banc** (règle F-112 §6.1
   inchangée). Le banc les regroupe et les annonce ; il continue les scénarios de lecture en attendant.
4. Lire le rapport.

## 3. Les règles d'un banc sur un poste client

- **Lecture d'abord** : tous les scénarios de lecture tournent sans rien modifier chez le client.
- **Écritures confinées** : les scénarios d'écriture Microsoft 365 n'agissent **que** dans un dossier
  dédié du **OneDrive du PO** (`claude-gateway-banc/`), créé par le banc, jamais dans une équipe, un canal
  ou un fichier du client ; chacune passe par l'autorisation du PO ; le banc nettoie derrière lui.
- **Pas de commande qui modifie la machine** hors du dossier du banc sur le poste (`~/dev/.banc/`).
- **Le Radar de CAGIP n'est pas pollué** : les nouvelles, clôtures et liens créés par le banc sont
  marqués « banc » et **annulés en fin de passage** (corrections annulables de F-99) ; aucun courriel
  n'est envoyé ailleurs qu'à l'adresse vérifiée du PO.
- **Consommation** : chaque passage affiche son coût en jetons avant de démarrer (estimé) et après
  (réel) ; un passage complet est plafonné (valeur par défaut à confirmer par le PO au premier passage).
- **Données du client** : le rapport ne contient que des constats (OK / KO, écart, source, durée), des
  identifiants et des extraits de 280 caractères au plus ; **jamais une transcription**, jamais un
  contenu de fichier.

## 4. La fiche de référence CAGIP

Les résultats attendus d'un vrai poste ne sont pas des valeurs inventées : ils reposent sur une **fiche
de référence**, remplie **une fois** avec le PO puis tenue à jour, dans l'application (pas dans le
dépôt, car ce sont des données du client) :
- une réunion enregistrée connue (« Présentation projet Data Platform – Chaîne d'ingestion », 12 sept.,
  41 min, transcription au téléchargement bloqué) ;
- une conversation connue et une mention connue ;
- un projet de la Forge et un sujet attendu du Radar (LZI, Data Platform) ;
- le dossier OneDrive du banc.

Les autres attendus sont des **propriétés** vérifiables sans connaître le contenu : « la source est
dite », « aucune transcription recopiée », « l'autorisation est demandée avec l'emplacement en clair »,
« le statut passe hors ligne en moins de 2 minutes ».

## 5. Le catalogue de scénarios

Chaque scénario : préconditions, étapes (appels MCP), attendu, nettoyage, et **ce qui exige le PO**.

| Domaine | Scénarios |
|---|---|
| Poste et Forge (F-97, F-98, F-111) | statut daté ; passage hors ligne quand le PO coupe le runner (seul scénario qui lui demande un geste) ; version du runner et mise à jour disponible |
| Liaison Teams (F-87, SF-89-05, SF-89-08) | liaison établie ; diagnostic chiffré ; « rien servi » contre « reçu mais pas reconnu » ; inventaire des chemins inconnus **versé au rapport** (sert SF-89-10) |
| Lecture (F-88, SF-89-06) | trouver la réunion de référence ; lire la conversation de référence ; mentions ; recherche ; transcription **par le réseau puis par l'écran** ; téléchargement bloqué signalé ; transcription jamais recopiée |
| Terminal Teams (F-89, SF-84-04, SF-84-06) | compte rendu de la réunion de référence ; précision pendant un tour ; tour suivi après un retour sur l'écran ; tour vivant malgré le proxy |
| Enregistrements (F-90, SF-108-05) | enregistrement de la réunion de référence : téléchargé et moments, **ou** blocage nommé |
| Microsoft 365 (F-108) | lister le dossier du banc ; créer, déposer, renommer, remplacer une version, supprimer — **chacun en attente d'autorisation du PO** ; nettoyage |
| Vigie (F-106, F-107) | CAGIP activé ; vérification guidée et source de chaque case ; réglage de la synchro lu (sans le modifier) |
| Radar (F-99 à F-104, SF-106-06) | synchroniser maintenant ; couverture ; résumé ; sujet de référence présent ; page sujet et preuves ; réponse au manager ; donner une nouvelle marquée « banc » puis annulée ; courriel collé daté ; lien sujet ↔ projet puis retiré ; relance préparée non envoyée |
| Pages et courriel (F-109, F-110) | page du compte rendu publiée, lue, partagée puis révoquée et supprimée ; courriel à l'adresse vérifiée du PO, avec une page jointe |

## 6. L'exécution et le rapport

- **Un prompt MCP « Banc d'essai CAGIP »** (tout, ou un domaine) : contrôle des préconditions (runner,
  liaison Teams, fiche de référence), estimation du coût, déroulé, regroupement des autorisations à
  accorder, nettoyage, rapport.
- **Rapport** publié en page (F-109), privée : par scénario OK / KO / partiel / en attente du PO, écart,
  source (réseau ou écran), durée, coût ; **comparaison avec le passage précédent** ; liste des défauts
  à cadrer.
- **Quand** : à la demande, et **après chaque déploiement touchant Teams ou la Vigie**, dès que le
  runner et Teams sont ouverts sur CAGIP (l'IA propose le passage).

## 7. Découpage

| SF | Titre | Contenu |
|---|---|---|
| SF-113-01 | La fiche de référence | Écran dans la Vigie du client pour la remplir et la tenir à jour ; stockage isolé `user_id` + `host_id` ; lue par les outils MCP |
| SF-113-02 | Les marqueurs et le nettoyage du banc | Marque « banc » sur les écritures du Radar, des pages et du dossier OneDrive ; annulation et nettoyage de fin de passage ; plafond de coût |
| SF-113-03 | Le catalogue de scénarios | Scénarios du §5 en fichiers lisibles dans le dépôt (sans donnée client), attendus par propriétés et par fiche de référence |
| SF-113-04 | L'exécution par l'IA et le rapport | Prompt MCP, préconditions, regroupement des autorisations, rapport en page, comparaison entre passages |

**Ordre** : F-112 (au moins SF-112-01 à 06) → 01 → (02 ∥ 03) → 04.

## 8. Préoccupations transversales

- **Sécurité : oui.** Aucune autorisation accordée par l'IA ; écritures confinées au dossier du banc
  et marquées ; rapport sans contenu client.
- **Contexte tenant : oui** — fiche de référence isolée par `user_id` + `host_id`.
- **Plans / limites : oui** — plafond de coût par passage, consommation sur le quota du PO.

## 9. Hors périmètre

- Un environnement Microsoft 365 ou une machine de test à nous (première version abandonnée).
- Accorder automatiquement une autorisation.
- Tester un autre poste que celui choisi par le PO (le banc est générique, mais CAGIP est le poste du
  banc tant que le PO n'en décide pas autrement).
