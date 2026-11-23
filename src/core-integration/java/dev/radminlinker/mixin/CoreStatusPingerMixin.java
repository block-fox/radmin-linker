// Adapted from OpenRad (MIT). See META-INF/LICENSE-OpenRad.
package dev.radminlinker.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.radminlinker.network.EmbeddedCoreBackend;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import net.minecraft.client.multiplayer.ServerStatusPinger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.net.InetAddress;
import java.net.InetSocketAddress;

// ViaFabricPlus replaces the legacy method with an empty implementation.
// Higher priority lets us inspect the merged method, and the call is optional
// so a disabled legacy fallback never prevents the multiplayer menu from opening.
@Mixin(value = ServerStatusPinger.class, priority = 1100)
public abstract class CoreStatusPingerMixin {
    /** The old ping fallback bypasses Connection.connect and must also stay inside the VPN. */
    @WrapOperation(method = "pingLegacyServer", at = @At(value = "INVOKE",
                    target = "Lio/netty/bootstrap/Bootstrap;connect(Ljava/net/InetAddress;I)Lio/netty/channel/ChannelFuture;",
                    remap = false), require = 0, expect = 0)
    private ChannelFuture radminlan$connect(Bootstrap bootstrap, InetAddress host, int port,
            Operation<ChannelFuture> connect, @Local(argsOnly = true) InetSocketAddress destination) {
        return EmbeddedCoreBackend.connect(destination, routed -> routed == destination
                ? connect.call(bootstrap, host, port)
                : connect.call(bootstrap, routed.getAddress(), routed.getPort()));
    }
}
