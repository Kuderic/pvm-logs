package com.pvmlogs;

import lombok.Getter;

/**
 * One fight against one NPC: the measured side from observed facts - damage from
 * {@code hitsplat.isMine()} splats, hits vs zeros, damage taken, duration -
 * alongside the expected side sampled from the combat model once per attack.
 */
@Getter
class Fight
{
	private final String targetName;
	// The room or boss this fight is a part of, or null if it stands alone.
	private final String groupName;
	// Overrides the group name where the room alone is too coarse to compare against itself - Olm's phases. Null unless
	// set.
	private String encounterLabel;
	// Stored the wrong way round on purpose: deserialisation fills fields without running initialisers, so a fight from an
	// older history file gets false, which has to mean the ordinary case.
	private final boolean unscored;
	// The raid this was fought in and which run of it, so the history reads back as raids without a second file. Null and
	// 0 outside a raid, which is what an older history file reads as.
	private final String raidName;
	private final int raidId;
	private final int targetId;
	private final int targetIndex;
	// The NPC's full hitpoints, or -1 if unknown; used to classify bosses. From the version of the monster data in use when
	// the fight opened (RuneLite's NPCManager knows nothing of Vardorvis or the Chambers), corrected at the end from the
	// boss health bar where the game drew one.
	private int maxHp;
	private final long startMillis;

	private long lastActivityMillis;
	/**
	 * The game tick this fight opened on. Not persisted for any purpose beyond
	 * the session it was recorded in: it exists to say whether an attack booked
	 * now was thrown before this fight existed, which is a question only the
	 * live client asks.
	 */
	private int startTick;
	private int damageDealt;
	// Our thrall's damage on this fight's npcs (issue #1). Real damage, so it takes the target's health down, but none of
	// it is the player's: it is kept out of damage dealt, the hits and everything they feed.
	private int thrallDamage;
	// Damage the player reflected onto this fight's target: a recoil ring's and vengeance's (T-017 Part B). Real damage
	// that takes the target's health down, but no attack threw it, so it is kept out of damage dealt, the hits and the
	// attacks, like the thrall's. Luck-neutral: nothing expects it and nothing marks it down.
	private int reflectDamage;
	// Damage over time on this fight's target: burn (burning claws), poison, venom, bleed. The splat names no owner, so
	// solo it is the player's and in a group it may be anyone's; either way it is kept out of damage dealt and the hits,
	// which are the weapon's, and shown on its own.
	private int dotDamage;
	// T-062: dotDamage and dotTaken by kind (HitsplatKind.typeName: burn, poison, venom, bleed). Null until there is any,
	// which is what a fight saved before T-062 reads as: its totals then show as one DoT line (DotKinds).
	private java.util.Map<String, Integer> dotDamageByKind;
	private java.util.Map<String, Integer> dotTakenByKind;
	// What the target healed while it was being fought, from its heal splats.
	private int healed;
	// Other players' hits on the target, which take its health down and are nobody's business here beyond that.
	private int othersDamage;
	// Splats of a type this plugin does not know, counted against the target's health and logged by type so they can be
	// identified. Never the player's.
	private int unclassifiedDamage;
	// The player's damage that landed on something other than hitpoints - a shield, an armour, a totem's charge, a
	// warden's poise - by kind. Still in damage dealt, since it is the attack's outcome and the expected side scores it;
	// kept out of the target's health. Null until there is any, which is what a fight from an older history reads as.
	private java.util.Map<String, Integer> notHitpoints;
	// Why this fight's expected side should not be trusted, or null: the target's stats carried a warning, or the attacks
	// that landed were too far from the expected count to be luck. See StatsCheck.
	private String statsWarning;
	// What the player took (T-018). damageTaken is hitpoints lost to hits only: before T-018 it was every splat on the
	// player, heals and prayer drains included, and fights saved then keep that total. Damage over time on the player
	// is beside it, not in it. All of these read 0 on a fight from an older history.
	private int damageTaken;
	private int dotTaken;
	// Hit splats on the player, 0s included, and the largest of them.
	private int hitsTaken;
	private int biggestHitTaken;
	// The part of damageTaken taken with no protection prayer up (DamageTaken.PROTECTION), by the server's varbits.
	private int damageTakenUnprotected;
	// What the hits on the player were, at a boss whose rules describe its attacks (T-041, boss-rules.json schema 3): the
	// rules' version, the avoidable part of the damage taken (off prayer, only what the prayer would have stopped), HP by
	// attack, the eating ticks the avoidable hits cost by the hitpoints replay, and the eating line it used with where that
	// came from. Set once, as the fight ends. A fight with no such rules, and every fight saved before T-041, has version 0,
	// and the getters below read -1 (unknown) for it, as they do for every fight still running: nothing about boss attacks
	// is readable live (Jagex's guidelines).
	private int attackRulesVersion;
	private int damageTakenAvoidable;
	private java.util.Map<String, Integer> damageTakenByAttack;
	private double avoidableEatingTicks;
	private int eatLineHp;
	private String eatLineSource;
	// The target's defence drains over the fight (T-049): its Defence and Magic at the start and the end, its base and
	// floor, the player's specials thrown and landed per weapon, the party's drains, and each drain. Set as the fight
	// ends; null on a fight saved before T-049 and where nothing was known of the target's stats.
	private DrainRecord drainRecord;
	// One attempt per attack thrown, and a hit for each attack that landed anything. accuracy = hits / attempts.
	private int attempts;
	private int hits;
	// Attacks that went out and never resolved, because the target died or changed form while they were still in the air.
	// See recordAttackNulled.
	private int nulled;
	private boolean ended;
	private boolean targetDied;
	// Whether this fight is the last of its room: the one the boss's loot dropped on. Not targetDied, which a Hueycoatl
	// sets several times on the way down.
	private boolean closedRoom;
	private long endMillis;

