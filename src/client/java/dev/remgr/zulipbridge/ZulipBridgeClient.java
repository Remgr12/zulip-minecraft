package dev.remgr.zulipbridge;

import dev.remgr.zulipbridge.config.ZulipBridgeConfig;
import dev.remgr.zulipbridge.image.ImageCache;
import dev.remgr.zulipbridge.text.CustomEmojiRegistry;
import io.wispforest.owo.config.Option;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.lwjgl.glfw.GLFW;

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
    private static KeyBinding openGuiKeybind;
    private static boolean previewLeftMouseDown;
    private static boolean previewEscapeDown;

    @Override
    public void onInitializeClient() {
        registerConfigObservers();
        ZulipBridgeClientCommands.register();
        registerKeybind();

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (CONFIG.enabled()) {
                startBridge();
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            stopBridge();
            ImageCache.clear();
            CustomEmojiRegistry.clear();
        });

        // Register preview HUD overlay
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register(new dev.remgr.zulipbridge.image.PreviewHud());
    }

    // ── Bridge lifecycle ──────────────────────────────────────────────────────

    /** Start (or restart) the polling thread. Safe to call multiple times. */
    public static void startBridge() {
        stopBridge();
        LOGGER.info("Starting Zulip bridge ({}#{}).",
                CONFIG.streamName(), CONFIG.topicName());
        CustomEmojiRegistry.refreshAsync(CONFIG);
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

    public static void reloadConfig() {
        CONFIG.load();
        applyConfigState();
    }

    public static void saveConfig() {
        CONFIG.save();
    }

    public static void applyConfigState() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getNetworkHandler() == null) {
            stopBridge();
            return;
        }

        if (CONFIG.enabled()) {
            startBridge();
        } else {
            stopBridge();
        }
    }

    public static void openBridgeScreen(MinecraftClient client) {
        if (client == null) return;
        CustomEmojiRegistry.refreshAsync(CONFIG);
        client.execute(() -> {
            if (!(client.currentScreen instanceof ZulipBridgeScreen)) {
                client.setScreen(new ZulipBridgeScreen());
            }
        });
    }

    private static void registerConfigObservers() {
        observeRestart(CONFIG.optionForKey(CONFIG.keys.zulipBaseUrl));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.botEmail));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.botApiKey));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.streamName));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.topicName));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.messageTarget));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.directMessageRecipients));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.commandName));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.pollIntervalSeconds));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.enabled));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.suppressSelfEcho));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.incomingPrefix));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.senderColor));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.messageColor));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.incomingMessageFormat));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.playIncomingSound));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.showIncomingToast));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.showSuccessfulSendMessages));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.playerDisplayName));
        observeRestart(CONFIG.optionForKey(CONFIG.keys.prependPlayerDisplayNameToOutgoing));
    }

    private static <T> void observeRestart(Option<T> option) {
        if (option == null) return;
        option.observe(value -> onConfigChanged());
    }

    private static void registerKeybind() {
        openGuiKeybind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.zulip-bridge.open_gui",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                KeyBinding.Category.create(Identifier.of("zulip-bridge", "main"))
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openGuiKeybind.wasPressed()) {
                openBridgeScreen(client);
            }

            handlePreviewControls(client);
        });
    }

    private static void handlePreviewControls(MinecraftClient client) {
        if (client == null || client.getWindow() == null) return;

        long windowHandle = client.getWindow().getHandle();

        boolean escapeDown = GLFW.glfwGetKey(windowHandle, GLFW.GLFW_KEY_ESCAPE) == GLFW.GLFW_PRESS;
        if (escapeDown && !previewEscapeDown) {
            dev.remgr.zulipbridge.image.PreviewHud.handleEscape(GLFW.GLFW_KEY_ESCAPE);
        }
        previewEscapeDown = escapeDown;

        boolean leftMouseDown = GLFW.glfwGetMouseButton(windowHandle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        if (leftMouseDown && !previewLeftMouseDown) {
            double mouseX = client.mouse.getX() * client.getWindow().getScaledWidth() / client.getWindow().getWidth();
            double mouseY = client.mouse.getY() * client.getWindow().getScaledHeight() / client.getWindow().getHeight();
            dev.remgr.zulipbridge.image.PreviewHud.handleMousePressed(mouseX, mouseY);
        } else if (!leftMouseDown && previewLeftMouseDown) {
            dev.remgr.zulipbridge.image.PreviewHud.handleMouseReleased();
        }
        previewLeftMouseDown = leftMouseDown;
    }

    private static void onConfigChanged() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) return;
        client.execute(ZulipBridgeClient::applyConfigState);
    }
}
