# F-127 — Lire une documentation depuis une URL (texte + images)

> Cadrage du 2026-09-17, à la demande du PO. **Cadrage seul : la livraison attend le go du PO.**

## 1. Le besoin
> « Je souhaite que l'application soit capable de lire les documentations. Au lieu de te donner une doc à
> la main, je ne te donne que l'URL, et l'application récupère le contenu. Et pas seulement le texte :
> aussi les **images** de la doc, juste via l'URL web. »

Aujourd'hui, pour qu'un agent exploite une doc, il faut **coller son contenu** dans le fil. On veut
qu'il suffise de donner une **URL** : l'agent récupère **le texte lisible** de la page **et ses images**,
puis les exploite (les images comme contenu visuel, pas juste des liens).

## 2. Où l'on récupère (décision de fond)
L'endroit qui fait la requête change ce qu'on peut lire :

| Voie | Ce qu'elle atteint | Verdict |
|---|---|---|
| **Le runner (machine du client)** | doc **publique** ET doc **interne** (intranet, Confluence, wiki client) — via le réseau et le **proxy** du client | **Recommandé** — c'est la seule voie qui lit une doc **interne** au client, cohérente avec notre modèle (le runner est déjà sur sa machine, derrière son proxy) |
| Web fetch d'Anthropic (Provider-First) | doc **publique** uniquement (requête depuis Anthropic, pas le réseau client) | Repli utile pour un **projet hébergé** sans runner, ou une doc publique ; **ne voit pas** l'intranet client |
| La gateway | publique ; risque **SSRF** vers nos services internes | Écarté (risque + ne voit pas l'intranet client) |

**Doctrine** : la lecture passe par le **runner** (réseau + proxy du client) ; repli web-fetch d'Anthropic
pour les projets hébergés / docs publiques. **Provider-First** : on **relaie** une capacité (lire une
page), on ne réimplémente pas un navigateur.

## 3. Ce qui est récupéré
- **Texte** : la page est récupérée, puis on en extrait le **contenu lisible** (readability : on enlève
  menus, pieds de page, pubs) et on le rend au modèle.
- **Images** : les images **du contenu principal** sont récupérées et passées au modèle comme **blocs
  image** (il les « voit » — multimodal), pas comme de simples URLs. Bornes : nombre d'images plafonné
  (ex. top 8-10), taille par image bornée, formats web courants ; au-delà, dit « N images non
  récupérées ». *(S'appuie sur la lecture multimodale — cf. reliquat F-121-15.)*
- **Traçabilité** : l'URL de source et l'emplacement d'origine de chaque image sont conservés.

## 4. Découpage
| SF | Titre | Contenu |
|---|---|---|
| **SF-127-01** | **Lire le texte d'une URL** | Outil `read_url` (runner) : récupère la page (via proxy client), extrait le **texte lisible**, le rend au modèle. Bornes (taille, timeout). Repli web-fetch d'Anthropic pour projet hébergé/doc publique. Échec nommé (URL injoignable, 403, non-HTML). |
| **SF-127-02** | **Récupérer aussi les images** | Récupère les **images du contenu principal**, bornées (nombre, taille, formats), et les passe au modèle en **blocs image**. Source de chaque image tracée. Une image non récupérable → dite, jamais inventée. |
| **SF-127-03** | **Pages dynamiques & docs authentifiées** *(plus tard)* | Pages **rendues en JavaScript** (SPA) et docs **derrière un login** (Confluence, wiki interne) via le **Chrome managé** (F-122) : on rend la page, puis on capte texte + images. À faire après F-122. |

**Ordre** : SF-127-01 → SF-127-02 → SF-127-03 (option).

## 5. Sécurité & cloisonnement
- **Runner-side = pas de SSRF vers notre infra** (c'est le réseau du client). Bornes de taille/temps/
  nombre pour éviter l'abus. Pas de secret réinjecté.
- Le contenu récupéré est **de la donnée**, pas des instructions : à ne pas traiter comme un ordre
  (une page peut contenir du texte piégé — l'agent la lit comme documentation, il ne « obéit » pas à la
  page).
- Isolation : la lecture se fait dans le contexte du terminal/poste ouvert (droit du terminal).

## 6. À trancher (défauts proposés, dites si vous changez)
1. **Voie par défaut = runner** (lit aussi l'intranet client), repli web-fetch pour l'hébergé. ✔️ ?
2. **Bornes images** : top ~8 images du contenu, ≤ ~5 Mo chacune, formats png/jpg/webp/gif/svg. ✔️ ?
3. **Pages JS-rendues / docs authentifiées** : **plus tard** (SF-127-03, après F-122), on commence par
   les docs statiques (HTML). ✔️ ?
4. **Un outil unique `read_url`** (texte + images ensemble) plutôt que deux outils séparés. ✔️ ?

## 7. Hors périmètre
- Rendre un site entier / suivre les liens en profondeur (on lit **une** URL, pas un crawl).
- Traiter la doc dans le pipeline documentaire OCR/RAG (F-05+) : ici c'est de la **lecture pour le tour**,
  pas de l'indexation.
- Télécharger des binaires non-image (PDF : peut venir ensuite, à part).

## 8. Préoccupations transversales
- **Auth / tenant** : l'outil suit le droit du terminal/poste ; isolation `user_id`+`host_id`.
- **Plans / limites** : la lecture consomme des jetons (texte + images au modèle) sur le quota existant ;
  bornes pour maîtriser le coût.
- **Composants** : catalogue d'outils (`buildTools`), runner (récupération + extraction readability +
  images), `AnthropicAgentProvider` (blocs image au modèle — multimodal), repli web-fetch (Provider).
