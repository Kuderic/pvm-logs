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
 * player as the resurrection varbit comes up. A ghost or skeleton attacks with a
 * projectile, and its splat lands on the tick that projectile is first seen:
 * 223 of 223 across eight Vardorvis kills, the projectile always seen first
 * within the tick, and never a splat of its own without one. So a projectile
 * makes one splat of 0 to its max due on that tick, and for those thralls a
 * splat with no projectile is not theirs. The animation, a tick ahead of the
 * projectile, is what a zombie's melee blow has instead. Neither event names the
 * thrall's target: it reads "interacting nothing" throughout, and its
 * projectiles name no source, so they are matched by where they leave from.</li>
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
		/**
		 * The player's own attack already landed on this npc this tick and has splats still to come: a scythe's second and
		 * third, a claw special. Only while it has (T-017): a tentacle hits once.
		 */
		SAME_TICK_BURST(0.9),
		/**
		 * The attack that landed on this npc last tick puts its remaining splats down now: the dual macuahuitl's second
		 * hit, the twinflame staff's second spell, burning claws' third (Vardorvis 2300, 2392, 2730, 4487).
		 */
		LATE_SPLAT_DUE(0.9),
		/**
		 * A multi-hit attack landed on this npc last tick and may still be landing: a special whose timing is not known.
		 * An ordinary single blow is one splat, and the tick after it is inside its cooldown (16:08 log, 171).
		 */
		BURST_CONTINUING(0.6),
		/**
		 * The attack landing on this npc now has already put down every splat it can (T-017): a second splat on a tick a
		 * tentacle's blow landed (19:34 log, 439). As impossible as a blow inside the cooldown.
		 */
		EXTRA_SPLAT(0.003),
		/** Melee in hand, and this is the first tick a blow thrown the moment the weapon came off cooldown lands. */
		ON_CADENCE(0.9),
		/**
		 * Melee in hand, but later than the weapon allowed: the player paused, so a blow now is less likely than one on
		 * cadence (16:08 log, 82 and 107: the zombie's zeros, a tick or more after the player's due blow).
		 */
		LATE(0.3),
		/** Melee in hand with no blow booked yet, so nothing says when one is due. */
		MELEE_POSSIBLE(0.5),
		/** The weapon was still on cooldown when this blow would have been thrown: it cannot be a new attack. */
		IN_COOLDOWN(0.003),
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
	// Four ticks apart in the 16:08 log: the ghost spawned around 190 and the varbit only came up on 194, as it finished
	// rising. In the earlier logs the two came on one tick.
	static final int SUMMON_WINDOW_TICKS = 5;
	// An animation says an attack went out, a tick ahead of its splat. For a ghost the projectile follows on the splat's
	// own tick and replaces this; for a zombie it is all there is.
	static final int ANIMATION_EARLIEST = 1;
	static final int ANIMATION_LATEST = 2;
	// A projectile's splat lands on the tick it is first seen. Its predicted landing tick is not used: it was a tick late
	// on 3 of 223, and the splat never was. A tick of slack after, none before.
	static final int PROJECTILE_LATE = 1;
	// How close to our thrall a projectile naming no source must leave from to be its own.
	static final int PROJECTILE_SOURCE_RADIUS = 2;
	// The cycle forgets old evidence so a thrall that re-paths or changes target re-locks onto its new phase.
	// Fast enough to re-lock within two splats of a one-tick re-path (the zombie's 94 to 99 in the 16:08 log).
	private static final double PHASE_DECAY = 0.6;
	// How recently the thrall must have shown an attack for its silence to mean it is not attacking now.
	private static final int OBSERVATION_FRESH_TICKS = 12;

	// Evidence weights, as rates of a splat arriving: the thrall's side.
	static final double RATE_THRALL_PROJECTILE = 0.98;
	static final double RATE_THRALL_DUE = 0.9;
	// A thrall that only attacks with projectiles, and none on this tick: once its projectiles have been seen, very
	// nearly impossible; before the first, merely unlikely, since that one could have been missed.
	static final double RATE_THRALL_NO_PROJECTILE = 0.0005;
	static final double RATE_THRALL_NO_PROJECTILE_YET = 0.03;
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
	private boolean firesProjectile;
	private int lastProjectileTick = Integer.MIN_VALUE;
	// Thrall projectiles seen while no thrall was ours yet: {thrall index, target index, tick}.
	private final List<int[]> unownedProjectiles = new ArrayList<>();
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
			claim(best[0], best[1], why, tick);
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
			claim(index, id, "spawned beside me as the varbit came up", tick);
		}
		else if (varbitActive && !hasThrall())
		{
			// Ours, still active, coming back into view: a new scene loads every npc afresh.
			claim(index, id, "spawned beside me while mine was active and unseen", tick);
		}
	}

	/**
	 * The last resort, for a thrall already out when tracking began: claimed only
	 * while the varbit says one of ours is active and the plugin can see exactly
	 * one candidate.
	 */
	void onOnlyCandidate(int index, int id, int tick)
	{
		if (varbitActive && !hasThrall())
		{
			claim(index, id, "the only thrall in view while mine was active", tick);
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

	private void claim(int index, int id, String why, int tick)
	{
		if (index == ownedIndex)
		{
			return;
		}
		ownedIndex = index;
		ownedId = id;
		maxHit = Thralls.maxHit(id);
		firesProjectile = Thralls.firesProjectile(id);
		due.clear();
		java.util.Arrays.fill(phase, 0);
		lastSplatTick = Integer.MIN_VALUE;
		lastObservedTick = Integer.MIN_VALUE;
		lastProjectileTick = Integer.MIN_VALUE;
		notes.add(String.format("THRALL claimed npc %d (index %d), max hit %d: %s", id, index, maxHit, why));
		// Its projectiles seen before it was known to be ours still bring their splats: 194 in the 16:08 log fired a tick
		// before its claim and its splat went to the player.
		for (int[] p : unownedProjectiles)
		{
			if (p[0] == index && tick - p[2] <= PROJECTILE_LATE)
			{
				addProjectileDue(p[1], p[2]);
			}
		}
		unownedProjectiles.clear();
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

	/**
	 * Our thrall started an attack animation. {@code targetIndex} is -1 when it
	 * names no target, which is what a thrall does, and then the window opens on
	 * whatever it hits.
	 */
	void onThrallAttackAnimation(int thrallIndex, int targetIndex, int tick)
	{
		if (thrallIndex != ownedIndex || thrallIndex < 0)
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

	/**
	 * A thrall projectile, first seen on this tick. {@code fromThrallIndex} is
	 * the thrall it came from: the one it names as its source, or, as ours do in
	 * practice, naming none, the thrall it set off from beside. It is ours when
	 * that is our thrall, and its splat lands on this tick or, at a stretch, the
	 * next. One from a thrall not ours is held for a tick, in case that thrall is
	 * claimed before its splat lands.
	 *
	 * @param fromThrallIndex the npc index of the thrall it came from, or -1 if none can be named
	 */
	void onThrallProjectile(int fromThrallIndex, int targetIndex, int tick)
	{
		if (targetIndex < 0 || fromThrallIndex < 0)
		{
			return;
		}
		if (fromThrallIndex != ownedIndex)
		{
			// Not ours, or not ours yet: a first thrall, or a recast's, can fire before it is claimed.
			unownedProjectiles.removeIf(p -> tick - p[2] > PROJECTILE_LATE);
			unownedProjectiles.add(new int[]{fromThrallIndex, targetIndex, tick});
			return;
		}
		addProjectileDue(targetIndex, tick);
	}

	private void addProjectileDue(int targetIndex, int tick)
	{
		lastObservedTick = tick;
		lastProjectileTick = tick;
		// The animation that went before it is the same attack: narrow its window rather than open a second.
		for (Due d : due)
		{
			if (!d.fromProjectile && tick - d.attackTick >= 0 && tick - d.attackTick <= ANIMATION_LATEST)
			{
				due.remove(d);
				break;
			}
		}
		final Due d = new Due(targetIndex, tick, tick, tick + PROJECTILE_LATE);
		d.fromProjectile = true;
		due.add(d);
	}

	private Due dueOn(int targetIndex, int tick)
	{
		for (Due d : due)
		{
			if ((d.targetIndex == targetIndex || d.targetIndex < 0) && tick >= d.earliest && tick <= d.latest)
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
		final Due d = dueOn(targetIndex, tick);
		if (d != null)
		{
			return d.fromProjectile ? RATE_THRALL_PROJECTILE : RATE_THRALL_DUE;
		}
		// Four ticks between attacks: one landed a tick or two ago means this cannot be its next.
		if (lastSplatTick != Integer.MIN_VALUE && tick - lastSplatTick < ATTACK_SPEED_TICKS - 1)
		{
			return RATE_THRALL_TOO_SOON;
		}
		// A ghost or skeleton attacks with nothing but projectiles, and none is due here. Before its first has been seen
		// that is weaker evidence, since that first could have gone unseen; the cycle cannot speak for it yet either.
		if (firesProjectile)
		{
			return lastProjectileTick != Integer.MIN_VALUE && tick - lastProjectileTick <= OBSERVATION_FRESH_TICKS
				? RATE_THRALL_NO_PROJECTILE : RATE_THRALL_NO_PROJECTILE_YET;
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

	/**
	 * The npcs our thrall's projectiles first seen on this tick were aimed at,
	 * whose splats nothing has yet been judged to be. A projectile always brings
	 * its splat on that tick, so at the end of it each one names a splat that
	 * went to the player by mistake - see the plugin's same-tick re-judging.
	 */
	List<Integer> unclaimedProjectileTargets(int tick)
	{
		final List<Integer> out = new ArrayList<>();
		for (Due d : due)
		{
			if (d.fromProjectile && d.attackTick == tick)
			{
				out.add(d.targetIndex);
			}
		}
		return out;
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
				// A projectile of ours always brings its splat. One that found none means its splat went to the player.
				if (d.fromProjectile)
				{
					notes.add(String.format("THRALL projectile seen on %d found no splat it was judged to own", d.attackTick));
				}
				it.remove();
			}
		}
		recentSpawns.removeIf(s -> tick - s[2] > SUMMON_WINDOW_TICKS);
		unownedProjectiles.removeIf(p -> tick - p[2] > PROJECTILE_LATE);
	}

	/**
	 * What the player could have done to put a splat on this npc now. Pure, so
	 * the plugin and the replay tests judge the player's side identically.
	 *
	 * @param burst           where this splat stands against the player's attack
	 *                        landing on this npc: its splats still to come this
	 *                        tick or the next, or all of them already down
	 * @param attackDueTick   the tick the weapon came off cooldown after the last
	 *                        attack booked, or {@code Integer.MIN_VALUE} for none
	 * @param lastFromProjectile whether that attack was booked from a projectile,
	 *                        whose own tick is known a tick less exactly
	 */
	static PlayerSplat classifyPlayer(AttackSplats.State burst, boolean projectileDue, boolean melee, int tick,
		int attackDueTick, boolean lastFromProjectile)
	{
		if (burst == AttackSplats.State.ROOM_THIS_TICK)
		{
			return PlayerSplat.SAME_TICK_BURST;
		}
		if (projectileDue)
		{
			return PlayerSplat.PROJECTILE_DUE;
		}
		// The attack on this tick has put all its splats down, and no other attack can land beside it: the cadence below
		// would call it on time, because this tick's blow is not booked until the tick ends.
		if (burst == AttackSplats.State.SPENT_THIS_TICK)
		{
			return PlayerSplat.EXTRA_SPLAT;
		}
		// Inside the cooldown by definition, so asked before it.
		if (burst == AttackSplats.State.LATE_DUE)
		{
			return PlayerSplat.LATE_SPLAT_DUE;
		}
		if (burst == AttackSplats.State.LATE_MAYBE)
		{
			return PlayerSplat.BURST_CONTINUING;
		}
		if (!melee)
		{
			return PlayerSplat.UNEXPLAINED;
		}
		if (attackDueTick == Integer.MIN_VALUE)
		{
			return PlayerSplat.MELEE_POSSIBLE;
		}
		// A blow lands the tick after it is thrown, and the earliest it can be thrown is the due tick. A projectile's own
		// tick is known a tick less exactly, so after one the window opens a tick early.
		final int earliest = attackDueTick + 1 - (lastFromProjectile ? 1 : 0);
		if (tick < earliest)
		{
			return PlayerSplat.IN_COOLDOWN;
		}
		return tick <= attackDueTick + 1 ? PlayerSplat.ON_CADENCE : PlayerSplat.LATE;
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
		unownedProjectiles.clear();
		summonTick = Integer.MIN_VALUE;
		varbitActive = false;
		notes.clear();
	}
}
