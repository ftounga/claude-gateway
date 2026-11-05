package fr.claudegateway.jarvis.cards;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import fr.claudegateway.jarvis.state.JarvisStateBlock;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;

/**
 * <b>Les pancartes des faits du registre</b> (F-248 / SF-248-07, cadrage §4.2, D15) — un objet du registre F-247 (ses
 * attributs regroupés) devient une fiche : ticket, MR, pipeline, déploiement, résultat, accès, sinon générique. Lien =
 * l'attribut {@code url} relevé, sinon le sujet où le fait a été vu, sinon aucun — jamais deviné. Heure et source posées
 * par la passerelle. Pur.
 */
public final class JarvisStateCards {

    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH 'h' mm", Locale.FRENCH);

    private JarvisStateCards() {
    }

    /** {@code fait:etat:<host>.<clé en base64url>} — la clé du registre peut porter « / » ou « : ». */
    public static String id(UUID hostId, String objectKey) {
        return "fait:etat:" + hostId + "." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectKey.getBytes(StandardCharsets.UTF_8));
    }

    /** La référence {@code <host>.<clé>} décodée, ou vide. */
    public static Optional<Map.Entry<UUID, String>> parse(String ref) {
        int dot = ref == null ? -1 : ref.indexOf('.');
        if (dot <= 0) {
            return Optional.empty();
        }
        try {
            UUID host = UUID.fromString(ref.substring(0, dot));
            String key = new String(Base64.getUrlDecoder().decode(ref.substring(dot + 1)), StandardCharsets.UTF_8);
            return key.isBlank() ? Optional.empty() : Optional.of(Map.entry(host, key));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Le type de pancarte d'une famille du registre. */
    static String type(String objectKind, Map<String, JarvisStateRegistry.Fact> by) {
        String k = objectKind == null ? "" : objectKind;
        return switch (k) {
            case "TICKET" -> JarvisCardTemplates.TICKET;
            case "MR" -> JarvisCardTemplates.MR;
            case "PIPELINE" -> JarvisCardTemplates.PIPELINE;
            case "DEPLOYMENT", "RELEASE" -> JarvisCardTemplates.DEPLOIEMENT;
            case "ACCESS" -> JarvisCardTemplates.ACCES;
            default -> by.containsKey("result") ? JarvisCardTemplates.RESULTAT : JarvisCardTemplates.GENERIQUE;
        };
    }

    /** Les fiches de faits (regroupés par client et objet), dans l'ordre de première apparition. */
    public static List<JarvisCard> fromFacts(List<JarvisStateRegistry.Fact> facts, Map<UUID, String> hostNames) {
        Map<String, List<JarvisStateRegistry.Fact>> byObject = new LinkedHashMap<>();
        for (JarvisStateRegistry.Fact f : facts == null ? List.<JarvisStateRegistry.Fact>of() : facts) {
            if (f.hostId() == null || f.objectKey() == null) {
                continue;
            }
            byObject.computeIfAbsent(f.hostId() + "|" + f.objectKey(), k -> new ArrayList<>()).add(f);
        }
        List<JarvisCard> out = new ArrayList<>();
        byObject.values().forEach(fs -> out.add(card(fs, hostNames == null ? null : hostNames.get(fs.get(0).hostId()))));
        return out;
    }

    /** Les attributs d'un objet (le plus récent d'abord pour chacun). */
    static Map<String, JarvisStateRegistry.Fact> attributes(List<JarvisStateRegistry.Fact> fs) {
        Map<String, JarvisStateRegistry.Fact> by = new LinkedHashMap<>();
        fs.stream().sorted(Comparator.comparing(JarvisStateRegistry.Fact::observedAt,
                Comparator.nullsLast(Comparator.reverseOrder()))).forEach(f -> by.putIfAbsent(f.attribute(), f));
        return by;
    }

    /** La fiche d'un objet. */
    public static JarvisCard card(List<JarvisStateRegistry.Fact> fs, String client) {
        Map<String, JarvisStateRegistry.Fact> by = attributes(fs);
        JarvisStateRegistry.Fact first = fs.get(0);
        JarvisStateRegistry.Fact newest = fs.stream().max(Comparator.comparing(JarvisStateRegistry.Fact::observedAt,
                Comparator.nullsFirst(Comparator.naturalOrder()))).orElse(first);
        String type = type(first.objectKind(), by);
        String label = JarvisStateBlock.label(first.objectKey());
        String title = by.containsKey("title") ? label + " — " + by.get("title").value() : label;
        Map<String, String> attrs = new LinkedHashMap<>();
        by.forEach((k, f) -> attrs.put(k, f.value()));
        if (JarvisCardTemplates.ACCES.equals(type)) {
            attrs.put("kind", value(by, "identity_type"));
            attrs.put("expires_at", value(by, "validity"));
        }
        String status = firstValue(by, "status", "state", "result", "health", "sync");
        String url = value(by, "url");
        String link = url != null && url.matches("(?i)^https?://\\S+$") ? url
                : JarvisCardSources.subjectLink(first.workspaceId(), null);
        String raw = first.objectKey().contains(":") ? first.objectKey().substring(first.objectKey().indexOf(':') + 1)
                : first.objectKey();
        return JarvisCardTemplates.card(id(first.hostId(), first.objectKey()), type, title, status, attrs, link,
                newest.observedAt(), source(newest.source()), client,
                JarvisCardTemplates.aliases(label.replaceFirst("^(MR|Pipeline|Déploiement|Page|Branche|Schéma|Infra) ",
                        ""), raw, value(by, "title")));
    }

    private static String value(Map<String, JarvisStateRegistry.Fact> by, String attr) {
        JarvisStateRegistry.Fact f = by.get(attr);
        return f == null ? null : f.value();
    }

    private static String firstValue(Map<String, JarvisStateRegistry.Fact> by, String... attrs) {
        for (String a : attrs) {
            String v = value(by, a);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** {@code tour}, {@code releve}, {@code veille}, {@code constat}. */
    static String source(String source) {
        return switch (source == null ? "" : source) {
            case "RELEVE" -> "releve";
            case "VEILLE" -> "veille";
            case "NOTE" -> "constat";
            default -> "tour";
        };
    }

    /** « Poste CAGIP éteint depuis 18 h 05 » (heure de Paris, posée par la passerelle) — une fois par jeu. */
    public static String offlineNote(String client, OffsetDateTime since) {
        String name = client == null || client.isBlank() ? "" : " " + JarvisCardTemplates.clean(client, 40);
        return "Poste" + name + " éteint" + (since == null ? "" : " depuis "
                + HM.format(since.atZoneSameInstant(ZONE)));
    }
}
