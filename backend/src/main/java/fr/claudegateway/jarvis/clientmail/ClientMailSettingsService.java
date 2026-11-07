package fr.claudegateway.jarvis.clientmail;

import java.time.Clock;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.jarvis.mail.JarvisMailItemRepository;
import fr.claudegateway.jarvis.mail.JarvisMailPurge;
import fr.claudegateway.jarvis.notices.JarvisNoticeService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostLifecycleEvent;
import fr.claudegateway.runner.host.RunnerHostRepository;

/**
 * <b>Les réglages et l'état des mails des clients</b> (F-249 / SF-249-05, D12, D16) — réglages du compte, clients
 * actifs, état par poste pour l'écran, et le dépôt qui fait taire le rappel du jour. Tout sous le {@code user_id}.
 */
@Service
public class ClientMailSettingsService implements JarvisMailPurge {

    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    public static final String CAPABILITY = "mail_deposit";

    /** Les réglages vus par l'écran. */
    public record SettingsView(String reminderTime, int reminderMax, int reminderEveryMinutes, boolean weekdaysOnly,
            int meetingAlertMinutes) {
    }

    /** Un client (poste) vu par l'écran. */
    public record ClientView(UUID hostId, String name, boolean active, boolean upToDate, String folder,
            OffsetDateTime lastDepositAt, long mails, boolean depositedToday) {
    }

    /** L'état complet. */
    public record Overview(SettingsView settings, List<ClientView> clients) {
    }

    /** Une valeur hors bornes (400). */
    public static class InvalidSettingsException extends RuntimeException {
        public InvalidSettingsException(String message) {
            super(message);
        }
    }

    /** Le poste n'est pas au compte (404). */
    public static class ClientNotFoundException extends RuntimeException {
        public ClientNotFoundException() {
            super("Poste introuvable.");
        }
    }

    private final ClientMailSettingsRepository settings;
    private final ClientMailHostRepository clientHosts;
    private final ClientMailReminderDayRepository days;
    private final ClientMailDepositRepository deposits;
    private final JarvisMailItemRepository items;
    private final RunnerHostRepository hosts;
    private final JarvisNoticeService notices;
    private final Clock clock;

    public ClientMailSettingsService(ClientMailSettingsRepository settings, ClientMailHostRepository clientHosts,
            ClientMailReminderDayRepository days, ClientMailDepositRepository deposits, JarvisMailItemRepository items,
            RunnerHostRepository hosts, JarvisNoticeService notices, Clock clock) {
        this.settings = settings;
        this.clientHosts = clientHosts;
        this.days = days;
        this.deposits = deposits;
        this.items = items;
        this.hosts = hosts;
        this.notices = notices;
        this.clock = clock;
    }

    /** Les réglages du compte (défauts si jamais enregistrés). */
    public ClientMailSettings of(UUID userId) {
        return settings.findById(userId).orElseGet(() -> ClientMailSettings.defaults(userId));
    }

    static SettingsView view(ClientMailSettings s) {
        return new SettingsView(s.getReminderTime(), s.getReminderMax(), s.getReminderEveryMinutes(),
                s.isWeekdaysOnly(), s.getMeetingAlertMinutes());
    }

    /** Enregistre des réglages bornés ; 400 sinon. */
    @Transactional
    public SettingsView save(UUID userId, SettingsView in) {
        if (in == null) {
            throw new InvalidSettingsException("Réglages absents.");
        }
        String time = in.reminderTime() == null ? "" : in.reminderTime().strip();
        try {
            if (!time.matches("\\d{2}:\\d{2}")) {
                throw new DateTimeParseException("format", time, 0);
            }
            LocalTime.parse(time);
        } catch (DateTimeParseException e) {
            throw new InvalidSettingsException("Heure du rappel invalide (HH:MM).");
        }
        if (in.reminderMax() < 1 || in.reminderMax() > 3) {
            throw new InvalidSettingsException("De 1 à 3 rappels.");
        }
        if (in.reminderEveryMinutes() < 15 || in.reminderEveryMinutes() > 180) {
            throw new InvalidSettingsException("De 15 à 180 minutes entre deux rappels.");
        }
        if (in.meetingAlertMinutes() < 0 || in.meetingAlertMinutes() > 60) {
            throw new InvalidSettingsException("Alerte avant réunion : de 0 (coupée) à 60 minutes.");
        }
        ClientMailSettings s = of(userId);
        s.setReminderTime(time);
        s.setReminderMax(in.reminderMax());
        s.setReminderEveryMinutes(in.reminderEveryMinutes());
        s.setWeekdaysOnly(in.weekdaysOnly());
        s.setMeetingAlertMinutes(in.meetingAlertMinutes());
        s.setUpdatedAt(OffsetDateTime.now(clock));
        return view(settings.save(s));
    }

