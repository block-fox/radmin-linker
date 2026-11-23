import dev.radminlan.network.RadminWire;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** The initial response ends at its state frame even while live events keep arriving. */
final class WireFramingRegression {
    static void check() {
        byte[] handshake = frame(scalar(1, 33));
        byte[] ping = frame(scalar(1, 28), scalar(0x4D, 42));
        byte[] state = field(0x20, 0, 0x13, new byte[0]);
        byte[] initial = frame(scalar(1, 34), state);
        byte[] batch = join(handshake, ping, initial);
        for (int length = 0; length < batch.length; length++) {
            require(RadminWire.initialSnapshotEnd(Arrays.copyOf(batch, length)) == -1,
                    "partial handshake, frame header or state must not end the read: " + length);
        }
        require(RadminWire.initialSnapshotEnd(batch) == batch.length, "complete empty/offline state is enough");
        require(RadminWire.initialSnapshotEnd(join(batch, ping, ping)) == batch.length,
                "ongoing ping traffic cannot delay the initial state boundary");
        require(RadminWire.initialSnapshotEnd(frame(state, scalar(1, 34))) > 0,
                "state and message headers need not have a fixed order");
        require(RadminWire.initialSnapshotEnd(join(handshake, ping, frame(scalar(1, 34)))) == -1,
                "message number alone cannot represent the initial state");
        require(RadminWire.initialSnapshotEnd(frame(scalar(1, 33), state)) == -1,
                "another message with a state-shaped field cannot finish initialization");
        require(RadminWire.initialSnapshotEnd(new byte[]{-1, -1, -1, 127}) == -1,
                "oversized frame header is rejected without allocating");
        byte[] malformed = initial.clone();
        ByteBuffer.wrap(malformed, 4, 4).putInt(Integer.MAX_VALUE);
        require(RadminWire.initialSnapshotEnd(malformed) == -1, "invalid field length cannot cross a frame boundary");
        require(RadminWire.initialSnapshotEnd(frame(scalar(1, 34), state, new byte[]{1})) == -1,
                "trailing incomplete field inside a frame is rejected");
        System.out.println("WIRE_FRAMING_REGRESSION_OK partialReads=true streamingBoundary=true malformed=true");
    }

    private static byte[] scalar(int id, int value) {
        return field(id, 1, 3, ByteBuffer.allocate(4).putInt(value).array());
    }

    private static byte[] field(int id, int type, int tag, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length).putInt(payload.length).put((byte) type).put((byte) 0)
                .put((byte) tag).put((byte) id).put(payload).array();
    }

    private static byte[] frame(byte[]... fields) {
        byte[] body = join(fields);
        return ByteBuffer.allocate(4 + body.length).order(ByteOrder.LITTLE_ENDIAN).putInt(body.length).put(body).array();
    }

    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] part : parts) output.writeBytes(part);
        return output.toByteArray();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
