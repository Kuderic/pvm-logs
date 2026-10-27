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
 * <p>It checks itself, one attack at a time. Once an attack's damage has all
 * landed, the splats it got are compared with what its drops decode to - but
 * only when every one of them could only have been the player's (too big for a
 * thrall, or no thrall out). An attack whose drops decode to nothing at all
 * fails too: that is the commonest sign of a wrong multiplier. While too many
 * fail, the ledger is reported untrusted and its evidence ignored.
 *
 * <p>And it calibrates itself from the same clean attacks. The game's PvM bonus
 * is a fortieth step, so experience over damage, pooled until rounding can no
 * longer move it half a step, says the multiplier outright. A changed
 * multiplier re-decodes the attacks still landing and re-runs the checks, so a
 * wrong starting figure costs a few attacks rather than the fight. The
 * Vardorvis log (claude/status/vardorvis-log-review.md, bug 3) started at
 * x1.700 against a true x1.55 and never left it: drops that decode to nothing
 * were never spent, so nothing reached the calibration and no check ever ran.
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
	// An attack is used for calibrating once it dealt this much: below it, a point of rounding is a large share.
	static final int CALIBRATION_MIN_DAMAGE = 10;
	// A drop reads up to 1.1 under or 1 over its exact value. Pooled, that error over the damage must stay under half the
	// game's fortieth step, or the snap could land on the neighbouring one.
	private static final double DROP_ERROR = 1.1;
	private static final double HALF_STEP = 0.5 / 40;
	// The clean attacks kept so a changed multiplier can re-run their checks.
	private static final int SETTLED_KEPT = 12;

	private final int[] open = new int[SKILLS.length];
	private final boolean[] reported = new boolean[SKILLS.length];
	private final Deque<Award> awards = new ArrayDeque<>();
	private final Deque<Award> settled = new ArrayDeque<>();
	private final Deque<Boolean> checks = new ArrayDeque<>();
	// Per target id: the multiplier the drops say, once they have said it clearly.
	private final Map<Integer, Double> calibrated = new HashMap<>();
	// Per target id: experience over rate, damage, the error bound on the first, and how many attacks went in.
	private final Map<Integer, double[]> calibrationSums = new HashMap<>();

	static final class Award
	{
		final int tick;
		final int targetId;
		// Each skill's gain, or XpDecoder.UNKNOWN for one that could not report.
		final int[] gains;
		double multiplier;
		// -1 when the drops decode to nothing at this multiplier.
		int lowest = -1;
		int highest = -1;
		int remainingLow;
		int remainingHigh;
		// What landed against this attack, and whether every splat of it could only have been the player's.
		int landed;
		int splats;
		boolean allCertain = true;
		// The cleanest single constraint, kept for calibrating the multiplier.
		final int calibrationGain;
		final double calibrationRate;

		Award(int tick, int targetId, int[] gains, double multiplier, int calibrationGain, double calibrationRate)
		{
			this.tick = tick;
			this.targetId = targetId;
			this.gains = gains;
			this.calibrationGain = calibrationGain;
			this.calibrationRate = calibrationRate;
			decode(multiplier);
		}

		/** Reads the drops at this multiplier, keeping whatever has already landed against them. */
		void decode(double m)
		{
			multiplier = m;
			final int[] range = XpDecoder.damageRange(gains[ATTACK], gains[STRENGTH], gains[DEFENCE], gains[RANGED],
				gains[MAGIC], gains[HITPOINTS], m);
			lowest = range == null ? -1 : range[0];
			highest = range == null ? -1 : range[1];
			remainingLow = Math.max(0, lowest - landed);
			remainingHigh = Math.max(0, highest - landed);
		}

		boolean decoded()
		{
			return lowest >= 0;
		}

		/** Whether what landed is what the drops say, at the current multiplier. */
		boolean matches()
		{
			return decoded() && landed >= lowest && landed <= highest;
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
			award = new Award(tick, targetId, g, multiplierFor(targetId, formulaMultiplier), calGain, calRate);
			awards.addLast(award);
		}
		java.util.Arrays.fill(open, 0);
		java.util.Arrays.fill(reported, false);
		return award;
	}

	/** Settles awards whose damage can no longer be arriving: checks them, and calibrates from the clean ones. */
	List<String> expire(int tick)
	{
		final List<String> notes = new ArrayList<>();
		while (!awards.isEmpty() && tick - awards.peekFirst().tick > WINDOW_TICKS)
		{
			settle(awards.pollFirst(), notes);
		}
		return notes;
	}

	private void settle(Award a, List<String> notes)
	{
		// Nothing landed (a splash, a target that died first), or something that landed could have been the thrall's:
		// either way this attack proves nothing about the experience.
		if (a.splats == 0 || !a.allCertain)
		{
			return;
		}
		final boolean ok = a.matches();
		noteCheck(ok);
		if (!ok)
		{
			notes.add(String.format("xp check FAILED: award %s, but %d landed", a, a.landed));
		}
		if (a.landed < CALIBRATION_MIN_DAMAGE || a.calibrationGain <= 0)
		{
			return;
		}
		settled.addLast(a);
		while (settled.size() > SETTLED_KEPT)
		{
			settled.pollFirst();
		}
		final String note = calibrate(a);
		if (note != null)
		{
			notes.add(note);
		}
	}

	/**
	 * Learns a target's multiplier from damage that was certainly the player's,
	 * pooled until the drops' rounding cannot reach half a step. Once it moves,
	 * the attacks still landing are re-read and the checks re-run under it.
	 */
	private String calibrate(Award a)
	{
		final double[] s = calibrationSums.computeIfAbsent(a.targetId, k -> new double[4]);
		s[0] += a.calibrationGain / a.calibrationRate;
		s[1] += a.landed;
		s[2] += DROP_ERROR / a.calibrationRate;
		s[3] += 1;
		if (s[3] < 2 || s[2] / s[1] >= HALF_STEP)
		{
			return null;
		}
		final double estimate = s[0] / s[1];
		final double snapped = 1.0 + Math.max(0, Math.round((estimate - 1.0) * 40)) / 40.0;
		final Double was = calibrated.get(a.targetId);
		final double inUse = was != null ? was : a.multiplier;
		calibrated.put(a.targetId, snapped);
		if (Math.abs(snapped - inUse) < 1e-9)
		{
			// Confirmed rather than corrected: nothing to re-read.
			return null;
		}
		// Everything still landing for this target is re-read at the new figure, and the checks start again from the
		// clean attacks kept: failures that were only the old figure's fault go with it.
		for (Award open : awards)
		{
			if (open.targetId == a.targetId)
			{
				open.decode(snapped);
			}
		}
		checks.clear();
		int passed = 0;
		for (Award past : settled)
		{
			if (past.targetId == a.targetId)
			{
				past.decode(snapped);
			}
			final boolean ok = past.matches();
			passed += ok ? 1 : 0;
			noteCheck(ok);
		}
		return String.format("xp multiplier for npc %d calibrated to x%.3f (was x%.3f) from %d attacks, %.0f damage; "
				+ "re-checked %d of %d clean attacks", a.targetId, snapped, inUse, (int) s[3], s[1], passed,
			settled.size());
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
	 * The tick of the attack a splat of this amount would be spent against, which
	 * is the tick it was thrown on (its drop's): the attack this splat finishes
	 * exactly, else the oldest with damage still to land, as {@link #spend} picks.
	 * {@code Integer.MIN_VALUE} for none: a zero, which pays nothing, or a splat no
	 * recent attack paid for. Read-only; asked before the splat is spent (T-021:
	 * the throw of a shot whose projectile was never taken as mine).
	 */
	int attackTickFor(int amount, int tick)
	{
		if (amount <= 0)
		{
			return Integer.MIN_VALUE;
		}
		for (Award a : awards)
		{
			if (eligible(a, tick) && a.decoded() && amount >= a.remainingLow && amount <= a.remainingHigh)
			{
				return a.tick;
			}
		}
		for (Award a : awards)
		{
			if (eligible(a, tick) && a.decoded())
			{
				return a.tick;
			}
		}
		return Integer.MIN_VALUE;
	}

	/**
	 * Spends a splat the player is taken to have dealt. {@code certain} says it
	 * could only have been theirs, which is what the checks and the calibration
	 * may learn from. A zero carries no damage and pays nothing, so it is left out.
	 */
	void spend(int amount, int tick, boolean certain)
	{
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
		Award taker = null;
		if (target != null)
		{
			left = take(target, left, certain);
			taker = target;
		}
		for (Award a : awards)
		{
			if (left <= 0)
			{
				break;
			}
			if (eligible(a, tick) && a.decoded() && a != target)
			{
				final int before = left;
				left = take(a, left, certain);
				if (left < before)
				{
					taker = a;
				}
			}
		}
		if (left <= 0)
		{
			return;
		}
		// More landed than the attacks' drops decode to. The rest stays with the attack that took the first of it, so its
		// check sees the whole splat; failing that it goes to the latest attack whose drops could not be read at all,
		// which is every attack while the multiplier is wrong. Never to one that decoded to nothing - a resurrection
		// cast paying magic alone - which is how a claw special's last splat was charged to the cast beside it.
		Award home = taker;
		if (home == null)
		{
			for (Award a : awards)
			{
				if (a.tick < tick && tick - a.tick <= WINDOW_TICKS && !a.decoded())
				{
					home = a;
				}
			}
		}
		if (home != null)
		{
			home.landed += left;
			home.splats += home == taker ? 0 : 1;
			home.allCertain &= certain;
			home.remainingHigh = Math.max(0, home.remainingHigh - left);
			home.remainingLow = Math.max(0, home.remainingLow - left);
		}
		else if (certain)
		{
			// Damage that could only be the player's, and no drop at all to have paid for it: a place that pays no
			// experience, or drops that stopped arriving. Fails at once, so trust earned elsewhere does not linger.
			noteCheck(false);
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
		a.landed += taken;
		a.splats++;
		a.allCertain &= certain;
		return amount - taken;
	}

	private static boolean eligible(Award a, int tick)
	{
		// Strictly after the drop: the attack goes out on the drop's tick and nothing lands on the tick it is thrown.
		return a.tick < tick && tick - a.tick <= WINDOW_TICKS && (!a.decoded() || a.remainingHigh > 0);
	}

	/** Whether recent attacks that could only be the player's have matched what the experience said. */
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

	List<Award> openAwards()
	{
		return new ArrayList<>(awards);
	}

	/**
	 * Forgets the attacks in flight. What it has learnt about each target's
	 * multiplier stays: that is the monster's, not the session's.
	 */
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
