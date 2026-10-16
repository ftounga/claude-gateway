package fr.claudegateway.atelier.actions;

import java.text.Normalizer;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.radar.RadarPerson;
import fr.claudegateway.radar.RadarPersonRepository;

/**
 * <b>Les interlocuteurs d'un poste</b> (F-180 / SF-180-03, décision D2) : résoudre le nom écrit dans une
 * attente vers une personne normalisée, la relier au Radar, proposer les fusions, les appliquer.
 *
 * <p>En prod, une personne portait 11 des 20 attentes ouvertes, sous plusieurs graphies. Un interlocuteur
 * par personne et par poste rend la vue « par personne » (SF-180-05) possible.</p>
 *
 * <p><b>Isolation</b> : toute lecture porte {@code user_id} + {@code host_id} — celui du terminal
 * <b>possédé</b> pour les routes, celui de <b>l'attente</b> pour la résolution. Un interlocuteur d'un autre
 * compte ou d'un autre poste est introuvable.</p>
 */
@Service
public class InterlocuteurService {

    private static final Logger log = LoggerFactory.getLogger(InterlocuteurService.class);

    private final InterlocuteurRepository repository;
    private final TerminalActionRepository actions;
    private final WorkspaceRepository workspaces;
    private final RadarPersonRepository radarPeople;
    private final Clock clock;

    public InterlocuteurService(InterlocuteurRepository repository, TerminalActionRepository actions,
                                WorkspaceRepository workspaces, RadarPersonRepository radarPeople, Clock clock) {
        this.repository = repository;
        this.actions = actions;
        this.workspaces = workspaces;
        this.radarPeople = radarPeople;
        this.clock = clock;
    }

