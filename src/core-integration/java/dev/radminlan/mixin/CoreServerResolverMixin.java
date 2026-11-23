// Adapted from OpenRad (MIT). See META-INF/LICENSE-OpenRad.
package dev.radminlan.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.radminlan.network.EmbeddedCoreBackend;
import dev.radminlan.core.CoreSession;
import java.net.InetSocketAddress;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddressResolver;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import net.minecraft.client.multiplayer.resolver.ServerRedirectHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Optional;

@Mixin(ServerNameResolver.class)
public abstract class CoreServerResolverMixin {
    @WrapOperation(method = "resolveAddress", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/resolver/ServerAddressResolver;"
                    + "resolve(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"))
    private Optional<ResolvedServerAddress> radminlan$resolveLiteral(ServerAddressResolver resolver,
            ServerAddress address, Operation<Optional<ResolvedServerAddress>> original) {
        if (EmbeddedCoreBackend.usesVirtualRoute(address.getHost())) {
            return Optional.of(ResolvedServerAddress.from(new InetSocketAddress(
                    CoreSession.virtualAddress(address.getHost()), address.getPort())));
        }
        return original.call(resolver, address);
    }

    @WrapOperation(method = "resolveAddress", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/resolver/ServerRedirectHandler;"
                    + "lookupRedirect(Lnet/minecraft/client/multiplayer/resolver/ServerAddress;)Ljava/util/Optional;"))
    private Optional<ServerAddress> radminlan$lookupRedirect(ServerRedirectHandler redirects,
            ServerAddress address, Operation<Optional<ServerAddress>> lookup) {
        return EmbeddedCoreBackend.usesVirtualRoute(address.getHost())
                ? Optional.empty()
                : lookup.call(redirects, address);
    }
}
