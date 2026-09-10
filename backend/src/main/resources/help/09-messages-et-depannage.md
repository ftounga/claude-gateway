# Messages d'erreur et dépannage

## Au lancement du runner

| Ce qui s'affiche | Cause | Remède |
|---|---|---|
| `A JNI error has occurred` / `UnsupportedClassVersionError` | Machine virtuelle Java trop ancienne (souvent Java 8) | Prendre le paquet autonome, ou installer un JDK 21 |
| `La gateway n'est pas joignable : UnknownHostException` | Le nom n'est pas résolu — DNS, ou proxy d'entreprise obligatoire absent du terminal | Déclarer le proxy dans **ce** terminal |
| `407` au contrôle réseau | Le proxy exige une authentification | Relais local d'authentification, puis `HTTPS_PROXY` vers `127.0.0.1` |
| `Réseau : gateway joignable` | Rien à faire, le contrôle est passé | — |
| Appairage refusé | Code expiré (5 minutes), déjà utilisé, ou mal recopié | Générer un nouveau code |
| Le runner redemande un code | Le jeton a été révoqué ou a expiré, et il a donc été effacé | Générer un code et relancer avec `--code` |
| Racine mémorisée disparue | Le dossier du projet a été déplacé ou supprimé | Relancer en précisant la racine |
| Chemin tronqué sous Windows | Espaces dans le chemin, non protégés par des guillemets | Entourer le chemin de guillemets |

## Les codes de sortie du runner

| Code | Signification |
|---|---|
| `0` | Arrêt propre (`Ctrl-C`) |
| `1` | Erreur inattendue |
| `2` | Configuration invalide : option requise absente, dossier inexistant |
| `3` | Appairage refusé ou passerelle injoignable |
| `4` | Jeton refusé et aucun code d'appairage fourni |

## Dans l'application

| Ce qui s'affiche | Cause | Remède |
|---|---|---|
| « Machine non connectée » | Le runner n'est pas lancé, ou sa connexion est tombée | Relancer le runner dans le dossier du projet |
| Commandes refusées | La machine a été lancée avec `--no-bash` | Relancer sans cette option |
| Service momentanément indisponible | Le fournisseur n'a pas répondu | Réessayer dans quelques instants |
| Quota atteint | L'allocation de la période est épuisée | Attendre le renouvellement, recharger, ou changer d'offre |
| Une clé personnelle est demandée | L'offre BYOK est active sans clé enregistrée | Enregistrer une clé dans les Réglages |

## Réflexes utiles

1. **Vérifier la sortie réseau avant tout** : c'est la cause la plus fréquente, et la commande de
   contrôle prend dix secondes.
2. **Relancer le runner après avoir modifié `.runnerignore`** : les règles sont lues au démarrage.
3. **Un code de sortie non nul d'une commande n'est pas une panne du produit** : la commande a
   tourné, elle a échoué, et son message dit pourquoi.
4. **Le journal d'audit** de la Forge trace chaque appel exécuté sur la machine, refus compris :
   c'est là qu'on voit ce qui s'est réellement passé.
