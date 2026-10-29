package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * T-034's tests: the History tab's grouping on Eric's Sire log, its medians
 * against {@link LedgerHistory}, kills saved before T-028 in seconds, Clear
 * asking first, and the Trip tab's rooms.
 */
public class SidePanelHistoryTest
{
	static
	{
		System.setProperty("java.awt.headless", "true");
	}

	// ---- grouping on the Sire fixture ----

	private static final Pattern LINE = Pattern.compile("^\\[(\\d+)] (.*)$");
	private static final Pattern START = Pattern.compile(
		"^fight START (.+) \\(npc (\\d+), index (\\d+), maxHp (-?\\d+)\\)");
	private static final Pattern END = Pattern.compile(
		"^fight END (.+) \\(npc (\\d+)\\) (.+?): attempts=(\\d+) made=\\d+ damage=(\\d+)");
	private static final Pattern WEARS = Pattern.compile("\\(npc (\\d+), index (\\d+)\\)");
	private static final List<Integer> SIRE_IDS = Arrays.asList(5886, 5887, 5888, 5889, 5890, 5891, 5908);

	/**
	 * Eric's ten Sire kills (01:30 log) as saved fights, newest first: every fight
	 * the log opened and closed, the room closed on the Sire's death as its loot
	 * closes it in the client (as {@code SireReplayTest} has it).
	 */
	static List<Fight> sireHistory() throws Exception
	{
		final List<Fight> oldestFirst = new ArrayList<>();
		final java.util.Map<Integer, Integer> wears = new java.util.HashMap<>();
		try (BufferedReader in = new BufferedReader(new InputStreamReader(SidePanelHistoryTest.class.getResourceAsStream(
			"replay/sire-10-kills-20261027-0130.log"), StandardCharsets.UTF_8)))
		{
			Fight open = null;
			String raw;
			while ((raw = in.readLine()) != null)
			{
				final Matcher m = LINE.matcher(raw);
				if (!m.matches())
				{
					continue;
				}
				final long millis = Long.parseLong(m.group(1)) * 600L;
				final String text = m.group(2);
				final Matcher w = WEARS.matcher(text);
				while (w.find())
				{
					wears.put(Integer.parseInt(w.group(2)), Integer.parseInt(w.group(1)));
				}
				final Matcher start = START.matcher(text);
				if (start.find())
				{
					open = new Fight(start.group(1), Integer.parseInt(start.group(2)), Integer.parseInt(start.group(3)),
						Integer.parseInt(start.group(4)), millis, null, 0);
					continue;
				}
				final Matcher end = END.matcher(text);
				if (end.find() && open != null)
				{
					for (int i = Integer.parseInt(end.group(4)); i > 0; i--)
					{
						open.recordAttackMade(false);
					}
					open.recordDamageDealt(Integer.parseInt(end.group(5)), millis, false);
					final boolean died = "target died".equals(end.group(3));
					open.end(died, millis);
					final int diedAs = wears.getOrDefault(open.getTargetIndex(), open.getTargetId());
					if (died && SIRE_IDS.contains(diedAs))
					{
						open.closeRoom();
					}
					oldestFirst.add(open);
					open = null;
				}
			}
		}
		Collections.reverse(oldestFirst);
		return oldestFirst;
	}

	@Test
	public void theSiresAddsSitUnderTheSire() throws Exception
	{
		final List<Fight> history = sireHistory();
		// Every respiratory system, spawn and scion is in a Sire room, never one of its own.
		int adds = 0;
		for (Encounter room : SidePanelHistory.allRooms(history))
		{
			for (Fight f : room.getFights())
			{
				if (!f.getTargetName().equals("Abyssal Sire"))
				{
					assertEquals(f.getTargetName(), "Abyssal Sire", room.getName());
					adds++;
				}
			}
		}
		assertEquals(48 + 3 + 4, adds);

		final List<SidePanelHistory.BossEntry> bosses = SidePanelHistory.bosses(history, false, "");
		assertEquals(1, bosses.size());
		final SidePanelHistory.BossEntry sire = bosses.get(0);
		assertEquals("Abyssal Sire", sire.name);
		assertEquals(10, sire.kills);
		assertTrue(sire.boss);
		// The sub-line names the adds fought: the spawns were only looked at in this log (attempts=0).
		assertEquals(Arrays.asList("Respiratory system", "Scion"), sire.adds);
		assertEquals("with Respiratory system, Scion", sire.sub());
	}

