package fr.claudegateway.videos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.speech.SpeechProperties;

/**
 * <b>Le devis d'une vidéo</b> (F-216 / SF-216-01) — l'outil {@code video_devis} : valide la demande, lit le coût d'un
 * tour, chiffre ({@link VideoQuoteCalculator}), range le devis ouvert dans le sujet et rend le texte que l'agent place
 * dans la question de validation.
 *
 * <p>Le devis est un <b>résultat d'outil</b> : rien n'en entre dans la consigne système (préfixe stable). Gratuit, il
 * ne demande aucun accord ; l'accord est la question qui valide la fiche ou le plan.</p>
 *
 * <p><b>Isolation</b> : le sujet est celui du tour (déjà vérifié possédé) ; la vidéo d'une retouche est lue par
 * {@code (id, user_id)} — celle d'un autre compte est introuvable.</p>
 */
@Service
public class VideoQuoteService {

    static final int MAX_EPISODES = 30;
    static final BigDecimal MIN_DURATION_MIN = new BigDecimal("0.25");
    static final BigDecimal MAX_DURATION_MIN = BigDecimal.valueOf(30);
    static final int MAX_RETOUCHES = 10;
    static final long MIN_CHARS = 50;
    static final long MAX_CHARS = 50_000;
    static final int MAX_SERIES_TITLE = 120;

    private final VideoQuoteRepository quotes;
    private final VideoRepository videos;
    private final VideoTurnCostReader turnCostReader;
    private final VideoCostProperties coefficients;
    private final SpeechProperties speechProperties;
    private final Clock clock;

    public VideoQuoteService(VideoQuoteRepository quotes, VideoRepository videos,
            VideoTurnCostReader turnCostReader, VideoCostProperties coefficients, SpeechProperties speechProperties,
            Clock clock) {
        this.quotes = quotes;
        this.videos = videos;
        this.turnCostReader = turnCostReader;
        this.coefficients = coefficients;
        this.speechProperties = speechProperties;
        this.clock = clock;
    }

    /**
     * Le taux de lecture en euros (F-224 / SF-224-06) : le devis est calculé en dollars (la devise des tarifs du
     * fournisseur) et DIT en euros, au taux unique de la passerelle. Absent = 0,92.
     */
    private BigDecimal usdToEur = DEFAULT_USD_TO_EUR;

    static final BigDecimal DEFAULT_USD_TO_EUR = new BigDecimal("0.92");

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPricing(fr.claudegateway.quota.ProviderPricingProperties pricing) {
        this.usdToEur = pricing == null ? DEFAULT_USD_TO_EUR : pricing.usdToEur();
    }

    /** Ce que rend l'outil : un texte, en erreur ou non. */
    public record Outcome(String content, boolean error, UUID quoteId) {

        static Outcome error(String message) {
            return new Outcome(message, true, null);
        }
    }

    /**
     * Exécute {@code video_devis}.
     *
     * @param userId    propriétaire du terminal (celui du tour)
     * @param workspace terminal du tour, déjà vérifié comme possédé
     * @param input     paramètres de l'outil
     */
    @Transactional
    public Outcome quote(UUID userId, Workspace workspace, JsonNode input) {
        if (userId == null || workspace == null || workspace.getId() == null) {
            return Outcome.error("Le devis se calcule dans un sujet : ce terminal n'en a pas.");
        }
        Request request;
        try {
            request = parse(userId, input);
        } catch (VideoRejectedException e) {
            return Outcome.error(e.getMessage());
        }
        VideoTurnCostReader.TurnCostEstimate turnCost = turnCostReader.estimate(userId);
        VideoQuoteCalculator.Result result = VideoQuoteCalculator.compute(request.input(),
                new VideoQuoteCalculator.Rates(speechProperties.costUsdPerThousandChars(),
                        speechProperties.transcriptionCostUsdPerMinute(), turnCost.costUsd()),
                coefficients);
        VideoQuote saved = save(userId, workspace.getId(), request, result, turnCost);
        return new Outcome(describe(saved.getId(), request, result, turnCost), false, saved.getId());
    }

    // ------------------------------------------------------------------ rangement

