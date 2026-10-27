package com.pvmlogs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Skill;
import net.runelite.api.gameval.ItemID;

/**
 * Replays a debug log through the thrall and experience logic, the way the
 * plugin drives it: events in the order they were logged, and at the end of
 * each tick the ledger closed and expired, the tracker ticked, and a blow booked.
 *
 * <p>Built for the logs of a melee player (every splat the player lands books a
 * blow the tick after it was thrown, as the plugin's melee path does) at a fixed
 * weapon speed. The log does not record where a projectile left from, so every
 * thrall projectile is taken to have left from beside ours - true in a solo
 * fight, which is what these logs are.
 *
 * <p>How many splats an attack puts down (T-017) is given rather than read: the
 * log names no weapon per attack. Eric's logs are a one-hit weapon with burning
 * claws for the special, which is the default. Which attacks were specials is
 * read from the log's own "scored as a SPECIAL" lines, written at the booking on
 * the attack's first tick; the plugin knows it from the energy, ahead of the
 * splats.
 */
final class ThrallReplay
{
	private static final Pattern LINE = Pattern.compile("^\\[(\\d+)\\] (.*)$");
	private static final Pattern XP = Pattern.compile("^xp drop ([A-Z]+) \\+(\\d+)");
	private static final Pattern SPAWN = Pattern.compile("^THRALL spawned: npc (\\d+) \\(index (\\d+)\\), (\\d+) tiles");
	private static final Pattern CLAIMED = Pattern.compile("^THRALL claimed npc (\\d+) \\(index (\\d+)\\)");
	private static final Pattern RELEASED = Pattern.compile("^THRALL released npc \\d+ \\(index (\\d+)\\): despawned");
	private static final Pattern VARBIT = Pattern.compile("^THRALL resurrection active varbit -> (\\d+)");
	private static final Pattern ANIMATION = Pattern.compile("^THRALL animation (-?\\d+): npc \\d+ \\(index (\\d+)\\)");
	private static final Pattern PROJECTILE = Pattern.compile(
		"^THRALL projectile \\d+ from (no source|index (\\d+)) at npc \\d+ \\(index (\\d+)\\)");
	private static final Pattern JUDGED = Pattern.compile("^hitsplat (\\d+) on .* \\(index (\\d+)\\): (THRALL|player),");
	private static final Pattern RAW = Pattern.compile("^hitsplat (\\d+) type (\\d+) on .*index (\\d+)\\)");
	private static final Pattern SPECIAL = Pattern.compile("^ +scored as a SPECIAL");
	private static final Pattern START = Pattern.compile("^fight START .*\\(npc (\\d+),");
	private static final Set<Integer> NON_ATTACK_ANIMATIONS = new HashSet<>(Arrays.asList(
		-1, 9047, 9048, 13683, 11595, 13674));

	/** One splat as the replay judged it. */
	static final class Judged
	{
		final int tick;
		final int amount;
		final int targetIndex;
		final boolean thralls;
		final double probability;
		final ThrallTracker.PlayerSplat player;
		final int fight;

		Judged(int tick, int amount, int targetIndex, boolean thralls, double probability,
			ThrallTracker.PlayerSplat player, int fight)
		{
			this.tick = tick;
			this.amount = amount;
			this.targetIndex = targetIndex;
			this.thralls = thralls;
			this.probability = probability;
			this.player = player;
			this.fight = fight;
		}

		@Override
		public String toString()
		{
			return String.format("%d: %d -> %s (p=%.2f, %s)", tick, amount, thralls ? "THRALL" : "player", probability,
				player);
		}
	}

	// Configuration.
	private final double formulaMultiplier;
	private final double playerAccuracy;
	private final int playerMaxHit;
	private final int weaponSpeed;
	private final AttackSplats ordinarySplats;
	private final AttackSplats specialSplats;

	// What the replay drives: the real classes.
	final ThrallTracker thralls = new ThrallTracker();
	final XpLedger ledger = new XpLedger();

