package com.thegildedking.orbitals;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.WitherSkull;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** The actual orbital strike effects. */
final class Strikes {

	private final OrbitalsPlugin plugin;
	private final Random random = new Random();

	Strikes(OrbitalsPlugin plugin) {
		this.plugin = plugin;
	}

	void fire(OrbitalType type, Player owner, Location target) {
		Location t = target.clone();
		switch (type) {
			case STAB -> stab(owner, t);
			case NUKE -> nuke(owner, t);
			case MOB -> mobs(owner, t);
			case WITHER -> wither(owner, t);
		}
	}

	/** Draws a shrinking ring and a beam to the sky, then runs onDone. Lasts ticks (rounded to 2). */
	private void countdown(Location target, int ticks, Runnable onDone) {
		final int steps = Math.max(1, ticks / 2);
		new BukkitRunnable() {
			int step = 0;

			@Override
			public void run() {
				World world = target.getWorld();
				double progress = (double) step / steps;
				double radius = 0.6 + 2.4 * (1.0 - progress);
				for (int i = 0; i < 24; i++) {
					double angle = 2 * Math.PI * i / 24;
					world.spawnParticle(Particle.END_ROD,
							target.getX() + Math.cos(angle) * radius, target.getY() + 0.2,
							target.getZ() + Math.sin(angle) * radius, 1, 0, 0, 0, 0);
				}
				for (double y = 0; y < 40; y += 2) {
					world.spawnParticle(Particle.END_ROD, target.getX(), target.getY() + y, target.getZ(),
							1, 0, 0, 0, 0);
				}
				if (step % 5 == 0) {
					world.playSound(target, Sound.BLOCK_NOTE_BLOCK_PLING, 2.0f, (float) (0.5 + progress * 1.5));
				}
				step++;
				if (step >= steps) {
					cancel();
					onDone.run();
				}
			}
		}.runTaskTimer(plugin, 0L, 2L);
	}

	// ----------------------------------------------------------------- stab

	private void stab(Player owner, Location t) {
		countdown(t, 20, () -> {
			World world = t.getWorld();
			double damage = plugin.getConfig().getDouble("stab.damage", 40.0);
			double radius = plugin.getConfig().getDouble("stab.radius", 3.0);
			boolean hurtOwner = plugin.getConfig().getBoolean("hurt-owner", false);

			world.strikeLightningEffect(t);
			world.playSound(t, Sound.ITEM_TRIDENT_THUNDER, 3.0f, 1.0f);
			world.spawnParticle(Particle.CRIT, t, 80, 0.6, 1.0, 0.6, 0.4);

			for (LivingEntity entity : world.getNearbyLivingEntities(t, radius, 6, radius)) {
				if (entity instanceof ArmorStand) {
					continue;
				}
				if (entity.equals(owner) && !hurtOwner) {
					continue;
				}
				entity.damage(damage, owner);
				entity.setVelocity(new Vector(0, 0.9, 0));
			}
		});
	}

	// ----------------------------------------------------------------- nuke

	private void nuke(Player owner, Location t) {
		int warningTicks = (int) (plugin.getConfig().getDouble("nuke.warning-seconds", 3.0) * 20);
		countdown(t, warningTicks, () -> {
			World world = t.getWorld();
			float power = (float) plugin.getConfig().getDouble("nuke.power", 20.0);
			boolean breakBlocks = plugin.getConfig().getBoolean("nuke.break-blocks", true);
			boolean fire = plugin.getConfig().getBoolean("nuke.set-fire", false);

			world.createExplosion(t, power, fire, breakBlocks, owner);
			world.spawnParticle(Particle.EXPLOSION_EMITTER, t, 6, 3, 1, 3, 0);
			world.playSound(t, Sound.ENTITY_GENERIC_EXPLODE, 6.0f, 0.5f);
			world.playSound(t, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 6.0f, 0.6f);
		});
	}

	// ------------------------------------------------------------------ mob

	private void mobs(Player owner, Location t) {
		countdown(t, 30, () -> {
			World world = t.getWorld();
			int count = plugin.getConfig().getInt("mob.count", 15);
			double spread = plugin.getConfig().getDouble("mob.spread", 5.0);
			int lifetime = plugin.getConfig().getInt("mob.lifetime-seconds", 60);

			List<EntityType> types = new ArrayList<>();
			for (String name : plugin.getConfig().getStringList("mob.types")) {
				try {
					types.add(EntityType.valueOf(name.toUpperCase()));
				} catch (IllegalArgumentException ex) {
					plugin.getLogger().warning("Unknown mob type in config: " + name);
				}
			}
			if (types.isEmpty()) {
				types.add(EntityType.ZOMBIE);
			}

			List<LivingEntity> spawned = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				double angle = random.nextDouble() * 2 * Math.PI;
				double distance = random.nextDouble() * spread;
				double y = Math.min(t.getY() + 35, world.getMaxHeight() - 2);
				Location at = new Location(world, t.getX() + Math.cos(angle) * distance, y,
						t.getZ() + Math.sin(angle) * distance);
				Entity entity = world.spawnEntity(at, types.get(random.nextInt(types.size())));
				if (entity instanceof LivingEntity living) {
					living.getPersistentDataContainer().set(plugin.ownerKey(), PersistentDataType.STRING,
							owner.getUniqueId().toString());
					living.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 20 * 15, 0));
					spawned.add(living);
				}
			}
			world.playSound(t, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 4.0f, 0.7f);

			plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
				for (LivingEntity living : spawned) {
					if (living.isValid()) {
						living.remove();
					}
				}
			}, lifetime * 20L);
		});
	}

	// --------------------------------------------------------------- wither

	private void wither(Player owner, Location t) {
		countdown(t, 40, () -> {
			World world = t.getWorld();
			int skulls = plugin.getConfig().getInt("wither.skulls", 14);
			double spread = plugin.getConfig().getDouble("wither.spread", 6.0);
			float yield = (float) plugin.getConfig().getDouble("wither.yield", 2.5);

			new BukkitRunnable() {
				int fired = 0;

				@Override
				public void run() {
					if (fired >= skulls) {
						cancel();
						return;
					}
					fired++;
					double aimX = t.getX() + (random.nextDouble() * 2 - 1) * spread;
					double aimZ = t.getZ() + (random.nextDouble() * 2 - 1) * spread;
					Location aim = new Location(world, aimX, t.getY(), aimZ);
					Location from = new Location(world, aimX + (random.nextDouble() - 0.5) * 8,
							Math.min(t.getY() + 35, world.getMaxHeight() - 2),
							aimZ + (random.nextDouble() - 0.5) * 8);
					Vector direction = aim.toVector().subtract(from.toVector()).normalize();

					world.spawn(from, WitherSkull.class, skull -> {
						skull.setShooter(owner);
						skull.setCharged(true);
						skull.setDirection(direction);
						skull.setYield(yield);
						skull.setVelocity(direction.clone().multiply(1.2));
					});
					world.playSound(from, Sound.ENTITY_WITHER_SHOOT, 2.0f, 0.8f);
				}
			}.runTaskTimer(plugin, 0L, 3L);
		});
	}
}
