package dev.radminlinker.network;

import dev.radminlinker.RadminLinkerClient;
import dev.radminlan.core.CoreSession;
import dev.radminlan.core.TcpForwarder;
import dev.radminlan.core.CoreSession.Action;
import io.netty.channel.ChannelFuture;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Owns the in-process VPN and adapts its confirmed results to the existing UI. */
public final class EmbeddedCoreBackend implements RadminCoreProvider, RadminBackend {
    private static final int MAX_DEVICE_NAME_LENGTH = 64;
    private static final System.Logger LOGGER = System.getLogger("Radmin Linker Core");
    private final CoreSession session = new CoreSession(CoreSession.defaultProfile(), List.of(),
            message -> LOGGER.log(System.Logger.Level.DEBUG, message));
    private final TcpForwarder forwarder = new TcpForwarder(session);
    private final boolean android = isAndroid();
    private volatile boolean closed;
    private String coreName = "Radmin Linker";
    private boolean chinese;
    private Object viewIdentity;
    private Object statusIdentity;
    private RadminSnapshot cached;

    @Override public RadminBackend create() { return this; }
    @Override public CompletableFuture<RadminSnapshot> snapshot() { return CompletableFuture.completedFuture(latestSnapshot()); }

    @Override public synchronized RadminSnapshot latestSnapshot() {
        var current = session.view();
        var status = current.status();
        if (current == viewIdentity || current.networks().isEmpty() && status == statusIdentity
                && cached != null && cached.networks().isEmpty()) return cached;
        var phase = status.phase();
        boolean ready = phase.equals("CONNECTED") && !closed;
        boolean off = phase.equals("OFFLINE") || phase.equals("STOPPED") || phase.equals("FAILED") || closed;
        ServiceState state = closed ? ServiceState.UNAVAILABLE : ready ? ServiceState.READY
                : phase.equals("FAILED") ? ServiceState.ERROR : off ? ServiceState.DISABLED : ServiceState.UNAVAILABLE;
        var networks = current.networks().stream().map(network -> new Network(network.name(), network.id(), "",
                network.peers().stream().filter(peer -> !peer.local()).map(peer -> new Peer(peer.name(), peer.address(), "",
                        peer.online() ? "online" : peer.presenceKnown() ? "offline" : "unknown", peer.latencyMs(), peer.online(), peer.id(), "", peer.connectionType(),
                        network.name(), peer.role() == null ? null : peer.role().longValue(), "")).toList(),
                null, network.enabled(), network.role() == null ? null : network.role() == 2,
                null, null, false)).toList();
        String detail = status.detail();
        if (detail.isBlank() && state == ServiceState.UNAVAILABLE && !closed) detail = "screen.radminlinker.core.connecting";
        if (detail.isBlank() && state == ServiceState.DISABLED) detail = "screen.radminlinker.core.offline";
        viewIdentity = current;
        statusIdentity = status;
        return cached = new RadminSnapshot(state, coreName, status.vip(), networks, detail, ready, "Radmin Linker Core", !off);
    }

    @Override public void setPublishedRoom(int port, String motd) {
        try {
            session.setPublishedRoom(port, motd);
        } catch (IllegalArgumentException invalidMotd) {
            session.setPublishedRoom(port, "Minecraft LAN");
        }
    }

    @Override public List<LanRoom> lanRooms() {
        return session.lanRooms().stream().map(room -> new LanRoom(room.motd(), room.host(), room.port())).toList();
    }

    @Override public java.util.Map.Entry<InetSocketAddress, AutoCloseable> routeStatus(InetSocketAddress destination) {
        var address = CoreSession.virtualAddress(destination.getHostString());
        if (address == null) return RadminBackend.super.routeStatus(destination);
        try {
            var lease = forwarder.prepare(address, destination.getPort());
            return java.util.Map.entry(lease.endpoint(), lease);
        } catch (IOException error) {
            throw new java.io.UncheckedIOException(error);
        }
    }

    @Override public synchronized void prepareLocalIdentity(String playerName, boolean chinese) {
        var config = RadminLinkerClient.CONFIG;
        if (config == null) return;
        this.chinese = chinese;
        if (config.coreAlias == null || config.coreAlias.isBlank()) {
            config.coreAlias = playerName == null || playerName.isBlank() ? "Radmin Linker" : playerName;
            config.save();
        }
        refreshLocalIdentity();
    }

    @Override public synchronized void refreshLocalIdentity() {
        var config = RadminLinkerClient.CONFIG;
        if (!closed && config != null && config.coreAlias != null && !config.coreAlias.isBlank())
            applyAlias(config.coreAlias, config.coreAliasSuffix);
    }

    @Override public boolean aliasAppliesOnReconnect() { return true; }
    @Override public boolean supportsAliasChanges() { return !closed; }

    @Override public synchronized CompletableFuture<Boolean> setAlias(String alias) {
        if (closed || alias == null || alias.isBlank() || alias.length() > MAX_DEVICE_NAME_LENGTH
                || alias.codePoints().anyMatch(Character::isISOControl)) return CompletableFuture.completedFuture(false);
        var config = RadminLinkerClient.CONFIG;
        String name = alias.strip();
        try {
            applyAlias(name, config.coreAliasSuffix);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(false);
        }
        config.coreAlias = name;
        config.save();
        return CompletableFuture.completedFuture(true);
    }

