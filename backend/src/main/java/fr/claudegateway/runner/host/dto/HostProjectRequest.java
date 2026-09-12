package fr.claudegateway.runner.host.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
 *
 * <p><b>Tolérant aux champs inconnus (F-81 / SF-81-02).</b> Règle uniforme sur tout le paquet du
 * canal runner : un corps de requête ignore ce qu'il ne connaît pas plutôt que de refuser la
 * demande entière. Aucune exception, parce qu'une exception aurait demandé une liste d'exceptions —
 * et c'est une liste tenue à la main qui a laissé {@code StoredToken} être le seul DTO strict du
 * runner, jusqu'à la panne d'appairage du 2026-09-10.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HostProjectRequest(@Size(max = 512) String path) {
}
