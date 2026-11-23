package dev.radminlan.network;

/** Loaded only in distributions that include the standalone VPN core. */
public interface RadminCoreProvider {
    RadminBackend create();
}
