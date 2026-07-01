package fr.claudegateway.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Découpe un texte en fragments de taille bornée avec chevauchement (F-06 / SF-06-01).
 *
 * <p>La taille est exprimée en « tokens » <b>approximés par des mots</b> (séquences séparées par des
 * espaces) — arbitrage réversible : un tokenizer exact (BPE) est un travail ultérieur, la config
 * {@code app.rag.chunk.*} permet d'ajuster sans changement de code. Chaque fragment conserve ses
 * offsets {@code charStart}/{@code charEnd} dans le texte source (utile aux citations F-07).</p>
 */
@Component
public class Chunker {

    /** Un « token » = une séquence de caractères non-espaces (approximation mot). */
    private static final Pattern TOKEN = Pattern.compile("\\S+");

    private final RagProperties properties;

    public Chunker(RagProperties properties) {
        this.properties = properties;
    }

    /**
     * Découpe {@code text} en fenêtres glissantes de {@code maxTokens} mots avec {@code overlapTokens}
     * de chevauchement.
     *
     * @param text texte source (typiquement {@code documents.extracted_text})
     * @return la liste ordonnée des fragments ; vide si le texte est nul ou blanc
     */
    public List<ChunkText> chunk(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        int maxTokens = properties.effectiveMaxTokens();
        int overlap = properties.effectiveOverlapTokens();
        int step = Math.max(1, maxTokens - overlap);

        List<int[]> tokenSpans = tokenSpans(text);
        if (tokenSpans.isEmpty()) {
            return List.of();
        }

        List<ChunkText> chunks = new ArrayList<>();
        int index = 0;
        for (int start = 0; start < tokenSpans.size(); start += step) {
            int end = Math.min(start + maxTokens, tokenSpans.size());
            int charStart = tokenSpans.get(start)[0];
            int charEnd = tokenSpans.get(end - 1)[1];
            chunks.add(new ChunkText(index++, text.substring(charStart, charEnd), charStart, charEnd));
            if (end == tokenSpans.size()) {
                break; // dernière fenêtre atteinte : ne pas produire de doublon via l'overlap.
            }
        }
        return chunks;
    }

    private static List<int[]> tokenSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        Matcher matcher = TOKEN.matcher(text);
        while (matcher.find()) {
            spans.add(new int[] {matcher.start(), matcher.end()});
        }
        return spans;
    }
}
