package dev.remgr.zulipbridge;

import dev.remgr.zulipbridge.config.ZulipBridgeConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-side mod initializer.
 *
 * <p>Starts the Zulip polling thread when the player joins a server/world
 * and stops it cleanly on disconnect.
 */
public class ZulipBridgeClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("zulip-bridge");
    public static final ZulipBridgeConfig CONFIG = ZulipBridgeConfig.createAndLoad();

    private static ZulipPollingThread pollingThread;

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (CONFIG.enabled()) {
                startBridge();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> stopBridge());
    }

    // ── Bridge lifecycle ──────────────────────────────────────────────────────

    /** Start (or restart) the polling thread. Safe to call multiple times. */
    public static void startBridge() {
        stopBridge();
        LOGGER.info("Starting Zulip bridge ({}#{}).",
                CONFIG.streamName(), CONFIG.topicName());
        pollingThread = new ZulipPollingThread(CONFIG);
        pollingThread.start();
    }

    /** Stop the polling thread if it is running. */
    public static void stopBridge() {
        if (pollingThread != null && pollingThread.isAlive()) {
            LOGGER.info("Stopping Zulip bridge.");
            pollingThread.shutdown();
            pollingThread = null;
        }
    }

    /** @return true if a polling thread is currently running. */
    public static boolean isRunning() {
        return pollingThread != null && pollingThread.isAlive();
    }
}
