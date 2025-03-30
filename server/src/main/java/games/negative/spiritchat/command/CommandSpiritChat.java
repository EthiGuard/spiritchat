package games.negative.spiritchat.command;

import games.negative.alumina.command.Command;
import games.negative.alumina.command.CommandContext;
import games.negative.alumina.command.builder.CommandBuilder;
import org.jetbrains.annotations.NotNull;
import games.negative.spiritchat.SpiritChatPlugin;
import games.negative.spiritchat.permission.Perm;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.EventHandler;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import net.kyori.adventure.text.Component;

public class CommandChatColor extends Command implements Listener {
    public CommandChatColor() {
        super(CommandBuilder.builder().name("chatcolor")
                .description("The administrative command for SpiritChat.")
                .permission(Perm.ADMIN)
                .smartTabComplete(true)
        );
    }

    @Override
    public void execute(@NotNull CommandContext context) {
        if (context.sender() instanceof Player) {
            Player player = (Player) context.sender();
            openChatColorGUI(player);
        } else {
            context.sender().sendMessage("This command can only be used by players.");
        }
    }

    private void openChatColorGUI(Player player) {
        Inventory gui = Bukkit.createInventory(null, InventoryType.HOPPER, Component.text("Select Chat Color"));

        // Example items for colors
        ItemStack red = new ItemStack(Material.RED_WOOL);
        ItemMeta redMeta = red.getItemMeta();
        redMeta.displayName(Component.text("Red"));
        red.setItemMeta(redMeta);

        ItemStack blue = new ItemStack(Material.BLUE_WOOL);
        ItemMeta blueMeta = blue.getItemMeta();
        blueMeta.displayName(Component.text("Blue"));
        blue.setItemMeta(blueMeta);

        // Add items to the GUI
        gui.setItem(0, red);
        gui.setItem(1, blue);

        player.openInventory(gui);
    }

    // Handle inventory click event to set the chat color
    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().title().equals(Component.text("Select Chat Color"))) {
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            ItemStack clickedItem = event.getCurrentItem();

            if (clickedItem != null && clickedItem.hasItemMeta()) {
                String color = clickedItem.getItemMeta().displayName().toString();
                // Set the player's chat color (implementation depends on your plugin)
                setPlayerChatColor(player, color);
                player.closeInventory();
                player.sendMessage("Your chat color has been set to " + color);
            }
        }
    }

    private void setPlayerChatColor(Player player, String color) {
        // Implement this method to save the player's chat color
    }
}