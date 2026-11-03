package fr.claudegateway.jarvis.activity;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;

/**
 * <b>La carte d'activité de Jarvis</b> (F-236 / SF-236-26, NF-16) — un agrégat <b>en lecture seule</b> : le nombre
 * de tours (messages de l'utilisateur) par sujet et par jour, sur au plus {@value #MAX_DAYS} jours. Tout est lu
 * <b>avec le {@code user_id} du compte</b> : les messages ({@link AtelierMessageRepository#userTurnsSince}) et les
 * sujets ({@link WorkspaceService#list}), dont l'intersection garantit qu'aucun sujet d'un autre compte n'apparaît.
 * Aucun contenu, aucun appel IA.
 */
@Service
public class JarvisActivityService {

    /** Bornes (cadrage §15 SF-26) : 7 jours, 12 clients × 40 sujets. */
    public static final int MAX_DAYS = 7;
    public static final int MAX_SUBJECTS = 12 * 40;
    /** Le fuseau par défaut quand l'écran n'en donne pas de lisible. */
    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Europe/Paris");

    private final AtelierMessageRepository messages;
    private final WorkspaceService workspaces;
    private final Clock clock;

    public JarvisActivityService(AtelierMessageRepository messages, WorkspaceService workspaces, Clock clock) {
        this.messages = messages;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    /** Le fuseau demandé s'il est lisible, sinon {@link #DEFAULT_ZONE}. */
    public static ZoneId zoneOf(String tz) {
        if (tz == null || tz.isBlank() || tz.length() > 64) {
            return DEFAULT_ZONE;
        }
        try {
            return ZoneId.of(tz.strip());
        } catch (DateTimeException e) {
            return DEFAULT_ZONE;
        }
    }

    /** {@code days} borné à [1, {@value #MAX_DAYS}]. */
    public static int boundDays(int days) {
        return Math.max(1, Math.min(MAX_DAYS, days));
    }

    @Transactional(readOnly = true)
    public JarvisActivity activity(UUID userId, int requestedDays, ZoneId zone) {
        int days = boundDays(requestedDays);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate first = today.minusDays(days - 1L);
        List<String> labels = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            labels.add(first.plusDays(i).toString());
        }
        // Les sujets du compte (le terminal Teams n'est pas un sujet de Jarvis), dans l'ordre de la liste.
        Map<UUID, int[]> counts = new LinkedHashMap<>();
        for (Workspace w : workspaces.list(userId)) {
            // F-245 / SF-245-01 : le fil Jarvis n'est pas un sujet de l'activité.
            if (userId.equals(w.getUserId()) && !w.isTeamsTerminal() && !w.isJarvisThread()) {
                counts.put(w.getId(), new int[days]);
            }
        }
        OffsetDateTime since = first.atStartOfDay(zone).toOffsetDateTime();
        for (Object[] row : messages.userTurnsSince(userId, since)) {
            int[] perDay = counts.get((UUID) row[0]);
            if (perDay == null || !(row[1] instanceof OffsetDateTime at)) {
                continue; // sujet d'un autre compte (impossible par la requête) ou supprimé : ignoré
            }
            int index = (int) (at.atZoneSameInstant(zone).toLocalDate().toEpochDay() - first.toEpochDay());
            if (index >= 0 && index < days && perDay[index] < Integer.MAX_VALUE) {
                perDay[index]++;
            }
        }
        List<JarvisActivity.Subject> subjects = new ArrayList<>();
        Map<UUID, Integer> totals = new HashMap<>();
        for (Map.Entry<UUID, int[]> e : counts.entrySet()) {
            int total = 0;
            List<Integer> turns = new ArrayList<>(days);
            for (int n : e.getValue()) {
                turns.add(n);
                total += n;
            }
            if (total > 0) {
                totals.put(e.getKey(), total);
                subjects.add(new JarvisActivity.Subject(e.getKey(), List.copyOf(turns)));
            }
        }
        if (subjects.size() > MAX_SUBJECTS) {
            // Les plus actifs d'abord quand il faut couper.
            subjects.sort((a, b) -> Integer.compare(totals.get(b.subjectId()), totals.get(a.subjectId())));
            subjects = new ArrayList<>(subjects.subList(0, MAX_SUBJECTS));
        }
        return new JarvisActivity(List.copyOf(labels), List.copyOf(subjects));
    }
}
