package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.Hitsplat;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Eric's ten Abyssal Sire kills of 2026-10-27 (debug stream from 01:30, cut after
 * the tenth kill), recorded on a build from before T-020: every fight in its own
 * room, every attack scored as the id its fight opened on, the count of what the
 * Sire had left run out with up to 27 still to deal.
 *
 * <p>Replayed through the pieces T-020 changed, as the other replay tests do: the
 * fights the log opened and closed through the plugin's own room rule and the real
 * group tables; the Sire's ids, as its splats name them, through
 * {@link TargetIdHistory} and the monster data; every splat on it through
 * {@link TargetHealth} with the boss rules. The plugin itself needs a client.
 *
 * <p>What the log cannot say, and so is assumed here: that the Sire's death is
 * followed by its loot. RuneLite's LootManager (1.13.4) posts {@code NpcLootReceived}
 * for a dead npc that leaves items in its own area, which is the Sire's case; the
 * respiratory systems, spawns and scions leave nothing. This build logs the room
 * closing on it as a LOOT line, so the next Sire log can confirm it.
 */
public class SireReplayTest
{
	private static final String LOG = "replay/sire-10-kills-20261027-0130.log";
	private static final int SIRE = 24468;
	private static final int FULL_HP = 425;
	private static final int RESPIRATORY = 5914;
	private static final List<Integer> SIRE_IDS = Arrays.asList(5886, 5887, 5888, 5889, 5890, 5891, 5908);
	private static final List<Integer> PHASE_ONE = Arrays.asList(5886, 5887, 5888);

	private static final Pattern LINE = Pattern.compile("^\\[(\\d+)] (.*)$");
	private static final Pattern START = Pattern.compile(
		"^fight START (.+) \\(npc (\\d+), index (\\d+), maxHp (-?\\d+)\\)");
	private static final Pattern END = Pattern.compile(
		"^fight END (.+) \\(npc (\\d+)\\) (.+?): attempts=(\\d+) made=\\d+ damage=(\\d+) thrallDamage=(\\d+)");
	private static final Pattern ATTACK = Pattern.compile("^ATTACK on .+ \\(npc (\\d+)\\) thrown tick (\\d+)");
	// The player's splats, as the isMine() branch logs them; the thrall's, as its gate logs them; everything else.
	private static final Pattern MINE = Pattern.compile("^hitsplat (\\d+) type (\\d+) on .*\\(npc (\\d+), index (\\d+)\\)");
	private static final Pattern THRALL = Pattern.compile("^hitsplat (\\d+) on .*\\(index (\\d+)\\): THRALL");
	private static final Pattern OTHER = Pattern.compile(
		"^splat (\\d+) .*\\(type (\\d+), (others'|no owner)\\) on .*\\(npc (\\d+), index (\\d+)\\)");
	private static final Pattern DROP = Pattern.compile("^xp drop (\\w+) \\+(\\d+)");
	private static final Pattern AWARD = Pattern.compile("^xp award tick (\\d+): .* against npc (-?\\d+)");
	// Any line naming the id an npc wears on this tick.
	private static final Pattern WEARS = Pattern.compile("\\(npc (\\d+), index (\\d+)\\)");

	private static final class Line
	{
		final int number;
		final int tick;
		final String text;

		Line(int number, int tick, String text)
		{
			this.number = number;
			this.tick = tick;
			this.text = text;
		}
	}

	private static List<Line> lines;

