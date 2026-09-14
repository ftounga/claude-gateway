# Les reliquats des vagues du 13 septembre — tout ce qu'il reste, cadré

> Cadrage du 2026-09-14, à la demande du PO : *« Cadre tout ce qu'il reste. »*
> **Cadrage seul : la livraison attend le go du PO.** Chaque entrée est une sous-feature de sa feature
> parente, prête pour sa mini-spec. Sources : comptes rendus des agents de livraison (risques
> résiduels), constats du PO en production, registre `OPEN_QUESTIONS.md`.
>
> **Déjà cadré ailleurs, non repris ici** : SF-89-09 (terminal Teams « Papier », couleurs seules),
> F-112 (serveur MCP), F-113 (banc d'essai sur CAGIP — son exécution par MCP viendra **plus tard**,
> décision du PO du 2026-09-14). **En livraison** : SF-30-15 (boutons lisibles dans les terminaux).

---

## A. Teams et Vigie

### SF-89-10 — Reconnaître par le réseau les réunions du nouveau Teams
- **Constat** : le classement fonctionne (632 réponses, 16 classées), mais aucune réponse de réunion,
  de calendrier, de récapitulatif ni de transcription du Teams v2 réel n'est reconnue (110 réponses
  Microsoft inconnues). La lecture passe aujourd'hui par l'écran (SF-89-06), plus lente et plus fragile.
- **Attendu** : à partir de l'inventaire `diagnostic.observation.unknownPaths` relevé sur le poste
  CAGIP (réunion passée, récapitulatif, transcription ouverts), une règle de reconnaissance par chemin
  (une ligne + un test chacune, SF-89-08) et une forme de lecture défensive par type ; le récapitulatif
  (`readcollabobject`) lu si sa forme le permet ; la source « réseau » redevient la voie normale.
- **Critère** : sur CAGIP, trouver la réunion de référence et lire sa transcription donnent la source
  « réseau ».
- **Prérequis** : l'inventaire du poste CAGIP (geste du PO, ou banc F-113).

### SF-106-07 — Un terminal Teams perd ses outils quand son client quitte la Vigie
- **Constat** (F-106, risque résiduel) : un terminal Teams déjà ouvert garde ses outils `teams_*` si
  son client est retiré de la Vigie ; seule l'ouverture est gardée.
- **Attendu** : les outils du terminal Teams exigent, à chaque tour, que le client soit activé dans la
  Vigie ; sinon le bandeau « client retiré de la Vigie » et la consigne de SF-89-04.
- **Critère** : retirer CAGIP de la Vigie → le tour suivant n'a plus d'outil Teams et le dit.

### SF-84-07 — La mosaïque traverse les proxys, et suit les tours de suite
- **Constat** : la mosaïque (F-83) a la même exposition au proxy d'entreprise que le terminal avant
  SF-84-04 ; une tuile peut se croire au repos au premier `done` d'un tour de suite (SF-84-06).
- **Attendu** : mêmes réponses courtes et reprise par curseur que le terminal ; une tuile suit le tour
  de suite sans passer par « au repos ».
- **Critère** : derrière un proxy qui retient le flux (simulé en test), les quatre tuiles avancent ; un
  tour de suite reste « vivant » dans sa tuile.

### SF-93-05 — Le report de promotion survit à un redémarrage
- **Constat** (SF-93-04) : le rappel « promotion reportée : poste hors ligne » est gardé en mémoire du
  processus ; un redémarrage ou un autre pod le perd.
- **Attendu** : report persisté par `user_id` + `host_id` + projet, réclamé au premier tour runner
  connecté, quel que soit le pod.
- **Critère** : report posé, backend redémarré, runner revenu → la promotion est réclamée une fois.

### SF-100-08 — La synchro du soir tient à plusieurs pods
- **Constat** (F-100) : le verrou « une synchro à la fois par poste » et le planificateur reposent sur une
  mise à jour conditionnelle PostgreSQL testée seulement en séquentiel sur H2 ; les lots d'une synchro
  annulée restent dans la file d'analyse sans règle.
- **Attendu** : test d'intégration sur PostgreSQL réel (deux planificateurs concurrents) ; lots d'une
  synchro annulée analysés s'ils sont complets, écartés sinon, et la couverture le dit.
- **Critère** : deux instances simultanées ne lancent jamais deux synchros du même poste.

## B. Microsoft 365, pages, courriel

### SF-108-06 — Les fichiers : entre sites, gros fichiers, transcription Word
- **Constat** (F-108) : pas de déplacement entre sites, pas de dépôt au-delà de 250 Mo, pas de lecture
  d'une transcription `.docx` téléchargée.
- **Attendu** : déplacement entre sites par copie puis corbeille (deux autorisations), dépôt par session
  d'envoi découpée, lecture du texte d'un `.docx` sur la machine ; même doctrine (API appelée depuis la
  page, Chrome télécharge, rien ne remonte de secret).
- **Critère** : sur le dossier du banc CAGIP, un fichier de 300 Mo est déposé, déplacé vers un autre site
  et supprimé, chaque écriture autorisée.

### SF-109-06 — Les pages depuis un projet hébergé, et leurs images
- **Constat** (F-109) : `page_publish` n'existe pas sur les projets hébergés (bac à sable) ; une pièce
  binaire lue sur la machine est refusée (images en `data:` seulement).