	// Sampled once per attack rather than snapshotted once for the fight: with a spec weapon swapped in partway the mean
	// is the actual blend wielded, which no single snapshot could represent.
	private double sumExpectedMaxHit;
	private int expectedMaxHitSamples;
	private double sumExpectedAccuracy;
	// The chance each attack lands a NON-ZERO splat - its accuracy less the 1 in (max + 1) a successful roll has of
	// dealing nothing - summed over the same attacks, with the sum of p(1-p) beside it: the mean and variance of the
	// landed count, which StatsCheck tests the actual hits against. The plain accuracy sum above would read a weak
	// weapon's rolled zeros as misses, and over a few hundred attacks that alone is four standard deviations.
	private double sumExpectedLanded;
	private double sumExpectedLandedVariance;
	private double sumExpectedAverageHit;
	// Accuracy and average hit need target data, so they can be missing while the max hit is known, and are counted
	// separately.
	private int expectedTargetSamples;
	// The variance of each attack's damage, from the distribution its average came from, summed over the same attacks
	// (T-028), and how many carried one. A fight saved before T-028 has none, so its spread is unknown rather than nought:
	// see damageVariance().
	private double sumExpectedDamageVariance;
	private int expectedVarianceSamples;
	// T-050: what averaging the expected damage over the player's own draining specials' outcomes adds to the sums above
	// (both 0 until a special has branched them), and what the drains were worth: the attacks' expectation as made less
	// with nothing drained, realized and averaged. drainValueKnown is false on fights saved before T-050 and where nothing
	// could be drained (an immune boss, no stats).
	private double outcomeExpectedShift;
	private double outcomeVarianceShift;
	private boolean drainValueKnown;
	private double drainValueDamage;
	private double drainValueExpected;

	// What the attacks were set up to deal, against what they would have set up properly. The ratio prices each slip in
	// damage rather than counting alike.
	private int attacksMade;
	private int attacksPrayed;
	private int attacksPotted;
	// Attacks thrown with nothing better available to switch into. The gap to attacksMade is the number that missed at
	// least one switch.
	private int attacksSwitched;
	private double sumActualSetup;
	private double sumIdealSetup;
	// How much of the full boost each attack's stats held, summed with the number of attacks it was read for (T-010).
	// attacksPotted stays beside it: a fight saved before these existed reads back with no samples, and falls back to the
	// count.
	private double sumBoostShare;
	private int boostSamples;
	// What the missing boost and the missed switches cost, in expected damage, summed over the same attacks as the two
	// setup sums. Both are parts of sumIdealSetup - sumActualSetup; the prayer is the rest.
	private double sumBoostCost;
	private double sumSwitchCost;
	// Attacks made with any stat the style rolls on below its base: drained, or brewed down (T-015). Additive, so a fight
	// saved before it loads with none.
	private int attacksDrained;

	// Ticks the weapon was off cooldown with no attack made, against those elapsed since the first attack. Eating is
	// separated out because it is a choice with a known cost.
	// Npcs reached, not attacks thrown: one per attack for the one it was aimed at, plus one for each other npc it
	// caught. The two are the same number until an AoE weapon comes out.
	private int targets;

