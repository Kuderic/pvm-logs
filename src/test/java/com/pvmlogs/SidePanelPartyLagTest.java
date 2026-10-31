package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.swing.JComponent;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-130 review: the Party tab must cost what the Fight tab does. Its header no longer lays out on every apply, it paints
 * through the same software buffer as the other tabs, and your panels on it are sent their chart incrementally, with
 * their own bookkeeping. Measured against the Fight tab on the same fight, a snapshot every 5 ticks.
 */
public class SidePanelPartyLagTest
{
	/** A fight fed a tick at a time, as SidePanelNoLagTest's: an attack every 4 ticks, a spec now and then, idle spans. */
	static final class Live
	{
		final int[] clock = {0};
		final FightTimeline.Store store = new FightTimeline.Store();
		final Fight fight;
		final Encounter room;
		final FightTimeline line;
		final int hit;
		private int cooldown;
		private int attacks;
		int tick;

		Live(int hit)
		{
			this.hit = hit;
			fight = new Fight("Verzik Vitur", NpcIds.GOBLIN, 1, 700, 1_000L, null, 0);
			room = new Encounter("Verzik Vitur", null, 1_000L);
			store.setClock(() -> clock[0]);
			store.attach(fight);
			line = store.of(fight);
			room.add(fight);
		}

		void run(int ticks)
		{
			for (int end = tick + ticks; tick < end; tick++)
			{
				clock[0] = tick;
				if (tick % 97 >= 90)
				{
					fight.recordTickLost(tick % 97 >= 95);
					cooldown = 0;
					continue;
				}
				if (cooldown > 0)
				{
					fight.recordTickSpent();
					cooldown--;
					continue;
				}
				attacks++;
				final boolean special = attacks % 23 == 0;
				fight.recordAttackMade(false, 1.0, false);
				line.describeLatest(special ? "Burning claws" : "Abyssal tentacle", special ? ItemID.BONE_CLAWS
					: ItemID.ABYSSAL_TENTACLE, null, FightTimeline.Style.MELEE, tick);
				if (special)
				{
					line.markLatestSpecial();
				}
				fight.recordExpected(49, 0.55, special ? 30 : 13.5, 233);
				fight.recordAttackResolved(true, true, 0.98, 1.0, 0.012, 0);
				if (attacks % 2 == 1)
				{
					fight.recordDamageDealt(special ? hit + 14 : hit, 0L, true);
					line.attributeSplat(Integer.MIN_VALUE);
				}
				cooldown = 3;
			}
		}

		FightTimeline.View view()
		{
			return FightTimeline.room(room, store);
		}

		/** The Fight tab's input, its chart sent from {@code chartFrom}. */
		SidePanelModel.Input input(SidePanelWeaponRows rows, int chartFrom)
		{
			final SidePanelModel.Input in = new SidePanelModel.Input();
			in.zone = SidePanelExamples.ZONE;
			in.kind = SidePanelModel.Kind.ROOM;
			SidePanelModel.fillRoom(in, room);
			in.view = view();
			in.weaponRows = rows.rows(room, in.view);
			in.chartFrom = chartFrom;
			return in;
		}

		/** What they send mid-fight, as the board holds it. */
		LuckVsSkillUpdate update()
		{
			return PartyShare.build(LuckVsSkillUpdate.LIVE, room, view().attacks, 0);
		}
	}

	/** The party mid-fight: you, and three members each with their own fight; their panels attached as the panel does. */
	static final class Party
	{
		final Live you = new Live(27);
		final Live[] them = {new Live(31), new Live(22), new Live(25)};
		final String[] names = {"Quillon", "Vey", "Old Tam"};
		final SidePanelWeaponRows rows = new SidePanelWeaponRows();
		final SidePanelWeaponRows partyRows = new SidePanelWeaponRows();
		final PvmPerformancePanel.ChartBook fightBook = new PvmPerformancePanel.ChartBook();
		final PvmPerformancePanel.ChartBook partyBook = new PvmPerformancePanel.ChartBook();
		final SidePanelMemberFight.Panels panels = new SidePanelMemberFight.Panels();
		int lastSent;

		void run(int ticks)
		{
			you.run(ticks);
			for (Live l : them)
			{
				l.run(ticks);
			}
		}

		Object key()
		{
			return Arrays.asList(you.room, you.view().startTick);
		}

		PanelSnapshot fightSnapshot()
		{
			return new PanelSnapshot(SidePanelModel.fight(you.input(rows, fightBook.from(key(),
				you.view().states.length))), null);
		}

