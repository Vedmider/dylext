package io.github.vedmider.dylext.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseCacheTest {

    @Test
    void disabledCacheNeverStoresAndReportsDisabled() {
        ResponseCache c = new ResponseCache(false, 10);
        c.put("k", "v");
        assertThat(c.get("k")).isEmpty();
        assertThat(c.stats().enabled()).isFalse();
    }

    @Test
    void hitsAndMissesAreCountedAndHitRateComputed() {
        ResponseCache c = new ResponseCache(true, 10);
        c.put("k", "v");
        assertThat(c.get("k")).contains("v");
        assertThat(c.get("absent")).isEmpty();
        ResponseCache.Stats s = c.stats();
        assertThat(s.hits()).isEqualTo(1);
        assertThat(s.misses()).isEqualTo(1);
        assertThat(s.hitRatePercent()).isEqualTo(50.0);
    }

    @Test
    void lruEvictsTheLeastRecentlyUsedBeyondCapacity() {
        ResponseCache c = new ResponseCache(true, 2);
        c.put("a", "1");
        c.put("b", "2");
        c.get("a");               // touch a -> b is now least-recently-used
        c.put("cc", "3");         // evicts b
        assertThat(c.get("a")).contains("1");
        assertThat(c.get("cc")).contains("3");
        assertThat(c.get("b")).isEmpty();
    }

    @Test
    void invalidateAllClearsEntriesButKeepsTheCacheEnabled() {
        ResponseCache c = new ResponseCache(true, 10);
        c.put("a", "1");
        c.put("b", "2");
        c.invalidateAll();
        assertThat(c.get("a")).isEmpty();
        assertThat(c.get("b")).isEmpty();
        assertThat(c.stats().enabled()).isTrue();
    }

    @Test
    void nullKeyIsIgnored() {
        ResponseCache c = new ResponseCache(true, 10);
        c.put(null, "v");
        assertThat(c.get(null)).isEmpty();
    }
}