	private int ticksLostEating;
	private int ticksLostOther;
	private int combatTicks;
	private boolean attacking;
	// This session's attack-by-attack and tick-by-tick record (T-035), fed from the methods below where each counts, so
	// its sums are the fight's. Transient: never saved, null for a fight read from history or with recording off.
	private transient FightTimeline timeline;

	// The tick this target became attackable, and how long it took to be

	Fight(String targetName, int targetId, int targetIndex, int maxHp, long now,
		RaidType raid, int raidId)
	{
		this.targetName = targetName == null ? "NPC" : targetName;
		this.groupName = EncounterGroup.of(targetId);
		this.unscored = EncounterGroup.isUnscored(targetId);
		this.raidName = raid == null ? null : raid.getDisplayName();
		this.raidId = raid == null ? 0 : raidId;
		this.targetId = targetId;
		this.targetIndex = targetIndex;
		this.maxHp = maxHp;
		this.startMillis = now;
		this.lastActivityMillis = now;
	}

	/**
	 * Whether this fight's damage counts towards its room. An unscored fight
	 * still spends time and loses ticks; it just does not speak for how well the
	 * player was hitting.
	 */
	boolean isScored()
	{
		return !unscored;
	}

	/** The room or boss this belongs to, falling back to the target's own name. */
	String encounterName()
	{
		if (encounterLabel != null)
		{
			return encounterLabel;
		}
		return groupName == null ? getTargetName() : groupName;
	}

	void setEncounterLabel(String label)
	{
		this.encounterLabel = label;
	}

	/**
	 * Damage from one hitsplat. {@code landedAttack} says whether this is the
	 * one that makes its attack count as landed - the first of a burst to deal
	 * anything - since a claw special's four splats are one attack.
	 */
	void recordDamageDealt(int amount, long now, boolean landedAttack)
	{
		damageDealt += amount;
		if (landedAttack)
		{
			hits++;
		}
		lastActivityMillis = now;
		if (timeline != null)
		{
			timeline.damage(amount, landedAttack);
		}
	}

	/**
	 * Takes back a splat counted as the player's that turned out to be the
	 * thrall's, within the tick it landed (see the plugin's same-tick re-judging).
	 */
	void revokeDamageDealt(int amount, boolean hit)
	{
		final int before = damageDealt;
		final boolean hitTaken = hit && hits > 0;
		damageDealt = Math.max(0, damageDealt - amount);
		if (hitTaken)
		{
			hits--;
		}
		if (timeline != null)
		{
			timeline.revoked(before - damageDealt, hitTaken);
		}
	}

	/** This session's timeline (T-035), or null. Set by {@link FightTimeline.Store}. */
	void setTimeline(FightTimeline timeline)
	{
		this.timeline = timeline;
	}

	/** A splat our thrall landed. Counted on its own and nowhere else - see thrallDamage. */
	void recordThrallDamage(int amount)
	{
		thrallDamage += amount;
	}

	/** A recoil or vengeance splat on the target. See reflectDamage. */
	void recordReflectDamage(int amount)
	{
		reflectDamage += Math.max(0, amount);
	}

	/** A burn, poison, venom or bleed splat on the target, its kind unknown. See dotDamage. */
	void recordDotDamage(int amount)
	{
		dotDamage += Math.max(0, amount);
	}

	/** A burn, poison, venom or bleed splat on the target, by its hitsplat type (T-062). See dotDamage. */
	void recordDotDamage(int amount, int type)
	{
		recordDotDamage(amount);
		dotDamageByKind = DotKinds.add(dotDamageByKind, HitsplatKind.typeName(type), amount);
	}

	/** dotDamage by kind (T-062); empty on a fight saved before T-062. */
	java.util.Map<String, Integer> getDotDamageByKind()
	{
		return dotDamageByKind == null ? java.util.Collections.emptyMap()
			: java.util.Collections.unmodifiableMap(dotDamageByKind);
	}

	/** dotTaken by kind (T-062); empty on a fight saved before T-062. */
	java.util.Map<String, Integer> getDotTakenByKind()
	{
		return dotTakenByKind == null ? java.util.Collections.emptyMap()
			: java.util.Collections.unmodifiableMap(dotTakenByKind);
	}

