package fr.claudegateway.radar.analysis;

import fr.claudegateway.radar.RadarEvidence;
import fr.claudegateway.radar.RadarText;

/**
 * La citation d'une preuve (F-101 / SF-101-03) : <b>toujours tirée du message</b>.
 *
 * <p>Le modèle peut proposer la phrase qui compte ; elle n'est gardée que si elle figure mot pour mot
 * dans le message (espaces normalisés). Sinon, c'est le début du message qui sert. Une citation qui
 * n'est pas dans la source serait un fait sans preuve déguisé.</p>
 */
final class RadarQuotes {

    private RadarQuotes() {
    }

    /**
     * @param proposed la citation proposée, ou {@code null}
     * @param message  le texte du message
     * @return une citation d'au plus {@value RadarEvidence#MAX_QUOTE_LENGTH} caractères, présente dans le message
     */
    static String pick(String proposed, String message) {
        if (proposed != null) {
            String quote = normalize(proposed);
            if (!quote.isEmpty() && quote.length() <= RadarEvidence.MAX_QUOTE_LENGTH
                    && normalize(message).contains(quote)) {
                return quote;
            }
        }
        return RadarText.quote(normalize(message));
    }

    static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }
}
