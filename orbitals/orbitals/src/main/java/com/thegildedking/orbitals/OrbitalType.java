package com.thegildedking.orbitals;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

public enum OrbitalType {
	STAB("stab", Material.NETHERITE_SWORD, "Stab Orbital", NamedTextColor.RED,
			"Calls down a piercing beam that skewers everything where you aim."),
	NUKE("nuke", Material.NETHER_STAR, "Nuke Orbital", NamedTextColor.GOLD,
			"Marks the target, then detonates a huge blast from orbit."),
	MOB("mob", Material.BONE, "Mob Orbital", NamedTextColor.GREEN,
			"Drops a swarm of mobs from the sky onto the target."),
	WITHER("wither", Material.BLAZE_ROD, "Wither Cannon Orbital", NamedTextColor.DARK_GRAY,
			"Rains wither skulls down on the target.");

	private final String id;
	private final Material material;
	private final String displayName;
	private final NamedTextColor color;
	private final String description;

	OrbitalType(String id, Material material, String displayName, NamedTextColor color, String description) {
		this.id = id;
		this.material = material;
		this.displayName = displayName;
		this.color = color;
		this.description = description;
	}

	public String id() {
		return id;
	}

	public Material material() {
		return material;
	}

	public String displayName() {
		return displayName;
	}

	public NamedTextColor color() {
		return color;
	}

	public String description() {
		return description;
	}

	public static OrbitalType fromId(String id) {
		if (id == null) {
			return null;
		}
		for (OrbitalType type : values()) {
			if (type.id.equalsIgnoreCase(id)) {
				return type;
			}
		}
		return null;
	}
}