    private VideoQuote save(UUID userId, UUID workspaceId, Request request, VideoQuoteCalculator.Result result,
            VideoTurnCostReader.TurnCostEstimate turnCost) {
        boolean retouche = request.input().kind() == VideoQuoteKind.RETOUCHE;
        OffsetDateTime now = OffsetDateTime.now(clock);
        // Un nouveau devis vidéo/série remplace les ouverts du sujet ; une retouche ne remplace que les retouches —
        // elle se superpose au devis d'une série en cours.
        for (VideoQuote open : quotes.findByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(userId, workspaceId,
                VideoQuote.Status.OPEN)) {
            if (!retouche || open.getKind() == VideoQuoteKind.RETOUCHE) {
                open.setStatus(VideoQuote.Status.CLOSED);
                open.setClosedAt(now);
                quotes.save(open);
            }
        }
        return quotes.save(VideoQuote.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .workspaceId(workspaceId)
                .kind(request.input().kind())
                .seriesTitle(request.seriesTitle())
                .videoId(request.videoId())
                .episodes(request.input().episodes())
                .episodeDurationS(request.input().durationMin().multiply(BigDecimal.valueOf(60))
                        .setScale(0, RoundingMode.HALF_UP).intValue())
                .narrationChars(request.input().charsPerEpisode())
                .retouches(request.input().retouches())
                .voiceLowUsd(result.voiceLow())
                .voiceHighUsd(result.voiceHigh())
                .agentLowUsd(result.agentLow())
                .agentHighUsd(result.agentHigh())
                .lowUsd(result.low())
                .highUsd(result.high())
                .turnCostUsd(turnCost.costUsd())
                .turnCostMeasured(turnCost.measured())
                .renderMinutes(result.renderMinutes())
                .status(VideoQuote.Status.OPEN)
                .createdAt(now)
                .lastActivityAt(now)
                .build());
    }

    // ------------------------------------------------------------------ demande

    record Request(VideoQuoteCalculator.Input input, String seriesTitle, UUID videoId, String videoTitle) {
    }

    private Request parse(UUID userId, JsonNode input) {
        String videoRef = text(input, "video");
        String series = text(input, "serie").replaceAll("\\s+", " ");
        if (series.length() > MAX_SERIES_TITLE) {
            throw new VideoRejectedException("serie : " + MAX_SERIES_TITLE + " caractères au plus.");
        }
        Integer retouchesIn = integer(input, "retouches");
        int retouches = retouchesIn == null ? 1 : retouchesIn;
        if (retouches < 0 || retouches > MAX_RETOUCHES) {
            throw new VideoRejectedException("retouches : entre 0 et " + MAX_RETOUCHES + ".");
        }
        BigDecimal duration = decimal(input, "duree_min");
        UUID videoId = null;
        String videoTitle = null;
        VideoQuoteKind kind;
        int episodes;
        if (!videoRef.isEmpty()) {
            kind = VideoQuoteKind.RETOUCHE;
            episodes = 1;
            retouches = Math.max(1, retouches);
            Video video = ownedVideo(userId, videoRef);
            videoId = video.getId();
            videoTitle = video.getTitle();
            if (duration == null && video.getDurationS() != null && video.getDurationS() > 0) {
                duration = BigDecimal.valueOf(video.getDurationS()).divide(BigDecimal.valueOf(60), 4,
                        RoundingMode.HALF_UP);
            }
            if (duration == null) {
                throw new VideoRejectedException("La durée de cette vidéo n'est pas connue : donne duree_min.");
            }
        } else {
            Integer episodesIn = integer(input, "episodes");
            episodes = episodesIn == null ? 1 : episodesIn;
            if (episodes < 1 || episodes > MAX_EPISODES) {
                throw new VideoRejectedException("episodes : entre 1 et " + MAX_EPISODES + ".");
            }
            if (duration == null) {
                throw new VideoRejectedException("Donne duree_min : la durée visée d'un épisode, en minutes.");
            }
            kind = episodes > 1 || !series.isEmpty() ? VideoQuoteKind.SERIES : VideoQuoteKind.SINGLE;
        }
        if (duration.compareTo(MIN_DURATION_MIN) < 0 || duration.compareTo(MAX_DURATION_MIN) > 0) {
            throw new VideoRejectedException("duree_min : entre 0,25 et 30 minutes (durée d'UN épisode).");
        }
        Integer charsIn = integer(input, "caracteres");
        long chars = charsIn == null
                ? duration.multiply(BigDecimal.valueOf(coefficients.charsPerMinute())).setScale(0, RoundingMode.HALF_UP)
                        .longValue()
                : charsIn;
        if (chars < MIN_CHARS || chars > MAX_CHARS) {
            throw new VideoRejectedException("caracteres : entre " + MIN_CHARS + " et " + MAX_CHARS
                    + " (narration d'UN épisode).");
        }
        return new Request(new VideoQuoteCalculator.Input(kind, episodes, duration, chars, retouches),
                kind == VideoQuoteKind.SERIES && !series.isEmpty() ? series : null, videoId, videoTitle);
    }

