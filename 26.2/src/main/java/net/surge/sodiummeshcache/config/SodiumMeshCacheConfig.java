package net.surge.sodiummeshcache.config;

import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.surge.sodiummeshcache.cache.MeshCacheManager;

/**
 * Registers this mod's options as a page inside Sodium's own video settings
 * UI, via Sodium's public config API (entrypoint key
 * "sodium:config_api_user" in fabric.mod.json) - the same mechanism Sodium
 * Extra and Reese's Sodium Options use, rather than a separate standalone
 * screen.
 *
 * Both options are bound straight to {@link MeshCacheManager}'s existing
 * getters/setters, which already persist to config/surgeon.properties
 * on every call - the storage handler here is therefore a no-op, kept only
 * because every option in the reference implementation (Sodium Extra) sets
 * one.
 */
public class SodiumMeshCacheConfig implements ConfigEntryPoint {
    private static final long BYTES_PER_MB = 1024 * 1024;

    private static Identifier id(String path) {
        return Identifier.parse("surgeon:" + path);
    }

    @Override
    public void registerConfigLate(ConfigBuilder builder) {
        builder.registerOwnModOptions()
                .setNonTintedIcon(Identifier.parse("surgeon:textures/icon.png"))
                .addPage(builder.createOptionPage()
                        .setName(Component.translatable("surgeon.option.page"))
                        .addOptionGroup(builder.createOptionGroup()
                                .addOption(builder.createBooleanOption(id("enabled"))
                                        .setName(Component.translatable("surgeon.option.enabled"))
                                        .setTooltip(Component.translatable("surgeon.option.enabled.tooltip"))
                                        .setBinding(MeshCacheManager::setEnabled, MeshCacheManager::isEnabled)
                                        .setDefaultValue(true)
                                        .setStorageHandler(() -> {
                                        })
                                )
                                .addOption(builder.createIntegerOption(id("max_cache_mb"))
                                        .setName(Component.translatable("surgeon.option.max_cache_mb"))
                                        .setTooltip(Component.translatable("surgeon.option.max_cache_mb.tooltip"))
                                        .setRange(128, 8192, 128)
                                        .setValueFormatter(mb -> Component.literal(mb + " MB"))
                                        .setBinding(
                                                mb -> MeshCacheManager.setMaxCacheBytes((long) mb * BYTES_PER_MB),
                                                () -> (int) (MeshCacheManager.getMaxCacheBytes() / BYTES_PER_MB)
                                        )
                                        .setDefaultValue(2048)
                                        .setStorageHandler(() -> {
                                        })
                                )
                                .addOption(builder.createExternalButtonOption(id("clear_cache"))
                                        .setName(Component.translatable("surgeon.option.clear_cache"))
                                        .setTooltip(Component.translatable("surgeon.option.clear_cache.tooltip"))
                                        .setScreenConsumer(screen -> Minecraft.getInstance().setScreenAndShow(new ConfirmScreen(
                                                confirmed -> {
                                                    if (confirmed) {
                                                        MeshCacheManager.clearAll();
                                                    }
                                                    Minecraft.getInstance().setScreenAndShow(screen);
                                                },
                                                Component.translatable("surgeon.option.clear_cache.confirm.title"),
                                                Component.translatable("surgeon.option.clear_cache.confirm.message")
                                        )))
                                )
                        )
                );
    }
}
