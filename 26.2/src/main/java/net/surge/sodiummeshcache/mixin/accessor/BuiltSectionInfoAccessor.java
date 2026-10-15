package net.surge.sodiummeshcache.mixin.accessor;

import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets the cache-hit path overwrite the final {@code visibilityData} field
 * with the cached occlusion bitset, bypassing the need to reconstruct a real
 * {@code VisibilitySet} (there is no public way to go from the encoded
 * {@code long} back to one - see {@code VisibilityEncoding}, which only
 * exposes one-way encode/query helpers).
 */
@Mixin(BuiltSectionInfo.class)
public interface BuiltSectionInfoAccessor {
    @Mutable
    @Accessor("visibilityData")
    void sodiumMeshCache$setVisibilityData(long[] value);
}