	/** One of the player's splats that landed on something other than hitpoints. See notHitpoints. */
	void recordNotHitpoints(String kind, int amount)
	{
		if (notHitpoints == null)
		{
			notHitpoints = new java.util.LinkedHashMap<>();
		}
		notHitpoints.merge(kind, Math.max(0, amount), Integer::sum);
	}

	/** All of the player's damage that landed on something other than hitpoints. */
	int notHitpointsTotal()
	{
		int total = 0;
		if (notHitpoints != null)
		{
			for (int v : notHitpoints.values())
			{
				total += v;
			}
		}
		return total;
	}

	/** A heal splat on the target. */
	void recordHeal(int amount)
	{
		healed += Math.max(0, amount);
	}

	/** Another player's hit on the target. */
	void recordOthersDamage(int amount)
	{
		othersDamage += Math.max(0, amount);
	}

	/** A splat of a type not known here. See unclassifiedDamage. */
	void recordUnclassifiedDamage(int amount)
	{
		unclassifiedDamage += Math.max(0, amount);
	}

	/**
	 * The chance an attack puts a non-zero splat on the target: a successful roll
	 * deals 0 to max uniformly, so one in (max + 1) of them reads as a miss. Taken on
	 * the attack's whole max hit, which for a multi-hit attack slightly understates
	 * the zeros - the safe side for a test that flags too few hits.
	 */
	static double landedChance(double accuracy, int maxHit)
	{
		final double p = Math.max(0, Math.min(1, accuracy));
		return maxHit > 0 ? p * maxHit / (maxHit + 1.0) : p;
	}

	/** The full hitpoints the game showed for the target, where that beats the table's. Ignored when not positive. */
	void noteMaxHp(int hitpoints)
	{
		if (hitpoints > 0)
		{
			maxHp = hitpoints;
		}
	}

	/** Adds a reason not to trust this fight's expected side, once each. */
	void addStatsWarning(String warning)
	{
		if (warning == null || warning.isEmpty() || (statsWarning != null && statsWarning.contains(warning)))
		{
			return;
		}
		statsWarning = statsWarning == null ? warning : statsWarning + "; " + warning;
	}

	/** A magic splash on the target: an attack that landed nothing. */
	void recordSplash(long now)
	{
		lastActivityMillis = now;
	}

	/**
	 * A hit on the player during this fight: an attack's damage or its 0 (see
	 * {@link HitsplatKind#onPlayer}). Like the player's own damage, it keeps the
	 * fight open.
	 *
	 * @param unprotected whether no protection prayer was up as it landed
	 */
	void recordHitTaken(int amount, boolean unprotected, long now)
	{
		final int hit = Math.max(0, amount);
		damageTaken += hit;
		hitsTaken++;
		biggestHitTaken = Math.max(biggestHitTaken, hit);
		if (unprotected)
		{
			damageTakenUnprotected += hit;
		}
		touch(now);
	}

	/** A hit taken with no word on the prayer, so it is not counted as unprotected. See recordHitTaken. */
	void recordDamageTaken(int amount, long now)
	{
		recordHitTaken(amount, false, now);
	}

	/** Poison, venom, burn or bleed on the player during this fight. Kept out of damageTaken; keeps the fight open. */
	void recordDotTaken(int amount, long now)
	{
		dotTaken += Math.max(0, amount);
		touch(now);
	}

	/** {@link #recordDotTaken(int, long)} by the splat's hitsplat type (T-062). */
	void recordDotTaken(int amount, int type, long now)
	{
		recordDotTaken(amount, now);
		dotTakenByKind = DotKinds.add(dotTakenByKind, HitsplatKind.typeName(type), amount);
	}

	private void touch(long now)
	{
		if (now > lastActivityMillis)
		{
			lastActivityMillis = now;
		}
	}

	void noteStartTick(int tick)
	{
		this.startTick = tick;
	}

	void closeRoom()
	{
		closedRoom = true;
	}

	/**
	 * What the hits on the player came to (T-041), recorded once, as the fight
	 * ends. Readable only once it has.
	 *
	 * @param rulesVersion the attack rules' version; 0 records nothing
	 * @param avoidable    the avoidable part of the damage taken, rounded to whole hitpoints here
	 * @param byAttack     HP taken by attack
	 * @param eatingTicks  the eating ticks the avoidable hits cost, to 1 decimal
	 * @param line         the eating line the replay used, with where it came from
	 */
	void recordAvoidable(int rulesVersion, double avoidable, java.util.Map<String, Integer> byAttack, double eatingTicks,
		int line, String lineSource)
	{
		if (rulesVersion <= 0)
		{
			return;
		}
		attackRulesVersion = rulesVersion;
		damageTakenAvoidable = (int) Math.round(Math.max(0, avoidable));
		damageTakenByAttack = byAttack == null ? null : new java.util.LinkedHashMap<>(byAttack);
		avoidableEatingTicks = Math.round(Math.max(0, eatingTicks) * 10) / 10.0;
		eatLineHp = line;
		eatLineSource = lineSource;
	}

