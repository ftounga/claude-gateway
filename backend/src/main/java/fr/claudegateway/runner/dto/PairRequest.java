package fr.claudegateway.runner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /runner/pair} : le code d'appairage, un libellé facultatif pour le jeton, et
 * le <b>nom du dossier</b> que le runner a reçu en {@code --workspace} (F-38 / SF-38-15).
 *
 * <p>{@code rootName} est le <b>dernier segment</b> du chemin, jamais le chemin absolu : c'est le
 * runner qui déclare sa racine, et la gateway n'a besoin que d'un libellé à afficher. Facultatif —
 * un runner antérieur à SF-38-15 ne l'envoie pas, et l'appairage reste valide.</p>
 */
public record PairRequest(
        @NotBlank @Size(max = 8) String code,
        @Size(max = 100) String label,
        @Size(max = 255) String rootName) {
}
