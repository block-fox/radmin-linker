package dev.radminlinker.ui;

import dev.radminlinker.RadminLinkerClient;
import dev.radminlinker.input.MouseButtons;
import dev.radminlinker.network.RadminBackend;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Full-screen properties page for a Radmin peer. */
public final class PeerDetailsScreen extends Screen {
    private final Screen parent;
    private final RadminBackend.Peer peer;
    private RadminBackend.Peer displayedPeer;
    private String copyStatus = "";
    private int scroll;
    private static final int CONTENT_HEIGHT = 240;
    private RadminBackend.RadminSnapshot cachedNetworkSnapshot;
    private String[] cachedIdentityValues;
    private String[] cachedIdentityCopyValues;
    private String[] cachedNetworkValues;
    private String[] cachedNetworkCopyValues;
    private String[] cachedConnectionValues;
    private String[] cachedConnectionCopyValues;

    public PeerDetailsScreen(Screen parent, RadminBackend.Peer peer) {
        super(Component.translatable("screen.radminlinker.properties").append(": ")
                .append(peer == null || peer.name() == null ? "" : peer.name()));
        this.parent = parent;
        this.peer = peer == null ? new RadminBackend.Peer("", "", "", -1, false) : peer;
        this.displayedPeer = this.peer;
    }

    @Override
    protected void init() {
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlinker.close"), button -> onClose())
                .bounds(width / 2 - 100, height - 32, 200, 20).build());
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        ensureCachedValues();
        renderTransparentBackground(graphics);
        graphics.fillGradient(0, 0, width, height, 0xEC22384B, 0xF00A1523);
        graphics.drawCenteredString(font, Component.literal(truncate(title.getString(), width - 32)), width / 2, 8, 0xFFFFFFFF);
        graphics.blit(Screen.HEADER_SEPARATOR, 0, 29,
                0.0F, 0.0F, width, 2, 32, 2);

        int panelWidth = Math.min(620, width - 32);
        int x = (width - panelWidth) / 2;
        int y = Math.max(40, (height - CONTENT_HEIGHT) / 2) - scroll;
        graphics.enableScissor(x, 36, x + panelWidth, Math.max(37, height - 52));
        int gap = 8;
        int identityHeight = 102;
        int networkHeight = 50;
        int connectionHeight = 72;

        drawGroup(graphics, x, y, panelWidth, identityHeight, Component.translatable("screen.radminlinker.peer_information"));
        drawProperty(graphics, x, y, panelWidth, "name", cachedIdentityValues[0], 0, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "ipv4", cachedIdentityValues[1], 1, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "id", cachedIdentityValues[2], 2, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "rid", cachedIdentityValues[3], 3, 0xFFB0BAC4);
        drawProperty(graphics, x, y, panelWidth, "ipv6", cachedIdentityValues[4], 4, 0xFFE6EAF0);
        boolean showDirectAddress = showsDirectPeerAddress();
        String address = showDirectAddress ? directPeerAddress() : cachedIdentityValues[5];
        drawProperty(graphics, x, y, panelWidth, showDirectAddress ? "direct_peer_ip" : "node_ip",
                emptyDash(address), 5, 0xFFE6EAF0);

        y += identityHeight + gap;
        drawGroup(graphics, x, y, panelWidth, networkHeight, Component.translatable("screen.radminlinker.network_status"));
        drawProperty(graphics, x, y, panelWidth, "network_name", cachedNetworkValues[0], 0, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "role", cachedNetworkValues[1], 1, 0xFFE6EAF0);

