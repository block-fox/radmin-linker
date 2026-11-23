package dev.radminlinker.compat;

import dev.radminlinker.RadminLinkerClient;
import dev.radminlinker.network.RadminBackend;
import dev.radminlinker.ui.NetworkPropertiesScreen;
import dev.radminlinker.ui.PeerDetailsScreen;
import dev.radminlinker.ui.RadminScreen;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Optional room integration using JDK values, without a dependency on LanWorlds. */
public final class LanWorldsProvider implements Function<String, Map<String, Object>> {
    private static final String SHARE_KEY = "radminlinker:room-provider-v1";

    private static final String MANAGEMENT_KEY = "radminlinker:open-management-v1";

    private RadminBackend.RadminSnapshot cachedSnapshot;
    private Map<String, Member> membersByHost = Map.of();
    private final Map<String, Map<String, Object>> cachedLookups = new HashMap<>();

    public static void register() {
        var share = FabricLoader.getInstance().getObjectShare();
        share.put(SHARE_KEY, new LanWorldsProvider());
        share.put("radminlinker:lan-rooms-v1", (java.util.function.Supplier<List<Map<String, Object>>>) () ->
                RadminLinkerClient.BACKEND.lanRooms().stream().map(room -> Map.<String, Object>of(
                        "motd", room.motd(), "host", room.host(), "port", room.port())).toList());
        share.put("radminlinker:status-route-v1", (Function<java.net.InetSocketAddress,
                Map.Entry<java.net.InetSocketAddress, AutoCloseable>>) RadminLinkerClient.BACKEND::routeStatus);
        share.put(MANAGEMENT_KEY, (Consumer<Screen>) parent -> Minecraft.getInstance().setScreen(new RadminScreen(parent)));
    }

    public static void unregister() {
        var share = FabricLoader.getInstance().getObjectShare();
        share.remove(SHARE_KEY);
        share.remove("radminlinker:lan-rooms-v1");
        share.remove("radminlinker:status-route-v1");
        share.remove(MANAGEMENT_KEY);
    }

    @Override
    public synchronized Map<String, Object> apply(String host) {
        RadminBackend.RadminSnapshot snapshot = RadminLinkerClient.cachedSnapshot();
        if (snapshot != cachedSnapshot) rebuildIndex(snapshot);
        String address = normalizeHost(host);
        if (address.isEmpty()) return Map.of();
        return cachedLookups.computeIfAbsent(address, key -> {
            Member member = membersByHost.get(key);
            return member == null ? Map.of() : describe(member);
        });
    }

    private void rebuildIndex(RadminBackend.RadminSnapshot snapshot) {
        cachedSnapshot = snapshot;
        cachedLookups.clear();
        if (snapshot.state() != RadminBackend.ServiceState.READY) {
            membersByHost = Map.of();
            return;
        }
        Map<String, Member> hosts = new HashMap<>();
        Map<Long, Member> nodes = new HashMap<>();
        for (RadminBackend.Network network : snapshot.networks()) {
            for (RadminBackend.Peer peer : network.peers()) {
                String ipv4 = normalizeHost(peer.virtualIp());
                String ipv6 = normalizeHost(peer.virtualIpv6());
                Member member = peer.nodeId() >= 0 ? nodes.get(peer.nodeId()) : null;
                if (member == null && !ipv4.isEmpty()) member = hosts.get(ipv4);
                if (member == null && !ipv6.isEmpty()) member = hosts.get(ipv6);
                if (member == null) member = new Member(peer);
                if (peer.nodeId() >= 0) nodes.putIfAbsent(peer.nodeId(), member);
                if (!ipv4.isEmpty()) hosts.putIfAbsent(ipv4, member);
                if (!ipv6.isEmpty()) hosts.putIfAbsent(ipv6, member);
                String networkKey = safe(network.guid()).isBlank() ? safe(network.name()) : network.guid();
                member.networks.putIfAbsent(networkKey, network);
            }
        }
        membersByHost = Map.copyOf(hosts);
    }