    private void applyAlias(String alias, boolean includeSuffix) {
        String suffix = "";
        if (includeSuffix) {
            suffix = chinese ? (android ? " （安卓，通过radmin linker模组）" : " （通过radmin linker模组）")
                    : (android ? " (Android, via Radmin Linker mod)" : " (via Radmin Linker mod)");
        }
        int limit = MAX_DEVICE_NAME_LENGTH - suffix.length();
        if (alias.length() > limit && Character.isHighSurrogate(alias.charAt(limit - 1))) limit--;
        String advertisedAlias = alias.substring(0, Math.min(alias.length(), limit)).stripTrailing();
        session.setAdvertisedName(advertisedAlias + suffix);
        coreName = alias;
        viewIdentity = null;
        statusIdentity = null;
    }

    private static boolean isAndroid() {
        if (System.getProperty("os.name", "").equalsIgnoreCase("Android")
                || System.getProperty("java.runtime.name", "").equalsIgnoreCase("Android Runtime")) return true;
        // Android Minecraft launchers can run OpenJDK and report their OS as Linux.
        return java.nio.file.Files.isRegularFile(java.nio.file.Path.of("/system/bin/getprop"))
                && java.nio.file.Files.isRegularFile(java.nio.file.Path.of("/system/framework/framework.jar"));
    }
    @Override public boolean supportsMemberRemoval() { return supportsWrite(); }
    @Override public boolean supportsMemberRoleChanges() { return supportsWrite(); }
    @Override public CompletableFuture<Boolean> managePeer(String guid, long nodeId, String networkName, RadminBackend.MemberAction action) {
        if (nodeId < 0 || guid == null || action == null) return CompletableFuture.completedFuture(false);
        try {
            return session.manageMember(CoreSession.MemberAction.valueOf(action.name()), guid, nodeId);
        } catch (IllegalArgumentException invalid) {
            return CompletableFuture.completedFuture(false);
        }
    }
    @Override public boolean supportsWrite() { return !closed && session.view().status().phase().equals("CONNECTED"); }
    @Override public CompletableFuture<Boolean> setPower(boolean enabled) {
        if (closed) return CompletableFuture.completedFuture(false);
        if (enabled) {
            prepareLocalIdentity(coreName, chinese);
            return CompletableFuture.completedFuture(session.start() || supportsWrite());
        }
        forwarder.cancelAll();
        session.stop();
        return CompletableFuture.completedFuture(true);
    }
    @Override public CompletableFuture<Boolean> createNetwork(String name, String password) { return passwordAction(Action.CREATE, name, password); }
    @Override public CompletableFuture<Boolean> joinNetwork(String name, String password) { return passwordAction(Action.JOIN, name, password); }
    @Override public CompletableFuture<Boolean> leaveNetwork(String name) {
        if (name == null) return CompletableFuture.completedFuture(false);
        return session.view().networks().stream().filter(n -> n.name().equals(name)).findFirst()
                .map(n -> leaveNetworkByGuid(n.id())).orElseGet(() -> CompletableFuture.completedFuture(false));
    }
    @Override public CompletableFuture<Boolean> leaveNetworkByGuid(String guid) { return session.manage(Action.LEAVE, guid, null); }
    @Override public CompletableFuture<Boolean> deleteNetwork(String guid) { return session.manage(Action.DELETE, guid, null); }
    @Override public CompletableFuture<Boolean> kickPeer(String guid, long nodeId, String networkName) {
        return session.removeMember(guid, nodeId);
    }
    @Override public CompletableFuture<Boolean> setNetworkEnabled(String guid, boolean enabled) {
        return session.manage(enabled ? Action.ENABLE : Action.DISABLE, guid, null);
    }
    @Override public CompletableFuture<List<String>> publicNetworks(String query) {
        if (!session.catalogReady()) return CompletableFuture.failedFuture(new IOException("Public catalog unavailable"));
        String match = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        return CompletableFuture.completedFuture(session.publicNetworks().stream()
                .filter(name -> name.toLowerCase(Locale.ROOT).contains(match)).toList());
    }
    private CompletableFuture<Boolean> passwordAction(Action action, String name, String password) {
        char[] secret = password == null || password.isEmpty() ? null : password.toCharArray();
        try { return session.manage(action, name, secret); }
        finally { if (secret != null) Arrays.fill(secret, '\0'); }
    }

    public static boolean usesVirtualRoute(String host) {
        return RadminLinkerClient.BACKEND instanceof EmbeddedCoreBackend && CoreSession.virtualAddress(host) != null;
    }

    /** Netty hands the full Minecraft stream through a one-use loopback lease. */
    public static ChannelFuture connect(InetSocketAddress destination, Function<InetSocketAddress, ChannelFuture> original) {
        if (!(RadminLinkerClient.BACKEND instanceof EmbeddedCoreBackend core) || destination == null) return original.apply(destination);
        var address = destination.getAddress();
        var peer = address instanceof java.net.Inet4Address v4 && v4.getAddress()[0] == 26 ? v4
                : CoreSession.virtualAddress(destination.getHostString());
        if (peer == null) return original.apply(destination);
        TcpForwarder.Lease lease;
        try { lease = core.forwarder.prepare(peer, destination.getPort()); }
        catch (IOException error) {
            // Legacy status ping can run on the render thread. Close this failed
            // attempt through Netty without falling back to the remote address.
            return original.apply(new InetSocketAddress("127.0.0.1", 0));
        }
        try {
            var future = original.apply(lease.endpoint());
            future.channel().closeFuture().addListener(closed -> lease.close());
            future.addListener(result -> { if (!result.isSuccess()) lease.close(); });
            return future;
        } catch (RuntimeException | Error failure) {
            lease.close();
            throw failure;
        }
    }

    @Override public synchronized void close() {
        closed = true;
        viewIdentity = null;
        statusIdentity = null;
        forwarder.close();
        session.close();
    }
}
