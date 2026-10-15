package net.surge.sodiummeshcache.cache;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Entry point for the on-disk mesh cache: path layout, world namespacing,
 * hit/miss bookkeeping and disk-size eviction. The eligibility decision
 * (which sections are safe to cache at all) and the content-hash computation
 * live next to the mixin that has access to the block/light data, since both
 * need the padded-volume scan; this class only knows how to store/retrieve
 * bytes once a hash has been computed.
 *
 * Eligibility, as enforced by the caller: a section is only ever cached when
 * {@code BuiltSectionInfo.flags} has neither MASK_HAS_BLOCK_ENTITIES nor
 * MASK_HAS_ANIMATED_SPRITES set, and its mesh map contains no TRANSLUCENT
 * pass. Block entities/animated sprites reference live, session-specific
 * objects (BlockEntity instances, TextureAtlasSprite instances) that aren't
 * meaningfully serializable, and translucent geometry ordering depends on
 * the dynamic sort-triggering system, not just block content - caching it
 * would require re-deriving sorter state on load, which isn't attempted in
 * this version. All three are comparatively cheap to recompute anyway; the
 * expensive part this cache targets is the per-block model/fluid quad baking
 * for the solid/cutout passes.
 *
 * Storage is content-addressed and globally deduplicated (see
 * SectionMeshCacheIO): many sections share byte-identical geometry (flat
 * stone layers, superflat worlds, repeated strip-mine tunnels), and that
 * holds across different worlds/servers too - a stone block's mesh doesn't
 * depend on which save it's in - so the blob store is one shared directory
 * for the whole player, not per-world. Only the small per-section pointer
 * files (content-validity hash + which blob each render pass points at) are
 * namespaced per world/server/dimension.
 */
public final class MeshCacheManager {
    private static final Logger LOGGER = LogManager.getLogger("Surgeon");
    private static final int EVICTION_CHECK_INTERVAL = 200;

    private static volatile CacheConfig config = new CacheConfig();
    private static volatile Path worldDir;
    private static volatile String currentDimensionId = "unknown";

    private static final AtomicLong hits = new AtomicLong();
    private static final AtomicLong misses = new AtomicLong();
    private static final AtomicLong writes = new AtomicLong();
    private static final AtomicLong bytesServedFromCache = new AtomicLong();
    private static final AtomicLong writesSinceEvictionCheck = new AtomicLong();

