package fr.claudegateway.jarvis.portrait;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.portrait.JarvisPortraitExceptions.InvalidPortraitException;
import fr.claudegateway.jarvis.portrait.JarvisPortraitExceptions.PortraitTooLargeException;
import fr.claudegateway.jarvis.portrait.JarvisPortraitExceptions.SouvenirNotFoundException;
import fr.claudegateway.jarvis.portrait.JarvisPortraitExceptions.SouvenirsFullException;

/**
 * <b>Le portrait de Jarvis</b> (F-241 / SF-241-01) : sections datées et souvenirs du compte, <b>scellés</b> au repos
 * ({@link PortraitSeal}), bornés (D4, D2), effaçables d'un geste (suppression physique).
 *
 * <p><b>Isolation</b> : chaque méthode porte exclusivement sur le {@code userId} fourni par l'appelant (issu du
 * principal) ; un souvenir est cherché par {@code (id, user_id)} — celui d'autrui est introuvable (404). Aucun point
 * d'accès administrateur. Le clair n'est jamais journalisé.</p>
 *
 * <p><b>Cache</b> : le portrait déchiffré est gardé en mémoire par compte et par {@code version} (incrémentée à chaque
 * écriture, portrait ou souvenir) ; un tour ne coûte qu'une lecture de la ligne, un déchiffrement par modification.</p>
 */
@Service
public class JarvisPortraitService {

    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    static final Set<String> SOURCES = Set.of("VOIX", "ECRAN", "ENTRETIEN");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Map<String, String>>> SECTIONS_TYPE = new TypeReference<>() {
    };

    /** Une section déchiffrée : son texte et sa date de mise à jour ({@code null} si jamais écrite). */
    public record SectionText(String text, OffsetDateTime updatedAt) {
    }

    /** Un souvenir déchiffré ; {@code number} = son rang {@code S<n>} (ordre de création, à partir de 1). */
    public record Souvenir(UUID id, int number, String text, boolean sensitive, LocalDate notedOn, String source) {
    }

    /** Le portrait déchiffré d'un compte, tel que Jarvis le reçoit (SF-241-03). */
    public record Snapshot(UUID userId, long version, boolean enabled, Map<PortraitSection, SectionText> sections,
            List<Souvenir> souvenirs, OffsetDateTime lastInterviewAt, OffsetDateTime lastCheckinAt,
            OffsetDateTime interviewOfferedAt, int interviewOffers) {

        /** Vrai si au moins une section ou un souvenir porte du texte. */
        public boolean hasContent() {
            return !souvenirs.isEmpty() || sections.values().stream()
                    .anyMatch(s -> s.text() != null && !s.text().isBlank());
        }

        /** Le texte d'une section (vide si absente). */
        public String text(PortraitSection section) {
            SectionText s = sections.get(section);
            return s == null || s.text() == null ? "" : s.text();
        }

        /** Le nombre de caractères compté par la borne D4. */
        public int chars() {
            return sizeOf(sections.values().stream().map(SectionText::text).toList(),
                    souvenirs.stream().map(Souvenir::text).toList());
        }
    }

    // ---- Vues rendues à l'écran ----

    public record SectionView(String key, String title, boolean sensitive, String text, OffsetDateTime updatedAt,
            boolean stale) {
    }

    public record SouvenirView(UUID id, int number, String text, boolean sensitive, LocalDate notedOn, String source,
            boolean stale) {
    }

    public record Size(int chars, int tokens, int maxTokens, int maxChars) {
    }

    public record View(boolean exists, boolean enabled, long version, List<SectionView> sections,
            List<SouvenirView> souvenirs, Size size, OffsetDateTime lastInterviewAt, int interviewOffers,
            List<String> blockedTerms) {
    }

    private final JarvisPortraitRepository portraits;
    private final JarvisSouvenirRepository souvenirs;
    private final PortraitSeal seal;
    private final Clock clock;
    private final Map<UUID, Snapshot> cache = new ConcurrentHashMap<>();

