package com.pvmlogs;

import lombok.Getter;

// Running totals for the current trip, so the overlay can show how a session went rather than resetting at each kill.
// One unlucky kill says little.
@Getter
class SessionTotals
{
	private long startMillis;
	private long lastActivityMillis;
	private long firstActivityMillis;
	private int fights;
	private int kills;
	private int damageDealt;
	// Our thrall's damage and damage over time (burn, poison, venom, bleed), fed where Fight's are. Kept out of damage
	// dealt like Fight keeps them, and shown beside it (T-014).
	private int thrallDamage;
	private int dotDamage;
	// Recoil and vengeance (T-017 Part B), kept out of damage dealt like the thrall's.
	private int reflectDamage;
	// The damage the player took in the trip's scored fights, fed where Fight's is (T-016), and its breakdown (T-018):
	// see Fight. Hits only since T-018.
	private int damageTaken;
	private int dotTaken;
	private int hitsTaken;
	/** T-076: see Fight's. */
	private int missedHitsTaken;
	private int biggestHitTaken;
	private int damageTakenUnprotected;
	// T-041: the trip's scored fights that ended at a boss with attack rules, and what Fight's avoidable figures add up
	// to over them. Added as each fight ends, never while it runs. The getters read -1 while there are none.
	private int ruledFights;
	private int damageTakenAvoidable;
	private double avoidableEatingTicks;
	private final java.util.Map<String, Integer> damageTakenByAttack = new java.util.LinkedHashMap<>();
	// T-062: dotDamage and dotTaken by kind, fed with them. See Fight.
	private final java.util.Map<String, Integer> dotDamageByKind = new java.util.LinkedHashMap<>();
	private final java.util.Map<String, Integer> dotTakenByKind = new java.util.LinkedHashMap<>();
	private int attackRulesVersion;
	private int attempts;
	private int targets;
	private int hits;
	// Attacks that went out and never resolved, because the target died or changed form in flight. Not misses; see
	// Fight.recordAttackNulled.
	private int nulled;
	private int attacksMade;
	private int attacksPrayed;
	private int attacksPotted;
	// Attacks thrown with nothing better available to switch into. The gap to attacksMade is the number that missed at
	// least one switch.
	private int attacksSwitched;
	private double sumActualSetup;
	private double sumIdealSetup;
	// See Fight: the boost share read per attack, and what the missing boost and missed switches cost (T-010).
	private double sumBoostShare;
	private int boostSamples;
	private double sumBoostCost;
	private double sumSwitchCost;
	// See Fight: attacks made with a stat below its base (T-015).
	private int attacksDrained;
	private int ticksLostEating;
	private int ticksLostOther;
	// See Fight: idle ticks a boss's mechanic excused (T-074).
	private int ticksMechanics;
	private int combatTicks;
	// The ticks of the trip's scored fights only (T-024): the time its damage, expected and thrall/DoT/reflect totals come
	// from, since those are fed behind isScored(). combatTicks stays every fight's, the Sire's respiratory systems included.
	private int scoredCombatTicks;
	// Running totals of what each attack was expected to do, so the measured side can be compared against the sum of every
	// weapon's own contribution.
	private double sumExpectedAccuracy;
	private double sumExpectedAverageHit;
	// The variance of each scored attack's damage (T-028), summed beside the expected; see Fight. The trip only lives in
	// memory, so every attack in it carries one, bar any recorded without (expectedSamples counts both).
	private double sumExpectedDamageVariance;
	private int expectedSamples;
	private int expectedVarianceSamples;
	// T-050: what averaging over the player's own draining specials' outcomes adds to the two sums above, live, and the
	// ended scored fights' drain worth (realized and averaged) and the specials' share of luck.
	private double outcomeExpectedShift;
	private double outcomeVarianceShift;
	private int drainValueFights;
	private double drainValueDamage;
	private double drainValueExpected;
	private double specLuckDamage;

	SessionTotals(long now)
	{
		this.startMillis = now;
		this.lastActivityMillis = now;
	}

