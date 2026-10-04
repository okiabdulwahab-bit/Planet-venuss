package com.venus;

import io.papermc.paper.event.player.PlayerShieldDisableEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class VenusPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private NamespacedKey itemKey;
    private NamespacedKey heartsKey;

    /** player -> time (ms) when the shield protection ends */
    private final Map<UUID, Long> activeUntil = new HashMap<>();
    /** player -> time (ms) when the ability can be used again */
    private final Map<UUID, Long> cooldownUntil = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();

        itemKey = new NamespacedKey(this, "venus_item");
        heartsKey = new NamespacedKey(this, "venus_hearts");

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("venus1").setExecutor(this);
        getCommand("venus").setExecutor(this);
        getCommand("venus").setTabCompleter(this);

        // Checks inventories (passive) and handles the ability timer every half second
        getServer().getScheduler().runTaskTimer(this, this::tick, 20L, 10L);
    }

    @Override
    public void onDisable() {
        // Don't leave permanent extra hearts behind if the plugin is removed
        for (Player player : getServer().getOnlinePlayers()) {
            updateHearts(player, false);
        }
        activeUntil.clear();
    }

    // ------------------------------------------------------------------
    // Passive + ability timer
    // ------------------------------------------------------------------

    private void tick() {
        long now = System.currentTimeMillis();

        for (Player player : getServer().getOnlinePlayers()) {
            updateHearts(player, hasVenusItem(player));

            Long until = activeUntil.get(player.getUniqueId());
            if (until != null) {
                if (until > now) {
                    long secondsLeft = (long) Math.ceil((until - now) / 1000.0);
                    player.sendActionBar(msg("messages.actionbar", "%seconds%", String.valueOf(secondsLeft)));
                } else {
                    activeUntil.remove(player.getUniqueId());
                    player.sendActionBar(msg("messages.ended"));
                }
            }
        }
    }

    private void updateHearts(Player player, boolean has) {
        AttributeInstance attr = player.getAttribute(Attribute.MAX_HEALTH);
        if (attr == null) return;

        AttributeModifier existing = null;
        for (AttributeModifier mod : attr.getModifiers()) {
            if (heartsKey.equals(mod.getKey())) {
                existing = mod;
                break;
            }
        }

        if (has) {
            double amount = getConfig().getDouble("passive.extra-hearts", 3.0) * 2.0;
            if (existing != null && existing.getAmount() == amount) return;
            if (existing != null) attr.removeModifier(existing);
            attr.addModifier(new AttributeModifier(heartsKey, amount, AttributeModifier.Operation.ADD_NUMBER));
        } else if (existing != null) {
            attr.removeModifier(existing);
            double max = attr.getValue();
            if (player.getHealth() > max) {
                player.setHealth(max);
            }
        }
    }

    // ------------------------------------------------------------------
    // Shield protection
    // ------------------------------------------------------------------

    private boolean isActive(Player player) {
        Long until = activeUntil.get(player.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    /** Axes (and anything else) can't disable the shield while the ability is active. */
    @EventHandler(ignoreCancelled = true)
    public void onShieldDisable(PlayerShieldDisableEvent event) {
        if (isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** The shield loses no durability while the ability is active. */
    @EventHandler(ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (event.getItem().getType() == Material.SHIELD && isActive(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Cooldown is kept so relogging can't be used to skip it
        activeUntil.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // Commands
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("venus1")) {
            return handleAbility(sender);
        }
        return handleAdmin(sender, args);
    }

    private boolean handleAbility(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("messages.players-only"));
            return true;
        }
        if (!player.hasPermission("venus.use")) {
            player.sendMessage(msg("messages.no-permission"));
            return true;
        }
        if (!hasVenusItem(player)) {
            player.sendMessage(msg("messages.need-item"));
            return true;
        }

        long now = System.currentTimeMillis();
        Long readyAt = cooldownUntil.get(player.getUniqueId());
        if (readyAt != null && readyAt > now) {
            long secondsLeft = (long) Math.ceil((readyAt - now) / 1000.0);
            player.sendMessage(msg("messages.on-cooldown", "%seconds%", String.valueOf(secondsLeft)));
            return true;
        }

        int duration = getConfig().getInt("ability.duration", 5);
        int cooldown = getConfig().getInt("ability.cooldown", 25);

        activeUntil.put(player.getUniqueId(), now + duration * 1000L);
        cooldownUntil.put(player.getUniqueId(), now + cooldown * 1000L);

        // If the shield is currently disabled from an axe hit, bring it back
        player.setCooldown(Material.SHIELD, 0);

        player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.2f);
        player.sendMessage(msg("messages.activated", "%seconds%", String.valueOf(duration)));
        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("venus.admin")) {
            sender.sendMessage(msg("messages.no-permission"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /venus <give|reload> [player]"));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> {
                reloadConfig();
                sender.sendMessage(Component.text("Venus config reloaded."));
            }
            case "give" -> {
                Player target;
                if (args.length >= 2) {
                    target = getServer().getPlayerExact(args[1]);
                } else if (sender instanceof Player p) {
                    target = p;
                } else {
                    target = null;
                }
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found."));
                    return true;
                }
                target.getInventory().addItem(createVenusItem()).values()
                        .forEach(left -> target.getWorld().dropItemNaturally(target.getLocation(), left));
                sender.sendMessage(Component.text("Gave the Venus item to " + target.getName() + "."));
            }
            default -> sender.sendMessage(Component.text("Usage: /venus <give|reload> [player]"));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!command.getName().equalsIgnoreCase("venus") || !sender.hasPermission("venus.admin")) {
            return out;
        }
        if (args.length == 1) {
            for (String s : List.of("give", "reload")) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            for (Player p : getServer().getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Item helpers
    // ------------------------------------------------------------------

    private ItemStack createVenusItem() {
        Material material = Material.matchMaterial(getConfig().getString("item.material", "GLOWSTONE_DUST"));
        if (material == null || !material.isItem()) {
            material = Material.GLOWSTONE_DUST;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(LEGACY.deserialize(getConfig().getString("item.name", "&6&lVenus"))
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        for (String line : getConfig().getStringList("item.lore")) {
            lore.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        if (!lore.isEmpty()) meta.lore(lore);

        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean hasVenusItem(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.hasItemMeta()
                    && item.getItemMeta().getPersistentDataContainer().has(itemKey, PersistentDataType.BYTE)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Message helper
    // ------------------------------------------------------------------

    private Component msg(String path, String... replacements) {
        String text = getConfig().getString(path, "");
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        return LEGACY.deserialize(text);
    }
}