    private static Map<String, Object> describe(Member member) {
        RadminBackend.Peer peer = member.peer;
        List<Map<String, Object>> actions = new ArrayList<>();
        actions.add(Map.of(
                "label", Component.translatable("screen.radminlinker.peer_properties").getString(),
                "open", (Consumer<Screen>) parent -> Minecraft.getInstance().setScreen(new PeerDetailsScreen(parent, peer))));
        for (RadminBackend.Network network : member.networks.values()) {
            actions.add(Map.of(
                    "label", Component.translatable("screen.radminlinker.network_properties").getString() + ": " + safe(network.name()),
                    "open", (Consumer<Screen>) parent -> Minecraft.getInstance().setScreen(new NetworkPropertiesScreen(parent, network))));
        }
        String networks = member.networks.values().stream().map(network -> safe(network.name()))
                .filter(name -> !name.isBlank()).distinct().reduce((left, right) -> left + ", " + right)
                .orElse(safe(peer.networkName()));
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", safe(peer.name()));
        values.put("virtualIp", safe(peer.virtualIp()));
        values.put("virtualIpv6", safe(peer.virtualIpv6()));
        values.put("nodeId", peer.nodeId() < 0 ? "" : Long.toUnsignedString(peer.nodeId()));
        values.put("publicIp", safe(peer.publicIp()));
        values.put("connectionType", safe(peer.connectionType()));
        values.put("version", safe(peer.version()));
        values.put("state", peer.online() ? "online" : "offline".equalsIgnoreCase(peer.state()) ? "offline" : "unknown");
        values.put("latencyMs", peer.latencyMs());
        values.put("networks", networks);
        values.put("actions", List.copyOf(actions));
        return Map.copyOf(values);
    }

    private static String normalizeHost(String host) {
        String value = safe(host).trim();
        if (value.startsWith("[") && value.endsWith("]")) value = value.substring(1, value.length() - 1);
        value = value.toLowerCase(Locale.ROOT);
        if (value.indexOf(':') < 0) return value;
        int scope = value.indexOf('%');
        String suffix = scope < 0 ? "" : value.substring(scope);
        String address = normalizeIpv6(scope < 0 ? value : value.substring(0, scope));
        return address.isEmpty() ? "" : address + suffix;
    }

    /** Canonicalizes only numeric literals, without resolving names or contacting DNS. */
    private static String normalizeIpv6(String value) {
        if (value.indexOf('.') >= 0) {
            int separator = value.lastIndexOf(':');
            String[] octets = value.substring(separator + 1).split("\\.", -1);
            if (octets.length != 4) return "";
            int ipv4 = 0;
            for (String octet : octets) {
                if (octet.isEmpty() || octet.length() > 3) return "";
                int number = 0;
                for (int index = 0; index < octet.length(); index++) {
                    char digit = octet.charAt(index);
                    if (digit < '0' || digit > '9') return "";
                    number = number * 10 + digit - '0';
                }
                if (number > 255) return "";
                ipv4 = (ipv4 << 8) | number;
            }
            value = value.substring(0, separator + 1) + Integer.toHexString(ipv4 >>> 16)
                    + ":" + Integer.toHexString(ipv4 & 0xffff);
        }
        int compression = value.indexOf("::");
        if (compression >= 0 && value.indexOf("::", compression + 2) >= 0) return "";
        String before = compression < 0 ? value : value.substring(0, compression);
        String after = compression < 0 ? "" : value.substring(compression + 2);
        String[] left = before.isEmpty() ? new String[0] : before.split(":", -1);
        String[] right = after.isEmpty() ? new String[0] : after.split(":", -1);
        int count = left.length + right.length;
        if (compression < 0 ? count != 8 : count >= 8) return "";
        String[] groups = new String[8];
        Arrays.fill(groups, "0");
        System.arraycopy(left, 0, groups, 0, left.length);
        System.arraycopy(right, 0, groups, groups.length - right.length, right.length);
        for (int index = 0; index < groups.length; index++) {
            String group = groups[index];
            if (group.isEmpty() || group.length() > 4) return "";
            for (int character = 0; character < group.length(); character++) {
                char digit = group.charAt(character);
                if (!(digit >= '0' && digit <= '9' || digit >= 'a' && digit <= 'f')) return "";
            }
            groups[index] = Integer.toHexString(Integer.parseInt(group, 16));
        }
        return String.join(":", groups);
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static final class Member {
        private final RadminBackend.Peer peer;
        private final Map<String, RadminBackend.Network> networks = new LinkedHashMap<>();

        private Member(RadminBackend.Peer peer) { this.peer = peer; }
    }
}
