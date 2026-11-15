import dev.radminlan.RadminLanClient;
import dev.radminlan.compat.LanWorldsProvider;
import dev.radminlan.network.RadminBackend;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Exercises the public optional bridge without loading LanWorlds or opening a screen. */
public final class LanWorldsProviderRegression {
    private static final long NODE_ID = 9_007_199_254_740_993L;
    private static final String IPV4 = "26.10.20.30";
    private static final String IPV6 = "FD00:1234:ABCD::30";

    public static void check() throws Exception {
        RadminBackend.RadminSnapshot originalSnapshot = RadminLanClient.cachedSnapshot();
        Language originalLanguage = Language.getInstance();
        try {
            for (String locale : List.of("en_us", "zh_cn")) {
                Map<String, String> translations = loadTranslations(locale);
                Language.inject(withTranslations(translations, originalLanguage));
                checkProvider(translations, locale);
            }
        } finally {
            RadminLanClient.publishSnapshot(originalSnapshot);
            Language.inject(originalLanguage);
        }
        System.out.println("LANWORLDS_PROVIDER_REGRESSION_OK locales=2 ipv4=true ipv6=true optional=true");
    }

    private static void checkProvider(Map<String, String> translations, String locale) {
        LanWorldsProvider provider = new LanWorldsProvider();
        RadminBackend.Peer host = peer("Host", IPV4, IPV6, "online", 37, true, NODE_ID, "Alpha");
        // Different virtual addresses prove that membership is merged by node ID.
        RadminBackend.Peer sameNode = peer("Host", "26.10.20.31", "fd00:1234:abcd::31", "online", 37, true, NODE_ID, "Beta");
        RadminBackend.Peer offline = peer("Offline", "26.10.20.40", "", "offline", -1, false, 42, "Alpha");
        RadminBackend.Peer unknown = peer("Unknown", "26.10.20.41", "", "connecting", -1, false, 43, "Alpha");
        List<RadminBackend.Network> networks = List.of(
                new RadminBackend.Network("Alpha", "network-alpha", "", List.of(host, offline, unknown)),
                new RadminBackend.Network("Beta", "network-beta", "", List.of(sameNode)));

        RadminLanClient.publishSnapshot(snapshot(RadminBackend.ServiceState.READY, networks));
        Map<String, Object> info = provider.apply(IPV4);
        require("Host".equals(info.get("name")), "READY snapshot exposes matching host: " + locale);
        require(IPV4.equals(info.get("virtualIp")) && IPV6.equals(info.get("virtualIpv6")),
                "Both virtual addresses survive the bridge");
        require(info.get("nodeId") instanceof String && Long.toUnsignedString(NODE_ID).equals(info.get("nodeId")),
                "Node ID is a precise decimal String above the JavaScript integer range");
        require(info.get("latencyMs") instanceof Long && Long.valueOf(37).equals(info.get("latencyMs")),
                "Latency is exposed as Long milliseconds");
        require("online".equals(info.get("state")), "Online state uses the public bridge vocabulary");
        require("198.51.100.24".equals(info.get("publicIp")) && "Direct".equals(info.get("connectionType"))
                        && "1.4.4642".equals(info.get("version")),
                "Optional peer information survives the bridge");
        require("Alpha, Beta".equals(info.get("networks")), "Same node combines every network membership");
        require(provider.apply("  " + IPV4 + "  ") == info,
                "Equivalent IPv4 queries reuse the current immutable result");
        Map<String, Object> ipv6 = provider.apply("  [fd00:1234:abcd::30]  ");
        require(info.get("nodeId").equals(ipv6.get("nodeId")), "Bracketed IPv6 matches without case sensitivity");
        require(provider.apply("FD00:1234:ABCD::30") == ipv6, "Equivalent IPv6 queries share one cached result");
        require(info.get("networks").equals(provider.apply("26.10.20.31").get("networks")),
                "Additional virtual addresses resolve the merged node membership");
        require("offline".equals(provider.apply("26.10.20.40").get("state")), "Known offline state stays distinct");
        require("unknown".equals(provider.apply("26.10.20.41").get("state")), "Unresolved connection state is unknown");
        require(provider.apply("192.168.1.25").isEmpty(), "An ordinary LAN room does not match a VPN peer");
        require(provider.apply("198.51.100.24").isEmpty(), "Public IP is not treated as a virtual host match");
        require(provider.apply(null).isEmpty() && provider.apply(" \t ").isEmpty(), "Missing host is safe");

        List<?> actions = (List<?>) info.get("actions");
        require(actions.size() == 3, "One peer action and two distinct network actions are exposed");
        String peerLabel = translations.get("screen.radminlan.peer_properties");
        String networkLabel = translations.get("screen.radminlan.network_properties");
        require(peerLabel != null && !peerLabel.isBlank() && networkLabel != null && !networkLabel.isBlank(),
                "Both action translations exist in the actual " + locale + " resource");
        require(peerLabel.equals(action(actions, 0).get("label")), "User action uses its translated label");
        require((networkLabel + ": Alpha").equals(action(actions, 1).get("label"))
                        && (networkLabel + ": Beta").equals(action(actions, 2).get("label")),
                "Network labels are translated and identify each network");
        for (int index = 0; index < actions.size(); index++) {
            Map<?, ?> action = action(actions, index);
            require(action.get("open") instanceof Consumer<?>, "Property action exposes the Consumer contract");
            require(action.get("label") instanceof String label && !label.isBlank()
                            && !label.contains("screen.radminlan."),
                    "No translation key leaks into an action label");
            expectImmutable(action::clear, "Action values are immutable");
        }
        expectImmutable(() -> info.put("name", "changed"), "Peer values are immutable");
        expectImmutable(actions::clear, "Action list is immutable");

        for (RadminBackend.ServiceState state : RadminBackend.ServiceState.values()) {
            if (state == RadminBackend.ServiceState.READY) continue;
            // Retain networks deliberately: readiness must gate all peer information.
            RadminLanClient.publishSnapshot(snapshot(state, networks));
            require(provider.apply(IPV4).isEmpty() && provider.apply(IPV6).isEmpty(),
                    "Non-READY state does not expose a previous peer: " + state);
        }
        RadminLanClient.publishSnapshot(snapshot(RadminBackend.ServiceState.READY, List.of()));
        require(provider.apply(IPV4).isEmpty(), "A new READY snapshot removes vanished peer matches");
        RadminBackend.Peer updated = peer("Host updated", IPV4, IPV6, "online", 81, true, NODE_ID, "Alpha");
        RadminLanClient.publishSnapshot(snapshot(RadminBackend.ServiceState.READY,
                List.of(new RadminBackend.Network("Alpha", "network-alpha", "", List.of(updated)))));
        Map<String, Object> refreshed = provider.apply(IPV4);
        require("Host updated".equals(refreshed.get("name")) && Long.valueOf(81).equals(refreshed.get("latencyMs")),
                "A subsequent snapshot replaces both positive and negative lookup caches");
        require(((List<?>) refreshed.get("actions")).size() == 2, "Removed network actions disappear after refresh");
    }

