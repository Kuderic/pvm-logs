package com.pvmlogs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Skill;

/**
 * The player's own damage, counted from the experience it paid, so a hitsplat
 * can be checked against it before it is believed.
 *
 * <p>The drop lands on the tick the attack goes out - measured from the fake
 * drops, cast on 550 and dropped on 550 with its hitsplat on 553 (commit
 * 8cfe357) - so an attack's damage is known before any of it arrives. Each
 * tick's drops become one award; the hitsplats that follow spend it. A hitsplat
 * with damage on it and nothing left to spend is not the player's: a thrall's,
 * which pays nothing, or a recoil's.
 *
 * <p>It checks itself. Every hitsplat that can only be the player's - too big
 * for a thrall, or no thrall out - is tested against the awards, and while too
 * many fail the ledger is reported untrusted and its evidence ignored. That
 * covers places that pay no experience, a multiplier it has wrong, and drops
 * arriving on a tick it does not expect.
 */
class XpLedger
{
	enum Coverage
	{
		/** One attack's remaining damage is exactly this splat. */
		EXACT,
		/** Some attack still has at least this much to land, but not this exactly. */
		PARTIAL,
		/** Nothing the player threw has this much left to land. */
		UNCOVERED,
		/** A recent attack paid drops that decode to nothing, so this cannot be judged. */
		UNKNOWN,
		/** A zero: pays nothing whoever threw it, so experience says nothing. */
		NO_INFO
	}

	static final Skill[] SKILLS = {Skill.ATTACK, Skill.STRENGTH, Skill.DEFENCE, Skill.RANGED, Skill.MAGIC,
		Skill.HITPOINTS};
	private static final int ATTACK = 0;
	private static final int STRENGTH = 1;
	private static final int DEFENCE = 2;
	private static final int RANGED = 3;
	private static final int MAGIC = 4;
	private static final int HITPOINTS = 5;

	// How long after its drop an attack's damage may still be arriving: a skeletal thrall's longest delay is three, a
	// long-range cast's five, and the client sees each a tick late.
	static final int WINDOW_TICKS = 8;
	// The rolling self-check.
	private static final int TRUST_SAMPLES = 12;
	private static final int TRUST_MIN_SAMPLES = 2;
	// Calibrating a multiplier needs enough damage that a point of rounding cannot move it a step.
	private static final int CALIBRATION_MIN_DAMAGE = 150;
	private static final int CALIBRATION_MIN_AWARD = 20;

	private final int[] open = new int[SKILLS.length];
	private final boolean[] reported = new boolean[SKILLS.length];
	private final Deque<Award> awards = new ArrayDeque<>();
	private final Deque<Boolean> checks = new ArrayDeque<>();
	// Per target id: the multiplier the drops say, once they have said it clearly.
	private final Map<Integer, Double> calibrated = new HashMap<>();
	private final Map<Integer, double[]> calibrationSums = new HashMap<>();

	static final class Award
	{
		final int tick;
		final int targetId;
		final double multiplier;
		// -1 when the drops decoded to nothing.
		final int lowest;
		final int highest;
		int remainingLow;
		int remainingHigh;
		int spent;
		boolean allCertain = true;
		// The cleanest single constraint, kept for calibrating the multiplier.
		final int calibrationGain;
		final double calibrationRate;

		Award(int tick, int targetId, double multiplier, int[] range, int calibrationGain, double calibrationRate)
		{
			this.tick = tick;
			this.targetId = targetId;
			this.multiplier = multiplier;
			this.lowest = range == null ? -1 : range[0];
			this.highest = range == null ? -1 : range[1];
			this.remainingLow = Math.max(0, lowest);
			this.remainingHigh = Math.max(0, highest);
			this.calibrationGain = calibrationGain;
			this.calibrationRate = calibrationRate;
		}

		boolean decoded()
		{
			return lowest >= 0;
		}

		@Override
		public String toString()
		{
			return decoded()
				? String.format("tick %d: %d-%d (left %d-%d, x%.3f)", tick, lowest, highest, remainingLow, remainingHigh,
				multiplier)
				: String.format("tick %d: undecodable (x%.3f)", tick, multiplier);
		}
	}

