package fr.claudegateway.jarvis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

/**
 * <b>Les clips de réaction de Jarvis</b> (F-233 / SF-233-02, D3 ; répertoire SF-233-11) : un répertoire <b>fermé</b> de
 * formules courtes au vouvoiement — réveil (« Oui ? », « Je vous écoute. »…) et attente (« Je regarde. », « Un
 * instant. », « Je m'en occupe. »…) — synthétisées <b>une fois par voix</b> et gardées en mémoire, que l'écran charge à
 * l'ouverture et varie (jamais deux fois la même de suite, « Monsieur » rarement) — zéro appel réseau au moment de
 * réagir. Les noms et textes sont les mêmes que {@code frontend/src/app/jarvis/jarvis-phrases.ts}.
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
        // Réveil.
        clips.put("oui", "Oui, Monsieur ?");
        clips.put("oui-court", "Oui ?");
        clips.put("je-vous-ecoute", "Je vous écoute.");
        clips.put("dites-moi", "Dites-moi.");
        clips.put("allez-y", "Allez-y.");
        clips.put("a-votre-ecoute", "À votre écoute.");
        // Attente (une au plus par échange, et aucune si la réponse arrive vite).
        clips.put("je-regarde", "Je regarde.");
        clips.put("une-seconde", "Une seconde.");
        clips.put("un-instant", "Un instant.");
        clips.put("je-m-en-occupe", "Je m’en occupe.");
        clips.put("voyons", "Voyons cela.");
        clips.put("je-verifie", "Je vérifie.");
        clips.put("en-cours", "C’est en cours.");
        clips.put("laissez-moi", "Laissez-moi regarder.");
        clips.put("je-m-en-charge", "Je m’en charge, Monsieur.");
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
