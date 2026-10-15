package net.surge.sodiummeshcache.mixin;

import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.surge.sodiummeshcache.cache.CachedMeshReconstructor;
import net.surge.sodiummeshcache.cache.CachedSectionMesh;
import net.surge.sodiummeshcache.cache.MeshCacheManager;
import net.surge.sodiummeshcache.cache.SectionContentHashing;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Core of the mesh cache: before a section's rebuild task would normally be
 * handed to a worker thread, check whether a previous session already built
 * an identical mesh (same block states + light values in the section and its
 * 2-block halo) and, if so, skip straight to a synthesized build result
 * instead of re-running the per-block model/fluid baking.
 *
 * Only solid/cutout geometry is ever served this way - see
 * MixinChunkBuilderMeshingTask for the matching write-back and why
 * translucent/block-entity/animated-sprite sections are excluded.
 */
@Mixin(RenderSectionManager.class)
public abstract class MixinRenderSectionManager {
    @Shadow
    @Final
    private ClientLevel level;

    @Shadow
    @Final
    private ClonedChunkSectionCache sectionCache;

    @Shadow
    @Final
    private ConcurrentLinkedDeque<ChunkJobResult<? extends BuilderTaskOutput>> buildResults;

    @Shadow
    private int frame;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void sodiumMeshCache$onConstructed(ClientLevel level, int renderDistance, SortBehavior sortBehavior, CallbackInfo ci) {
        MeshCacheManager.setCurrentWorld(level);
    }

    @Inject(
            method = "submitSectionTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;ILnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/UploadResourceBudget;Z)V",
            at = @At("HEAD"),
            cancellable = true
    )
    private void sodiumMeshCache$tryServeFromCache(ChunkJobCollector collector, @NotNull RenderSection section, int type,
                                                     UploadResourceBudget uploadBudget, boolean blocking, CallbackInfo ci) {
        if (!MeshCacheManager.isEnabled() || section.isDisposed()) {
            return;
        }

        if (!(ChunkUpdateTypes.isInitialBuild(type) || ChunkUpdateTypes.isRebuild(type))) {
            // Sort-only tasks never touch the mesh cache.
            return;
        }

        ChunkRenderContext context = LevelSlice.prepare(this.level, section.getPosition(), this.sectionCache);
        if (context == null) {
            // Vanilla's own null-context (truly empty section) path handles this.
            return;
        }

        LevelSlice slice = new LevelSlice(this.level);
        slice.copyData(context);

        long contentHash = SectionContentHashing.hash(slice, section.getOriginX(), section.getOriginY(), section.getOriginZ());

        CachedSectionMesh cached = MeshCacheManager.tryRead(section.getChunkX(), section.getChunkY(), section.getChunkZ(), contentHash);
        if (cached == null) {
            // Cache miss: let vanilla build it (and, if eligible, write it
            // back - see MixinChunkBuilderMeshingTask).
            return;
        }

        ChunkBuildOutput output = CachedMeshReconstructor.build(section, this.frame, cached);
        this.buildResults.add(ChunkJobResult.successfully(output));
        section.clearPendingUpdate();

        ci.cancel();
    }
}
