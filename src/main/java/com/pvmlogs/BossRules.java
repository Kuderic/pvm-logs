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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * <p>Schema 4 (T-049) adds one optional field to a boss, {@code defenceDrain}
 * ({@link DrainRules}): how far its Defence and Magic can be drained, whether at
 * all, how fast drained levels come back and what restores them at once
 * mid-fight, and the few special cases the drain rules
 * need (Tekton, the godsword's double drain, magic defence off the Defence level).
 * It replaces the hard-coded {@code DefenceDrainCap}. A file of an older schema
 * has none: those bosses drain to 0, the wiki's rule for a monster with no stated
 * limit.
 *
 * <p>Schema 5 (T-057) adds one optional field to a boss, {@code damageModifiers}
 * ({@link DamageModifierRules}): what the boss does to the player's damage while
 * it is in some state - the Abyssal Sire's halving while it walks, the
 * Leviathan's cap while stunned, its wound, its specials and the enrage's
 * pathfinder. {@link DamageModifiers} tracks the states and the expected figures
 * take the effects. A file of an older schema has none: the boss takes every hit
 * as it rolls. T-079 adds {@code rolls} and T-083 {@code rollsFromMin} to a
 * modifier within schema 5: a reader without them takes one roll raised to the
 * minimum, so an older build reads the file as it did. T-087 adds, still within
 * schema 5, a state's {@code startDelay}, a modifier's {@code version},
 * {@code defenceRoll}, {@code attackTypes}, {@code firstHitOnly},
 * {@code stacks} and {@code magicDefencePerStack}, and the rules'
 * {@code stacks} (the Maggot King's phases, burn and punish). The bundled file
 * ships with the code that reads it; only a user's own file could reach an older
 * build, which would take the King's punish without its delay or accuracy.
 */
@Slf4j
class BossRules
{
	static final int SCHEMA_VERSION = 5;

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
		/** Schema 4: how its stats drain; null for the wiki's default, to 0 with no special cases. */
		DrainRules defenceDrain;
		/** Schema 5: what it does to the player's damage, and when; null for nothing. */
		DamageModifierRules damageModifiers;
		/** T-074: the idle ticks its mechanics may excuse from uptime, by the damage-modifier state they ride on. */
		List<UptimeAllowance> uptimeAllowances;
		/** T-086: an attack on the tick of one of its animations that ignores the weapon's attack delay; null for none. */
		MeleePunish meleePunish;
	}

	/**
	 * T-086: a boss move the player may answer with an attack outside the
	 * weapon's speed (the Maggot King's slam). The attack thrown {@link
	 * #afterTicks} after the boss starts {@link #onBossAnim} is the punish: it
	 * neither waits for the weapon's cooldown nor starts one, so the weapon's
	 * next attack is still due when it was. Booking only; what the punish does to
	 * the attack's accuracy and damage is the calc's.
	 */
	static final class MeleePunish
	{
		/** The boss animation the punish answers. */
		int onBossAnim;
		/** Ticks from the animation's start to the tick the punish is thrown on. */
		int afterTicks;
		String source;
	}

	/**
	 * T-074: downtime a boss forces on the player. Idle ticks inside one of the
	 * named state's windows, up to {@link #maxTicks} a window, are neutral
	 * "mechanics" ticks rather than lost; those beyond the cap are still lost.
	 */
	static final class UptimeAllowance
	{
		/** The {@link StateRule} name whose windows it covers. */
		String state;
		/** {@code "cast"}: from the player's cast that started the window (the Leviathan's stun); else from its opening. */
		String from;
		/** The idle ticks one window may excuse. */
		int maxTicks;
		/** Ticks after the window closes it still covers (getting back into position after a special). */
		int afterTicks;
		String source;

		boolean fromCast()
		{
			return "cast".equals(from);
		}
	}

	/**
	 * Schema 5 (T-057): a boss's damage modifiers. {@code states} are windows the
	 * client can see open and close (a stun, a special attack); {@code modifiers}
	 * say what an attack landing in them takes, in the order listed.
	 */
	static final class DamageModifierRules
	{
		List<StateRule> states;
		List<ModifierRule> modifiers;
		/** Counts the boss carries, read off its splats (T-087: the Maggot King's burn stacks). */
		List<StackRule> stacks;
	}

	/**
	 * A count the boss carries (T-087), read off the damage-over-time splats on it:
	 * a {@link #fromDot} splat shows how many are on it, as a burn splat deals one
	 * a stack. Gone once no such splat has come for {@link #lapseTicks}.
	 */
	static final class StackRule
	{
		String name;
		/** The kind of damage-over-time splat that shows the count: burn, poison, venom or bleed. */
		String fromDot;
		/** The most there can be. */
		int max;
		/** Ticks after the last such splat that the count still stands. */
		int lapseTicks;
		String source;
	}

	/** A window of the boss's: what opens it, what closes it, how long it can last. Every field optional. */
	static final class StateRule
	{
		String name;
		/** The player's own cast of one of these spells (Spell names) lands on the boss. */
		List<String> startOnPlayerSpell;
		/** The boss starts one of these animations. */
		int[] startOnBossAnim;
		/** One of the player's attacks lands with this modifier: the Leviathan's wound starts its special. */
		String startOnModifierLanded;
		/** A fixed length in ticks from the start; 0 for none. */
		int ticks;
		/** Ticks from what starts it to its start (T-087: the Maggot King's punish, two ticks into its slam). */
		int startDelay;
		/** The boss starts one of these animations, at least {@link #minTicks} after the start. */
		int[] endOnBossAnim;
		int minTicks;
		/** One of the player's attacks lands with this modifier: a shot into the wound breaks the stun. */
		String endOnModifierLanded;
		/** The longest it can last, when nothing else closed it; 0 for no limit. */
		int maxTicks;
		/**
		 * The player's spell that starts it resets their attack delay (T-078): the weapon's next attack can go out the
		 * next tick, and one thrown before the state starts lands free of it - the Leviathan's "free ranged shot every
		 * stun before Levi's damage reduction kicks in".
		 */
		boolean resetsAttackDelay;
		/** Not open while an npc of one of these ids is present: it is not opened, and one open is closed (T-078). */
		int[] endWhileNpc;
		String source;
	}

	/**
	 * What an attack landing on the boss takes, and when. Every condition given
	 * must hold; the effects apply in the order accuracy, minimum hit, cap,
	 * multiplier, and modifiers in the order listed.
	 */
	static final class ModifierRule
	{
		String name;
		/** The id the boss wears when the attack lands; any when left out. */
		int[] npcIds;
		/** A state that must be open when the attack lands. */
		String state;
		/** A state that must not be. */
		String notState;
		/** The player behind the boss (its facing against the player's tile when the attack goes out); either if null. */
		Boolean behind;
		/** The player within {@code range} tiles of one of these npcs when the attack goes out. */
		InsideNpc insideNpc;
		/** MELEE, RANGED or MAGIC; any when left out. */
		List<String> styles;
		/** The chance a roll lands, replacing the accuracy: 1 for "cannot miss". */
		Double accuracy;
		/** A landed roll deals at least this share of the max, rounded down. */
		Double minHitShare;
		/**
		 * The damage roll is the best of this many, each from 0 to the max, before
		 * the minimum and the rest (T-079: the Leviathan's pathfinder); one when left
		 * out.
		 */
		Integer rolls;
		/**
		 * A landed roll is drawn from the minimum ({@link #minHitShare}) up to the
		 * max, rather than raised to the minimum (T-083: the Leviathan's wound); with
		 * {@link #rolls}, the best of that many such draws.
		 */
		Boolean rollsFromMin;
		/** No hitsplat above this. */
		Integer cap;
		/** The damage times num/den, rounded down: {1, 2} halves. */
		int[] multiply;
		/** The multiply applies to the first hitsplat of an attack only (T-087: the punish on a multi-hit weapon). */
		Boolean firstHitOnly;
		/** STAB, SLASH, CRUSH, RANGED or MAGIC: the attack type it rolls with, for {@link #defenceRoll}; any if left out. */
		List<String> attackTypes;
		/** The boss's defence roll times num/den, rounded down (T-087: the punish's "85% reduced defence roll"). */
		int[] defenceRoll;
		/**
		 * While it applies, the boss's figures are this version's in the monster
		 * data (T-087: the Maggot King "Nearby" while it slams); its usual version
		 * otherwise.
		 */
		String version;
		/** A count the boss carries ({@link StackRule#name}) ... */
		String stacks;
		/** ... and the change to its magic defence bonus for each (T-087: burn, -20 each). */
		Integer magicDefencePerStack;
		/**
		 * Never in the figures shown live (T-087, the Jagex rule): the attack it
		 * applies to is scored with it, but the overlay and the side panel never
		 * change for it - the Maggot King's slam and punish must not read as a timer.
		 */
		Boolean hiddenLive;
		String source;
	}

	static final class InsideNpc
	{
		int[] npcIds;
		int range;
	}

	/**
	 * Schema 4 (T-049): one boss's limits on defence drains, from the OSRS Wiki and
	 * the DPS calculator's {@code getDefenceFloor} (sources in each entry's
	 * {@link #source}). Every field is optional. Floors given several ways combine
	 * to the highest.
	 */
	static final class DrainRules
	{
		/** A monster restores one drained level every 100 ticks unless it is known to be faster (wiki, Temporary skill drain). */
		static final int DEFAULT_REGEN_TICKS = 100;

		/** Nothing lowers its Defence or Magic. */
		boolean immune;
		/** Versions (as the monster data names them) that nothing drains: the awakened Desert Treasure II bosses. */
		List<String> immuneVersions;
		/**
		 * The game replaces its actor mid-fight (a new npc index) and the drains carry
		 * to the new one: Kephri's shield, The Fractured Archive's bosses within a phase.
		 */
		boolean carryAcrossActors;
		/** The lowest its Defence can be drained to, in levels. */
		Integer defenceFloor;
		/** The share of its (raid-scaled) Defence it always keeps, in percent: The Fractured Archive's 60. */
		Integer defenceFloorPercent;
		/** The most levels of Defence that can be drained off it in all. */
		Integer maxDefenceDrain;
		/** The lowest its Magic can be drained to, in levels; 0 when left out. */
		Integer magicFloor;
		/** It rolls magic defence off its Defence level, not its Magic, so a Defence drain lowers that too. */
		boolean magicUsesDefence;
		/** Tekton: a warhammer or maul that misses still takes 5%, a godsword 10 (RuneLite's {@code SpecialWeapon}). */
		boolean tekton;
		/** A godsword drains twice its damage off it. */
		boolean godswordDoubled;
		/** Changing into any of these npc ids restores its stats to their base. */
		int[] resetOnNpcIds;
		/** A varbit taking a value restores them: Sotetseg's maze. */
		List<VarbitReset> resetOnVarbit;
		/** Ticks between each drained level it gets back; {@link #DEFAULT_REGEN_TICKS} when left out, 0 for never. */
		Integer regenTicks;
		/** Where each fact came from. */
		String source;

		/** Whether nothing drains it in this version of the monster data. */
		boolean immuneIn(String version)
		{
			if (immune)
			{
				return true;
			}
			if (immuneVersions == null || version == null)
			{
				return false;
			}
			for (String v : immuneVersions)
			{
				if (v != null && v.equalsIgnoreCase(version))
				{
					return true;
				}
			}
			return false;
		}

		/** Ticks between each drained level it gets back. */
		int regenTicks()
		{
			return regenTicks == null ? DEFAULT_REGEN_TICKS : Math.max(0, regenTicks);
		}

		/** The lowest its Defence can go from this (raid-scaled) base. */
		int defenceFloor(int base)
		{
			if (immune)
			{
				return base;
			}
			int floor = 0;
			if (defenceFloor != null)
			{
				floor = Math.max(floor, defenceFloor);
			}
			if (defenceFloorPercent != null)
			{
				floor = Math.max(floor, base * defenceFloorPercent / 100);
			}
			if (maxDefenceDrain != null)
			{
				floor = Math.max(floor, base - maxDefenceDrain);
			}
			return Math.max(0, Math.min(base, floor));
		}

		/** The lowest its Magic can go from this base. */
		int magicFloor(int base)
		{
			if (immune)
			{
				return base;
			}
			return Math.max(0, Math.min(base, magicFloor == null ? 0 : magicFloor));
		}

		boolean resetsOnNpc(int npcId)
		{
			if (resetOnNpcIds == null)
			{
				return false;
			}
			for (int id : resetOnNpcIds)
			{
				if (id == npcId)
				{
					return true;
				}
			}
			return false;
		}

		boolean resetsOnVarbit(int varbit, int value)
		{
			if (resetOnVarbit == null)
			{
				return false;
			}
			for (VarbitReset r : resetOnVarbit)
			{
				if (r != null && r.varbit == varbit && r.value == value)
				{
					return true;
				}
			}
			return false;
		}
	}

	/** A varbit and the value that restores a boss's stats. */
	static final class VarbitReset
	{
		int varbit;
		int value;
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
	// Every varbit a drain rule resets on, so a varbit change can be dismissed in O(1).
	private final Set<Integer> drainVarbits;

	private BossRules(Map<Integer, Boss> byId, PortedFrom portedFrom)
	{
		this.byId = byId;
		this.portedFrom = portedFrom;
		final Set<Integer> varbits = new HashSet<>();
		for (Boss b : byId.values())
		{
			if (b.defenceDrain != null && b.defenceDrain.resetOnVarbit != null)
			{
				for (VarbitReset r : b.defenceDrain.resetOnVarbit)
				{
					if (r != null)
					{
						varbits.add(r.varbit);
					}
				}
			}
		}
		this.drainVarbits = Collections.unmodifiableSet(varbits);
	}

	/** The varbits any boss's drain rules reset on (T-049). */
	Set<Integer> drainVarbits()
	{
		return drainVarbits;
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
