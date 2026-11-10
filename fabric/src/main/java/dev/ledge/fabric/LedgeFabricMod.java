package dev.ledge.fabric;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LedgeFabricMod implements ModInitializer {
    public static final String MOD_ID = "ledge";
    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Ledge Fabric adapter is loaded.");
    }
}
