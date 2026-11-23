import dev.radminlan.network.RadminBackend;
import dev.radminlan.network.RadminPeerTelemetry;
import dev.radminlan.network.RadminSnapshotParser;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.lang.reflect.Method;

class NetworkParsingRegression {
    public static void main(String[] args) { check(); }

    static void check() {
        byte[] initial = initialSnapshot();
        RadminBackend.RadminSnapshot baseline = parse(initial);
        require(peer(baseline, 100).online(), "online attribute bit is independent of connection direction 0x21=0");
        require(!peer(baseline, 101).online(), "offline attribute overrides connection direction 0x21=1");
        require("offline".equals(peer(baseline, 101).state()), "known offline status remains distinct from unknown");
        require("unknown".equals(peer(baseline, 102).state()), "missing node attributes are unknown");
        require(peer(baseline, 100).latencyMs() == -1, "profile field 0x3E is never presented as a ping");

        var updated = parse(join(initial, ping(100, 37), ping(100, 53), ping(101, 12), ping(103, 0)));
        require(peer(updated, 100).latencyMs() == 53, "latest type 28 / 0x4D ping is used");
        require(peer(updated, 101).latencyMs() == -1, "offline node does not display a ping");
        require(peer(updated, 103).latencyMs() == 0, "an explicit zero millisecond service sample is valid");
        require(Boolean.TRUE.equals(updated.networks().getFirst().admin()), "peer ping IDs never replace the local node ID");

        var offline = parse(join(initial, ping(100, 37), attributes(100, 0)));
        require(!peer(offline, 100).online() && peer(offline, 100).latencyMs() == -1,
                "incremental node attributes invalidate a previous online sample");

        var telemetry = new RadminPeerTelemetry();
        telemetry.accept(ping(100, 44));
        require(peer(telemetry.enrich(baseline), 100).latencyMs() == 44,
                "subscription ping survives a fresh snapshot without latency fields");
        telemetry.accept(ping(100, 0));
        require(peer(telemetry.enrich(baseline), 100).latencyMs() == 0, "subscription preserves a reported zero");
        telemetry.accept(ping(100, 0xFFFF_FFFFL));
        require(peer(telemetry.enrich(baseline), 100).latencyMs() == -1, "unknown ping sentinel clears a previous sample");
        telemetry.accept(ping(100, 28));
        telemetry.accept(attributes(100, 0));
        require(peer(telemetry.enrich(baseline), 100).latencyMs() == -1, "offline notification invalidates cached telemetry");
        telemetry.accept(ping(100, 28));
        telemetry.clear();
        require(peer(telemetry.enrich(baseline), 100).latencyMs() == -1, "disconnect clears measurements");
        require(peer(baseline, 100).latencyMs() == -1, "telemetry never mutates an existing snapshot");
        checkAttributeContexts();
        checkNotificationOrdering();
        checkSnapshotOrdering();
        checkQueryTelemetry();
        checkSampleExpiry();
        checkDisconnect();
        checkIpv4Validation();
        System.out.println("NETWORK_PARSING_REGRESSION_OK attributes=0xA0 latency=type28/0x4D");
    }

    private static void checkAttributeContexts() {
        byte[] ignored = ignoredAttributes(100, 0);
        var baseline = parse(join(initialSnapshot(), ping(100, 37)));
        var updated = parse(join(initialSnapshot(), ping(100, 37), ignored));
        require(peer(updated, 100).online() && peer(updated, 100).latencyMs() == 37,
                "CNodeIgnoreChanged A0=0 is not an offline notification");
        var unrelated = parse(join(initialSnapshot(), ping(100, 37),
                frame(scalar(0x01, 7), container(0x66, node(100), scalar(0xA0, 0))),
                frame(scalar(0x01, 8), container(0x1F, container(0x66, node(100), scalar(0xA0, 0))))));
        require(peer(unrelated, 100).online() && peer(unrelated, 100).latencyMs() == 37,
                "online changes require message 7 and the 131F/1366 container path");
        var clock = new TestClock();
        var telemetry = new RadminPeerTelemetry(clock);
        telemetry.enrich(baseline, clock.getAsLong());
        clock.advance(1);
        telemetry.accept(ignored);
        require(peer(telemetry.enrich(baseline, seconds(1)), 100).online()
                        && peer(telemetry.enrich(baseline, seconds(1)), 100).latencyMs() == 37,
                "ignore notifications do not change subscription online state or ping");
    }