	/** One skill's gain, from StatChanged or a fake drop, on the tick it arrived. */
	void add(Skill skill, int gained)
	{
		final int i = indexOf(skill);
		if (i < 0 || gained <= 0)
		{
			return;
		}
		open[i] += gained;
		reported[i] = true;
	}

	/**
	 * Ends the tick: whatever it paid becomes one award against the target. A
	 * skill that cannot report - at 200m with no fake drop - is unknown rather
	 * than zero, so its silence is not read as a miss.
	 *
	 * @return the award made, or null when nothing was paid
	 */
	Award close(int tick, int targetId, double formulaMultiplier, boolean[] cannotReport)
	{
		boolean any = false;
		for (boolean r : reported)
		{
			any |= r;
		}
		Award award = null;
		if (any)
		{
			final int[] g = new int[SKILLS.length];
			for (int i = 0; i < g.length; i++)
			{
				g[i] = reported[i] ? open[i] : cannotReport != null && cannotReport[i] ? XpDecoder.UNKNOWN : 0;
			}
			final double m = multiplierFor(targetId, formulaMultiplier);
			final int[] range = XpDecoder.damageRange(g[ATTACK], g[STRENGTH], g[DEFENCE], g[RANGED], g[MAGIC],
				g[HITPOINTS], m);
			// For calibration: a single combat skill at four a point is the sharpest, hitpoints the fallback.
			int calGain = -1;
			double calRate = 0;
			final int meleeMoved = (g[ATTACK] > 0 ? 1 : 0) + (g[STRENGTH] > 0 ? 1 : 0) + (g[DEFENCE] > 0 ? 1 : 0);
			if (g[MAGIC] <= 0 && g[RANGED] > 0 && g[DEFENCE] <= 0)
			{
				calGain = g[RANGED];
				calRate = 4.0;
			}
			else if (g[MAGIC] <= 0 && g[RANGED] <= 0 && meleeMoved == 1)
			{
				calGain = Math.max(g[ATTACK], Math.max(g[STRENGTH], g[DEFENCE]));
				calRate = 4.0;
			}
			else if (g[HITPOINTS] > 0)
			{
				calGain = g[HITPOINTS];
				calRate = 4.0 / 3.0;
			}
			award = new Award(tick, targetId, m, range, calGain, calRate);
			awards.addLast(award);
		}
		java.util.Arrays.fill(open, 0);
		java.util.Arrays.fill(reported, false);
		return award;
	}

	/** Drops awards whose damage can no longer be arriving, checking and calibrating from them on the way out. */
	List<String> expire(int tick)
	{
		final List<String> notes = new ArrayList<>();
		while (!awards.isEmpty() && tick - awards.peekFirst().tick > WINDOW_TICKS)
		{
			final Award a = awards.pollFirst();
			// Decoded to more than ever arrived, from attacks that were plainly the player's: the multiplier is too low or
			// the drops are not what this expects. One failed check, the same as a hit that nothing paid for.
			if (a.decoded() && a.allCertain && a.remainingLow > 3)
			{
				noteCheck(false);
				notes.add("xp award " + a + " never fully arrived");
			}
			if (a.allCertain && a.spent >= CALIBRATION_MIN_AWARD && a.calibrationGain > 0)
			{
				final String note = calibrate(a.targetId, a.calibrationGain / a.calibrationRate, a.spent);
				if (note != null)
				{
					notes.add(note);
				}
			}
		}
		return notes;
	}

	/** Whether this splat fits what the player's recent attacks have left to land. */
	Coverage coverage(int amount, int tick)
	{
		if (amount <= 0)
		{
			return Coverage.NO_INFO;
		}
		int sumHigh = 0;
		boolean exact = false;
		for (Award a : awards)
		{
			if (!eligible(a, tick))
			{
				continue;
			}
			if (!a.decoded())
			{
				return Coverage.UNKNOWN;
			}
			sumHigh += a.remainingHigh;
			exact |= amount >= a.remainingLow && amount <= a.remainingHigh && a.remainingHigh > 0;
		}
		if (exact)
		{
			return Coverage.EXACT;
		}
		return amount <= sumHigh ? Coverage.PARTIAL : Coverage.UNCOVERED;
	}

