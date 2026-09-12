/**
 * <b>Tout ce que le produit sait de Teams vit ici, et nulle part ailleurs</b> (F-87 / SF-87-01).
 *
 * <p>Quelles URL portent quoi, quels champs lire, comment paginer : ces trois savoirs sont enfermés
 * dans ce paquet. Le reste du produit — l'aiguilleur d'outils, la gateway, l'écran — ne connaît que
 * les objets <b>à nous</b> déclarés ici : {@link fr.claudegateway.runner.teams.TeamsMessage},
 * {@link fr.claudegateway.runner.teams.TeamsConversation},
 * {@link fr.claudegateway.runner.teams.TeamsMeeting},
 * {@link fr.claudegateway.runner.teams.TeamsMention},
 * {@link fr.claudegateway.runner.teams.TeamsParticipant} — et
 * {@link fr.claudegateway.runner.teams.TeamsGap}, qui porte <b>ce qui n'a pas pu être lu</b>.</p>
 *
 * <p>C'est le principe {@code AIProvider} de l'architecture, appliqué à Teams : le jour où Microsoft
 * change quelque chose, <b>un seul paquet</b> est à corriger. La règle est tenue par le compilateur
 * ({@code TeamsAdapterV1}, {@code TeamsUrls} et {@code TeamsJson} ne sont pas publics) et par un
 * test d'architecture qui interdit toute mention de Teams ailleurs dans le runner.</p>
 *
 * <p><b>La règle qui prime sur toutes les autres</b> : échouer bruyamment, jamais à moitié faux. Un
 * objet n'est rendu que si <b>tous</b> ses champs obligatoires ont été lus ; sinon il ne l'est pas,
 * et un manque le dit. Un adaptateur cassé qui rendrait la moitié des messages serait pire qu'un
 * adaptateur qui refuse : il produirait un compte rendu plausible et faux, sur lequel on déciderait.</p>
 *
 * <p><b>Sécurité, tenue par construction</b> : l'adaptateur recopie <b>uniquement</b> les champs
 * qu'il déclare connaître. Aucun cookie, aucun jeton Microsoft ne peut donc franchir cette couche,
 * même si Microsoft en ajoutait un demain dans un corps de réponse — c'est vérifié par test.</p>
 */
package fr.claudegateway.runner.teams;
