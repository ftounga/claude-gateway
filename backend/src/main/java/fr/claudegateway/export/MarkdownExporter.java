package fr.claudegateway.export;

import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

import fr.claudegateway.chat.Conversation;
import fr.claudegateway.chat.Message;
import fr.claudegateway.chat.MessageRole;
import fr.claudegateway.export.dto.AnswerCitation;
import fr.claudegateway.export.dto.AnswerExportRequest;

/**
 * Rendu Markdown d'une conversation ou d'une réponse documentée (F-14). Composant sans état ; ne
 * connaît que les données qui lui sont passées (déjà filtrées {@code user_id} en amont).
 */
@Component
public class MarkdownExporter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Fil d'une conversation : métadonnées + messages ordonnés. */
    public String conversation(Conversation conversation, List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(safe(conversation.getTitle())).append("\n\n");
        sb.append("> Exporté depuis Claude Gateway le ").append(now()).append("  \n");
        sb.append("> Modèle : ").append(safe(conversation.getModel())).append("\n\n");
        sb.append("---\n\n");
        for (Message message : messages) {
            sb.append("## ").append(roleLabel(message.getRole()));
            if (message.getCreatedAt() != null) {
                sb.append(" — ").append(message.getCreatedAt().format(DATE));
            }
            sb.append("\n\n");
            sb.append(safe(message.getContent())).append("\n\n");
        }
        return sb.toString();
    }

    /** Réponse documentée : question, réponse, statut d'ancrage et sources citées. */
    public String answer(AnswerExportRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Réponse documentée\n\n");
        sb.append("> Exporté depuis Claude Gateway le ").append(now()).append("  \n");
        if (request.model() != null && !request.model().isBlank()) {
            sb.append("> Modèle : ").append(request.model()).append("  \n");
        }
        sb.append("> ").append(Boolean.TRUE.equals(request.grounded())
                ? "Réponse ancrée sur vos documents" : "Réponse non ancrée").append("\n\n");
        sb.append("## Question\n\n").append(safe(request.question())).append("\n\n");
        sb.append("## Réponse\n\n").append(safe(request.answer())).append("\n\n");

        List<AnswerCitation> citations = request.safeCitations();
        if (!citations.isEmpty()) {
            sb.append("## Sources\n\n");
            for (AnswerCitation citation : citations) {
                sb.append("- `[").append(reference(citation)).append("]` ")
                        .append(safe(citation.snippet())).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Référence lisible {@code filename:page:chunkIndex} (page omise si absente). */
    static String reference(AnswerCitation citation) {
        StringBuilder ref = new StringBuilder(safe(citation.filename()));
        ref.append(':').append(citation.page() == null ? "-" : citation.page());
        ref.append(':').append(citation.chunkIndex() == null ? "-" : citation.chunkIndex());
        return ref.toString();
    }

    private static String roleLabel(MessageRole role) {
        return role == MessageRole.ASSISTANT ? "Assistant" : "Vous";
    }

    private static String now() {
        return java.time.OffsetDateTime.now().format(DATE);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