	// The plugin's player-side state, as far as the judgement reads it.
	private int attackDueTick = Integer.MIN_VALUE;
	private final Map<Integer, Integer> lastHitsplatTick = new HashMap<>();
	private final Set<Integer> burstBooked = new HashSet<>();
	private final Map<Integer, AttackSplats.Burst> bursts = new HashMap<>();
	private int bookOnTick = Integer.MIN_VALUE;
	// This tick's player splats, as the plugin keeps them for re-judging: {judged index or -1, npc, amount, booked,
	// previous seen tick or MIN}.
	private final List<int[]> playerSplatsThisTick = new ArrayList<>();
	/** Splats re-judged to the thrall at the end of their tick. */
	final List<Judged> rejudged = new ArrayList<>();
	private int targetId = -1;
	private int fight;
	// The last thrall seen spawning or claimed, which a projectile naming no source is taken to have come from: these
	// are solo logs, and the log does not say where a projectile started.
	private int lastThrall = -1;
	// The ticks an attack booked as a special began landing on, from the log's own "scored as a SPECIAL" lines.
	private final Set<Integer> specialStarts = new HashSet<>();

	// Outputs.
	final List<Judged> judged = new ArrayList<>();
	/** Per fight, the ticks blows were thrown on. */
	final Map<Integer, List<Integer>> blows = new HashMap<>();
	/** Per fight, the ticks a thrall projectile was first seen on. */
	final Map<Integer, List<Integer>> thrallProjectiles = new HashMap<>();
	/** Per fight, the splats certain to be the player's (too big for a thrall, or no thrall out), as ticks. */
	final Map<Integer, List<Integer>> cleanHits = new HashMap<>();
	/** Per fight, the first tick the ledger reported itself trusted, if it did. */
	final Map<Integer, Integer> trustedFrom = new HashMap<>();
	/** Splats the player kept beyond what their attack deals, as "tick: amount" (the plugin's EXTRA SPLAT lines). */
	final List<String> extras = new ArrayList<>();
	final List<String> notes = new ArrayList<>();

	ThrallReplay(double formulaMultiplier, double playerAccuracy, int playerMaxHit, int weaponSpeed)
	{
		// Eric's logs: a one-hit weapon, burning claws for the special.
		this(formulaMultiplier, playerAccuracy, playerMaxHit, weaponSpeed, AttackSplats.ONE,
			AttackSplats.of(ItemID.BONE_CLAWS, true, 2, null));
	}

	ThrallReplay(double formulaMultiplier, double playerAccuracy, int playerMaxHit, int weaponSpeed,
		AttackSplats ordinarySplats, AttackSplats specialSplats)
	{
		this.formulaMultiplier = formulaMultiplier;
		this.playerAccuracy = playerAccuracy;
		this.playerMaxHit = playerMaxHit;
		this.weaponSpeed = weaponSpeed;
		this.ordinarySplats = ordinarySplats;
		this.specialSplats = specialSplats;
	}

