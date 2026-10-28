package com.pvmlogs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.Getter;

// One boss or room: the fights that belong together, kept as a list rather than as a second set of counters.
@Getter
class Encounter
{
	private final String name;
	private final RaidType raid;
	private final long startMillis;
	private final List<Fight> fights = new ArrayList<>();
	private long endMillis;

	Encounter(String name, RaidType raid, long now)
	{
		this.name = name;
		this.raid = raid;
		this.startMillis = now;
	}

	void add(Fight fight)
	{
		fights.add(fight);
	}

	/** Whether a fight on this NPC continues this encounter or starts another. */
	boolean accepts(String candidateName)
	{
		return name.equals(candidateName);
	}

	void end(long now)
	{
		endMillis = now;
	}

	/**
	 * Whether killing this NPC is killing what this room is about. Asked of the
	 * group rather than the id: a boss made of several NPCs is killed at whichever
	 * part is last, and the Hueycoatl drops its loot from a head while the room is
	 * full of fights against body segments.
	 */
	boolean holds(int npcId)
	{
		for (Fight fight : fights)
		{
			if (fight.getTargetId() == npcId || EncounterGroup.sameGroup(npcId, fight.getTargetId()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether loot from this NPC closes the room (T-055): the room is open, holds
	 * a fight on it or its group, and the NPC is not one of the boss's adds, whose
	 * drops never mean the kill.
	 */
	boolean closesOnLootFrom(int npcId)
	{
		return !isEnded() && EncounterGroup.lootIsTheKill(npcId) && holds(npcId);
	}

	/** The parts that have gone down, by npc index, for a room that closes on deaths (T-061). */
	private final Set<Integer> partsDown = new HashSet<>();

	/**
	 * One of this room's parts went down - despawned dead ({@code fell} false) or
	 * changed into its beaten form - and whether that closes the room (T-061):
	 * the room is open, holds the NPC's group, and the group's death rule has now
	 * seen as many distinct parts go down as it needs. A boss whose room closes
	 * on loot never closes here.
	 */
	boolean closesOnDeathOf(int npcId, int npcIndex, boolean fell)
	{
		if (isEnded() || !holds(npcId))
		{
			return false;
		}
		final EncounterGroup.DeathClose rule = EncounterGroup.deathClose(npcId);
		if (rule == null || !rule.counts(npcId, fell))
		{
			return false;
		}
		partsDown.add(npcIndex);
		return partsDown.size() >= rule.deaths;
	}

	boolean isEnded()
	{
		return endMillis > 0;
	}

	/**
	 * Whether anything in this room has been attacked yet. Latched rather than
	 * counted, so the answer is the same after the fight that did it has been
	 * replaced - which is the whole point of asking the room instead of the
	 * fight - and so it costs nothing on a room holding hundreds of them.
	 */
	private boolean engaged;

	void noteAttack()
	{
		engaged = true;
	}

	boolean isEngaged()
	{
		return engaged;
	}

	/**
	 * Whether anything has been thrown in this room yet. A fight opens on merely
	 * looking at something, so a room with no attempts is one nothing happened in.
	 */
	boolean hasAttempts()
	{
		for (Fight fight : fights)
		{
			if (fight.getAttempts() > 0)
			{
				return true;
			}
		}
		return false;
	}

	/** Whether any of this encounter's targets died. */
	boolean isKilled()
	{
		for (Fight fight : fights)
		{
			if (fight.isTargetDied())
			{
				return true;
			}
		}
		return false;
	}

	/** Our thrall's damage across the room's fights, which damage dealt leaves out. */
	int getThrallDamage()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getThrallDamage();
		}
		return total;
	}

	/** Recoil and vengeance across the room's fights, which damage dealt leaves out (T-017 Part B). */
	int getReflectDamage()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getReflectDamage();
		}
		return total;
	}

