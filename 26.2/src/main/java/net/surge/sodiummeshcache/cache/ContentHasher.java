package net.surge.sodiummeshcache.cache;

/**
 * Incremental FNV-1a 64-bit hash. Not cryptographic - this is a cache
 * invalidation key, not a security boundary. Collisions just mean a stale
 * mesh could theoretically be served; see the padding/salt discussion in
 * {@link MeshCacheManager} for why the input is wide enough that this is not
 * a practical concern.
 */
public final class ContentHasher {
    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private long hash = OFFSET_BASIS;

    public ContentHasher update(long value) {
        for (int i = 0; i < 8; i++) {
            this.hash = (this.hash ^ ((value >>> (i * 8)) & 0xFF)) * PRIME;
        }
        return this;
    }

    public ContentHasher update(int value) {
        for (int i = 0; i < 4; i++) {
            this.hash = (this.hash ^ ((value >>> (i * 8)) & 0xFF)) * PRIME;
        }
        return this;
    }

    public ContentHasher update(byte value) {
        this.hash = (this.hash ^ (value & 0xFF)) * PRIME;
        return this;
    }

    public ContentHasher update(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            this.update((int) s.charAt(i));
        }
        return this;
    }

    public long finish() {
        return this.hash;
    }
}
