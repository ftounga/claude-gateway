package fr.claudegateway.dictation;

import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import fr.claudegateway.teams.meeting.stt.TranscriptionProvider;

/**
 * <b>Dicter sa demande</b> (F-145 / SF-145-01) : un extrait de voix, du texte en retour.
 *
 * <p><b>Rien n'est réimplémenté.</b> Le relais vers le service de transcription existe depuis
 * F-128 / SF-128-04 ({@link TranscriptionProvider}, Provider Independence) et il est configuré en
 * production. Cette classe ne fait que ce que la gateway doit faire : valider, relayer, et ne rien
 * garder.</p>
 *
 * <p><b>L'audio ne touche ni disque ni base.</b> Il vit dans la mémoire de la requête, le temps de
 * l'appel, et disparaît avec elle. Il n'est pas non plus journalisé : une dictée est de la parole,
 * souvent chez un client.</p>
 *
 * <p><b>Un extrait très court est ignoré</b> plutôt que transcrit : c'est un clic sur le bouton, pas
 * une dictée — et l'envoyer coûterait un appel pour rendre une chaîne vide.</p>
 */
@Service
public class DictationService {

    private static final Logger log = LoggerFactory.getLogger(DictationService.class);

    /**
     * Ce qu'un navigateur produit réellement au micro. La liste est <b>fermée</b> : accepter
     * « n'importe quel binaire » ferait de cette route un tunnel vers un service tiers.
     */
    static final Set<String> ACCEPTED_TYPES = Set.of(
            "audio/webm", "audio/ogg", "audio/mpeg", "audio/mp4", "audio/wav", "audio/x-wav",
            "audio/mp3", "audio/m4a", "audio/flac");

    /**
     * En deçà, ce n'est pas de la parole.
     *
     * <p>Un conteneur WebM vide pèse déjà quelques centaines d'octets : le seuil écarte le clic
     * malencontreux sans jamais écarter une vraie phrase, même brève.</p>
     */
    static final int MIN_AUDIO_BYTES = 2_000;

    private final TranscriptionProvider provider;
    private final fr.claudegateway.teams.meeting.stt.TranscriptionProperties properties;
    private final String languageHint;

    public DictationService(TranscriptionProvider provider,
            fr.claudegateway.teams.meeting.stt.TranscriptionProperties properties,
            @Value("${app.stt.language:}") String languageHint) {
        this.provider = provider;
        this.properties = properties;
        this.languageHint = languageHint == null || languageHint.isBlank() ? null
                : languageHint.trim();
    }

    /**
     * Transcrit un extrait dicté.
     *
     * @param audio       les octets reçus
     * @param contentType le type déclaré par le navigateur
     * @return le texte, ou une chaîne vide si l'extrait était trop court pour être de la parole
     */
    public String transcribe(byte[] audio, String contentType) {
        if (audio == null || audio.length < MIN_AUDIO_BYTES) {
            // Un clic, pas une dictée : rien ne part, et l'écran ne montre pas d'erreur pour ça.
            return "";
        }
        String type = normalize(contentType);
        if (!ACCEPTED_TYPES.contains(type)) {
            throw new UnsupportedDictationAudioException(
                    "Format audio non accepté pour la dictée : " + type);
        }
        // La taille haute est celle du service (F-128) : elle est vérifiée par la couche web, qui
        // refuse avant de charger l'extrait en mémoire.
        TranscriptionProvider.Transcript transcript =
                provider.transcribe(audio, type, languageHint);
        String text = transcript == null || transcript.text() == null ? "" : transcript.text().strip();
        // On journalise la LONGUEUR, jamais le texte : c'est la parole de l'utilisateur, et souvent
        // celle d'une mission chez un client.
        log.debug("Dictée transcrite ({} caractères)", text.length());
        return text;
    }

    /**
     * Le service est-il configuré ? Sans clé, <b>aucun octet ne part</b> — c'est le drapeau fort
     * posé par F-128, et l'écran doit pouvoir le dire avant d'ouvrir le micro.
     */
    public boolean isAvailable() {
        return properties.isConfigured();
    }

    private static String normalize(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        // « audio/webm;codecs=opus » → « audio/webm » : le paramètre de codec ne change pas le type.
        String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return type;
    }
}
