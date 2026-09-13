package fr.claudegateway.radar.sync;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncStatus;
import fr.claudegateway.radar.RadarSyncTrigger;

/**
 * <b>Ce que la couverture dit en tête</b> (F-100 / SF-100-04, cadrage §4.4) : « il dit ce qu'il n'a pas lu ».
 *
 * <p>Une synchro partielle produit une phrase qui <b>le dit d'abord</b> ; une synchro faite avec des manques
 * (canaux non lus, réunions sans transcription) ne se dit jamais « complète » ; un échec dit sa cause et le
 * geste ; un rattrapage le dit. La phrase est calculée ici, une fois, pour que l'écran n'ait rien à deviner.</p>
 *
 * @param headline la phrase de tête
 * @param remedy   le geste à faire, ou {@code null}
 * @param items    les manques, avec les gestes possibles
 */
public record RadarCoverageSummary(String headline, String remedy, List<Item> items) {

    /**
     * Un manque.
     *
     * @param actions gestes possibles : {@code IGNORE}, {@code READ_CHANNEL}
     * @param rule    la règle déjà posée sur ce fil, ou {@code null}
     */
    public record Item(String ref, String label, String kind, String status, String detail, List<String> actions,
            String rule) {
    }

    /**
     * @param rules règle déjà posée par fil ({@code ref} → {@code IGNORE} / {@code READ_CHANNEL})
     * @param zone  fuseau du poste, pour dire l'heure d'un rattrapage ou d'une annulation
     */
    public static RadarCoverageSummary of(RadarSync sync, JsonNode coverage, JsonNode progress,
            Map<String, String> rules, ZoneId zone) {
        JsonNode cov = coverage == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : coverage;
        String prefix = catchUpPrefix(sync, zone);
        String remedy = null;
        String body;
        List<Item> items = items(cov, rules);
        RadarSyncStatus status = sync.getStatus();
        switch (status) {
            case RUNNING -> body = running(progress);
            case FAILED -> {
                String sentence = cov.path("failure").path("sentence").asText("");
                body = sentence.isBlank() ? "Synchro échouée : rien n'a été synchronisé." : sentence;
                String fix = cov.path("failure").path("remedy").asText("");
                remedy = fix.isBlank() ? null : fix;
            }
            case CANCELLED -> body = "Synchro annulée" + (sync.getFinishedAt() == null ? "" : " à " + hour(sync.getFinishedAt(), zone))
                    + " : ce qui avait été lu est conservé.";
            case PARTIAL -> {
                List<String> gaps = gaps(cov);
                body = gaps.isEmpty() ? "Synchro partielle : tout n'a pas pu être lu."
                        : "Synchro partielle : " + String.join(", ", gaps) + ".";
            }
            default -> {
                List<String> gaps = gaps(cov);
                String read = readSentence(cov);
                body = gaps.isEmpty() ? "Synchro complète" + (read.isEmpty() ? "." : " : " + read + ".")
                        : "Synchro faite" + (read.isEmpty() ? "" : " : " + read) + " ; " + String.join(", ", gaps) + ".";
            }
        }
        return new RadarCoverageSummary(prefix + body, remedy, items);
    }

    private static String running(JsonNode progress) {
        if (progress == null || progress.isMissingNode() || progress.isNull()) {
            return "Synchro en cours.";
        }
        String phase = progress.path("phase").asText("");
        int done = progress.path("done").asInt(0);
        int total = progress.path("total").asInt(0);
        if ("conversations".equals(phase) && total > 0) {
            return "Synchro en cours : " + done + " conversation" + (done > 1 ? "s" : "") + " sur " + total + ".";
        }
        if ("meetings".equals(phase)) {
            return total > 0 ? "Synchro en cours : réunions, " + done + " sur " + total + "." : "Synchro en cours : réunions.";
        }
        return "Synchro en cours.";
    }

    private static String readSentence(JsonNode cov) {
        int conversations = cov.path("conversations").path("read").asInt(0);
        int meetings = cov.path("meetings").path("transcribed").asInt(0);
        List<String> parts = new ArrayList<>();
        if (conversations > 0) {
            parts.add(plural(conversations, "conversation lue", "conversations lues"));
        }
        if (meetings > 0) {
            parts.add(plural(meetings, "réunion transcrite", "réunions transcrites"));
        }
        if (parts.isEmpty() && !cov.isMissingNode()) {
            return "rien de nouveau";
        }
        return String.join(" et ", parts);
    }

