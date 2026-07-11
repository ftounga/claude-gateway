package fr.claudegateway.atelier.storage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stockage de workspace <b>en mémoire</b> (défaut dev/tests) : aucun réseau, aucune dépendance S3.
 * Sélectionné quand {@code app.atelier.storage} vaut {@code in-memory} ou n'est pas défini.
 */
@Component
@ConditionalOnProperty(prefix = "app.atelier", name = "storage", havingValue = "in-memory",
        matchIfMissing = true)
public class InMemoryWorkspaceStorage implements WorkspaceStorage {

    private final Map<String, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public void putFile(String key, byte[] content, String contentType) {
        store.put(key, content.clone());
    }

    @Override
    public Optional<byte[]> getFile(String key) {
        byte[] content = store.get(key);
        return content == null ? Optional.empty() : Optional.of(content.clone());
    }

    @Override
    public List<String> listKeys(String prefix) {
        List<String> keys = new ArrayList<>();
        for (String key : store.keySet()) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        return keys;
    }

    @Override
    public void deletePrefix(String prefix) {
        store.keySet().removeIf(key -> key.startsWith(prefix));
    }
}