    private static void checkNotificationOrdering() {
        var baseline = parse(join(initialSnapshot(), ping(100, 17)));
        for (byte[] batch : new byte[][]{
                join(ping(100, 44), attributes(100, 0)),
                join(attributes(100, 0), ping(100, 44))}) {
            var clock = new TestClock();
            var telemetry = new RadminPeerTelemetry(clock);
            telemetry.enrich(baseline, clock.getAsLong());
            clock.advance(1);
            telemetry.accept(batch);
            var offline = peer(telemetry.enrich(baseline, seconds(1)), 100);
            require(!offline.online() && "offline".equals(offline.state()) && offline.latencyMs() == -1,
                    "offline and ping frames retain their order within one native read");
            clock.advance(1);
            telemetry.accept(attributes(100, 1));
            var online = peer(telemetry.enrich(baseline, seconds(1)), 100);
            require(online.online() && online.latencyMs() == -1,
                    "returning online without a new sample cannot revive a previous ping");
            telemetry.accept(ping(100, 0));
            require(peer(telemetry.enrich(baseline, seconds(1)), 100).latencyMs() == 0,
                    "a fresh zero millisecond ping restores a measurement after reconnecting");
        }
        var clock = new TestClock();
        var telemetry = new RadminPeerTelemetry(clock);
        telemetry.enrich(baseline, clock.getAsLong());
        clock.advance(1);
        telemetry.accept(attributes(100, 0xFFFF_FFFFL));
        var unknown = peer(telemetry.enrich(baseline, seconds(1)), 100);
        require(!unknown.online() && "unknown".equals(unknown.state()) && unknown.latencyMs() == -1,
                "unknown attributes invalidate both online state and old baseline ping");
        clock.advance(1);
        telemetry.accept(join(attributes(100, 1), ping(100, 28), ping(100, 0xFFFF_FFFFL)));
        require(peer(telemetry.enrich(baseline, seconds(1)), 100).latencyMs() == -1,
                "unknown ping tombstone prevents a baseline sample returning on repaint");
        var parsed = parse(join(initialSnapshot(), ping(100, 17), attributes(100, 0),
                ping(100, 44), attributes(100, 1)));
        require(peer(parsed, 100).online() && peer(parsed, 100).latencyMs() == -1,
                "full snapshot parsing also ignores a queued ping received while offline");
    }

    private static void checkSnapshotOrdering() {
        var online = parse(initialSnapshot());
        var offline = parse(join(initialSnapshot(), attributes(100, 0)));
        var sampled = parse(join(initialSnapshot(), ping(100, 17)));
        var clock = new TestClock();
        var telemetry = new RadminPeerTelemetry(clock);
        telemetry.accept(initialSnapshot());
        clock.advance(1);
        require(!peer(telemetry.enrich(offline, clock.getAsLong()), 100).online(),
                "a newer full query overrides an earlier subscription's initial profiles");
        clock.advance(1);
        telemetry.accept(attributes(100, 0));
        clock.advance(1);
        var fresh = peer(telemetry.enrich(online, clock.getAsLong()), 100);
        require(fresh.online() && fresh.latencyMs() == -1,
                "a newer online query replaces an older offline notification without reviving ping");

        clock = new TestClock();
        telemetry = new RadminPeerTelemetry(clock);
        long queryStarted = clock.getAsLong();
        clock.advance(1);
        telemetry.accept(join(attributes(100, 1), ping(100, 44)));
        fresh = peer(telemetry.enrich(offline, queryStarted), 100);
        require(fresh.online() && fresh.latencyMs() == 44,
                "subscription online and ping arriving during a query override the older query result");

        clock = new TestClock();
        telemetry = new RadminPeerTelemetry(clock);
        queryStarted = clock.getAsLong();
        clock.advance(1);
        telemetry.accept(ping(100, 53));
        require(peer(telemetry.enrich(sampled, queryStarted), 100).latencyMs() == 53,
                "a newer subscription ping overrides the full query's sample");
        clock.advance(1);
        require(peer(telemetry.enrich(online, clock.getAsLong()), 100).latencyMs() == 53,
                "a recent older sample fills a newer query that has no ping field");
        telemetry.enrich(sampled, queryStarted);
        require(peer(telemetry.enrich(online, clock.getAsLong()), 100).latencyMs() == 53,
                "replaying an older baseline does not replace a recent ping");

        clock = new TestClock();
        telemetry = new RadminPeerTelemetry(clock);
        queryStarted = clock.getAsLong();
        clock.advance(1);
        telemetry.accept(attributes(100, 0));
        require(!peer(telemetry.enrich(sampled, queryStarted), 100).online(),
                "an offline notification arriving during a query overrides its online result");
    }

