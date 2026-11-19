package io.github.vedmider.dylext.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded in-memory LRU cache for deterministic read-tool payloads, keyed by
 * (tool, args, epoch). A new commit/index yields a new epoch -> new key -> natural miss, so stale
 * entries are never served; they age out by LRU. Off unless {@code dylext.cache.enabled=true}.
 */
@Component
public class ResponseCache {

    private final boolean enabled;
    private final Map<String, String> lru;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();

    public ResponseCache(@Value("${dylext.cache.enabled:false}") boolean enabled,
                         @Value("${dylext.cache.max-entries:2000}") int maxEntries) {
        this.enabled = enabled;
        this.lru = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maxEntries;
            }
        });
    }

    public boolean enabled() {
        return enabled;
    }

    public Optional<String> get(String key) {
        if (!enabled || key == null) {
            return Optional.empty();
        }
        String v = lru.get(key);
        (v == null ? misses : hits).incrementAndGet();
        return Optional.ofNullable(v);
    }

    public void put(String key, String value) {
        if (enabled && key != null && value != null) {
            lru.put(key, value);
        }
    }

    /** Drop all entries — called after a mutation whose effect the epoch (git HEAD) cannot capture. */
    public void invalidateAll() {
        lru.clear();
    }

    public Stats stats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        return new Stats(enabled, h, m, total == 0 ? 0.0 : Math.round(1000.0 * h / total) / 10.0, lru.size());
    }

    public record Stats(boolean enabled, long hits, long misses, double hitRatePercent, int entries) {
    }
}
