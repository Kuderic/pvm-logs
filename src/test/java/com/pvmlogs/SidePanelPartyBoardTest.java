package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.After;
import org.junit.Test;

/**
 * T-216 (Eric, 2026-11-11 17:27: "1 member should show the member's discord picture still when they're the only member in
 * party"; "The three numbers for dmg, luck, skill during the fight have no header"; "Post fight clicking them shouldn't
 * just change the sort, it should change what's displayed in the graphs"; "Do you think dps makes more sense than dmg
 * during a live fight?"; 17:28: "Party dps timeline should display the other 1 member even if they don't have luck vs
 * skill"). Invented names and pictures.
 */
public class SidePanelPartyBoardTest
{
	static final long ASH = 77;
	static final long OLD_TAM = 23;

	@After
	public void plainText()
	{
		SidePanelStyle.setText(false, false);
	}

	// ---- examples ----

	/** Luck and Skill so far at each of a member's points, drifting from a start to the end figures. */
	static void metrics(SidePanelParty.Member m, long seed, double luckEnd, double skillEnd)
	{
		final Random r = new Random(seed);
		final int n = m.ticks.length;
		m.luck = new float[n];
		m.skill = new float[n];
		double luck = 0.5;
		double skill = Math.min(1, skillEnd + 0.03);
		for (int i = 0; i < n; i++)
		{
			final double t = n <= 1 ? 1 : (double) i / (n - 1);
			luck = Math.max(0.01, Math.min(0.99, luck + (r.nextDouble() - 0.5) * 0.12 + (luckEnd - luck) * t * 0.35));
			skill = Math.max(0.5, Math.min(1, skill + (r.nextDouble() - 0.55) * 0.012 + (skillEnd - skill) * t * 0.3));
			// The first point or two before luck has a spread: not known yet.
			m.luck[i] = i < 2 ? Float.NaN : (float) (i == n - 1 ? luckEnd : luck);
			m.skill[i] = i < 1 ? Float.NaN : (float) (i == n - 1 ? skillEnd : skill);
		}
	}

	/**
	 * The other players' splats tick by tick, as the client sees them: the sharing members' (their lines' steps) and
	 * {@code own} more by the members not running the plugin, an attack every 4 or 5 ticks.
	 */
	static void splats(SidePanelParty.Input in, int ticks, int own, long seed)
	{
		final Random r = new Random(seed);
		final double[] theirs = new double[ticks + 1];
		double sum = 0;
		for (int t = 1; t <= ticks; t++)
		{
			sum += t % (4 + (int) (seed % 2)) == 0 ? 0.4 + r.nextDouble() * 1.6 : 0;
			theirs[t] = sum;
		}
		final List<int[]> points = new ArrayList<>();
		int last = 0;
		for (int t = 1; t <= ticks; t++)
		{
			double members = 0;
			for (SidePanelParty.Member m : in.members)
			{
				if (!m.you)
				{
					members += SidePanelParty.sharedAt(m, t);
				}
			}
			final int total = (int) Math.round(members + (sum == 0 ? 0 : theirs[t] / sum * own));
			if (total > last)
			{
				points.add(new int[]{t, total});
				last = total;
			}
		}
		in.othersTicks = new int[points.size()];
		in.othersTotals = new int[points.size()];
		for (int i = 0; i < points.size(); i++)
		{
			in.othersTicks[i] = points.get(i)[0];
			in.othersTotals[i] = points.get(i)[1];
		}
		in.othersDamage = last;
	}