    public JarvisPortraitService(JarvisPortraitRepository portraits, JarvisSouvenirRepository souvenirs,
            PortraitSeal seal, Clock clock) {
        this.portraits = portraits;
        this.souvenirs = souvenirs;
        this.seal = seal;
        this.clock = clock;
    }

    // ---- Lecture ----

    /** Le portrait déchiffré du compte, ou vide si aucun n'existe. Lève si le scellé est indisponible (503). */
    @Transactional(readOnly = true)
    public Optional<Snapshot> snapshot(UUID userId) {
        if (userId == null) {
            return Optional.empty();
        }
        Optional<JarvisPortrait> row = portraits.findById(userId);
        if (row.isEmpty()) {
            cache.remove(userId);
            return Optional.empty();
        }
        Snapshot cached = cache.get(userId);
        if (cached != null && cached.version() == row.get().getVersion()) {
            return Optional.of(cached);
        }
        Snapshot fresh = decrypt(row.get());
        cache.put(userId, fresh);
        return Optional.of(fresh);
    }

    /** La vue de l'écran : les sept sections (vides si absentes), les souvenirs, la taille, « à revoir ». */
    @Transactional(readOnly = true)
    public View view(UUID userId) {
        Optional<Snapshot> snap = snapshot(userId);
        LocalDate today = today();
        List<SectionView> sectionViews = new ArrayList<>();
        for (PortraitSection section : PortraitSection.values()) {
            SectionText s = snap.map(p -> p.sections().get(section)).orElse(null);
            String text = s == null || s.text() == null ? "" : s.text();
            OffsetDateTime at = s == null ? null : s.updatedAt();
            boolean stale = !text.isBlank() && at != null
                    && JarvisPortraitRules.stale(at.atZoneSameInstant(ZONE).toLocalDate(), today);
            sectionViews.add(new SectionView(section.name(), section.title(), section.sensitive(), text, at, stale));
        }
        List<SouvenirView> souvenirViews = snap.map(Snapshot::souvenirs).orElse(List.of()).stream()
                .map(s -> new SouvenirView(s.id(), s.number(), s.text(), s.sensitive(), s.notedOn(), s.source(),
                        JarvisPortraitRules.stale(s.notedOn(), today)))
                .toList();
        int chars = snap.map(Snapshot::chars).orElse(0);
        return new View(snap.isPresent(), snap.map(Snapshot::enabled).orElse(true),
                snap.map(Snapshot::version).orElse(0L), sectionViews, souvenirViews,
                new Size(chars, JarvisPortraitRules.tokens(chars), JarvisPortraitRules.MAX_TOKENS,
                        JarvisPortraitRules.MAX_CHARS),
                snap.map(Snapshot::lastInterviewAt).orElse(null), snap.map(Snapshot::interviewOffers).orElse(0),
                // F-241 / SF-241-03 (D12 a) — les termes « À ne pas aborder », pour le filet des lignes 🎙 sur l'écran.
                snap.map(s -> List.copyOf(JarvisPortraitNote.forbiddenTerms(s))).orElse(List.of()));
    }

    // ---- Écriture du portrait ----

