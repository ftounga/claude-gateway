package fr.claudegateway.pages.pdf;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Le <b>cache court</b> des PDF imprimés (F-184 / SF-184-04) : l'agent imprime (et vérifie), l'utilisateur
 * télécharge dans la foulée — sans seconde impression. Borné en durée, en nombre et en octets ; en
 * mémoire du pod, rien n'est persisté. La clé porte le compte : un PDF n'est jamais servi à un autre.
 */
final class PagePdfCache {

    static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_ENTRIES = 20;
    static final long MAX_BYTES = 40L * 1024 * 1024;

    private record Entry(PagePdfService.PagePdf pdf, Instant expiresAt) {
    }

    private final Duration ttl;
    private final int maxEntries;
    private final long maxBytes;
    private final Clock clock;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long bytes;

    PagePdfCache(Duration ttl, int maxEntries, long maxBytes, Clock clock) {
        this.ttl = ttl;
        this.maxEntries = maxEntries;
        this.maxBytes = maxBytes;
        this.clock = clock;
    }

    synchronized Optional<PagePdfService.PagePdf> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(entry.expiresAt())) {
            remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.pdf());
    }

    synchronized void put(String key, PagePdfService.PagePdf pdf) {
        if (pdf.pdf().length > maxBytes) {
            return;
        }
        remove(key);
        entries.put(key, new Entry(pdf, clock.instant().plus(ttl)));
        bytes += pdf.pdf().length;
        Iterator<Map.Entry<String, Entry>> eldest = entries.entrySet().iterator();
        while ((entries.size() > maxEntries || bytes > maxBytes) && eldest.hasNext()) {
            bytes -= eldest.next().getValue().pdf().pdf().length;
            eldest.remove();
        }
    }

    synchronized int size() {
        return entries.size();
    }

    private void remove(String key) {
        Entry previous = entries.remove(key);
        if (previous != null) {
            bytes -= previous.pdf().pdf().length;
        }
    }
}
