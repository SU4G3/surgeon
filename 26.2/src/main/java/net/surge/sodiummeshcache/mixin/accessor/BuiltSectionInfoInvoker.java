package net.surge.sodiummeshcache.mixin.accessor;

import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;

/**
 * Exposes BuiltSectionInfo's private constructor so the cache-hit path can
 * build an instance without re-running the block-entity/sprite/occlusion
 * scan - safe because cache eligibility guarantees empty block-entity and
 * sprite collections, and {@link BuiltSectionInfoAccessor} overwrites the
 * occlusion data with the cached bitset right after construction (the
 * dummy VisibilitySet[] passed in here only needs the right array length).
 */
@Mixin(BuiltSectionInfo.class)
public interface BuiltSectionInfoInvoker {
    @Invoker("<init>")
    static BuiltSectionInfo sodiumMeshCache$construct(
            Collection<TerrainRenderPass> blockRenderPasses,
            Collection<BlockEntity> globalBlockEntities,
            Collection<BlockEntity> culledBlockEntities,
            Collection<TextureAtlasSprite> animatedSprites,
            VisibilitySet[] occlusionData) {
        throw new AssertionError("Mixin invoker not applied");
    }
}
