package com.thegildedking.orbitals;

import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.WitherSkull;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * The orbital strikes, modelled on Wemmbu's orbital strike cannon:
 * stab = a TNT shaft drilled straight down to bedrock,
 * nuke = rings of TNT falling from the sky and going off in a wave.
 * There is no build-up animation: everything happens the moment you fire.
 */
final class Strikes {

	private final OrbitalsPlugin plugin;
	private final Random random = new Random();

	Strikes(OrbitalsPlugin plugin) {
		this.plugin = plugin;
	}

	void fire(OrbitalType type, Player owner, Location target, EntityType mobType) {
		Location t = target.clone();
		switch (type) {
			case STAB -> stab(owner, t);
			case NUKE -> nuke(owner, t);
			case MOB -> mobs(owner, t, mobType);
			case WITHER -> wither(owner, t);
		}
	}

	private TNTPrimed spawnTnt(World world, Location at, int fuse, Player owner) {
		return world.spawn(at, TNTPrimed.class, tnt -> {
			tnt.setFuseTicks(fuse);
			tnt.setSource(owner);
		});
	}

	// ----------------------------------------------------------------- stab

	/** Drops short-fuse TNT down a single column from the target to the bottom of the world. */
	private void stab(Player owner, Location t) {
		World world = t.getWorld();
		int spacing = Math.max(1, plugin.getConfig().getInt("stab.spacing", 3));
		int count = Math.max(1, plugin.getConfig().getInt("stab.tnt-count", 5));
		int perTick = Math.max(1, plugin.getConfig().getInt("stab.steps-per-tick", 3));
		int bottom = world.getMinHeight();

		world.playSound(t, Sound.ENTITY_GENERIC_EXPLODE, 4.0f, 0.6f);
		new BukkitRunnable() {
			int y = t.getBlockY() + 1;

			@Override
			public void run() {
				for (int i = 0; i < perTick; i++) {
					if (y <= bottom) {
						cancel();
						return;
					}
					Location at = new Location(world, t.getX(), y, t.getZ());
					for (int j = 0; j < count; j++) {
						spawnTnt(world, at, 1, owner);
					}
					y -= spacing;
				}
			}
		}.runTaskTimer(plugin, 0L, 1L);
	}

	// ----------------------------------------------------------------- nuke

	/**
	 * A layered carpet of TNT. Each layer is a set of concentric rings (plus a dense centre cluster) that
	 * spawns high above the target, a few blocks higher than the layer before. Every TNT is set off the
	 * moment it lands, so each layer goes off as a wave from the centre outwards, and later layers fall
	 * into the crater the earlier ones dug, making it deeper.
	 */
	private void nuke(Player owner, Location t) {
		World world = t.getWorld();
		int rings = Math.max(1, plugin.getConfig().getInt("nuke.ring-count", 12));
		double ringGap = Math.max(1.0, plugin.getConfig().getDouble("nuke.ring-gap", 3.0));
		double tntSpacing = Math.max(1.0, plugin.getConfig().getDouble("nuke.tnt-spacing", 3.0));
		double dropHeight = plugin.getConfig().getDouble("nuke.drop-height", 70.0);
		int layers = Math.max(1, plugin.getConfig().getInt("nuke.layers", 2));
		double layerGap = plugin.getConfig().getDouble("nuke.layer-gap", 8.0);
		int layerDelay = Math.max(1, plugin.getConfig().getInt("nuke.layer-delay-ticks", 10));
		int center = Math.max(1, plugin.getConfig().getInt("nuke.center-count", 40));
		int maxFuse = Math.max(40, plugin.getConfig().getInt("nuke.max-fuse", 200));
		int lastSpawnTick = (layers - 1) * layerDelay + rings * 2;

		world.playSound(t, Sound.ENTITY_WITHER_SPAWN, 4.0f, 0.5f);

		List<TNTPrimed> falling = new ArrayList<>();
		new BukkitRunnable() {
			int tick = 0;

			@Override
			public void run() {
				// Spawn the next ring of each layer that is currently active (one ring every 2 ticks).
				for (int layer = 0; layer < layers; layer++) {
					int layerTick = tick - layer * layerDelay;
					if (layerTick < 0 || layerTick % 2 != 0) {
						continue;
					}
					int ring = layerTick / 2;
					if (ring > rings) {
						continue;
					}
					double topY = Math.min(t.getY() + dropHeight + layer * layerGap, world.getMaxHeight() - 2);
					if (ring == 0) {
						for (int i = 0; i < center; i++) {
							Location at = new Location(world, t.getX() + (random.nextDouble() - 0.5) * 2.0, topY,
									t.getZ() + (random.nextDouble() - 0.5) * 2.0);
							falling.add(spawnTnt(world, at, maxFuse, owner));
						}
					} else {
						double radius = ring * ringGap;
						int n = Math.max(6, (int) Math.round(2 * Math.PI * radius / tntSpacing));
						double offset = random.nextDouble() * 2 * Math.PI;
						for (int i = 0; i < n; i++) {
							double angle = offset + 2 * Math.PI * i / n;
							Location at = new Location(world, t.getX() + Math.cos(angle) * radius, topY,
									t.getZ() + Math.sin(angle) * radius);
							falling.add(spawnTnt(world, at, maxFuse, owner));
						}
					}
				}
				tick++;

				// Detonate anything that has landed.
				Iterator<TNTPrimed> it = falling.iterator();
				while (it.hasNext()) {
					TNTPrimed tnt = it.next();
					if (!tnt.isValid()) {
						it.remove();
					} else if (tnt.getTicksLived() > 8 && tnt.isOnGround()) {
						tnt.setFuseTicks(1);
						it.remove();
					}
				}

				if (tick > lastSpawnTick && falling.isEmpty()) {
					cancel();
				}
			}
		}.runTaskTimer(plugin, 0L, 1L);
	}