    /** Active ou coupe le rappel d'un client ; 404 si le poste n'est pas au compte. */
    @Transactional
    public ClientView setActive(UUID userId, UUID hostId, boolean active) {
        RunnerHost host = hosts.findByIdAndUserId(hostId, userId).orElseThrow(ClientNotFoundException::new);
        OffsetDateTime now = OffsetDateTime.now(clock);
        ClientMailHost row = clientHosts.findByUserIdAndHostId(userId, hostId).orElseGet(() -> {
            ClientMailHost h = new ClientMailHost();
            h.setUserId(userId);
            h.setHostId(hostId);
            h.setCreatedAt(now);
            return h;
        });
        row.setReminderEnabled(active);
        row.setUpdatedAt(now);
        clientHosts.save(row);
        return client(userId, host, row);
    }

    /** L'état pour l'écran : réglages et un élément par poste du compte. */
    @Transactional(readOnly = true)
    public Overview overview(UUID userId) {
        List<ClientView> clients = new ArrayList<>();
        for (RunnerHost h : hosts.findByUserIdOrderByCreatedAtDesc(userId)) {
            clients.add(client(userId, h, clientHosts.findByUserIdAndHostId(userId, h.getId()).orElse(null)));
        }
        return new Overview(view(of(userId)), clients);
    }

    private ClientView client(UUID userId, RunnerHost h, ClientMailHost row) {
        ClientMailDeposit last = deposits.findFirstByUserIdAndHostIdOrderByDepositedAtDesc(userId, h.getId());
        boolean upToDate = h.getRunnerCapabilities() != null
                && List.of(h.getRunnerCapabilities().split(",")).stream().map(String::strip).anyMatch(CAPABILITY::equals);
        String root = h.getRootName() == null || h.getRootName().isBlank() ? "<racine du poste>" : h.getRootName();
        return new ClientView(h.getId(), h.getName(), row != null && row.isReminderEnabled(), upToDate,
                root + "/jarvis/mails/", last == null ? null : last.getDepositedAt(),
                items.countByUserIdAndHostId(userId, h.getId()), depositedToday(userId, h.getId()));
    }

    /** Un dépôt (quelle que soit son issue) a-t-il eu lieu aujourd'hui (Paris) pour ce client ? */
    public boolean depositedToday(UUID userId, UUID hostId) {
        OffsetDateTime start = OffsetDateTime.now(clock).atZoneSameInstant(ZONE).toLocalDate().atStartOfDay(ZONE)
                .toOffsetDateTime();
        if (deposits.existsByUserIdAndHostIdAndDepositedAtGreaterThanEqual(userId, hostId, start)) {
            return true;
        }
        return days.findByUserIdAndHostIdAndReminderDay(userId, hostId, start.atZoneSameInstant(ZONE).toLocalDate())
                .map(d -> d.getDepositedAt() != null).orElse(false);
    }

    /**
     * Un dépôt vient d'arriver (même un doublon) : le client devient actif s'il ne l'était pas encore, le rappel du jour
     * se tait et son annonce se ferme.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void onDeposit(UUID userId, UUID hostId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (clientHosts.findByUserIdAndHostId(userId, hostId).isEmpty()) {
            ClientMailHost h = new ClientMailHost();
            h.setUserId(userId);
            h.setHostId(hostId);
            h.setReminderEnabled(true);
            h.setCreatedAt(now);
            h.setUpdatedAt(now);
            try {
                clientHosts.saveAndFlush(h);
            } catch (DataIntegrityViolationException e) {
                // créé au même instant ailleurs
            }
        }
        java.time.LocalDate today = now.atZoneSameInstant(ZONE).toLocalDate();
        ClientMailReminderDay d = days.findByUserIdAndHostIdAndReminderDay(userId, hostId, today).orElseGet(() -> {
            ClientMailReminderDay n = new ClientMailReminderDay();
            n.setUserId(userId);
            n.setHostId(hostId);
            n.setReminderDay(today);
            return n;
        });
        if (d.getDepositedAt() == null) {
            d.setDepositedAt(now);
            try {
                days.saveAndFlush(d);
            } catch (DataIntegrityViolationException e) {
                // ligne créée au même instant par le travail du rappel : le dépôt du jour reste vu par le journal
            }
        }
        notices.close(userId, ClientMailReminder.key(hostId, today));
    }

    @EventListener
    public void onHostDeleted(RunnerHostLifecycleEvent event) {
        if (event != null && event.kind() == RunnerHostLifecycleEvent.Kind.DELETED) {
            clientHosts.purgeHost(event.userId(), event.hostId());
            days.purgeHost(event.userId(), event.hostId());
        }
    }

    @Override
    public void purgeAccount(UUID userId, UUID accountId) {
        // sans objet
    }

    @Override
    public void purgeUser(UUID userId) {
        days.purgeUser(userId);
        clientHosts.purgeUser(userId);
        settings.purgeUser(userId);
    }
}
