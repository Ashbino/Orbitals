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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class OrbitalsPlugin extends JavaPlugin implements Listener, TabExecutor {

	/** Marks an item as an orbital rod (value = type id). */
	private NamespacedKey typeKey;
	/** Stores which mob a Mob Orbital rod drops. */
	private NamespacedKey mobKey;
	/** Marks a summoned mob with the UUID of the player who summoned it. */
	private NamespacedKey ownerKey;

	private Strikes strikes;

	@Override
	public void onEnable() {
		saveDefaultConfig();
		typeKey = new NamespacedKey(this, "orbital_type");
		mobKey = new NamespacedKey(this, "orbital_mob");
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

	private static String pretty(EntityType type) {
		String lower = type.name().toLowerCase(Locale.ROOT).replace('_', ' ');
		return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
	}

	/** mobType is only used for the Mob Orbital. */
	public ItemStack createItem(OrbitalType type, EntityType mobType) {
		ItemStack item = new ItemStack(type.material());
		ItemMeta meta = item.getItemMeta();
		String name = type.displayName();
		if (type == OrbitalType.MOB && mobType != null) {
			name = name + ": " + pretty(mobType);
		}
		meta.displayName(Component.text(name, type.color(), TextDecoration.BOLD)
				.decoration(TextDecoration.ITALIC, false));
		meta.lore(List.of(
				Component.text(type.description(), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
				Component.text("Right-click to fire where you aim. Single use.", NamedTextColor.DARK_GRAY)
						.decoration(TextDecoration.ITALIC, false)));
		meta.setEnchantmentGlintOverride(true);
		meta.getPersistentDataContainer().set(typeKey, PersistentDataType.STRING, type.id());
		if (type == OrbitalType.MOB && mobType != null) {
			meta.getPersistentDataContainer().set(mobKey, PersistentDataType.STRING, mobType.name());
		}
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

	/** Valid mob types are living, spawnable entity types. Returns null if the name is not one. */
	private EntityType parseMobType(String name) {
		if (name == null) {
			return null;
		}
		try {
			EntityType type = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
			return type.isAlive() && type.isSpawnable() ? type : null;
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private EntityType defaultMobType() {
		EntityType type = parseMobType(getConfig().getString("mob.default-type", "ZOMBIE"));
		return type != null ? type : EntityType.ZOMBIE;
	}

	private EntityType mobTypeOf(ItemStack item) {
		String stored = item.getItemMeta().getPersistentDataContainer().get(mobKey, PersistentDataType.STRING);
		EntityType type = parseMobType(stored);
		return type != null ? type : defaultMobType();
	}

	private void give(Player target, ItemStack item) {
		Map<Integer, ItemStack> leftover = target.getInventory().addItem(item);
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
		ItemStack item = event.getItem();
		OrbitalType type = typeOf(item);
		if (type == null) {
			return;
		}
		// Stop the rod from casting a bobber.
		event.setCancelled(true);

		Player player = event.getPlayer();
		double range = Math.max(5.0, getConfig().getDouble("range", 200.0));
		RayTraceResult hit = player.rayTraceBlocks(range);
		if (hit == null || hit.getHitPosition() == null) {
			player.sendActionBar(Component.text("No target in range.", NamedTextColor.RED));
			return;
		}
		Location target = hit.getHitPosition().toLocation(player.getWorld());
		EntityType mobType = type == OrbitalType.MOB ? mobTypeOf(item) : null;

		// Single use: the rod breaks as it fires.
		ItemStack inHand = player.getInventory().getItemInMainHand();
		if (inHand.getAmount() <= 1) {
			player.getInventory().setItemInMainHand(null);
		} else {
			inHand.setAmount(inHand.getAmount() - 1);
		}
		player.getWorld().playSound(player.getLocation(), "entity.item.break", 1.0f, 1.0f);

		strikes.fire(type, player, target, mobType);
	}

	/** Safety net: an orbital rod never casts a real bobber. */
	@EventHandler
	public void onFish(PlayerFishEvent event) {
		Player player = event.getPlayer();
		if (typeOf(player.getInventory().getItemInMainHand()) != null
				|| typeOf(player.getInventory().getItemInOffHand()) != null) {
			event.setCancelled(true);
		}
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

	// ------------------------------------------------------------- commands

	private static Component msg(String text, NamedTextColor color) {
		return Component.text(text, color);
	}

	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission("orbitals.admin")) {
			sender.sendMessage(msg("You do not have permission to do that.", NamedTextColor.RED));
			return true;
		}
		if (args.length == 0) {
			sender.sendMessage(msg("Usage: /orbital give <stab|nuke|wither|all> [player]  |  /orbital give mob <mob> [player]  |  /orbital reload",
					NamedTextColor.GRAY));
			return true;
		}
		if (args[0].equalsIgnoreCase("reload")) {
			reloadConfig();
			sender.sendMessage(msg("Orbitals config reloaded.", NamedTextColor.GOLD));
			return true;
		}
		if (!args[0].equalsIgnoreCase("give") || args.length < 2) {
			sender.sendMessage(msg("Usage: /orbital give <stab|nuke|wither|all> [player]  |  /orbital give mob <mob> [player]",
					NamedTextColor.GRAY));
			return true;
		}

		boolean all = args[1].equalsIgnoreCase("all");
		OrbitalType single = all ? null : OrbitalType.fromId(args[1]);
		if (!all && single == null) {
			sender.sendMessage(msg("Unknown orbital: " + args[1], NamedTextColor.RED));
			return true;
		}

		// For a single Mob Orbital the next argument is the mob; the player comes after it.
		EntityType mobType = defaultMobType();
		int playerArg = 2;
		if (single == OrbitalType.MOB) {
			if (args.length < 3) {
				sender.sendMessage(msg("Pick a mob: /orbital give mob <mob> [player]  (example: /orbital give mob zombie)",
						NamedTextColor.RED));
				return true;
			}
			EntityType chosen = parseMobType(args[2]);
			if (chosen == null) {
				sender.sendMessage(msg("Unknown or unspawnable mob: " + args[2], NamedTextColor.RED));
				return true;
			}
			mobType = chosen;
			playerArg = 3;
		}

		Player target;
		if (args.length > playerArg) {
			target = Bukkit.getPlayerExact(args[playerArg]);
			if (target == null) {
				sender.sendMessage(msg("Player not found: " + args[playerArg], NamedTextColor.RED));
				return true;
			}
		} else if (sender instanceof Player self) {
			target = self;
		} else {
			sender.sendMessage(msg("Console must specify a player.", NamedTextColor.RED));
			return true;
		}

		List<OrbitalType> types = new ArrayList<>();
		if (all) {
			types.addAll(List.of(OrbitalType.values()));
		} else {
			types.add(single);
		}
		for (OrbitalType type : types) {
			give(target, createItem(type, mobType));
		}
		String what = all ? "all orbitals"
				: single == OrbitalType.MOB ? "a Mob Orbital (" + pretty(mobType) + ")" : single.displayName();
		sender.sendMessage(msg("Gave " + what + " to " + target.getName() + ".", NamedTextColor.GOLD));
		return true;
	}

	@Override
	public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
		if (!sender.hasPermission("orbitals.admin")) {
			return List.of();
		}
		List<String> players = Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
		if (args.length == 1) {
			return List.of("give", "reload");
		}
		if (!args[0].equalsIgnoreCase("give")) {
			return List.of();
		}
		if (args.length == 2) {
			return List.of("stab", "nuke", "mob", "wither", "all");
		}
		if (args.length == 3) {
			if (args[1].equalsIgnoreCase("mob")) {
				return Arrays.stream(EntityType.values())
						.filter(t -> t.isAlive() && t.isSpawnable())
						.map(t -> t.name().toLowerCase(Locale.ROOT))
						.toList();
			}
			return players;
		}
		if (args.length == 4 && args[1].equalsIgnoreCase("mob")) {
			return players;
		}
		return List.of();
	}
}