		PanelSnapshot partySnapshot()
		{
			final SidePanelParty.Input in = new SidePanelParty.Input();
			in.inParty = true;
			in.scope = "Verzik Vitur";
			in.live = true;
			in.seconds = you.tick * LedgerRows.SECONDS_PER_TICK;
			in.partySize = 4;
			in.partyKey = 5;
			in.shared = them.length;
			in.itemNames = SidePanelPartyExamples::itemName;
			in.members.add(member(SidePanelParty.YOU_ID, SidePanelParty.YOU_NAME, true, you, 0));
			final SidePanelModel.Input yours = you.input(partyRows, partyBook.from(key(), you.view().states.length));
			final PanelSnapshot.FightTab fight = SidePanelModel.fight(yours);
			lastSent = fight.chart.length - fight.chart.from;
			in.members.get(0).fight = fight;
			for (int i = 0; i < them.length; i++)
			{
				final SidePanelParty.Member m = member(60 + i, names[i], false, them[i], i);
				panels.attach(m, null, in.itemNames);
				in.members.add(m);
			}
			return new PanelSnapshot(null, null, SidePanelParty.party(in));
		}

		private static SidePanelParty.Member member(long id, String name, boolean you, Live l, int slot)
		{
			final SidePanelParty.Member m = new SidePanelParty.Member(id, name, you, l.update());
			m.slot = slot;
			final int[][] s = SidePanelParty.series(l.view().damage);
			m.ticks = s[0];
			m.damage = s[1];
			return m;
		}
	}

