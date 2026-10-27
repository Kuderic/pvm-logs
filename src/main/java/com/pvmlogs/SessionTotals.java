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
	private int biggestHitTaken;
	private int damageTakenUnprotected;
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
		biggestHitTaken = 0;
		damageTakenUnprotected = 0;
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
		combatTicks = 0;
		scoredCombatTicks = 0;
		sumExpectedAccuracy = 0;
		sumExpectedAverageHit = 0;
		sumExpectedDamageVariance = 0;
		expectedSamples = 0;
		expectedVarianceSamples = 0;
	}

	void recordFightEnded(boolean died, Fight fight, long now)
	{
		fights++;
		if (died)
		{
			kills++;
		}
		noteActivity(now);
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

	/** See {@code Fight.recordHitTaken}. */
	void recordHitTaken(int damage, boolean unprotected)
	{
		final int hit = Math.max(0, damage);
		damageTaken += hit;
		hitsTaken++;
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
		return expectedSamples > 0 && expectedVarianceSamples == expectedSamples ? sumExpectedDamageVariance : -1;
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
