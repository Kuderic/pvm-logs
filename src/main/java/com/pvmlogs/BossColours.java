package com.pvmlogs;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The colour a boss's name is drawn in at the head of the fight overlay
 * (Eric, 2026-10-28: "color the boss name so that it stands out ... a boss
 * themed color that's easy to see"). Each is themed on the boss (Vardorvis's
 * blood, Zulrah's venom, Duke Sucellus's frost) and light enough to read on
 * RuneLite's overlay background. None is a colour the overlay gives meaning to:
 * Better's blue, Cost's orange, the warning's yellow, Dead's red. A raid's
 * rooms take the raid's colour. A boss with no colour of its own takes
 * {@link #ACCENT}; anything that isn't a boss stays white.
 * <p>
 * Names are matched as the hiscores and the plugin's rooms spell them, without
 * case or apostrophes. Eric picked the first twenty from a mockup on
 * 2026-10-28; the rest follow them.
 */
final class BossColours
{
	/** A boss with no themed colour: a soft parchment gold, apart from the warning's yellow. */
	static final int ACCENT = 0xE6D6A8;

	private static final int CHAMBERS = 0x9BD86B;
	private static final int THEATRE = 0xE8609A;
	private static final int TOMBS = 0xE6C88C;

	private static final Map<String, Integer> COLOURS = build();

	private BossColours()
	{
	}

	/**
	 * The colour for a boss's or a raid's name, {@link #ACCENT} for a boss with
	 * none of its own, or -1 for anything else (drawn white, as before).
	 */
	static int of(String name)
	{
		final Integer themed = COLOURS.get(key(name));
		if (themed != null)
		{
			return themed;
		}
		return PvmPerformancePlugin.isBossName(name) ? ACCENT : -1;
	}

	/** A raid's colour, for its rooms; -1 outside a raid. */
	static int of(RaidType raid)
	{
		return raid == null ? -1 : of(raid.getDisplayName());
	}

	private static String key(String name)
	{
		return name == null ? "" : name.toLowerCase(Locale.ROOT).replace("'", "").replace("’", "").trim();
	}

	private static Map<String, Integer> build()
	{
		final Map<String, Integer> m = new HashMap<>();
		// Eric's twenty (2026-10-28 mockup).
		put(m, 0xFF66B2, "Vardorvis");
		put(m, 0x9DA8FF, "Duke Sucellus");
		put(m, 0x52D6B0, "The Leviathan");
		put(m, 0xC08CFF, "The Whisperer");
		put(m, 0x7CFC5A, "Zulrah");
		put(m, 0xB8D0FF, "Vorkath");
		put(m, 0xE07CE0, "Abyssal Sire");
		put(m, 0x6FA8FF, "Kraken");
		put(m, 0xC8C0E8, "Grotesque Guardians");
		put(m, 0xB39DFF, "The Royal Titans");
		put(m, 0x9FD0FF, "Corporeal Beast");
		put(m, 0xB070FF, "Nex");
		put(m, 0xD070D0, "The Nightmare", "Nightmare", "Phosani's Nightmare");
		put(m, 0x50D890, "The Hueycoatl");
		put(m, 0xFF6FA0, "Yama");
		put(m, 0x9CE66A, "Araxxor");
		put(m, 0xA8E85A, "Alchemical Hydra");
		put(m, CHAMBERS, "Chambers of Xeric", "Chambers of Xeric: Challenge Mode");
		put(m, THEATRE, "Theatre of Blood", "Theatre of Blood: Hard Mode");
		put(m, TOMBS, "Tombs of Amascut", "Tombs of Amascut: Expert Mode");
		// The rest of the hiscores' bosses, in the same spirit.
		put(m, 0xA0C8FF, "Amoxliatl");
		put(m, 0xD8B890, "Artio", "Callisto", "Brutus");
		put(m, 0xC8C0A0, "Barrows Chests");
		put(m, 0x8CD87A, "Bryophyta", "Hespori");
		put(m, 0xB8A0E8, "Calvarion", "Vetion");
		put(m, 0xE890C8, "Cerberus", "Doom of Mokhaiotl");
		put(m, 0xD890FF, "Chaos Elemental", "Chaos Fanatic");
		put(m, 0x8FB8FF, "Commander Zilyana");
		put(m, 0xC8B4E0, "Crazy Archaeologist", "Deranged Archaeologist", "Scorpia");
		put(m, 0x7AB8E8, "Dagannoth Prime", "Tempoross");
		put(m, 0xA8D890, "Dagannoth Rex");
		put(m, 0xD8C890, "Dagannoth Supreme", "Shellbane Gryphon");
		put(m, 0xC8C0E8, "The Fractured Archive", "Zalcano");
		put(m, 0x9BD86B, "General Graardor");
		put(m, 0xC8B48C, "Giant Mole", "Obor", "Scurrius");
		put(m, 0xA8E85A, "Kalphite Queen");
		put(m, 0xC8C8D8, "King Black Dragon", "Thermonuclear Smoke Devil");
		put(m, 0xE8E4C0, "Kreearra");
		put(m, 0xE87AB8, "Kril Tsutsaroth", "Skotizo", "TzKal-Zuk", "TzTok-Jad");
		put(m, 0xA8B8FF, "Lunar Chests");
		put(m, 0xA890FF, "Phantom Muspah");
		put(m, 0x9CE66A, "Sarachnis", "Spindel", "Venenatis");
		put(m, TOMBS, "Sol Heredit");
		put(m, 0xB8E0F0, "The Gauntlet");
		put(m, THEATRE, "The Corrupted Gauntlet");
		put(m, 0xB8D0FF, "Wintertodt");
		return Collections.unmodifiableMap(m);
	}

	private static void put(Map<String, Integer> m, int rgb, String... names)
	{
		for (String name : names)
		{
			m.put(key(name), rgb);
		}
	}
}