	@Test
	public void bossesOnlyAndSearchFilterTheList()
	{
		final List<Fight> h = SidePanelHistoryExamples.history();
		final List<String> all = names(SidePanelHistory.bosses(h, false, ""));
		assertEquals(Arrays.asList("Vardorvis", "Abyssal Sire", "Abyssal demon"), all);
		assertEquals(Arrays.asList("Vardorvis", "Abyssal Sire"), names(SidePanelHistory.bosses(h, true, "")));
		assertEquals(Arrays.asList("Abyssal Sire", "Abyssal demon"), names(SidePanelHistory.bosses(h, false, "ABYSS")));
		final SidePanelHistory.BossEntry demon = SidePanelHistory.bosses(h, false, "demon").get(0);
		assertEquals("3 kills", demon.right());
		assertEquals("not a boss", demon.sub());
	}

	private static List<String> names(List<SidePanelHistory.BossEntry> entries)
	{
		final List<String> out = new ArrayList<>();
		for (SidePanelHistory.BossEntry e : entries)
		{
			out.add(e.name);
		}
		return out;
	}

	// ---- medians against LedgerHistory ----

	private static LedgerHistory.Like vardorvis(List<Fight> h)
	{
		return LedgerHistory.Like.of(SidePanelHistory.kills(h, "Vardorvis", SidePanelHistoryExamples.NOW,
			SidePanelExamples.ZONE).get(0).room);
	}

	@Test
	public void yourAverageIsLedgerHistorysOwn()
	{
		final List<Fight> h = SidePanelHistoryExamples.history();
		final LedgerHistory.Like like = vardorvis(h);
		final LedgerHistory.Average a = LedgerHistory.of(h, like, null);
		final SidePanelHistory.Usual u = SidePanelHistory.usual(h, like);
		assertNotNull(a);
		assertNotNull(u);
		assertEquals(a.kills, u.kills.size());
		assertEquals(a.actual.median, u.clock.median, 1e-9);
		assertEquals(a.actual.lowerQuartile, u.clock.lowerQuartile, 1e-9);
		assertEquals(a.actual.upperQuartile, u.clock.upperQuartile, 1e-9);
	}

	@Test
	public void luckRemovedIsTheMedianOfTStar()
	{
		final List<Fight> h = SidePanelHistoryExamples.history();
		final SidePanelHistory.Usual u = SidePanelHistory.usual(h, vardorvis(h));
		final double[] stars = new double[u.kills.size()];
		for (int i = 0; i < stars.length; i++)
		{
			stars[i] = new LedgerRows.Numbers(LedgerRows.of(u.kills.get(i))).expectedDuration;
		}
		Arrays.sort(stars);
		assertEquals(LedgerHistory.quantile(stars, 0.5), u.star.median, 1e-9);
		assertEquals(LedgerHistory.quantile(stars, 0.25), u.star.lowerQuartile, 1e-9);
		// The 26 Oct kill kept a boost share (T-010) but no spread (T-028): it counts, so all thirteen.
		assertEquals(13, u.kills.size());
	}

	@Test
	public void underFiveKillsTheTilesSaySo()
	{
		final List<Fight> h = new ArrayList<>(SidePanelHistoryExamples.history().subList(0, 3));
		final SidePanelHistory.Card card = SidePanelHistory.card(h, "Vardorvis", null, SidePanelHistory.Versions.NONE);
		assertNotNull(card);
		assertEquals(LedgerRows.UNKNOWN, card.tiles.get(0).value.text);
		assertEquals("from 5 kills (3 so far)", card.tiles.get(0).sub.text);
		assertEquals(SidePanelHistory.LUCK_REMOVED, card.tiles.get(1).edge);
		assertNotNull(card.spark);
	}

	@Test
	public void theVersionPickerNamesTheVersion()
	{
		final List<Fight> h = SidePanelHistoryExamples.history();
		final SidePanelHistory.Card named = SidePanelHistory.card(h, "Vardorvis", null,
			(room, hp) -> hp == 700 ? "Post-quest" : null);
		assertEquals("Post-quest · solo (13)", named.versions.get(0).label);
		final SidePanelHistory.Card plain = SidePanelHistory.card(h, "Vardorvis", null, SidePanelHistory.Versions.NONE);
		assertEquals("700 HP · solo (13)", plain.versions.get(0).label);
		assertEquals(13, plain.kills);
		assertEquals("1:58", plain.tiles.get(0).value.text);
		assertTrue(plain.tiles.get(0).sub.text, plain.tiles.get(0).sub.text.startsWith("middle half "));
	}

