package io.github.vedmider.dylext.domain.model;

/**
 * How much a language's parser can answer. Tools degrade honestly by tier instead of erroring or
 * returning a misleading empty result.
 */
public enum CapabilityTier {
    T1_STRUCTURE("T1", "structure only: outline, search_symbols, get_digest, expand"),
    T2_EDGES("T2", "T1 + call edges: impact_of"),
    T3_TYPED("T3", "T2 + typed overload resolution");

    private final String id;
    private final String describes;

    CapabilityTier(String id, String describes) {
        this.id = id;
        this.describes = describes;
    }

    public String id() {
        return id;
    }

    public String describes() {
        return describes;
    }

    public boolean atLeast(CapabilityTier other) {
        return ordinal() >= other.ordinal();
    }

    public static CapabilityTier fromId(String s) {
        if (s != null) {
            for (CapabilityTier t : values()) {
                if (t.id.equalsIgnoreCase(s.trim()) || t.name().equalsIgnoreCase(s.trim())) {
                    return t;
                }
            }
        }
        return null;
    }
}