	void reset(long now)
	{
		startMillis = now;
		lastActivityMillis = now;
		firstActivityMillis = 0;
		fights = 0;
		kills = 0;
		damageDealt = 0;
		thrallDamage = 0;
		dotDamage = 0;
		reflectDamage = 0;
		damageTaken = 0;
		dotTaken = 0;
		hitsTaken = 0;
		missedHitsTaken = 0;
		biggestHitTaken = 0;
		damageTakenUnprotected = 0;
		ruledFights = 0;
		damageTakenAvoidable = 0;
		avoidableEatingTicks = 0;
		damageTakenByAttack.clear();
		dotDamageByKind.clear();
		dotTakenByKind.clear();
		attackRulesVersion = 0;
		attempts = 0;
		targets = 0;
		hits = 0;
		nulled = 0;
		attacksMade = 0;
		attacksPrayed = 0;
		attacksPotted = 0;
		attacksSwitched = 0;
		sumActualSetup = 0;
		sumIdealSetup = 0;
		sumBoostShare = 0;
		boostSamples = 0;
		sumBoostCost = 0;
		sumSwitchCost = 0;
		attacksDrained = 0;
		ticksLostEating = 0;
		ticksLostOther = 0;
		ticksMechanics = 0;
		combatTicks = 0;
		scoredCombatTicks = 0;
		sumExpectedAccuracy = 0;
		sumExpectedAverageHit = 0;
		sumExpectedDamageVariance = 0;
		expectedSamples = 0;
		expectedVarianceSamples = 0;
		outcomeExpectedShift = 0;
		outcomeVarianceShift = 0;
		drainValueFights = 0;
		drainValueDamage = 0;
		drainValueExpected = 0;
		specLuckDamage = 0;
	}

	/**
	 * T-123: whether a fight or room that began at {@code firstStartMillis} is this trip's: it began at or after the trip
	 * did. One running when New trip was clicked stays the old trip's, so the plugin books nothing of it here.
	 */
	boolean holds(long firstStartMillis)
	{
		return firstStartMillis >= startMillis;
	}

	void recordFightEnded(boolean died, Fight fight, long now)
	{
		fights++;
		if (died)
		{
			kills++;
		}
		// The avoidable figures exist only once the fight has ended (T-041); a scored fight's go into the trip, like its
		// damage taken.
		if (fight != null && fight.isScored() && fight.hasDrainValue())
		{
			drainValueFights++;
			drainValueDamage += fight.getDrainValueDamage();
			drainValueExpected += fight.getDrainValueExpected();
			specLuckDamage += fight.getSpecLuckDamage();
		}
		if (fight != null && fight.isScored() && fight.hasAvoidable())
		{
			ruledFights++;
			damageTakenAvoidable += fight.getDamageTakenAvoidable();
			avoidableEatingTicks += fight.getAvoidableEatingTicks();
			fight.getDamageTakenByAttack().forEach((name, hp) -> damageTakenByAttack.merge(name, hp, Integer::sum));
			attackRulesVersion = Math.max(attackRulesVersion, fight.getAttackRulesVersion());
		}
		noteActivity(now);
	}

	/** The avoidable part of the damage taken over the trip's ended fights with attack rules (T-041), or -1 with none. */
	int getDamageTakenAvoidable()
	{
		return ruledFights > 0 ? damageTakenAvoidable : -1;
	}

	/** The eating ticks the avoidable hits cost over the same fights (T-041), or -1 with none. */
	double getAvoidableEatingTicks()
	{
		return ruledFights > 0 ? Math.round(avoidableEatingTicks * 10) / 10.0 : -1;
	}

	/** HP taken by attack over the same fights (T-041). */
	java.util.Map<String, Integer> getDamageTakenByAttack()
	{
		return java.util.Collections.unmodifiableMap(damageTakenByAttack);
	}

	/**
	 * Books a tick that passed with the weapon off cooldown and no attack made.
	 *
	 * @param scored whether the fight the tick was spent on is scored (T-024)
	 */
	void recordTickLost(boolean eating, boolean scored)
	{
		if (eating)
		{
			ticksLostEating++;
		}
		else
		{
			ticksLostOther++;
		}
		recordTick(scored);
	}

	/** An idle tick a boss's mechanic excused (T-074): a combat tick, not lost. */
	void recordTickMechanic(boolean scored)
	{
		ticksMechanics++;
		recordTick(scored);
	}

	/** A tick that passed while the weapon was still on cooldown, or an attack on an unscored target. */
	void recordTickSpent(boolean scored)
	{
		recordTick(scored);
	}