    /** Les manques, dans l'ordre où ils comptent. */
    private static List<String> gaps(JsonNode cov) {
        List<String> gaps = new ArrayList<>();
        JsonNode conversations = cov.path("conversations");
        int incomplete = conversations.path("partial").asInt(0) + conversations.path("failed").asInt(0);
        if (incomplete > 0) {
            gaps.add(plural(incomplete, "fil non entièrement lu", "fils non entièrement lus"));
        }
        int deferred = conversations.path("deferred").asInt(0);
        if (deferred > 0) {
            gaps.add(plural(deferred, "élément reporté à la prochaine synchro", "éléments reportés à la prochaine synchro"));
        }
        if (cov.path("discovery").has("complete") && !cov.path("discovery").path("complete").asBoolean(true)) {
            gaps.add("liste des conversations peut-être incomplète");
        }
        if ("REFUSED".equals(cov.path("meetings").path("navigation").asText(""))) {
            gaps.add("calendrier inaccessible");
        }
        int failedMeetings = cov.path("meetings").path("failed").asInt(0);
        if (failedMeetings > 0) {
            gaps.add(plural(failedMeetings, "réunion non remontée", "réunions non remontées"));
        }
        int unread = cov.path("channels").path("unreadActive").asInt(0);
        if (unread > 0) {
            gaps.add(plural(unread, "canal actif non lu", "canaux actifs non lus"));
        }
        int noTranscript = cov.path("meetings").path("noTranscript").asInt(0);
        if (noTranscript > 0) {
            gaps.add(plural(noTranscript, "réunion sans transcription", "réunions sans transcription"));
        }
        int denied = cov.path("meetings").path("denied").asInt(0);
        if (denied > 0) {
            gaps.add(plural(denied, "transcription refusée", "transcriptions refusées"));
        }
        JsonNode depot = cov.path("depot");
        int depotFailed = depot.path("failed").asInt(0) + depot.path("unavailable").asInt(0);
        if (depotFailed > 0) {
            gaps.add(plural(depotFailed, "enregistrement déposé non transcrit", "enregistrements déposés non transcrits"));
        }
        return gaps;
    }

    private static List<Item> items(JsonNode cov, Map<String, String> rules) {
        List<Item> items = new ArrayList<>();
        JsonNode threads = cov.path("threads");
        if (!threads.isArray()) {
            return items;
        }
        for (JsonNode node : threads) {
            String ref = node.path("ref").asText("");
            String kind = node.path("kind").asText("");
            String status = node.path("status").asText("");
            List<String> actions = new ArrayList<>();
            if ("UNREAD_CHANNEL".equals(status)) {
                actions.add("READ_CHANNEL");
            }
            if ("CONVERSATION".equals(kind) || "CHANNEL".equals(kind)) {
                actions.add("IGNORE");
            }
            items.add(new Item(ref, node.path("label").asText(""), kind, status,
                    node.path("detail").asText(null), List.copyOf(actions), rules == null ? null : rules.get(ref)));
        }
        return items;
    }

    /** « Synchro d'hier soir non faite, rattrapée à 8 h 12. » */
    private static String catchUpPrefix(RadarSync sync, ZoneId zone) {
        if (sync.getTriggerKind() != RadarSyncTrigger.CATCH_UP || sync.getStartedAt() == null) {
            return "";
        }
        ZoneId z = zone == null ? ZoneId.of("Europe/Paris") : zone;
        String when = "du soir";
        if (sync.getScheduledFor() != null) {
            ZonedDateTime slot = sync.getScheduledFor().atZoneSameInstant(z);
            ZonedDateTime start = sync.getStartedAt().atZoneSameInstant(z);
            when = slot.toLocalDate().plusDays(1).equals(start.toLocalDate()) ? "d'hier soir"
                    : slot.toLocalDate().equals(start.toLocalDate()) ? "de ce soir" : "du " + slot.getDayOfMonth() + " au soir";
        }
        return "Synchro " + when + " non faite" + ("d'hier soir".equals(when) ? "" : " à l'heure") + ", rattrapée à "
                + hour(sync.getStartedAt(), z) + ". ";
    }

    static String hour(OffsetDateTime at, ZoneId zone) {
        ZonedDateTime local = at.atZoneSameInstant(zone == null ? ZoneId.of("Europe/Paris") : zone);
        return local.getHour() + " h " + String.format("%02d", local.getMinute());
    }

    private static String plural(int count, String one, String many) {
        return count + " " + (count > 1 ? many : one);
    }
}
