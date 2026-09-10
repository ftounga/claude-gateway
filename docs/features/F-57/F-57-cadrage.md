# Cadrage — F-57 — Transparence sur le poste de travail

> Cadrage de la feature `F-57` (`docs/PRODUCT_SPEC.md`, ligne 167). Écrit le 2026-09-10, le jour du
> banc d'essai runner (`docs/features/F-38/BANC-ESSAI-RUNNER-2026-09-10.md`).

---

## 1. La question posée, et la réponse

Le PO demande : *« le runner peut-il savoir si des outils scannent l'utilisation d'un shell ? »*

**Non — et il ne doit pas chercher.** Trois raisons, dans cet ordre :

1. **Techniquement**, un EDR travaille *sous* le processus (hooks noyau, ETW sur Windows, ESF sur
   macOS, eBPF/auditd sur Linux). Aucune API ne répond « tu es observé » : l'observation est faite
   pour ne pas se voir.
2. **Stratégiquement**, énumérer les agents de sécurité installés — lister les services, sonder les
   pilotes, interroger le registre — est de la **reconnaissance de défenses**. C'est le comportement
   même qu'un EDR classe comme malveillant. Un runner qui le ferait se signalerait comme suspect sur
   **chaque parc client** : le prix serait le produit lui-même.
3. **Moralement**, chercher à savoir ce qui surveille un poste, c'est se placer du côté de celui qui
   veut échapper à la surveillance. Ce n'est pas la position du produit.

L'angle retenu est donc **l'inverse** : la **transparence**. Le runner ne cherche pas à savoir ce
qu'on voit de lui — il **dit ce qu'il fait**, à celui qui le lance.

## 2. Ce que la feature livre

| | |
|---|---|
| **Le runner se déclare au démarrage** | Ce qu'il fait, sous quels droits (`Privileges`, SF-38-18), par quelle route réseau (direct, proxy déclaré, relais local), et qu'il ne cherche rien de ce qui l'observe. |
| **L'application rappelle périodiquement** | Toutes les 2 h par défaut, **réglable**, **jamais bloquant** : sur un poste d'entreprise, les commandes sont vraisemblablement journalisées par l'employeur. C'est un rappel de **responsabilité**, pas une alerte de menace. |
| **L'interception TLS est annoncée** | Une racine non publique dans la chaîne du serveur prouve qu'un équipement déchiffre et re-signe le trafic. Détection **légitime et diagnostique** : le runner l'**affiche**, il ne la contourne pas. |

## 3. Hors périmètre — explicite et définitif

- **Détecter** un dispositif de sécurité : énumération de services, de pilotes, de processus, de
  clés de registre, de fichiers d'agents connus. Aucune ligne de code de cette nature.
- **Contourner** quoi que ce soit : pas de désactivation de vérification TLS, pas de canal alternatif
  pour « passer » un proxy, pas d'`--insecure` caché.
- **Masquer** quoi que ce soit : le runner n'efface aucune trace, ne renomme aucun processus, ne
  change aucun libellé pour se rendre moins visible.
- La journalisation **côté gateway** de ce que le runner exécute : elle existe déjà (journal d'audit,
  F-38), F-57 n'y touche pas.

## 4. Décisions de cadrage

| # | Sujet | Décision | Réversible |
|---|---|---|---|
| 1 | Direction de la détection | On ne détecte **que** ce qui nous concerne : la route de **nos propres** connexions. Jamais l'état du poste | non — c'est la ligne rouge de la feature |
| 2 | Interception TLS — signal | Racine de la chaîne présentée par la gateway **absente du magasin livré avec le JDK**. Un faux négatif (racine d'entreprise ajoutée au magasin système) est acceptable ; un faux positif ne l'est pas | oui |
| 3 | Interception TLS — conduite | **Afficher**, jamais agir. Aucune vérification n'est relâchée, la connexion suit exactement le même chemin qu'avant | non — sécurité |
| 4 | Rappel employeur — ton | *« vraisemblablement journalisées »*, jamais *« vous êtes surveillé »*. Le produit ne sait pas, et ne prétend pas savoir | oui |
| 5 | Rappel employeur — forme | Bandeau **non modal**, en bas de l'Atelier, fermable d'un geste. Il ne bloque aucun envoi, aucune commande | oui |
| 6 | Rappel employeur — mémoire | `localStorage`, comme le guide d'accueil (F-53, décision 5). Aucune persistance serveur : la périodicité est un confort de poste, pas une donnée de compte | oui |
| 7 | Périodicité | 2 h par défaut ; réglages **2 h / 8 h / 24 h / jamais**, depuis le bandeau lui-même | oui |
| 8 | Secrets | La route affichée est **expurgée** : un `HTTPS_PROXY` de la forme `http://user:motdepasse@hote:port` s'affiche `hote:port`. Un écran de transparence qui divulgue un mot de passe serait une régression de sécurité | non — sécurité |

## 5. Découpage

| SF | Titre | Portée |
|---|---|---|
| SF-57-01 | La déclaration de démarrage | Runner — bloc de transparence unique : ce qu'il fait, sous quels droits, par quelle route, et ce qu'il ne cherche pas |
| SF-57-02 | L'interception TLS, annoncée sans être contournée | Runner — racine non publique dans la chaîne de la gateway, affichée en diagnostic |
| SF-57-03 | Le rappel de journalisation | Frontend — bandeau périodique (2 h, réglable, non bloquant) dans l'Atelier |

Aucune subfeature backend : F-57 n'ajoute aucun endpoint, aucune table, aucune migration.

## 6. Ce que F-57 ne promet pas

Le rappel dit *« vraisemblablement »* parce que c'est la vérité : le produit **ne sait pas** si ce
poste est journalisé. Il sait seulement que sur un poste d'entreprise, il l'est presque toujours.
Transformer ce rappel en verdict — « ce poste est surveillé », ou pire « ce poste ne l'est pas » —
serait mentir dans les deux sens.