	private void recordTick(boolean scored)
	{
		combatTicks++;
		if (scored)
		{
			scoredCombatTicks++;
		}
	}

	/** Damage from one hitsplat; see {@code Fight.recordDamageDealt}. */
	void recordAttempt(int damage, boolean landedAttack, long now)
	{
		damageDealt += damage;
		if (landedAttack)
		{
			hits++;
		}
		noteActivity(now);
	}

	/** See {@code Fight.recordThrallDamage}: real damage, but none of it the player's weapon. */
	void recordThrallDamage(int damage)
	{
		thrallDamage += Math.max(0, damage);
	}

	/** See {@code Fight.recordReflectDamage}. */
	void recordReflectDamage(int damage)
	{
		reflectDamage += Math.max(0, damage);
	}

	/** See {@code Fight.recordDotDamage}. */
	void recordDotDamage(int damage)
	{
		dotDamage += Math.max(0, damage);
	}

	/** See {@code Fight.recordDotDamage(int, int)}: by the splat's hitsplat type (T-062). */
	void recordDotDamage(int damage, int type)
	{
		recordDotDamage(damage);
		DotKinds.add(dotDamageByKind, HitsplatKind.typeName(type), damage);
	}

	/** dotDamage by kind (T-062). */
	java.util.Map<String, Integer> getDotDamageByKind()
	{
		return java.util.Collections.unmodifiableMap(dotDamageByKind);
	}

	/** dotTaken by kind (T-062). */
	java.util.Map<String, Integer> getDotTakenByKind()
	{
		return java.util.Collections.unmodifiableMap(dotTakenByKind);
	}

	/** See {@code Fight.recordHitTaken}. */
	void recordHitTaken(int damage, boolean unprotected)
	{
		final int hit = Math.max(0, damage);
		damageTaken += hit;
		hitsTaken++;
		if (hit == 0)
		{
			missedHitsTaken++;
		}
		biggestHitTaken = Math.max(biggestHitTaken, hit);
		if (unprotected)
		{
			damageTakenUnprotected += hit;
		}
	}

	/** See {@code Fight.recordDamageTaken}: a hit with no word on the prayer. */
	void recordDamageTaken(int damage)
	{
		recordHitTaken(damage, false);
	}

	/** See {@code Fight.recordDotTaken}. */
	void recordDotTaken(int damage)
	{
		dotTaken += Math.max(0, damage);
	}

	/** See {@code Fight.recordDotTaken(int, int, long)}: by the splat's hitsplat type (T-062). */
	void recordDotTaken(int damage, int type)
	{
		recordDotTaken(damage);
		DotKinds.add(dotTakenByKind, HitsplatKind.typeName(type), damage);
	}

	/** Takes back a splat recordAttempt counted, judged within its tick to be the thrall's instead. */
	void revokeAttempt(int damage, boolean landedAttack)
	{
		damageDealt = Math.max(0, damageDealt - damage);
		if (landedAttack && hits > 0)
		{
			hits--;
		}
	}

	/**
	 * Adds what this attack was expected to do. The max hit is not accumulated:
	 * it is a property of the loadout rather than something that adds up.
	 */
	void recordAttackMade(boolean potted)
	{
		recordAttackMade(potted, -1);
	}

	/** See {@code Fight.recordAttackMade(boolean, double)}. */
	void recordAttackMade(boolean potted, double boostShare)
	{
		recordAttackMade(potted, boostShare, false);
	}