    /** Un nom ramené à sa forme stable : minuscules, sans accent, ponctuation en espace, espaces simples. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String folded = Normalizer.normalize(raw.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^a-z0-9@]+", " ")
                .strip();
        return folded.length() > Interlocuteur.MAX_NAME ? folded.substring(0, Interlocuteur.MAX_NAME) : folded;
    }

    /**
     * <b>L'interlocuteur de ce nom sur ce poste</b>, créé s'il n'existe pas. Vide si le nom est vide ou le
     * poste absent (terminal hébergé). Best-effort : une panne rend vide, l'attente garde son texte libre.
     */
    @Transactional
    public Optional<Interlocuteur> resolve(UUID userId, UUID hostId, String rawName) {
        String normalized = normalize(rawName);
        if (userId == null || hostId == null || normalized.isEmpty()) {
            return Optional.empty();
        }
        try {
            var exact = repository.findByUserIdAndHostIdAndNormalizedName(userId, hostId, normalized);
            if (exact.isPresent()) {
                return exact;
            }
            for (Interlocuteur candidate : repository.findByUserIdAndHostIdOrderByDisplayNameAsc(userId, hostId)) {
                if (candidate.answersTo(normalized)) {
                    return Optional.of(candidate);
                }
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            String display = rawName.strip();
            Interlocuteur created = Interlocuteur.builder()
                    .userId(userId).hostId(hostId)
                    .displayName(display.length() > Interlocuteur.MAX_NAME
                            ? display.substring(0, Interlocuteur.MAX_NAME) : display)
                    .normalizedName(normalized)
                    .createdAt(now).updatedAt(now)
                    .build();
            linkRadarByName(created);
            return Optional.of(repository.save(created));
        } catch (RuntimeException e) {
            log.debug("Interlocuteur non résolu (best-effort) : {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Relie la personne du Radar du même poste au même nom normalisé, et reprend son intitulé. */
    private void linkRadarByName(Interlocuteur interlocuteur) {
        if (radarPeople == null || interlocuteur.getRadarPersonId() != null) {
            return;
        }
        for (RadarPerson person : radarPeople.findByUserIdAndHostIdOrderByDisplayNameAsc(
                interlocuteur.getUserId(), interlocuteur.getHostId())) {
            if (interlocuteur.answersTo(normalize(person.getDisplayName()))) {
                interlocuteur.setRadarPersonId(person.getId());
                if (interlocuteur.getRole() == null && person.getJobTitle() != null) {
                    interlocuteur.setRole(clip(person.getJobTitle()));
                }
                return;
            }
        }
    }

    // ---- Les routes : le poste du terminal possédé ----

    /** Ce que l'écran montre d'un interlocuteur. */
    public record InterlocuteurView(UUID id, String displayName, String role, String team, UUID radarPersonId,
                                    String radarName, List<String> variants, int openCount) {
    }

    /** Les interlocuteurs du poste, avec leurs attentes ouvertes. Vide pour un terminal hébergé. */
    @Transactional(readOnly = true)
    public List<InterlocuteurView> list(UUID userId, UUID workspaceId) {
        Workspace workspace = owned(userId, workspaceId);
        if (workspace.getHostId() == null) {
            return List.of();
        }
        Map<UUID, Integer> open = new HashMap<>();
        for (TerminalAction a : actions.findByUserIdAndHostIdAndStatusIn(userId, workspace.getHostId(),
                TerminalActionStatus.OPEN_STATES)) {
            if (a.getInterlocuteurId() != null) {
                open.merge(a.getInterlocuteurId(), 1, Integer::sum);
            }
        }
        Map<UUID, String> radarNames = radarNames(userId, workspace.getHostId());
        return repository.findByUserIdAndHostIdOrderByDisplayNameAsc(userId, workspace.getHostId()).stream()
                .map(i -> view(i, open.getOrDefault(i.getId(), 0), radarNames))
                .toList();
    }

    /** La modification d'un interlocuteur ; {@code null} = inchangé, chaîne vide = effacé (sauf le nom). */
    public record UpdateRequest(String displayName, String role, String team, UUID radarPersonId) {
    }

    @Transactional
    public InterlocuteurView update(UUID userId, UUID workspaceId, UUID id, UpdateRequest request) {
        Workspace workspace = owned(userId, workspaceId);
        Interlocuteur i = require(userId, workspace, id);
        if (request == null) {
            return view(i, 0, Map.of());
        }
        if (request.displayName() != null) {
            String name = request.displayName().strip();
            if (name.isEmpty() || name.length() > Interlocuteur.MAX_NAME) {
                throw new InvalidTerminalActionException("Le nom tient en 1 à " + Interlocuteur.MAX_NAME + " caractères.");
            }
            String normalized = normalize(name);
            var other = repository.findByUserIdAndHostIdAndNormalizedName(userId, workspace.getHostId(), normalized);
            if (other.isPresent() && !other.get().getId().equals(i.getId())) {
                throw new InvalidTerminalActionException(
                        "Un autre interlocuteur porte déjà ce nom : proposez plutôt la fusion.");
            }
            if (!normalized.equals(i.getNormalizedName())) {
                String previous = i.getNormalizedName();
                i.setNormalizedName(normalized);
                i.addVariants(List.of(previous)); // l'ancienne graphie reste reconnue
            }
            i.setDisplayName(name);
        }
        if (request.role() != null) {
            i.setRole(optional(request.role()));
        }
        if (request.team() != null) {
            i.setTeam(optional(request.team()));
        }
        if (request.radarPersonId() != null) {
            RadarPerson person = radarPeople.findByIdAndUserIdAndHostId(request.radarPersonId(), userId,
                            workspace.getHostId())
                    .orElseThrow(() -> new TerminalActionNotFoundException("Personne du Radar introuvable."));
            i.setRadarPersonId(person.getId());
        }
        i.setUpdatedAt(OffsetDateTime.now(clock));
        repository.save(i);
        return view(i, 0, radarNames(userId, workspace.getHostId()));
    }

    /** Pourquoi deux interlocuteurs semblent être la même personne. */
    public enum MergeReason { PREFIX, INITIALS, TYPO }

    /** Une fusion proposée : celui qu'on garde, celui qu'on fusionnerait, et pourquoi. */
    public record MergeSuggestion(InterlocuteurView keep, InterlocuteurView merge, MergeReason reason) {
    }

    @Transactional(readOnly = true)
    public List<MergeSuggestion> suggestions(UUID userId, UUID workspaceId) {
        Workspace workspace = owned(userId, workspaceId);
        if (workspace.getHostId() == null) {
            return List.of();
        }
        List<Interlocuteur> all = repository.findByUserIdAndHostIdOrderByDisplayNameAsc(userId, workspace.getHostId());
        List<MergeSuggestion> out = new ArrayList<>();
        java.util.Set<UUID> proposed = new java.util.HashSet<>();
        for (int x = 0; x < all.size(); x++) {
            for (int y = x + 1; y < all.size(); y++) {
                Interlocuteur a = all.get(x);
                Interlocuteur b = all.get(y);
                if (a.distinctSet().contains(b.getId()) || b.distinctSet().contains(a.getId())) {
                    continue;
                }
                MergeReason reason = similarity(a.getNormalizedName(), b.getNormalizedName());
                if (reason == null) {
                    continue;
                }
                Interlocuteur keep = keeper(a, b);
                Interlocuteur merge = keep == a ? b : a;
                if (proposed.add(merge.getId())) {
                    out.add(new MergeSuggestion(view(keep, 0, Map.of()), view(merge, 0, Map.of()), reason));
                }
            }
        }
        return out.stream().limit(20).toList();
    }

    /**
     * <b>[Fusionner]</b> : les attentes de {@code id} passent à {@code intoId}, ses graphies deviennent des
     * variantes, rôle / équipe / lien Radar repris s'ils manquaient ; {@code id} disparaît.
     */
    @Transactional
    public InterlocuteurView merge(UUID userId, UUID workspaceId, UUID id, UUID intoId) {
        Workspace workspace = owned(userId, workspaceId);
        if (intoId == null || intoId.equals(id)) {
            throw new InvalidTerminalActionException("Fusion impossible : désignez l'interlocuteur à garder.");
        }
        Interlocuteur source = require(userId, workspace, id);
        Interlocuteur target = require(userId, workspace, intoId);
        List<String> forms = new ArrayList<>(source.variantList());
        forms.add(source.getNormalizedName());
        target.addVariants(forms);
        if (target.getRole() == null) {
            target.setRole(source.getRole());
        }
        if (target.getTeam() == null) {
            target.setTeam(source.getTeam());
        }
        if (target.getRadarPersonId() == null) {
            target.setRadarPersonId(source.getRadarPersonId());
        }
        target.setUpdatedAt(OffsetDateTime.now(clock));
        actions.repointInterlocuteur(userId, source.getId(), target.getId());
        if (mergeListener != null) {
            mergeListener.onMerge(userId, source.getId(), target.getId());
        }
        repository.delete(source);
        repository.save(target);
        return view(target, 0, radarNames(userId, workspace.getHostId()));
    }

    /** Ce qui suit une fusion d'interlocuteurs ailleurs (le registre des accès, SF-180-04). */
    public interface MergeListener {
        void onMerge(UUID userId, UUID from, UUID into);
    }

    private MergeListener mergeListener;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setMergeListener(MergeListener mergeListener) {
        this.mergeListener = mergeListener;
    }

    /** <b>[Ce sont deux personnes]</b> : la paire n'est plus proposée. */
    @Transactional
    public void distinct(UUID userId, UUID workspaceId, UUID id, UUID otherId) {
        Workspace workspace = owned(userId, workspaceId);
        Interlocuteur a = require(userId, workspace, id);
        Interlocuteur b = require(userId, workspace, otherId);
        a.addDistinct(b.getId());
        b.addDistinct(a.getId());
        OffsetDateTime now = OffsetDateTime.now(clock);
        a.setUpdatedAt(now);
        b.setUpdatedAt(now);
        repository.save(a);
        repository.save(b);
    }

    /** Les noms affichés d'interlocuteurs, sous {@code user_id} + {@code host_id}. */
    @Transactional(readOnly = true)
    public Map<UUID, String> namesOf(UUID userId, UUID hostId, java.util.Collection<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        if (userId == null || hostId == null || ids == null || ids.isEmpty()) {
            return out;
        }
        for (Interlocuteur i : repository.findByUserIdAndHostIdAndIdIn(userId, hostId, ids)) {
            out.put(i.getId(), i.getDisplayName());
        }
        return out;
    }

    // ---- La similarité des noms ----

    /** Deux noms normalisés désignent-ils probablement la même personne ? */
    static MergeReason similarity(String a, String b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.equals(b)) {
            return null;
        }
        String[] ta = a.split(" ");
        String[] tb = b.split(" ");
        if (ta.length != tb.length) {
            String[] shorter = ta.length < tb.length ? ta : tb;
            String[] longer = ta.length < tb.length ? tb : ta;
            if (shorter[0].length() >= 3 && shorter[0].equals(longer[0])
                    && java.util.Arrays.asList(longer).containsAll(java.util.Arrays.asList(shorter))) {
                return MergeReason.PREFIX;
            }
            return null;
        }
        boolean initials = true;
        boolean oneFull = false;
        boolean anyInitial = false;
        for (int i = 0; i < ta.length; i++) {
            if (ta[i].equals(tb[i])) {
                oneFull |= ta[i].length() > 1;
            } else if ((ta[i].length() == 1 && tb[i].startsWith(ta[i])) || (tb[i].length() == 1 && ta[i].startsWith(tb[i]))) {
                anyInitial = true;
            } else {
                initials = false;
            }
        }
        if (initials && oneFull && anyInitial) {
            return MergeReason.INITIALS;
        }
        if (a.length() >= 4 && b.length() >= 4 && levenshtein(a, b) <= 1) {
            return MergeReason.TYPO;
        }
        return null;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** Le plus complet est gardé (le nom le plus long), à défaut le plus ancien. */
    private static Interlocuteur keeper(Interlocuteur a, Interlocuteur b) {
        return java.util.stream.Stream.of(a, b)
                .max(Comparator.<Interlocuteur>comparingInt(i -> i.getDisplayName().length())
                        .thenComparing(Interlocuteur::getCreatedAt, Comparator.reverseOrder()))
                .orElse(a);
    }

    // ---- Outils ----

    private InterlocuteurView view(Interlocuteur i, int open, Map<UUID, String> radarNames) {
        return new InterlocuteurView(i.getId(), i.getDisplayName(), i.getRole(), i.getTeam(), i.getRadarPersonId(),
                i.getRadarPersonId() == null ? null : radarNames.get(i.getRadarPersonId()), i.variantList(), open);
    }

    private Map<UUID, String> radarNames(UUID userId, UUID hostId) {
        Map<UUID, String> out = new HashMap<>();
        if (radarPeople == null || hostId == null) {
            return out;
        }
        try {
            for (RadarPerson p : radarPeople.findByUserIdAndHostIdOrderByDisplayNameAsc(userId, hostId)) {
                out.put(p.getId(), p.getDisplayName());
            }
        } catch (RuntimeException e) {
            log.debug("Annuaire du Radar illisible (best-effort) : {}", e.getClass().getSimpleName());
        }
        return out;
    }

    private Workspace owned(UUID userId, UUID workspaceId) {
        return workspaces.findByIdAndUserId(workspaceId, userId)
                .orElseThrow(() -> new TerminalActionNotFoundException("Terminal introuvable."));
    }

    private Interlocuteur require(UUID userId, Workspace workspace, UUID id) {
        if (workspace.getHostId() == null || id == null) {
            throw new TerminalActionNotFoundException("Interlocuteur introuvable.");
        }
        return repository.findByIdAndUserIdAndHostId(id, userId, workspace.getHostId())
                .orElseThrow(() -> new TerminalActionNotFoundException("Interlocuteur introuvable."));
    }

    private static String optional(String value) {
        String v = value.strip();
        if (v.length() > Interlocuteur.MAX_NAME) {
            throw new InvalidTerminalActionException("Rôle et équipe tiennent en " + Interlocuteur.MAX_NAME + " caractères.");
        }
        return v.isEmpty() ? null : v;
    }

    private static String clip(String value) {
        return value.length() > Interlocuteur.MAX_NAME ? value.substring(0, Interlocuteur.MAX_NAME) : value;
    }
}
