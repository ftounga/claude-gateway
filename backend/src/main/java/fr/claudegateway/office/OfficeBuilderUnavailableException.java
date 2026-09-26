package fr.claudegateway.office;

/**
 * Le constructeur de documents n'a pas répondu (F-129 / SF-129-07). <b>Distinct d'une description
 * invalide</b> : l'agent peut alors retomber sur {@code python-docx} / {@code openpyxl} <b>s'ils
 * sont présents</b> sur le poste — sinon il le dit, et n'installe rien.
 */
public class OfficeBuilderUnavailableException extends RuntimeException {

    public OfficeBuilderUnavailableException(String message) {
        super(message);
    }
}