	@Test
	public void yourPanelsOnThePartyTabAreSentTheirChartIncrementally()
	{
		final Party party = new Party();
		party.run(130);
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null, null));
		p.tabs().select(p.tabs().getTab(3));
		p.apply(party.partySnapshot());
		assertEquals("the first is whole", party.you.view().states.length, party.lastSent);
		final SidePanelFightTab yours = p.partyTab().memberFight();
		assertNotNull(yours);
		final int builds = p.partyTab().rebuilds();
		while (party.you.tick < 1000)
		{
			party.run(PvmPerformancePanel.UPDATE_TICKS);
			p.apply(party.partySnapshot());
			// Only what's new, and the few ticks resent behind it.
			assertTrue("sent " + party.lastSent, party.lastSent <= PvmPerformancePanel.UPDATE_TICKS
				+ PvmPerformancePanel.CHART_RESEND);
		}
		assertEquals(builds, p.partyTab().rebuilds());
		// The copy is the whole series, as one sent from the start would make it.
		final SidePanelChartData whole = new SidePanelChartData();
		whole.apply(SidePanelModel.fight(party.you.input(new SidePanelWeaponRows(), 0)).chart);
		final SidePanelChartData copy = yours.chartData();
		assertEquals(whole.length(), copy.length());
		for (int i = 0; i < whole.length(); i++)
		{
			assertEquals("tick " + i, whole.damage(i), copy.damage(i), 0f);
		}
		// The Fight tab's bookkeeping is its own: its first build on this scope is still whole.
		assertEquals(0, party.fightBook.from(party.key(), party.you.view().states.length));
	}

	/**
	 * The 10:35 log had the Party tab's "panel repaint after apply" at ~29 ms p50 against the Fight tab's ~6 ms, a layout
	 * pass on nearly every apply from the header's short forms, and no software render. The same fight on both tabs, ten
	 * minutes of snapshots: the Party tab now lays out about as rarely as the Fight tab, paints through its buffer, and
	 * its apply and paint are printed beside the Fight tab's.
	 */
	@Test
	public void thePartyTabCostsWhatTheFightTabDoes()
	{
		final Party party = new Party();
		party.run(130);
		final PvmPerformancePanel onFight = new PvmPerformancePanel(null, null, null, null);
		onFight.setSize(242, 1000);
		final PvmPerformancePanel onParty = new PvmPerformancePanel(null, null, null, null);
		onParty.setSize(242, 1000);
		onFight.apply(party.fightSnapshot());
		onParty.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), null, null));
		onParty.tabs().select(onParty.tabs().getTab(3));
		onParty.apply(party.partySnapshot());
		final JComponent fightTab = onFight.fightTab();
		final JComponent partyTab = onParty.partyTab();
		settle(fightTab);
		settle(partyTab);
		final List<Long> fightApply = new ArrayList<>();
		final List<Long> partyApply = new ArrayList<>();
		final List<Long> fightPaint = new ArrayList<>();
		final List<Long> partyPaint = new ArrayList<>();
		final List<Long> fightBuild = new ArrayList<>();
		final List<Long> partyBuild = new ArrayList<>();
		int fightLayouts = 0;
		int partyLayouts = 0;
		int updates = 0;
		while (party.you.tick < 1000)
		{
			party.run(PvmPerformancePanel.UPDATE_TICKS);
			long b0 = System.nanoTime();
			final PanelSnapshot f = party.fightSnapshot();
			fightBuild.add(System.nanoTime() - b0);
			b0 = System.nanoTime();
			final PanelSnapshot q = party.partySnapshot();
			partyBuild.add(System.nanoTime() - b0);
			updates++;
			int before = SidePanelParts.relayouts;
			long t0 = System.nanoTime();
			onFight.apply(f);
			fightApply.add(System.nanoTime() - t0);
			if (SidePanelParts.relayouts != before)
			{
				fightLayouts++;
				settle(fightTab);
			}
			fightPaint.add(paint(fightTab));
			before = SidePanelParts.relayouts;
			t0 = System.nanoTime();
			onParty.apply(q);
			partyApply.add(System.nanoTime() - t0);
			if (SidePanelParts.relayouts != before)
			{
				partyLayouts++;
				settle(partyTab);
			}
			partyPaint.add(paint(partyTab));
		}
		System.out.println(String.format("T-130 review, %d updates over ten minutes, on the same fight:%n"
				+ "  Fight tab: build p50 %.2f ms, apply p50 %.2f p99 %.2f ms, paint p50 %.2f p99 %.2f ms, layout asked %d times%n"
				+ "  Party tab: build p50 %.2f ms, apply p50 %.2f p99 %.2f ms, paint p50 %.2f p99 %.2f ms, layout asked %d times",
			updates, ms(fightBuild, 0.5), ms(fightApply, 0.5), ms(fightApply, 0.99), ms(fightPaint, 0.5),
			ms(fightPaint, 0.99), fightLayouts, ms(partyBuild, 0.5), ms(partyApply, 0.5), ms(partyApply, 0.99),
			ms(partyPaint, 0.5), ms(partyPaint, 0.99), partyLayouts));
		assertTrue(updates >= 170);
		// Before: a layout pass on nearly every apply. Now a handful more than the Fight tab's (the meter's and the
		// header's values gaining a digit as four members' damage grows), never one an apply.
		assertTrue("party layouts " + partyLayouts + " against the Fight tab's " + fightLayouts,
			partyLayouts <= fightLayouts + 12);
		// Through the buffer, as the other tabs.
		assertTrue(onParty.partyTab().buffer().imageSize().width > 0);
	}

	/** The header's clock ticking inside its short forms lays nothing out; only the shortest form's width does. */
	@Test
	public void shortFormsLayOutOnlyWhenTheShortestChanges()
	{
		final SidePanelParts.Line line = new SidePanelParts.Line(PanelSnapshot.Text.small("Verzik Vitur",
			SidePanelStyle.SECONDARY), PanelSnapshot.Text.small("1:00 · party 80.1 dps", SidePanelStyle.SECONDARY));
		line.setRightShort(Arrays.asList("1:00 · 80.1 dps", "1:00", ""));
		final int before = SidePanelParts.relayouts;
		for (String clock : new String[]{"1:03", "1:06", "1:09", "1:12"})
		{
			line.setRightShort(Arrays.asList(clock + " · 81.0 dps", clock, ""));
		}
		assertEquals(before, SidePanelParts.relayouts);
		line.setRightShort(Arrays.asList("1:15 · 81.0 dps", "1:15"));
		assertEquals(before + 1, SidePanelParts.relayouts);
		line.setRightShort(java.util.Collections.<String>emptyList());
		assertEquals(before + 2, SidePanelParts.relayouts);
	}

	private static void settle(JComponent tab)
	{
		tab.setSize(242, 10_000);
		SidePanelPictures.layout(tab);
		tab.setSize(242, tab.getPreferredSize().height);
		SidePanelPictures.layout(tab);
	}

	/** A whole paint of the tab, through its buffer as on screen; nanoseconds. */
	private static long paint(JComponent tab)
	{
		final BufferedImage img = new BufferedImage(Math.max(1, tab.getWidth()), Math.max(1, tab.getHeight()),
			BufferedImage.TYPE_INT_RGB);
		final Graphics2D g = img.createGraphics();
		final long t0 = System.nanoTime();
		tab.paint(g);
		final long took = System.nanoTime() - t0;
		g.dispose();
		return took;
	}

	private static double ms(List<Long> ns, double q)
	{
		final List<Long> s = new ArrayList<>(ns);
		java.util.Collections.sort(s);
		return s.get(Math.min(s.size() - 1, (int) Math.floor(q * s.size()))) / 1e6;
	}
}
