package com.pvmlogs;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Which of the player's hitsplats are really their thrall's (issue #1).
 *
 * <p>A thrall's splats carry the summoner's own hitsplat type, so isMine()
 * passes them, and the tracker was counting each as the player hitting again.
 * Nothing on the splat says whose it is, so this weighs what does:
 *
 * <ul>
 * <li><b>The thrall's own attacks.</b> Ours is found when it spawns beside the
 * player as the resurrection varbit comes up. Its attack animation, and the
 * projectile a ghost or skeleton fires, are seen at least a tick before the
 * splat - wiki (Hit delay): thrall damage processes a tick late - so each one
 * opens a window in which one splat of 0 to its max is due.</li>
 * <li><b>The cycle.</b> It attacks every four ticks while it has a target, so
 * the splats it has landed fix a phase, and a splat on that phase is likelier
 * its own. Nor can it land two within three ticks.</li>
 * <li><b>The amount.</b> A thrall rolls 0 to its max evenly; the player rolls
 * a miss or 0 to a much larger max. A splat over the thrall's max is never its.</li>
 * <li><b>Experience</b> ({@link XpLedger}). A thrall pays none, so a splat with
 * damage on it that no recent attack paid for is not the player's, and one that
 * finishes an attack exactly is.</li>
 * <li><b>What the player could have done</b>: a projectile of theirs due now, a
 * melee weapon off cooldown, or their own multi-hit burst already landing.</li>
 * </ul>
 *
 * <p>These are folded into odds of thrall against player, reported as a
 * probability and logged with every splat it judges, so a wrong call can be
 * traced to the evidence that made it. With no thrall of ours out, nothing here
 * changes anything.
 */
class ThrallTracker
{
	static final int ATTACK_SPEED_TICKS = 4;

	enum PlayerSplat
	{
		/** A projectile the player fired is due to land on this npc now. */
		PROJECTILE_DUE(0.9),
		/** The player's own burst already landed on this npc this tick: a scythe, a claw special. */
		SAME_TICK_BURST(0.9),
		/** Melee in hand and the weapon was off cooldown, so this could be a new blow. */
		MELEE_POSSIBLE(0.5),
		/** Nothing the player did explains a splat here now. */
		UNEXPLAINED(0.05);

		final double rate;

		PlayerSplat(double rate)
		{
			this.rate = rate;
		}
	}

	// How far from the player a thrall may appear and still be the one just cast. It spawns on an adjacent tile.
	static final int SPAWN_RADIUS = 2;
	// How far apart the varbit coming up and the spawn may be seen.
	static final int SUMMON_WINDOW_TICKS = 2;
	// An animation says an attack went out but not when it lands: a zombie's blow is seen a tick or two later, and a
	// projectile, once seen, narrows this to its own landing.
	static final int ANIMATION_EARLIEST = 1;
	static final int ANIMATION_LATEST = 3;
	static final int PROJECTILE_SLACK = 1;
	// The cycle forgets old evidence so a thrall that re-paths or changes target re-locks onto its new phase.
	private static final double PHASE_DECAY = 0.8;
	// How recently the thrall must have shown an attack for its silence to mean it is not attacking now.
	private static final int OBSERVATION_FRESH_TICKS = 12;

	// Evidence weights, as rates of a splat arriving: the thrall's side.
	static final double RATE_THRALL_DUE = 0.95;
	static final double RATE_THRALL_SILENT = 0.03;
	static final double RATE_THRALL_TOO_SOON = 0.002;
	static final double RATE_THRALL_UNKNOWN_PHASE = 0.15;
	// Experience: how strongly a splat nothing paid for, or one that finishes an attack exactly, moves the odds.
	static final double XP_UNCOVERED = 40;
	static final double XP_EXACT = 1.0 / 30;

	private int ownedIndex = -1;
	private int ownedId = -1;
	private int maxHit = -1;
	private int summonTick = Integer.MIN_VALUE;
	private boolean varbitActive;
	private final List<int[]> recentSpawns = new ArrayList<>(); // {index, id, tick, distance}
	private final List<Due> due = new ArrayList<>();
	private final double[] phase = new double[ATTACK_SPEED_TICKS];
	private int lastSplatTick = Integer.MIN_VALUE;
	private int lastObservedTick = Integer.MIN_VALUE;
	private final List<String> notes = new ArrayList<>();

	/** One attack of the thrall's, and the ticks its splat may land on. */
	static final class Due
	{
		final int targetIndex;
		final int attackTick;
		int earliest;
		int latest;
		boolean fromProjectile;

		Due(int targetIndex, int attackTick, int earliest, int latest)
		{
			this.targetIndex = targetIndex;
			this.attackTick = attackTick;
			this.earliest = earliest;
			this.latest = latest;
		}
	}

	/** What one splat looks like, gathered by the plugin at the moment it lands. */
	static final class Evidence
	{
		int amount;
		int tick;
		int targetIndex;
		PlayerSplat player = PlayerSplat.UNEXPLAINED;
		XpLedger.Coverage xp = XpLedger.Coverage.NO_INFO;
		boolean xpTrusted;
		// The player's expected figures against this target; negative when unknown.
		double playerAccuracy = -1;
		int playerMaxHit = -1;
	}

	// --- ownership ---

	boolean hasThrall()
	{
		return ownedIndex >= 0;
	}

	int ownedIndex()
	{
		return ownedIndex;
	}

	int maxHit()
	{
		return maxHit;
	}

	void onResurrectionVarbit(boolean active, int tick)
	{
		varbitActive = active;
		if (!active)
		{
			release("resurrection varbit went down");
			return;
		}
		summonTick = tick;
		// The spawn may already have been seen this tick or the one before.
		claimRecentSpawn(tick, "spawned beside me as the varbit came up");
	}

	/** Claims the nearest thrall that spawned beside the player within the summon window, if any did. */
	private void claimRecentSpawn(int tick, String why)
	{
		int[] best = null;
		for (int[] s : recentSpawns)
		{
			if (tick - s[2] <= SUMMON_WINDOW_TICKS && s[3] <= SPAWN_RADIUS && (best == null || s[3] < best[3]))
			{
				best = s;
			}
		}
		if (best != null)
		{
			claim(best[0], best[1], why);
		}
	}

	/**
	 * The player's own resurrection cast. A recast replaces the thrall while the
	 * varbit stays up, so this is the only thing that marks that summon.
	 */
	void onSummonCast(int tick)
	{
		summonTick = tick;
		claimRecentSpawn(tick, "spawned beside me as I cast");
	}

	void onThrallSpawned(int index, int id, int distance, int tick)
	{
		recentSpawns.add(new int[]{index, id, tick, distance});
		if (distance > SPAWN_RADIUS)
		{
			return;
		}
		if (tick - summonTick <= SUMMON_WINDOW_TICKS && tick >= summonTick)
		{
			claim(index, id, "spawned beside me as the varbit came up");
		}
		else if (varbitActive && !hasThrall())
		{
			// Ours, still active, coming back into view: a new scene loads every npc afresh.
			claim(index, id, "spawned beside me while mine was active and unseen");
		}
	}

	/**
	 * The last resort, for a thrall already out when tracking began: claimed only
	 * while the varbit says one of ours is active and the plugin can see exactly
	 * one candidate.
	 */
	void onOnlyCandidate(int index, int id)
	{
		if (varbitActive && !hasThrall())
		{
			claim(index, id, "the only thrall in view while mine was active");
		}
	}

	void onThrallDespawned(int index, int tick)
	{
		recentSpawns.removeIf(s -> s[0] == index);
		if (index == ownedIndex)
		{
			release("despawned");
			// A recast's new thrall can be seen before the old one goes.
			if (tick - summonTick <= SUMMON_WINDOW_TICKS)
			{
				claimRecentSpawn(tick, "spawned beside me as I recast");
			}
		}
	}

	boolean varbitActive()
	{
		return varbitActive;
	}

	void setVarbitActive(boolean active)
	{
		varbitActive = active;
	}

	private void claim(int index, int id, String why)
	{
		if (index == ownedIndex)
		{
			return;
		}
		ownedIndex = index;
		ownedId = id;
		maxHit = Thralls.maxHit(id);
		due.clear();
		java.util.Arrays.fill(phase, 0);
		lastSplatTick = Integer.MIN_VALUE;
		lastObservedTick = Integer.MIN_VALUE;
		notes.add(String.format("THRALL claimed npc %d (index %d), max hit %d: %s", id, index, maxHit, why));
	}

	private void release(String why)
	{
		if (ownedIndex >= 0)
		{
			notes.add(String.format("THRALL released npc %d (index %d): %s", ownedId, ownedIndex, why));
		}
		ownedIndex = -1;
		ownedId = -1;
		maxHit = -1;
		due.clear();
	}

	// --- its attacks ---

	/** Our thrall started an animation while aimed at an npc: an attack went out. */
	void onThrallAttackAnimation(int thrallIndex, int targetIndex, int tick)
	{
		if (thrallIndex != ownedIndex || targetIndex < 0)
		{
			return;
		}
		for (Due d : due)
		{
			// The same attack, already seen from its projectile.
			if (Math.abs(d.attackTick - tick) <= 1)
			{
				return;
			}
		}
		due.add(new Due(targetIndex, tick, tick + ANIMATION_EARLIEST, tick + ANIMATION_LATEST));
		lastObservedTick = tick;
	}

	/** A projectile from our thrall, which says exactly when its splat lands. */
	void onThrallProjectile(int thrallIndex, int targetIndex, int landingTick, int tick)
	{
		if (thrallIndex != ownedIndex || targetIndex < 0)
		{
			return;
		}
		lastObservedTick = tick;
		for (Due d : due)
		{
			if (Math.abs(d.attackTick - tick) <= 1 && !d.fromProjectile)
			{
				d.earliest = landingTick - PROJECTILE_SLACK;
				d.latest = landingTick + PROJECTILE_SLACK;
				d.fromProjectile = true;
				return;
			}
		}
		final Due d = new Due(targetIndex, tick, landingTick - PROJECTILE_SLACK, landingTick + PROJECTILE_SLACK);
		d.fromProjectile = true;
		due.add(d);
	}

	private Due dueOn(int targetIndex, int tick)
	{
		for (Due d : due)
		{
			if (d.targetIndex == targetIndex && tick >= d.earliest && tick <= d.latest)
			{
				return d;
			}
		}
		return null;
	}

	// --- judging a splat ---

	/**
	 * The probability, 0 to 1, that this splat is our thrall's. Zero when there is
	 * no thrall of ours or the splat is more than it can deal.
	 */
	double probability(Evidence e)
	{
		if (!hasThrall() || e.amount > maxHit || e.amount < 0)
		{
			return 0;
		}
		final double thrallRate = thrallRate(e.targetIndex, e.tick);
		final double playerRate = e.player.rate;
		// A thrall rolls 0 to its max evenly. The player misses, or rolls 0 to their max evenly.
		final double thrallLikelihood = 1.0 / (maxHit + 1);
		final double accuracy = e.playerAccuracy >= 0 ? Math.max(0.02, Math.min(0.98, e.playerAccuracy)) : 0.7;
		final int playerMax = e.playerMaxHit > 0 ? e.playerMaxHit : 30;
		final double playerLikelihood = (e.amount == 0 ? 1 - accuracy : 0) + accuracy / (playerMax + 1);
		double odds = (thrallRate / playerRate) * (thrallLikelihood / playerLikelihood);
		if (e.xpTrusted)
		{
			if (e.xp == XpLedger.Coverage.UNCOVERED)
			{
				odds *= XP_UNCOVERED;
			}
			else if (e.xp == XpLedger.Coverage.EXACT)
			{
				odds *= XP_EXACT;
			}
		}
		return odds / (1 + odds);
	}

	/** How readily the thrall lands a splat on this npc now, before the amount is looked at. */
	double thrallRate(int targetIndex, int tick)
	{
		if (dueOn(targetIndex, tick) != null)
		{
			return RATE_THRALL_DUE;
		}
		// Four ticks between attacks: one landed a tick or two ago means this cannot be its next.
		if (lastSplatTick != Integer.MIN_VALUE && tick - lastSplatTick < ATTACK_SPEED_TICKS - 1)
		{
			return RATE_THRALL_TOO_SOON;
		}
		final double share = cycleShare(tick);
		// Its attacks are being seen and none is due, so it is probably not attacking this npc now - unless the splat
		// sits squarely on its cycle, which says an attack was missed rather than not thrown.
		if (lastObservedTick != Integer.MIN_VALUE && tick - lastObservedTick <= OBSERVATION_FRESH_TICKS)
		{
			return share < 0 ? RATE_THRALL_SILENT : Math.max(RATE_THRALL_SILENT, 0.5 * share);
		}
		// Blind to its attacks, so the cycle is all there is.
		return share < 0 ? RATE_THRALL_UNKNOWN_PHASE : Math.max(RATE_THRALL_SILENT, 0.9 * share);
	}

	/** The share of the thrall's recent splats that fell on this tick's phase of the cycle, or -1 with none yet. */
	double cycleShare(int tick)
	{
		final double total = phase[0] + phase[1] + phase[2] + phase[3];
		if (total < 1)
		{
			return -1;
		}
		// Lightly smoothed: the cycle is strict, so once it has locked a splat off its phase is close to impossible
		// rather than a quarter as likely.
		return (phase[Math.floorMod(tick, ATTACK_SPEED_TICKS)] + 0.05) / (total + 0.2);
	}

	/** A splat judged to be the thrall's: it spends the attack it came from and votes for its phase. */
	void noteThrallSplat(int targetIndex, int tick)
	{
		final Due d = dueOn(targetIndex, tick);
		if (d != null)
		{
			due.remove(d);
		}
		for (int i = 0; i < phase.length; i++)
		{
			phase[i] *= PHASE_DECAY;
		}
		phase[Math.floorMod(tick, ATTACK_SPEED_TICKS)] += 1;
		lastSplatTick = tick;
	}

	/** The phase the cycle has locked onto, or -1 before it has any. */
	int lockedPhase()
	{
		int best = -1;
		double bestWeight = 0.5;
		for (int i = 0; i < phase.length; i++)
		{
			if (phase[i] > bestWeight)
			{
				best = i;
				bestWeight = phase[i];
			}
		}
		return best;
	}

	void tick(int tick)
	{
		for (Iterator<Due> it = due.iterator(); it.hasNext(); )
		{
			final Due d = it.next();
			if (tick > d.latest)
			{
				it.remove();
			}
		}
		recentSpawns.removeIf(s -> tick - s[2] > SUMMON_WINDOW_TICKS);
	}

	/** Lines for the debug log since the last call. */
	List<String> drainNotes()
	{
		final List<String> out = new ArrayList<>(notes);
		notes.clear();
		return out;
	}

	List<Due> dueAttacks()
	{
		return new ArrayList<>(due);
	}

	void clear()
	{
		release("reset");
		recentSpawns.clear();
		summonTick = Integer.MIN_VALUE;
		varbitActive = false;
		notes.clear();
	}
}
