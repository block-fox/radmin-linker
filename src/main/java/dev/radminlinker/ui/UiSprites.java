package dev.radminlinker.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** PNG sprites painted in Blockbench and bundled with the mod. */
public final class UiSprites {
    public enum Art {
        EMPTY("checkbox_empty", 16), CHECKED("checkbox_checked", 16),
        NETWORK("network", 16), NETWORK_DISABLED("network_disabled", 16), PEER("peer", 16), PEER_OFFLINE("peer_offline", 16),
        SIGNAL_0("signal_0", 16), SIGNAL_1("signal_1", 16), SIGNAL_2("signal_2", 16),
        SIGNAL_3("signal_3", 16), SIGNAL_4("signal_4", 16);
        private final Identifier texture;
        private final int size;
        Art(String name, int size) {
            this.size = size;
            this.texture = Identifier.fromNamespaceAndPath("radminlinker", "textures/gui/" + name + ".png");
        }
    }

    public static final Art[] SIGNAL = {Art.SIGNAL_0, Art.SIGNAL_1, Art.SIGNAL_2, Art.SIGNAL_3, Art.SIGNAL_4};

    private UiSprites() {}

    public static void draw(GuiGraphicsExtractor graphics, Art art, int x, int y, int size) {
        graphics.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, art.texture, x, y, 0, 0, size, size, art.size, art.size, art.size, art.size);
    }

    public static void checkbox(GuiGraphicsExtractor graphics, boolean checked, int x, int y, int size) {
        draw(graphics, checked ? Art.CHECKED : Art.EMPTY, x, y, size);
    }
}