    private static void checkQueryTelemetry() {
        byte[] sampledRaw = join(initialSnapshot(), ping(100, 41));
        byte[] unknownRaw = join(initialSnapshot(), ping(100, 0xFFFF_FFFFL));
        var sampled = parse(sampledRaw);
        var missing = parse(initialSnapshot());
        var unknown = parse(unknownRaw);
        var clock = new TestClock();
        var telemetry = new RadminPeerTelemetry(clock);
        telemetry.enrich(sampled, clock.getAsLong());
        clock.advance(1);
        long missingStarted = clock.getAsLong();
        telemetry.acceptSnapshot(initialSnapshot(), missingStarted);
        require(peer(telemetry.enrich(missing, missingStarted), 100).latencyMs() == 41,
                "an online query without a ping field retains a recent cached sample");
        clock.advance(1);
        long unknownStarted = clock.getAsLong();
        telemetry.acceptSnapshot(unknownRaw, unknownStarted);
        require(peer(telemetry.enrich(unknown, unknownStarted), 100).latencyMs() == -1,
                "an explicit unknown query ping invalidates a previous cached sample");
        require(peer(telemetry.enrich(sampled, seconds(1)), 100).latencyMs() == -1,
                "an older sampled baseline cannot undo the query's explicit unknown ping");
        clock.advance(1);
        telemetry.acceptSnapshot(initialSnapshot(), clock.getAsLong());
        require(peer(telemetry.enrich(missing, clock.getAsLong()), 100).latencyMs() == -1,
                "a later query with no ping does not revive an invalidated sample");
        clock.advance(1);
        byte[] zeroRaw = join(initialSnapshot(), ping(100, 0));
        long zeroStarted = clock.getAsLong();
        telemetry.acceptSnapshot(zeroRaw, zeroStarted);
        require(peer(telemetry.enrich(parse(zeroRaw), zeroStarted), 100).latencyMs() == 0,
                "a newer query can replace the tombstone with an explicit zero sample");
        telemetry.acceptSnapshot(unknownRaw, unknownStarted);
        require(peer(telemetry.enrich(parse(zeroRaw), zeroStarted), 100).latencyMs() == 0,
                "an older query batch cannot erase a newer query's measurement");

        clock = new TestClock();
        telemetry = new RadminPeerTelemetry(clock);
        telemetry.enrich(sampled, clock.getAsLong());
        clock.advance(1);
        long queryStarted = clock.getAsLong();
        clock.advance(1);
        telemetry.accept(ping(100, 53));
        telemetry.acceptSnapshot(unknownRaw, queryStarted);
        require(peer(telemetry.enrich(unknown, queryStarted), 100).latencyMs() == 53,
                "a newer subscription ping wins over an explicit unknown in an older query");
        clock.advance(1);
        queryStarted = clock.getAsLong();
        clock.advance(1);
        telemetry.accept(ping(100, 0xFFFF_FFFFL));
        telemetry.acceptSnapshot(sampledRaw, queryStarted);
        require(peer(telemetry.enrich(sampled, queryStarted), 100).latencyMs() == -1,
                "a newer subscription unknown wins over a positive sample in an older query");

        clock.advance(1);
        queryStarted = clock.getAsLong();
        clock.advance(1);
        telemetry.accept(join(attributes(100, 1), ping(100, 36)));
        byte[] offlineRaw = join(initialSnapshot(), attributes(100, 0));
        telemetry.acceptSnapshot(offlineRaw, queryStarted);
        var current = peer(telemetry.enrich(parse(offlineRaw), queryStarted), 100);
        require(current.online() && current.latencyMs() == 36,
                "a newer subscription online state and sample survive an older offline query");

        for (byte[] batch : new byte[][]{
                join(initialSnapshot(), ping(100, 17), attributes(100, 0), attributes(100, 1)),
                join(initialSnapshot(), attributes(100, 0), ping(100, 17), attributes(100, 1)),
                join(initialSnapshot(), ping(100, 17), ping(100, 0xFFFF_FFFFL))}) {
            clock = new TestClock();
            telemetry = new RadminPeerTelemetry(clock);
            telemetry.enrich(sampled, clock.getAsLong());
            clock.advance(1);
            queryStarted = clock.getAsLong();
            telemetry.acceptSnapshot(batch, queryStarted);
            current = peer(telemetry.enrich(parse(batch), queryStarted), 100);
            require(current.online() && current.latencyMs() == -1,
                    "query frame ordering retains offline and unknown invalidation before returning online");
        }

        clock = new TestClock();
        telemetry = new RadminPeerTelemetry(clock);
        clock.advance(61);
        telemetry.acceptSnapshot(sampledRaw, seconds(1));
        require(peer(telemetry.enrich(sampled, seconds(1)), 100).latencyMs() == -1,
                "a delayed query batch does not install a sample beyond its 60 second lifetime");
        clock.advance(1);
        telemetry.clear();
        telemetry.acceptSnapshot(sampledRaw, seconds(1));
        require("unknown".equals(peer(telemetry.enrich(sampled, seconds(1)), 100).state()),
                "a raw query batch started before disconnect cannot restore cleared state");
    }

