package fr.claudegateway.speech;

import java.io.IOException;
import java.util.List;

/**
 * <b>Capacité optionnelle d'une voix : l'alignement</b> (F-250 / SF-250-01) — à côté de {@link SpeechProvider}
 * (Provider Independence). Une voix qui sait dire <b>quand</b> chaque caractère est prononcé rend, dans la <b>même
 * requête</b> que l'audio, un minutage par caractère ; l'écran en tire la forme des lèvres du visage de Jarvis.
 *
 * <p>Une voix qui ne l'implémente pas (OpenAI, voix locale) garde le chemin mp3 : le visage bat alors sur le niveau
 * sonore (repli amplitude). <b>Relais, pas calcul</b> : la passerelle ne décode ni ne recode l'audio, elle le
 * transmet tel que le fournisseur l'a encodé (base64) avec son alignement.</p>
 */
public interface AlignedSpeech {

    /**
     * Lit {@code text} à voix haute, en flux, et remet chaque morceau (audio + alignement) à {@code sink} dès son
     * arrivée. Le premier morceau porte déjà du son.
     *
     * @return le nombre de morceaux relayés
     * @throws SpeechProviderUnavailableException si la voix n'est pas configurée (aucun appel émis)
     * @throws SpeechProviderException            si l'appel échoue
     */
    long synthesizeAligned(String text, String voice, String instructions, AlignedSink sink) throws IOException;

    /** Reçoit les morceaux, dans l'ordre. */
    @FunctionalInterface
    interface AlignedSink {
        void accept(AlignedChunk chunk) throws IOException;
    }

    /**
     * Un morceau : de l'audio mp3 en base64 (tel que reçu ; peut être vide) et, s'il en porte, l'alignement des
     * caractères qu'il couvre — temps <b>absolus</b> depuis le début de la phrase, en secondes. Sans alignement,
     * les trois listes sont vides.
     */
    record AlignedChunk(String audioBase64, List<String> characters, List<Double> starts, List<Double> ends) {

        public AlignedChunk {
            audioBase64 = audioBase64 == null ? "" : audioBase64;
            characters = characters == null ? List.of() : List.copyOf(characters);
            starts = starts == null ? List.of() : List.copyOf(starts);
            ends = ends == null ? List.of() : List.copyOf(ends);
        }

        /** Vrai si le morceau porte un alignement cohérent (trois listes de même longueur, non vides). */
        public boolean aligned() {
            return !characters.isEmpty() && characters.size() == starts.size() && starts.size() == ends.size();
        }
    }
}
