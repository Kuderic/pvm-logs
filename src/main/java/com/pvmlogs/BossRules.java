package com.pvmlogs;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.util.Filepath;

/**
 * Bosses whose stats the monster data cannot describe on its own, as data.
 *
 * <p>The monster data is the DPS calculator's, and some of what the calculator
 * does is not in that data but in its code: Vardorvis's strength and defence move
 * with his hitpoints and are stored as 0; Araxxor, Yama and Bork change with phases
 * the data does not carry. Each such case lives here as data, so a new boss is a
 * data change rather than a code change.
 *
 * <p>The <em>numbers</em> in a rule (stat ranges, phase bonuses, npc ids) are game
 * facts read from the calculator's {@code src/lib/scaling} and
 * {@code src/lib/constants.ts}; the file's {@code portedFrom} names the repo and
 * commit they came from. The code that applies them is our own. The calculator (weirdgloop/osrs-dps-calc)
 * is GPL-3, so none of its code may be copied into this BSD-2 plugin; its
 * {@code monsters.json} data and the game numbers are fine with credit.
 *
 * <p>Three layers, later ones winning by boss name:
 * <ol>
 * <li>{@code boss-rules.json} bundled with the plugin;</li>
 * <li>{@code boss-rules.json} in the plugin's data folder
 * ({@code ~/.runelite/plugin-data/<PvmPerformanceConfig.INTERNAL_NAME>/}), if present - a fix can be
 * tried without waiting for a release;</li>
 * <li>nothing else. A boss with no rule falls back to the monster data and to the
 * checks in MonsterStatsProvider and StatsCheck, which flag rather than guess.</li>
 * </ol>
 *
 * <p>What a rule can say, in schema 1: which versions a boss has and their full
 * hitpoints (the boss health bar's full hitpoints picks between them); stats that
 * scale linearly with remaining hitpoints; that defence cannot be drained; and, for
 * anything not yet modelled, a plain description that is surfaced as a warning.
 *
 * <p>Schema 2 (T-020, the Abyssal Sire) adds four optional fields, so a schema-1
 * file still reads the same:
 * <ul>
 * <li>{@code versionByNpcId}: which version of the monster data each id is, for a
 * boss that changes id with its phases. Without it an id the data does not list
 * resolves by name and takes whichever version comes first.</li>
 * <li>{@code hpRestoredOnLeaving}: ids whose damage the boss heals back when it
 * changes out of them, so the count of what it has left starts again at full.</li>
 * <li>{@code countedHpMargin}: how far the counted hitpoints are known to run low,
 * which the overkill cap allows above the count.</li>
 * <li>{@code xpBonus}: the experience bonus the game pays on this boss, where the
 * wiki formula on a version's stats says otherwise.</li>
 * </ul>
 *
 * <p>Schema 3 (T-041, from T-039's research) adds one optional field to a version,
 * {@code attacksOnPlayer}: how to tell the boss's attacks on the player apart from
 * what the client sees, and which of them were avoidable. {@link AttackClassifier}
 * reads it. A schema-2 file has none and reads the same.
 */
@Slf4j
class BossRules
{
	static final int SCHEMA_VERSION = 3;

	static final class Variant
	{
		String version;
		int maxHp;
		@SerializedName("default")
		boolean byDefault;
		ScaleWithHp scaleWithHp;
		/** Schema 3: the version's attacks on the player, or null where none are described. */
		AttacksOnPlayer attacksOnPlayer;
	}

	/**
	 * Schema 3 (T-041): how a version's hits on the player are named, as data. Each
	 * hit is checked against {@link #attacks} in order and the first that fits names
	 * it; one that fits none is {@link #unnamed}.
	 */
	static final class AttacksOnPlayer
	{
		/** Saved with every kill classed by these rules, so a kill classed by older rules can be told apart. */
		int rulesVersion;
		/** The boss heals off its own hits: which hits it heals for, and how soon the heal splat comes. */
		BossHeal bossHeal;
		/** How many ticks a hit waits for the evidence after it (a bleed, a heal) before it is named. */
		int waitTicks;
		List<AttackRule> attacks;
		/** What a hit that fits no attack counts as. Only {@code chip} (never avoidable) is understood. */
		String unnamed;
	}

	static final class BossHeal
	{
		/** The share of the hit the boss heals, rounded down: 0.5 for Vardorvis. */
		double share;
		/** The heal splat lands on the hit's tick or up to this many ticks after it. */
		int withinTicks;
		/** The hitsplat types the boss heals off: his hits, not the bleed they leave. Any when left out. */
		int[] splat;
	}

