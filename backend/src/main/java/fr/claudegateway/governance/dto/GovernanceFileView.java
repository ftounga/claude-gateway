package fr.claudegateway.governance.dto;

/**
 * Un fichier apporté, tel que l'<b>utilisateur</b> le voit avant d'adopter un paquet
 * (F-51 / SF-51-01) : <b>où</b> il atterrira et <b>ce que c'est</b> — jamais son contenu.
 *
 * <p>C'est le cœur de l'exigence de la feature : un paquet écrit sur la machine de l'utilisateur, et
 * l'écran annonce ce qu'il va écrire et où. Le contenu, lui, n'est pas nécessaire à cette décision et
 * alourdirait chaque lecture du catalogue.</p>
 *
 * @param path chemin relatif au projet
 * @param kind {@code SKILL} ou {@code TEMPLATE}
 */
public record GovernanceFileView(String path, String kind) {
}
