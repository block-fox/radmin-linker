package dev.radminlinker.mixin;

import dev.radminlinker.RadminLinkerClient;
import dev.radminlinker.config.RadminConfig;
import dev.radminlinker.network.RadminBackend;
import dev.radminlinker.ui.RadminScreen;
import dev.radminlinker.ui.RadminEntryButton;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.client.server.LanServer;
import net.minecraft.client.server.LanServerDetection;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Mixin(JoinMultiplayerScreen.class)
public abstract class MultiplayerScreenMixin extends Screen {
    @Shadow @Final private static Logger LOGGER;
    @Shadow private LanServerDetection.LanServerList lanServerList;
    @Shadow private LanServerDetection.LanServerDetector lanServerDetector;
    @Shadow protected ServerSelectionList serverSelectionList;
    @Unique private int radminlan$detectorRetryTicks;
    @Unique private List<LanServer> radminlan$vanillaRooms = List.of();
    @Unique private List<RadminBackend.LanRoom> radminlan$coreRooms = List.of();

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

    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/multiplayer/ServerSelectionList;updateNetworkServers(Ljava/util/List;)V"), index = 0)
    private List<LanServer> radminlan$mergeVanillaRooms(List<LanServer> vanillaRooms) {
        if (FabricLoader.getInstance().isModLoaded("lanworlds")) return vanillaRooms;
        radminlan$vanillaRooms = List.copyOf(vanillaRooms);
        return radminlan$withCoreRooms(vanillaRooms);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void radminlan$refreshCoreRooms(CallbackInfo ci) {
        if (FabricLoader.getInstance().isModLoaded("lanworlds") || RadminLinkerClient.BACKEND == null) return;
        List<RadminBackend.LanRoom> rooms = RadminLinkerClient.BACKEND.lanRooms();
        if (rooms.equals(radminlan$coreRooms)) return;
        radminlan$coreRooms = rooms;
        serverSelectionList.updateNetworkServers(radminlan$withCoreRooms(radminlan$vanillaRooms));
    }

    @Unique
    private List<LanServer> radminlan$withCoreRooms(List<LanServer> vanillaRooms) {
        if (radminlan$coreRooms.isEmpty()) return vanillaRooms;
        var combined = new ArrayList<>(vanillaRooms);
        var addresses = new HashSet<String>();
        for (LanServer room : vanillaRooms) addresses.add(room.getAddress());
        for (var room : radminlan$coreRooms) {
            String address = room.host() + ":" + room.port();
            if (addresses.add(address)) combined.add(new LanServer(room.motd(), address));
        }
        return combined;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void radminlan$addButton(CallbackInfo ci) {
        RadminConfig config = RadminLinkerClient.CONFIG;
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
                Component.translatable("screen.radminlinker.title"),
                () -> minecraft.setScreenAndShow(new RadminScreen(this))));
    }
}
