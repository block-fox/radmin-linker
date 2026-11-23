import dev.radminlan.RadminLanClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;

/** Verifies the real Fabric mappings and the embedded provider without going online. */
public final class CoreRoutingMixinSmoke implements ClientModInitializer {
    @Override public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> checkDefaultBackend());
    }

    private void checkDefaultBackend() {
        try {
            String[] targets = {
                    "net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen",
                    "net.minecraft.network.Connection",
                    "net.minecraft.client.multiplayer.ServerStatusPinger",
                    "net.minecraft.client.multiplayer.resolver.ServerNameResolver"
            };
            for (String target : targets) Class.forName(target, false, getClass().getClassLoader()).getDeclaredMethods();
            var actualDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
            var expectedDir = java.nio.file.Path.of(System.getProperty("radminlan.smokeDirectory")).toAbsolutePath().normalize();
            if (!actualDir.equals(expectedDir)) throw new AssertionError("Unexpected smoke game directory: " + actualDir);
            var backend = RadminLanClient.BACKEND;
            if (backend == null) throw new AssertionError("Backend did not initialize");
            var snapshot = backend.snapshot().get(9, java.util.concurrent.TimeUnit.SECONDS);
            boolean official = Boolean.getBoolean("radminlan.smokeOfficial");
            boolean selected = official ? snapshot.transport().contains("RadminVpnGuiChannel")
                    : "RadminLAN Core".equals(snapshot.transport());
            if (!selected) throw new AssertionError("Unexpected backend for AUTO with official=" + official + ": " + snapshot.transport());
            String version = FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString();
            System.out.println("RADMIN_CORE_ROUTING_SMOKE_OK Minecraft=" + version + " targets=" + targets.length + " official=" + official);
            backend.close();
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }
}
