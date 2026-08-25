# Mini-spec — [F-29 / SF-07] Vitrine : présenter les trois solutions

---

## Identifiant

`F-29 / SF-07`

## Feature parente

`F-29` — Identité publique & conformité web

## Statut

`ready`

## Date de création

2026-08-25

## Branche Git

`feat/SF-29-07-vitrine-trois-solutions`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Faire du site de l'éditeur une **porte d'entrée vers les trois solutions** du groupe — Claude Portal,
AI LegalCase et NG Acquisitions — au lieu d'une vitrine ne présentant qu'un seul produit.

---

## Contexte

`www.ng-itconsulting.com` (SF-29-04) présente NG-CONSULTING et **un seul produit**, Claude Portal.
Deux autres activités existent et sont en ligne, sans être mentionnées nulle part :

| Solution | Adresse | Nature |
|----------|---------|--------|
| **Claude Portal** | `portal.ng-itconsulting.com` | Logiciel — passerelle professionnelle vers l'assistant Claude |
| **AI LegalCase** | `legalcase.fr` | Logiciel — analyse de dossiers juridiques par IA, pour avocats |
| **NG Acquisitions** | `ng-acquisitions.com` | Activité — reprise et développement de PME |

Un visiteur arrivant sur le domaine de l'éditeur n'a donc aucun moyen de découvrir l'étendue de ce que
fait le groupe.

**Nuance à respecter** : les deux premières sont des logiciels édités ; la troisième est une activité
de reprise d'entreprises. Les aligner sans distinction laisserait croire que NG Acquisitions est un
produit à souscrire.

---

## Comportement attendu

### Cas nominal

1. La section « Notre produit » devient **« Nos solutions »** et présente les trois entrées.
2. Chaque entrée porte : sa nature (logiciel édité / activité), ce qu'elle fait, pour qui, et un lien
   vers son site.
3. Claude Portal reste l'entrée la plus développée — c'est le produit servi par ce domaine.
4. La navigation d'en-tête pointe vers « Nos solutions » plutôt que vers un produit unique.
5. Les liens externes s'ouvrent dans un nouvel onglet, en `rel="noopener"`.
6. Le reste de la page (activité de l'éditeur, mentions, contact) est **inchangé**.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Un site tiers indisponible | Aucun impact : ce sont des liens, la page reste servie |
| Visiteur sur mobile | Les trois cartes s'empilent (grille déjà responsive) |
| Apex `https://ng-itconsulting.com` | Inchangé, hors scope (voir ci-dessous) |

---

## Critères d'acceptation

- [ ] Les trois solutions sont présentées, chacune avec sa nature explicite
- [ ] NG Acquisitions est identifiable comme une **activité de reprise**, pas comme un produit logiciel
- [ ] Chaque entrée renvoie vers son site (nouvel onglet, `rel="noopener"`)
- [ ] Aucune donnée chiffrée invérifiable n'est reprise des sites tiers
- [ ] La navigation d'en-tête est cohérente avec la nouvelle section
- [ ] Charte inchangée : navy, orange, crème, Space Grotesk / Inter — aucune couleur hors palette
- [ ] Les métadonnées (titre, description, Open Graph) reflètent le groupe et ses solutions
- [ ] Le reste de la page est inchangé (activité, éditeur, contact, mentions)
- [ ] Aucun endpoint, aucune table, aucune migration, aucune image à reconstruire

---

## Périmètre

### Hors scope

- **HTTPS sur l'apex nu** `ng-itconsulting.com` : arbitré en SF-29-04 — un apex ne peut pas porter de
  CNAME et les IP de l'équilibreur sont dynamiques. La redirection OVH `http://ng-itconsulting.com` →
  `www` **fonctionne** (302 vérifié le 2026-08-25) ; seul le HTTPS sur l'apex échoue. Résoudre cela
  suppose une migration de zone DNS — action OVH, hors dépôt.
- Pages dédiées par solution, formulaire de contact, blog, mesure d'audience
- Toute modification des sites `legalcase.fr` et `ng-acquisitions.com` eux-mêmes

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Contenu tiers | Description **qualitative** uniquement ; aucun chiffre repris (ils changent sans que nous le sachions) |
| Liens externes | `target="_blank"` + `rel="noopener"` |
| Charte | Jetons existants de la page, aucune couleur ni police nouvelle |
| Poids | Page statique, sans requête externe (aucune police ni image distante ajoutée) |

---

## Technique

### Endpoint(s)

Aucun.

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `k8s/base/corporate/configmap.yaml` | Contenu de la page (section « Nos solutions », navigation, métadonnées) |

> Le site est porté par une **ConfigMap**, pas par une image (décision SF-29-04) : corriger un texte
> n'impose ni build ni publication d'image. Le déploiement se limite à appliquer la ConfigMap et à
> relancer le pod.

---

## Plan de test

### Tests unitaires

Sans objet : page statique sans logique. La vérification porte sur le rendu et les liens.

### Tests d'intégration

- [ ] La ConfigMap s'applique et le pod redémarre sans erreur
- [ ] `https://www.ng-itconsulting.com/` répond 200 et contient les trois solutions
- [ ] Les trois liens externes pointent vers les bonnes adresses
- [ ] `http://ng-itconsulting.com` redirige toujours vers `www` (non-régression)

### Isolation utilisateur

- [ ] **Non applicable** — page publique statique, aucune donnée utilisateur, aucun accès authentifié.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Page publique, aucun accès authentifié. |
| Contexte tenant | **Non** | Aucune donnée. |
| Plans / limites | **Non** | Aucun appel applicatif. |
| Navigation / routing | **Non** | Site statique indépendant de l'application ; les routes Angular ne sont pas touchées. |

---

## Dépendances

- **SF-29-04 (Done)** — le site et son hébergement existent.

---

## Notes et décisions

- **Distinguer les natures** : présenter une activité de reprise de PME comme un « produit » à côté de
  deux logiciels induirait le visiteur en erreur. Chaque carte annonce ce qu'elle est.
- **Pas de chiffres tiers** : les sites de LegalCase et NG Acquisitions affichent des volumes et des
  montants. Les recopier créerait une donnée que nous ne maîtrisons pas et qui vieillirait sans que
  personne ne s'en aperçoive. On décrit, on renvoie vers la source.