	/** Whether this fight's avoidable figures can be read: it has ended, at a boss with attack rules (T-041). */
	boolean hasAvoidable()
	{
		return ended && attackRulesVersion > 0;
	}

	/** The attack rules' version this fight was classed by, or 0: no rules, saved before T-041, or still running. */
	int getAttackRulesVersion()
	{
		return ended ? attackRulesVersion : 0;
	}

	/** The avoidable part of the damage taken, or -1 (unknown) while the fight runs or with no attack rules. */
	int getDamageTakenAvoidable()
	{
		return hasAvoidable() ? damageTakenAvoidable : -1;
	}

	/** HP taken by attack ({@code melee}, {@code melee off prayer}, {@code axe}, ...); empty while running or with no rules. */
	java.util.Map<String, Integer> getDamageTakenByAttack()
	{
		return hasAvoidable() && damageTakenByAttack != null ? java.util.Collections.unmodifiableMap(damageTakenByAttack)
			: java.util.Collections.emptyMap();
	}

	/** The eating ticks the avoidable hits cost, or -1 (unknown) while the fight runs or with no attack rules. */
	double getAvoidableEatingTicks()
	{
		return hasAvoidable() ? avoidableEatingTicks : -1;
	}

	/** The eating line the replay used, or -1 (unknown) while the fight runs or with no attack rules. */
	int getEatLineHp()
	{
		return hasAvoidable() ? eatLineHp : -1;
	}

	/** Where the line came from ({@link AvoidableEating#LINE_THIS_KILL} and the others), or null when unknown. */
	String getEatLineSource()
	{
		return hasAvoidable() ? eatLineSource : null;
	}

	/** The target's drains over the fight (T-049), recorded once as it ends. Null records nothing. */
	void recordDrain(DrainRecord record)
	{
		drainRecord = record;
	}

	/** The fight's drain record (T-049), or null: saved before T-049, or nothing known of the target. */
	DrainRecord getDrainRecord()
	{
		return drainRecord;
	}

	/** The target's Defence when the fight opened, after any drains before it; -1 unknown (T-049). */
	int getDefenceStart()
	{
		return drainRecord == null ? -1 : drainRecord.defenceStart;
	}

	/** Its Defence as the fight ended; -1 unknown. */
	int getDefenceEnd()
	{
		return drainRecord == null ? -1 : drainRecord.defenceEnd;
	}

	/** Its Magic when the fight opened; -1 unknown. */
	int getMagicStart()
	{
		return drainRecord == null ? -1 : drainRecord.magicStart;
	}

	/** Its Magic as the fight ended; -1 unknown. */
	int getMagicEnd()
	{
		return drainRecord == null ? -1 : drainRecord.magicEnd;
	}

	/** Its undrained Defence, as the raid scaled it; -1 unknown. */
	int getDefenceBase()
	{
		return drainRecord == null ? -1 : drainRecord.defenceBase;
	}

	/** The lowest its Defence could be drained to: its base where it is immune; -1 unknown. */
	int getDefenceFloor()
	{
		return drainRecord == null ? -1 : drainRecord.defenceFloor;
	}

	/** The player's own draining specials thrown at it; -1 unknown. */
	int getOwnDrainSpecsThrown()
	{
		return drainRecord == null ? -1 : drainRecord.ownThrown();
	}

	/** Of those, the ones that landed; -1 unknown. */
	int getOwnDrainSpecsLanded()
	{
		return drainRecord == null ? -1 : drainRecord.ownLanded();
	}

	/** Party members' drains on it; -1 unknown. */
	int getPartyDrains()
	{
		return drainRecord == null ? -1 : drainRecord.partyDrains;
	}

	void end(boolean died, long now)
	{
		ended = true;
		targetDied = died;
		endMillis = now;
	}

	long durationMillis()
	{
		final long stop = ended ? endMillis : lastActivityMillis;
		return Math.max(0, stop - startMillis);
	}

	double durationSeconds()
	{
		// Floor at one tick so a one-shot kill doesn't produce an infinite DPS.
		return Math.max(0.6, durationMillis() / 1000.0);
	}