    private static Map<?, ?> action(List<?> actions, int index) {
        require(actions.get(index) instanceof Map<?, ?>, "Each property action is a map");
        return (Map<?, ?>) actions.get(index);
    }

    private static RadminBackend.RadminSnapshot snapshot(RadminBackend.ServiceState state, List<RadminBackend.Network> networks) {
        return new RadminBackend.RadminSnapshot(state, "Local", "26.10.20.1", networks, "test");
    }

    private static RadminBackend.Peer peer(String name, String ipv4, String ipv6, String state,
                                           long latency, boolean online, long nodeId, String network) {
        return new RadminBackend.Peer(name, ipv4, ipv6, state, latency, online, nodeId,
                "198.51.100.24", "Direct", network, 1L, "1.4.4642");
    }

    private static Map<String, String> loadTranslations(String locale) throws Exception {
        Map<String, String> values = new HashMap<>();
        try (InputStream stream = LanWorldsProviderRegression.class.getResourceAsStream("/assets/radminlan/lang/" + locale + ".json")) {
            require(stream != null, "Packaged language resource exists: " + locale);
            Language.loadFromJson(stream, values::put);
        }
        return Map.copyOf(values);
    }

    private static Language withTranslations(Map<String, String> translations, Language fallback) {
        return new Language() {
            @Override public String getOrDefault(String key, String defaultValue) {
                return translations.getOrDefault(key, fallback.getOrDefault(key, defaultValue));
            }
            @Override public boolean has(String key) { return translations.containsKey(key) || fallback.has(key); }
            @Override public boolean isDefaultRightToLeft() { return fallback.isDefaultRightToLeft(); }
            @Override public FormattedCharSequence getVisualOrder(FormattedText text) { return fallback.getVisualOrder(text); }
        };
    }

    private static void expectImmutable(Runnable mutation, String message) {
        try {
            mutation.run();
            throw new AssertionError(message);
        } catch (UnsupportedOperationException expected) {
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
