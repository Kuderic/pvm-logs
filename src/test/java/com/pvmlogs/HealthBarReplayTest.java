package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Hitsplat;
import org.junit.Test;

/**
 * Eric's confirmation fight on the T-001 build (Vardorvis, 2026-10-26 17:15, saved
 * from the panel): burning claws, no thrall, the boss health bar read every tick.
 *
 * <p>The bar is the game's own count of what the boss has left, so it is ground
 * truth for the plugin's. Every splat the log records on the target is replayed
 * through {@link HitsplatKind} (with the real API's {@code isMine()} for its type)
 * and {@link TargetHealth}'s counted path, the bar is never fed in, and the count
 * has to equal the bar at every reading: 54 changes from 700 down to 10, including
 * 14 burn splats and 20 heals.
 */
public class HealthBarReplayTest
{
	private static final String LOG = "replay/vardorvis-burn-heals-bar-20261026-1715.log";
	private static final int INDEX = 42791;
	private static final int VARDORVIS = 12223;

	private static final Pattern LINE = Pattern.compile("^\\[(\\d+)] (.*)$");
	// The player's splats, as the isMine() branch logs them.
	private static final Pattern MINE = Pattern.compile("^hitsplat (\\d+) type (\\d+) on .*index " + INDEX + "\\)");
	// Everything else on the target, as onNotMineSplat logs it.
	private static final Pattern OTHER = Pattern.compile("^splat (\\d+) .*\\(type (\\d+), (others'|no owner)\\) on .*index "
		+ INDEX + "\\)");
	// A small splat the thrall gate judged to be the thrall's.
	private static final Pattern THRALL = Pattern.compile("^hitsplat (\\d+) on .*\\(index " + INDEX + "\\): THRALL");
	private static final Pattern BAR = Pattern.compile("^health bar: npc varp (-?\\d+), hp (\\d+) / (\\d+).*-> OURS");

	private static Hitsplat splat(int type, int amount)
	{
		return new Hitsplat()
		{
			@Override
			public int getHitsplatType()
			{
				return type;
			}

			@Override
			public int getAmount()
			{
				return amount;
			}

			@Override
			public int getDisappearsOnGameCycle()
			{
				return 0;
			}
		};
	}

	@Test
	public void everyChangeOfTheBarIsTheSplatsCounted() throws Exception
	{
		final TargetHealth health = new TargetHealth();
		health.setTableMaxHp(INDEX, VARDORVIS, 700, 0);
		int readings = 0;
		int burn = 0;
		int healed = 0;
		int last = -1;
		final List<String> mismatches = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			HealthBarReplayTest.class.getResourceAsStream(LOG), StandardCharsets.UTF_8)))
		{
			String raw;
			while ((raw = in.readLine()) != null)
			{
				final Matcher line = LINE.matcher(raw);
				if (!line.matches())
				{
					continue;
				}
				final int tick = Integer.parseInt(line.group(1));
				final String body = line.group(2);
				Matcher m;
				if ((m = MINE.matcher(body)).find() || (m = OTHER.matcher(body)).find())
				{
					final int amount = Integer.parseInt(m.group(1));
					final Hitsplat s = splat(Integer.parseInt(m.group(2)), amount);
					final HitsplatKind kind = HitsplatKind.of(s.isMine(), s.isOthers(), s.getHitsplatType());
					if (kind == HitsplatKind.HEAL)
					{
						health.heal(INDEX, VARDORVIS, amount, tick);
						healed += amount;
					}
					else if (kind.damagesTarget())
					{
						health.damage(INDEX, VARDORVIS, amount, tick);
						if (kind == HitsplatKind.DAMAGE_OVER_TIME)
						{
							burn += amount;
						}
					}
				}
				else if ((m = THRALL.matcher(body)).find())
				{
					health.damage(INDEX, VARDORVIS, Integer.parseInt(m.group(1)), tick);
				}
				else if ((m = BAR.matcher(body)).find())
				{
					assertEquals(VARDORVIS, Integer.parseInt(m.group(1)));
					assertEquals(700, Integer.parseInt(m.group(3)));
					final int bar = Integer.parseInt(m.group(2));
					// The counted path only: the bar is what it is checked against, never fed in.
					final int counted = health.remaining(INDEX, tick);
					if (counted != bar)
					{
						mismatches.add("tick " + tick + ": bar " + bar + ", counted " + counted);
					}
					readings++;
					last = bar;
				}
			}
		}
		assertTrue(mismatches.toString(), mismatches.isEmpty());
		assertEquals(55, readings);
		assertEquals(10, last);
		assertEquals(40, burn);
		assertEquals(71, healed);
		assertEquals("counted", health.source(INDEX, Integer.MAX_VALUE / 2));
	}
}
