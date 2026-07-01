package fr.claudegateway.export;

import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;

import fr.claudegateway.chat.Conversation;
import fr.claudegateway.chat.Message;
import fr.claudegateway.chat.MessageRole;
import fr.claudegateway.export.dto.AnswerCitation;
import fr.claudegateway.export.dto.AnswerExportRequest;

/**
 * Rendu PDF d'une conversation ou d'une réponse documentée (F-14). Le moteur PDF (OpenPDF, pur Java)
 * est <b>confiné à cette classe</b> : aucune autre partie du domaine n'en dépend. Rendu texte
 * structuré (pas de moteur HTML/CSS). Composant sans état.
 */
@Component
public class PdfExporter {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Font TITLE = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
    private static final Font META = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9);
    private static final Font HEADING = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
    private static final Font BODY = FontFactory.getFont(FontFactory.HELVETICA, 11);
    private static final Font CITATION = FontFactory.getFont(FontFactory.COURIER, 9);

    /** Fil d'une conversation : métadonnées + messages ordonnés. */
    public byte[] conversation(Conversation conversation, List<Message> messages) {
        return render(document -> {
            document.add(title(safe(conversation.getTitle())));
            document.add(meta("Exporté depuis Claude Gateway le " + now()
                    + "  |  Modèle : " + safe(conversation.getModel())));
            document.add(spacer());
            for (Message message : messages) {
                String header = roleLabel(message.getRole());
                if (message.getCreatedAt() != null) {
                    header += " — " + message.getCreatedAt().format(DATE);
                }
                document.add(new Paragraph(header, HEADING));
                document.add(new Paragraph(safe(message.getContent()), BODY));
                document.add(spacer());
            }
        });
    }

    /** Réponse documentée : question, réponse, statut d'ancrage et sources citées. */
    public byte[] answer(AnswerExportRequest request) {
        return render(document -> {
            document.add(title("Réponse documentée"));
            StringBuilder metaLine = new StringBuilder("Exporté depuis Claude Gateway le ").append(now());
            if (request.model() != null && !request.model().isBlank()) {
                metaLine.append("  |  Modèle : ").append(request.model());
            }
            metaLine.append("  |  ").append(Boolean.TRUE.equals(request.grounded())
                    ? "Réponse ancrée sur vos documents" : "Réponse non ancrée");
            document.add(meta(metaLine.toString()));
            document.add(spacer());

            document.add(new Paragraph("Question", HEADING));
            document.add(new Paragraph(safe(request.question()), BODY));
            document.add(spacer());
            document.add(new Paragraph("Réponse", HEADING));
            document.add(new Paragraph(safe(request.answer()), BODY));

            List<AnswerCitation> citations = request.safeCitations();
            if (!citations.isEmpty()) {
                document.add(spacer());
                document.add(new Paragraph("Sources", HEADING));
                for (AnswerCitation citation : citations) {
                    document.add(new Paragraph(
                            "[" + MarkdownExporter.reference(citation) + "] " + safe(citation.snippet()),
                            CITATION));
                }
            }
        });
    }

    /** Instructions de remplissage d'un document PDF ouvert. */
    private interface PdfContent {
        void write(Document document) throws DocumentException;
    }

    private byte[] render(PdfContent content) {
        Document document = new Document();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(document, out);
            document.open();
            content.write(document);
        } catch (DocumentException e) {
            // Message générique : aucune donnée sensible ni stacktrace ne remonte au client.
            throw new IllegalStateException("Échec du rendu PDF", e);
        } finally {
            if (document.isOpen()) {
                document.close();
            }
        }
        return out.toByteArray();
    }

    private static Paragraph title(String text) {
        Paragraph p = new Paragraph(text, TITLE);
        p.setSpacingAfter(4f);
        return p;
    }

    private static Paragraph meta(String text) {
        return new Paragraph(text, META);
    }

    private static Paragraph spacer() {
        Paragraph p = new Paragraph(" ", BODY);
        p.setSpacingAfter(4f);
        p.setAlignment(Element.ALIGN_LEFT);
        return p;
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