	// ------------------------------------------------------------------ mob

	private void mobs(Player owner, Location t, EntityType type) {
		World world = t.getWorld();
		int count = plugin.getConfig().getInt("mob.amount", 25);
		double spread = plugin.getConfig().getDouble("mob.spread", 5.0);
		int lifetime = plugin.getConfig().getInt("mob.lifetime-seconds", 60);
		EntityType mobType = type != null ? type : EntityType.ZOMBIE;

		List<LivingEntity> spawned = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			double angle = random.nextDouble() * 2 * Math.PI;
			double distance = random.nextDouble() * spread;
			double y = Math.min(t.getY() + 35, world.getMaxHeight() - 2);
			Location at = new Location(world, t.getX() + Math.cos(angle) * distance, y,
					t.getZ() + Math.sin(angle) * distance);
			Entity entity = world.spawnEntity(at, mobType);
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
	}

	// --------------------------------------------------------------- wither

	/** Blue wither skulls appear in a sphere high above the target, then all fly in at it. */
	private void wither(Player owner, Location t) {
		World world = t.getWorld();
		int count = Math.max(1, plugin.getConfig().getInt("wither.skull-count", 100));
		double radius = plugin.getConfig().getDouble("wither.sphere-size", 9.0);
		double height = plugin.getConfig().getDouble("wither.hover-height", 35.0);
		float yield = (float) plugin.getConfig().getDouble("wither.yield", 2.5);

		double centerY = Math.min(t.getY() + height, world.getMaxHeight() - radius - 2);
		Location center = new Location(world, t.getX(), centerY, t.getZ());
		double goldenAngle = Math.PI * (3.0 - Math.sqrt(5.0));

		for (int i = 0; i < count; i++) {
			// Evenly spaced points on a sphere (Fibonacci sphere).
			double y = count == 1 ? 0.0 : 1.0 - 2.0 * i / (count - 1);
			double ring = Math.sqrt(Math.max(0.0, 1.0 - y * y));
			double theta = goldenAngle * i;
			Location from = center.clone().add(Math.cos(theta) * ring * radius, y * radius,
					Math.sin(theta) * ring * radius);

			Location aim = t.clone().add((random.nextDouble() - 0.5) * 2, 0, (random.nextDouble() - 0.5) * 2);
			Vector direction = aim.toVector().subtract(from.toVector()).normalize();

			WitherSkull skull = world.spawn(from, WitherSkull.class, s -> {
				s.setShooter(owner);
				s.setCharged(true);
				s.setDirection(direction);
				s.setYield(yield);
				s.setVelocity(direction.clone().multiply(1.2));
			});
			skull.setCharged(true); // blue skull
		}
		world.playSound(center, Sound.ENTITY_WITHER_SHOOT, 4.0f, 0.7f);
	}
}