	double dps()
	{
		return damageDealt / durationSeconds();
	}

	/**
	 * Mean damage per attack, counting misses as zero. This is what the expected
	 * side is compared against, depending only on the loadout and the target.
	 */
	double averageHit()
	{
		final int resolved = resolvedAttempts();
		return resolved == 0 ? 0 : (double) damageDealt / resolved;
	}

	/** 0..1; share of the npcs my attacks reached that took damage. */
	double accuracy()
	{
		final int resolved = resolvedTargets();
		return resolved == 0 ? 0 : (double) hits / resolved;
	}

	/**
	 * One sample of the expected figures for the attack just resolved. A
	 * negative accuracy or average hit means no target stats were available.
	 */
	void recordExpected(int maxHit, double accuracy, double averageHit)
	{
		recordExpected(maxHit, accuracy, averageHit, -1);
	}

	/**
	 * As above, with the variance of the attack's damage (T-028); negative when
	 * it has none, which leaves the fight's spread unknown.
	 */
	void recordExpected(int maxHit, double accuracy, double averageHit, double damageVariance)
	{
		if (maxHit > 0)
		{
			sumExpectedMaxHit += maxHit;
			expectedMaxHitSamples++;
		}
		if (accuracy >= 0 && averageHit >= 0)
		{
			sumExpectedAccuracy += accuracy;
			final double landed = landedChance(accuracy, maxHit);
			sumExpectedLanded += landed;
			sumExpectedLandedVariance += landed * (1 - landed);
			sumExpectedAverageHit += averageHit;
			expectedTargetSamples++;
			if (damageVariance >= 0)
			{
				sumExpectedDamageVariance += damageVariance;
				expectedVarianceSamples++;
			}
			if (timeline != null)
			{
				timeline.expected(averageHit, damageVariance);
			}
		}
	}

	/**
	 * The variance of the damage the attacks were expected to deal, summed
	 * (T-028): what a luck percentile divides by. -1 when unknown, because an
	 * attack in it had no figure (a fight saved before T-028) or none was made.
	 */
	double damageVariance()
	{
		return expectedTargetSamples > 0 && expectedVarianceSamples == expectedTargetSamples
			? Math.max(0, sumExpectedDamageVariance + outcomeVarianceShift) : -1;
	}

	/**
	 * What every attack was expected to deal, summed: averaged over the player's
	 * own draining specials' outcomes (T-050), so a missed hammer is bad luck and
	 * a landed one good. The same as {@link #getRealizedExpected()} until a
	 * special has branched them, and on every fight saved before T-050.
	 */
	double getSumExpectedAverageHit()
	{
		return sumExpectedAverageHit + outcomeExpectedShift;
	}

	/** The attacks' expectations as made, each at the state the target was really in (T-050): E(realized). */
	double getRealizedExpected()
	{
		return sumExpectedAverageHit;
	}

	/**
	 * T-050's figures, as they stand: what averaging over the specials' outcomes
	 * adds to the expected damage and its variance, and the drains' worth realized
	 * and averaged. Set again after every attack, so live luck includes them.
	 */
	void recordSpecOutcomes(double expectedShift, double varianceShift, double valueDamage, double valueExpected)
	{
		outcomeExpectedShift = expectedShift;
		outcomeVarianceShift = varianceShift;
		drainValueKnown = true;
		drainValueDamage = valueDamage;
		drainValueExpected = valueExpected;
	}

	/** Whether the drains' worth is known: false before T-050 and where nothing could be drained. */
	boolean hasDrainValue()
	{
		return drainValueKnown;
	}

	/**
	 * The drains' worth in damage: the attacks' expectation as made less what it
	 * would have been with nothing drained, the party's drains included. NaN when
	 * unknown.
	 */
	double getDrainValueDamage()
	{
		return drainValueKnown ? drainValueDamage : Double.NaN;
	}

	/** The same, averaged over the player's own specials' outcomes; NaN when unknown. */
	double getDrainValueExpected()
	{
		return drainValueKnown ? drainValueExpected : Double.NaN;
	}

	/**
	 * E(realized) - E(averaged): the part of luck that came from the specials,
	 * positive when they landed better than average. The rest of luck is the
	 * attacks' own rolls. NaN when unknown.
	 */
	double getSpecLuckDamage()
	{
		return drainValueKnown ? -outcomeExpectedShift : Double.NaN;
	}