	ThrallReplay run(String resource) throws IOException
	{
		final List<String> lines = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			ThrallReplay.class.getResourceAsStream(resource), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = in.readLine()) != null)
			{
				lines.add(line);
			}
		}
		return run(lines);
	}

	/** Replays log lines written out in a test. */
	ThrallReplay run(List<String> lines)
	{
		for (String line : lines)
		{
			final Matcher m = LINE.matcher(line);
			if (m.matches() && SPECIAL.matcher(m.group(2)).find())
			{
				specialStarts.add(Integer.parseInt(m.group(1)));
			}
		}
		int tick = Integer.MIN_VALUE;
		int[] pendingJudged = null;
		for (String line : lines)
		{
			final Matcher m = LINE.matcher(line);
			if (!m.matches())
			{
				continue;
			}
			final int t = Integer.parseInt(m.group(1));
			final String body = m.group(2);
			if (t != tick)
			{
				if (tick != Integer.MIN_VALUE)
				{
					endTick(tick);
				}
				tick = t;
			}
			Matcher e;
			if (SPECIAL.matcher(body).find())
			{
				continue; // read up front
			}
			else if ((e = START.matcher(body)).find())
			{
				fight++;
				targetId = Integer.parseInt(e.group(1));
			}
			else if (body.startsWith("fight END"))
			{
				targetId = -1;
				attackDueTick = Integer.MIN_VALUE;
				lastHitsplatTick.clear();
				burstBooked.clear();
				bursts.clear();
			}
			else if ((e = XP.matcher(body)).find())
			{
				ledger.add(Skill.valueOf(e.group(1)), Integer.parseInt(e.group(2)));
			}
			else if ((e = CLAIMED.matcher(body)).find())
			{
				// A log saved without file logging has no spawn lines, only the claim at the end of its tick.
				final int index = Integer.parseInt(e.group(2));
				lastThrall = index;
				if (thralls.ownedIndex() != index)
				{
					thralls.onThrallSpawned(index, Integer.parseInt(e.group(1)), 1, t);
					thralls.onResurrectionVarbit(true, t);
				}
			}
			else if ((e = SPAWN.matcher(body)).find())
			{
				final int distance = Integer.parseInt(e.group(3));
				lastThrall = Integer.parseInt(e.group(2));
				thralls.onThrallSpawned(Integer.parseInt(e.group(2)), Integer.parseInt(e.group(1)), distance, t);
				// The plugin polls the varbit each tick; the log shows the claim on the spawn tick itself.
				if (distance <= ThrallTracker.SPAWN_RADIUS)
				{
					thralls.onResurrectionVarbit(true, t);
				}
			}
			else if ((e = RELEASED.matcher(body)).find())
			{
				thralls.onThrallDespawned(Integer.parseInt(e.group(1)), t);
			}
			else if ((e = VARBIT.matcher(body)).find())
			{
				final boolean active = "1".equals(e.group(1));
				if (active != thralls.varbitActive())
				{
					thralls.onResurrectionVarbit(active, t);
				}
			}
			else if ((e = ANIMATION.matcher(body)).find())
			{
				final int animation = Integer.parseInt(e.group(1));
				if (!NON_ATTACK_ANIMATIONS.contains(animation))
				{
					thralls.onThrallAttackAnimation(Integer.parseInt(e.group(2)), -1, t);
				}
			}
			else if ((e = PROJECTILE.matcher(body)).find())
			{
				final int source = e.group(2) == null ? lastThrall : Integer.parseInt(e.group(2));
				thralls.onThrallProjectile(source, Integer.parseInt(e.group(3)), t);
				thrallProjectiles.computeIfAbsent(fight, k -> new ArrayList<>()).add(t);
			}
			else if ((e = JUDGED.matcher(body)).find())
			{
				final int amount = Integer.parseInt(e.group(1));
				final int index = Integer.parseInt(e.group(2));
				splat(t, amount, index);
				pendingJudged = new int[]{t, amount};
			}
			else if ((e = RAW.matcher(body)).find())
			{
				final int amount = Integer.parseInt(e.group(1));
				// The plugin logged a judged line and then, for a splat it gave the player, this one: the same splat.
				if (pendingJudged != null && pendingJudged[0] == t && pendingJudged[1] == amount)
				{
					pendingJudged = null;
					continue;
				}
				pendingJudged = null;
				splat(t, amount, Integer.parseInt(e.group(3)));
			}
		}
		if (tick != Integer.MIN_VALUE)
		{
			endTick(tick);
		}
		return this;
	}

	private void splat(int tick, int amount, int index)
	{
		if (thralls.hasThrall() && amount <= thralls.maxHit())
		{
			final ThrallTracker.Evidence ev = new ThrallTracker.Evidence();
			ev.amount = amount;
			ev.tick = tick;
			ev.targetIndex = index;
			final AttackSplats.Burst burst = bursts.get(index);
			ev.player = ThrallTracker.classifyPlayer(burst == null ? AttackSplats.State.NONE : burst.stateOn(tick),
				false, true, tick, attackDueTick, false);
			ev.xp = ledger.coverage(amount, tick);
			ev.xpTrusted = ledger.trusted();
			ev.playerAccuracy = playerAccuracy;
			ev.playerMaxHit = playerMaxHit;
			final double p = thralls.probability(ev);
			final boolean isThralls = p >= 0.5;
			judged.add(new Judged(tick, amount, index, isThralls, p, ev.player, fight));
			if (isThralls)
			{
				thralls.noteThrallSplat(index, tick);
				return;
			}
			ledger.spend(amount, tick, false);
			playerSplat(tick, amount, index, judged.size() - 1);
		}
		else
		{
			ledger.spend(amount, tick, true);
			if (amount > 0)
			{
				cleanHits.computeIfAbsent(fight, k -> new ArrayList<>()).add(tick);
			}
			playerSplat(tick, amount, index, -1);
		}
	}

	// The player's: a burst on one npc is one blow, booked from its first splat.
	private void playerSplat(int tick, int amount, int index, int judgedAt)
	{
		final Integer seen = lastHitsplatTick.put(index, tick);
		if (seen == null || tick - seen > 1)
		{
			burstBooked.remove(index);
		}
		final boolean booked = burstBooked.add(index);
		if (booked)
		{
			bookOnTick = tick;
			bursts.put(index, new AttackSplats.Burst(tick - 1, tick, ordinarySplats, specialSplats,
				specialStarts.contains(tick), "replay"));
		}
		else
		{
			final AttackSplats.Burst burst = bursts.get(index);
			if (burst != null && burst.belongsOn(tick) && !burst.give(tick))
			{
				extras.add(tick + ": " + amount);
			}
		}
		playerSplatsThisTick.add(new int[]{judgedAt, index, amount, booked ? 1 : 0,
			seen == null ? Integer.MIN_VALUE : seen});
	}

	// As the plugin's rejudgeUnclaimedProjectiles: before the tick's blow is booked.
	private void rejudge(int tick)
	{
		for (int target : thralls.unclaimedProjectileTargets(tick))
		{
			int[] pick = null;
			for (int[] r : playerSplatsThisTick)
			{
				if (r[0] >= 0 && r[1] == target && r[2] <= thralls.maxHit()
					&& (pick == null || judged.get(r[0]).probability > judged.get(pick[0]).probability))
				{
					pick = r;
				}
			}
			if (pick == null)
			{
				continue;
			}
			final Judged was = judged.get(pick[0]);
			final Judged now = new Judged(was.tick, was.amount, was.targetIndex, true, was.probability, was.player,
				was.fight);
			judged.set(pick[0], now);
			rejudged.add(now);
			thralls.noteThrallSplat(target, tick);
			boolean other = false;
			for (int[] o : playerSplatsThisTick)
			{
				other |= o != pick && o[1] == target;
			}
			if (other)
			{
				final AttackSplats.Burst burst = bursts.get(target);
				if (burst != null)
				{
					burst.takeBack();
				}
			}
			else
			{
				if (pick[3] == 1 && bookOnTick == tick)
				{
					bookOnTick = Integer.MIN_VALUE;
				}
				burstBooked.remove(target);
				bursts.remove(target);
				if (pick[4] == Integer.MIN_VALUE)
				{
					lastHitsplatTick.remove(target);
				}
				else
				{
					lastHitsplatTick.put(target, pick[4]);
				}
			}
		}
		playerSplatsThisTick.clear();
	}

	private void endTick(int tick)
	{
		if (thralls.hasThrall())
		{
			rejudge(tick);
		}
		playerSplatsThisTick.clear();
		ledger.close(tick, targetId, formulaMultiplier, null);
		notes.addAll(ledger.expire(tick));
		thralls.tick(tick);
		notes.addAll(thralls.drainNotes());
		if (bookOnTick == tick && targetId >= 0)
		{
			// Thrown the tick before its splat; the weapon is next due a speed later.
			final int thrown = tick - 1;
			attackDueTick = thrown + weaponSpeed;
			blows.computeIfAbsent(fight, k -> new ArrayList<>()).add(thrown);
		}
		if (targetId >= 0 && ledger.trusted())
		{
			trustedFrom.putIfAbsent(fight, tick);
		}
	}

	List<Judged> judgedBetween(int from, int to)
	{
		final List<Judged> out = new ArrayList<>();
		for (Judged j : judged)
		{
			if (j.tick >= from && j.tick <= to)
			{
				out.add(j);
			}
		}
		return out;
	}
}