	@Test
	public void theVersionNameComesFromTheBossRules()
	{
		final BossRules rules = BossRules.load(new com.google.gson.Gson(), null);
		// Vardorvis's id in the bundled rules, at each of its versions' full HP.
		final List<Fight> fights = Collections.singletonList(new Fight("Vardorvis", 12223, 1, 700, 0L, null, 0));
		assertEquals("Post-quest", SidePanelModel.versionName(rules, fights, 700));
		assertEquals("Awakened", SidePanelModel.versionName(rules, fights, 1400));
		// An HP no version has, or a boss with no rule: nothing to name, so the HP stands in.
		assertNull(SidePanelModel.versionName(rules, fights, 900));
		assertNull(SidePanelModel.versionName(rules, Collections.singletonList(
			new Fight("Goblin", NpcIds.GOBLIN, 1, 5, 0L, null, 0)), 5));
	}

	// ---- the kill list ----

	@Test
	public void killsNewestFirstWithShowMore()
	{
		final List<SidePanelHistory.KillRow> rows = SidePanelHistory.kills(SidePanelHistoryExamples.history(), "Vardorvis",
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE);
		assertEquals(13, rows.size());
		for (int i = 1; i < rows.size(); i++)
		{
			assertTrue(SidePanelHistory.endMillis(rows.get(i - 1).room) >= SidePanelHistory.endMillis(rows.get(i).room));
		}
		// Today's kill is its time alone (T-072: the rows were full before am and pm).
		assertTrue(rows.get(0).date, rows.get(0).date.matches("\\d{1,2}:\\d{2} [ap]m"));
		assertEquals("26 Oct 9:40 pm", rows.get(12).date);
		assertEquals("Show 6 more", SidePanelHistory.showMore(13, SidePanelHistory.FIRST_KILLS));
		assertNull(SidePanelHistory.showMore(13, 13));
	}