- **Attendu** : outil de publication disponible pour les projets hébergés ; images de la machine
  acceptées comme pièces de la page (types d'image, taille bornée), servies avec la même politique de
  sécurité.
- **Critère** : une page avec deux captures de la machine s'affiche dans le terminal.

### SF-110-05 — Courriel : pièces orphelines et secrets dans les documents
- **Constat** (F-110) : un plantage entre l'écriture des pièces et la file laisse des fichiers orphelins
  dans le stockage ; les secrets d'un PDF, `.docx` ou `.xlsx` joint ne sont pas détectés.
- **Attendu** : balayage quotidien des pièces sans envoi rattaché ; extraction du texte des documents
  joints pour la détection de secrets, avec refus nommé.
- **Critère** : un `.docx` contenant un mot de passe explicite n'est pas envoyé ; une pièce orpheline
  disparaît au balayage suivant.

## C. Runner

### SF-111-06 — Le lanceur se met à jour, et le contrat du runner est rejoué
- **Constat** (F-111) : le lanceur ne se met jamais à jour lui-même ; le module `contract-tests` appelle
  un constructeur obsolète de `RunnerCallDispatcher` et n'est plus exécuté ; la commande de mise à jour
  relayée vers un autre pod exige le secret de relais, sinon 409.
- **Attendu** : un nouveau lanceur est posé à côté de l'ancien et pris au **prochain démarrage** (jamais
  à chaud) ; `contract-tests` réaligné et exécuté par la vérification de `main` ; secret de relais vérifié
  au démarrage, erreur explicite s'il manque en multi-pods.
- **Critère** : deux mises à jour successives, la seconde changeant le lanceur, aboutissent après un
  redémarrage manuel ; `contract-tests` vert.

## D. Sécurité et exploitation

> **Annulé par le PO le 2026-09-14** : toutes les features de sécurité de cette section — SF-38-30
> (masquage des secrets) et SF-73-05 (garde de toutes les routes) — sont **abandonnées**, avec F-114.
> *« Oublie toutes les features que j'ai évoquées sur la sécu. »* Restent uniquement SF-00-DEP
> (déploiement automatique, exploitation, non sécurité) et SF-30-16 (budgets de style).

### ~~SF-38-30~~ — abandonnée (PO, 2026-09-14)
### ~~SF-73-05~~ — abandonnée (PO, 2026-09-14)

### SF-00-DEP — Le déploiement automatique
- **Constat** : `backend.yml` a le déploiement automatique désactivé (secrets GitHub absents) ;
  le déploiement se fait à la main depuis le poste du PO ; `docs/DEPLOYMENT.md` montre un contexte de
  construction périmé (`./backend`) ; la signature du runner (F-111) exige la clé AWS.
- **Attendu** : déploiement par GitHub Actions avec **rôle AWS par fédération OIDC** (aucune clé longue
  durée dans GitHub), clé de signature lue dans Secrets Manager au moment de la construction,
  vérification de `main` (suites complètes, `contract-tests`) avant publication, et `DEPLOYMENT.md` à
  jour. **Modifier `.github/` est interdit aux agents** : la sous-feature est exécutée par
  l'orchestrateur avec le PO.
- **Critère** : un merge sur `main` déploie seul, et un test rouge l'empêche.
- **Décision PO** : activer ou non le déploiement automatique (aujourd'hui volontairement manuel).

### SF-30-16 — Les budgets de style
- **Constat** : avertissements de budget sur `vigie.component.scss` et `atelier-files.component.scss`.
- **Attendu** : feuilles découpées par ce qu'elles portent (patron F-83/F-89), sous le budget, sans
  changement visuel.

## E. Commercial — décisions, pas de développement

Reste ouvert dans `OPEN_QUESTIONS.md` (OQ-16) et dans les cadrages, **à trancher par le PO** :

| Point | Où | Ce qui manque |
|---|---|---|
| Prix du pack de recharge 1 M jetons | OQ-16 §1 | montant (affiché « au paiement » aujourd'hui) |
| Offre annuelle BYOK | OQ-16 §5 | voulue ou non |
| Taille de l'essai gratuit (200 000 jetons) | OQ-16 §9 | suffisante ou non |
| Recharge de la réserve de synchro Vigie | F-107 §9 | pack et montant (la règle « complétée par une recharge » n'a pas de produit) → **SF-107-08** une fois le montant fixé |
| Changement de plan avec l'option Forge (Solo à 40 € → BYOK à 70 €) | F-107, risque SF-107-01 | refuser, ou migrer le prix de l'option chez Stripe → **SF-107-09** une fois décidé |
| Prices Stripe de la nouvelle grille | F-107 §9 | création par le PO |

---

## Ordre proposé

1. **SF-38-30** et **SF-73-05** (sécurité).
2. **SF-89-10** dès l'inventaire CAGIP reçu, puis **SF-106-07**, **SF-84-07**.
3. **SF-93-05**, **SF-100-08**, **SF-111-06**.
4. **SF-108-06**, **SF-109-06**, **SF-110-05**, **SF-30-16**.
5. **SF-00-DEP** avec le PO.
6. SF-107-08 et SF-107-09 après les décisions commerciales.
