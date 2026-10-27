package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * T-035's sums on Eric's committed fixtures: every fight's timeline adds up to
 * the fight's own totals. Each fixture's fights are rebuilt from its lines in
 * the plugin's order (an attack, its expectation, its splats and re-judged
 * splats, AoE splats, every tick spent or lost), into real {@link Fight}s with
 * timelines attached, as the plugin does.
 */
public class FightTimelineReplayTest
{
	private static final Pattern LINE = Pattern.compile("^\\[(\\d+)\\] (.*)$");
	private static final Pattern START = Pattern.compile("^fight START (.*?) \\(npc (\\d+), index (\\d+), maxHp (-?\\d+)\\)");
	private static final Pattern ATTACK = Pattern.compile("^ATTACK on .* thrown tick (-?\\d+),");
	private static final Pattern SCORED = Pattern.compile(
		"^\\s*scored as (a SPECIAL|an ordinary attack): max (\\d+), chances ([\\d.]+), avg (-?[\\d.]+)(?:, var (-?[\\d.]+))?");
	private static final Pattern HITSPLAT = Pattern.compile("^hitsplat (\\d+) type \\d+ on .* landedAttack=(true|false)");
	private static final Pattern REJUDGED = Pattern.compile("^RE-JUDGED hitsplat (\\d+) on .*\\(was player.*: THRALL");
	private static final Pattern AOE = Pattern.compile("^AoE (\\d+) on .* newTarget=(true|false) landed=(true|false)");
	private static final Pattern SPENT = Pattern.compile("^tick SPENT: .*attacking=(true|false)");
	private static final Pattern LOST = Pattern.compile("^tick LOST(?: \\(eating \\d+/\\d+\\))?:");

	private static final class Replayed
	{
		final List<Fight> fights = new ArrayList<>();
		final FightTimeline.Store store = new FightTimeline.Store();
		int tick;
	}

	private static Replayed replay(String log) throws IOException
	{
		final Replayed r = new Replayed();
		r.store.setClock(() -> r.tick);
		Fight current = null;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			FightTimelineReplayTest.class.getResourceAsStream("/com/pvmlogs/replay/" + log), StandardCharsets.UTF_8)))
		{
			String raw;
			while ((raw = in.readLine()) != null)
			{
				final Matcher line = LINE.matcher(raw.trim());
				if (!line.matches())
				{
					continue;
				}
				r.tick = Integer.parseInt(line.group(1));
				final String body = line.group(2);
				Matcher m;
				if ((m = START.matcher(body)).find())
				{
					current = new Fight(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
						Integer.parseInt(m.group(4)), 0L, null, 0);
					r.store.attach(current);
					r.fights.add(current);
				}
				else if (current == null)
				{
					continue;
				}
				else if ((m = ATTACK.matcher(body)).find())
				{
					current.recordAttackMade(false, 1.0, false);
					r.store.of(current).describeLatest("replayed", -1, null, FightTimeline.Style.UNKNOWN,
						Integer.parseInt(m.group(1)));
				}
				else if ((m = SCORED.matcher(body.trim())).find())
				{
					final double average = Double.parseDouble(m.group(4));
					// The fixtures predate T-028's variance; any spread will do for the sums.
					final double variance = m.group(5) != null ? Double.parseDouble(m.group(5)) : average * average / 3;
					if (m.group(1).equals("a SPECIAL"))
					{
						r.store.of(current).markLatestSpecial();
					}
					current.recordExpected(Integer.parseInt(m.group(2)), Double.parseDouble(m.group(3)), average, variance);
				}
				else if ((m = HITSPLAT.matcher(body)).find())
				{
					current.recordDamageDealt(Integer.parseInt(m.group(1)), 0L, Boolean.parseBoolean(m.group(2)));
					r.store.of(current).attributeSplat(Integer.MIN_VALUE);
				}
				else if ((m = REJUDGED.matcher(body)).find())
				{
					final int amount = Integer.parseInt(m.group(1));
					current.revokeDamageDealt(amount, amount > 0);
				}
				else if ((m = AOE.matcher(body)).find())
				{
					current.recordExtraTarget(Integer.parseInt(m.group(1)), 0L, Boolean.parseBoolean(m.group(2)),
						Boolean.parseBoolean(m.group(3)));
				}
				else if ((m = SPENT.matcher(body)).find())
				{
					// The log's "attacking" is the plugin's counts flag, the room's exemption included.
					current.recordTickSpent(Boolean.parseBoolean(m.group(1)));
				}
				else if (LOST.matcher(body).find())
				{
					current.recordTickLost(body.startsWith("tick LOST (eating"), !body.contains("NOT COUNTED"));
				}
			}
		}
		return r;
	}

	private static void assertSumsMatch(String log) throws IOException
	{
		final Replayed r = replay(log);
		assertTrue(log, r.fights.size() > 1);
		int checked = 0;
		for (Fight fight : r.fights)
		{
			final FightTimeline line = r.store.of(fight);
			if (line == null)
			{
				continue;
			}
			final String what = log + ": " + fight.getTargetName() + " started " + line.startTick();
			assertEquals(what + " damage", fight.getDamageDealt(), line.totalDamage());
			assertEquals(what + " expected", fight.getSumExpectedAverageHit(), line.totalExpected(), 1e-6);
			assertEquals(what + " variance", fight.getSumExpectedDamageVariance(), line.totalVariance(), 1e-6);
			assertEquals(what + " lost", fight.getTicksLost(), line.lostTicks());
			assertEquals(what + " eating", fight.getTicksLostEating(), line.eatingTicks());
			int attributed = 0;
			for (FightTimeline.Attack attack : line.attacks())
			{
				attributed += attack.damage;
			}
			assertEquals(what + " attributed", fight.getDamageDealt(), attributed + line.unattributedDamage());
			assertEquals(what + " attacks", fight.getAttacksMade(), line.attacks().size());
			if (line.length() > 0)
			{
				final float[] damage = line.cumulativeDamage(0);
				assertEquals(what + " cumulative", fight.getDamageDealt(), damage[damage.length - 1], 0.5);
			}
			// Every tick the fight counted has its state, one each.
			int counted = 0;
			for (int i = 0; i < line.length(); i++)
			{
				counted += line.stateAt(i) != FightTimeline.NONE ? 1 : 0;
			}
			assertEquals(what + " ticks", fight.getCombatTicks(), counted);
			checked++;
		}
		assertTrue(log, checked > 1);
		assertTrue(log + " under the cap", r.store.approximateBytes() < 1_500_000);
	}

	@Test
	public void vardorvisEightKills() throws IOException
	{
		assertSumsMatch("vardorvis-8-kills-20261026.log");
	}

	@Test
	public void vardorvisClawsRecoilVengeance() throws IOException
	{
		assertSumsMatch("vardorvis-claws-recoil-veng-20261026-2308.log");
	}

	@Test
	public void sireTenKills() throws IOException
	{
		assertSumsMatch("sire-10-kills-20261027-0130.log");
	}

	@Test
	public void sireRecoilVengeance() throws IOException
	{
		assertSumsMatch("sire-recoil-veng-20261027-1455.log");
	}
}
