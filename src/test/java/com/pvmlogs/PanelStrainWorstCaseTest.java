package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-045's offline worst cases: what the side panel and the gear search cost at
 * their largest, timed here on the build machine. They print their numbers and
 * never fail on time (a CI runner's clock says nothing about Eric's PC); they
 * fail only if the work itself breaks. Each is warmed up first, so the figures
 * are the JIT-compiled steady state a long session reaches, not the first tick.
 *
 * <p>Budgets they're read against (T-045): panel apply + paint under 2 ms a
 * tick; a handler's p99 under 1 ms and max under 3 ms.
 */
public class PanelStrainWorstCaseTest
{
	private static final int WARM = 300;
	private static final int RUNS = 1000;

	private interface Work
	{
		void run();
	}

	/** Times a piece of work: a warm-up, then RUNS timed runs; prints count, p50, p99 and max. */
	private static PerfProbe.Timer time(String what, Work work)
	{
		for (int i = 0; i < WARM; i++)
		{
			work.run();
		}
		final PerfProbe.Timer t = new PerfProbe.Timer(RUNS);
		for (int i = 0; i < RUNS; i++)
		{
			final long started = System.nanoTime();
			work.run();
			t.add(System.nanoTime() - started);
		}
		System.out.println("T-045 worst case: " + PerfProbe.timerSummary(what, t));
		return t;
	}

	// ---- The snapshot: built on the client thread, applied on Swing's ----

	/** A 10-minute fight: 1,000 ticks, an attack every four, specs, idle and eating spans. */
	private static SidePanelTimelineExamples.Built tenMinuteFight()
	{
		return SidePanelTimelineExamples.fight(1000, new int[]{11, 31, 90, 150, 210}, new int[]{60, 10, 400, 25, 700, 15},
			new int[]{130, 7, 520, 9, 850, 6}, 2700, 3000, 380, 420, 233);
	}

	@Test
	public void tenMinuteFightSnapshotAndApply()
	{
		final SidePanelTimelineExamples.Built b = tenMinuteFight();
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(b, false);
		final SidePanelModel.Input trip = SidePanelExamples.trip();
		assertEquals(1000, in.view.states.length);
		snapshotAndApply("10-minute fight", in, trip, b::view);
	}

	@Test
	public void nineRoomRaidSnapshotAndApply()
	{
		final int[] clock = {0};
		final FightTimeline.Store store = new FightTimeline.Store();
		store.setClock(() -> clock[0]);
		final Raid raid = new Raid(RaidType.TOMBS_OF_AMASCUT, 1, 0L);
		int tick = 0;
		for (int room = 0; room < 9; room++)
		{
			final Encounter e = new Encounter("Room " + (room + 1), RaidType.TOMBS_OF_AMASCUT, 0L);
			// 300 ticks a room and 30 between: a 9-room raid of about 33 minutes.
			e.add(room(store, clock, tick, 300));
			raid.add(e);
			tick += 330;
		}
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.RAID;
		SidePanelModel.fillRaid(in, raid);
		in.view = FightTimeline.raid(raid, store);
		assertTrue(in.view.states.length >= 2700);
		final SidePanelModel.Input trip = SidePanelExamples.trip();
		for (int i = 0; i < 2; i++)
		{
			trip.tripRows.addAll(new ArrayList<>(trip.tripRows));
		}
		snapshotAndApply("9-room raid (" + in.view.states.length + " ticks)", in, trip, () -> FightTimeline.raid(raid, store));
	}

	/** One room of a raid on a shared store and clock, fed the way the plugin feeds a fight. */
	private static Fight room(FightTimeline.Store store, int[] clock, int startTick, int ticks)
	{
		clock[0] = startTick;
		final Fight f = new Fight("Boss", NpcIds.GOBLIN, 1, 3000, 0L, null, 0);
		store.attach(f);
		final FightTimeline line = store.of(f);
		int cooldown = 0;
		int n = 0;
		for (int t = 0; t < ticks; t++)
		{
			clock[0] = startTick + t;
			if (t % 60 >= 50)
			{
				f.recordTickLost(t % 60 >= 57);
				cooldown = 0;
				continue;
			}
			if (cooldown > 0)
			{
				f.recordTickSpent();
				cooldown--;
				continue;
			}
			n++;
			f.recordAttackMade(false, 1.0, false);
			line.describeLatest("Abyssal tentacle", ItemID.ABYSSAL_TENTACLE, null, FightTimeline.Style.MELEE, clock[0]);
			f.recordExpected(49, 0.55, 13.5, 233);
			f.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
			if (n % 2 == 1)
			{
				f.recordDamageDealt(27, 0L, true);
				line.attributeSplat(Integer.MIN_VALUE);
			}
			cooldown = 3;
		}
		return f;
	}