	/**
	 * One attack, and the conditions a hit must meet to be it. Every condition given
	 * must hold; one left out is not checked.
	 */
	static final class AttackRule
	{
		/** The class it is saved under, e.g. {@code melee}; an {@code offPrayer} attack off prayer is {@code melee off prayer}. */
		String name;
		/** The hitsplat types it can show as; any when left out. */
		int[] splat;
		/** At least / at most this much damage. */
		Integer minDamage;
		Integer maxDamage;
		/** Whether the boss healed off it (see {@link AttacksOnPlayer#bossHeal}). A hit too small to heal fits neither. */
		Boolean bossHeals;
		/** The target started one of these animations in the range of ticks before the hit (its latest such one). */
		ActionCondition anim;
		/** One of these projectiles landed in the range of ticks before the hit. */
		ActionCondition projectile;
		/** A splat of one of these types lands on the player in the range of ticks after it: an axe's bleed. */
		FollowedBy followedBy;
		/** The overhead up at the hit must not protect from this style ({@code melee}, {@code missiles}, {@code magic}). */
		String overheadNot;
		/** The style its protection prayer is, for {@code offPrayer}. */
		String prayer;
		/** The most a hit gets through that prayer; more under it means the prayer came up too late. */
		int prayedMax;
		/** The share of the hit the prayer lets through: 0.25 for Vardorvis's melee. */
		double prayedShare;
		/** {@code always}, {@code offPrayer} (only off prayer, only the part the prayer stops) or {@code never}. */
		String avoidable;
		/** What the debug log says it was named by. */
		String evidence;
	}

	static final class ActionCondition
	{
		int[] ids;
		/** [nearest, furthest] ticks before the hit; for a projectile, when it landed. */
		@SerializedName(value = "ticksBefore", alternate = {"landedTicksBefore"})
		int[] ticksBefore;
	}

	static final class FollowedBy
	{
		int[] splat;
		/** [nearest, furthest] ticks after the hit. */
		int[] withinTicks;
	}

	static final class ScaleWithHp
	{
		/** [at full hitpoints, at none]. */
		int[] defence;
		int[] strength;
	}

	static final class Boss
	{
		String name;
		int[] npcIds;
		boolean defenceDrainImmune;
		List<Variant> variants;
		/** What about this boss is known and not modelled. Shown as a warning against its expected figures. */
		String unmodelled;
		String notes;
		/** Schema 2: npc id (as a string, JSON's keys are) -> the version of the monster data that id is. */
		Map<String, String> versionByNpcId;
		/** Schema 2: ids whose damage is healed back when the boss changes out of them into an id not listed. */
		int[] hpRestoredOnLeaving;
		/** Schema 2: how many hitpoints the count is known to run low by; the overkill cap allows this much above it. */
		int countedHpMargin;
		/** Schema 2: the experience bonus the game pays on every version, where the formula disagrees. 0 means none. */
		double xpBonus;
	}

	static final class PortedFrom
	{
		String repo;
		List<String> paths;
		String commit;
		String date;
	}

	static final class RulesFile
	{
		int schemaVersion;
		PortedFrom portedFrom;
		List<Boss> bosses;
	}

	private final Map<Integer, Boss> byId;
	private final PortedFrom portedFrom;

	private BossRules(Map<Integer, Boss> byId, PortedFrom portedFrom)
	{
		this.byId = byId;
		this.portedFrom = portedFrom;
	}

	static BossRules empty()
	{
		return new BossRules(Collections.emptyMap(), null);
	}

	/** The bundled rules, overlaid by the user's file where it exists and parses. Never throws. */
	static BossRules load(Gson gson, Filepath userOverride)
	{
		RulesFile bundled = null;
		try (InputStream in = BossRules.class.getResourceAsStream("boss-rules.json"))
		{
			if (in != null)
			{
				bundled = read(gson, new InputStreamReader(in, StandardCharsets.UTF_8));
			}
		}
		catch (IOException | RuntimeException e)
		{
			log.warn("PvM Logs: bundled boss rules unreadable", e);
		}
		RulesFile user = null;
		if (userOverride != null && userOverride.isFile())
		{
			try (Reader r = userOverride.openBufferedReader())
			{
				user = read(gson, r);
			}
			catch (IOException | RuntimeException e)
			{
				log.warn("PvM Logs: boss rules override {} unreadable, ignored", userOverride, e);
			}
		}
		return merge(bundled, user);
	}

	/** Parses one rules file, refusing a schema newer than this code understands. Null if unusable. */
	static RulesFile read(Gson gson, Reader reader)
	{
		final RulesFile file = gson.fromJson(reader, RulesFile.class);
		if (file == null || file.bosses == null)
		{
			return null;
		}
		if (file.schemaVersion > SCHEMA_VERSION)
		{
			log.warn("PvM Logs: boss rules schema {} is newer than {}, ignored", file.schemaVersion, SCHEMA_VERSION);
			return null;
		}
		return file;
	}

