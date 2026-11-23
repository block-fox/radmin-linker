package dev.radminlan.mixin;

import dev.radminlan.RadminLanClient;
import dev.radminlan.config.RadminConfig;
import dev.radminlan.ui.RadminScreen;
import dev.radminlan.ui.RadminEntryButton;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {
    @Shadow @Final private static Logger LOGGER;
    @Shadow private LanServerDetection.LanServerList lanServerList;
    @Shadow private LanServerDetection.LanServerDetector lanServerDetector;
    @Unique private int radminlan$detectorRetryTicks;

    protected MultiplayerScreenMixin(Component title) { super(title); }

    @Inject(method = "tick", at = @At("HEAD"))
    private void radminlan$resumeVanillaDiscovery(CallbackInfo ci) {
        if (FabricLoader.getInstance().isModLoaded("lanworlds")) return;
        if (lanServerDetector != null && lanServerDetector.isAlive()) return;
        if (radminlan$detectorRetryTicks > 0) {
            radminlan$detectorRetryTicks--;
            return;
        }
        // Returning to an initialized multiplayer screen only repositions it;
        // removed() stopped its detector. Resume the same vanilla list so its
        // selection and scroll position survive a trip through management.
        radminlan$detectorRetryTicks = 100;
        if (lanServerList == null) lanServerList = new LanServerDetection.LanServerList();
        try {
            LanServerDetection.LanServerDetector detector = new LanServerDetection.LanServerDetector(lanServerList);
            detector.start();
            lanServerDetector = detector;
            radminlan$detectorRetryTicks = 0;
        } catch (IOException error) {
            LOGGER.debug("Unable to resume LAN server detection", error);
        }
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void radminlan$addButton(CallbackInfo ci) {
        RadminConfig config = RadminLanClient.CONFIG;
        if (config == null) return;
        if (Boolean.TRUE.equals(FabricLoader.getInstance().getObjectShare().get("lanworlds:manages-radmin-entry-v1"))) return;
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