        y += networkHeight + gap;
        drawGroup(graphics, x, y, panelWidth, connectionHeight, Component.translatable("screen.radminlinker.connection_status"));
        drawProperty(graphics, x, y, panelWidth, "state", cachedConnectionValues[0], 0, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "channel", cachedConnectionValues[1], 1, 0xFFE6EAF0);
        drawProperty(graphics, x, y, panelWidth, "latency", cachedConnectionValues[2], 2,
                displayedPeer.online() ? 0xFFE6EAF0 : 0xFFB0BAC4);
        drawProperty(graphics, x, y, panelWidth, "version", cachedConnectionValues[3], 3, 0xFFE6EAF0);
        graphics.disableScissor();
        if (!copyStatus.isBlank()) graphics.drawString(font, copyStatus, 20, height - 48, 0xFF9FC8E8);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void drawGroup(GuiGraphics graphics, int x, int y, int width, int height, Component heading) {
        graphics.fill(x, y, x + width, y + height, 0xFF2B475B);
        graphics.fillGradient(x + 1, y + 1, x + width - 1, y + height - 1, 0xFF22394C, 0xFF132637);
        UiSprites.draw(graphics, UiSprites.Art.PEER, x + 6, y + 2, 17);
        graphics.drawString(font, heading, x + 26, y + 5, 0xFF9FC8E8);
        graphics.blit(Screen.HEADER_SEPARATOR, x + 8, y + 17,
                0.0F, 0.0F, width - 16, 2, 32, 2);
    }