    private static void checkSampleExpiry() {
        var baseline = parse(join(initialSnapshot(), ping(100, 41)));
        var clock = new TestClock();
        var telemetry = new RadminPeerTelemetry(clock);
        long started = clock.getAsLong();
        telemetry.enrich(baseline, started);
        long firstRevision = telemetry.revision();
        clock.advance(30);
        require(peer(telemetry.enrich(baseline, started), 100).latencyMs() == 41,
                "a baseline ping remains usable within its lifetime");
        require(telemetry.revision() == firstRevision, "repainting a baseline does not re-ingest its samples");
        clock.advance(30);
        long expiredRevision = telemetry.revision();
        require(expiredRevision > firstRevision, "expiry advances the revision before a cached view is reused");
        require(peer(telemetry.enrich(baseline, started), 100).latencyMs() == -1,
                "repainting a baseline cannot renew a ping's 60 second lifetime");
        require(telemetry.revision() == expiredRevision, "an expired tombstone does not repeatedly advance revision");

        clock = new TestClock();
        clock.advance(61);
        telemetry = new RadminPeerTelemetry(clock);
        require(peer(telemetry.enrich(baseline, seconds(1)), 100).latencyMs() == -1,
                "a never-before-seen baseline already older than 60 seconds is immediately stale");

        clock = new TestClock();
        telemetry = new RadminPeerTelemetry(clock);
        telemetry.enrich(baseline);
        clock.advance(61);
        require(peer(telemetry.enrich(baseline), 100).latencyMs() == -1,
                "the compatibility overload does not renew retained baseline pings either");
    }

    private static void checkDisconnect() {
        var baseline = parse(join(initialSnapshot(), ping(100, 41)));
        var clock = new TestClock();
        var telemetry = new RadminPeerTelemetry(clock);
        long started = clock.getAsLong();
        telemetry.enrich(baseline, started);
        long previousRevision = telemetry.revision();
        clock.advance(1);
        telemetry.clear();
        require(telemetry.revision() > previousRevision, "disconnect invalidates a cached enriched snapshot");
        var cleared = peer(telemetry.enrich(baseline, started), 100);
        require("unknown".equals(cleared.state()) && !cleared.online() && cleared.latencyMs() == -1,
                "a retained snapshot cannot restore state or ping after disconnect");
        clock.advance(1);
        telemetry.accept(join(attributes(100, 1), ping(100, 36)));
        var recovered = peer(telemetry.enrich(baseline, started), 100);
        require(recovered.online() && recovered.latencyMs() == 36,
                "fresh subscription state and ping can recover after disconnect");
        clock.advance(1);
        telemetry.clear();
        clock.advance(1);
        recovered = peer(telemetry.enrich(baseline, clock.getAsLong()), 100);
        require(recovered.online() && recovered.latencyMs() == 41,
                "a full query started after disconnect can establish a fresh baseline");
    }

    private static long seconds(long value) { return TimeUnit.SECONDS.toNanos(value); }

