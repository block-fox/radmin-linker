package dev.radminlinker.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** Vanilla button surface with the Radmin VPN shield layered over the left edge. */
public final class RadminEntryButton extends Button {
    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath("radminlinker", "textures/radmin_pixel.png");

    public RadminEntryButton(int x, int y, int width, int height, Component message, Runnable action) {
        super(x, y, width, height, message, input -> action.run(), Button.DEFAULT_NARRATION);
        setTooltip(net.minecraft.client.gui.components.Tooltip.create(message));
    }

    @Override
    protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.renderWidget(graphics, mouseX, mouseY, delta);
        int iconSize = Math.min(16, getHeight() - 4);
        graphics.blit(ICON, getX() + 4, getY() + (getHeight() - iconSize) / 2,
                0.0F, 0.0F, iconSize, iconSize, 16, 16);
    }
}
