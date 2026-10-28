package com.pvmlogs;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

// One run of a raid: its rooms, in the order they were fought.
@Getter
class Raid
{
	private final RaidType type;
	private final String name;
	private final int id;
	private final long startMillis;
	private final List<Encounter> encounters = new ArrayList<>();
	private long endMillis;

	Raid(RaidType type, int id, long now)
	{
		this(type, type.getDisplayName(), id, now);
	}

	/** Rebuilt from a history file, which stores the raid's name and not its type. */
	Raid(String name, int id, long now)
	{
		this(null, name, id, now);
	}

	private Raid(RaidType type, String name, int id, long now)
	{
		this.type = type;
		this.name = name;
		this.id = id;
		this.startMillis = now;
	}

	void add(Encounter encounter)
	{
		encounters.add(encounter);
	}

	void end(long now)
	{
		endMillis = now;
	}

	int getThrallDamage()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getThrallDamage();
		}
		return total;
	}

	int getReflectDamage()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getReflectDamage();
		}
		return total;
	}

	int getDotDamage()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getDotDamage();
		}
		return total;
	}

	/** {@link #getDotDamage()} by kind (T-062). */
	java.util.Map<String, Integer> getDotDamageByKind()
	{
		final java.util.Map<String, Integer> total = new java.util.LinkedHashMap<>();
		for (Encounter encounter : encounters)
		{
			DotKinds.addAll(total, encounter.getDotDamageByKind());
		}
		return total;
	}

	/** The scored fights' DoT by kind across the rooms (T-062). */
	java.util.Map<String, Integer> getScoredDotDamageByKind()
	{
		final java.util.Map<String, Integer> total = new java.util.LinkedHashMap<>();
		for (Encounter encounter : encounters)
		{
			DotKinds.addAll(total, encounter.getScoredDotDamageByKind());
		}
		return total;
	}

	int getHealed()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getHealed();
		}
		return total;
	}

	/** The damage the player took across the rooms (T-016, for the overlay). */
	int getDamageTaken()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getDamageTaken();
		}
		return total;
	}

	/** Damage over time on the player across the rooms (T-018). */
	int getDotTaken()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getDotTaken();
		}
		return total;
	}

	/** {@link #getDotTaken()} by kind (T-062). */
	java.util.Map<String, Integer> getDotTakenByKind()
	{
		final java.util.Map<String, Integer> total = new java.util.LinkedHashMap<>();
		for (Encounter encounter : encounters)
		{
			DotKinds.addAll(total, encounter.getDotTakenByKind());
		}
		return total;
	}

	/** Hit splats on the player across the rooms, 0s included (T-018). */
	int getHitsTaken()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getHitsTaken();
		}
		return total;
	}

	/** The biggest hit on the player in any room (T-018). */
	int getBiggestHitTaken()
	{
		int biggest = 0;
		for (Encounter encounter : encounters)
		{
			biggest = Math.max(biggest, encounter.getBiggestHitTaken());
		}
		return biggest;
	}

	/** Hit damage on the player with no protection prayer up, across the rooms (T-018). */
	int getDamageTakenUnprotected()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getDamageTakenUnprotected();
		}
		return total;
	}

	/** The avoidable part of the damage taken across the rooms (T-041), or -1 where none has a figure. */
	int getDamageTakenAvoidable()
	{
		int total = 0;
		boolean any = false;
		for (Encounter encounter : encounters)
		{
			final int room = encounter.getDamageTakenAvoidable();
			if (room >= 0)
			{
				total += room;
				any = true;
			}
		}
		return any ? total : -1;
	}

	/** The eating ticks the avoidable hits cost across the rooms (T-041), or -1 where none has a figure. */
	double getAvoidableEatingTicks()
	{
		double total = 0;
		boolean any = false;
		for (Encounter encounter : encounters)
		{
			final double room = encounter.getAvoidableEatingTicks();
			if (room >= 0)
			{
				total += room;
				any = true;
			}
		}
		return any ? Math.round(total * 10) / 10.0 : -1;
	}

	/** HP taken by attack across the rooms (T-041). */
	java.util.Map<String, Integer> getDamageTakenByAttack()
	{
		final java.util.Map<String, Integer> total = new java.util.LinkedHashMap<>();
		for (Encounter encounter : encounters)
		{
			encounter.getDamageTakenByAttack().forEach((name, hp) -> total.merge(name, hp, Integer::sum));
		}
		return total;
	}

	/** The newest attack rules any room was classed by, or 0 (T-041). */
	int getAttackRulesVersion()
	{
		int version = 0;
		for (Encounter encounter : encounters)
		{
			version = Math.max(version, encounter.getAttackRulesVersion());
		}
		return version;
	}

	/** The first reason any room gives not to trust its expected side, or null. */
	String getStatsWarning()
	{
		for (Encounter encounter : encounters)
		{
			if (encounter.getStatsWarning() != null)
			{
				return encounter.getStatsWarning();
			}
		}
		return null;
	}

	int getDamageDealt()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getDamageDealt();
		}
		return total;
	}

	int getAttempts()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getAttempts();
		}
		return total;
	}

	int getTargets()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getTargets();
		}
		return total;
	}

	double accuracy()
	{
		final int targets = getTargets();
		return targets == 0 ? 0 : (double) getHits() / targets;
	}

	int getHits()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getHits();
		}
		return total;
	}

	/** The rooms' damage variance, summed (T-028); -1 when any room's is unknown, or none has one. */
	double sumExpectedDamageVariance()
	{
		double total = 0;
		boolean any = false;
		for (Encounter encounter : encounters)
		{
			if (encounter.sumExpectedAverageHit() <= 0)
			{
				continue;
			}
			final double variance = encounter.sumExpectedDamageVariance();
			if (variance < 0)
			{
				return -1;
			}
			total += variance;
			any = true;
		}
		return any ? total : -1;
	}

	double sumExpectedAverageHit()
	{
		double total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.sumExpectedAverageHit();
		}
		return total;
	}

	double sumExpectedAccuracy()
	{
		double total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.sumExpectedAccuracy();
		}
		return total;
	}

	int getAttacksMade()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getAttacksMade();
		}
		return total;
	}

	int getAttacksPrayed()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getAttacksPrayed();
		}
		return total;
	}

	int getAttacksSwitched()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getAttacksSwitched();
		}
		return total;
	}

	/** See {@code Fight.attacksDrained}: over every room. */
	int getAttacksDrained()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getAttacksDrained();
		}
		return total;
	}

	int getAttacksPotted()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getAttacksPotted();
		}
		return total;
	}

	double efficiency()
	{
		double actual = 0;
		double ideal = 0;
		for (Encounter encounter : encounters)
		{
			for (Fight fight : encounter.getFights())
			{
				if (fight.isScored())
				{
					actual += fight.getSumActualSetup();
					ideal += fight.getSumIdealSetup();
				}
			}
		}
		return ideal <= 0 ? -1 : actual / ideal;
	}

	/** See {@code Fight.boostShare}: over every room's scored fights, or -1 if none was read. */
	double boostShare()
	{
		double sum = 0;
		int samples = 0;
		for (Encounter encounter : encounters)
		{
			for (Fight fight : encounter.getFights())
			{
				if (fight.isScored())
				{
					sum += fight.getSumBoostShare();
					samples += fight.getBoostSamples();
				}
			}
		}
		return samples == 0 ? -1 : sum / samples;
	}

	/** See {@code Fight.boostCostShare}. */
	double boostCostShare()
	{
		return costShare(true);
	}

	/** See {@code Fight.switchCostShare}. */
	double switchCostShare()
	{
		return costShare(false);
	}

	private double costShare(boolean boost)
	{
		double cost = 0;
		double ideal = 0;
		for (Encounter encounter : encounters)
		{
			for (Fight fight : encounter.getFights())
			{
				if (fight.isScored())
				{
					cost += boost ? fight.getSumBoostCost() : fight.getSumSwitchCost();
					ideal += fight.getSumIdealSetup();
				}
			}
		}
		return ideal <= 0 ? -1 : cost / ideal;
	}

	int getTicksLost()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getTicksLost();
		}
		return total;
	}

	int getTicksLostEating()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getTicksLostEating();
		}
		return total;
	}

	int getCombatTicks()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getCombatTicks();
		}
		return total;
	}

	/** Thrall, DoT and reflected damage on the scored fights only (T-025); see {@link Encounter#getScoredThrallDamage()}. */
	int getScoredOtherDamage()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getScoredThrallDamage() + encounter.getScoredDotDamage() + encounter.getScoredReflectDamage();
		}
		return total;
	}

	// T-050: the rooms' drain figures summed; NaN where none knows them.
	private double drainSum(int which)
	{
		double total = 0;
		boolean any = false;
		for (Encounter encounter : encounters)
		{
			final double room = which == 0 ? encounter.getDrainValueDamage() : which == 1
				? encounter.getDrainValueExpected() : encounter.getSpecLuckDamage();
			if (!Double.isNaN(room))
			{
				total += room;
				any = true;
			}
		}
		return any ? total : Double.NaN;
	}

	/** The drains' worth in damage, realized (T-050); NaN when unknown. */
	double getDrainValueDamage()
	{
		return drainSum(0);
	}

	/** The same, averaged over the player's own specials' outcomes. */
	double getDrainValueExpected()
	{
		return drainSum(1);
	}

	/** E(realized) - E(averaged): the specials' share of luck. */
	double getSpecLuckDamage()
	{
		return drainSum(2);
	}

	/** Damage as seconds over the raid's scored fights, as luck's are (T-050). */
	double luckSeconds(double damage)
	{
		return DrainValue.seconds(damage, getScoredCombatTicks(), sumExpectedAverageHit(), getScoredOtherDamage());
	}

	/** The ticks of the scored fights only (T-023); see {@link Encounter#getScoredCombatTicks()}. */
	int getScoredCombatTicks()
	{
		int total = 0;
		for (Encounter encounter : encounters)
		{
			total += encounter.getScoredCombatTicks();
		}
		return total;
	}

	double ticksLostShare()
	{
		final int ticks = getCombatTicks();
		return ticks <= 0 ? -1 : (double) getTicksLost() / ticks;
	}

	long durationMillis()
	{
		long last = startMillis;
		for (Encounter encounter : encounters)
		{
			last = Math.max(last, encounter.getStartMillis() + encounter.durationMillis());
		}
		return Math.max(0, (endMillis > 0 ? endMillis : last) - startMillis);
	}
}
