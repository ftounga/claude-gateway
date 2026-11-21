# Recette guidée F-270 — « Parler à Jarvis pendant qu'il travaille »

Pour l'essai du PO sur `/jarvis`, conversation mains libres, poste CAGIP **allumé** (sauf l'étape 7). Dire chaque phrase
telle quelle ; l'attendu est ce que Jarvis dit **et** ce que l'écran montre (aucune phrase écrite à l'écran : des
satellites autour du cœur, un nom court chacun).

| # | Vous dites | Jarvis dit (≈) | L'écran montre | Vérifie |
|---|---|---|---|---|
| 1 (S1) | « Jarvis, regarde dans data-platform s'il y a des tickets du backlog que je peux prendre. » | « Je regarde le backlog de data-platform, Monsieur. » (≤ 1,5 s après la fin de votre phrase en régime chaud) | un satellite **violet** `data-platform` part en orbite | le micro reste ouvert (anneau d'écoute) |
| 2 (S1) | 5 s après : « Et sur agenor, où en est le chantier 4 ? » | une réponse **tout de suite** (ou « Je regarde… » + second satellite `chantier 4`) | deux satellites | la première recherche continue (son satellite tourne) |
| 3 (S3 précision) | « Et regarde aussi ceux du sprint 10. » | « J'ajoute ça à la recherche en cours : … » | pas de nouveau satellite | précision, pas nouvelle commande |
| 4 (S3 nouvelle) | « Et relance le pipeline de dev de data-ingestion. » | « Je le lance dès que … est fini. » | satellite **creux** (en attente) | parti seul à la fin de la lecture, puis violet |
| 5 (S4) | Pendant que Jarvis réfléchit à une réponse (2-3 s), enchaîner : « Et pour EDENRED ? » | il finit sa réponse, puis répond à « Et pour EDENRED ? » | — | **rien n'est perdu** |
| 6 (S4) | Pendant qu'il réfléchit : « Laisse tomber. » | « Entendu. » ; la réponse en cours n'est pas dite | — | annulation immédiate |
| 7 (S2) | Une demande qui engage (« lance LDIC-325 en autonome » s'il la reformule avec « Vous confirmez ? ») puis « Oui. » | « C'est parti. » | satellite **cyan** (attend votre oui) puis **violet/or** | **le micro est déjà rouvert** : « Montre-moi mes rendez-vous de demain » répond aussitôt |
| 8 (D2) | Deux confirmations en attente, puis « Oui. » | « LDIC-325 ou agenor ? » | deux satellites cyan | rien n'est lancé tant que vous ne nommez pas |
| 9 (S5) | « Qu'est-ce qui tourne ? » | une phrase courte depuis l'état | les satellites, chacun nommé | — |
| 10 (S6) | « Arrête data-platform. » | « J'arrête data-platform. » | le satellite s'éteint | seul ce travail s'arrête |
| 11 (S7) | Poste CAGIP éteint : « Relance le pipeline de dev de data-ingestion. » | « Le poste CAGIP est éteint, je ne peux pas agir. » | — | **rien n'est gardé pour plus tard** |

Teintes : violet = réfléchit · **or = exécute sur le poste** · cyan = attend votre « oui » · creux = attend son tour ·
vert = fini (s'éteint en 20 s) · rouge = échec.

À noter pendant l'essai (pour la mesure) : une phrase non entendue, un « oui » parti sur le mauvais travail, une
attente d'écoute après un « oui ».
