package dev.radminlan.network;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Service-reported online state and ping received over a read-only pipe subscription. */
public final class RadminPeerTelemetry {
    private static final long MAX_SAMPLE_AGE = TimeUnit.SECONDS.toNanos(60);
    private static final long OLDEST_SNAPSHOT = Long.MIN_VALUE;
    private final Map<Long, StateSample> states = new HashMap<>();
    private final Map<Long, LatencySample> samples = new HashMap<>();
    private final LongSupplier nanoTime;
    private RadminBackend.RadminSnapshot ingestedSnapshot;
    private long ingestedSnapshotStartedAt = OLDEST_SNAPSHOT;
    private long latestSnapshotStartedAt = OLDEST_SNAPSHOT;
    private long latestBatchStartedAt = OLDEST_SNAPSHOT;
    private boolean hasSnapshot;
    private boolean cleared;
    private long clearedAt;
    private long revision;

    public RadminPeerTelemetry() { this(System::nanoTime); }

    /** The clock is injectable so expiry and races can be checked without sleeping. */
    public RadminPeerTelemetry(LongSupplier nanoTime) { this.nanoTime = Objects.requireNonNull(nanoTime); }

    public synchronized void accept(byte[] framedBytes) {
        expireSamples(nanoTime.getAsLong());
        for (byte[] frame : RadminWire.splitFrames(framedBytes)) {
            Map<Long, Long> attributes = new HashMap<>();
            Map<Long, Long> latencies = new HashMap<>();
            RadminSnapshotParser.collectTelemetry(RadminWire.parseFrame(frame), attributes, latencies);
            long now = nanoTime.getAsLong();
            applyTelemetry(attributes, latencies, now, now, now, true);
        }
    }

    /** Preserves the difference between a missing ping field and an explicit unknown query sample. */
    public synchronized void acceptSnapshot(byte[] framedBytes, long snapshotStartedAtNanos) {
        long now = nanoTime.getAsLong();
        expireSamples(now);
        if (staleSnapshot(snapshotStartedAtNanos)) return;
        Map<Long, Long> attributes = new HashMap<>();
        Map<Long, Long> latencies = new HashMap<>();
        for (byte[] frame : RadminWire.splitFrames(framedBytes)) {
            RadminSnapshotParser.collectTelemetry(RadminWire.parseFrame(frame), attributes, latencies);
        }
        latestBatchStartedAt = snapshotStartedAtNanos;
        long receivedAt = snapshotStartedAtNanos == OLDEST_SNAPSHOT ? now : snapshotStartedAtNanos;
        applyTelemetry(attributes, latencies, snapshotStartedAtNanos, receivedAt, now, false);
    }

    /** Compatibility overload: timestamped subscription events take priority over this baseline. */
    public synchronized RadminBackend.RadminSnapshot enrich(RadminBackend.RadminSnapshot snapshot) {
        return enrich(snapshot, OLDEST_SNAPSHOT);
    }

    /** The query start time prevents an older subscription snapshot replacing a newer full query. */
    public synchronized RadminBackend.RadminSnapshot enrich(RadminBackend.RadminSnapshot snapshot,
                                                            long snapshotStartedAtNanos) {
        long now = nanoTime.getAsLong();
        expireSamples(now);
        ingestSnapshot(snapshot, snapshotStartedAtNanos, now);
        List<RadminBackend.Network> networks = snapshot.networks().stream().map(network -> {
            List<RadminBackend.Peer> peers = network.peers().stream().map(peer -> {
                StateSample state = states.get(peer.nodeId());
                Boolean online = state == null ? null : state.online;
                LatencySample sample = samples.get(peer.nodeId());
                long latency = Boolean.TRUE.equals(online) && sample != null ? sample.latency : -1;
                return withTelemetry(peer, online, latency);
            }).toList();
            return new RadminBackend.Network(network.name(), network.guid(), network.owner(), peers,
                    network.networkType(), network.enabled(), network.admin(), network.allowPasswordless(),
                    network.blockNewMembers(), network.publicNetwork());
        }).toList();
        return new RadminBackend.RadminSnapshot(snapshot.state(), snapshot.localName(), snapshot.virtualIp(),
                networks, snapshot.message(), snapshot.canWrite(), snapshot.transport(), snapshot.power());
    }

    /** Includes expiry so a caller caching enriched snapshots can notice a sample becoming stale. */
    public synchronized long revision() {
        expireSamples(nanoTime.getAsLong());
        return revision;
    }

    public synchronized void clear() {
        states.clear();
        samples.clear();
        cleared = true;
        clearedAt = nanoTime.getAsLong();
        ingestedSnapshot = null;
        revision++;
    }