	/**
	 * Spends a splat the player is taken to have dealt. {@code certain} says it
	 * could only have been theirs, which is what the self-check and the
	 * calibration may learn from.
	 */
	void spend(int amount, int tick, boolean certain)
	{
		if (certain && amount > 0)
		{
			final Coverage c = coverage(amount, tick);
			if (c != Coverage.UNKNOWN)
			{
				noteCheck(c != Coverage.UNCOVERED);
			}
		}
		if (amount <= 0)
		{
			return;
		}
		// The attack this splat finishes exactly, if there is one; otherwise the oldest with damage still to land.
		Award target = null;
		for (Award a : awards)
		{
			if (eligible(a, tick) && a.decoded() && amount >= a.remainingLow && amount <= a.remainingHigh)
			{
				target = a;
				break;
			}
		}
		int left = amount;
		if (target != null)
		{
			left = take(target, left, certain);
		}
		for (Award a : awards)
		{
			if (left <= 0)
			{
				break;
			}
			if (eligible(a, tick) && a.decoded() && a != target)
			{
				left = take(a, left, certain);
			}
		}
	}

	private static int take(Award a, int amount, boolean certain)
	{
		final int taken = Math.min(amount, a.remainingHigh);
		if (taken <= 0)
		{
			return amount;
		}
		a.remainingHigh -= taken;
		a.remainingLow = Math.max(0, a.remainingLow - taken);
		a.spent += taken;
		a.allCertain &= certain;
		return amount - taken;
	}

	private static boolean eligible(Award a, int tick)
	{
		// Strictly after the drop: the attack goes out on the drop's tick and nothing lands on the tick it is thrown.
		return a.tick < tick && tick - a.tick <= WINDOW_TICKS && (!a.decoded() || a.remainingHigh > 0);
	}

	/** Whether recent hits that could only be the player's have matched what the experience said. */
	boolean trusted()
	{
		if (checks.size() < TRUST_MIN_SAMPLES)
		{
			return false;
		}
		int failed = 0;
		for (boolean ok : checks)
		{
			failed += ok ? 0 : 1;
		}
		return failed * 4 <= checks.size();
	}

	String trustSummary()
	{
		int ok = 0;
		for (boolean c : checks)
		{
			ok += c ? 1 : 0;
		}
		return ok + "/" + checks.size() + (trusted() ? " trusted" : " NOT trusted");
	}

	private void noteCheck(boolean ok)
	{
		checks.addLast(ok);
		while (checks.size() > TRUST_SAMPLES)
		{
			checks.pollFirst();
		}
	}

	double multiplierFor(int targetId, double formulaMultiplier)
	{
		final Double c = calibrated.get(targetId);
		if (c != null)
		{
			return c;
		}
		return formulaMultiplier > 0 ? formulaMultiplier : 1.0;
	}

	/**
	 * Learns a target's multiplier from damage that was certainly the player's.
	 * The game's bonus is always a fortieth step, so the estimate is snapped to
	 * one; it only overrides the formula once enough damage has been seen.
	 */
	private String calibrate(int targetId, double unscaledXp, int damage)
	{
		final double[] sums = calibrationSums.computeIfAbsent(targetId, k -> new double[2]);
		sums[0] += unscaledXp;
		sums[1] += damage;
		if (sums[1] < CALIBRATION_MIN_DAMAGE)
		{
			return null;
		}
		final double estimate = sums[0] / sums[1];
		final double snapped = 1.0 + Math.max(0, Math.round((estimate - 1.0) * 40)) / 40.0;
		final Double was = calibrated.put(targetId, snapped);
		if (was == null || Math.abs(was - snapped) > 1e-9)
		{
			return String.format("xp multiplier for npc %d calibrated to x%.3f (%.0f damage seen)", targetId, snapped,
				sums[1]);
		}
		return null;
	}

	List<Award> openAwards()
	{
		return new ArrayList<>(awards);
	}

	void clear()
	{
		awards.clear();
		java.util.Arrays.fill(open, 0);
		java.util.Arrays.fill(reported, false);
	}

	static int indexOf(Skill skill)
	{
		for (int i = 0; i < SKILLS.length; i++)
		{
			if (SKILLS[i] == skill)
			{
				return i;
			}
		}
		return -1;
	}
}
