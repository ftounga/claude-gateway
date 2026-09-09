package fr.claudegateway.help.dto;

/**
 * Réponse du chatbot d'aide produit (F-54 / SF-54-01).
 *
 * @param answer réponse fondée sur la documentation d'aide, en Markdown court
 */
public record HelpChatResponse(String answer) {
}