    private Video ownedVideo(UUID userId, String ref) {
        UUID id;
        try {
            id = UUID.fromString(ref.strip());
        } catch (IllegalArgumentException e) {
            throw new VideoRejectedException("Vidéo introuvable : « " + ref + " » (donne son video_id).");
        }
        // Lecture directe par (id, user_id) : une exception levée dans une transaction imbriquée marquerait celle du
        // devis « rollback-only ».
        return videos.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new VideoRejectedException("Vidéo introuvable : « " + ref + " »."));
    }

    // ------------------------------------------------------------------ texte

    /** Le texte rendu à l'agent. */
    String describe(UUID quoteId, Request request, VideoQuoteCalculator.Result result,
            VideoTurnCostReader.TurnCostEstimate turnCost) {
        VideoQuoteCalculator.Input in = request.input();
        StringBuilder out = new StringBuilder("Devis ").append(quoteId).append(" — ");
        switch (in.kind()) {
            case RETOUCHE -> out.append("retouche de « ").append(request.videoTitle()).append(" » (")
                    .append(duration(in.durationMin())).append("), ").append(plural(in.retouches(), "retouche"));
            case SERIES -> out.append("série").append(request.seriesTitle() == null ? ""
                    : " « " + request.seriesTitle() + " »").append(" de ").append(in.episodes())
                    .append(" épisodes de ").append(duration(in.durationMin())).append(" (")
                    .append(duration(result.totalMinutes())).append(" au total), ")
                    .append(planned(in.retouches())).append(" par épisode");
            default -> out.append("vidéo unique de ").append(duration(in.durationMin())).append(", ")
                    .append(planned(in.retouches()));
        }
        out.append(".\n").append(line(result, usdToEur)).append("\n");
        out.append("Détail : voix = ").append(group(in.charsPerEpisode())).append(" caractères");
        if (in.kind() != VideoQuoteKind.RETOUCHE && in.episodes() > 1) {
            out.append(" × ").append(in.episodes()).append(" épisodes");
        }
        out.append(" au tarif de voix_off + horodatage des mots");
        if (in.kind() == VideoQuoteKind.RETOUCHE) {
            out.append(" (borne haute : voix en partie refaite)");
        }
        out.append(" ; agent = ").append(number(result.turnsLow())).append(" à ").append(number(result.turnsHigh()))
                .append(" tours × ").append(money(eur(turnCost.costUsd(), usdToEur, RoundingMode.HALF_UP)))
                .append(" € par tour (")
                .append(turnCost.measured()
                        ? "coût moyen mesuré de vos tours sur 7 jours, " + turnCost.samples() + " tours"
                        : "valeur par défaut : moins de " + coefficients.minTurnSamples() + " tours mesurés sur 7 jours")
                .append(") ; rendu sur votre poste ≈ ").append(result.renderMinutes()).append(" min, gratuit.\n");
        if (in.kind() == VideoQuoteKind.RETOUCHE) {
            BigDecimal threshold = coefficients.retoucheConfirmThresholdUsd();
            if (result.high().compareTo(threshold) <= 0) {
                out.append("Sous le seuil de ").append(money(eur(threshold, usdToEur, RoundingMode.HALF_UP)))
                        .append(" € : annonce ce devis dans ta réponse "
                        + "et lance la retouche, sans question.");
            } else {
                out.append("Au-dessus du seuil de ").append(money(eur(threshold, usdToEur, RoundingMode.HALF_UP)))
                        .append(" € : fais confirmer ce devis par "
                        + "une question (demander) avant de lancer la retouche.");
            }
        } else {
            out.append("À faire : place la ligne « Coût estimé … » telle quelle dans la question UNIQUE (demander) qui "
                    + "valide ").append(in.kind() == VideoQuoteKind.SERIES ? "le plan de la série" : "la fiche de la vidéo")
                    .append(" ; ne fabrique rien avant l'accord. Si le plan change, refais le devis.");
        }
        return out.toString();
    }

    /**
     * « Coût estimé : 4,41 à 6,63 € (voix 0,36 € · agent 4,04 à 6,26 € · rendu 0 € — votre poste) » — en euros
     * (F-224 / SF-224-06), au taux unique de la passerelle. Le devis reste calculé et rangé en dollars.
     */
    static String line(VideoQuoteCalculator.Result result, BigDecimal usdToEur) {
        return "Coût estimé : " + range(result.low(), result.high(), usdToEur) + " (voix "
                + range(result.voiceLow(), result.voiceHigh(), usdToEur) + " · agent "
                + range(result.agentLow(), result.agentHigh(), usdToEur) + " · rendu 0 € — votre poste)";
    }

    /**
     * Une fourchette en euros. Borne basse au cent inférieur, haute au cent supérieur : la conversion ne resserre
     * jamais la fourchette (même règle que le calcul en dollars).
     */
    static String range(BigDecimal low, BigDecimal high, BigDecimal usdToEur) {
        if (low.compareTo(high) == 0) {
            return money(eur(low, usdToEur, RoundingMode.HALF_UP)) + " €";
        }
        return money(eur(low, usdToEur, RoundingMode.FLOOR)) + " à " + money(eur(high, usdToEur, RoundingMode.CEILING))
                + " €";
    }

    /** Un montant en dollars, en euros au centime selon {@code rounding}. */
    static BigDecimal eur(BigDecimal usd, BigDecimal usdToEur, RoundingMode rounding) {
        BigDecimal rate = usdToEur == null || usdToEur.signum() <= 0 ? DEFAULT_USD_TO_EUR : usdToEur;
        return usd.multiply(rate).setScale(2, rounding);
    }

    static String money(BigDecimal value) {
        return String.format(Locale.ROOT, "%.2f", value.setScale(2, RoundingMode.HALF_UP)).replace('.', ',');
    }

    private static String number(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private static String group(long value) {
        String digits = Long.toString(value);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                out.append(' ');
            }
            out.append(digits.charAt(i));
        }
        return out.toString();
    }

    static String duration(BigDecimal minutes) {
        long seconds = minutes.multiply(BigDecimal.valueOf(60)).setScale(0, RoundingMode.HALF_UP).longValue();
        if (seconds < 60) {
            return seconds + " s";
        }
        long hours = seconds / 3600;
        long min = (seconds % 3600) / 60;
        long sec = seconds % 60;
        if (hours > 0) {
            return hours + " h" + (min > 0 ? " " + String.format(Locale.ROOT, "%02d", min) : "");
        }
        return min + " min" + (sec > 0 ? " " + String.format(Locale.ROOT, "%02d", sec) : "");
    }

    private static String planned(int count) {
        return plural(count, "retouche") + (count > 1 ? " prévues" : " prévue");
    }

    private static String plural(int count, String word) {
        return count + " " + word + (count > 1 ? "s" : "");
    }

    // ------------------------------------------------------------------ lecture des paramètres

    private static String text(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        return node == null || node.isNull() ? "" : node.asText("").strip();
    }

    private static Integer integer(JsonNode input, String field) {
        BigDecimal value = decimal(input, field);
        if (value == null) {
            return null;
        }
        try {
            return value.setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException e) {
            throw new VideoRejectedException(field + " : nombre hors bornes.");
        }
    }

    private static BigDecimal decimal(JsonNode input, String field) {
        JsonNode node = input == null ? null : input.get(field);
        if (node == null || node.isNull() || (node.isTextual() && node.asText().isBlank())) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        try {
            return new BigDecimal(node.asText().strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new VideoRejectedException(field + " : un nombre est attendu.");
        }
    }
}
