package fr.claudegateway.runner.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /runner/pair} : le code d'appairage du <b>poste</b>, un libellé facultatif
 * pour le jeton, et ce que le runner déclare de sa machine (F-48 / SF-48-01).
 *
 * <p>{@code rootName} est le <b>dernier segment</b> de la racine, jamais le chemin absolu : c'est le
 * runner qui déclare sa racine, et la gateway n'a besoin que d'un libellé à afficher. {@code os} et
 * {@code elevated} disent le système et les droits sous lesquels le runner tourne (F-38 / SF-38-18) :
 * la gateway ne peut pas les deviner, et c'est au moment d'autoriser une commande qu'ils comptent.</p>
 *
 * <p>Tout est facultatif sauf le code — un runner qui ne déclare rien reste appairable.</p>
 */
public record PairRequest(
        @NotBlank @Size(max = 8) String code,
        @Size(max = 100) String label,
        @Size(max = 255) String rootName,
        @Size(max = 64) String os,
        Boolean elevated) {
}
