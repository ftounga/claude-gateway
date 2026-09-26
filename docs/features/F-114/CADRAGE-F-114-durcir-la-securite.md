# F-114 — Durcir la sécurité de l'application, et être reconnu plutôt que caché

> ## ⛔ RETIRÉE DU PÉRIMÈTRE — décision du PO du 2026-09-26
>
> **La ligne F-114 a été supprimée de `docs/PRODUCT_SPEC.md`.** Ce cadrage est conservé **pour
> mémoire**, et ne doit pas être livré.
>
> **Motif** : ce qui lève réellement SmartScreen et Gatekeeper repose sur des **achats externes
> récurrents** — certificat Windows OV (≈ 200–400 €/an) ou EV (≈ 300–700 €/an + jeton matériel),
> Apple Developer (99 $/an) — que le produit ne peut pas décider seul. La part gratuite (empreintes,
> SBOM, VirusTotal) ne suffit pas à lever un blocage antivirus : la livrer seule donnerait
> l'apparence d'une feature faite sans en produire l'effet.
>
> **Reste écarté**, comme dans le cadrage d'origine : toute mesure d'**anti-détection** du runner —
> elle augmente les vrais positifs et contourne la sécurité du client.
>
> Pour rouvrir : réinscrire la feature dans `PRODUCT_SPEC.md` **après** la décision d'achat.

> Cadrage du 2026-09-14, à la demande du PO. **Cadrage seul : la livraison attend le go du PO.**
> **Réduit au volet B, sur décision du PO du 2026-09-14** : le durcissement applicatif (ancien volet A)
> et la fiche DSI enrichie (ancienne SF-114-09) sont retirés. Reste : **supprimer les faux positifs des
> outils de sécurité du client — par la reconnaissance, pas par la dissimulation.**
>
> **Priorité — décision du PO du 2026-09-14 : reportée.** Le PO essaie d'abord **sans** signature ni
> réputation, et verra à l'usage si un poste client bloque réellement avant d'acheter un certificat.
> À ne lancer que sur go explicite ; aucune vague ne la prend d'office.

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

## Découpage et ordre

SF-114-07 (signer) puis SF-114-08 (réputation). SF-114-07 prolonge la signature de mise à jour de F-111.
Rien ne peut être signé avant que le PO ait acquis les certificats (voir « décisions »).

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

## Ce que ça coûte

| Poste | Coût | Nature |
|---|---|---|
| Certificat de signature Windows **OV** | ≈ 200–400 € / an | annuel, par autorité de certification (Sectigo, DigiCert…) |
| Certificat de signature Windows **EV** (réputation SmartScreen immédiate) | ≈ 300–700 € / an, **+ jeton matériel ou HSM** | annuel ; l'EV supprime l'avertissement dès la première signature, l'OV le supprime après une montée en réputation |
| Compte **Apple Developer** (notarisation macOS) | **99 $ / an** (≈ 92 €) | annuel |
| **VirusTotal** (soumission publique) | **gratuit** | l'API publique suffit à publier et suivre |
| Empreintes, SBOM, page de comportement | **0** | du développement, pas un abonnement |
| **Développement** (SF-114-07 et 08) | inclus dans la vague, **0 € externe** | — |

**En clair : le seul argent qui sort, ce sont les certificats** — de l'ordre de **100 € à 800 € par an**
selon Windows OV ou EV, plus 92 € pour Apple. Tout le reste (VirusTotal, empreintes, SBOM) est gratuit,
et le code est livré dans la vague. Recommandation : **Apple à 99 $** (indispensable, peu cher) et
**Windows EV** si le budget le permet (réputation SmartScreen immédiate), sinon **OV** (moins cher, la
réputation se construit en quelques semaines d'usage).

## Hors périmètre

- Toute technique visant à rendre le runner **indétectable** par un antivirus, un EDR, un DLP ou un
  proxy d'inspection : packing, obfuscation, masquage de processus, imitation d'un processus système,
  évitement de l'inspection réseau. Ces techniques **augmentent** les faux positifs et contournent la
  sécurité du client.