	@BeforeClass
	public static void read() throws Exception
	{
		lines = new ArrayList<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			SireReplayTest.class.getResourceAsStream(LOG), StandardCharsets.UTF_8)))
		{
			String raw;
			int number = 0;
			while ((raw = in.readLine()) != null)
			{
				number++;
				final Matcher m = LINE.matcher(raw);
				if (m.matches())
				{
					lines.add(new Line(number, Integer.parseInt(m.group(1)), m.group(2)));
				}
			}
		}
	}

	private static long millis(int tick)
	{
		return tick * 600L;
	}

	// ---- rooms -------------------------------------------------------------------------------------------------------

	/** A room as the replay saw it: the plugin's Encounter, and the tick its loot closed it on. */
	private static final class Room
	{
		final Encounter encounter;
		final int startTick;
		int closedTick = -1;
		final Map<Fight, Integer> fightStart = new HashMap<>();

		Room(Encounter encounter, int startTick)
		{
			this.encounter = encounter;
			this.startTick = startTick;
		}
	}

	/** Every room the fights in the log make under the plugin's rule, look-only ones included. */
	private static List<Room> rooms()
	{
		final List<Room> rooms = new ArrayList<>();
		final Map<Integer, Integer> wears = new HashMap<>();
		Room room = null;
		Fight open = null;
		for (Line line : lines)
		{
			final Matcher w = WEARS.matcher(line.text);
			while (w.find())
			{
				wears.put(Integer.parseInt(w.group(2)), Integer.parseInt(w.group(1)));
			}
			final Matcher start = START.matcher(line.text);
			if (start.find())
			{
				assertNull("line " + line.number + ": a fight opened while another was open", open);
				open = new Fight(start.group(1), Integer.parseInt(start.group(2)), Integer.parseInt(start.group(3)),
					Integer.parseInt(start.group(4)), millis(line.tick), null, 0);
				open.noteStartTick(line.tick);
				if (PvmPerformancePlugin.opensRoom(room == null ? null : room.encounter, open))
				{
					if (room != null && !room.encounter.isEnded())
					{
						room.encounter.end(millis(line.tick));
					}
					room = new Room(new Encounter(open.encounterName(), null, millis(line.tick)), line.tick);
					rooms.add(room);
				}
				room.encounter.add(open);
				room.fightStart.put(open, line.tick);
				continue;
			}
			final Matcher end = END.matcher(line.text);
			if (end.find())
			{
				assertNotNull("line " + line.number + ": a fight closed that never opened", open);
				assertEquals("line " + line.number, open.getTargetId(), Integer.parseInt(end.group(2)));
				for (int i = Integer.parseInt(end.group(4)); i > 0; i--)
				{
					open.recordAttackMade(false);
				}
				open.recordDamageDealt(Integer.parseInt(end.group(5)), millis(line.tick), false);
				final boolean died = "target died".equals(end.group(3));
				open.end(died, millis(line.tick));
				// The loot: only the Sire leaves any, and it is the id the Sire wears when it dies that the event carries.
				final int diedAs = wears.getOrDefault(open.getTargetIndex(), open.getTargetId());
				if (died && SIRE_IDS.contains(diedAs) && !room.encounter.isEnded() && room.encounter.holds(diedAs))
				{
					room.encounter.end(millis(line.tick));
					room.closedTick = line.tick;
				}
				open = null;
			}
		}
		return rooms;
	}

	private static List<Room> killRooms()
	{
		return killRooms(rooms());
	}

	private static List<Room> killRooms(List<Room> all)
	{
		final List<Room> kept = new ArrayList<>();
		for (Room r : all)
		{
			if (r.encounter.hasAttempts())
			{
				kept.add(r);
			}
		}
		return kept;
	}

	@Test
	public void theFirstKillIsOneRoomFrom46To232()
	{
		final Room first = killRooms().get(0);
		assertEquals("Abyssal Sire", first.encounter.getName());
		assertEquals(46, first.startTick);
		assertEquals(232, first.closedTick);
		assertTrue(first.encounter.isKilled());
		// The Sire in phase one, the nine respiratory systems, and the Sire from its last moments of phase one to its
		// death: the whole kill, where the build that recorded it made eleven rooms of it.
		final List<Fight> fights = first.encounter.getFights();
		assertEquals(11, fights.size());
		assertEquals(5886, fights.get(0).getTargetId());
		assertEquals(5888, fights.get(10).getTargetId());
		for (Fight f : fights.subList(1, 10))
		{
			assertEquals(RESPIRATORY, f.getTargetId());
		}
		for (Fight f : fights)
		{
			final int at = first.fightStart.get(f);
			assertTrue("fight at " + at, at >= 46 && at <= 232);
		}
	}

	@Test
	public void tenKillsAreTenRoomsEachClosedByTheSiresDeath()
	{
		final List<Room> all = rooms();
		final List<Room> kills = killRooms(all);
		final int[] deaths = new int[kills.size()];
		for (int i = 0; i < kills.size(); i++)
		{
			final Room r = kills.get(i);
			assertEquals("room " + (i + 1), "Abyssal Sire", r.encounter.getName());
			assertTrue("room " + (i + 1), r.encounter.isKilled());
			deaths[i] = r.closedTick;
			// Closed on the death of the Sire, which is the room's last fight.
			final List<Fight> fights = r.encounter.getFights();
			final Fight last = fights.get(fights.size() - 1);
			assertTrue("room " + (i + 1), SIRE_IDS.contains(last.getTargetId()));
			assertTrue("room " + (i + 1), last.isTargetDied());
		}
		assertEquals("[232, 4896, 5173, 5355, 5543, 6596, 6751, 6935, 7132, 7260]", Arrays.toString(deaths));
		// Nothing outside them: no respiratory system, spawn or scion makes a room of its own, and no room opened by a look
		// is kept.
		int looks = 0;
		for (Room r : all)
		{
			if (!kills.contains(r))
			{
				looks++;
				assertEquals("a look-only room at " + r.startTick, 1, r.encounter.getFights().size());
				assertEquals(0, r.encounter.getFights().get(0).getAttempts());
			}
		}
		assertTrue(looks > 0);
	}

	/** Kill 2 began with a glance at the Sire at 4191; its room starts at the first fight anything was thrown in. */
	@Test
	public void aLookDoesNotStartTheNextKill()
	{
		final Room second = killRooms().get(1);
		assertEquals(4545, second.startTick);
		assertEquals(4896, second.closedTick);
	}

	@Test
	public void theRespiratorySystemsAreInTheRoomButNotItsScore()
	{
		final Room first = killRooms().get(0);
		int respiratory = 0;
		int scored = 0;
		for (Fight f : first.encounter.getFights())
		{
			if (f.getTargetId() == RESPIRATORY)
			{
				assertFalse(f.isScored());
				respiratory += f.getDamageDealt();
			}
			else
			{
				assertTrue(f.isScored());
				scored += f.getDamageDealt();
			}
		}
		// 40 + 0 + 34 + 29 + 50 + 23 + 0 + 19 + 14 on the nine fights the log closed.
		assertEquals(209, respiratory);
		// The room's damage is the Sire's: 100 in phase one, 444 after (and 8 burn the weapon figure leaves out).
		assertEquals(544, scored);
		assertEquals(544, first.encounter.getDamageDealt());
		// Every kill's respiratory systems are in its room.
		int fights = 0;
		for (Room r : killRooms())
		{
			for (Fight f : r.encounter.getFights())
			{
				fights += f.getTargetId() == RESPIRATORY ? 1 : 0;
			}
		}
		int logged = 0;
		for (Line line : lines)
		{
			final Matcher m = START.matcher(line.text);
			logged += m.find() && Integer.parseInt(m.group(2)) == RESPIRATORY ? 1 : 0;
		}
		assertEquals(logged, fights);
	}

	// ---- the id an attack is scored against --------------------------------------------------------------------------

	private static MonsterStatsProvider provider() throws Exception
	{
		return SirePhaseStatsTest.provider();
	}

	@Test
	public void attacksOnTheLastStageAreScoredAgainstItsDefences() throws Exception
	{
		final MonsterStatsProvider stats = provider();
		final TargetIdHistory ids = new TargetIdHistory();
		int wears = -1;
		int firstStageTwo = -1;
		int lastStageOne = -1;
		final List<int[]> attacks = new ArrayList<>(); // {thrown tick, the fight's id, scored as}
		for (Line line : lines)
		{
			if (line.tick > 232)
			{
				break;
			}
			final Matcher w = WEARS.matcher(line.text);
			while (w.find())
			{
				if (Integer.parseInt(w.group(2)) == SIRE && !line.text.startsWith("ATTACK"))
				{
					wears = Integer.parseInt(w.group(1));
					firstStageTwo = wears == 5908 && firstStageTwo < 0 ? line.tick : firstStageTwo;
					lastStageOne = wears == 5891 ? line.tick : lastStageOne;
				}
			}
			// What the plugin records each tick: the target's index and the id it wears. Seen here only on the ticks something
			// names it, so it changes no earlier than the log shows.
			if (wears > 0)
			{
				ids.record(line.tick, SIRE, wears);
			}
			final Matcher a = ATTACK.matcher(line.text);
			if (a.find())
			{
				final int fightId = Integer.parseInt(a.group(1));
				final int thrown = Integer.parseInt(a.group(2));
				if (SIRE_IDS.contains(fightId))
				{
					attacks.add(new int[]{thrown, fightId, ids.idOn(thrown, SIRE, fightId)});
				}
			}
		}
		assertEquals(191, firstStageTwo);
		int stageTwo = 0;
		for (int[] attack : attacks)
		{
			final MonsterStatsProvider.MonsterStats as = stats.get(attack[2]);
			if (attack[0] >= firstStageTwo)
			{
				stageTwo++;
				assertEquals("thrown " + attack[0], 5908, attack[2]);
				assertEquals("Phase 3 (stage 2)", as.getVersion());
				assertEquals(30, as.getDefSlash());
				assertEquals(20, as.getDefStab());
				assertEquals(25, as.getDefCrush());
				assertEquals(-40, as.getDefMagic());
				// The build that recorded it scored them as the fight's id, phase one, at twice the defences.
				assertEquals(5888, attack[1]);
				assertEquals(60, stats.get(attack[1]).getDefSlash());
			}
			else if (attack[0] <= lastStageOne && attack[0] >= 162)
			{
				assertEquals("thrown " + attack[0], 5891, attack[2]);
				assertEquals(60, as.getDefSlash());
			}
		}
		// The eight thrown from its first splat as stage two (191) to its death. The one thrown on 190 landed its 0 on stage
		// two at 191, but the change came somewhere after its last splat as stage one at 182, and this log cannot say on
		// which tick: the plugin records the id on every tick, so a log from this build can.
		assertEquals(8, stageTwo);
	}

	// ---- what it has left --------------------------------------------------------------------------------------------

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

	/** Each attack's damage on the Sire, against what the cap would have allowed it the tick it landed. */
	private static final class Capped
	{
		final List<String> cutShort = new ArrayList<>();
		final List<Integer> afterPhaseOne = new ArrayList<>();
		int attacks;
	}

	/**
	 * Every splat on every npc through the count, as the plugin keeps it: one count per
	 * index, the target's full hitpoints from the tables when its fight opens, and all
	 * of it dropped when a fight closes. Before each tick's own damage lands on the
	 * Sire, the cap the attack was judged against is compared with what it dealt.
	 */
	private static Capped replayHealth(boolean withRules)
	{
		return replayHealth(withRules, false);
	}

	/**
	 * @param judgedAfterCounting read the cap after the tick's first splat of the
	 *                            player's is counted, as the plugin did before T-020
	 */
	private static Capped replayHealth(boolean withRules, boolean judgedAfterCounting)
	{
		final TargetHealth health = new TargetHealth();
		if (withRules)
		{
			health.setRules(BossRules.load(new Gson(), null));
		}
		final Capped out = new Capped();
		final Map<Integer, Integer> wears = new HashMap<>();
		int target = -1;
		int tick = -1;
		int mineThisTick = 0;
		int capThisTick = -1;
		int postPhaseOne = 0;
		for (Line line : lines)
		{
			if (line.tick != tick)
			{
				if (mineThisTick > 0)
				{
					out.attacks++;
					if (capThisTick >= 0 && capThisTick < mineThisTick)
					{
						out.cutShort.add("tick " + tick + ": " + mineThisTick + " dealt, cap " + capThisTick);
					}
				}
				tick = line.tick;
				mineThisTick = 0;
				capThisTick = -1;
			}
			final Matcher start = START.matcher(line.text);
			if (start.find())
			{
				target = Integer.parseInt(start.group(3));
				health.setTableMaxHp(target, Integer.parseInt(start.group(2)), Integer.parseInt(start.group(4)), tick);
				continue;
			}
			final Matcher end = END.matcher(line.text);
			if (end.find())
			{
				if (target == SIRE && "target died".equals(end.group(3)))
				{
					out.afterPhaseOne.add(postPhaseOne);
					postPhaseOne = 0;
				}
				health.clear();
				target = -1;
				continue;
			}
			int index = -1;
			int npcId = -1;
			int amount = 0;
			boolean mine = false;
			HitsplatKind kind = null;
			final Matcher m = MINE.matcher(line.text);
			final Matcher t = THRALL.matcher(line.text);
			final Matcher o = OTHER.matcher(line.text);
			if (m.find())
			{
				amount = Integer.parseInt(m.group(1));
				final Hitsplat h = splat(Integer.parseInt(m.group(2)), amount);
				kind = HitsplatKind.of(h.isMine(), h.isOthers(), h.getHitsplatType());
				npcId = Integer.parseInt(m.group(3));
				index = Integer.parseInt(m.group(4));
				mine = true;
			}
			else if (t.find())
			{
				amount = Integer.parseInt(t.group(1));
				index = Integer.parseInt(t.group(2));
				npcId = wears.getOrDefault(index, -1);
				kind = HitsplatKind.PLAYER;
			}
			else if (o.find())
			{
				amount = Integer.parseInt(o.group(1));
				kind = HitsplatKind.of(false, "others'".equals(o.group(3)), Integer.parseInt(o.group(2)));
				npcId = Integer.parseInt(o.group(4));
				index = Integer.parseInt(o.group(5));
			}
			if (kind == null || npcId < 0)
			{
				continue;
			}
			wears.put(index, npcId);
			if (index == SIRE && kind.damagesTarget() && !PHASE_ONE.contains(npcId))
			{
				postPhaseOne += amount;
			}
			final boolean judged = mine && index == SIRE && index == target && kind.damagesTarget() && amount > 0;
			if (judged)
			{
				if (capThisTick < 0 && !judgedAfterCounting)
				{
					// The plugin judges the attack a melee splat books before counting the splat (and a projectile's on the tick
					// it left, before it landed): the cap is what the count says before this tick's own damage. A change of id is
					// only seen here on the splat that carries it, which counts it after the cap is read - the later of the two
					// orders the game could have, and the one that restores latest.
					capThisTick = health.capRemaining(index, tick);
				}
				mineThisTick += amount;
			}
			if (kind == HitsplatKind.HEAL)
			{
				health.heal(index, npcId, amount, tick);
			}
			else if (kind.damagesTarget())
			{
				health.damage(index, npcId, amount, tick);
			}
			if (judged && capThisTick < 0 && judgedAfterCounting)
			{
				capThisTick = health.capRemaining(index, tick);
			}
		}
		return out;
	}

	@Test
	public void noAttackIsCappedBelowWhatItDealt()
	{
		final Capped capped = replayHealth(true);
		assertTrue(capped.attacks > 150);
		assertEquals(capped.cutShort.toString(), 0, capped.cutShort.size());
	}

	/** Without the rule the count runs out before the Sire does, and the last attacks are cut to what it says is left. */
	@Test
	public void withoutTheRuleTheCountCutsTheLastAttacksShort()
	{
		final Capped capped = replayHealth(false);
		assertFalse(capped.cutShort.isEmpty());
		// Kill one: 2 left by the count on tick 223 with 23 and 5 still to land.
		assertTrue(capped.cutShort.toString(), capped.cutShort.toString().contains("tick 225: 23 dealt, cap 2"));
	}

	/**
	 * The other half of it: judged after its own splat was counted, a blow is capped
	 * by what is left after it. Kill one's 39 on tick 221 found 42 left and was judged
	 * against 3, an expected 2.16 in the log, and the margin alone cannot cover that.
	 */
	@Test
	public void judgedAfterItsOwnSplatABlowIsCappedByWhatItLeft()
	{
		final String after = replayHealth(true, true).cutShort.toString();
		assertTrue(after, after.contains("tick 221: 39 dealt, cap 33"));
	}

	/**
	 * What reconciles, kill by kill: everything the Sire took after phase one is its
	 * 425 and at most the rule's margin over (wiki: phase one heals back). Kills 5, 9
	 * and 10 come to exactly 425. The 0 to 27 over in the others is not explained
	 * (T-020 status doc), and the margin is set just above it.
	 */
	@Test
	public void afterPhaseOneEveryKillTakesItsFullHitpointsAndAtMostTheMargin()
	{
		final List<Integer> after = replayHealth(true).afterPhaseOne;
		assertEquals("[452, 428, 452, 427, 425, 432, 428, 427, 425, 425]", after.toString());
		final int margin = BossRules.load(new Gson(), null).forNpc(5889).countedHpMargin;
		for (int taken : after)
		{
			assertTrue(String.valueOf(taken), taken >= FULL_HP && taken <= FULL_HP + margin);
		}
	}

	// ---- what it pays ------------------------------------------------------------------------------------------------

	/**
	 * Stage two pays the x1.125 every other phase does, not the x1.100 the formula
	 * gives on its halved defences. Every melee drop against it that moved attack,
	 * strength, defence and hitpoints and nothing else is decoded against the
	 * player's own splats on the Sire in the ticks after it (to the next drop, three
	 * at most, which takes in a claw special's second tick).
	 */
	@Test
	public void stageTwoPaysTheRulesBonusNotTheFormulas() throws Exception
	{
		final double rule = provider().get(5908).getXpMultiplier();
		assertEquals(1.125, rule, 1e-9);
		final double formula = XpDecoder.bonusMultiplier(180, 136, 250, 425, 20, 30, 25, 65, 0);
		assertEquals(1.100, formula, 1e-9);

		final Map<Integer, Map<String, Integer>> drops = new HashMap<>();
		final Map<Integer, Integer> mine = new HashMap<>();
		final List<int[]> awards = new ArrayList<>(); // {tick, npc}
		for (Line line : lines)
		{
			final Matcher d = DROP.matcher(line.text);
			if (d.find())
			{
				drops.computeIfAbsent(line.tick, t -> new HashMap<>()).put(d.group(1), Integer.parseInt(d.group(2)));
			}
			final Matcher a = AWARD.matcher(line.text);
			if (a.find())
			{
				awards.add(new int[]{Integer.parseInt(a.group(1)), Integer.parseInt(a.group(2))});
			}
			final Matcher m = MINE.matcher(line.text);
			if (m.find() && Integer.parseInt(m.group(4)) == SIRE)
			{
				mine.merge(line.tick, Integer.parseInt(m.group(1)), Integer::sum);
			}
		}
		int clean = 0;
		int fitsFormula = 0;
		final List<String> misfits = new ArrayList<>();
		for (int i = 0; i < awards.size(); i++)
		{
			final int tick = awards.get(i)[0];
			final Map<String, Integer> g = drops.getOrDefault(tick, new HashMap<>());
			if (awards.get(i)[1] != 5908 || g.size() != 4 || !g.keySet().containsAll(
				Arrays.asList("ATTACK", "STRENGTH", "DEFENCE", "HITPOINTS")))
			{
				continue;
			}
			clean++;
			final int next = i + 1 < awards.size() ? awards.get(i + 1)[0] : Integer.MAX_VALUE;
			int landed = 0;
			for (int t = tick + 1; t <= Math.min(next, tick + 3); t++)
			{
				landed += mine.getOrDefault(t, 0);
			}
			final int[] atRule = XpDecoder.damageRange(g.get("ATTACK"), g.get("STRENGTH"), g.get("DEFENCE"), 0, 0,
				g.get("HITPOINTS"), rule);
			if (atRule == null || landed < atRule[0] || landed > atRule[1])
			{
				misfits.add("tick " + tick + ": " + landed + " landed, decodes " + Arrays.toString(atRule));
			}
			final int[] atFormula = XpDecoder.damageRange(g.get("ATTACK"), g.get("STRENGTH"), g.get("DEFENCE"), 0, 0,
				g.get("HITPOINTS"), formula);
			fitsFormula += atFormula != null && landed >= atFormula[0] && landed <= atFormula[1] ? 1 : 0;
		}
		assertEquals(42, clean);
		assertEquals(misfits.toString(), 0, misfits.size());
		// The formula's bonus fits only the small hits, where the two overlap.
		assertEquals(17, fitsFormula);
	}
}
