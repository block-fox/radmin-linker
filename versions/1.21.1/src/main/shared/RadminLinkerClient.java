package dev.radminlinker;

import dev.radminlinker.config.RadminConfig;
import dev.radminlinker.network.RadminBackend;
import dev.radminlinker.network.RadminBackends;
import dev.radminlinker.compat.LanWorldsProvider;
import dev.radminlinker.ui.RadminScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

import java.util.concurrent.atomic.AtomicBoolean;

public final class RadminLinkerClient implements ClientModInitializer {
    public static final String MOD_ID = "radminlinker";
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
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> BACKEND.prepareLocalIdentity(client.getUser().getName(),
                client.getLanguageManager().getSelected().startsWith("zh_")));
        refreshSnapshot();
        int[] ticksSinceRefresh = {0};
        int[] ticksSinceTelemetry = {0};
        int[] publishedPort = {0};
        String[] publishedMotd = {""};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            var server = client.getSingleplayerServer();
            int port = server != null && server.isPublished() ? server.getPort() : 0;
            String motd = port > 0 ? server.getMotd() : "";
            if (port != publishedPort[0] || !java.util.Objects.equals(motd, publishedMotd[0])) {
                BACKEND.setPublishedRoom(Math.max(0, port), motd);
                publishedPort[0] = port;
                publishedMotd[0] = motd;
            }
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
