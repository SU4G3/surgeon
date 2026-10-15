package net.surge.sodiummeshcache.cache;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.surge.sodiummeshcache.mixin.accessor.BuiltSectionInfoAccessor;
import net.surge.sodiummeshcache.mixin.accessor.BuiltSectionInfoInvoker;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Turns a disk-loaded {@link CachedSectionMesh} back into a real
 * {@code ChunkBuildOutput}, the same type a worker thread would have
 * produced by actually running the mesher. This is the only place that
 * needs to touch BuiltSectionInfo's private constructor/final field - see
 * the accessor mixins for why.
 */
public final class CachedMeshReconstructor {
    private CachedMeshReconstructor() {
    }

    // Sodium 0.9.2+mc26.2's ChunkBuildOutput has no chunkCaptureTime param
    // (added later, mc26.3) - this build target drops it accordingly.
    public static ChunkBuildOutput build(RenderSection section, int frame, CachedSectionMesh cached) {
        List<TerrainRenderPass> passList = new ArrayList<>(cached.passes.size());
        Map<TerrainRenderPass, BuiltSectionMeshParts> meshes = new Reference2ReferenceOpenHashMap<>();

        for (CachedPass cp : cached.passes) {
            TerrainRenderPass pass = DefaultTerrainRenderPasses.ALL[cp.passIndex];
            passList.add(pass);

            NativeBuffer buffer = new NativeBuffer(cp.vertexData.length);
            ByteBuffer direct = buffer.getDirectBuffer();
            direct.put(cp.vertexData);

            meshes.put(pass, new BuiltSectionMeshParts(buffer, cp.vertexSegments));
        }

        // Eligibility (enforced where CachedSectionMesh is produced, see
        // MixinChunkBuilderMeshingTask) guarantees no block entities and no
        // animated sprites, so empty collections here are always correct,
        // not just a placeholder. The dummy VisibilitySet only needs to
        // produce a length-1 array; its content is immediately overwritten.
        BuiltSectionInfo info = BuiltSectionInfoInvoker.sodiumMeshCache$construct(
                passList,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                new VisibilitySet[] { new VisibilitySet() });

        ((BuiltSectionInfoAccessor) info).sodiumMeshCache$setVisibilityData(cached.visibilityData);

        return new ChunkBuildOutput(section, frame, null, info, meshes, false);
    }
}