    /**
     * Remplace les sections fournies (les autres restent) et, si donné, l'interrupteur D20. La date d'une section ne
     * bouge que si son texte change. Borne D4 vérifiée avant toute écriture.
     */
    @Transactional
    public View update(UUID userId, Map<String, String> changes, Boolean enabled) {
        Map<PortraitSection, String> parsed = new EnumMap<>(PortraitSection.class);
        if (changes != null) {
            for (Map.Entry<String, String> e : changes.entrySet()) {
                PortraitSection section = PortraitSection.parse(e.getKey());
                if (section == null) {
                    throw new InvalidPortraitException("Section inconnue : " + cut(e.getKey()) + ".");
                }
                parsed.put(section, JarvisPortraitRules.normalizeSection(e.getValue()));
            }
        }
        JarvisPortrait row = portraits.findById(userId).orElse(null);
        Snapshot current = row == null ? null : snapshot(userId).orElse(null);
        Map<PortraitSection, SectionText> next = new EnumMap<>(PortraitSection.class);
        if (current != null) {
            next.putAll(current.sections());
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean changed = false;
        for (Map.Entry<PortraitSection, String> e : parsed.entrySet()) {
            SectionText before = next.get(e.getKey());
            String beforeText = before == null || before.text() == null ? "" : before.text();
            if (!beforeText.equals(e.getValue())) {
                next.put(e.getKey(), new SectionText(e.getValue(), now));
                changed = true;
            }
        }
        List<String> souvenirTexts = current == null ? List.of()
                : current.souvenirs().stream().map(Souvenir::text).toList();
        int chars = sizeOf(next.values().stream().map(SectionText::text).toList(), souvenirTexts);
        if (changed && JarvisPortraitRules.tooLarge(chars)) {
            throw new PortraitTooLargeException(JarvisPortraitRules.tooLargeMessage(chars));
        }
        if (row == null) {
            row = JarvisPortrait.builder().userId(userId).version(0L).build();
        }
        if (changed || row.getSectionsEnc() == null) {
            row.setSectionsEnc(seal.seal(toJson(next)));
        }
        if (enabled != null) {
            row.setPortraitEnabled(enabled);
        }
        touch(row, now);
        return view(userId);
    }

    /** F-241 / SF-241-02 — l'entretien validé : la date du dernier entretien (la relance D15 s'en sert). */
    @Transactional
    public void markInterviewed(UUID userId) {
        portraits.findById(userId).ifPresent(row -> {
            row.setLastInterviewAt(OffsetDateTime.now(clock));
            portraits.save(row);
            cache.remove(userId);
        });
    }

    /** F-241 / SF-241-04 — le journal des allusions, vidé avec le portrait. */
    private JarvisAllusionService allusions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAllusions(JarvisAllusionService allusions) {
        this.allusions = allusions;
    }

    /** « Tout effacer » (et la suppression du compte) : suppression <b>physique</b> immédiate. */
    @Transactional
    public void eraseAll(UUID userId) {
        if (allusions != null) {
            allusions.purgeUser(userId);
        }
        souvenirs.deleteByUser(userId);
        portraits.deleteByUser(userId);
        cache.remove(userId);
    }

    /** Oublie le portrait déchiffré gardé en mémoire (une colonne hors sections a changé). */
    public void invalidate(UUID userId) {
        cache.remove(userId);
    }

    /** Purge nommée à la suppression du compte. */
    public void purgeUser(UUID userId) {
        eraseAll(userId);
    }

    // ---- Souvenirs ----

    /** Ajoute un souvenir (D2) : ≤ 200 caractères, 30 au plus, borne D4. */
    @Transactional
    public SouvenirView addSouvenir(UUID userId, String text, Boolean sensitive, String source) {
        String clean = validSouvenir(text);
        String origin = source == null || source.isBlank() ? "ECRAN" : source.strip().toUpperCase(Locale.ROOT);
        if (!SOURCES.contains(origin)) {
            throw new InvalidPortraitException("Origine inconnue : " + cut(source) + ".");
        }
        if (souvenirs.countByUserId(userId) >= JarvisPortraitRules.MAX_SOUVENIRS) {
            throw new SouvenirsFullException();
        }
        Snapshot current = snapshot(userId).orElse(null);
        int chars = (current == null ? 0 : current.chars()) + clean.length();
        if (JarvisPortraitRules.tooLarge(chars)) {
            throw new PortraitTooLargeException(JarvisPortraitRules.tooLargeMessage(chars));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        JarvisSouvenir saved = souvenirs.save(JarvisSouvenir.builder().id(UUID.randomUUID()).userId(userId)
                .textEnc(seal.seal(clean)).sensitive(Boolean.TRUE.equals(sensitive))
                .notedOn(now.atZoneSameInstant(ZONE).toLocalDate()).source(origin).createdAt(now).build());
        bump(userId, now);
        return souvenirView(userId, saved.getId());
    }

    /** Modifie le texte et / ou la sensibilité d'un souvenir du compte (404 sinon). */
    @Transactional
    public SouvenirView patchSouvenir(UUID userId, UUID id, String text, Boolean sensitive) {
        JarvisSouvenir row = souvenirs.findByIdAndUserId(id, userId).orElseThrow(SouvenirNotFoundException::new);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (text != null) {
            String clean = validSouvenir(text);
            Snapshot current = snapshot(userId).orElse(null);
            String before = current == null ? "" : current.souvenirs().stream().filter(s -> s.id().equals(id))
                    .map(Souvenir::text).findFirst().orElse("");
            int chars = (current == null ? 0 : current.chars()) - before.length() + clean.length();
            if (JarvisPortraitRules.tooLarge(chars)) {
                throw new PortraitTooLargeException(JarvisPortraitRules.tooLargeMessage(chars));
            }
            row.setTextEnc(seal.seal(clean));
            row.setNotedOn(now.atZoneSameInstant(ZONE).toLocalDate());
        }
        if (sensitive != null) {
            row.setSensitive(sensitive);
        }
        souvenirs.save(row);
        bump(userId, now);
        return souvenirView(userId, id);
    }

    /** Efface un souvenir du compte (404 sinon). */
    @Transactional
    public void deleteSouvenir(UUID userId, UUID id) {
        JarvisSouvenir row = souvenirs.findByIdAndUserId(id, userId).orElseThrow(SouvenirNotFoundException::new);
        souvenirs.delete(row);
        bump(userId, OffsetDateTime.now(clock));
    }

    /**
     * « Intégrer au portrait » : le souvenir devient une ligne en fin de section, puis il est effacé — en une seule
     * transaction (rien n'est perdu ni dupliqué si l'une des deux écritures échoue).
     */
    @Transactional
    public View integrate(UUID userId, UUID id, String sectionKey) {
        PortraitSection section = PortraitSection.parse(sectionKey);
        if (section == null) {
            throw new InvalidPortraitException("Section inconnue : " + cut(sectionKey) + ".");
        }
        JarvisSouvenir row = souvenirs.findByIdAndUserId(id, userId).orElseThrow(SouvenirNotFoundException::new);
        String text = seal.open(row.getTextEnc());
        Snapshot current = snapshot(userId).orElseThrow(SouvenirNotFoundException::new);
        String before = current.text(section);
        String merged = before.isBlank() ? text : before.stripTrailing() + "\n" + text;
        souvenirs.delete(row);
        souvenirs.flush();
        cache.remove(userId); // la borne D4 relit la base : le souvenir déplacé n'est pas compté deux fois
        Map<String, String> change = new LinkedHashMap<>();
        change.put(section.name(), merged);
        return update(userId, change, null);
    }

    /** F-241 / SF-241-05 — les souvenirs du compte, dans l'ordre de leur numéro. */
    public List<Souvenir> listSouvenirs(UUID userId) {
        return snapshot(userId).map(Snapshot::souvenirs).orElse(List.of());
    }

    // ---- Interne ----

    private SouvenirView souvenirView(UUID userId, UUID id) {
        LocalDate today = today();
        return snapshot(userId).orElseThrow(SouvenirNotFoundException::new).souvenirs().stream()
                .filter(s -> s.id().equals(id)).findFirst()
                .map(s -> new SouvenirView(s.id(), s.number(), s.text(), s.sensitive(), s.notedOn(), s.source(),
                        JarvisPortraitRules.stale(s.notedOn(), today)))
                .orElseThrow(SouvenirNotFoundException::new);
    }

    /** Une écriture de souvenir : la ligne du portrait existe et sa version avance (rechargement du bloc, D5). */
    private void bump(UUID userId, OffsetDateTime now) {
        JarvisPortrait row = portraits.findById(userId)
                .orElseGet(() -> JarvisPortrait.builder().userId(userId).version(0L).build());
        touch(row, now);
    }

    private void touch(JarvisPortrait row, OffsetDateTime now) {
        row.setVersion(row.getVersion() + 1);
        row.setUpdatedAt(now);
        portraits.save(row);
        cache.remove(row.getUserId());
    }

    private Snapshot decrypt(JarvisPortrait row) {
        Map<PortraitSection, SectionText> sections = new EnumMap<>(PortraitSection.class);
        String json = seal.open(row.getSectionsEnc());
        if (json != null && !json.isBlank()) {
            try {
                Map<String, Map<String, String>> raw = JSON.readValue(json, SECTIONS_TYPE);
                for (Map.Entry<String, Map<String, String>> e : raw.entrySet()) {
                    PortraitSection section = PortraitSection.parse(e.getKey());
                    if (section != null && e.getValue() != null) {
                        String at = e.getValue().get("updatedAt");
                        sections.put(section, new SectionText(e.getValue().getOrDefault("text", ""),
                                at == null || at.isBlank() ? null : OffsetDateTime.parse(at)));
                    }
                }
            } catch (Exception e) {
                throw new IllegalStateException("portrait illisible");
            }
        }
        List<Souvenir> list = new ArrayList<>();
        int n = 1;
        for (JarvisSouvenir s : souvenirs.findByUserIdOrderByCreatedAtAscIdAsc(row.getUserId())) {
            list.add(new Souvenir(s.getId(), n++, seal.open(s.getTextEnc()), s.isSensitive(), s.getNotedOn(),
                    s.getSource()));
        }
        return new Snapshot(row.getUserId(), row.getVersion(), row.isPortraitEnabled(), sections, List.copyOf(list),
                row.getLastInterviewAt(), row.getLastCheckinAt(), row.getInterviewOfferedAt(),
                row.getInterviewOffers());
    }

    private static String toJson(Map<PortraitSection, SectionText> sections) {
        Map<String, Map<String, String>> raw = new LinkedHashMap<>();
        for (Map.Entry<PortraitSection, SectionText> e : sections.entrySet()) {
            Map<String, String> v = new LinkedHashMap<>();
            v.put("text", e.getValue().text() == null ? "" : e.getValue().text());
            v.put("updatedAt", e.getValue().updatedAt() == null ? "" : e.getValue().updatedAt().toString());
            raw.put(e.getKey().name(), v);
        }
        try {
            return JSON.writeValueAsString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("portrait non sérialisable");
        }
    }

    static int sizeOf(List<String> sectionTexts, List<String> souvenirTexts) {
        int chars = 0;
        for (String t : sectionTexts) {
            chars += t == null ? 0 : t.length();
        }
        for (String t : souvenirTexts) {
            chars += t == null ? 0 : t.length();
        }
        return chars;
    }

    private static String validSouvenir(String text) {
        String clean = JarvisPortraitRules.normalizeSouvenir(text);
        if (clean.isEmpty()) {
            throw new InvalidPortraitException("Le souvenir est vide.");
        }
        if (clean.length() > JarvisPortraitRules.MAX_SOUVENIR_CHARS) {
            throw new InvalidPortraitException("Un souvenir tient en " + JarvisPortraitRules.MAX_SOUVENIR_CHARS
                    + " caractères au plus.");
        }
        return clean;
    }

    private LocalDate today() {
        return OffsetDateTime.now(clock).atZoneSameInstant(ZONE).toLocalDate();
    }

    private static String cut(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 24 ? s : s.substring(0, 24) + "…";
    }
}
