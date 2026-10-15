package net.surge.sodiummeshcache.cache;

import java.util.List;

/**
 * Disk/in-memory form of a built section's mesh output, for sections eligible
 * for caching (no block entities, no animated sprites, no translucent
 * geometry - see {@link MeshCacheManager#isEligible}).
 *
 * This intentionally mirrors only the parts of {@code ChunkBuildOutput} /
 * {@code BuiltSectionInfo} that are (a) expensive to recompute and (b) safe
 * to persist across game sessions: raw vertex buffers and occlusion data.
 * Block entities and animated sprites are cheap to redetect live and are
 * never part of the eligible set in the first place.
 */
public final class CachedSectionMesh {
    public final long contentHash;
    public final int flags;
    public final long[] visibilityData;
    public final List<CachedPass> passes;

    public CachedSectionMesh(long contentHash, int flags, long[] visibilityData, List<CachedPass> passes) {
        this.contentHash = contentHash;
        this.flags = flags;
        this.visibilityData = visibilityData;
        this.passes = passes;
    }
}