    private static final class TestClock implements LongSupplier {
        private long now = seconds(1);
        @Override public long getAsLong() { return now; }
        void advance(long seconds) { now += NetworkParsingRegression.seconds(seconds); }
    }

    private static void checkIpv4Validation() {
        try {
            Method validate = RadminSnapshotParser.class.getDeclaredMethod("validIpv4", String.class);
            validate.setAccessible(true);
            for (String invalid : new String[]{null, "", "a.b.c.d", "1.999.x.y", "1.2.3.256", "1.2.3.-1",
                    "1.2.3.", ".1.2.3", "1.2.3.4.", "1.2.3.+4", "1.2.3.0000", "0.0.0.0", "255.255.255.255", "224.1.2.3"}) {
                require("".equals(validate.invoke(null, invalid)), "invalid IPv4 alias is rejected safely: " + invalid);
            }
            for (String valid : new String[]{"26.0.0.1", "1.2.3.255", "223.255.255.254"}) {
                require(valid.equals(validate.invoke(null, valid)), "valid IPv4 alias is retained: " + valid);
            }
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("IPv4 validation regression failed", error);
        }
    }

    private static RadminBackend.RadminSnapshot parse(byte[] bytes) {
        return RadminSnapshotParser.parse(bytes, true, "test");
    }

    private static RadminBackend.Peer peer(RadminBackend.RadminSnapshot snapshot, long nodeId) {
        return snapshot.networks().getFirst().peers().stream().filter(peer -> peer.nodeId() == nodeId).findFirst().orElseThrow();
    }

    private static byte[] initialSnapshot() {
        byte[] network = container(0x23, string(0x06, "Regression"), field(0x09, 0x0D, new byte[16]),
                member(42, 2), member(100, 1), member(101, 1), member(102, 1), member(103, 1));
        return frame(scalar(0x01, 34), container(0x20, node(42), string(0x04, "Local"), scalar(0x11, 1),
                container(0x21, network,
                        profile(100, 1L, 0), profile(101, 0L, 1), profile(102, null, 1), profile(103, 9L, 0))));
    }

    private static byte[] profile(long id, Long flags, int direction) {
        return container(0x22, node(id), string(0x04, "Peer" + id), scalar(0x05, 0x1A000000L + id),
                flags == null ? new byte[0] : scalar(0xA0, flags), scalar(0x21, direction), scalar(0x26, 1), scalar(0x3E, 777));
    }

    private static byte[] member(long id, int role) { return container(0x24, node(id), scalar(0x0A, role)); }
    private static byte[] ping(long id, long latency) { return frame(scalar(0x01, 28), node(id), scalar(0x4D, latency)); }
    private static byte[] attributes(long id, long flags) {
        return frame(scalar(0x01, 7), container(0x1F, container(0x66, node(id), scalar(0xA0, flags))));
    }
    private static byte[] ignoredAttributes(long id, long flags) {
        return frame(scalar(0x01, 7), container(0x1F, container(0x55, node(id), scalar(0xA0, flags))));
    }
    private static byte[] node(long id) { return field(0xE1, 0x02, ByteBuffer.allocate(8).putLong(id).array()); }
    private static byte[] scalar(int id, long value) { return field(id, 0x01, ByteBuffer.allocate(4).putInt((int) value).array()); }
    private static byte[] string(int id, String value) {
        return field(id, 0x03, join(value.getBytes(StandardCharsets.UTF_16BE), new byte[]{0, 1}));
    }
    private static byte[] container(int id, byte[]... children) {
        byte[] payload = join(children);
        return ByteBuffer.allocate(8 + payload.length).putInt(payload.length).put((byte) 0).put((byte) 0)
                .put((byte) 0x13).put((byte) id).put(payload).array();
    }
    private static byte[] field(int id, int type, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length).putInt(payload.length).put((byte) type).put((byte) 0)
                .put((byte) (id == 0xE1 ? 1 : 3)).put((byte) id).put(payload).array();
    }
    private static byte[] frame(byte[]... fields) {
        byte[] body = join(fields);
        return ByteBuffer.allocate(4 + body.length).order(ByteOrder.LITTLE_ENDIAN).putInt(body.length).put(body).array();
    }
    private static byte[] join(byte[]... arrays) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] array : arrays) out.writeBytes(array);
        return out.toByteArray();
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
