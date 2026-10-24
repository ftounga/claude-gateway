package fr.claudegateway.atelier.questions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import fr.claudegateway.atelier.nextsteps.ChantierAnchor;
import fr.claudegateway.atelier.nextsteps.NextStep;
import fr.claudegateway.atelier.nextsteps.NextStepsBlock;

/**
 * <b>Une réponse par question</b> (F-210 / SF-210-02) : lit les blocs {@code <<question id="N">> …
 * <</question>>} de la réponse finale d'un tour découpé, et rend le texte lisible sans marqueur.
 *
 * <p>Fonctions pures : le rattachement des suites au chantier est passé par l'appelant (il relit le
 * parcours en fin de tour, comme pour les suites globales).</p>
 */
public final class QuestionAnswers {

    /** Clé du relevé ({@code terminal_json}) qui porte les réponses. */
    public static final String REPORT_FIELD = "answers";

    private static final Pattern OPEN = Pattern.compile(
            "<<\\s*question\\s+(?:id\\s*=\\s*)?[\"']?(\\d+)[\"']?\\s*>>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSE = Pattern.compile("<<\\s*/\\s*question\\s*>>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESSENTIAL_OPEN = Pattern.compile("<<\\s*essentiel\\s*>>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESSENTIAL_CLOSE =
            Pattern.compile("<<\\s*/\\s*essentiel\\s*>>", Pattern.CASE_INSENSITIVE);

    private QuestionAnswers() {
    }

    /** La réponse à une question, telle que l'écran la relit. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Answer(int id, String libelle, String status, String essential, String body,
            List<NextStep> nextSteps, ChantierAnchor.Progress chantier) {

        public Answer {
            nextSteps = nextSteps == null ? List.of() : List.copyOf(nextSteps);
        }
    }

    /** Un bloc lu dans le texte : son id, son contenu brut, et où il se trouvait. */
    record Block(int id, String content, int start, int end) {
    }

    /** Les blocs du texte, dans l'ordre ; une ouverture sans fermeture court jusqu'à la fin. */
    static List<Block> blocks(String raw) {
        List<Block> blocks = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return blocks;
        }
        Matcher open = OPEN.matcher(raw);
        int from = 0;
        while (from < raw.length() && open.find(from)) {
            int id = Integer.parseInt(open.group(1));
            Matcher close = CLOSE.matcher(raw);
            Matcher next = OPEN.matcher(raw);
            int contentEnd;
            int blockEnd;
            boolean closed = close.find(open.end());
            boolean reopened = next.find(open.end());
            if (closed && (!reopened || close.start() < next.start())) {
                contentEnd = close.start();
                blockEnd = close.end();
            } else if (reopened) {
                contentEnd = next.start();
                blockEnd = next.start();
            } else {
                contentEnd = raw.length();
                blockEnd = raw.length();
            }
            blocks.add(new Block(id, raw.substring(open.end(), contentEnd), open.start(), blockEnd));
            from = blockEnd;
        }
        return blocks;
    }

    /**
     * Les réponses aux questions annoncées, dans l'ordre des questions ; vide sans découpage, avec
     * fusion, ou sans aucun bloc d'une question annoncée. Une question annoncée sans bloc est
     * {@code NON_ABOUTIE}.
     *
     * @param raw      la réponse finale brute (suites comprises)
     * @param snapshot les questions du tour (déjà closes)
     * @param anchor   le rattachement des suites d'un bloc au chantier ; {@code null} = aucune suite
     */
    public static List<Answer> parse(String raw, TurnQuestions.Snapshot snapshot,
            Function<List<NextStep>, ChantierAnchor.Anchored> anchor) {
        if (snapshot == null || snapshot.fusion() != null || snapshot.questions().isEmpty()) {
            return List.of();
        }
        Map<Integer, Block> byId = new LinkedHashMap<>();
        for (Block block : blocks(raw)) {
            boolean announced = snapshot.questions().stream().anyMatch(q -> q.id() == block.id());
            if (announced) {
                byId.putIfAbsent(block.id(), block);
            }
        }
        if (byId.isEmpty()) {
            return List.of();
        }
        List<Answer> answers = new ArrayList<>();
        for (TurnQuestions.QuestionView question : snapshot.questions()) {
            Block block = byId.get(question.id());
            if (block == null) {
                answers.add(new Answer(question.id(), question.libelle(), TurnQuestions.State.NON_ABOUTIE.name(),
                        null, "", List.of(), null));
                continue;
            }
            NextStepsBlock.Extraction extraction = NextStepsBlock.extract(block.content());
            ChantierAnchor.Anchored anchored = anchor == null || extraction.steps().isEmpty()
                    ? new ChantierAnchor.Anchored(List.of(), null) : anchor.apply(extraction.steps());
            String[] split = splitEssential(extraction.text());
            String status = TurnQuestions.State.NON_ABOUTIE.name().equals(question.state())
                    ? question.state() : TurnQuestions.State.TERMINEE.name();
            answers.add(new Answer(question.id(), question.libelle(), status, split[0], split[1],
                    anchored.steps(), anchored.chantier()));
        }
        return List.copyOf(answers);
    }

    /** Les ids des questions répondues par un bloc (pour clore les autres « non abouties »). */
    public static List<Integer> unanswered(List<Answer> answers) {
        return answers.stream().filter(a -> a.essential() == null && a.body().isEmpty()
                && TurnQuestions.State.NON_ABOUTIE.name().equals(a.status())).map(Answer::id).toList();
    }

    /**
     * Le texte sans aucun marqueur de question : chaque bloc devient « **N. libellé** » suivi de son
     * contenu ; les marqueurs d'essentiel sont retirés dès qu'il y a plus d'un bloc (un seul essentiel par
     * message pour un écran d'avant F-210). Sans bloc, le texte revient tel quel.
     */
    public static String readable(String text, TurnQuestions.Snapshot snapshot) {
        List<Block> blocks = blocks(text);
        if (blocks.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        int from = 0;
        for (Block block : blocks) {
            out.append(text, from, block.start());
            String label = labelOf(snapshot, block.id());
            out.append("\n\n**").append(block.id()).append('.');
            if (label != null) {
                out.append(' ').append(label);
            }
            out.append("**\n\n");
            String content = block.content();
            if (blocks.size() > 1) {
                content = ESSENTIAL_CLOSE.matcher(ESSENTIAL_OPEN.matcher(content).replaceAll("")).replaceAll("");
            }
            out.append(content.strip()).append("\n\n");
            from = block.end();
        }
        out.append(text.substring(from));
        // Une fermeture orpheline n'est jamais montrée.
        return CLOSE.matcher(out.toString()).replaceAll("").replaceAll("\n{3,}", "\n\n").strip();
    }

    private static String labelOf(TurnQuestions.Snapshot snapshot, int id) {
        if (snapshot == null) {
            return null;
        }
        return snapshot.questions().stream().filter(q -> q.id() == id).map(TurnQuestions.QuestionView::libelle)
                .findFirst().orElse(null);
    }

    /** {essentiel ou null, détail} — même règles que l'écran (F-126). */
    static String[] splitEssential(String text) {
        String content = text == null ? "" : text;
        Matcher open = ESSENTIAL_OPEN.matcher(content);
        if (!open.find()) {
            return new String[] {null, content.strip()};
        }
        String before = content.substring(0, open.start()).strip();
        String after = content.substring(open.end());
        Matcher close = ESSENTIAL_CLOSE.matcher(after);
        if (!close.find()) {
            String essential = after.strip();
            return new String[] {essential.isEmpty() ? null : essential, before};
        }
        String essential = after.substring(0, close.start()).strip();
        String rest = after.substring(close.end()).strip();
        String detail = before.isEmpty() ? rest : rest.isEmpty() ? before : before + "\n\n" + rest;
        return new String[] {essential.isEmpty() ? null : essential, detail};
    }
}