    private void ingestSnapshot(RadminBackend.RadminSnapshot snapshot, long startedAt, long now) {
        if (snapshot == ingestedSnapshot && startedAt == ingestedSnapshotStartedAt) return;
        if (staleSnapshot(startedAt)) return;
        if (hasSnapshot && (startedAt < latestSnapshotStartedAt
                || startedAt == latestSnapshotStartedAt && startedAt != OLDEST_SNAPSHOT)) return;
        ingestedSnapshot = snapshot;
        ingestedSnapshotStartedAt = startedAt;
        latestSnapshotStartedAt = startedAt;
        hasSnapshot = true;
        Set<Long> seen = new HashSet<>();
        for (RadminBackend.Network network : snapshot.networks()) {
            for (RadminBackend.Peer peer : network.peers()) {
                if (!seen.add(peer.nodeId())) continue;
                Boolean online = peer.online() ? Boolean.TRUE : "offline".equals(peer.state()) ? Boolean.FALSE : null;
                putState(peer.nodeId(), online, startedAt, false);
                StateSample current = states.get(peer.nodeId());
                if (peer.online() && peer.latencyMs() >= 0 && current != null && Boolean.TRUE.equals(current.online)) {
                    long receivedAt = startedAt == OLDEST_SNAPSHOT ? now : startedAt;
                    long latency = now - receivedAt >= MAX_SAMPLE_AGE ? -1 : peer.latencyMs();
                    putLatency(peer.nodeId(), latency, startedAt, receivedAt, false);
                }
            }
        }
    }

    private boolean staleSnapshot(long startedAt) {
        return cleared && (startedAt == OLDEST_SNAPSHOT || startedAt <= clearedAt)
                || startedAt < latestSnapshotStartedAt || startedAt < latestBatchStartedAt;
    }

    private void applyTelemetry(Map<Long, Long> attributes, Map<Long, Long> latencies,
                                long observedAt, long receivedAt, long now, boolean subscription) {
        attributes.forEach((nodeId, flags) -> putState(nodeId,
                flags < 0 ? null : (flags & 1) != 0, observedAt, subscription));
        latencies.forEach((nodeId, latency) -> {
            StateSample state = states.get(nodeId);
            // A ping does not imply online: queued pings may follow an offline notification.
            long value = state != null && !Boolean.TRUE.equals(state.online)
                    || now - receivedAt >= MAX_SAMPLE_AGE ? -1 : latency;
            putLatency(nodeId, value, observedAt, receivedAt, subscription);
        });
    }

    private void putState(long nodeId, Boolean online, long observedAt, boolean subscription) {
        StateSample previous = states.get(nodeId);
        if (previous != null && (observedAt < previous.observedAt
                || observedAt == previous.observedAt && !subscription && observedAt != OLDEST_SNAPSHOT)) return;
        states.put(nodeId, new StateSample(online, observedAt));
        revision++;
        if (!Boolean.TRUE.equals(online)) {
            LatencySample sample = samples.get(nodeId);
            // Retain invalidation as a tombstone; deleting would let an old baseline revive its ping.
            long invalidatedAt = sample == null ? observedAt : Math.max(observedAt, sample.observedAt);
            samples.put(nodeId, new LatencySample(-1, invalidatedAt, invalidatedAt));
            revision++;
        }
    }

    private void putLatency(long nodeId, long latency, long observedAt, long receivedAt, boolean subscription) {
        LatencySample previous = samples.get(nodeId);
        if (previous != null && (observedAt < previous.observedAt
                || observedAt == previous.observedAt && !subscription)) return;
        samples.put(nodeId, new LatencySample(latency, observedAt, receivedAt));
        revision++;
    }

    private void expireSamples(long now) {
        samples.replaceAll((nodeId, sample) -> {
            if (sample.latency < 0 || now - sample.receivedAt < MAX_SAMPLE_AGE) return sample;
            revision++;
            return new LatencySample(-1, sample.observedAt, sample.receivedAt);
        });
    }

    private static RadminBackend.Peer withTelemetry(RadminBackend.Peer peer, Boolean online, long latency) {
        String state = online == null ? "unknown" : online ? "online" : "offline";
        return new RadminBackend.Peer(peer.name(), peer.virtualIp(), peer.virtualIpv6(), state, latency,
                Boolean.TRUE.equals(online), peer.nodeId(), peer.publicIp(), peer.connectionType(), peer.networkName(),
                peer.role(), peer.version());
    }

    private record StateSample(Boolean online, long observedAt) {}
    private record LatencySample(long latency, long observedAt, long receivedAt) {}
}
