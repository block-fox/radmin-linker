package dev.radminlan;

import dev.radminlan.config.RadminConfig;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.network.RadminBackends;
import dev.radminlan.compat.LanWorldsProvider;
import dev.radminlan.ui.RadminScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.concurrent.atomic.AtomicBoolean;

public final class RadminLanClient implements ClientModInitializer {
    public static final String MOD_ID = "radminlan";
    public static RadminConfig CONFIG;
    public static RadminBackend BACKEND;
    private static volatile RadminBackend.RadminSnapshot cachedSnapshot =
            RadminBackend.RadminSnapshot.unavailable("Loading...");
    private static final AtomicBoolean SNAPSHOT_REQUEST_PENDING = new AtomicBoolean();

    public static RadminBackend.RadminSnapshot cachedSnapshot() {
        return cachedSnapshot;
    }

    public static void publishSnapshot(RadminBackend.RadminSnapshot snapshot) {
        if (snapshot != null) cachedSnapshot = snapshot;
    }

    @Override
    public void onInitializeClient() {
        CONFIG = RadminConfig.load();
        BACKEND = RadminBackends.create(CONFIG);
        LanWorldsProvider.register();
        refreshSnapshot();
        int[] ticksSinceRefresh = {0};
        int[] ticksSinceTelemetry = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // This is a local cache read. Service events reach both the
            // management screen and room integrations without another request.
            if (CONFIG.autoRefresh && ++ticksSinceTelemetry[0] >= 2) {
                ticksSinceTelemetry[0] = 0;
                publishSnapshot(BACKEND.latestSnapshot());
            }
            // The management screen owns its refresh cadence while open.
            // Reset the background timer while paused so leaving the screen
            // does not immediately repeat its most recent request.
            if (!CONFIG.autoRefresh || client.screen instanceof RadminScreen) {
                ticksSinceRefresh[0] = 0;
                return;
            }
            if (++ticksSinceRefresh[0] >= CONFIG.getRefreshIntervalTicks()) {
                ticksSinceRefresh[0] = 0;
                refreshSnapshot();
            }
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            LanWorldsProvider.unregister();
            BACKEND.close();
            CONFIG.save();
        });
    }

    private static void refreshSnapshot() {
        if (BACKEND == null || !SNAPSHOT_REQUEST_PENDING.compareAndSet(false, true)) return;
        try {
            BACKEND.snapshot().whenComplete((snapshot, error) -> {
                if (error == null && snapshot != null) {
                    RadminBackend.RadminSnapshot latest = BACKEND.latestSnapshot();
                    publishSnapshot(latest == null ? snapshot : latest);
                }
                SNAPSHOT_REQUEST_PENDING.set(false);
            });
        } catch (RuntimeException ignored) {
            SNAPSHOT_REQUEST_PENDING.set(false);
        }
    }
}
