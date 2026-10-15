package net.surge.sodiummeshcache.cache;

/**
 * One render pass's worth of built mesh data, in the on-disk / in-memory
 * cache representation (not GPU-ready yet).
 *
 * {@code passIndex} matches {@code DefaultTerrainRenderPasses.getPassIndex(...)}
 * (0 = SOLID, 1 = CUTOUT). TRANSLUCENT is never cached, see
 * {@link MeshCacheManager} for why.
 */
public final class CachedPass {
    public final int passIndex;
    public final int[] vertexSegments;
    public final byte[] vertexData;

    public CachedPass(int passIndex, int[] vertexSegments, byte[] vertexData) {
        this.passIndex = passIndex;
        this.vertexSegments = vertexSegments;
        this.vertexData = vertexData;
    }
}
