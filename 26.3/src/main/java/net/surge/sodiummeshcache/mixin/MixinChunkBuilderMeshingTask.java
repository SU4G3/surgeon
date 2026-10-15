package net.surge.sodiummeshcache.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionFlags;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.surge.sodiummeshcache.cache.CachedPass;
import net.surge.sodiummeshcache.cache.CachedSectionMesh;
import net.surge.sodiummeshcache.cache.MeshCacheManager;
import net.surge.sodiummeshcache.cache.SectionContentHashing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Write-back half of the mesh cache: whenever a section is actually rebuilt
 * (cache miss, or any section this cache never tries, like translucent
 * terrain), check whether the result is eligible for caching and, if so,
 * persist it. See MixinRenderSectionManager for the read side and
 * MeshCacheManager's class doc for the eligibility rules.
 *
 * This reuses the exact same {@code LevelSlice} the real mesher just read
 * from ({@code buildContext.cache.getWorldSlice()}) to compute the content
 * hash, so there's no second block/light scan here beyond the hash itself.
 */
@Mixin(ChunkBuilderMeshingTask.class)
public abstract class MixinChunkBuilderMeshingTask {
    @Inject(method = "execute", at = @At("RETURN"))
    private void sodiumMeshCache$writeBack(ChunkBuildContext buildContext, CancellationToken cancellationToken,
                                            CallbackInfoReturnable<ChunkBuildOutput> cir) {
        if (!MeshCacheManager.isEnabled()) {
            return;
        }

        ChunkBuildOutput output = cir.getReturnValue();
        if (output == null) {
            return;
        }

        int flags = output.info.flags;
        if ((flags & (RenderSectionFlags.MASK_HAS_BLOCK_ENTITIES | RenderSectionFlags.MASK_HAS_ANIMATED_SPRITES)) != 0) {
            return;
        }

        Map<TerrainRenderPass, BuiltSectionMeshParts> meshes = output.meshes;
        if (meshes.isEmpty() || meshes.containsKey(DefaultTerrainRenderPasses.TRANSLUCENT)) {
            return;
        }

        RenderSection section = output.section;

        List<CachedPass> cachedPasses = new ArrayList<>(meshes.size());
        for (Map.Entry<TerrainRenderPass, BuiltSectionMeshParts> entry : meshes.entrySet()) {
            int passIndex = DefaultTerrainRenderPasses.getPassIndex(entry.getKey());
            BuiltSectionMeshParts parts = entry.getValue();

            ByteBuffer direct = parts.getVertexData().getDirectBuffer();
            byte[] vertexData = new byte[direct.remaining()];
            direct.get(vertexData);

            cachedPasses.add(new CachedPass(passIndex, parts.getVertexSegments(), vertexData));
        }

        LevelSlice slice = buildContext.cache.getWorldSlice();
        long contentHash = SectionContentHashing.hash(slice, section.getOriginX(), section.getOriginY(), section.getOriginZ());

        CachedSectionMesh toStore = new CachedSectionMesh(contentHash, flags, output.info.visibilityData, cachedPasses);
        MeshCacheManager.write(section.getChunkX(), section.getChunkY(), section.getChunkZ(), toStore);
    }
}
