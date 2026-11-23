package dev.radminlan.network;

import dev.radminlan.RadminLanClient;
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
    private static final System.Logger LOGGER = System.getLogger("RadminLAN Core");
    private final CoreSession session = new CoreSession(CoreSession.defaultProfile(), List.of(),
            message -> LOGGER.log(System.Logger.Level.DEBUG, message));
    private final TcpForwarder forwarder = new TcpForwarder(session);
    private volatile boolean closed;
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
                network.peers().stream().map(peer -> new Peer(peer.name(), peer.address(), "",
                        peer.connected() ? "online" : "unknown", peer.latencyMs(), peer.connected(), peer.id(), "", "",
                        network.name(), peer.role() == null ? null : peer.role().longValue(), "")).toList(),
                null, network.enabled(), network.role() == null ? null : network.role() == 2,
                null, null, false)).toList();
        String detail = status.detail();
        if (detail.isBlank() && state == ServiceState.UNAVAILABLE && !closed) detail = "screen.radminlan.core.connecting";
        if (detail.isBlank() && state == ServiceState.DISABLED) detail = "screen.radminlan.core.offline";
        viewIdentity = current;
        statusIdentity = status;
        return cached = new RadminSnapshot(state, "", status.vip(), networks, detail, ready, "RadminLAN Core", !off);
    }

    @Override public boolean supportsAliasChanges() { return false; }
    @Override public boolean supportsMemberRemoval() { return supportsWrite(); }
    @Override public boolean supportsWrite() { return !closed && session.view().status().phase().equals("CONNECTED"); }
    @Override public CompletableFuture<Boolean> setPower(boolean enabled) {
        if (closed) return CompletableFuture.completedFuture(false);
        if (enabled) return CompletableFuture.completedFuture(session.start() || supportsWrite());
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
        return RadminLanClient.BACKEND instanceof EmbeddedCoreBackend && CoreSession.virtualAddress(host) != null;
    }

    /** Netty hands the full Minecraft stream through a one-use loopback lease. */
    public static ChannelFuture connect(InetSocketAddress destination, Function<InetSocketAddress, ChannelFuture> original) {
        if (!(RadminLanClient.BACKEND instanceof EmbeddedCoreBackend core) || destination == null) return original.apply(destination);
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