	/** Damage as seconds, the way luck turns damage into time (T-050): over this fight, NaN where it has no rate. */
	double luckSeconds(double damage)
	{
		return DrainValue.seconds(damage, isScored() ? combatTicks : 0, getSumExpectedAverageHit(),
			Math.max(0, thrallDamage) + Math.max(0, dotDamage) + Math.max(0, reflectDamage));
	}

	/**
	 * The share of fights with these same attacks that would have dealt less
	 * (T-028): Φ of the player's damage against the expected, over the spread.
	 * -1 when the spread is unknown.
	 */
	double luckPercentile()
	{
		return DamageMoments.percentile(damageDealt, getSumExpectedAverageHit(), damageVariance());
	}

	/** Mean expected max hit over the attacks made, or -1 if never sampled. */
	double expectedMaxHit()
	{
		return expectedMaxHitSamples == 0 ? -1 : sumExpectedMaxHit / expectedMaxHitSamples;
	}

	/** Mean expected hit chance (0..1) over the attacks made, or -1 if never sampled. */
	double expectedAccuracy()
	{
		return expectedTargetSamples == 0 ? -1 : sumExpectedAccuracy / expectedTargetSamples;
	}

	/** Mean expected damage per attack over the attacks made, or -1 if never sampled. */
	double expectedAverageHit()
	{
		return expectedTargetSamples == 0 ? -1 : sumExpectedAverageHit / expectedTargetSamples;
	}

	// The prayer-dependent half of an attack. Apart from recordAttackMade because a projectile's prayer is only known when
	// it resolves, and the flag and the efficiency pair have to come from the same reading.
	void recordAttackResolved(boolean prayed, boolean switched, double actualSetup, double idealSetup)
	{
		recordAttackResolved(prayed, switched, actualSetup, idealSetup, 0, 0);
	}

	/**
	 * As above, with what this attack lost to a missing boost and to a missed
	 * switch, in expected damage. Counted only where the setup pair is, so the
	 * parts and the whole are summed over the same attacks.
	 */
	void recordAttackResolved(boolean prayed, boolean switched, double actualSetup, double idealSetup,
		double boostCost, double switchCost)
	{
		if (prayed)
		{
			attacksPrayed++;
		}
		if (switched)
		{
			attacksSwitched++;
		}
		if (actualSetup >= 0 && idealSetup > 0)
		{
			sumActualSetup += actualSetup;
			sumIdealSetup += idealSetup;
			sumBoostCost += Math.max(0, boostCost);
			sumSwitchCost += Math.max(0, switchCost);
		}
	}

	/**
	 * An attack that went out and never got an answer: the target died or
	 * changed form while it was in the air, so the damage was nulled.
	 */
	void recordAttackNulled()
	{
		nulled++;
	}

	/** Attacks that actually got an answer, which is what the damage divides by. */
	int resolvedAttempts()
	{
		return Math.max(0, attempts - nulled);
	}

	/**
	 * Another npc caught by the attack already booked - chinchompas, a barrage,
	 * a scythe's arc. One throw stays one attack: this adds its damage and one
	 * more target for the accuracy to divide by, and never touches attempts.
	 *
	 * @param newTarget whether this is the first hitsplat this npc took from
	 *                  this attack, so a burst on one npc counts as one target
	 * @param landed    whether this is the splat that makes the npc a hit
	 */
	void recordExtraTarget(int damage, long now, boolean newTarget, boolean landed)
	{
		damageDealt += damage;
		if (newTarget)
		{
			targets++;
		}
		if (landed)
		{
			hits++;
		}
		lastActivityMillis = now;
		if (timeline != null)
		{
			timeline.damage(damage, landed);
		}
	}

	/**
	 * The npcs this fight's attacks were aimed at, which stops being the number
	 * of attacks the moment one throw can hit several. Accuracy divides by this:
	 * a chinchompa that catches five and connects with three is three of five,
	 * not three of one.
	 */
	int resolvedTargets()
	{
		// NOT reduced by the nulled attacks, unlike the attempts the damage divides by. An attack that was thrown was
		// thrown, and striking it from the accuracy left the Hits line reading 13 of 17 while prayed, potted and switches
		// on the same panel all read out of 18. One denominator, and it is the one you can count yourself.
		//
		// A fight read back from an older history file has no targets at all: deserialisation fills fields without running
		// initialisers, so the count is zero where it should be one per attack. Falling back to the attempts keeps every
		// kill already on disk reading as it did, instead of reporting 0% accuracy for all of them.
		return targets == 0 ? attempts : targets;
	}

