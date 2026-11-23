import dev.radminlinker.RadminLinkerClient;
import dev.radminlinker.network.RadminBackend;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.fabricmc.loader.api.FabricLoader;

/** Verifies the real Fabric mappings and the embedded provider without going online. */
public final class CoreRoutingMixinSmoke implements ClientModInitializer {
    @Override public void onInitializeClient() {
        if (Boolean.getBoolean("radminlan.smokeMultiplayer")) {
            ClientTickEvents.END_CLIENT_TICK.register(this::openMultiplayer);
        } else {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> checkDefaultBackend());
        }
    }

    private void openMultiplayer(Minecraft client) {
        try {
            Screen current;
            try {
                var gui = client.getClass().getField("gui").get(client);
                current = (Screen) gui.getClass().getMethod("screen").invoke(gui);
            } catch (NoSuchMethodException olderClient) {
                current = (Screen) client.getClass().getField("screen").get(client);
            }
            if (!(current instanceof TitleScreen title)) return;
            var multiplayer = new JoinMultiplayerScreen(title);
            java.lang.reflect.Method open;
            try { open = client.getClass().getMethod("setScreenAndShow", Screen.class); }
            catch (NoSuchMethodException olderClient) { open = client.getClass().getMethod("setScreen", Screen.class); }
            open.invoke(client, multiplayer);
            if (multiplayer.children().isEmpty()) throw new AssertionError("Multiplayer screen did not initialize");
            if (!FabricLoader.getInstance().isModLoaded("lanworlds")) checkEmbeddedRows(multiplayer);
            System.out.println("RADMIN_MULTIPLAYER_SMOKE_OK ViaFabricPlus=" + FabricLoader.getInstance().isModLoaded("viafabricplus"));
            checkDefaultBackend();
        } catch (Throwable error) {
            error.printStackTrace();
            System.exit(1);
        }
    }

    private void checkEmbeddedRows(JoinMultiplayerScreen screen) throws ReflectiveOperationException {
        var original = RadminLinkerClient.BACKEND;
        var rooms = new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of(
                new RadminBackend.LanRoom("Embedded Test Room", "26.11.12.13", 25565)));
        // Serve the room only inside this check. No network sockets or real memberships are touched.
        RadminLinkerClient.BACKEND = (RadminBackend) java.lang.reflect.Proxy.newProxyInstance(
                original.getClass().getClassLoader(), new Class<?>[]{RadminBackend.class},
                (proxy, method, args) -> method.getName().equals("lanRooms") ? rooms.get() : method.invoke(original, args));
        try {
            var listField = JoinMultiplayerScreen.class.getDeclaredField("serverSelectionList");
            listField.setAccessible(true);
            var list = (net.minecraft.client.gui.screens.multiplayer.ServerSelectionList) listField.get(screen);
            screen.tick();
            if (!containsFixtureRoom(list)) {
                throw new AssertionError("Embedded room was not published to vanilla");
            }
            rooms.set(java.util.List.of());
            screen.tick();
            if (containsFixtureRoom(list)) {
                throw new AssertionError("Expired embedded room remained in vanilla");
            }
            System.out.println("RADMIN_EMBEDDED_ROWS_SMOKE_OK");
        } finally {
            RadminLinkerClient.BACKEND = original;
        }
    }

    private boolean containsFixtureRoom(net.minecraft.client.gui.screens.multiplayer.ServerSelectionList list)
            throws ReflectiveOperationException {
        var type = net.minecraft.client.gui.screens.multiplayer.ServerSelectionList.NetworkServerEntry.class;
        var data = type.getDeclaredField("serverData");
        data.setAccessible(true);
        for (var entry : list.children()) {
            if (type.isInstance(entry) && data.get(entry) instanceof net.minecraft.client.server.LanServer server
                    && server.getAddress().equals("26.11.12.13:25565")) return true;
        }
        return false;
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
            var backend = RadminLinkerClient.BACKEND;
            if (backend == null) throw new AssertionError("Backend did not initialize");
            var snapshot = backend.snapshot().get(9, java.util.concurrent.TimeUnit.SECONDS);
            boolean official = Boolean.getBoolean("radminlan.smokeOfficial");
            boolean selected = official ? snapshot.transport().contains("RadminVpnGuiChannel")
                    : "Radmin Linker Core".equals(snapshot.transport());
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
