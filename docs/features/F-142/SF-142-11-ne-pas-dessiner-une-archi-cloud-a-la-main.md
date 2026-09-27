# Mini-spec — F-142 / SF-142-11 — Ne pas dessiner une archi cloud à la main

## Identifiant
`F-142 / SF-142-11` — feature parente `F-142`

## Objectif
Qu'« archi AWS » suffise : une architecture cloud ne doit **pas** pouvoir être publiée en Mermaid
écrit à la main, sans icônes officielles.

## La demande
> PO, 2026-09-27 : *« C'est troooop détaillé. Je ne dois pas être obligé de lui dire tout ça.
> Pourquoi juste en lui disant archi AWS il ne comprend pas ? C'est exactement ça mon besoin :
> qu'il sache basculer sur les outils AWS quand il s'agit de ça. »*

## Le constat, et il corrige un diagnostic antérieur
La page « data-ingestion — 4 vues » (2026-09-26) contient **5 blocs `<pre class="mermaid">` écrits à
la main** et **zéro image**. Sa vue réseau décrit un VPC, des sous-réseaux, huit endpoints, un
Transit Gateway — en rectangles nommés.

**L'agent n'a pas mal choisi le moteur : il n'a jamais ouvert l'outil de diagramme.** Il a tapé du
Mermaid dans du HTML, que la page rend elle-même côté navigateur (SF-142-01).

C'est pourquoi la description de l'outil — qui dit pourtant *« pour une ARCHITECTURE CLOUD avec les
icônes officielles… »* — n'a rien changé : **on ne lit pas la notice d'un outil qu'on n'ouvre pas.**

## L'arbitrage, tranché ici
**Une porte, pas une meilleure consigne.** Trois patrons du produit le montrent déjà — la porte de
complétude de plan (SF-121-05), la porte du runner (SF-161-01), l'arrêt net (SF-161-02) : le produit
n'attend pas la bonne volonté du modèle, **il l'empêche de se tromper**, avec un contrôle
déterministe et gratuit.

**Refuser, pas réécrire.** Convertir nous-mêmes le Mermaid en `spec` cloud serait deviner
l'intention — et un composant faux est pire qu'un composant absent (règle de fond de F-142). Le
refus rend la main à l'agent, qui sait ce qu'il voulait dessiner.

**Le doute ne ferme rien.** Un diagramme de séquence, un flux métier, un organigramme restent en
Mermaid. Seul un bloc qui parle **manifestement** d'infrastructure cloud est refusé.

## Comportement attendu
1. À la publication d'une page, un bloc Mermaid dont le contenu nomme **plusieurs** marqueurs
   d'infrastructure cloud (VPC, sous-réseau, EKS, S3, Transit Gateway, `vpce-`, Route 53, ALB,
   Lambda, région `eu-west-…`, CIDR) provoque un **refus nommé**.
2. Le refus dit **quoi faire**, pas seulement ce qui ne va pas : appeler l'outil de diagramme en
   `engine: "cloud"`, préfixer les types par la famille (`aws.eks`), insérer l'image rendue.
3. Le refus part **avant** la publication : aucune page fausse n'est écrite.
4. **Aucun appel modèle**, aucun coût : c'est une lecture de texte.
5. Un bloc Mermaid **non cloud** passe, inchangé.
6. Un seul marqueur ne suffit pas — « le bucket S3 des logs » dans un diagramme de séquence n'est
   pas une architecture.
7. **Débranchable** par réglage ; sans lui, comportement d'avant.

| Cas | Comportement |
|---|---|
| Bloc Mermaid décrivant un VPC + sous-réseaux + endpoints | **refus nommé**, rien n'est publié |
| Diagramme de séquence, flux métier, organigramme | passe |
| Un seul marqueur isolé | passe — pas une architecture |
| Page sans bloc Mermaid | passe |
| Image rendue par l'outil (`<img>`) | passe — c'est le but |
| Réglage à `false` | comportement d'avant |

## Critères d'acceptation
- [ ] La vue réseau **réelle** de la page du 2026-09-26 est refusée (cas d'or, repris tel quel).
- [ ] Un `sequenceDiagram` citant S3 une fois **passe**.
- [ ] Le message de refus nomme `engine: "cloud"` **et** le préfixe de famille.
- [ ] Le refus intervient **avant** `pageService.publish` — vérifié en comptant les publications.
- [ ] **Aucun appel fournisseur** n'est déclenché par le contrôle.
- [ ] Une page portant l'image d'un diagramme cloud passe.
- [ ] Réglage à `false` → la page du 2026-09-26 passe (non-régression).

## Plan de test minimal
**Unitaires** — le détecteur, fonction pure : la vue réseau réelle (refus) · un `sequenceDiagram`
avec une mention S3 (passe) · un flux métier (passe) · un bloc vide (passe) · le seuil de marqueurs.
**Intégration** — `page_publish` avec la vue réseau réelle rend un refus nommé et **ne publie
rien** ; avec réglage à `false`, publie.
**Isolation** — inchangée : le contrôle lit le HTML du tour, aucun accès données.

## Technique
| Élément | Changement |
|---|---|
| `CloudArchitectureInMermaid` | détecteur **pur** : HTML → bloc fautif ou rien |
| `PageToolExecutor` | contrôle **avant** `pageService.publish`, `Outcome.error(...)` |
| réglage `app.pages.refuse-hand-drawn-cloud` | défaut `true`, débranchable |

**Aucune migration, aucun endpoint, aucun changement de protocole runner, aucun composant cluster.**

## Préoccupations transversales
Aucune. Ni auth, ni contexte tenant, ni plans/limites, ni routing : le contrôle lit une chaîne de
caractères déjà en mémoire, sur le chemin de publication existant.

## Hors périmètre
Convertir automatiquement le Mermaid en `spec` cloud (ce serait deviner) · les **slides** et les
**documents**, qui ont leur propre chemin (F-129) · enrichir le catalogue de types · la qualité des
suggestions de `strict` (laissée faible par SF-142-12, tracée).
