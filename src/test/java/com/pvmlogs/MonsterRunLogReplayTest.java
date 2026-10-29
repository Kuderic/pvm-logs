package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * T-089: Eric's Hallowfell trip of 2026-10-29 on the Catacombs' Abyssal demons,
 * [13849] to [16884], replayed through the room and fight lifecycle
 * ({@link MonsterRunReplay}). On the build that logged it the trip was 262
 * fights and, by the loot, as many rooms as drops; here it is one run.
 *
 * <p>The excerpt holds only the lines the replay reads: fight START and END,
 * the player's hitsplats, the AoE splats, ATTACK, the drops and the tick
 * lines. Until Eric OKs it as a fixture it is not in the repository, and the
 * test is skipped unless {@code -Dpvmlogs.demonLog} names the excerpt;
 * {@link MonsterRoomTest} has the same shapes synthetic.
 */
public class MonsterRunLogReplayTest
{
	static final String FIXTURE = "replay/abyssal-demons-hallowfell-20261029.log";
	private static final String DEMON = "Abyssal demon";

	private static final Pattern TICK = Pattern.compile("^\\[(\\d+)\\] ");
	private static final Pattern START = Pattern.compile(
		"fight START (.+?) \\(npc (\\d+), index (\\d+), maxHp (-?\\d+)\\)");
	private static final Pattern END = Pattern.compile("fight END (.+?) \\(npc (\\d+)\\) (.+?): attempts=(\\d+) .*?"
		+ "damage=(\\d+) ");
	private static final Pattern HIT = Pattern.compile("hitsplat (\\d+) type \\d+ on .+? \\(npc \\d+, index (\\d+)\\)");
	private static final Pattern AOE = Pattern.compile("AoE (\\d+) on (.+?) \\(npc \\d+, index (\\d+)\\) joins");
	private static final Pattern LOOT = Pattern.compile("LOOT from (.+?) \\(npc (\\d+), index (\\d+)\\): (item|no items)");

	/** What the replay saw, beside the run it made. */
	static final class Trip
	{
		final MonsterRunReplay run = new MonsterRunReplay();
		int starts;
		int ends;
		int drops;
		int attacks;
		// Fights whose replayed damage differed from their END line's.
		int mismatched;
		// Overdue ticks counted lost on that build that the run calls moving to the next demon: in a fight before its
		// first attack, once the trip had begun. (Ticks with the player aimed at the next demon are not in the log.)
		int lostBeforeFirstAttack;
		int lost;
	}

	static Trip replay(BufferedReader in) throws IOException
	{
		final Trip trip = new Trip();
		final MonsterRunReplay run = trip.run;
		run.clocked = true;
		boolean attackedYet = false;
		boolean tripEngaged = false;
		final List<int[]> unused = new ArrayList<>();
		for (String line; (line = in.readLine()) != null; )
		{
			final Matcher t = TICK.matcher(line);
			if (!t.find())
			{
				continue;
			}
			run.at(Long.parseLong(t.group(1)) * 600);
			final String body = line.substring(t.end());
			// Every line moves the clock, so the pause is checked as the plugin checks it, each tick.
			run.waits(0);
			Matcher m;
			if ((m = START.matcher(body)).lookingAt())
			{
				trip.starts++;
				run.fight(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
					Integer.parseInt(m.group(4)));
				attackedYet = false;
			}
			else if ((m = END.matcher(body)).lookingAt())
			{
				trip.ends++;
				if (run.open != null && run.open.getDamageDealt() != Integer.parseInt(m.group(5)))
				{
					trip.mismatched++;
				}
				if (m.group(3).equals("target died"))
				{
					run.dies(run.open.getTargetIndex());
				}
				else if (m.group(3).equals("target despawned"))
				{
					run.despawns(run.open.getTargetIndex());
				}
			}
			else if ((m = HIT.matcher(body)).lookingAt())
			{
				if (run.open != null && !run.open.isEnded() && run.open.getTargetIndex() == Integer.parseInt(m.group(2)))
				{
					run.hit(Integer.parseInt(m.group(1)));
				}
			}
			else if ((m = AOE.matcher(body)).lookingAt())
			{
				run.splash(m.group(2), Integer.parseInt(m.group(3)), Integer.parseInt(m.group(1)));
			}
			else if (body.startsWith("ATTACK on "))
			{
				trip.attacks++;
				run.attack();
				attackedYet = true;
				tripEngaged = true;
			}
			else if ((m = LOOT.matcher(body)).lookingAt())
			{
				trip.drops++;
				final int index = Integer.parseInt(m.group(3));
				// The despawn of one that died while not the target is not logged: its drop says it died.
				if (run.open == null || run.open.isEnded() || run.open.getTargetIndex() != index)
				{
					run.dies(index);
				}
				run.loot(Integer.parseInt(m.group(2)), index);
			}
			else if (body.startsWith("tick LOST") && !body.contains("(eating"))
			{
				trip.lost++;
				if (tripEngaged && !attackedYet)
				{
					trip.lostBeforeFirstAttack++;
				}
			}
		}
		// The log stops with the trip: its pause runs out.
		run.waits(MonsterRooms.PAUSE_MILLIS);
		return trip;
	}

