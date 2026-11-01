package fr.claudegateway.jarvis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * <b>Les clips de réaction de Jarvis</b> (F-233 / SF-233-02, D3) : quelques phrases <b>fixes</b> (« Oui, Monsieur ? »,
 * « Je regarde. », « Une seconde. ») synthétisées <b>une fois par voix</b> et gardées en mémoire, que l'écran charge à
 * l'ouverture et joue localement dès la fin de la parole — zéro appel réseau au moment de réagir.
 *
 * <p>Aucun contenu client : les textes sont des constantes. La clé du cache est la voix : changer la voix de Jarvis
 * (SF-233-07) régénère les clips d'elle-même. Le premier appel d'un clip est compté et plafonné comme une lecture ;
 * les suivants ne coûtent rien.</p>
 */
@Service
public class JarvisClipService {

    /** Les clips, par nom (liste fermée). */
    public static final Map<String, String> CLIPS = clips();

    private final JarvisVoiceService voice;
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();

    public JarvisClipService(JarvisVoiceService voice) {
        this.voice = voice;
    }

    private static Map<String, String> clips() {
        Map<String, String> clips = new LinkedHashMap<>();
        clips.put("oui", "Oui, Monsieur ?");
        clips.put("je-regarde", "Je regarde.");
        clips.put("une-seconde", "Une seconde.");
        return java.util.Collections.unmodifiableMap(clips);
    }

    /**
     * L'audio (mp3) du clip {@code name}, avec la voix de Jarvis.
     *
     * @throws IllegalArgumentException                                 clip inconnu
     * @throws fr.claudegateway.speech.SpeechProviderUnavailableException voix éteinte ou non configurée
     * @throws JarvisVoiceBudgetException                               plafond atteint (et clip pas encore en cache)
     */
    public byte[] clip(UUID userId, String name) {
        String text = name == null ? null : CLIPS.get(name);
        if (text == null) {
            throw new IllegalArgumentException("Clip inconnu.");
        }
        voice.requireVoice(userId);
        String key = voice.voice(userId) + '|' + name;
        byte[] cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] audio = voice.synthesizeClip(userId, text);
        byte[] previous = cache.putIfAbsent(key, audio);
        return previous == null ? audio : previous;
    }
}