	/** A snapshot as the panel builds it each tick: the scope's timeline view, then the two tabs' models. */
	private static void snapshotAndApply(String what, SidePanelModel.Input in, SidePanelModel.Input trip,
		java.util.function.Supplier<FightTimeline.View> view)
	{
		final int length = in.view.states.length;
		// The first snapshot of a scope sends the whole chart; every tick after sends from a few ticks back.
		in.chartFrom = 0;
		final PanelSnapshot first = new PanelSnapshot(SidePanelModel.fight(in), SidePanelModel.trip(trip));
		assertNotNull(first.fight);
		time(what + ": snapshot build, whole chart", () ->
		{
			in.view = view.get();
			new PanelSnapshot(SidePanelModel.fight(in), SidePanelModel.trip(trip));
		});
		in.chartFrom = length - PvmPerformancePanel.CHART_RESEND - 1;
		final PanelSnapshot tick = new PanelSnapshot(SidePanelModel.fight(in), SidePanelModel.trip(trip));
		time(what + ": snapshot build, a tick's", () ->
		{
			in.view = view.get();
			new PanelSnapshot(SidePanelModel.fight(in), SidePanelModel.trip(trip));
		});

		time(what + ": the timeline view alone", () -> view.get());
		time(what + ": the Fight tab's model alone, a tick's", () -> SidePanelModel.fight(in));
		time(what + ": the Trip tab's model alone", () -> SidePanelModel.trip(trip));

		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		// RuneLite's side panel is 242 wide; 1,000 high is a tall client's panel.
		panel.setSize(new Dimension(242, 1000));
		panel.apply(first);
		time(what + ": apply, a tick's snapshot", () -> panel.apply(tick));
		time(what + ": apply, alternating whole and tick", new Work()
		{
			private int i;

			@Override
			public void run()
			{
				panel.apply(i++ % 2 == 0 ? first : tick);
			}
		});
		// The whole panel painted, as a full repaint paints it: the upper bound of a tick's repaint.
		panel.doLayout();
		layoutAll(panel);
		final BufferedImage image = new BufferedImage(242, 1000, BufferedImage.TYPE_INT_ARGB);
		time(what + ": the whole panel painted", () ->
		{
			final Graphics2D g = image.createGraphics();
			panel.paint(g);
			g.dispose();
		});
	}

	private static void layoutAll(java.awt.Container c)
	{
		c.doLayout();
		for (java.awt.Component k : c.getComponents())
		{
			if (k instanceof java.awt.Container)
			{
				layoutAll((java.awt.Container) k);
			}
		}
	}

	// ---- The chart and the tick strip, 1,000 ticks ----

	@Test
	public void chartAndStripPaintAThousandTicks()
	{
		final SidePanelTimelineExamples.Built b = tenMinuteFight();
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(b, false);
		final SidePanelChartData data = new SidePanelChartData();
		data.apply(SidePanelModel.chart(in, in.scope, LedgerRows.Colour.NEUTRAL, false));
		assertEquals(1000, data.length());
		paint("damage chart, 1,000 ticks", new SidePanelDamageChart(data));
		paint("tick strip, 1,000 ticks", new SidePanelTickStrip(data));
	}

	private static void paint(String what, JComponent c)
	{
		final Dimension size = c.getPreferredSize();
		c.setSize(Math.max(1, Math.max(size.width, 226)), Math.max(1, size.height));
		final BufferedImage image = new BufferedImage(c.getWidth(), c.getHeight(), BufferedImage.TYPE_INT_ARGB);
		time(what + " (" + c.getWidth() + "x" + c.getHeight() + ")", () ->
		{
			final Graphics2D g = image.createGraphics();
			c.paint(g);
			g.dispose();
		});
	}

	// ---- The gear search with a full inventory of switches ----

	@Test
	public void gearSearchWithAFullInventory()
	{
		// 28 carried items over the eight slots a switch can use, each better or worse in its own way; the worn loadout the
		// worst of each, so the search improves every slot and runs both passes.
		final EquipmentInventorySlot[] slots = {EquipmentInventorySlot.HEAD, EquipmentInventorySlot.CAPE,
			EquipmentInventorySlot.AMULET, EquipmentInventorySlot.BODY, EquipmentInventorySlot.LEGS,
			EquipmentInventorySlot.GLOVES, EquipmentInventorySlot.BOOTS, EquipmentInventorySlot.RING};
		Loadout worn = Loadout.worn(null, null);
		for (EquipmentInventorySlot slot : slots)
		{
			worn = worn.with(slot, 1000);
		}
		final List<GearSearch.Candidate> carried = new ArrayList<>();
		for (int i = 0; i < 28; i++)
		{
			carried.add(new GearSearch.Candidate(slots[i % slots.length], 1001 + i));
		}
		final int[] evaluations = {0};
		// Each evaluation does the arithmetic of one expected hit: bonuses summed over the slots, the max hit and the accuracy
		// from them, and the damage's moments. The real one (CombatCalc.averageHitWith) also reads item stats, which needs
		// the client; the in-game probe's gear-switch check slot times that.
		final GearSearch.Score score = gear ->
		{
			evaluations[0]++;
			int strength = 0;
			int attack = 0;
			for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
			{
				final int id = gear.id(slot);
				if (id > 0)
				{
					strength += id % 17;
					attack += id % 23;
				}
			}
			final int max = CombatCalc.maxHitFromStrength(118 * 23 / 20 + 11, 100 + strength);
			final double accuracy = CombatCalc.hitChanceFrom((118 + 8) * (64 + 130 + attack), 30_000);
			return GearSearch.perTick(CombatCalc.ordinaryMoments(max, accuracy, 1, Integer.MAX_VALUE).mean, 4);
		};
		final Loadout start = worn;
		GearSearch.best(start, carried, false, score);
		final int perSearch = evaluations[0];
		assertTrue(perSearch > carried.size());
		final PerfProbe.Timer t = time("gear search, 28 carried switches (" + perSearch + " loadouts weighed)",
			() -> GearSearch.best(start, carried, false, score));
		assertTrue(t.count() == RUNS);
	}
}
