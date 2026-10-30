package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-036's tests: the damage rows add up to the scope's totals, the chart's last
 * point is A, E and V, and the strip's counts are the lost and eating ticks, on
 * Eric's replay fixtures and on fights made with the plugin's own calls.
 */
public class SidePanelChartsTest
{
	static
	{
		System.setProperty("java.awt.headless", "true");
	}

	// ---- the replay fixtures, rebuilt as FightTimelineReplayTest rebuilds them ----

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

	/** The fixture's fights with timelines attached, fed in the plugin's order (FightTimelineReplayTest's harness). */
	private static Replayed replay(String log) throws IOException
	{
		final Replayed r = new Replayed();
		r.store.setClock(() -> r.tick);
		Fight current = null;
		int attacks = 0;
		try (BufferedReader in = new BufferedReader(new InputStreamReader(
			SidePanelChartsTest.class.getResourceAsStream("/com/pvmlogs/replay/" + log), StandardCharsets.UTF_8)))
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
					// Two weapons, by turns, so there's more than one row to add up.
					attacks++;
					r.store.of(current).describeLatest(attacks % 3 == 0 ? "Second weapon" : "First weapon", -1, null,
						FightTimeline.Style.MELEE, Integer.parseInt(m.group(1)));
				}
				else if ((m = SCORED.matcher(body.trim())).find())
				{
					final double average = Double.parseDouble(m.group(4));
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

	/** Each fixture fight as a room of its own, and its rows, chart and strip against its own totals. */
	private static void assertSums(String log) throws IOException
	{
		final Replayed r = replay(log);
		int checked = 0;
		for (Fight fight : r.fights)
		{
			final FightTimeline line = r.store.of(fight);
			if (line == null || line.length() == 0 || fight.getAttacksMade() == 0)
			{
				continue;
			}
			final String what = log + ": " + fight.getTargetName() + " at " + line.startTick();
			final Encounter room = new Encounter(fight.encounterName(), null, 0L);
			room.add(fight);
			final FightTimeline.View view = FightTimeline.room(room, r.store);
			if (!fight.isScored())
			{
				// An unscored add's ticks show as unscored, and it has no rows.
				assertTrue(what, view.attacks.isEmpty());
				continue;
			}

			// Rows: the weapon rows and what no attack holds add up to the fight's weapon damage; their expected to its.
			int rows = 0;
			double expected = 0;
			int attacks = 0;
			for (SidePanelModel.WeaponRow w : SidePanelModel.weaponRows(view))
			{
				rows += w.damage;
				expected += w.expected;
				attacks += w.attacks;
			}
			assertEquals(what + " row damage", fight.getDamageDealt(), rows + line.unattributedDamage());
			// Expectations that came before any attack of the fight was booked aren't any row's: the "not matched" row's.
			final double unheld = fight.getSumExpectedAverageHit() - expected;
			assertTrue(what + " row expected " + unheld, unheld > -1e-6);
			assertEquals(what + " row attacks", fight.getAttacksMade(), attacks);

			// The chart's last point: A, E and V.
			final SidePanelModel.Input in = new SidePanelModel.Input();
			in.kind = SidePanelModel.Kind.ROOM;
			SidePanelModel.fillRoom(in, room);
			in.view = view;
			final PanelSnapshot.Chart c = SidePanelModel.chart(in, in.scope, LedgerRows.Colour.NEUTRAL, false);
			assertNotNull(what, c);
			final int last = c.length - 1;
			assertEquals(what + " A", fight.getDamageDealt(), c.damage[last], 0.5);
			assertEquals(what + " E", fight.getSumExpectedAverageHit(), c.expected[last], 0.05);
			assertEquals(what + " V", fight.getSumExpectedDamageVariance(), c.variance[last],
				Math.max(0.5, fight.getSumExpectedDamageVariance() * 1e-5));

			// The strip's counts: the lost and eating ticks.
			assertEquals(what + " lost", fight.getTicksLost() - fight.getTicksLostEating(), c.lost);
			assertEquals(what + " eating", fight.getTicksLostEating(), c.eating);

			// The rows as shown: weapon rows, plus "not matched" exactly when something is.
			final PanelSnapshot.Sources sources = SidePanelModel.damage(new LedgerRows.Numbers(in.scope), in, false);
			final boolean unmatched = sources.rows.stream().anyMatch(s -> s.name.equals("Not matched to an attack"));
			assertEquals(what + " unmatched row", line.unattributedDamage() != 0 || Math.abs(unheld) >= 0.5, unmatched);
			// So the rows, the unmatched one included, add up to the fight's damage and expected damage.
			int shownDamage = 0;
			for (PanelSnapshot.Source src : sources.rows)
			{
				shownDamage += Integer.parseInt(src.damage.replace(",", ""));
			}
			assertEquals(what + " shown damage", fight.getDamageDealt(), shownDamage);
			checked++;
		}
		assertTrue(log, checked > 1);
	}

	@Test
	public void vardorvisEightKills() throws IOException
	{
		assertSums("vardorvis-8-kills-20261026.log");
	}

	@Test
	public void vardorvisClawsRecoilVengeance() throws IOException
	{
		assertSums("vardorvis-claws-recoil-veng-20261026-2308.log");
	}

	@Test
	public void sireTenKills() throws IOException
	{
		assertSums("sire-10-kills-20261027-0130.log");
	}

	// ---- the canvas's split, built with the plugin's calls ----

	@Test
	public void theKillsRowsAreTheCanvasSplit()
	{
		final SidePanelTimelineExamples.Built kill = SidePanelTimelineExamples.kill();
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(kill, true);
		in.scoredThrall = 80;
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		final PanelSnapshot.Sources d = SidePanelModel.damage(n, in, false);
		assertEquals("768 yours", d.hint.text);
		assertEquals("Abyssal tentacle", d.rows.get(0).name);
		assertEquals("612", d.rows.get(0).damage);
		// T-085: the expected damage is in the row's details; its sub-line has the DPS against expected.
		assertTrue(d.rows.get(0).tooltip, d.rows.get(0).tooltip.contains("damage 612, expected 690 ("));
		assertEquals(ItemID.ABYSSAL_TENTACLE, d.rows.get(0).itemId);
		assertEquals(SidePanelStyle.MELEE, d.rows.get(0).colour);
		assertEquals("Burning claws", d.rows.get(1).name);
		assertEquals("2 specs (expected 88 dmg)", d.rows.get(1).sub);
		// A special's colour: its style's, 70% toward white (#FFD6DA from melee's #E06C75, rounded).
		assertEquals(SidePanelStyle.towardWhite(SidePanelStyle.MELEE, 0.7), d.rows.get(1).colour);
		assertEquals("Thrall", d.rows.get(2).name);
		assertEquals(SidePanelIcons.THRALL_SPRITE, d.rows.get(2).spriteId);
		// 612 + 76 + 80 = 768: 80%, 10%, 10% (spec §10).
		assertEquals(Arrays.asList("80%", "10%", "10%"), Arrays.asList(d.rows.get(0).share, d.rows.get(1).share,
			d.rows.get(2).share));
		assertEquals(3, d.rows.size());
	}

	@Test
	public void theStripCountsTheKillsTicks()
	{
		final SidePanelTimelineExamples.Built kill = SidePanelTimelineExamples.kill();
		final PanelSnapshot.Chart c = SidePanelModel.chart(SidePanelTimelineExamples.input(kill, true),
			LedgerRows.of(kill.room), LedgerRows.Colour.COST, false);
		assertEquals(228, c.length);
		assertEquals(25, c.lost);
		assertEquals(7, c.eating);
		assertEquals(kill.fight.getTicksLost(), c.lost + c.eating);
		assertEquals(SidePanelStyle.COST, c.actual);
		assertEquals("you 688", c.youLegend);
		assertEquals("expected 778", c.expectedLegend);
		assertEquals(SidePanelStyle.MELEE, c.attackColour);
		// The two specials' ticks carry the special state, every attack tick its style.
		int specials = 0;
		for (int i = 0; i < c.length; i++)
		{
			if (c.states[i] == FightTimeline.SPECIAL)
			{
				specials++;
			}
			if (c.states[i] == FightTimeline.ATTACK || c.states[i] == FightTimeline.SPECIAL)
			{
				assertEquals(FightTimeline.Style.MELEE.ordinal() + 1, c.styles[i]);
			}
		}
		assertEquals(2, specials);
		// Four rows of 60 for 228 ticks.
		assertEquals(4, SidePanelTickStrip.rows(c.length));
	}

	@Test
	public void theStripsColours()
	{
		// Spec §2, with T-038: eating is neutral (the time budget's eating grey), not a cost like a lost tick.
		final byte melee = (byte) (FightTimeline.Style.MELEE.ordinal() + 1);
		assertEquals(SidePanelStyle.EATING, SidePanelTickStrip.colour(FightTimeline.EATING, (byte) 0));
		assertEquals(SidePanelStyle.COST, SidePanelTickStrip.colour(FightTimeline.LOST, (byte) 0));
		assertFalse(SidePanelStyle.EATING.equals(SidePanelStyle.COST));
		assertEquals(SidePanelStyle.MELEE, SidePanelTickStrip.colour(FightTimeline.ATTACK, melee));
		assertEquals(SidePanelStyle.towardWhite(SidePanelStyle.MELEE, 0.7), SidePanelTickStrip.colour(FightTimeline.SPECIAL,
			melee));
		assertEquals(SidePanelStyle.TRACK, SidePanelTickStrip.colour(FightTimeline.COOLDOWN, (byte) 0));
		assertEquals(SidePanelStyle.UNSCORED, SidePanelTickStrip.colour(FightTimeline.UNSCORED, (byte) 0));
	}

	@Test
	public void theWarningHidesExpectedAndTheRange()
	{
		final SidePanelTimelineExamples.Built live = SidePanelTimelineExamples.live();
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(live, false);
		in.warning = "unsure";
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertNotNull(t.chart);
		assertFalse(t.chart.expectedShown);
		assertFalse(t.chart.rangeShown);
		assertEquals(SidePanelStyle.VALUE, t.chart.actual);
		// T-085: the DPS, but no expected beside it.
		assertEquals("27 attacks · 5.1 DPS", t.damage.rows.get(0).sub);
	}

	@Test
	public void chartsOffOrNoDataHideTheChart()
	{
		final SidePanelModel.Input off = SidePanelTimelineExamples.input(SidePanelTimelineExamples.live(), false);
		off.charts = false;
		assertNull(SidePanelModel.fight(off).chart);

		final SidePanelModel.Input none = SidePanelTimelineExamples.input(SidePanelTimelineExamples.live(), false);
		none.view = SidePanelTimelineExamples.none();
		final PanelSnapshot.FightTab t = SidePanelModel.fight(none);
		assertNull(t.chart);
		// Without in-memory data, only the Now rows: here the thrall.
		assertEquals(1, t.damage.rows.size());
		assertEquals("Thrall", t.damage.rows.get(0).name);
	}

	@Test
	public void theChartIsSentATickAtATime()
	{
		// Swing's copy, extended from a few ticks back each time, ends up the whole series (spec 5.1).
		final SidePanelTimelineExamples.Built kill = SidePanelTimelineExamples.kill();
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(kill, true);
		final PanelSnapshot.Chart whole = SidePanelModel.chart(in, in.scope, LedgerRows.Colour.NEUTRAL, false);
		final SidePanelChartData data = new SidePanelChartData();
		int sent = 0;
		for (int upTo = 30; upTo <= 228; upTo += 33)
		{
			final FightTimeline.View v = in.view;
			final int end = Math.min(228, upTo);
			in.view = new FightTimeline.View(v.startTick, Arrays.copyOf(v.states, end), Arrays.copyOf(v.damage, end),
				Arrays.copyOf(v.expected, end), Arrays.copyOf(v.variance, end), v.attacks);
			in.chartFrom = Math.max(0, sent - PvmPerformancePanel.CHART_RESEND);
			final PanelSnapshot.Chart part = SidePanelModel.chart(in, in.scope, LedgerRows.Colour.NEUTRAL, false);
			assertEquals(in.chartFrom, part.from);
			assertEquals(end - in.chartFrom, part.damage.length);
			data.apply(part);
			sent = end;
			in.view = v;
		}
		in.chartFrom = Math.max(0, sent - PvmPerformancePanel.CHART_RESEND);
		data.apply(SidePanelModel.chart(in, in.scope, LedgerRows.Colour.NEUTRAL, false));
		assertEquals(whole.length, data.length());
		final float[] got = new float[data.length()];
		final byte[] states = new byte[data.length()];
		for (int i = 0; i < got.length; i++)
		{
			got[i] = data.damage(i);
			states[i] = data.state(i);
		}
		assertArrayEquals(whole.damage, got, 0f);
		assertArrayEquals(whole.states, states);
		// A snapshot from 0 starts the copy over: a new scope.
		in.chartFrom = 0;
		in.view = SidePanelTimelineExamples.live().view();
		data.apply(SidePanelModel.chart(in, in.scope, LedgerRows.Colour.NEUTRAL, false));
		assertEquals(122, data.length());
	}

	@Test
	public void aTickDoesntRebuildTheChart()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		final SidePanelModel.Input in = SidePanelExamples.live();
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		final int builds = panel.fightTab().builds();
		in.chartFrom = 118;
		((LedgerExamples.FakeScope) in.scope).combatTicks += 1;
		panel.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		assertEquals(builds, panel.fightTab().builds());
	}

