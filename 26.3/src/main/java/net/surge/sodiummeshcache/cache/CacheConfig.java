package net.surge.sodiummeshcache.cache;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Plain key=value config at config/surgeon.properties. Deliberately
 * not JSON5/GSON - this mod has exactly two settings, a Properties file is
 * the least code that can possibly work and needs no extra dependency.
 */
public final class CacheConfig {
    private static final Logger LOGGER = LogManager.getLogger("Surgeon");
    private static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024; // 2 GiB

    public boolean enabled = true;
    public long maxCacheBytes = DEFAULT_MAX_BYTES;

    public static CacheConfig load() {
        CacheConfig config = new CacheConfig();
        Path path = configPath();

        if (Files.isRegularFile(path)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(path)) {
                props.load(in);
            } catch (IOException e) {
                LOGGER.warn("Failed to read surgeon.properties, using defaults", e);
                return config;
            }

            config.enabled = Boolean.parseBoolean(props.getProperty("enabled", "true"));
            try {
                config.maxCacheBytes = Long.parseLong(props.getProperty("max_cache_mb", String.valueOf(DEFAULT_MAX_BYTES / (1024 * 1024)))) * 1024 * 1024;
            } catch (NumberFormatException e) {
                config.maxCacheBytes = DEFAULT_MAX_BYTES;
            }
        } else {
            config.save();
        }

        return config;
    }

    public void save() {
        Properties props = new Properties();
        props.setProperty("enabled", String.valueOf(this.enabled));
        props.setProperty("max_cache_mb", String.valueOf(this.maxCacheBytes / (1024 * 1024)));

        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            try (OutputStream out = Files.newOutputStream(path)) {
                props.store(out, "Surgeon - disk cache of built chunk section meshes.\n"
                        + "enabled: turn the cache on/off entirely.\n"
                        + "max_cache_mb: soft disk size cap per world; oldest entries are evicted first.");
            }
        } catch (IOException e) {
            LOGGER.warn("Failed to write surgeon.properties", e);
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("surgeon.properties");
    }
}