	/** Later files replace earlier ones boss by boss, matched on name. */
	static BossRules merge(RulesFile base, RulesFile overlay)
	{
		final Map<String, Boss> byName = new LinkedHashMap<>();
		for (RulesFile f : new RulesFile[]{base, overlay})
		{
			if (f == null)
			{
				continue;
			}
			for (Boss b : f.bosses)
			{
				if (b != null && b.name != null && b.npcIds != null)
				{
					byName.put(b.name.toLowerCase(), b);
				}
			}
		}
		final Map<Integer, Boss> byId = new HashMap<>();
		for (Boss b : byName.values())
		{
			for (int id : b.npcIds)
			{
				byId.put(id, b);
			}
		}
		final PortedFrom from = base != null ? base.portedFrom : overlay != null ? overlay.portedFrom : null;
		return new BossRules(byId, from);
	}

	Boss forNpc(int npcId)
	{
		return byId.get(npcId);
	}

	/** Where the bundled rules were ported from, for the debug log header. */
	String provenance()
	{
		return portedFrom == null || portedFrom.commit == null ? "unknown"
			: portedFrom.repo + "@" + portedFrom.commit.substring(0, Math.min(7, portedFrom.commit.length()))
				+ " (" + portedFrom.date + ")";
	}

	int size()
	{
		return byId.size();
	}

	/**
	 * Which version of the boss this is: the one whose full hitpoints the game has
	 * shown, then the rule's default, then the first listed. Null when the rule
	 * lists none.
	 */
	static Variant choose(Boss boss, int observedMaxHp)
	{
		if (boss == null || boss.variants == null || boss.variants.isEmpty())
		{
			return null;
		}
		if (observedMaxHp > 0)
		{
			for (Variant v : boss.variants)
			{
				if (v.maxHp == observedMaxHp)
				{
					return v;
				}
			}
		}
		for (Variant v : boss.variants)
		{
			if (v.byDefault)
			{
				return v;
			}
		}
		return boss.variants.get(0);
	}

	/** The version of the monster data this id is, by the rule, or null where the rule does not say. */
	static String versionFor(Boss boss, int npcId)
	{
		return boss == null || boss.versionByNpcId == null ? null : boss.versionByNpcId.get(Integer.toString(npcId));
	}

	/**
	 * Whether changing from one id to another heals back the damage taken so far:
	 * the old id is one the rule restores on leaving and the new one is not. The
	 * Abyssal Sire is restored to full by the end of its first phase, whatever was
	 * dealt to it while it slept.
	 */
	static boolean restoresOnLeaving(Boss boss, int fromId, int toId)
	{
		if (boss == null || boss.hpRestoredOnLeaving == null || fromId == toId)
		{
			return false;
		}
		boolean from = false;
		boolean to = false;
		for (int id : boss.hpRestoredOnLeaving)
		{
			from |= id == fromId;
			to |= id == toId;
		}
		return from && !to;
	}

	/**
	 * The attacks on the player for the version this full hitpoints picks (see
	 * {@link #choose}), or null where that version describes none.
	 */
	static AttacksOnPlayer attacksFor(Boss boss, int observedMaxHp)
	{
		final Variant variant = choose(boss, observedMaxHp);
		return variant == null ? null : variant.attacksOnPlayer;
	}

	/** The rule's variant with this version name, or null. */
	static Variant variantNamed(Boss boss, String version)
	{
		if (boss == null || boss.variants == null || version == null)
		{
			return null;
		}
		for (Variant v : boss.variants)
		{
			if (version.equalsIgnoreCase(v.version))
			{
				return v;
			}
		}
		return null;
	}

	/**
	 * A stat that moves linearly from {@code range[0]} at full hitpoints to
	 * {@code range[1]} at none, truncated the way the DPS calculator's {@code lerp}
	 * truncates. Unknown or out-of-range hitpoints read as full.
	 */
	static int scaleWithHp(int[] range, int currentHp, int maxHp)
	{
		if (range == null || range.length != 2 || maxHp <= 0)
		{
			return -1;
		}
		final int hp = currentHp < 0 || currentHp > maxHp ? maxHp : currentHp;
		// lerp(curr, srcStart = max, srcEnd = 0, dstStart = range[0], dstEnd = range[1])
		final double value = ((double) (hp - maxHp) * (range[1] - range[0])) / (0 - maxHp) + range[0];
		return (int) value; // truncates towards zero, as Math.trunc
	}

	/** Every boss id with a rule, for tests and the debug header. */
	List<Integer> ids()
	{
		return new ArrayList<>(byId.keySet());
	}
}