	/** Eric's duo Yama, his partner without the plugin, live at 1:36 or after the kill: their picture and name. */
	static SidePanelParty.Input duo(boolean live)
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.yamaDuoNotSharing();
		final SidePanelParty.Member you = in.members.get(0);
		final int ticks = live ? 160 : 388;
		if (live)
		{
			in.live = true;
			in.seconds = 96;
			you.figures.k = LuckVsSkillUpdate.LIVE;
			you.figures.d = 4_610;
			you.figures.e = 4_520;
			you.figures.ct = ticks;
			you.figures.st = ticks;
			you.figures.p = 0.44;
			you.figures.x = 0.95;
			final int[][] s = SidePanelPartyExamples.series(you.figures.d, ticks, 4, 1);
			you.ticks = s[0];
			you.damage = s[1];
			in.members.get(0).fight = null;
		}
		metrics(you, 3, live ? 0.44 : 0.62, live ? 0.95 : 0.92);
		splats(in, ticks, live ? 4_020 : 9_870, 6);
		return in;
	}

	/**
	 * A four-member ToA room (invented names): you, Moss Pike and Brindle sharing, Old Tam in it without the plugin,
	 * his picture on; live at 0:54 or after the room.
	 */
	static SidePanelParty.Input four(boolean live)
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.toaOneNotSharing();
		in.unshared.set(0, new SidePanelParty.Unshared(OLD_TAM, "Old Tam", SidePanelPartyExamples.picture(9, 'O')));
		in.members.get(1).avatar = SidePanelPartyExamples.picture(4, 'M');
		final int ticks = live ? 90 : 215;
		final double[][] ends = {{0.5, 0.91}, {0.88, 0.96}, {0.09, 0.84}};
		for (int i = 0; i < in.members.size(); i++)
		{
			final SidePanelParty.Member m = in.members.get(i);
			if (live)
			{
				m.figures.k = LuckVsSkillUpdate.LIVE;
				m.figures.d = (int) Math.round(m.figures.d * 90 / 215.0);
				m.figures.e = m.figures.e * 90 / 215.0;
				m.figures.ct = ticks;
				m.figures.st = ticks;
				final int[][] s = SidePanelPartyExamples.series(m.figures.d, ticks, 4 + i % 2, m.id);
				m.ticks = s[0];
				m.damage = s[1];
				m.figures.p = ends[i][0] * 0.6 + 0.2;
			}
			metrics(m, 10 + i, live ? ends[i][0] * 0.6 + 0.2 : ends[i][0], ends[i][1]);
		}
		if (live)
		{
			in.live = true;
			in.seconds = 54;
		}
		splats(in, ticks, live ? 780 : 1_830, 11);
		return in;
	}

	private static SidePanelPartyMeter meter(PanelSnapshot.PartyTab t)
	{
		final SidePanelPartyMeter m = new SidePanelPartyMeter(id -> { });
		m.set(t.rows);
		m.setSize(m.getPreferredSize());
		final BufferedImage img = new BufferedImage(m.getWidth(), m.getHeight(), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		m.paint(g);
		g.dispose();
		return m;
	}

	private static SidePanelPartyChart chart(PanelSnapshot.PartyTab t, SidePanelParty.Metric metric)
	{
		final SidePanelPartyChart c = new SidePanelPartyChart();
		c.set(t, SidePanelPartySection.NONE, metric);
		c.setSize(c.getPreferredSize());
		final BufferedImage img = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		c.paint(g);
		g.dispose();
		return c;
	}

	// ---- 1. one member keeps their picture ----

	@Test
	public void aLoneMemberNotRunningThePluginKeepsTheirPictureAndName()
	{
		for (boolean live : new boolean[]{false, true})
		{
			final SidePanelParty.Input in = duo(live);
			final PanelSnapshot.PartyTab t = SidePanelParty.party(in);
			final PanelSnapshot.PartyRow ash = t.rows.get(1);
			assertEquals("Ash Kettle", ash.name);
			assertEquals(ASH, ash.id);
			assertNotNull(ash.avatar);
			assertTrue(ash.estimate);
			assertTrue(ash.person());
			// Their own colour, not the others' grey.
			assertFalse(SidePanelParty.OTHERS.equals(ash.colour));
			final SidePanelPartyMeter m = meter(t);
			assertEquals(0, m.cutNames());
			// "~" before the estimate: damage after, DPS live.
			assertTrue(m.lastFirst(ASH), m.lastFirst(ASH).startsWith(SidePanelParty.APPROX));
			// Its hover: both figures, that it's an estimate and why.
			assertTrue(ash.tip, ash.tip.contains(" damage, ~") && ash.tip.contains(" dps."));
			assertTrue(ash.tip, ash.tip.contains("estimated"));
			assertTrue(ash.tip, ash.tip.contains("No luck or skill"));
		}
		// Two or more stay "N members", the names on hover.
		final SidePanelParty.Input two = duo(false);
		two.unshared.add(new SidePanelParty.Unshared(79, "Fen Wright", null));
		final PanelSnapshot.PartyRow group = SidePanelParty.party(two).rows.get(1);
		assertEquals("2 members", group.name);
		assertNull(group.avatar);
		assertTrue(group.tip.contains("Ash Kettle and Fen Wright"));
	}

	// ---- 2 and 3. the header; DPS live, damage after ----

	@Test
	public void theHeaderReadsDpsLiveAndDmgAfter()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			SidePanelStyle.setText(larger, false);
			final SidePanelPartyMeter live = meter(SidePanelParty.party(four(true)));
			assertEquals("[dps, luck, skill]", Arrays.toString(live.headerWords()));
			final SidePanelPartyMeter after = meter(SidePanelParty.party(four(false)));
			assertEquals("[dmg, luck, skill]", Arrays.toString(after.headerWords()));
			// Each word over its column, left to right; the picked word (damage) the first.
			final List<java.awt.Rectangle> boxes = after.headerBoxes();
			assertEquals(3, boxes.size());
			assertTrue(boxes.get(0).x < boxes.get(1).x && boxes.get(1).x < boxes.get(2).x);
			assertTrue(boxes.get(2).x + boxes.get(2).width <= after.getWidth());
			// The rows start under it.
			assertEquals(SidePanelPartyMeter.headerHeight() + 4 * SidePanelPartyMeter.rowHeight(), after.getHeight());
		}
		// Members listed alone, out of a fight: no header.
		assertEquals(SidePanelPartyMeter.headerHeight() > 0, true);
		final SidePanelPartyMeter listed = new SidePanelPartyMeter(id -> { });
		listed.set(SidePanelParty.party(SidePanelPartyExamples.waitingWithMembers()).rows);
		assertEquals(2 * SidePanelPartyMeter.rowHeight(), listed.getPreferredSize().height);
	}

	@Test
	public void liveTheFirstColumnIsDpsAndAfterItIsDamage()
	{
		final SidePanelParty.Input in = four(true);
		final PanelSnapshot.PartyTab live = SidePanelParty.party(in);
		final SidePanelPartyMeter m = meter(live);
		// A member's DPS: all their damage over their own clock, 90 ticks.
		final LuckVsSkillUpdate moss = in.members.get(1).figures;
		assertEquals(String.format(java.util.Locale.ENGLISH, "%.1f", moss.all() / (90 * 0.6)), m.lastFirst(21));
		// Sorted by DPS live.
		for (int i = 1; i < 3; i++)
		{
			assertTrue(m.shown().get(i - 1).rate >= m.shown().get(i).rate);
		}
		// Old Tam's: his estimated damage over the fight so far, "~" before it.
		final int others = SidePanelParty.others(in.othersDamage, in.members);
		assertEquals("~" + String.format(java.util.Locale.ENGLISH, "%.1f", others / 54.0), m.lastFirst(OLD_TAM));
		// Yours is the card's T-200 rate.
		m.setYourDps("10.8");
		final PanelSnapshot.PartyRow you = live.row(SidePanelParty.YOU_ID);
		assertEquals("10.8", m.first(you));
		assertTrue(Tips.plain(SidePanelPartyMeter.rowTip(you, null, "10.8")).contains("10.8 dps so far"));
		// Both figures on hover, live and after.
		assertTrue(Tips.plain(SidePanelPartyMeter.rowTip(live.row(21), null)).contains(" damage, "));
		// After: damage; the share is the fill both times.
		final PanelSnapshot.PartyTab after = SidePanelParty.party(four(false));
		assertEquals("3,420", meter(after).lastFirst(21));
		assertEquals(1.0, after.row(21).fill, 1e-9);
		assertEquals(1.0, live.row(21).fill, 1e-9);
	}

	@Test
	public void theSwitchsPickAndTheHeadersMatchingWordShareOneStyle()
	{
		final SidePanelParty.Input in = four(false);
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(in), null, SidePanelParty.party(in)));
		final SidePanelPartySection s = p.partySection();
		s.pick(SidePanelParty.Metric.LUCK);
		assertEquals(SidePanelParty.Metric.LUCK, s.meter().metric());
		assertEquals(SidePanelParty.Metric.LUCK, s.chart().metric());
		// The switch is there live too, its first word "dps".
		final SidePanelParty.Input live = four(true);
		final PvmPerformancePanel q = new PvmPerformancePanel(null, null, null, null);
		q.apply(new PanelSnapshot(SidePanelPartyMemberViewTest.yours(live), null, SidePanelParty.party(live)));
		final List<SidePanelPartySection.MetricSwitch> words = new ArrayList<>();
		collect(q.partySection().top(), words);
		assertEquals(1, words.size());
		assertEquals("[dps, luck, skill]", words.get(0).words().toString());
	}

	private static void collect(java.awt.Container c, List<SidePanelPartySection.MetricSwitch> out)
	{
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof SidePanelPartySection.MetricSwitch)
			{
				out.add((SidePanelPartySection.MetricSwitch) k);
			}
			if (k instanceof java.awt.Container)
			{
				collect((java.awt.Container) k, out);
			}
		}
	}

	// ---- 4. the switch changes the chart ----

	@Test
	public void theSwitchChangesWhatTheChartDraws()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(four(false));
		final SidePanelPartyChart dmg = chart(t, SidePanelParty.Metric.DMG);
		final SidePanelPartyChart luck = chart(t, SidePanelParty.Metric.LUCK);
		final SidePanelPartyChart skill = chart(t, SidePanelParty.Metric.EXEC);
		// Damage: every member and Old Tam's estimate; luck and skill: the sharing members only.
		assertTrue(dmg.drawn().toString(), dmg.drawn().contains(OLD_TAM));
		for (SidePanelPartyChart c : Arrays.asList(luck, skill))
		{
			assertFalse(c.drawn().toString(), c.drawn().contains(OLD_TAM));
			assertTrue(c.drawn().containsAll(Arrays.asList(SidePanelParty.YOU_ID, 21L, 22L)));
		}
		// The readout follows the metric: damage, luck's band, the Skill %.
		final int at = 215;
		assertEquals("3,420", value(dmg.readoutAt(at), "Moss Pike"));
		assertEquals(SidePanelModel.luckText(0.88), value(luck.readoutAt(at), "Moss Pike"));
		assertEquals("96%", value(skill.readoutAt(at), "Moss Pike"));
		// Members not running the plugin: said on the luck and skill readouts, not left out silently.
		assertTrue(lines(luck.readoutAt(at)).contains("Old Tam: no luck"));
		assertTrue(lines(skill.readoutAt(at)).contains("Old Tam: no skill"));
		assertEquals("~" + LedgerRows.number(SidePanelParty.others(four(false).othersDamage, four(false).members)),
			value(dmg.readoutAt(215), "Old Tam"));
		// Highest first.
		assertEquals("Moss Pike", luck.readoutAt(at).get(1)[0]);
		assertEquals("Brindle", luck.readoutAt(at).get(3)[0]);
	}

	@Test
	public void theChartRedrawsOnANewPointOrASwitchNotEachUpdate()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(four(false));
		final SidePanelPartyChart c = new SidePanelPartyChart();
		c.set(t, SidePanelPartySection.NONE, SidePanelParty.Metric.DMG);
		final int first = c.changes();
		c.set(SidePanelParty.party(four(false)), SidePanelPartySection.NONE, SidePanelParty.Metric.DMG);
		assertEquals(first, c.changes());
		c.set(t, SidePanelPartySection.NONE, SidePanelParty.Metric.LUCK);
		assertEquals(first + 1, c.changes());
		// A new point on someone's line.
		final SidePanelParty.Input more = four(false);
		final SidePanelParty.Member moss = more.members.get(1);
		moss.ticks = Arrays.copyOf(moss.ticks, moss.ticks.length + 1);
		moss.damage = Arrays.copyOf(moss.damage, moss.damage.length + 1);
		moss.ticks[moss.ticks.length - 1] = 220;
		moss.damage[moss.damage.length - 1] = 3_500;
		moss.luck = Arrays.copyOf(moss.luck, moss.luck.length + 1);
		moss.skill = Arrays.copyOf(moss.skill, moss.skill.length + 1);
		c.set(SidePanelParty.party(more), SidePanelPartySection.NONE, SidePanelParty.Metric.LUCK);
		assertEquals(first + 2, c.changes());
	}

	@Test
	public void luckAndSkillRecordOnePointPerUpdate()
	{
		final PartyBoard board = new PartyBoard();
		final double[] p = {-1, 0.4, 0.55, 0.61};
		final double[] x = {0.97, 0.96, 0.96, 0.95};
		for (int i = 0; i < p.length; i++)
		{
			final LuckVsSkillUpdate u = SidePanelPartyExamples.figures("Yama", false, 100 * (i + 1), 95 * (i + 1),
				5 * (i + 1), 0, 0, x[i], 0);
			u.p = p[i];
			board.accept(51, u, null, 1_000 + i);
			// The same update again (nothing new): no new point.
			board.accept(51, u, null, 1_000 + i);
		}
		final List<PartyBoard.Point> points = board.view(id -> "Quillon").get(0).series.values().iterator().next();
		assertEquals(4, points.size());
		final float[][] m = SidePanelParty.metrics(Collections.singletonList(points));
		assertTrue(Float.isNaN(m[0][0]));
		assertEquals(0.4f, m[0][1], 1e-6);
		assertEquals(0.61f, m[0][3], 1e-6);
		assertEquals(0.97f, m[1][0], 1e-6);
		assertEquals(0.95f, m[1][3], 1e-6);
		// A sender before T-216's luck had no luck: the old Point says none.
		assertEquals(-1, new PartyBoard.Point(5, 10, 9, 5).luck, 0);
	}

	@Test
	public void membersNotRunningThePluginAreAbsentFromTheLuckAndSkillCharts()
	{
		final PanelSnapshot.PartyTab t = SidePanelParty.party(duo(false));
		final PanelSnapshot.PartyRow ash = t.rows.get(1);
		assertFalse(ash.charts(SidePanelParty.Metric.LUCK));
		assertFalse(ash.charts(SidePanelParty.Metric.EXEC));
		assertEquals(Collections.singletonList(SidePanelParty.YOU_ID), chart(t, SidePanelParty.Metric.LUCK).drawn());
		assertEquals(Collections.singletonList(SidePanelParty.YOU_ID), chart(t, SidePanelParty.Metric.EXEC).drawn());
		assertTrue(chart(t, SidePanelParty.Metric.DMG).drawn().contains(ASH));
	}

	// ---- 4b. the member not running the plugin on the Timeline ----

	@Test
	public void othersSplatsAreRecordedTickByTickAndNeverSaved()
	{
		final Fight f = new Fight("Yama", NpcIds.GOBLIN, 1, 900, 0L, null, 0);
		f.recordOthersDamage(30, 1_000);
		f.recordOthersDamage(12, 1_000);
		f.recordOthersDamage(0, 1_002);
		f.recordOthersDamage(25, 1_004);
		assertEquals(67, f.getOthersDamage());
		final int[][] s = f.getOthersSeries();
		assertArrayEquals(new int[]{1_000, 1_004}, s[0]);
		assertArrayEquals(new int[]{42, 67}, s[1]);
		// The total is saved; the series isn't.
		final String json = new com.google.gson.Gson().toJson(f);
		assertFalse(json, json.contains("othersTicks") || json.contains("othersTotals"));
		// On your line's axis: the room's first tick is tick 1.
		final Encounter room = new Encounter("Yama", null, 0L);
		room.add(f);
		final int[][] onAxis = PvmPerformancePanel.othersSeries(Collections.singletonList(room), 990);
		assertArrayEquals(new int[]{11, 15}, onAxis[0]);
		assertArrayEquals(new int[]{42, 67}, onAxis[1]);
	}

	@Test
	public void theirLineIsTheSplatsLessTheSharingMembersAtEachTick()
	{
		for (boolean live : new boolean[]{false, true})
		{
			final SidePanelParty.Input in = four(live);
			final int[][] line = SidePanelParty.otherSeries(in);
			final int others = SidePanelParty.others(in.othersDamage, in.members);
			// Ends at the total, never falls, and isn't the straight rise of before: it has a point a splat.
			assertEquals(others, line[1][line[1].length - 1]);
			for (int i = 1; i < line[1].length; i++)
			{
				assertTrue(line[1][i] >= line[1][i - 1]);
				assertTrue(line[0][i] >= line[0][i - 1]);
			}
			assertTrue(line[0].length > 10);
			// Halfway, about half of it: the non-sharing member's own damage, not the members'.
			final int mid = line[0][line[0].length / 2];
			final int at = line[1][line[0].length / 2];
			assertTrue(at + " at " + mid, Math.abs(at - (double) others * mid / line[0][line[0].length - 1]) < others * 0.25);
		}
		// Without the ticks (a scope from history): the straight rise, as before.
		final SidePanelParty.Input old = four(false);
		old.othersTicks = null;
		assertEquals(2, SidePanelParty.otherSeries(old)[0].length);
		// One member: drawn in their colour, lighter; two or more as the group's grey.
		final PanelSnapshot.PartyTab t = SidePanelParty.party(duo(false));
		assertTrue(t.rows.get(1).estimate);
		assertTrue(t.rows.get(1).seriesTicks.length > 10);
	}

	// ---- helpers ----

	private static String value(List<String[]> lines, String name)
	{
		for (String[] l : lines)
		{
			if (l[0].equals(name))
			{
				return l[1];
			}
		}
		return null;
	}

	private static List<String> lines(List<String[]> lines)
	{
		final List<String> out = new ArrayList<>();
		for (String[] l : lines)
		{
			out.add(l[0]);
		}
		return out;
	}
}