	private static Trip trip() throws IOException
	{
		// Or the excerpt where it is, outside the repository: -Dpvmlogs.demonLog=<path>.
		final String path = System.getProperty("pvmlogs.demonLog");
		final InputStream in = path != null ? java.nio.file.Files.newInputStream(java.nio.file.Paths.get(path))
			: MonsterRunLogReplayTest.class.getResourceAsStream(FIXTURE);
		assumeNotNull(in);
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)))
		{
			return replay(reader);
		}
	}

	@Test
	public void theDemonTripIsOneRun() throws IOException
	{
		final Trip trip = trip();
		// The excerpt as read: 262 fights on demons and one on a Reanimated abyssal, 475 attacks, 111 drops.
		assertEquals(263, trip.starts);
		assertEquals(263, trip.ends);
		assertEquals(476, trip.attacks);
		assertEquals(111, trip.drops);
		// Every fight's damage replayed as its END line had it: the parse misses nothing.
		assertEquals(0, trip.mismatched);

		final List<Encounter> fought = trip.run.fought();
		final List<Encounter> demons = new ArrayList<>();
		for (Encounter room : fought)
		{
			if (room.getName().equals(DEMON))
			{
				demons.add(room);
			}
		}
		// One run of demons, and the Reanimated abyssal's swing a room of its own beside it.
		assertEquals(1, demons.size());
		assertEquals(2, fought.size());
		final Encounter run = demons.get(0);
		assertTrue(run.isEnded());
		assertTrue(run.isKilled());
		// The 244 demon fights with an attack in them, all in the one room.
		int attempted = 0;
		for (Fight fight : run.getFights())
		{
			attempted += fight.getAttempts() > 0 ? 1 : 0;
		}
		assertEquals(244, attempted);
		// Its damage is every splat of the player's on a demon, splash included, and the 31 the axe splashed on the
		// Reanimated abyssal while a demon was the target: 17,133, the fights' END lines summed.
		assertEquals(17133, run.getDamageDealt());
		// Every drop is a kill, counted once though the target's death came too, and so is the one target that died with
		// no drop ([15990]). A demon the splash killed that dropped nothing has no line here, so the plugin may count more.
		assertEquals(111 + 1, run.getKills());
		assertEquals(1, kills(fought, "Reanimated abyssal"));
		assertNotNull(run.lastAttempted());
		assertTrue(run.lastAttempted().isClosedRoom());
	}

	private static int kills(List<Encounter> rooms, String name)
	{
		int n = 0;
		for (Encounter room : rooms)
		{
			if (room.getName().equals(name))
			{
				n += room.getKills();
			}
		}
		return n;
	}

	@Test
	public void demonsSwitchedToKeepWhatTheSplashTookOff() throws IOException
	{
		final Trip trip = trip();
		// Most fights open on a demon the axe had already reached: on that build each started at a full 150.
		assertTrue(trip.run.startedHurt > 100);
		assertTrue(trip.run.hpCarried > trip.run.startedHurt * 30);
		assertFalse(trip.run.damageByIndex.values().stream().anyMatch(v -> v > 150 + 59));
	}

	@Test
	public void historyHoldsTheTripAsOneRow() throws IOException
	{
		final Trip trip = trip();
		int demonRooms = 0;
		for (Encounter room : SidePanelHistory.allRooms(trip.run.history))
		{
			if (room.getName().equals(DEMON))
			{
				demonRooms++;
				assertEquals(112, room.getKills());
			}
		}
		assertEquals(1, demonRooms);
	}
}