	@Test
	public void killsSavedBeforeT028ShowSeconds()
	{
		final List<SidePanelHistory.KillRow> rows = SidePanelHistory.kills(SidePanelHistoryExamples.history(), "Vardorvis",
			SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE);
		// The 26 Oct kill kept no spread: its chip is the seconds, coloured by the seconds rule.
		final SidePanelHistory.KillRow old = rows.get(12);
		final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(old.room));
		assertTrue(Double.isNaN(n.percentile));
		assertEquals(LedgerRows.fasterOrSlower(n.luck), old.chip.text);
		assertTrue(old.chip.text, old.chip.text.endsWith("faster"));
		// Today's kept it: their chips are bands.
		final String band = rows.get(0).chip.text;
		assertTrue(band, band.startsWith("bottom") || band.startsWith("top") || band.equals("average"));
	}

	// ---- the saved kill view ----

	@Test
	public void aKillFromBeforeThisSessionGetsTheLaterCard()
	{
		final List<Fight> h = SidePanelHistoryExamples.history();
		final Encounter old = SidePanelHistory.kills(h, "Vardorvis", SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE)
			.get(12).room;
		final PanelSnapshot.FightTab before = SidePanelModel.fight(SidePanelHistory.savedKill(h, old,
			SidePanelExamples.at(11, 0), SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE, SidePanelHistory.Versions.NONE));
		assertEquals(SidePanelModel.LATER_TEXT, before.later);
		assertNull(before.damage);
		assertEquals("26 Oct 9:40 pm · saved kill", before.header.sub.text);
		assertEquals(" (dead)", before.header.suffix.text);
		// Before T-028: the seconds stand in for the curve, in the header too.
		assertNull(before.curve);
		assertNotNull(before.noCurve);
		assertEquals("6 s faster", before.noCurveHint.text);

		final Encounter today = SidePanelHistory.kills(h, "Vardorvis", SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE)
			.get(0).room;
		final PanelSnapshot.FightTab thisSession = SidePanelModel.fight(SidePanelHistory.savedKill(h, today,
			SidePanelExamples.at(11, 0), SidePanelHistoryExamples.NOW, SidePanelExamples.ZONE, SidePanelHistory.Versions.NONE));
		assertNull(thisSession.later);
		assertNotNull(thisSession.curve);
		assertNotNull(thisSession.budget);
	}

	@Test
	public void openAndBack()
	{
		final SidePanelHistoryTab.Source source = SidePanelPictures.source();
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source);
		final SidePanelHistoryTab tab = panel.historyTab();
		// Opens on the boss of the last kill.
		assertEquals("Vardorvis", tab.boss());
		assertFalse(tab.isShowingKill());
		final Fight first = SidePanelHistory.kills(source.history(), "Vardorvis", 0, SidePanelExamples.ZONE).get(0)
			.room.getFights().get(0);
		tab.open(first);
		assertTrue(tab.isShowingKill());
		tab.back();
		assertFalse(tab.isShowingKill());
	}

	// ---- Clear asks first ----

	@Test
	public void clearAsksFirst()
	{
		final int[] cleared = {0};
		final SidePanelFightTab.Actions actions = new SidePanelFightTab.Actions()
		{
			public void exportAll()
			{
			}

			public void newTrip()
			{
			}

			public void saveDebugLog()
			{
			}

			public void clearHistory()
			{
				cleared[0]++;
			}

			public void wholeRaid(boolean whole)
			{
			}

			public void showRaidRoom(int index)
			{
			}
		};
		final boolean[] answer = {false};
		final int[] asked = {0};
		final SidePanelFightTab.ToolButtons tools = new SidePanelFightTab.ToolButtons(actions, () ->
		{
			asked[0]++;
			return answer[0];
		});
		tools.setHistory(true);
		tools.pressClear();
		assertEquals(1, asked[0]);
		assertEquals("no: nothing cleared", 0, cleared[0]);
		answer[0] = true;
		tools.pressClear();
		assertEquals(2, asked[0]);
		assertEquals(1, cleared[0]);
	}

	// ---- the Trip tab ----

	@Test
	public void theTripListsItsRoomsWithTheRunningOneFirst()
	{
		final List<Fight> h = SidePanelHistoryExamples.history();
		final long since = SidePanelExamples.at(12, 0);
		final List<SidePanelHistory.KillRow> saved = SidePanelHistory.tripSaved(h, since, SidePanelHistoryExamples.NOW,
			SidePanelExamples.ZONE);
		// 12 Vardorvis kills, two Sire rooms and three demons since noon; not the 26 Oct kill.
		assertEquals(17, saved.size());
		// A room still being fought that shares a fight with a saved one takes its place.
		final Encounter sireSaved = saved.stream().filter(r -> r.name.equals("Abyssal Sire")).findFirst().get().room;
		final Encounter running = new Encounter("Abyssal Sire", null, sireSaved.getStartMillis());
		for (Fight f : sireSaved.getFights())
		{
			running.add(f);
		}
		final List<SidePanelHistory.KillRow> rows = SidePanelHistory.tripRooms(saved, running);
		assertEquals(17, rows.size());
		assertTrue(rows.get(0).room == running);
		assertFalse(rows.get(0).ended);
	}

	@Test
	public void theTripTabReadsAsTheCanvasHasIt()
	{
		final PanelSnapshot.TripTab t = SidePanelModel.trip(SidePanelExamples.trip());
		assertEquals("Trip", t.header.name.text);
		assertEquals("since 2:52 pm · fighting time", t.header.sub.text);
		assertEquals("12:40", t.header.clock.text);
		assertEquals("6 kills · 1 ended", t.roomsHint.text);
		assertEquals(4, t.tiles.size());
		// Two kinds of damage over time: the tile says DoT, the rows split it (T-063).
		assertEquals("+290 thrall, 60 DoT", t.tiles.get(1).sub.text);
		assertEquals(Arrays.asList("thrall dmg", "burn dmg", "venom dmg"), Arrays.asList(t.other.rows.get(0).left.text,
			t.other.rows.get(1).left.text, t.other.rows.get(2).left.text));
		// The trip's figures are the overlay's trip figures.
		final List<LedgerRows.Row> overlay = LedgerRows.build(SidePanelExamples.trip().scope,
			LedgerRows.State.trip(6, false, false), new LedgerRows.Settings());
		for (LedgerRows.Row r : overlay)
		{
			if (r.left.equals("Execution"))
			{
				assertEquals(r.right, t.execution.hint.text);
				assertEquals(r.right, t.tiles.get(3).value.text);
			}
			if (r.left.equals("DPS"))
			{
				assertEquals(r.right, t.tiles.get(2).value.text);
			}
		}
	}

	@Test
	public void anEmptyTripSaysSo()
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.kind = SidePanelModel.Kind.TRIP;
		in.scope = new LedgerExamples.FakeScope();
		final PanelSnapshot.TripTab t = SidePanelModel.trip(in);
		assertNull(t.tiles);
		assertTrue(t.rooms.isEmpty());
		assertEquals("0:00", t.header.clock.text);
		assertEquals(SidePanelStyle.UNSCORED, t.header.clock.colour);
	}

	// ---- History is built on refresh, never per tick (spec 5.1 step 3) ----

	@Test
	public void historyIsntBuiltPerTick()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, SidePanelPictures.source());
		final int builds = panel.historyTab().builds();
		for (int i = 0; i < 20; i++)
		{
			final SidePanelModel.Input live = SidePanelExamples.live();
			((LedgerExamples.FakeScope) live.scope).combatTicks += i;
			panel.apply(new PanelSnapshot(SidePanelModel.fight(live), SidePanelModel.trip(SidePanelExamples.trip())));
		}
		assertEquals(builds, panel.historyTab().builds());
		assertEquals(1, panel.tripTab().builds());
	}
}
