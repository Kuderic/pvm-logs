package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Skill;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * T-021: attacks booked into the fight on the npc they hit, replayed over Eric's ten
 * Abyssal Sire kills of 2026-10-27 (the T-020 fixture), recorded on a build from
 * before either task.
 *
 * <p>The fights open and close where that build opened and closed them (its fight
 * START and END lines). What is booked into them is decided again, with the pieces
 * T-021 changed:
 * <ul>
 * <li>a projectile taken as mine books into {@link PvmPerformancePlugin#attackerFor}'s
 * fight, the one on the npc it was aimed at, thrown two ticks before (the booking
 * lag the plugin uses for a projectile);</li>
 * <li>a melee blow the old build booked books the same way, on the npc its splat
 * landed on;</li>
 * <li>a ranged splat that arrived as no projectile of mine books as an unseen shot
 * ({@link PvmPerformancePlugin#shotThrownOn}) from the tick its experience was paid,
 * read from the real {@link XpLedger} fed the log's drops and awards.</li>
 * </ul>
 * As in the plugin, a fight that ends with nothing booked into it is discarded.
 */
public class SireBookingReplayTest
{
	private static final String LOG = "replay/sire-10-kills-20261027-0130.log";
	private static final int RESPIRATORY = 5914;
	private static final int PROJECTILE_LAG = 2;
	private static final int FLIGHT_SLACK = 1;

	private static final Pattern LINE = Pattern.compile("^\\[(\\d+)] (.*)$");
	private static final Pattern START = Pattern.compile(
		"^fight START (.+) \\(npc (\\d+), index (\\d+), maxHp (-?\\d+)\\)");
	private static final Pattern END = Pattern.compile("^fight END .+ \\(npc \\d+\\) (.+?): attempts=");
	private static final Pattern PROJECTILE = Pattern.compile(
		"^projectile \\d+ taken as mine, aimed at npc (\\d+) \\(index (\\d+)\\), lands tick (\\d+)");
	private static final Pattern MINE = Pattern.compile(
		"^hitsplat (\\d+) type \\d+ on .*\\(npc (\\d+), index (\\d+)\\), landedAttack=(true|false), fromFlight=(true|false)");
	private static final Pattern ATTACK = Pattern.compile(
		"^ATTACK on .+ thrown tick (\\d+), booked \\+\\d+, speed (\\d+), .* projectile=(true|false)");
	private static final Pattern EXPECTED = Pattern.compile("^expected vs .+ \\(npc (\\d+), .*avg ([\\d.]+), dps");
	private static final Pattern DROP = Pattern.compile("^xp drop (\\w+) \\+(\\d+)");
	private static final Pattern AWARD = Pattern.compile("^xp award tick \\d+: .*x([\\d.]+)\\) against npc (-?\\d+)");

	/** One booking the replay made. */
	static final class Booking
	{
		final int thrown;
		final int bookedOn;
		final int aimedIndex;
		final Fight fight;
		final boolean unseen;

		Booking(int thrown, int bookedOn, int aimedIndex, Fight fight, boolean unseen)
		{
			this.thrown = thrown;
			this.aimedIndex = aimedIndex;
			this.bookedOn = bookedOn;
			this.fight = fight;
			this.unseen = unseen;
		}
	}

	private static final List<Booking> bookings = new ArrayList<>();
	/** Every fight kept (something was booked into it), in the order they ended. */
	private static final List<Fight> kept = new ArrayList<>();
	/** Fights that ended with damage on them and nothing booked: discarded, their damage counted nowhere. */
	private static final List<String> orphaned = new ArrayList<>();

	@BeforeClass
	public static void replay() throws Exception
	{
		final List<String[]> lines = new ArrayList<>();
		final Map<Integer, Double> expectedById = new HashMap<>();
		final Set<Integer> meleeBookedTicks = new HashSet<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			SireBookingReplayTest.class.getResourceAsStream(LOG), StandardCharsets.UTF_8)))
		{
			String raw;
			while ((raw = in.readLine()) != null)
			{
				final Matcher m = LINE.matcher(raw);
				if (!m.matches())
				{
					continue;
				}
				lines.add(new String[]{m.group(1), m.group(2)});
				// The expected figure the old build first logged for each npc id: what the replay records an attack as
				// expecting. Taken up front, since a fight's first attack books before its line is logged.
				final Matcher x = EXPECTED.matcher(m.group(2));
				if (x.find())
				{
					expectedById.putIfAbsent(Integer.parseInt(x.group(1)), Double.parseDouble(x.group(2)));
				}
				final Matcher a = ATTACK.matcher(m.group(2));
				if (a.find() && "false".equals(a.group(3)))
				{
					// The old build booked a blow from this tick's splat: melee, or a cast seen without its projectile.
					meleeBookedTicks.add(Integer.parseInt(m.group(1)));
				}
			}
		}

		final XpLedger ledger = new XpLedger();
		final Map<Integer, List<Integer>> pending = new HashMap<>();
		final Map<Integer, Integer> lastSplat = new HashMap<>();
		Fight current = null;
		Fight lastFinished = null;
		int attackDueTick = Integer.MIN_VALUE;
		int speed = 4;
		for (String[] line : lines)
		{
			final int t = Integer.parseInt(line[0]);
			final String body = line[1];
			final long now = t * 600L;
			Matcher m;
			if ((m = START.matcher(body)).find())
			{
				current = new Fight(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
					Integer.parseInt(m.group(4)), now, null, 0);
				current.noteStartTick(t);
			}
			else if ((m = END.matcher(body)).find() && current != null)
			{
				current.end(m.group(1).startsWith("target died"), now);
				if (current.getAttempts() > 0)
				{
					kept.add(current);
					lastFinished = current;
				}
				else if (current.getDamageDealt() > 0)
				{
					orphaned.add(t + ": " + current.getTargetName() + " " + current.getDamageDealt());
				}
				current = null;
			}
			else if ((m = DROP.matcher(body)).find())
			{
				ledger.add(Skill.valueOf(m.group(1)), Integer.parseInt(m.group(2)));
			}
			else if ((m = AWARD.matcher(body)).find())
			{
				ledger.close(t, Integer.parseInt(m.group(2)), Double.parseDouble(m.group(1)), null);
				ledger.expire(t);
			}
			else if ((m = ATTACK.matcher(body)).find())
			{
				// The weapon's speed as that build read it, for the cooldown an unseen shot is checked against.
				speed = Integer.parseInt(m.group(2));
				attackDueTick = Math.max(attackDueTick, Integer.parseInt(m.group(1)) + speed);
			}
			else if ((m = PROJECTILE.matcher(body)).find())
			{
				final int npcId = Integer.parseInt(m.group(1));
				final int index = Integer.parseInt(m.group(2));
				pending.computeIfAbsent(index, k -> new ArrayList<>()).add(Integer.parseInt(m.group(3)));
				final int thrown = t - PROJECTILE_LAG;
				book(PvmPerformancePlugin.attackerFor(index, thrown, current, lastFinished), npcId, index, thrown, t, false,
					expectedById);
				attackDueTick = thrown + speed;
			}
			else if ((m = MINE.matcher(body)).find() && current != null)
			{
				final int amount = Integer.parseInt(m.group(1));
				final int npcId = Integer.parseInt(m.group(2));
				final int index = Integer.parseInt(m.group(3));
				final boolean fromFlight = "true".equals(m.group(5));
				final int paidOn = ledger.attackTickFor(amount, t);
				ledger.spend(amount, t, true);
				if (current.getTargetIndex() == index)
				{
					current.recordDamageDealt(amount, now, "true".equals(m.group(4)));
				}
				final Integer seen = lastSplat.put(index, t);
				final boolean newBurst = seen == null || t - seen > 1;
				final List<Integer> due = pending.get(index);
				if (due != null)
				{
					due.removeIf(lands -> fromFlight ? Math.abs(lands - t) <= FLIGHT_SLACK : t - lands > FLIGHT_SLACK);
				}
				if (fromFlight || !newBurst)
				{
					continue;
				}
				if (meleeBookedTicks.contains(t))
				{
					book(PvmPerformancePlugin.attackerFor(index, t - 1, current, lastFinished), npcId, index, t - 1, t, false,
						expectedById);
					continue;
				}
				final int thrown = due != null && !due.isEmpty() ? Integer.MIN_VALUE
					: PvmPerformancePlugin.shotThrownOn(t, paidOn, attackDueTick);
				if (thrown != Integer.MIN_VALUE)
				{
					book(PvmPerformancePlugin.attackerFor(index, thrown, current, lastFinished), npcId, index, thrown, t, true,
						expectedById);
					attackDueTick = thrown + speed;
				}
			}
		}
	}

	private static void book(Fight fight, int npcId, int index, int thrown, int tick, boolean unseen,
		Map<Integer, Double> expected)
	{
		if (fight == null)
		{
			return;
		}
		fight.recordAttackMade(true);
		final Double average = expected.get(npcId);
		if (average != null && fight.getTargetId() == npcId)
		{
			fight.recordExpected(0, 0.9, average);
		}
		bookings.add(new Booking(thrown, tick, index, fight, unseen));
	}

	private static Booking bookedFor(int thrown)
	{
		for (Booking b : bookings)
		{
			if (b.thrown == thrown && b.bookedOn < thrown + 8)
			{
				return b;
			}
		}
		return null;
	}

	@Test
	public void theShotThrownOn68BooksToTheRespiratorySystemItWasAimedAt()
	{
		// Before: "ATTACK on Abyssal Sire (npc 5886) thrown tick 68" on 70, the Sire fight that very shot ended.
		final Booking b = bookedFor(68);
		assertNotNull(b);
		assertEquals(70, b.bookedOn);
		assertEquals(RESPIRATORY, b.fight.getTargetId());
		assertEquals(35728, b.fight.getTargetIndex());
		assertTrue(b.fight.getSumExpectedAverageHit() > 0);
	}

	@Test
	public void theShotsWhoseProjectilesWereNotTakenAsMineBookFromTheirDrops()
	{
		// 78's 34, 82's 29 and 100's 19: Ranged drops on 74, 78 and 96, no projectile of mine seen for any of them.
		final int[][] shots = {{74, 35730}, {78, 35729}, {96, 35730}};
		for (int[] shot : shots)
		{
			final Booking b = bookedFor(shot[0]);
			assertNotNull("the shot thrown on " + shot[0], b);
			assertTrue(b.unseen);
			assertEquals(shot[1], b.fight.getTargetIndex());
			assertTrue(b.fight.getSumExpectedAverageHit() > 0);
		}
	}

	@Test
	public void everyRespiratorySystemsDamageHasAttacksAndExpectedDamageBehindIt()
	{
		final List<String> without = new ArrayList<>();
		for (Fight f : kept)
		{
			if (f.getTargetId() == RESPIRATORY && f.getDamageDealt() > 0
				&& (f.getAttacksMade() == 0 || f.getSumExpectedAverageHit() <= 0))
			{
				without.add(f.getTargetIndex() + " " + f.getDamageDealt());
			}
		}
		assertEquals(new ArrayList<String>(), without);
		final List<String> lost = new ArrayList<>();
		for (String o : orphaned)
		{
			if (o.contains("Respiratory system"))
			{
				lost.add(o);
			}
		}
		assertEquals("respiratory damage in fights with nothing booked", new ArrayList<String>(), lost);
	}

	@Test
	public void everyAttackIsBookedIntoTheFightOnTheNpcItHit()
	{
		final List<String> elsewhere = new ArrayList<>();
		int toRespiratory = 0;
		for (Booking b : bookings)
		{
			if (b.fight.getTargetIndex() != b.aimedIndex)
			{
				elsewhere.add("thrown " + b.thrown + " at index " + b.aimedIndex + ", booked to " + b.fight.getTargetName()
					+ " index " + b.fight.getTargetIndex());
			}
			toRespiratory += b.fight.getTargetId() == RESPIRATORY ? 1 : 0;
		}
		assertEquals(new ArrayList<String>(), elsewhere);
		// Every one of the 21 respiratory fights that held damage with nothing booked before now has its attack.
		assertTrue("respiratory bookings: " + toRespiratory, toRespiratory >= 21);
	}
}
