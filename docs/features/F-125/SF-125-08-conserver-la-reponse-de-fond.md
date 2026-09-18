# SF-125-08 — Conserver la réponse de fond, jamais la plomberie de carte

> Cadrage du 2026-09-19 (PO), après un cas réel CAGIP **tracé en prod, sur le backend déjà porteur de
> SF-125-07**. **Cadrage seul : livraison sur go** (donné). Raffine SF-125-07.

## 1. Le constat (cas réel, tracé)
- **USER 22:15** : « trace les mails là… »
- **ASSISTANT 22:17** (backend neuf, SF-125-07 actif) : message persisté = **212 caractères** :
  *« Vérifié : la clé privée versionnée est déjà dans la carte, en `acces.md:465`… »*

Le PO a **vu défiler** l'essentiel + le détail (le modèle a bien produit la réponse de fond), **mais le
message final enregistré/affiché est une note de plomberie de carte**. La vraie réponse n'est pas dans le
message persisté.

## 2. Pourquoi SF-125-07 ne l'a pas rattrapé
SF-125-07 conserve **le dernier texte utilisateur non vide** et empêche un tour **vide**. Ici les deux
textes sont **non vides** : la réponse de fond (tôt) **puis** une note de plomberie (tard). « Garder le
dernier non vide » garde donc **la plomberie**, pas la réponse de fond. Deux causes cumulées :
1. **L'agent finit encore un tour sur de la plomberie de carte** (cœur F-125 : « déjà dans `X.md` »,
   « vérifié : déjà rangé »). F-125-05 l'interdit **côté prompt** ; ça glisse encore.
2. **La rétention serveur (SF-125-07) garde le *dernier* texte, pas le plus *substantiel*.**

## 3. Objectif (une phrase)
La réponse **persistée et affichée** d'un tour est la **réponse de fond à l'utilisateur** — jamais une note
de plomberie de carte émise plus tard dans le tour.

## 4. Comportement attendu
1. **Signal fort = le bloc `<<essentiel>>` (F-126).** Si un bloc `<<essentiel>>` a été émis dans le tour,
   **c'est lui (+ son détail) la réponse conservée**, même si un texte **postérieur** (sans essentiel) a
   été émis ensuite. Un texte postérieur ne peut pas évincer un essentiel déjà produit.
2. **Rétention par substance, pas par ordre.** À défaut d'essentiel, conserver le **texte utilisateur
   substantiel** du tour ; ne pas retenir comme réponse finale un **dernier bloc reconnaissable comme
   plomberie de carte** (formules « déjà dans `X.md` », « vérifié : déjà rangé », « la clé/le fait est déjà
   dans la carte », un simple `fichier.md:ligne` en guise de réponse).
3. **F-125-05 côté serveur.** L'interdiction « un statut de rangement n'est jamais une réponse » cesse
   d'être seulement une consigne de prompt : la **rétention serveur** l'applique (une note de plomberie
   seule n'est jamais promue en réponse du tour).
4. **Coexistence SF-125-07** : « jamais un tour vide » reste ; SF-125-08 raffine **quel** texte garder
   (le fond, pas le dernier).

## 5. Cas d'erreur / limites
- **Aucun essentiel + aucun texte substantiel** (le tour n'a produit que de la plomberie) → repli
  SF-125-07 (synthèse forcée) plutôt que d'afficher la plomberie.
- **Plusieurs blocs essentiels** dans le tour → conserver le dernier essentiel (c'est une réponse de fond,
  pas de la plomberie).
- La détection « plomberie » reste **conservatrice** (liste de formules + absence d'essentiel) : en cas de
  doute, garder le texte (ne jamais masquer une vraie réponse).

## 6. Portée & implémentation (esquisse)
- **`AtelierChatService.runLoop`** : remplacer « dernier texte non vide » par « **texte de fond du tour** » :
  privilégier le(s) bloc(s) `<<essentiel>>` (réutiliser `splitEssential`, F-126) ; sinon le texte
  substantiel ; exclure un dernier bloc identifié comme plomberie (petit + formules connues + sans
  essentiel). C'est ce texte qui est **persisté** et **rendu** (SSE final + base convergents, cf. 125-07).
- Aucune table, migration, endpoint, ni frontend nouveau (le front rend déjà `<<essentiel>>`/détail).

## 7. Critères d'acceptation
- **Scénario du cas réel** : un tour émet `<<essentiel>>`+détail **puis** une note de plomberie de carte →
  la réponse persistée/affichée = **l'essentiel+détail**, pas la plomberie (test d'intégration).
- Une note de plomberie seule ne devient **jamais** la réponse du tour (repli synthèse si rien d'autre).
- Non-régression : SF-125-07 (jamais vide), F-126 (`<<essentiel>>`), F-125-01/05, F-119/120 intacts.

## 8. Hors périmètre
- Le **fond** du raisonnement (F-119) et la distinction question/action (F-120).
- La reprise d'un tour dont le **flux s'est détaché** côté client → c'est **F-131** (rejouer la dernière
  requête), complémentaire.

## 9. Préoccupations transversales
- **Composants** : `AtelierChatService.runLoop`, réutilisation `splitEssential` (F-126), rétention/rendu du
  message final. Auth/tenant/routing : non touchés.
