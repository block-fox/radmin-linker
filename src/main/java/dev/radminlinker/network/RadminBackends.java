package dev.radminlinker.network;

import dev.radminlinker.config.RadminConfig;

public final class RadminBackends {
    private RadminBackends() {}
    public static RadminBackend create(RadminConfig config) {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        RadminConfig.ConnectionBackend mode = config.getConnectionBackend();
        if (mode != RadminConfig.ConnectionBackend.WINDOWS) {
            var core = java.util.ServiceLoader.load(RadminCoreProvider.class, RadminCoreProvider.class.getClassLoader())
                    .findFirst();
            if (core.isPresent()) {
                if (mode == RadminConfig.ConnectionBackend.CORE || !windows
                        || !RadminPipeClient.isAvailable(config.pipeName)) return core.get().create();
            }
            if (mode == RadminConfig.ConnectionBackend.CORE || !windows) {
                return new UnavailableBackend("screen.radminlinker.core.not_embedded");
            }
        }
        if (!windows) return new UnavailableBackend("screen.radminlinker.core.windows_only");
        if (config.pipeName != null && !config.pipeName.isBlank()) {
            RadminBackend pipe = new PipeRadminBackend(config);
            if (config.enableCli && config.radminExecutable != null && !config.radminExecutable.isBlank()) {
                return new FallbackRadminBackend(pipe, new WindowsCliBackend(config));
            }
            return pipe;
        }
        if (!config.enableCli) return new UnavailableBackend("CLI integration disabled in config");
        return new WindowsCliBackend(config);
    }
}

