package dev.radminlan.mixin;

import dev.radminlan.RadminLanClient;
import dev.radminlan.config.RadminConfig;
import dev.radminlan.ui.RadminScreen;
import dev.radminlan.ui.RadminEntryButton;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {
    protected MultiplayerScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void radminlan$addButton(CallbackInfo ci) {
        RadminConfig config = RadminLanClient.CONFIG;
        if (config == null) return;
        int x;
        int y;
        int buttonWidth = 128;
        int titleWidth = font.width(title);
        switch (config.buttonAnchor) {
            case TITLE_AFTER -> { x = width / 2 + titleWidth / 2 + 8; y = 8; }
            case BOTTOM_LEFT -> { x = 12; y = height - 54; }
            case BOTTOM_RIGHT -> { x = width - 140; y = height - 54; }
            case MULTIPLAYER_TITLE, TITLE_BEFORE -> { x = width / 2 - titleWidth / 2 - 136; y = 8; }
            default -> { x = width / 2 - titleWidth / 2 - 136; y = 8; }
        }
        boolean titleAnchor = config.buttonAnchor != RadminConfig.ButtonAnchor.BOTTOM_LEFT
                && config.buttonAnchor != RadminConfig.ButtonAnchor.BOTTOM_RIGHT;
        if (titleAnchor && FabricLoader.getInstance().isModLoaded("lanworlds")) {
            // LanWorlds owns the rightmost 84 pixels of the header. If the
            // requested position does not fit, share the header on the left
            // of its centered title, retaining a gap around both controls.
            int headerRight = Math.max(4, width - 92) - 8;
            if (x < 4 || x + buttonWidth > headerRight) {
                int titleLeft = width / 2 - titleWidth / 2;
                int available = Math.max(1, Math.min(headerRight, titleLeft - 8) - 4);
                buttonWidth = Math.min(buttonWidth, available);
                x = Math.max(4, titleLeft - 8 - buttonWidth);
            }
        }
        addRenderableWidget(new RadminEntryButton(x, y, buttonWidth, 20,
                Component.translatable("screen.radminlan.title"),
                () -> minecraft.setScreenAndShow(new RadminScreen(this))));
    }
}