	void recordAttackMade(boolean potted)
	{
		recordAttackMade(potted, -1);
	}

	/**
	 * @param boostShare how much of the full boost the attack's stats held, 0
	 *                   to 1, or negative when it was not read
	 */
	void recordAttackMade(boolean potted, double boostShare)
	{
		recordAttackMade(potted, boostShare, false);
	}

	/**
	 * @param drained whether any stat the attack rolled on was below its base
	 */
	void recordAttackMade(boolean potted, double boostShare, boolean drained)
	{
		if (drained)
		{
			attacksDrained++;
		}
		if (boostShare >= 0)
		{
			sumBoostShare += Math.min(1, boostShare);
			boostSamples++;
		}
		attacking = true;
		combatTicks++;
		attacksMade++;
		// The npc it was aimed at. Anything else it catches adds itself as the hitsplats arrive.
		targets++;
		// One attack, one attempt. Counted here rather than where the damage arrives, so a spec landing four hitsplats is the
		// single attack it was and both sides are denominated per attack thrown.
		attempts++;
		if (potted)
		{
			attacksPotted++;
		}
		if (timeline != null)
		{
			timeline.attackMade();
		}
	}

	/**
	 * What the attacks were set up to deal as a share of what they would have
	 * with the intended prayer and full boost. -1 until an attack has been made
	 * against a target with stats.
	 */
	double efficiency()
	{
		return sumIdealSetup <= 0 ? -1 : sumActualSetup / sumIdealSetup;
	}

	/** The mean share of the full boost the attacks held, or -1 when none was read (a fight saved before T-010). */
	double boostShare()
	{
		return boostSamples == 0 ? -1 : sumBoostShare / boostSamples;
	}

	/**
	 * What the missing boost cost, as a share of the damage the attacks would
	 * have been set up to deal. With switchCostShare and the prayer's part it
	 * makes up 1 - efficiency. -1 when nothing was comparable.
	 */
	double boostCostShare()
	{
		return sumIdealSetup <= 0 ? -1 : sumBoostCost / sumIdealSetup;
	}

	/** What the missed switches cost, on the same terms as boostCostShare. */
	double switchCostShare()
	{
		return sumIdealSetup <= 0 ? -1 : sumSwitchCost / sumIdealSetup;
	}

	/**
	 * A tick that passed with the weapon off cooldown and no attack made.
	 * Ignored before the first attack: walking to a boss is not wasted time.
	 */
	void recordTickLost(boolean eating)
	{
		recordTickLost(eating, false);
	}

	/**
	 * As above, but the exemption is the ROOM's rather than this fight's.
	 *
	 * <p>It was written for the walk to a boss and it is right for the first
	 * fight in a room. Applied to every fight it becomes a hole: a room whose
	 * fights are one attack each - chinning a pile of adds opens a fight per
	 * npc, and a target that dies and is replaced does the same - never gets
	 * past the exemption, so the tick loss reads zero however long the room
	 * takes. A real tightrope room booked 8 lost ticks against 1354 that
	 * passed.
	 *
	 * <p>Once anything in the room has been attacked, the walk is over and every
	 * fight under it counts from its first tick.
	 */
	void recordTickLost(boolean eating, boolean roomEngaged)
	{
		if (!attacking && !roomEngaged)
		{
			return;
		}
		if (eating)
		{
			ticksLostEating++;
		}
		else
		{
			ticksLostOther++;
		}
		combatTicks++;
		if (timeline != null)
		{
			timeline.tickLost(eating);
		}
	}

	/** Every tick off cooldown with no attack, whatever the reason. */
	int getTicksLost()
	{
		return ticksLostEating + ticksLostOther;
	}

	/** A tick that passed while the weapon was still on cooldown. */
	void recordTickSpent()
	{
		recordTickSpent(false);
	}

	/** As above, under the room's exemption rather than this fight's. */
	void recordTickSpent(boolean roomEngaged)
	{
		if (attacking || roomEngaged)
		{
			combatTicks++;
			if (timeline != null)
			{
				timeline.tickSpent();
			}
		}
	}

	/**
	 * Ticks lost as a share of those since the first attack, so a long fight and
	 * a short one compare. -1 before the first attack.
	 */
	double ticksLostShare()
	{
		return combatTicks <= 0 ? -1 : (double) getTicksLost() / combatTicks;
	}

	int getCombatTicks()
	{
		return combatTicks;
	}
}
