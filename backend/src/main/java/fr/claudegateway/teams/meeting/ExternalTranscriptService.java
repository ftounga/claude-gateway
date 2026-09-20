package fr.claudegateway.teams.meeting;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.docx.DocxExtraction;
import fr.claudegateway.docx.DocxTextExtractor;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.teams.meeting.dto.MeetingResponse;

/**
 * La <b>transcription externe (client)</b> d'une réunion (F-128 / SF-128-20a) — <b>Gateway-First</b> :
 * la gateway <b>orchestre</b> (valide, met à plat un {@code .docx}, stocke tel quel, tient l'isolation),
 * elle ne transcrit rien. C'est la transcription <b>déjà attribuée aux vrais noms</b> que l'utilisateur
 * récupère chez le client (Teams) et apporte par collage ou dépôt de fichier.
 *
 * <p><b>Distincte de notre transcription.</b> {@link Meeting#getTranscript()} (SF-128-04, OpenAI) reste
 * inchangée. Ici on remplit un second jeu de colonnes ; la consolidation des deux (+ images) arrive en
 * SF-128-20b.</p>
 *
 * <p><b>Isolation.</b> Toute méthode reçoit un {@link RadarScope} déjà résolu (possession + activation
 * Vigie contrôlées par le controller) ; toute lecture/écriture est filtrée {@code user_id} ET
 * {@code host_id} — une réunion d'un autre couple est introuvable (404 indiscernable).</p>
 *
 * <p><b>Contenu = donnée.</b> Le texte apporté n'est jamais interprété comme une instruction ; il est
 * stocké tel quel. L'anti-injection s'applique à l'exploitation (SF-128-20b).</p>
 */
@Service
public class ExternalTranscriptService {

    private final MeetingRepository repository;
    private final DocxTextExtractor docxTextExtractor;

    public ExternalTranscriptService(MeetingRepository repository, DocxTextExtractor docxTextExtractor) {
        this.repository = repository;
        this.docxTextExtractor = docxTextExtractor;
    }

    /**
     * Attache (ou remplace) une transcription externe <b>collée</b>.
     *
     * @throws MeetingNotFoundException   réunion inconnue / hors périmètre
     * @throws MeetingValidationException texte vide
     */
    public MeetingResponse attachText(RadarScope scope, UUID meetingId, String rawText, String rawSource) {
        Meeting meeting = require(scope, meetingId);
        String text = normalizeText(rawText);
        return store(meeting, text, ExternalTranscriptFormat.TEXT, rawSource);
    }

    /**
     * Attache (ou remplace) une transcription externe <b>déposée en fichier</b>. Le format est déduit
     * du contenu (un {@code .docx} est un zip, détecté par {@link DocxTextExtractor#looksLikeDocx}) puis
     * du nom ({@code .vtt}), sinon texte brut UTF-8. Un {@code .docx} est mis à plat en texte ; le reste
     * est stocké tel quel.
     *
     * @throws MeetingNotFoundException   réunion inconnue / hors périmètre
     * @throws MeetingValidationException fichier vide / texte vide après lecture
     * @throws fr.claudegateway.docx.InvalidDocxException   {@code .docx} illisible ou hors garde-fous (→ 422)
     */
    public MeetingResponse attachFile(RadarScope scope, UUID meetingId, String filename, byte[] content) {
        Meeting meeting = require(scope, meetingId);
        if (content == null || content.length == 0) {
            throw new MeetingValidationException("Le fichier de transcription est vide.");
        }
        String lowerName = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        String text;
        ExternalTranscriptFormat format;
        if (docxTextExtractor.looksLikeDocx(content)) {
            DocxExtraction extraction = docxTextExtractor.extract(content); // peut lever InvalidDocxException (422)
            text = normalizeText(extraction.text());
            format = ExternalTranscriptFormat.DOCX;
        } else {
            text = normalizeText(new String(content, StandardCharsets.UTF_8));
            format = lowerName.endsWith(".vtt") ? ExternalTranscriptFormat.VTT : ExternalTranscriptFormat.TEXT;
        }
        // Le libellé de source par défaut porte le format déposé, sinon le défaut générique.
        return store(meeting, text, format, filename == null || filename.isBlank() ? null : filename);
    }

    /** Le texte de la transcription externe, ou vide (→ 404 côté controller). */
    public Optional<String> externalTranscript(RadarScope scope, UUID meetingId) {
        Meeting meeting = require(scope, meetingId);
        return Optional.ofNullable(meeting.getExternalTranscript()).filter(t -> !t.isBlank());
    }

    /** Retire la transcription externe (les 4 colonnes vidées). Idempotent. */
    public MeetingResponse clear(RadarScope scope, UUID meetingId) {
        Meeting meeting = require(scope, meetingId);
        meeting.setExternalTranscript(null);
        meeting.setExternalTranscriptSource(null);
        meeting.setExternalTranscriptFormat(null);
        meeting.setExternalTranscriptAddedAt(null);
        return MeetingResponse.of(repository.save(meeting));
    }

    // ------------------------------------------------------------------ interne

    private MeetingResponse store(Meeting meeting, String text, ExternalTranscriptFormat format, String rawSource) {
        meeting.setExternalTranscript(text);
        meeting.setExternalTranscriptFormat(format);
        meeting.setExternalTranscriptSource(normalizeSource(rawSource));
        meeting.setExternalTranscriptAddedAt(java.time.OffsetDateTime.now());
        return MeetingResponse.of(repository.save(meeting));
    }

    private static String normalizeText(String raw) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) {
            throw new MeetingValidationException("La transcription externe est vide.");
        }
        if (text.length() > Meeting.MAX_EXTERNAL_TRANSCRIPT_CHARS) {
            // On tronque plutôt que de refuser : une transcription réelle très longue reste exploitable.
            return text.substring(0, Meeting.MAX_EXTERNAL_TRANSCRIPT_CHARS);
        }
        return text;
    }

    private static String normalizeSource(String raw) {
        String source = raw == null ? "" : raw.strip();
        if (source.isEmpty()) {
            return Meeting.DEFAULT_EXTERNAL_TRANSCRIPT_SOURCE;
        }
        if (source.length() > Meeting.MAX_EXTERNAL_TRANSCRIPT_SOURCE_LENGTH) {
            return source.substring(0, Meeting.MAX_EXTERNAL_TRANSCRIPT_SOURCE_LENGTH);
        }
        return source;
    }

    private Meeting require(RadarScope scope, UUID meetingId) {
        return repository.findByIdAndUserIdAndHostId(meetingId, scope.userId(), scope.hostId())
                .orElseThrow(() -> new MeetingNotFoundException("Réunion introuvable : " + meetingId));
    }
}