    private void drawProperty(GuiGraphics graphics, int x, int y, int panelWidth,
                              String label, String value, int row, int color) {
        int rowY = y + 23 + row * 13;
        graphics.fill(x + 8, rowY - 2, x + panelWidth - 8, rowY + 10,
                (row & 1) == 0 ? 0x221D2931 : 0x12000000);
        graphics.drawString(font, truncate(Component.translatable("screen.radminlinker.property." + label).getString(), 142),
                x + 12, rowY, 0xFFB9C2CA);
        int valueX = x + Math.min(164, panelWidth / 2);
        int available = x + panelWidth - 12 - valueX;
        graphics.drawString(font, truncate(value, available), valueX, rowY, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if (mouseButton == MouseButtons.left() && mouseY >= 36 && mouseY < height - 52) {
            int panelWidth = Math.min(620, width - 32);
            int x = (width - panelWidth) / 2;
            int y = Math.max(40, (height - CONTENT_HEIGHT) / 2) - scroll;
            String value = valueAt(x, y, (int) mouseX, (int) mouseY);
            if (value != null && !value.isBlank() && minecraft != null) {
                minecraft.keyboardHandler.setClipboard(value);
                copyStatus = Component.translatable("screen.radminlinker.peer.copied", value).getString();
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        int maximum = Math.max(0, CONTENT_HEIGHT + 91 - height);
        if (maximum == 0 || verticalAmount == 0 || mouseY < 36 || mouseY >= height - 52) return false;
        scroll = Math.clamp(scroll - (int) Math.round(verticalAmount * 24), 0, maximum);
        return true;
    }

    private String valueAt(int x, int y, int mouseX, int mouseY) {
        ensureCachedValues();
        int panelWidth = Math.min(620, width - 32);
        for (int row = 0; row < cachedIdentityCopyValues.length; row++) {
            if (hitRow(x, y + 23 + row * 13, panelWidth, mouseX, mouseY)) {
                return row == 5 && showsDirectPeerAddress() ? directPeerAddress() : cachedIdentityCopyValues[row];
            }
        }
        y += 102 + 8;
        for (int row = 0; row < cachedNetworkCopyValues.length; row++) {
            if (hitRow(x, y + 23 + row * 13, panelWidth, mouseX, mouseY)) return cachedNetworkCopyValues[row];
        }
        y += 50 + 8;
        for (int row = 0; row < cachedConnectionCopyValues.length; row++) {
            if (hitRow(x, y + 23 + row * 13, panelWidth, mouseX, mouseY)) return cachedConnectionCopyValues[row];
        }
        return null;
    }

    private static boolean hitRow(int x, int rowY, int panelWidth, int mouseX, int mouseY) {
        return mouseX >= x + 8 && mouseX <= x + panelWidth - 8 && mouseY >= rowY - 2 && mouseY <= rowY + 10;
    }

    private boolean showsDirectPeerAddress() {
        var backend = RadminLinkerClient.BACKEND;
        return backend != null && backend.showsDirectPeerAddress();
    }

    private String directPeerAddress() {
        long nodeId = displayedPeer.nodeId();
        return nodeId == -1L ? "" : RadminLinkerClient.BACKEND.directPeerAddress(nodeId);
    }

    private void ensureCachedValues() {
        RadminBackend.RadminSnapshot snapshot = RadminLinkerClient.cachedSnapshot();
        if (cachedIdentityValues != null && cachedNetworkSnapshot == snapshot) return;
        cachedNetworkSnapshot = snapshot;
        List<RadminBackend.Network> networks = snapshot == null || snapshot.networks() == null
                ? List.of() : snapshot.networks();
        displayedPeer = networks.stream().filter(network -> network != null && network.peers() != null)
                .flatMap(network -> network.peers().stream())
                .filter(candidate -> candidate != null && samePeer(candidate, peer))
                .findFirst().orElse(peer);
        RadminBackend.Peer current = displayedPeer;
        String names = snapshot == null ? safe(peer.networkName()) : networks.stream()
                .filter(network -> network != null && network.peers() != null
                        && network.peers().stream().anyMatch(candidate -> candidate != null && samePeer(candidate, peer)))
                .map(RadminBackend.Network::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String networkNames = names.isBlank() ? safe(current.networkName()) : names;
        String role = current.role() == null ? emptyValue() : Component.translatable(switch (current.role().intValue()) {
            case 1 -> "screen.radminlinker.role_member";
            case 2 -> "screen.radminlinker.role_admin";
            case 3 -> "screen.radminlinker.role_pending";
            default -> "screen.radminlinker.property.not_provided";
        }).getString();
        cachedIdentityValues = new String[]{current.name(), emptyDash(current.virtualIp()),
                current.nodeId() < 0 ? emptyValue() : Long.toUnsignedString(current.nodeId()), emptyValue(),
                emptyDash(current.virtualIpv6()), emptyDash(current.publicIp())};
        cachedIdentityCopyValues = new String[]{current.name(), emptyText(current.virtualIp()),
                current.nodeId() < 0 ? "" : Long.toUnsignedString(current.nodeId()), "",
                emptyText(current.virtualIpv6()), emptyText(current.publicIp())};
        cachedNetworkValues = new String[]{emptyDash(networkNames), role};
        cachedNetworkCopyValues = new String[]{networkNames, role};
        String state = statusText(current);
        String latency = formatLatency(current.latencyMs(), current.online());
        cachedConnectionValues = new String[]{state, emptyDash(current.connectionType()),
                latency, emptyDash(current.version())};
        cachedConnectionCopyValues = new String[]{state, emptyText(current.connectionType()),
                current.online() && current.latencyMs() >= 0 ? latency : "", emptyText(current.version())};
    }

    private static boolean samePeer(RadminBackend.Peer left, RadminBackend.Peer right) {
        if (left == null || right == null) return false;
        if (left.nodeId() >= 0 && right.nodeId() >= 0) return left.nodeId() == right.nodeId();
        String leftIp = safe(left.virtualIp());
        String rightIp = safe(right.virtualIp());
        return !leftIp.isBlank() && leftIp.equalsIgnoreCase(rightIp);
    }

    private static String emptyDash(String value) { return value == null || value.isBlank() ? "-" : value; }
    private static String emptyText(String value) { return value == null ? "" : value; }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String emptyValue() { return Component.translatable("screen.radminlinker.property.not_provided").getString(); }
    private String truncate(String value, int maxWidth) {
        if (value == null || font.width(value) <= maxWidth) return value == null ? "" : value;
        return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("..."))) + "...";
    }
    private static String statusText(RadminBackend.Peer peer) {
        String key = peer.online() ? "online" : "offline".equalsIgnoreCase(peer.state()) ? "offline" : "unknown";
        return Component.translatable("screen.radminlinker.peer." + key).getString();
    }

    private static String formatLatency(long value, boolean online) {
        if (!online) return "-";
        return Component.translatable(value < 0 ? "screen.radminlinker.peer.latency_unknown"
                : "screen.radminlinker.peer.latency", value).getString();
    }
}