	private static List<java.awt.Component> all(java.awt.Container c)
	{
		final List<java.awt.Component> out = new ArrayList<>();
		for (java.awt.Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof java.awt.Container)
			{
				out.addAll(all((java.awt.Container) k));
			}
		}
		return out;
	}

	// ---- icons ----

	@Test
	public void everySpellHasASprite()
	{
		for (Spell spell : Spell.values())
		{
			assertTrue(spell.name(), SidePanelIcons.spellSprite(spell) >= 0);
		}
		assertEquals(-1, SidePanelIcons.spellSprite(null));
	}

	@Test
	public void aRowShowsItsIconWhenItArrives()
	{
		final List<Integer> asked = new ArrayList<>();
		final List<Consumer<BufferedImage>> pending = new ArrayList<>();
		final SidePanelIcons icons = new SidePanelIcons()
		{
			public void item(int itemId, Consumer<BufferedImage> done)
			{
				asked.add(itemId);
				pending.add(done);
			}

			public void sprite(int spriteId, Consumer<BufferedImage> done)
			{
				asked.add(-spriteId);
				pending.add(done);
			}
		};
		final SidePanelParts.SourceRowView row = new SidePanelParts.SourceRowView(new PanelSnapshot.Source("Abyssal tentacle",
			"27 attacks", "330", "79%", 0.79, SidePanelStyle.MELEE, ItemID.ABYSSAL_TENTACLE, -1), icons);
		assertEquals(Arrays.asList(ItemID.ABYSSAL_TENTACLE), asked);
		assertFalse(row.hasIcon());
		pending.get(0).accept(new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB));
		assertTrue(row.hasIcon());
		// The same row reused for the thrall: asks for its sprite, and a late tentacle image is dropped.
		row.set(new PanelSnapshot.Source("Thrall", "no luck either way", "48", "11%", 0.11, SidePanelStyle.THRALL, -1,
			SidePanelIcons.THRALL_SPRITE));
		assertEquals(-SidePanelIcons.THRALL_SPRITE, (int) asked.get(1));
		assertFalse(row.hasIcon());
		pending.get(0).accept(new BufferedImage(36, 32, BufferedImage.TYPE_INT_ARGB));
		assertFalse(row.hasIcon());
	}

	// ---- the saved kill view ----

	@Test
	public void aSavedKillFromThisSessionGetsItsCharts()
	{
		final SidePanelTimelineExamples.Built kill = SidePanelTimelineExamples.kill();
		kill.fight.end(true, 1L);
		final List<Fight> history = new ArrayList<>(Arrays.asList(kill.fight));
		final SidePanelModel.Input in = SidePanelHistory.savedKill(history, kill.room, 0L, 0L, SidePanelExamples.ZONE,
			SidePanelHistory.Versions.NONE, kill.view());
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertNull(t.later);
		assertNotNull(t.chart);
		assertEquals("Abyssal tentacle", t.damage.rows.get(0).name);

		final PanelSnapshot.FightTab gone = SidePanelModel.fight(SidePanelHistory.savedKill(history, kill.room, 0L, 0L,
			SidePanelExamples.ZONE, SidePanelHistory.Versions.NONE, FightTimeline.View.EMPTY));
		assertEquals(SidePanelModel.NO_CHARTS_TEXT, gone.later);
		assertNull(gone.chart);
	}

	@Test
	public void theHistoryTabFetchesTheOpenKillsCharts()
	{
		final SidePanelTimelineExamples.Built kill = SidePanelTimelineExamples.kill();
		kill.fight.end(true, 1L);
		final List<Fight> history = new ArrayList<>(Arrays.asList(kill.fight));
		final int[] asked = {0};
		final SidePanelHistoryTab.Source source = new SidePanelHistoryTab.Source()
		{
			public List<Fight> history()
			{
				return history;
			}

			public SidePanelHistory.Versions versions()
			{
				return SidePanelHistory.Versions.NONE;
			}

			public long sessionStart()
			{
				return 0;
			}

			public long now()
			{
				return 0;
			}

			public java.time.ZoneId zone()
			{
				return SidePanelExamples.ZONE;
			}

			public void exportNpc(String name)
			{
			}

			@Override
			public void charts(Encounter room, Consumer<FightTimeline.View> done)
			{
				asked[0]++;
				done.accept(FightTimeline.room(room, kill.store));
			}
		};
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source);
		panel.historyTab().open(kill.fight);
		assertTrue(panel.historyTab().isShowingKill());
		assertEquals(1, asked[0]);
		// One kill view, even when the charts come back at once.
		int views = 0;
		for (java.awt.Component c : all(panel.historyTab()))
		{
			views += c instanceof SidePanelFightTab ? 1 : 0;
		}
		assertEquals(1, views);
	}
}
