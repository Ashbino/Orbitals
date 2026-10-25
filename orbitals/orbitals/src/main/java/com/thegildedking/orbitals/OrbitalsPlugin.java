package com.thegildedking.orbitals;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OrbitalsPlugin extends JavaPlugin implements Listener, TabExecutor {

	/** Marks an item as an orbital item (value = type id). */
	private NamespacedKey typeKey;
	/** Marks a summoned mob with the UUID of the player who summoned it. */
	private NamespacedKey ownerKey;

	private Strikes strikes;
	private final Map<UUID, Map<OrbitalType, Long>> cooldowns = new HashMap<>();

	@Override
	public void onEnable() {
		saveDefaultConfig();
		typeKey = new NamespacedKey(this, "orbital_type");
		ownerKey = new NamespacedKey(this, "orbital_owner");
		strikes = new Strikes(this);
		getServer().getPluginManager().registerEvents(this, this);
		if (getCommand("orbital") != null) {
			getCommand("orbital").setExecutor(this);
			getCommand("orbital").setTabCompleter(this);
		}
		getLogger().info("Orbitals enabled.");
	}

	public NamespacedKey ownerKey() {
		return ownerKey;
	}

	// ---------------------------------------------------------------- items

	public ItemStack createItem(OrbitalType type) {
		ItemStack item = new ItemStack(type.material());
		ItemMeta meta = item.getItemMeta();
		meta.displayName(Component.text(type.displayName(), type.color(), TextDecoration.BOLD)
				.decoration(TextDecoration.ITALIC, false));
		meta.lore(List.of(
				Component.text(type.description(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
				Component.text("Right-click to fire where you aim.", NamedTextColor.DARK_GRAY)
						.decoration(TextDecoration.ITALIC, false)));
		meta.setEnchantmentGlintOverride(true);
		meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
		item.setItemMeta(meta);
		return item;
	}

	private OrbitalType typeOf(ItemStack item) {
		if (item == null || !item.hasItemMeta()) {
			return null;
		}
		String id = item.getItemMeta().getPersistentDataContainer().get(typeKey, PersistentDataType.STRING);
		return OrbitalType.fromId(id);
	}

	private void give(Player target, OrbitalType type) {
		Map<Integer, ItemStack> leftover = target.getInventory().addItem(createItem(type));
		for (ItemStack extra : leftover.values()) {
			target.getWorld().dropItem(target.getLocation(), extra);
		}
	}

	// --------------------------------------------------------------- firing

	@EventHandler
	public void onInteract(PlayerInteractEvent event) {
		if (event.getHand() != EquipmentSlot.HAND) {
			return;
		}
		Action action = event.getAction();
		if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
			return;
		}
		OrbitalType type = typeOf(event.getItem());
		if (type == null) {
			return;
		}
		event.setCancelled(true);

		Player player = event.getPlayer();
		long now = System.currentTimeMillis();
		Map<OrbitalType, Long> mine = cooldowns.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(OrbitalType.class));
		long readyAt = mine.getOrDefault(type, 0L);
		if (now < readyAt) {
			long seconds = (readyAt - now + 999) / 1000;
			player.sendActionBar(Component.text(type.displayName() + " recharging: " + seconds + "s", NamedTextColor.RED));
			return;
		}

		double range = Math.max(5.0, getConfig().getDouble("range", 200.0));
		RayTraceResult hit = player.rayTraceBlocks(range);
		if (hit == null || hit.getHitPosition() == null) {
			player.sendActionBar(Component.text("No target in range.", NamedTextColor.RED));
			return;
		}
		Location target = hit.getHitPosition().toLocation(player.getWorld());

		long cooldownMs = (long) (getConfig().getDouble("cooldowns." + type.id(), 10.0) * 1000L);
		mine.put(type, now + cooldownMs);

		player.sendActionBar(Component.text(type.displayName() + " locked on.", type.color()));
		strikes.fire(type, player, target);
	}

	/** Mobs summoned by the mob orbital will not attack the player who called them. */
	@EventHandler(ignoreCancelled = true)
	public void onTarget(EntityTargetLivingEntityEvent event) {
		LivingEntity target = event.getTarget();
		if (target == null) {
			return;
		}
		String owner = event.getEntity().getPersistentDataContainer().get(ownerKey, PersistentDataType.STRING);
		if (owner != null && owner.equals(target.getUniqueId().toString())) {
			event.setCancelled(true);
		}
	}

	@EventHandler
	public void onQuit(PlayerQuitEvent event) {
		cooldowns.remove(event.getPlayer().getUniqueId());
	}

	// ------------------------------------------------------------- commands

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission("orbitals.admin")) {
			sender.sendMessage(Component.text("You do not have permission to do that.", NamedTextColor.RED));
			return true;
		}
		if (args.length == 0) {
			sender.sendMessage(Component.text("Usage: /orbital <give <stab|nuke|mob|wither|all> [player] | reload>",
					NamedTextColor.GRAY));
			return true;
		}
		if (args[0].equalsIgnoreCase("reload")) {
			reloadConfig();
			sender.sendMessage(Component.text("Orbitals config reloaded.", NamedTextColor.GOLD));
			return true;
		}
		if (!args[0].equalsIgnoreCase("give") || args.length < 2) {
			sender.sendMessage(Component.text("Usage: /orbital give <stab|nuke|mob|wither|all> [player]",
					NamedTextColor.GRAY));
			return true;
		}

		List<OrbitalType> types = new ArrayList<>();
		if (args[1].equalsIgnoreCase("all")) {
			types.addAll(List.of(OrbitalType.values()));
		} else {
			OrbitalType type = OrbitalType.fromId(args[1]);
			if (type == null) {
				sender.sendMessage(Component.text("Unknown orbital: " + args[1], NamedTextColor.RED));
				return true;
			}
			types.add(type);
		}

		Player target;
		if (args.length > 2) {
			target = Bukkit.getPlayerExact(args[2]);
			if (target == null) {
				sender.sendMessage(Component.text("Player not found: " + args[2], NamedTextColor.RED));
				return true;
			}
		} else if (sender instanceof Player self) {
			target = self;
		} else {
			sender.sendMessage(Component.text("Console must specify a player.", NamedTextColor.RED));
			return true;
		}

		for (OrbitalType type : types) {
			give(target, type);
		}
		sender.sendMessage(Component.text("Gave " + (types.size() == 1 ? types.get(0).displayName() : "all orbitals")
				+ " to " + target.getName() + ".", NamedTextColor.GOLD));
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!sender.hasPermission("orbitals.admin")) {
			return List.of();
		}
		if (args.length == 1) {
			return List.of("give", "reload");
		}
		if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
			return List.of("stab", "nuke", "mob", "wither", "all");
		}
		if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
			return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
		}
		return List.of();
	}
}
