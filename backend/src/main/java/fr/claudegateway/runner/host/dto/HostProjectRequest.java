package fr.claudegateway.runner.host.dto;

import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /runner-hosts/{hostId}/projects} (F-72 / SF-72-01) : <b>un chemin, et rien
 * d'autre</b>.
 *
 * <p>Pas de nom (décision D5 du PO) : le projet prend celui de son <b>dossier</b>, et celui du
 * <b>poste</b> quand c'est la racine. C'est la question de trop du parcours d'avant — celle qui a
 * produit deux entités nommées « EDENRED » : le client était déjà nommé à la connexion du poste,
 * et le dossier porte déjà le nom que l'utilisateur aurait retapé.</p>
 *
 * <p>{@code path} absent ou vide désigne la <b>racine du poste</b> : c'est un choix légitime, un
 * poste pouvant n'héberger qu'un projet.</p>
 */
public record HostProjectRequest(@Size(max = 512) String path) {
}
