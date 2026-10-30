package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-110, R1: what a saved fight needs beyond its luck and execution sums to
 * be read again later - the build that scored it, the weapons used with their
 * own sums, the setup, the context and each mechanic window - recorded
 * locally, for the calibration report now and, if uploads ever happen, as the
 * whitelist an upload would copy (claude/specs/global-logs.md, section 3.3).
 *
 * <p>One nested object on {@link Fight}, so a history.json saved before has
 * none (null) and loads as it always did, and a later upload copies this
 * object and nothing else of the fight. Nothing here names the account: no
 * account hash, no name, no world number. The install id lives in its own
 * file ({@link InstallId}), not here.
 *
 * <p>Filled from events the plugin already handles: the fight's start, its
 * first attack, its bookings and its end, and the game's kill messages. No
 * per-tick work.
 */
final class FightContext
{
	/** T-096's build line: {@code <commit> (<branch>, clean)} or {@code release <version>}. */
	String build;
	/** boss-rules.json's {@code portedFrom} commit and short hashes of the bundled rules and any override in the data folder. */
	String rules;
	/** The DPS calc's monsters.json data in use, as the provider names it; null when unknown. */
	String monsters;

	/** Per weapon, or spell for magic, and its special apart: the timeline's rows summed at the fight's end. */
	Map<String, Weapon> weapons;
	/** The special-attack weapons used, by name, in the order first used. */
	List<String> specWeapons;
	/** The ammunition or autocast spells used, by name, in the order first used (at most {@link #LIST_CAP}). */
	List<String> ammoOrSpell;
	/** The item ids worn at the fight's first attack, sorted: a list, so it can be read back. */
	int[] armour;
	/** Attack, Strength, Defence, Ranged, Magic, Hitpoints, Prayer, as real levels at the fight's start. */
	int[] baseLevels;
	/**
	 * The main weapon's ideal expected DPS against the target at full hitpoints
	 * and base Defence, with the gear on hand: worked out once, as the fight
	 * opens on a fresh target, and carried from the room's earlier fight on the
	 * same npc otherwise; 0 when unknown.
	 */
	double idealDpsFullHp;
	/** Other players in the scene as the fight opened, and the most seen while it ran. */
	int teamSizeStart;
	int teamSizeMax;
	/** The world's types (members, Leagues, speedrunning, ...): flags, never the world number. */
	List<String> worldTypes;
	/** The game's kill count and fight duration, from its messages, on the fight a kill closed; 0 when none came. */
	int kc;
	int gameDurationTicks;
	/** Each mechanic window's idle ticks (T-074, and T-097's chase), at most {@link #WINDOW_CAP}. */
	List<Window> mechanicWindows;

	static final int LIST_CAP = 8;
	static final int WINDOW_CAP = 50;
	/** The skills in {@link #baseLevels}, in order. */
	static final String[] LEVELS = {"attack", "strength", "defence", "ranged", "magic", "hitpoints", "prayer"};

	/** One weapon's (or spell's) attacks in the fight: as the timeline credited them. */
	static final class Weapon
	{
		int attacks;
		/** Attacks that put damage down. */
		int landed;
		int damage;
		/** The expected damage and its variance as booked; attacks with no expectation add nothing. */
		double expected;
		double variance;
		/** Its attack speed in ticks, as the attack was thrown; 0 when unknown. */
		int speed;
	}

	/** One mechanic window: its state and the idle ticks inside it, excused and still lost. */
	static final class Window
	{
		String mechanic;
		int excused;
		int lost;

		Window()
		{
		}

		Window(String mechanic, int excused, int lost)
		{
			this.mechanic = mechanic;
			this.excused = excused;
			this.lost = lost;
		}

		int idle()
		{
			return excused + lost;
		}
	}

	/** Sums the timeline's attacks per weapon row: {@code key} for an ordinary attack, {@code key (special)} for a special. */
	static Map<String, Weapon> weaponsOf(List<FightTimeline.Attack> attacks)
	{
		final Map<String, Weapon> out = new LinkedHashMap<>();
		if (attacks == null)
		{
			return out;
		}
		for (FightTimeline.Attack a : attacks)
		{
			final Weapon w = out.computeIfAbsent(a.rowKey(), k -> new Weapon());
			w.attacks++;
			if (a.damage > 0)
			{
				w.landed++;
			}
			w.damage += a.damage;
			if (a.mean >= 0)
			{
				w.expected += a.mean;
			}
			if (a.variance >= 0)
			{
				w.variance += a.variance;
			}
			if (a.speed > 0 && w.speed == 0)
			{
				w.speed = a.speed;
			}
		}
		// Two decimals are plenty for a sum of hits, and keep the saved fight small.
		for (Weapon w : out.values())
		{
			w.expected = round2(w.expected);
			w.variance = round2(w.variance);
		}
		return out;
	}

	/** To two decimals. */
	static double round2(double v)
	{
		return Math.round(v * 100) / 100.0;
	}

	/** Adds a name to a capped list, once. */
	static List<String> addOnce(List<String> list, String name)
	{
		if (name == null || name.isEmpty())
		{
			return list;
		}
		final List<String> out = list == null ? new ArrayList<>() : list;
		if (!out.contains(name) && out.size() < LIST_CAP)
		{
			out.add(name);
		}
		return out;
	}

	/** Other players seen around the fight now: the most it has seen. */
	void seePlayers(int others)
	{
		teamSizeMax = Math.max(teamSizeMax, others);
	}

	/** Adds a window, up to {@link #WINDOW_CAP}. */
	void addWindow(String mechanic, int excused, int lost)
	{
		if (mechanicWindows == null)
		{
			mechanicWindows = new ArrayList<>();
		}
		if (mechanicWindows.size() < WINDOW_CAP)
		{
			mechanicWindows.add(new Window(mechanic, excused, lost));
		}
	}

	/** The item ids, without empties, sorted. */
	static int[] sortedIds(int[] ids)
	{
		if (ids == null)
		{
			return null;
		}
		return Arrays.stream(ids).filter(id -> id > 0).sorted().toArray();
	}

	/** A short, stable hash of a text (FNV-1a, 32 bits, as hex): for telling two rules files apart, not for security. */
	static String shortHash(String text)
	{
		if (text == null)
		{
			return null;
		}
		int h = 0x811c9dc5;
		for (int i = 0; i < text.length(); i++)
		{
			h ^= text.charAt(i);
			h *= 0x01000193;
		}
		return String.format("%08x", h);
	}
}
