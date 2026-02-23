package net.infiniteimperm.fabric.tagger.diagnose;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.awt.Desktop;
import java.nio.file.Path;

public final class ChatUi {
    private ChatUi() {
    }

    public static void info(String message) {
        send(Text.literal(message).formatted(Formatting.GRAY));
    }

    public static void success(String message) {
        send(Text.literal(message).formatted(Formatting.GREEN));
    }

    public static void warn(String message) {
        send(Text.literal(message).formatted(Formatting.YELLOW));
    }

    public static void error(String message) {
        send(Text.literal(message).formatted(Formatting.RED));
    }

    public static void hintGray(String message) {
        send(Text.literal(message).formatted(Formatting.DARK_GRAY));
    }

    public static void divider() {
        send(Text.literal("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━").formatted(Formatting.DARK_AQUA));
    }

    public static void title(String message) {
        send(Text.literal(message).formatted(Formatting.GOLD, Formatting.BOLD));
    }

    public static void clickableUrl(String label, String url) {
        MutableText text = Text.literal(label).setStyle(
            Style.EMPTY.withColor(Formatting.AQUA).withUnderline(true).withClickEvent(
                new ClickEvent(ClickEvent.Action.OPEN_URL, url)
            )
        );
        send(text);
    }

    public static void clickableOpenFolderLabel(String label, Path folder) {
        MutableText text = Text.literal(label).setStyle(
            Style.EMPTY.withColor(Formatting.GOLD).withBold(true).withUnderline(true).withClickEvent(
                new ClickEvent(ClickEvent.Action.OPEN_FILE, folder.toAbsolutePath().toString())
            )
        );
        send(text);
    }

    public static void openFolderOrCopy(Path folder) {
        MinecraftClient client = MinecraftClient.getInstance();
        Thread worker = new Thread(() -> {
            try {
                Desktop.getDesktop().open(folder.toFile());
                client.execute(() -> success("Opened folder: " + folder.toAbsolutePath()));
            } catch (Throwable error) {
                client.execute(() -> {
                    if (client.keyboard != null) {
                        client.keyboard.setClipboard(folder.toAbsolutePath().toString());
                    }
                    warn("Could not open folder. Path copied to clipboard.");
                    info(folder.toAbsolutePath().toString());
                });
            }
        }, "insignia-open-folder");
        worker.setDaemon(true);
        worker.start();
    }

    private static void send(Text text) {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendMessage(text, false);
            }
        });
    }
}
