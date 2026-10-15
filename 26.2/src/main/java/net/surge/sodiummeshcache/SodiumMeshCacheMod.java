package net.surge.sodiummeshcache;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.surge.sodiummeshcache.cache.MeshCacheManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public final class SodiumMeshCacheMod implements ClientModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("Surgeon");

    @Override
    public void onInitializeClient() {
        MeshCacheManager.loadConfig();

        // World/dimension detection itself happens in MixinRenderSectionManager's
        // constructor injection - a RenderSectionManager is created exactly once
        // per loaded ClientLevel, which covers both world join and dimension
        // travel; a connection-level JOIN event would miss the latter.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            LOGGER.info("Sodium Mesh Cache: {} hits, {} misses, {} writes this session",
                    MeshCacheManager.getHits(), MeshCacheManager.getMisses(), MeshCacheManager.getWrites());
        });
    }
}
