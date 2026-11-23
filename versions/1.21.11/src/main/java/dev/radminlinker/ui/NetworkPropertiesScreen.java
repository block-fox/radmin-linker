package dev.radminlinker.ui;

import dev.radminlinker.network.RadminBackend;
import dev.radminlinker.input.MouseButtons;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Full page for the properties of one Radmin network. */
public final class NetworkPropertiesScreen extends Screen {
    private final Screen parent;
    private final RadminBackend.Network network;
    private String copyStatus = "";
    private String[] cachedValues;
    private String[] cachedOptionValues;

    public NetworkPropertiesScreen(Screen parent, RadminBackend.Network network) {
        super(Component.translatable("screen.radminlinker.network_properties"));
        this.parent = parent;
        this.network = network == null
                ? new RadminBackend.Network("", "", "", List.of()) : network;
    }

    @Override
    protected void init() {
        int buttonY = panelY() + panelHeight() - 32;
        addRenderableWidget(Button.builder(Component.translatable("screen.radminlinker.close"), button -> onClose())
                .bounds(width / 2 - 90, buttonY, 180, 20).build());
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
        int x = panelX();
        int y = panelY();
        int panelWidth = panelWidth();
        int panelHeight = panelHeight();
        graphics.fill(x, y, x + panelWidth, y + panelHeight, 0xFF365368);
        graphics.fillGradient(x + 1, y + 1, x + panelWidth - 1, y + panelHeight - 1, 0xFF1E3448, 0xFF102535);
        graphics.fillGradient(x + 1, y + 1, x + panelWidth - 1, y + 28, 0xFF355970, 0xFF294358);
        UiSprites.draw(graphics, UiSprites.Art.NETWORK, x + 7, y + 3, 22);
        graphics.drawString(font, truncate(network.name(), panelWidth - 42), x + 34, y + 9, 0xFFFFFFFF);
        graphics.drawString(font, Component.translatable("screen.radminlinker.network_information"), x + 12, y + 39, 0xFF9FC8E8);
        drawProperty(graphics, x, y + 54, panelWidth, "network_name", cachedValues[0]);
        drawProperty(graphics, x, y + 72, panelWidth, "network_type", cachedValues[1]);
        drawProperty(graphics, x, y + 90, panelWidth, "role", cachedValues[2]);
        drawProperty(graphics, x, y + 108, panelWidth, "members", cachedValues[3]);
        drawProperty(graphics, x, y + 126, panelWidth, "network_state", cachedValues[4]);
        drawProperty(graphics, x, y + 144, panelWidth, "guid", cachedValues[5]);
        if (!network.publicNetwork()) {
            graphics.drawString(font, Component.translatable("screen.radminlinker.network_options"), x + 12, y + 168, 0xFF9FC8E8);
            if (Boolean.TRUE.equals(network.admin())) {
                drawProperty(graphics, x, y + 186, panelWidth, "allow_passwordless", cachedOptionValues[0]);
                drawProperty(graphics, x, y + 204, panelWidth, "block_new_members", cachedOptionValues[1]);
                graphics.drawString(font, truncate(Component.translatable("screen.radminlinker.property_write_unavailable").getString(), panelWidth - 24),
                        x + 12, y + panelHeight - 42, 0xFFAAAAAA);
            } else {
                String note = Component.translatable(network.admin() == null
                        ? "screen.radminlinker.admin_unknown" : "screen.radminlinker.admin_required").getString();
                graphics.drawString(font, truncate(note, panelWidth - 24), x + 12, y + 188, 0xFFAAAAAA);
            }
        }
        if (!copyStatus.isBlank()) graphics.drawString(font, copyStatus, x + 12, height - 48, 0xFF9FC8E8);
        super.render(graphics, mouseX, mouseY, delta);
    }

    private void drawProperty(GuiGraphics graphics, int x, int y, int panelWidth, String key, String value) {
        graphics.fill(x + 8, y - 2, x + panelWidth - 8, y + 11, (y / 18 & 1) == 0 ? 0x221D2931 : 0x12000000);
        graphics.drawString(font, Component.translatable("screen.radminlinker.property." + key).append(":"), x + 12, y, 0xFFB9C2CA);
        graphics.drawString(font, truncate(value, panelWidth - 170), x + 134, y, 0xFFE6EAF0);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == MouseButtons.left() && minecraft != null) {
            int x = panelX();
            int y = panelY();
            String value = valueAt(x, y, (int) event.x(), (int) event.y());
            if (value != null && !value.isBlank()) {
                minecraft.keyboardHandler.setClipboard(value);
                copyStatus = Component.translatable("screen.radminlinker.peer.copied", value).getString();
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private String valueAt(int x, int y, int mouseX, int mouseY) {
        int width = panelWidth();
        ensureCachedValues();
        int[] ys = {y + 54, y + 72, y + 90, y + 108, y + 126, y + 144};
        for (int i = 0; i < ys.length; i++) if (hitRow(x, ys[i], width, mouseX, mouseY)) return cachedValues[i];
        if (!network.publicNetwork()) {
            int[] optionY = {y + 186, y + 204};
            for (int i = 0; i < optionY.length; i++) if (hitRow(x, optionY[i], width, mouseX, mouseY)) return cachedOptionValues[i];
        }
        return null;
    }

    private static boolean hitRow(int x, int rowY, int width, int mouseX, int mouseY) {
        return mouseX >= x + 8 && mouseX <= x + width - 8 && mouseY >= rowY - 2 && mouseY <= rowY + 11;
    }

    private void ensureCachedValues() {
        if (cachedValues != null) return;
        cachedValues = new String[]{network.name(),
                Component.translatable(network.publicNetwork()
                        ? "screen.radminlinker.public_network" : "screen.radminlinker.private_network").getString(),
                roleText(), Integer.toString(network.peers().size()),
                network.enabled() == null ? Component.translatable("screen.radminlinker.property_unknown").getString()
                        : Component.translatable(Boolean.TRUE.equals(network.enabled())
                        ? "screen.radminlinker.network_enabled" : "screen.radminlinker.network_disabled").getString(),
                network.guid()};
        cachedOptionValues = new String[]{flagText(network.allowPasswordless()), flagText(network.blockNewMembers())};
    }

    private String roleText() {
        return network.admin() == null ? Component.translatable("screen.radminlinker.property_unknown").getString()
                : Component.translatable(Boolean.TRUE.equals(network.admin())
                ? "screen.radminlinker.network_admin" : "screen.radminlinker.network_member").getString();
    }
    private static String flagText(Boolean value) {
        return Component.translatable(value == null ? "screen.radminlinker.property_unknown"
                : Boolean.TRUE.equals(value) ? "screen.radminlinker.enabled" : "screen.radminlinker.disabled").getString();
    }
    private int panelWidth() { return Math.min(500, width - 32); }
    private int panelX() { return (width - panelWidth()) / 2; }
    private int panelHeight() { return network.publicNetwork() ? 196 : Boolean.TRUE.equals(network.admin()) ? 258 : 246; }
    private int panelY() { return Math.max(12, (height - panelHeight()) / 2); }
    private String truncate(String value, int maxWidth) {
        if (value == null || font.width(value) <= maxWidth) return value == null ? "" : value;
        return font.plainSubstrByWidth(value, Math.max(1, maxWidth - font.width("…"))) + "…";
    }
}
