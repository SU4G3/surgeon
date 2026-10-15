package net.surge.sodiummeshcache.cache;

import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Computes the cache-validity key for one section: a hash of every block
 * state and light value (block + sky) in the section plus the same 2-block
 * halo that Sodium itself copies into a LevelSlice for meshing purposes
 * (ambient occlusion and face culling read 1-2 blocks past the section
 * boundary; baked lighting is per-vertex and comes straight from these light
 * values, not from the day/night lightmap texture, which is why block/sky
 * light actually need to be in the key - see the design notes next to
 * MeshCacheManager), plus two inputs that are easy to miss because they
 * never show up in {@code BlockState} or light levels at all:
 *
 * <ul>
 *   <li><b>Biome tint.</b> Grass/foliage/water color is resolved per-biome
 *   and baked directly into vertex color at mesh-build time (see
 *   {@code BlockRenderer}'s {@code colorProvider.getColors(...)} call) - it
 *   is not applied later via a uniform or lightmap. Two otherwise-identical
 *   sections in different biomes (or the same position if its biome ever
 *   changes without a block update) would hash identically without this,
 *   silently serving the wrong tint. Sampled once at the section's center
 *   rather than per-block: vanilla's own biome color resolvers already
 *   average over a blend radius, so one sample is a faithful proxy for the
 *   whole section, and resampling at every block position here would
 *   multiply the cost of the one thing this cache exists to avoid paying
 *   for repeatedly.</li>
 *   <li><b>Active resource packs.</b> A different pack can change a block's
 *   baked model (different stair/fence shapes, etc.) or its biome colormap
 *   textures without touching the block state or light at all - the exact
 *   same gap as biome tint, just triggered by the player instead of world
 *   state. Folded in as the sorted, joined pack id list; cheap enough
 *   (typically a handful of strings) to just recompute on every check
 *   rather than caching it against a reload-event hook.</li>
 *   <li><b>Content-affecting quality settings.</b> {@code useAmbientOcclusion}
 *   (read straight off the same {@code LevelSlice}) gates whether AO
 *   shading is baked into vertex colors at all, and Sodium's own
 *   {@code hiddenFluidCulling}/{@code improvedFluidShaping} quality toggles
 *   change which fluid faces get built and how flowing fluid is shaped
 *   (see {@code DefaultFluidRenderer}'s {@code prepare()}). Toggling any of
 *   these between sessions is the same class of bug as a resource pack
 *   swap: the block/light content looks unchanged but the baked geometry
 *   isn't. Other quality toggles were deliberately left out - things like
 *   {@code useBlockFaceCulling} only affect which already-invisible faces
 *   get optimized away, never what's visible, and translucent-sorting
 *   toggles don't matter since translucent geometry is never cached at
 *   all (see MeshCacheManager's class doc).</li>
 * </ul>
 *
 * The halo radius (2) mirrors LevelSlice's private NEIGHBOR_BLOCK_RADIUS
 * constant, which isn't accessible directly. If Sodium widens that radius in
 * a future version without this mod being updated to match, hashes here
 * would miss some inputs that affect the real mesh - that's a correctness
 * regression (a stale mesh could be served), not just a missed cache hit.
 * Anything else about this hash being "too wide" (extra inputs it didn't
 * strictly need) can only ever cost cache hit rate, never correctness.
 *
 * Per-world/server/dimension directory namespacing (see
 * {@code MeshCacheManager.setCurrentWorld}) only needs to keep different
 * saves' pointer files from colliding on disk; it is not itself a source of
 * invalidation, which is why every input above lives in the hash instead.
 */
public final class SectionContentHashing {
    private static final int NEIGHBOR_BLOCK_RADIUS = 2;

    private SectionContentHashing() {
    }

    public static long hash(LevelSlice slice, int originX, int originY, int originZ) {
        ContentHasher hasher = new ContentHasher();

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        int minX = originX - NEIGHBOR_BLOCK_RADIUS;
        int minY = originY - NEIGHBOR_BLOCK_RADIUS;
        int minZ = originZ - NEIGHBOR_BLOCK_RADIUS;
        int maxX = originX + 16 + NEIGHBOR_BLOCK_RADIUS;
        int maxY = originY + 16 + NEIGHBOR_BLOCK_RADIUS;
        int maxZ = originZ + 16 + NEIGHBOR_BLOCK_RADIUS;

        for (int y = minY; y < maxY; y++) {
            for (int z = minZ; z < maxZ; z++) {
                for (int x = minX; x < maxX; x++) {
                    pos.set(x, y, z);

                    BlockState state = slice.getBlockState(x, y, z);
                    int stateId = Block.BLOCK_STATE_REGISTRY.getId(state);
                    int blockLight = slice.getBrightness(LightLayer.BLOCK, pos);
                    int skyLight = slice.getBrightness(LightLayer.SKY, pos);

                    hasher.update(stateId);
                    hasher.update((byte) ((blockLight << 4) | (skyLight & 0xF)));
                }
            }
        }

        BlockPos center = new BlockPos(originX + 8, originY + 8, originZ + 8);
        hasher.update(BiomeColors.getAverageGrassColor(slice, center));
        hasher.update(BiomeColors.getAverageFoliageColor(slice, center));
        hasher.update(BiomeColors.getAverageDryFoliageColor(slice, center));
        hasher.update(BiomeColors.getAverageWaterColor(slice, center));

        hasher.update(activeResourcePackSignature());

        hasher.update((byte) (slice.useAmbientOcclusion() ? 1 : 0));
        hasher.update((byte) (SodiumClientMod.options().quality.hiddenFluidCulling ? 1 : 0));
        hasher.update((byte) (SodiumClientMod.options().quality.improvedFluidShaping ? 1 : 0));

        return hasher.finish();
    }

    private static String activeResourcePackSignature() {
        try {
            List<String> ids = new ArrayList<>(Minecraft.getInstance().getResourcePackRepository().getSelectedIds());
            Collections.sort(ids);
            return String.join("\u0000", ids);
        } catch (Throwable t) {
            // If this can't be determined for some reason, falling back to a
            // constant is still safe: it just means a pack swap in that
            // degraded state wouldn't invalidate correctly, the same gap
            // that existed before this fix, not a new one.
            return "unknown";
        }
    }
}
