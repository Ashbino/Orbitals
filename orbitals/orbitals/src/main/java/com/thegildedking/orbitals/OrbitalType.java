package com.thegildedking.orbitals;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;

/** All orbitals are fishing rods; the name and colour tell them apart. */
public enum OrbitalType {
	STAB("stab", "Stab Orbital", NamedTextColor.RED,
			"Drills a TNT shaft straight down to bedrock."),
	NUKE("nuke", "Nuke Orbital", NamedTextColor.GOLD,
			"Rains rings of TNT from the sky onto the target."),
	MOB("mob", "Mob Orbital", NamedTextColor.GREEN,
			"Drops a swarm of mobs from the sky onto the target."),
	WITHER("wither", "Wither Cannon Orbital", NamedTextColor.DARK_GRAY,
			"Rains blue wither skulls down on the target.");

	private final String id;
	private final String displayName;
	private final NamedTextColor color;
	private final String description;

	OrbitalType(String id, String displayName, NamedTextColor color, String description) {
		this.id = id;
		this.displayName = displayName;
		this.color = color;
		this.description = description;
	}

	public String id() {
		return id;
	}

	public Material material() {
		return Material.FISHING_ROD;
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