	/** Burn, poison, venom and bleed across the room's fights, which damage dealt leaves out. */
	int getDotDamage()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getDotDamage();
		}
		return total;
	}

	/** {@link #getDotDamage()} by kind (T-062); a kind-less part (old kills) is {@code DotKinds.remainder}. */
	java.util.Map<String, Integer> getDotDamageByKind()
	{
		final java.util.Map<String, Integer> total = new java.util.LinkedHashMap<>();
		for (Fight fight : fights)
		{
			DotKinds.addAll(total, fight.getDotDamageByKind());
		}
		return total;
	}

	/** {@link #getScoredDotDamage()} by kind (T-062). */
	java.util.Map<String, Integer> getScoredDotDamageByKind()
	{
		final java.util.Map<String, Integer> total = new java.util.LinkedHashMap<>();
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				DotKinds.addAll(total, fight.getDotDamageByKind());
			}
		}
		return total;
	}

	/** Thrall damage on the scored fights only (T-025), for luck's other damage, as damage dealt is scored-only. */
	int getScoredThrallDamage()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getThrallDamage();
			}
		}
		return total;
	}

	/** DoT on the scored fights only (T-025); see {@link #getScoredThrallDamage()}. */
	int getScoredDotDamage()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getDotDamage();
			}
		}
		return total;
	}

	/** Recoil and vengeance on the scored fights only (T-025); see {@link #getScoredThrallDamage()}. */
	int getScoredReflectDamage()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getReflectDamage();
			}
		}
		return total;
	}

	/** What the room's targets healed while being fought. */
	int getHealed()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getHealed();
		}
		return total;
	}

	/** The first reason any of the room's fights gives not to trust its expected side, or null. */
	String getStatsWarning()
	{
		for (Fight fight : fights)
		{
			if (fight.getStatsWarning() != null)
			{
				return fight.getStatsWarning();
			}
		}
		return null;
	}

	int getDamageDealt()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getDamageDealt();
			}
		}
		return total;
	}

	int getDamageTaken()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getDamageTaken();
		}
		return total;
	}

	/** Damage over time on the player across the fights (T-018). */
	int getDotTaken()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getDotTaken();
		}
		return total;
	}

	/** {@link #getDotTaken()} by kind (T-062). */
	java.util.Map<String, Integer> getDotTakenByKind()
	{
		final java.util.Map<String, Integer> total = new java.util.LinkedHashMap<>();
		for (Fight fight : fights)
		{
			DotKinds.addAll(total, fight.getDotTakenByKind());
		}
		return total;
	}

	/** Hit splats on the player, 0s included (T-018). */
	int getHitsTaken()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getHitsTaken();
		}
		return total;
	}

	/** The biggest hit on the player in any of the fights (T-018). */
	int getBiggestHitTaken()
	{
		int biggest = 0;
		for (Fight fight : fights)
		{
			biggest = Math.max(biggest, fight.getBiggestHitTaken());
		}
		return biggest;
	}

	/** Hit damage on the player with no protection prayer up (T-018). */
	int getDamageTakenUnprotected()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getDamageTakenUnprotected();
		}
		return total;
	}

	/** The first fight's drain record that exists, for the room's starting Defence (T-049), or null. */
	private DrainRecord firstDrainRecord()
	{
		for (Fight fight : fights)
		{
			if (fight.getDrainRecord() != null)
			{
				return fight.getDrainRecord();
			}
		}
		return null;
	}

	/** The last fight's drain record that exists, or null. */
	private DrainRecord lastDrainRecord()
	{
		for (int i = fights.size() - 1; i >= 0; i--)
		{
			if (fights.get(i).getDrainRecord() != null)
			{
				return fights.get(i).getDrainRecord();
			}
		}
		return null;
	}

	/** The target's Defence when the room's first recorded fight opened (T-049); -1 unknown. */
	int getDefenceStart()
	{
		final DrainRecord r = firstDrainRecord();
		return r == null ? -1 : r.defenceStart;
	}

	/** Its Defence as the room's last recorded fight ended; -1 unknown. */
	int getDefenceEnd()
	{
		final DrainRecord r = lastDrainRecord();
		return r == null ? -1 : r.defenceEnd;
	}

	/** The target's Magic when the room's first recorded fight opened; -1 unknown. */
	int getMagicStart()
	{
		final DrainRecord r = firstDrainRecord();
		return r == null ? -1 : r.magicStart;
	}

	/** Its Magic as the room's last recorded fight ended; -1 unknown. */
	int getMagicEnd()
	{
		final DrainRecord r = lastDrainRecord();
		return r == null ? -1 : r.magicEnd;
	}

	/** The player's own draining specials thrown across the room; -1 unknown. */
	int getOwnDrainSpecsThrown()
	{
		return sumDrain(true, false);
	}

	/** Of those, the ones that landed; -1 unknown. */
	int getOwnDrainSpecsLanded()
	{
		return sumDrain(false, false);
	}

	/** Party members' drains across the room; -1 unknown. */
	int getPartyDrains()
	{
		return sumDrain(false, true);
	}

	private int sumDrain(boolean thrown, boolean party)
	{
		int total = 0;
		boolean any = false;
		for (Fight fight : fights)
		{
			final DrainRecord r = fight.getDrainRecord();
			if (r != null)
			{
				any = true;
				total += party ? r.partyDrains : thrown ? r.ownThrown() : r.ownLanded();
			}
		}
		return any ? total : -1;
	}

	/** The player's own draining specials per weapon across the room: {thrown, landed} (T-049). */
	java.util.Map<String, int[]> getOwnDrainSpecs()
	{
		final java.util.Map<String, int[]> total = new java.util.LinkedHashMap<>();
		for (Fight fight : fights)
		{
			final DrainRecord r = fight.getDrainRecord();
			if (r == null)
			{
				continue;
			}
			for (java.util.Map.Entry<String, int[]> e : r.specs.entrySet())
			{
				final int[] sum = total.computeIfAbsent(e.getKey(), w -> new int[2]);
				sum[0] += e.getValue()[0];
				sum[1] += e.getValue()[1];
			}
		}
		return total;
	}

	/**
	 * The avoidable part of the damage taken (T-041), over the fights that have
	 * ended at a boss with attack rules, or -1 with none: a fight still running
	 * counts nothing yet.
	 */
	int getDamageTakenAvoidable()
	{
		int total = 0;
		boolean any = false;
		for (Fight fight : fights)
		{
			if (fight.hasAvoidable())
			{
				total += fight.getDamageTakenAvoidable();
				any = true;
			}
		}
		return any ? total : -1;
	}

	/** The eating ticks the avoidable hits cost (T-041), as {@link #getDamageTakenAvoidable}, or -1. */
	double getAvoidableEatingTicks()
	{
		double total = 0;
		boolean any = false;
		for (Fight fight : fights)
		{
			if (fight.hasAvoidable())
			{
				total += fight.getAvoidableEatingTicks();
				any = true;
			}
		}
		return any ? Math.round(total * 10) / 10.0 : -1;
	}

	// T-050: the drains' worth and the specials' share of luck over the scored fights that know them; NaN with none.
	private double drainSum(int which)
	{
		double total = 0;
		boolean any = false;
		for (Fight fight : fights)
		{
			if (fight.isScored() && fight.hasDrainValue())
			{
				total += which == 0 ? fight.getDrainValueDamage() : which == 1 ? fight.getDrainValueExpected()
					: fight.getSpecLuckDamage();
				any = true;
			}
		}
		return any ? total : Double.NaN;
	}

	/** The drains' worth in damage, realized (T-050): see {@link Fight#getDrainValueDamage()}. NaN when unknown. */
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

	/** Damage as seconds over this room's scored fights, as luck's are (T-050). */
	double luckSeconds(double damage)
	{
		return DrainValue.seconds(damage, getScoredCombatTicks(), sumExpectedAverageHit(),
			getScoredThrallDamage() + getScoredDotDamage() + getScoredReflectDamage());
	}

	/** HP taken by attack (T-041) over the same fights; empty with none. */
	java.util.Map<String, Integer> getDamageTakenByAttack()
	{
		final java.util.Map<String, Integer> total = new java.util.LinkedHashMap<>();
		for (Fight fight : fights)
		{
			fight.getDamageTakenByAttack().forEach((name, hp) -> total.merge(name, hp, Integer::sum));
		}
		return total;
	}

	/** The newest attack rules any of its ended fights was classed by, or 0 for none (T-041). */
	int getAttackRulesVersion()
	{
		int version = 0;
		for (Fight fight : fights)
		{
			version = Math.max(version, fight.getAttackRulesVersion());
		}
		return version;
	}

	/**
	 * Attacks that got an answer. One nulled by the kill has no outcome to be
	 * right or wrong about, and left in here it reads as a miss that dealt
	 * nothing.
	 */
	int getAttempts()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.resolvedAttempts();
			}
		}
		return total;
	}

	int getHits()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getHits();
			}
		}
		return total;
	}

	/** Npcs this room's attacks reached, which accuracy divides by. */
	int getTargets()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.resolvedTargets();
			}
		}
		return total;
	}

	double accuracy()
	{
		final int targets = getTargets();
		return targets == 0 ? 0 : (double) getHits() / targets;
	}

	double averageHit()
	{
		final int attempts = getAttempts();
		return attempts == 0 ? 0 : (double) getDamageDealt() / attempts;
	}

	/**
	 * The scored fights' damage variance, summed (T-028); -1 when any scored fight
	 * that was attacked has none (saved before T-028), or nothing was.
	 */
	double sumExpectedDamageVariance()
	{
		double total = 0;
		boolean any = false;
		for (Fight fight : fights)
		{
			if (!fight.isScored() || fight.getExpectedTargetSamples() == 0)
			{
				continue;
			}
			final double variance = fight.damageVariance();
			if (variance < 0)
			{
				return -1;
			}
			total += variance;
			any = true;
		}
		return any ? total : -1;
	}

	/** Running total of what each attack was expected to deal, or 0 if untracked. */
	double sumExpectedAverageHit()
	{
		double total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getSumExpectedAverageHit();
			}
		}
		return total;
	}

	double sumExpectedAccuracy()
	{
		double total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getSumExpectedAccuracy();
			}
		}
		return total;
	}

	int getAttacksMade()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getAttacksMade();
			}
		}
		return total;
	}

	int getAttacksPrayed()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getAttacksPrayed();
			}
		}
		return total;
	}

	int getAttacksSwitched()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getAttacksSwitched();
			}
		}
		return total;
	}

	/** See {@code Fight.attacksDrained}: over the scored fights. */
	int getAttacksDrained()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getAttacksDrained();
			}
		}
		return total;
	}

	int getAttacksPotted()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getAttacksPotted();
			}
		}
		return total;
	}

	double efficiency()
	{
		double actual = 0;
		double ideal = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				actual += fight.getSumActualSetup();
				ideal += fight.getSumIdealSetup();
			}
		}
		return ideal <= 0 ? -1 : actual / ideal;
	}

	/** See {@code Fight.boostShare}: over every scored fight's attacks, or -1 if none was read. */
	double boostShare()
	{
		double sum = 0;
		int samples = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				sum += fight.getSumBoostShare();
				samples += fight.getBoostSamples();
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
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				cost += boost ? fight.getSumBoostCost() : fight.getSumSwitchCost();
				ideal += fight.getSumIdealSetup();
			}
		}
		return ideal <= 0 ? -1 : cost / ideal;
	}

	int getTicksLostEating()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getTicksLostEating();
		}
		return total;
	}

	int getTicksLost()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getTicksLost();
		}
		return total;
	}

	int getCombatTicks()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			total += fight.getCombatTicks();
		}
		return total;
	}

	/**
	 * The ticks of the scored fights only (T-023), the time the room's damage and
	 * expected damage come from. {@link #getCombatTicks()} stays the whole room.
	 */
	int getScoredCombatTicks()
	{
		int total = 0;
		for (Fight fight : fights)
		{
			if (fight.isScored())
			{
				total += fight.getCombatTicks();
			}
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
		for (Fight fight : fights)
		{
			last = Math.max(last, fight.getLastActivityMillis());
		}
		return Math.max(0, (isEnded() ? endMillis : last) - startMillis);
	}
}
