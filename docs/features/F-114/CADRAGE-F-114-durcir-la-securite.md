# F-114 — Durcir la sécurité de l'application, et être reconnu plutôt que caché

> Cadrage du 2026-09-14, à la demande du PO. **Cadrage seul : la livraison attend le go du PO.**
> Deux volets : **A. protéger l'application** ; **B. supprimer les faux positifs des outils de sécurité
> du client — par la reconnaissance, pas par la dissimulation.**

## 0. La ligne que ce cadrage ne franchit pas

Le PO a demandé aussi des mesures « pour rendre difficile la détection de nos outils » et « être le
moins visible possible ». **Refusé, et remplacé par le volet B.** Deux raisons :

1. **Ça produit l'effet inverse.** Un EDR / antivirus classe un logiciel hostile sur ses **signaux de
   dissimulation** : binaire compressé ou obfusqué, processus masqué ou déguisé en processus système,
   évitement de l'inspection réseau, absence de signature. Rendre le runner « invisible » **ajouterait**
   ces signaux et **multiplierait** les vrais positifs. Le runner exécute des commandes, lit le trafic
   Teams et pilote un navigateur : le seul moyen qu'il ne soit pas pris pour une menace est qu'il soit
   **manifestement identifiable et signé**.
2. **Sur un poste client (banque), c'est hors périmètre.** Se soustraire aux contrôles de sécurité du
   client sans son accord contredit toute la doctrine du produit (fiche DSI F-45, consentement,
   journal d'audit, « rien ne quitte la machine »). Le produit se fait **autoriser**, il ne se cache pas.

Le volet B répond au **vrai** besoin — pas de faux positif — par la voie qui marche chez un client
bancaire : signature, réputation, liste blanche.

---

## A. Protéger l'application

### SF-114-01 — En-têtes et défenses HTTP
- En-têtes de sécurité sur toutes les réponses : `Content-Security-Policy` stricte pour le frontend
  (distincte du bac à sable des pages F-109), `Strict-Transport-Security`, `X-Content-Type-Options`,
  `Referrer-Policy`, `X-Frame-Options`/`frame-ancestors` (l'app ne s'encadre pas), `Permissions-Policy`.
- Limites de débit par compte et par IP sur les routes sensibles (connexion, appairage, code d'accès,
  MCP F-112, envoi de courriel F-110), avec réponse nommée plutôt que 500.
- Taille de corps bornée partout (déjà 155 Mo à l'ingress) ; délais bornés (règle F-77) sur tout appel
  sortant.

### SF-114-02 — Authentification et sessions durcies
- Verrouillage progressif après échecs de connexion, journal des connexions (date, IP, appareil),
  écran « mes sessions » avec révocation.
- Rotation des secrets de signature JWT, jetons courts, `refresh` rotatif (aligné sur F-112).
- Vérification que **toute** route porte sa garde (rejoint SF-73-05) ; test d'architecture qui casse
  si une route de `/api/**` (hors liste blanche explicite : santé, `.well-known`, liens partagés
  F-109) n'est pas authentifiée.

### SF-114-03 — Secrets et journaux
- Masquage des secrets à l'écriture, partout où du texte est persisté : audit runner, historique du
  terminal, journaux applicatifs, journal MCP, courriels (reprend et généralise SF-38-30).
- Revue des secrets d'exécution : aucun secret en variable d'environnement lisible par un tour ;
  audit des accès à `backend-secrets`.
- Journal d'audit **inviolable en ajout seul** pour les actions sensibles (appairage, coupe-circuit,
  autorisations, révocations, actions ADMIN).

### SF-114-04 — Dépendances et chaîne de construction
- Analyse des dépendances (backend, frontend, runner) à chaque construction, échec sur vulnérabilité
  critique connue ; **SBOM** publié par artefact.
- Construction reproductible du runner (déjà signée en F-111) ; empreintes publiées.
- Scan de secrets sur le dépôt en intégration continue.

### SF-114-05 — Cloisonnement et surface
- Revue du cloisonnement `user_id` + `host_id` sur tout accès (test d'architecture existant étendu).
- Revue des routes publiques (santé, téléchargement runner, liens partagés) : chacune justifiée,
  aucune fuite d'information.
- Politique réseau du cluster : le backend ne sort que vers ce dont il a besoin (fournisseur IA, SMTP,
  S3, base) ; le reste refusé.

### SF-114-06 — Ce que le client peut vérifier
- Une page **« Sécurité »** publique et une **fiche de conformité** : chiffrement en transit et au
  repos, isolation multi-tenant, ce qui reste sur la machine du client, journal d'audit, la doctrine
  du runner (consentement, coupe-circuit, aucun secret rapatrié). Ce que l'équipe sécurité d'un client
  demande avant d'autoriser.

---

## B. Zéro faux positif — par la reconnaissance

### SF-114-07 — Signer et notariser le runner
- **Windows** : signature Authenticode du paquet et de l'exécutable du lanceur (certificat éditeur EV
  si possible : réputation SmartScreen immédiate). Supprime l'avertissement SmartScreen.
- **macOS** : signature Developer ID **et notarisation Apple** ; le lanceur cesse d'avoir à lever la
  quarantaine à la main. Supprime le blocage Gatekeeper.
- Clés dans un magasin dédié (AWS / HSM), signature à la construction comme la signature de mise à jour
  de F-111. **Prérequis PO** : compte Apple Developer et certificat éditeur Windows (coûts à confirmer).

### SF-114-08 — Bâtir une réputation vérifiable
- Empreintes SHA-256 et **SBOM** publiés pour chaque version du runner ; soumission automatique à
  **VirusTotal** à la publication, résultat suivi (un moteur qui flague est traité, pas caché).
- Page publique décrivant précisément le comportement du runner (processus lancés, connexions
  sortantes, fichiers touchés) : ce qu'un analyste sécurité veut lire pour lever un doute.
- Nom de processus et chemins **clairs et stables**, aucun packing, aucune obfuscation — c'est ce qui
  distingue un outil professionnel d'un logiciel hostile aux yeux d'un EDR.

### SF-114-09 — La fiche DSI enrichie pour la liste blanche
- Étend la fiche « Pour votre DSI » (F-45 / SF-45-03) avec ce qu'une équipe sécurité met en liste
  blanche : éditeur et empreinte du certificat, empreintes des binaires, chemins d'installation
  (`~/.claude-runner/…`), processus attendus, connexions sortantes (domaine, 443, HTTPS+WSS), et la
  demande d'**exclusion nominative** (par éditeur signé, pas par contournement).
- Générée par l'écran, à jour de la version réellement servie, copiable et imprimable.

## Découpage et ordre

Volet A d'abord (protège tout de suite, sans dépendance externe) : SF-114-01 → 06.
Volet B ensuite, car il dépend d'achats du PO (certificats) : SF-114-07 → 09. SF-114-07 prolonge la
signature de mise à jour de F-111.

## Préoccupations transversales

- **Sécurité** : c'est l'objet. **Auth** : oui (SF-114-02). **Navigation** : page Sécurité et fiche DSI.
- Aucune mesure de ce cadrage ne réduit la visibilité du runner vis-à-vis des outils de sécurité du
  client. C'est délibéré (§0).

## Décisions qui reviennent au PO

| Décision | Recommandation |
|---|---|
| Certificat éditeur Windows (EV de préférence) | Oui — supprime l'avertissement SmartScreen chez tout client |
| Compte Apple Developer + notarisation | Oui — supprime le blocage Gatekeeper sur Mac |
| Soumission VirusTotal automatique | Oui — la réputation se construit, elle ne se décrète pas |
| ~~Techniques de dissimulation / anti-détection~~ | **Écarté (§0)** : contre-productif et hors périmètre |

## Hors périmètre

- Toute technique visant à rendre le runner **indétectable** par un antivirus, un EDR, un DLP ou un
  proxy d'inspection : packing, obfuscation, masquage de processus, imitation d'un processus système,
  évitement de l'inspection réseau. Ces techniques **augmentent** les faux positifs et contournent la
  sécurité du client.