	/** See {@code Fight.recordAttackMade(boolean, double, boolean)}. */
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
		attacksMade++;
		// Fed only for a scored fight's attacks; an unscored one's tick goes through recordTickSpent(false).
		recordTick(true);
		attempts++;
		targets++;
		if (potted)
		{
			attacksPotted++;
		}
	}

	void recordAttackNulled()
	{
		nulled++;
	}

	/** See {@code Fight.recordExtraTarget}: another npc caught by one throw. */
	void recordExtraTarget(int damage, boolean newTarget, boolean landed, long now)
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
		noteActivity(now);
	}

	/** Attacks that actually got an answer, which is what the damage divides by. */
	int resolvedAttempts()
	{
		return Math.max(0, attempts - nulled);
	}

	/** Npcs reached; see {@code Fight.resolvedTargets}. Never reduced by the nulled attacks. */
	int resolvedTargets()
	{
		return targets;
	}

	double accuracy()
	{
		final int resolved = resolvedTargets();
		return resolved == 0 ? 0 : (double) hits / resolved;
	}

	void recordAttackResolved(boolean prayed, boolean switched, double actualSetup, double idealSetup)
	{
		recordAttackResolved(prayed, switched, actualSetup, idealSetup, 0, 0);
	}

	/** See {@code Fight.recordAttackResolved(boolean, boolean, double, double, double, double)}. */
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

	double efficiency()
	{
		return sumIdealSetup <= 0 ? -1 : sumActualSetup / sumIdealSetup;
	}

	/** See {@code Fight.boostShare}. */
	double boostShare()
	{
		return boostSamples == 0 ? -1 : sumBoostShare / boostSamples;
	}

	/** See {@code Fight.boostCostShare}. */
	double boostCostShare()
	{
		return sumIdealSetup <= 0 ? -1 : sumBoostCost / sumIdealSetup;
	}

	/** See {@code Fight.switchCostShare}. */
	double switchCostShare()
	{
		return sumIdealSetup <= 0 ? -1 : sumSwitchCost / sumIdealSetup;
	}

	void recordExpected(double accuracy, double averageHit)
	{
		recordExpected(accuracy, averageHit, -1);
	}

	/** As above, with the attack's damage variance (T-028); negative for none. */
	void recordExpected(double accuracy, double averageHit, double damageVariance)
	{
		if (accuracy >= 0 && averageHit >= 0)
		{
			sumExpectedAccuracy += accuracy;
			sumExpectedAverageHit += averageHit;
			expectedSamples++;
			if (damageVariance >= 0)
			{
				sumExpectedDamageVariance += damageVariance;
				expectedVarianceSamples++;
			}
		}
	}

	/** The trip's damage variance (T-028), as {@link Fight#damageVariance()}: -1 when unknown or nothing was scored. */
	double damageVariance()
	{
		return expectedSamples > 0 && expectedVarianceSamples == expectedSamples
			? Math.max(0, sumExpectedDamageVariance + outcomeVarianceShift) : -1;
	}

	/** The trip's expected damage, averaged over the player's own draining specials' outcomes (T-050). */
	double getSumExpectedAverageHit()
	{
		return sumExpectedAverageHit + outcomeExpectedShift;
	}

	/**
	 * A change in a fight's T-050 shifts (see {@link Fight#recordSpecOutcomes}),
	 * carried into the trip as it happens, so the trip's live luck has them too.
	 */
	void recordOutcomeShift(double expectedDelta, double varianceDelta)
	{
		outcomeExpectedShift += expectedDelta;
		outcomeVarianceShift += varianceDelta;
	}

	/** The drains' worth over the trip's ended scored fights (T-050), realized; NaN with none. */
	double getDrainValueDamage()
	{
		return drainValueFights > 0 ? drainValueDamage : Double.NaN;
	}

	/** The same, averaged over the player's own specials' outcomes. */
	double getDrainValueExpected()
	{
		return drainValueFights > 0 ? drainValueExpected : Double.NaN;
	}

	/** E(realized) - E(averaged) over the same fights: the specials' share of luck. */
	double getSpecLuckDamage()
	{
		return drainValueFights > 0 ? specLuckDamage : Double.NaN;
	}

	/** Damage as seconds over the trip's scored fights, as luck's are (T-050). */
	double luckSeconds(double damage)
	{
		return DrainValue.seconds(damage, scoredCombatTicks, getSumExpectedAverageHit(),
			Math.max(0, thrallDamage) + Math.max(0, dotDamage) + Math.max(0, reflectDamage));
	}

	/**
	 * How long the trip has run, from its first attack rather than from when the
	 * plugin started.
	 */
	long durationMillis()
	{
		return firstActivityMillis == 0 ? 0
			: Math.max(0, lastActivityMillis - firstActivityMillis);
	}

	// Stamped by the first thing that happens rather than at construction, so the trip reads no time at all until the
	// first attack.
	private void noteActivity(long now)
	{
		if (firstActivityMillis == 0)
		{
			firstActivityMillis = now;
		}
		lastActivityMillis = now;
	}

	int getTicksLost()
	{
		return ticksLostEating + ticksLostOther;
	}

	double ticksLostShare()
	{
		return combatTicks <= 0 ? -1 : (double) getTicksLost() / combatTicks;
	}
}
