import com.google.gson.JsonParser;
import dev.radminlan.config.RadminConfig;
import dev.radminlan.input.MouseButtons;
import dev.radminlan.network.RadminBackend;
import dev.radminlan.ui.RadminSettingsPanel;
import dev.radminlan.ui.RadminScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.network.chat.Component;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class UiRegressionCheck {
    public static void main(String[] args) throws Exception {
        boolean legacy = !args[0].equals("26.3");
        int expectedLeft = legacy ? 0 : 1;
        int expectedRight = legacy ? 1 : 3;
        require(MouseButtons.left() == expectedLeft, "left button mapping");
        require(MouseButtons.right() == expectedRight, "right button mapping");
        Button vanilla = Button.builder(Component.literal("Test"), input -> {}).build();
        Class<?> clickType = args[0].equals("1.21.1") ? int.class : Class.forName("net.minecraft.client.input.MouseButtonInfo");
        Method accepts = AbstractWidget.class.getDeclaredMethod("isValidClickButton", clickType);
        accepts.setAccessible(true);
        Object left = clickType == int.class ? MouseButtons.left() : clickType.getConstructor(int.class, int.class).newInstance(MouseButtons.left(), 0);
        Object right = clickType == int.class ? MouseButtons.right() : clickType.getConstructor(int.class, int.class).newInstance(MouseButtons.right(), 0);
        require((boolean) accepts.invoke(vanilla, left), "left click accepted by the real vanilla widget");
        require(!(boolean) accepts.invoke(vanilla, right), "right click does not activate vanilla actions");
        var empty = new RadminBackend.RadminSnapshot(RadminBackend.ServiceState.READY, "", "", null, "");
        require(empty.networks().isEmpty(), "missing network list is safe for property views");
        var networks = new ArrayList<RadminBackend.Network>();
        networks.add(new RadminBackend.Network("Test", "abc", "", null));
        var snapshot = new RadminBackend.RadminSnapshot(RadminBackend.ServiceState.READY, "", "", networks, "");
        networks.clear();
        require(snapshot.networks().size() == 1, "UI snapshot is stable during backend changes");
        require(snapshot.networks().getFirst().peers().isEmpty(), "missing member list is safe");
        try {
            snapshot.networks().clear();
            throw new AssertionError("snapshot list must be immutable");
        } catch (UnsupportedOperationException expected) {
        }
        checkRefreshConfiguration(args[0]);
        checkRefreshIntervalInput();
        checkNetworkSelectionIdentity();
        checkLiveSelectionIdentity();
        BatchOperationsRegression.check();
        BackendFallbackRegression.check();
        ListPreferencesRegression.check();
        ScreenLayoutRegression.check();
        NetworkParsingRegression.check();
        WireFramingRegression.check();
        LanWorldsProviderRegression.check();
        System.out.println("UI_REGRESSION_OK Minecraft=" + args[0] + " protocol=" + SharedConstants.getProtocolVersion()
            + " left=" + MouseButtons.left() + " right=" + MouseButtons.right());
    }

    static void checkRefreshConfiguration(String minecraftVersion) throws Exception {
        // Set only the loader's directory before RadminConfig initializes its static FILE.
        // The real load/save path then exercises Gson defaults and persisted normalization.
        Path workDir = Path.of(System.getProperty("uiRegression.workDir")).toAbsolutePath().normalize();
        Path isolatedGameDir = Files.createTempDirectory(workDir, "runtime-" + minecraftVersion + "-");
        FabricLoader loader = FabricLoader.getInstance();
        Method setGameDir = loader.getClass().getDeclaredMethod("setGameDir", Path.class);
        setGameDir.setAccessible(true);
        setGameDir.invoke(loader, isolatedGameDir);
        Path configFile = loader.getConfigDir().resolve("radminlan.json");
        require(configFile.startsWith(workDir), "config fixture stays inside the temporary regression directory");

        Files.writeString(configFile, "{\"enableCli\":false,\"queryTimeoutMs\":1700}");
        RadminConfig oldConfig = RadminConfig.load();
        require(oldConfig.autoRefresh, "older JSON enables automatic refresh by default");
        require(oldConfig.refreshIntervalSeconds == 2, "older JSON defaults to a two-second interval");
        require(oldConfig.getRefreshIntervalTicks() == 40, "default interval schedules forty game ticks");
        require(!oldConfig.enableCli && oldConfig.queryTimeoutMs == 1700, "older JSON keeps existing settings");
        require("LIST".equals(oldConfig.networkViewMode), "older JSON defaults the network view to list");
        require("LIST".equals(oldConfig.peerViewMode), "older JSON defaults the peer view to list");
        var migrated = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
        require(migrated.get("autoRefresh").getAsBoolean(), "migration saves the default automatic refresh setting");
        require(migrated.get("refreshIntervalSeconds").getAsInt() == 2, "migration saves the default interval");

        oldConfig.networkViewMode = "DETAILS";
        oldConfig.peerViewMode = "LARGE_ICONS";
        oldConfig.save();
        RadminConfig savedViews = RadminConfig.load();
        require("DETAILS".equals(savedViews.networkViewMode), "network view survives save and reload");
        require("LARGE_ICONS".equals(savedViews.peerViewMode), "peer view survives save and reload independently");
        var persistedViews = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
        require("DETAILS".equals(persistedViews.get("networkViewMode").getAsString()), "network view is persisted in JSON");
        require("LARGE_ICONS".equals(persistedViews.get("peerViewMode").getAsString()), "peer view is persisted in JSON");

        for (int seconds : new int[] {Integer.MIN_VALUE, -1, 0, 61, Integer.MAX_VALUE}) {
            int expectedSeconds = seconds < 1 ? 1 : 60;
            Files.writeString(configFile, "{\"autoRefresh\":false,\"refreshIntervalSeconds\":" + seconds + "}");
            RadminConfig loaded = RadminConfig.load();
            require(!loaded.autoRefresh, "explicitly disabled refresh survives loading: " + seconds);
            require(loaded.refreshIntervalSeconds == expectedSeconds, "invalid saved interval is normalized: " + seconds);
            require(loaded.getRefreshIntervalTicks() == expectedSeconds * 20, "normalized interval produces safe ticks: " + seconds);
            var saved = JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
            require(saved.get("refreshIntervalSeconds").getAsInt() == expectedSeconds,
                    "normalized interval is written back to JSON: " + seconds);
        }

        for (int seconds : new int[] {1, 2, 30, 60}) {
            Files.writeString(configFile, "{\"refreshIntervalSeconds\":" + seconds + "}");
            RadminConfig loaded = RadminConfig.load();
            require(loaded.refreshIntervalSeconds == seconds, "valid saved interval is preserved: " + seconds);
            require(loaded.getRefreshIntervalTicks() == seconds * 20, "valid saved interval converts to ticks: " + seconds);
        }
        System.out.println("CONFIG_REGRESSION_OK isolatedConfig=" + configFile);
    }

    static void checkNetworkSelectionIdentity() throws Exception {
        Method key = RadminScreen.class.getDeclaredMethod("networkKey", RadminBackend.Network.class);
        key.setAccessible(true);
        var alpha = new RadminBackend.Network("Alpha", "", "", List.of());
        var beta = new RadminBackend.Network("Beta", "", "", List.of());
        require(!key.invoke(null, alpha).equals(key.invoke(null, beta)),
                "name-only CLI networks can be selected independently");
        require(key.invoke(null, alpha).equals(key.invoke(null, new RadminBackend.Network("Alpha", null, "", List.of()))),
                "missing GUID representations preserve the same network selection");
        require(key.invoke(null, new RadminBackend.Network("Renamed", "abc", "", List.of())).equals("abc"),
                "pipe-backed network selection keeps its GUID across renames");

        RadminConfig config = new RadminConfig();
        config.disabledNetworks.add("abc");
        require(config.isNetworkDisabled("ABC", null), "missing service flag uses the local disabled fallback");
        require(!config.isNetworkDisabled("ABC", true), "explicit service enable overrides a stale local disabled flag");
        require(config.isNetworkDisabled("other", false), "explicit service disable overrides the local fallback");
        require(!config.isNetworkDisabled("other", null), "unrecorded network remains enabled when the service omits its flag");
        System.out.println("NETWORK_SELECTION_REGRESSION_OK cliIdentity=true serviceState=true");
    }

    static void checkRefreshIntervalInput() throws Exception {
        for (int seconds = 1; seconds <= 60; seconds++) {
            require(Integer.valueOf(seconds).equals(RadminSettingsPanel.parseInterval(Integer.toString(seconds))),
                    "settings screen accepts an integer interval: " + seconds);
        }
        for (String invalid : new String[] {null, "", " ", "\t", "0", "61", "-1", "2.0", "abc", "1 ", " 1", "2147483648"}) {
            require(RadminSettingsPanel.parseInterval(invalid) == null,
                    "settings screen rejects invalid interval text: " + invalid);
        }
        System.out.println("INTERVAL_INPUT_REGRESSION_OK validRange=1..60 invalidCases=12");
    }

    static void checkLiveSelectionIdentity() throws Exception {
        Method network = RadminScreen.class.getDeclaredMethod("resolveNetwork",
                RadminBackend.Network.class, RadminBackend.RadminSnapshot.class);
        Method peer = RadminScreen.class.getDeclaredMethod("resolvePeer",
                RadminBackend.Peer.class, RadminBackend.RadminSnapshot.class);
        network.setAccessible(true);
        peer.setAccessible(true);
        var oldPeer = new RadminBackend.Peer("Before", "26.1.2.3", "", "online", 15,
                true, 101, "", "", "Friends", 1L, "");
        var updatedPeer = new RadminBackend.Peer("After", "26.1.2.3", "", "offline", -1,
                false, 101, "", "", "Friends", 1L, "");
        var elsewhere = new RadminBackend.Peer("After", "26.1.2.3", "", "offline", -1,
                false, 101, "", "", "Other", 2L, "");
        var oldNetwork = new RadminBackend.Network("Friends", "abc", "", List.of(oldPeer));
        var updatedNetwork = new RadminBackend.Network("Friends", "abc", "", List.of(updatedPeer));
        var current = new RadminBackend.RadminSnapshot(RadminBackend.ServiceState.READY, "", "",
                List.of(new RadminBackend.Network("Other", "def", "", List.of(elsewhere)), updatedNetwork), "");
        require(network.invoke(null, oldNetwork, current) == updatedNetwork,
                "live updates retain selected network by its key");
        require(peer.invoke(null, oldPeer, current) == updatedPeer,
                "selection and context bind to new telemetry while preserving the member's network role");
        require(network.invoke(null, oldNetwork, RadminBackend.RadminSnapshot.unavailable("disconnect")) == null,
                "removed networks cannot leave stale context actions");
        require(peer.invoke(null, oldPeer, RadminBackend.RadminSnapshot.unavailable("disconnect")) == null,
                "removed peers cannot leave stale context actions");
        require(peer.invoke(null, oldPeer, new RadminBackend.RadminSnapshot(RadminBackend.ServiceState.READY,
                "", "", List.of(new RadminBackend.Network("Other", "def", "", List.of(elsewhere))), "")) == elsewhere,
                "a member still present in another network remains selected");
        System.out.println("LIVE_SELECTION_REGRESSION_OK network=true peer=true context=true");
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