    // Single background thread: disk writes and eviction scans must never
    // stall the render/build threads. Bounded queue with a caller-runs policy
    // means that if writes somehow pile up faster than disk can absorb them,
    // we degrade to synchronous writes rather than growing memory unbounded.
    private static final ThreadPoolExecutor IO_EXECUTOR = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(512),
            runnable -> {
                Thread t = new Thread(runnable, "Surgeon IO");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private MeshCacheManager() {
    }

    public static void loadConfig() {
        config = CacheConfig.load();
    }

    public static boolean isEnabled() {
        return config.enabled;
    }

    public static void setEnabled(boolean value) {
        config.enabled = value;
        config.save();
    }

    public static long getMaxCacheBytes() {
        return config.maxCacheBytes;
    }

    public static void setMaxCacheBytes(long value) {
        config.maxCacheBytes = value;
        config.save();
    }

    /**
     * Called once per {@code RenderSectionManager} construction, i.e. once
     * per loaded ClientLevel - world join, and every dimension change, both
     * create a fresh level client-side. Derives both the world namespace
     * (which world/server this is - singleplayer save name, or server
     * address) and the dimension id, so pointer files for the overworld,
     * nether and end never collide.
     *
     * The namespace only affects pointer directory layout and therefore
     * cache hit rate, never correctness: every read is additionally gated on
     * a content hash of the actual block/light data, so a wrong or reused
     * namespace can at worst cause an avoidable miss, never a wrong mesh
     * being served.
     */
    public static void setCurrentWorld(ClientLevel level) {
        currentDimensionId = level.dimension().identifier().toString();

        String namespace;
        try {
            Minecraft client = Minecraft.getInstance();
            if (client.hasSingleplayerServer() && client.getSingleplayerServer() != null) {
                namespace = "sp-" + client.getSingleplayerServer().getWorldData().getLevelName();
            } else if (client.getCurrentServer() != null) {
                namespace = "mp-" + client.getCurrentServer().ip;
            } else {
                namespace = "shared";
            }
        } catch (Throwable t) {
            LOGGER.warn("Could not determine world namespace for mesh cache, falling back to shared bucket", t);
            namespace = "shared";
        }

        String safe = namespace.replaceAll("[^a-zA-Z0-9._-]", "_");
        worldDir = baseDir().resolve("worlds").resolve(safe);
    }

    private static Path baseDir() {
        return FabricLoader.getInstance().getConfigDir().resolve("surgeon");
    }

    private static Path pointerPathFor(int sectionX, int sectionY, int sectionZ) {
        String safeDim = currentDimensionId.replaceAll("[^a-zA-Z0-9._-]", "_");
        String fileName = sectionX + "_" + sectionY + "_" + sectionZ + ".bin";
        return worldDir.resolve(safeDim).resolve("sections").resolve(fileName);
    }

    /**
     * Global and shared across every world/server this player has visited -
     * see the class doc for why that's safe.
     */
    private static Path blobsDir() {
        return baseDir().resolve("blobs");
    }

    public static CachedSectionMesh tryRead(int sectionX, int sectionY, int sectionZ, long contentHash) {
        if (!config.enabled || worldDir == null) {
            return null;
        }

        Path pointer = pointerPathFor(sectionX, sectionY, sectionZ);
        CachedSectionMesh result = SectionMeshCacheIO.readIfValid(pointer, blobsDir(), contentHash);

        if (result != null) {
            hits.incrementAndGet();
            long bytes = 0;
            for (CachedPass pass : result.passes) {
                bytes += pass.vertexData.length;
            }
            bytesServedFromCache.addAndGet(bytes);
        } else {
            misses.incrementAndGet();
        }

        return result;
    }

    /**
     * Queues the write on a background thread so a cache miss never costs
     * the render thread more than the (unavoidable) mesh build it already
     * has to do.
     */
    public static void write(int sectionX, int sectionY, int sectionZ, CachedSectionMesh mesh) {
        if (!config.enabled || worldDir == null) {
            return;
        }

        Path pointer = pointerPathFor(sectionX, sectionY, sectionZ);
        Path blobsDirSnapshot = blobsDir();
        long maxBytes = config.maxCacheBytes;

        IO_EXECUTOR.execute(() -> {
            try {
                SectionMeshCacheIO.write(pointer, blobsDirSnapshot, mesh);
                writes.incrementAndGet();
            } catch (IOException e) {
                LOGGER.warn("Failed to write mesh cache entry at {}", pointer, e);
                return;
            }

            if (writesSinceEvictionCheck.incrementAndGet() >= EVICTION_CHECK_INTERVAL) {
                writesSinceEvictionCheck.set(0);
                evictBlobsIfOverBudget(blobsDirSnapshot, maxBytes);
            }
        });
    }

    /**
     * Least-recently-used eviction of the (global, shared) blob store. A
     * blob being evicted while some world's pointer still references it is
     * always safe: that pointer just becomes a miss on next read, which
     * rebuilds and rewrites it - see SectionMeshCacheIO's class doc. This is
     * why eviction doesn't need to cross-reference pointer files at all,
     * unlike a naive reference-counted scheme would.
     *
     * "Recently used" is tracked via each blob file's own mtime, bumped on
     * every read hit and on a write that finds the blob already present -
     * see SectionMeshCacheIO.touch.
     */
    private static void evictBlobsIfOverBudget(Path blobsDir, long maxBytes) {
        if (!Files.isDirectory(blobsDir)) {
            return;
        }

        record Entry(Path path, long size, long mtime) {
        }

        List<Entry> entries = new ArrayList<>();
        long total = 0;

        try (Stream<Path> list = Files.list(blobsDir)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                if (!Files.isRegularFile(p) || !p.getFileName().toString().endsWith(".blob")) {
                    continue;
                }
                long size = Files.size(p);
                long mtime = Files.getLastModifiedTime(p).toMillis();
                entries.add(new Entry(p, size, mtime));
                total += size;
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to scan blob store {} for eviction", blobsDir, e);
            return;
        }

        if (total <= maxBytes) {
            return;
        }

        entries.sort(Comparator.comparingLong(Entry::mtime));

        long toFree = total - maxBytes;
        long freed = 0;
        int deleted = 0;

        for (Entry e : entries) {
            if (freed >= toFree) {
                break;
            }
            try {
                Files.deleteIfExists(e.path());
                freed += e.size();
                deleted++;
            } catch (IOException ignored) {
                // Another thread/process may have touched it; not fatal.
            }
        }

        LOGGER.info("Surgeon: evicted {} least-recently-used mesh blobs ({} bytes) to stay under the {} byte budget",
                deleted, freed, maxBytes);
    }

    public static long getHits() {
        return hits.get();
    }

    public static long getMisses() {
        return misses.get();
    }

    public static long getWrites() {
        return writes.get();
    }

    public static long getBytesServedFromCache() {
        return bytesServedFromCache.get();
    }

    /**
     * Wipes every pointer file (across all worlds/servers) and the entire
     * shared blob store. Runs on the IO executor thread, serialized against
     * any in-flight writes, so there's no race with normal cache traffic -
     * at worst a write queued just before this runs gets its pointer/blob
     * deleted right after being created, which just means that section is a
     * miss again next time, same as any other eviction.
     */
    public static void clearAll() {
        IO_EXECUTOR.execute(() -> {
            Path base = baseDir();
            if (!Files.isDirectory(base)) {
                return;
            }

            long freed = 0;
            int deleted = 0;

            try (Stream<Path> walk = Files.walk(base)) {
                List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
                for (Path p : paths) {
                    if (p.equals(base)) {
                        continue;
                    }
                    try {
                        if (Files.isRegularFile(p)) {
                            freed += Files.size(p);
                        }
                        Files.deleteIfExists(p);
                        deleted++;
                    } catch (IOException ignored) {
                        // Another thread/process may have touched it; not fatal.
                    }
                }
            } catch (IOException e) {
                LOGGER.warn("Failed to clear cache directory {}", base, e);
                return;
            }

            hits.set(0);
            misses.set(0);
            writes.set(0);
            bytesServedFromCache.set(0);

            LOGGER.info("Surgeon: cache cleared - removed {} files ({} bytes)", deleted, freed);
        });
    }
}
