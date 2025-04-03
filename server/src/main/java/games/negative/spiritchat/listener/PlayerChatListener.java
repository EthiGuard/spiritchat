package games.negative.spiritchat.listener;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import games.negative.alumina.logger.Logs;
import games.negative.alumina.message.Message;
import games.negative.spiritchat.SpiritChatPlugin;
import games.negative.spiritchat.command.CommandColor;
import games.negative.spiritchat.config.SpiritChatConfig;
import games.negative.spiritchat.permission.Perm;
import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class PlayerChatListener implements Listener {
    private static final Duration CACHE_DURATION = Duration.ofSeconds(10);
    private static final MiniMessage MINIMESSAGE = MiniMessage.builder()
            .postProcessor(component -> component.decoration(TextDecoration.ITALIC, false))
            .build();

    private final CommandColor commandColor;

    public PlayerChatListener(@NotNull CommandColor commandColor) {
        this.commandColor = Objects.requireNonNull(commandColor, "CommandColor cannot be null");
    }

    @EventHandler
    public void onChat(@NotNull AsyncChatEvent event) {
        Player player = event.getPlayer();
        
        // Check if player is muted
        if (SpiritChatPlugin.instance().getMuteManager().isMuted(player.getUniqueId())) {
            long remaining = SpiritChatPlugin.instance().getMuteManager().getRemainingTime(player.getUniqueId());
            if (remaining > 0) {
                String timeLeft = formatDuration(remaining);
                player.sendMessage(Component.text("You are muted for " + timeLeft, NamedTextColor.RED));
            } else {
                player.sendMessage(Component.text("You are muted!", NamedTextColor.RED));
            }
            event.setCancelled(true);
            return;
        }

        // Filter out ignored players from receivers
        event.viewers().removeIf(audience -> {
            if (!(audience instanceof Player viewer)) return false;
            return SpiritChatPlugin.instance().getIgnoreManager().isIgnored(viewer.getUniqueId(), player.getUniqueId());
        });

        event.renderer(format().isUseStaticFormat() ? 
            new StaticGlobalChatRenderer() : 
            new GroupGlobalChatRenderer());
    }

    private String formatDuration(long millis) {
        long seconds = millis / 1000;
        if (seconds < 60) return seconds + " seconds";
        if (seconds < 3600) return (seconds / 60) + " minutes";
        if (seconds < 86400) return (seconds / 3600) + " hours";
        return (seconds / 86400) + " days";
    }

    public static SpiritChatConfig.Format format() {
        return SpiritChatPlugin.config().format();
    }

    private String formatMessage(@NotNull Player source, @NotNull String messageStr) {
        Objects.requireNonNull(source, "Player cannot be null");
        Objects.requireNonNull(messageStr, "Message cannot be null");

        messageStr = handleLegacyFormatting(messageStr);
        messageStr = handlePermissionBasedFormatting(source, messageStr);
        return applyPlayerColor(source, messageStr);
    }

    private String handleLegacyFormatting(String messageStr) {
        if (messageStr.contains("§")) {
            Component legacyComponent = LegacyComponentSerializer.legacySection().deserialize(messageStr);
            messageStr = MINIMESSAGE.serialize(legacyComponent);
        }
        return messageStr;
    }

    private String handlePermissionBasedFormatting(Player source, String messageStr) {
        if (!source.hasPermission(Perm.CHAT_COLORS)) {
            messageStr = ChatColor.stripColor(messageStr);
            return "<white>" + MINIMESSAGE.escapeTags(messageStr) + "</white>";
        }

        if (!source.hasPermission(Perm.CHAT_FORMAT)) {
            messageStr = MINIMESSAGE.escapeTags(messageStr);
        }
        return messageStr;
    }

    private String applyPlayerColor(Player source, String messageStr) {
        String customColor = SpiritChatPlugin.colors().getCustomColor(source.getUniqueId());
        if (customColor != null) {
            if (customColor.startsWith("<gradient:")) {
                return customColor + messageStr + "</gradient>";
            }
            if (customColor.startsWith("#")) {
                return "<color:" + customColor + ">" + messageStr + "</color>";
            }
        }

        String colorTag = SpiritChatPlugin.colors().getColor(source.getUniqueId());
        if (colorTag != null && !colorTag.isEmpty()) {
            return colorTag + messageStr + commandColor.getClosingTag(colorTag);
        }

        return "<white>" + messageStr + "</white>";
    }

    private class StaticGlobalChatRenderer implements ChatRenderer {

        @Override
        public @NotNull Component render(@NotNull Player source, @NotNull Component display, @NotNull Component message, @NotNull Audience viewer) {
            String format = format().getGlobalFormat();
            if (format == null || format.isBlank()) {
                Logs.error("Could not send chat message because 'global-format' is blank or does not exist");
                throw new IllegalStateException("Empty global-chat format!");
            }

            String messageStr = PlainTextComponentSerializer.plainText().serialize(message);
            String formattedMessage = formatMessage(source, messageStr);

            Message.Builder builder = new Message(format).create()
                    .replace("%display-name%", display)
                    .replace("%username%", source.getName())
                    .replace("%message%", formattedMessage);

            ItemStack item = source.getInventory().getItemInMainHand();
            if (format().isUseItemDisplay() && source.hasPermission(Perm.CHAT_ITEM) && isChatItemSyntax(message) && !item.getType().isAir()) {
                String itemMiniMessage = createItemName(item.displayName());
                builder = builder.replace(Pattern.quote("{i}"), itemMiniMessage);
                builder = builder.replace("\\{item\\}", itemMiniMessage);
            }

            return builder.asComponent(source);
        }
    }

    private class GroupGlobalChatRenderer implements ChatRenderer {

        private static final LoadingCache<UUID, String> CACHE = CacheBuilder.newBuilder()
                .expireAfterWrite(CACHE_DURATION)
                .build(new CacheLoader<>() {
                    @Override
                    public @NotNull String load(@NotNull UUID key) throws Exception {
                        LuckPerms api = SpiritChatPlugin.luckperms().orElse(null);
                        if (api == null) throw new Exception("Could not find LuckPerms dependency on the server!");

                        User user = api.getUserManager().getUser(key);
                        if (user == null) throw new Exception("Could not find user with UUID %s".formatted(key));

                        LinkedList<Group> groups = user.getInheritedGroups(user.getQueryOptions()).stream()
                                .sorted(Comparator.<Group, Integer>comparing(g -> g.getWeight().orElse(0)).reversed())
                                .collect(Collectors.toCollection(Lists::newLinkedList));

                        for (Group group : groups) {
                            String format = format().groupFormat(group.getName()).orElse(null);
                            if (format == null || format.isBlank()) continue;

                            return format;
                        }

                        throw new Exception("Could not find a valid group format for user with UUID %s".formatted(key));
                    }
                });

        @Override
        public Component render(@NotNull Player source, @NotNull Component display, @NotNull Component message, @NotNull Audience viewer) {
            LuckPerms api = SpiritChatPlugin.luckperms().orElse(null);
            if (api == null) {
                Logs.error("Could not find LuckPerms dependency on the server, yet 'use-static-format' is false!");
                Logs.info("Defaulting to static format for chat messages.");

                return new StaticGlobalChatRenderer().render(source, display, message, viewer);
            }

            try {
                String format = CACHE.get(source.getUniqueId());

                Message.Builder builder = new Message(format).create()
                        .replace("%display-name%", display)
                        .replace("%username%", source.getName());

                String messageStr = PlainTextComponentSerializer.plainText().serialize(message);
                messageStr = formatMessage(source, messageStr);
                builder = builder.replace("%message%", messageStr);

                ItemStack item = source.getInventory().getItemInMainHand();
                if (format().isUseItemDisplay() && source.hasPermission(Perm.CHAT_ITEM) && isChatItemSyntax(message) && !item.getType().isAir()) {
                    String itemMiniMessage = createItemName(item.displayName());
                    builder = builder.replace(Pattern.quote("{i}"), itemMiniMessage);
                    builder = builder.replace("\\{item\\}", itemMiniMessage);
                }

                return builder.asComponent(source);
            } catch (ExecutionException e) {
                return new StaticGlobalChatRenderer().render(source, display, message, viewer);
            }
        }
    }

    private boolean isChatItemSyntax(@NotNull Component message) {
        String string = PlainTextComponentSerializer.plainText().serialize(message);
        return string.contains("{i}") || string.contains("{item}");
    }

    private String createItemName(@NotNull Component component) {
        String itemName = MINIMESSAGE.serialize(component);

        Integer start = null;
        Integer end = null;
        for (int i = 0; i < itemName.length(); i++) {
            if (itemName.charAt(i) == '<') start = i;
            if (itemName.charAt(i) == '>') end = i;

            if (start != null && end != null) break;
        }

        if (start == null || end == null) return itemName;

        String between = itemName.substring(start + 1, end);
        if (between.contains("color")) return itemName + "</color>";
        if (between.contains("gradient")) return itemName + "</gradient>";

        return itemName + "</" + between + ">";
    }
}
