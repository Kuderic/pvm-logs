package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * T-085: each weapon or spell row on the Damage card shows its DPS, its damage over the time spent attacking with it,
 * beside its expected DPS; a tooltip has its details. Not for specials, thrall, damage over time or recoil, nor for a
 * kill with no timeline.
 */
public class SidePanelWeaponDpsTest
{
	private static PanelSnapshot.Source row(PanelSnapshot.FightTab tab, String name, boolean special)
	{
		for (PanelSnapshot.Source s : tab.damage.rows)
		{
			if (s.name.equals(name) && (s.sub.contains("spec") == special))
			{
				return s;
			}
		}
		return null;
	}

	private static SidePanelModel.WeaponRow weapon(SidePanelModel.Input in, String name, boolean special)
	{
		for (SidePanelModel.WeaponRow w : SidePanelModel.weaponRows(in.view))
		{
			if (w.name.equals(name) && w.special == special)
			{
				return w;
			}
		}
		return null;
	}

	// ---- the time spent attacking with each weapon ----

	@Test
	public void aWeaponsTimeIsItsAttacksTimesItsSpeed()
	{
		final SidePanelModel.Input sire = SidePanelExamples.sireWeapons();
		assertEquals(12 * 4, weapon(sire, "Scorching bow", false).ticks);
		assertEquals(30 * 4, weapon(sire, "Emberlight", false).ticks);
		assertEquals(2 * 4, weapon(sire, "Emberlight", true).ticks);
		// The lost ticks between the stretches (8 and 4) are nobody's.
		final SidePanelModel.Input leviathan = SidePanelExamples.leviathanWeapons();
		assertEquals(40 * 5, weapon(leviathan, "Twisted bow", false).ticks);
		assertEquals(30 * 2, weapon(leviathan, "Toxic blowpipe", false).ticks);
	}

	@Test
	public void aSwitchBeforeTheCooldownEndsGivesTheFirstWeaponOnlyTheTicksItHeld()
	{
		// A bow (5 ticks) thrown at 0, a blowpipe at 2 (switched before the bow's cooldown ended), idle at 4 and 5, the
		// bow again at 6 and to the end.
		final byte a = FightTimeline.ATTACK;
		final byte c = FightTimeline.COOLDOWN;
		final byte l = FightTimeline.LOST;
		final byte[] states = {a, c, a, c, l, l, a, c, c, c, c};
		final List<FightTimeline.Attack> attacks = Arrays.asList(new FightTimeline.Attack(100),
			new FightTimeline.Attack(102), new FightTimeline.Attack(106));
		final FightTimeline.View view = new FightTimeline.View(100, states, new float[states.length],
			new float[states.length], new float[states.length], attacks);
		assertEquals(2, SidePanelWeaponRows.ticks(view, 0));
		assertEquals(2, SidePanelWeaponRows.ticks(view, 1));
		assertEquals(5, SidePanelWeaponRows.ticks(view, 2));
	}

	@Test
	public void overAFightTheTicksAreTheTimeSpentAttacking()
	{
		// Every tick the timeline drew as an attack or cooldown is one weapon's, once.
		final SidePanelModel.Input in = SidePanelExamples.leviathanWeapons();
		int sum = 0;
		for (SidePanelModel.WeaponRow w : SidePanelModel.weaponRows(in.view))
		{
			sum += w.ticks;
		}
		int attacking = 0;
		for (byte s : in.view.states)
		{
			attacking += s == FightTimeline.ATTACK || s == FightTimeline.SPECIAL || s == FightTimeline.COOLDOWN ? 1 : 0;
		}
		assertEquals(attacking, sum);
	}

	@Test
	public void theCachedSumsCountTheSameTicks()
	{
		// The panel keeps the rows' sums between snapshots (T-046): the same ticks as adding up the whole list.
		final SidePanelModel.Input in = SidePanelExamples.leviathanWeapons();
		final List<SidePanelModel.WeaponRow> cached = new SidePanelWeaponRows().rows("key", in.view);
		for (SidePanelModel.WeaponRow w : cached)
		{
			assertEquals(w.name, weapon(in, w.name, w.special).ticks, w.ticks);
		}
	}

	// ---- the rows ----

	@Test
	public void eachWeaponRowShowsItsDpsAgainstExpected()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.sireWeapons());
		final PanelSnapshot.Source ember = row(tab, "Emberlight", false);
		// 620 over 120 ticks (72 s): 8.6; expected 546 over the same: 7.6.
		assertEquals("8.6", ember.dps);
		assertEquals("7.6", ember.expectedDps);
		// Eric's forms, longest first: "expected" spelt out wherever it fits, as the spec row's "expected 44" is.
		assertEquals("30 attacks · 8.6 DPS (expected 7.6)", ember.sub);
		assertEquals(Arrays.asList("8.6 DPS (expected 7.6)", "8.6 DPS (exp 7.6)", "8.6 DPS"), ember.subShort);
		assertEquals("75%", ember.share);
		final PanelSnapshot.Source bow = row(tab, "Scorching bow", false);
		assertEquals("5.4", bow.dps);
		assertEquals("5.6", bow.expectedDps);
		final PanelSnapshot.FightTab lev = SidePanelModel.fight(SidePanelExamples.leviathanWeapons());
		assertNotNull(row(lev, "Twisted bow", false).dps);
		assertNotNull(row(lev, "Toxic blowpipe", false).dps);
	}

	@Test
	public void specialsThrallDotAndRecoilHaveNone()
	{
		final PanelSnapshot.FightTab tab = SidePanelModel.fight(SidePanelExamples.sireWeapons());
		final PanelSnapshot.Source spec = row(tab, "Emberlight", true);
		assertNull(spec.dps);
		assertEquals("2 specs · expected 44", spec.sub);
		assertTrue(spec.tooltip, spec.tooltip.contains("no DPS: a special is spent by energy, not time"));
		// The 2:17 kill has a thrall row.
		final PanelSnapshot.Source thrall = row(SidePanelModel.fight(SidePanelExamples.dead()), "Thrall", false);
		assertNotNull(thrall);
		assertNull(thrall.dps);
		assertNull(thrall.tooltip);
	}

	@Test
	public void underTheStatsWarningNoExpected()
	{
		final SidePanelModel.Input in = SidePanelExamples.sireWeapons();
		in.warning = "two versions share this HP";
		final PanelSnapshot.Source ember = row(SidePanelModel.fight(in), "Emberlight", false);
		assertEquals("8.6", ember.dps);
		assertNull(ember.expectedDps);
		assertEquals("30 attacks · 8.6 DPS", ember.sub);
		assertEquals(Arrays.asList("8.6 DPS", "8.6 DPS", "8.6 DPS"), ember.subShort);
		assertFalse(ember.tooltip, ember.tooltip.contains("expected"));
	}

	@Test
	public void aRowWithNoTicksHasNoDps()
	{
		// Rows read without their view (no ticks): no DPS rather than a wrong one.
		final SidePanelModel.WeaponRow w = new SidePanelModel.WeaponRow();
		w.name = "Abyssal tentacle";
		w.attacks = 10;
		w.damage = 200;
		final SidePanelModel.Input in = SidePanelExamples.dead();
		in.weaponRows = new ArrayList<>(java.util.Collections.singletonList(w));
		in.view = null;
		final PanelSnapshot.Source s = SidePanelModel.fight(in).damage.rows.get(0);
		assertEquals("Abyssal tentacle", s.name);
		assertNull(s.dps);
	}

	@Test
	public void anOldKillHasNoDamageRows()
	{
		// A kill from before this RuneLite session has no timeline, so its Damage card is the "kept only for fights since
		// RuneLite started" note: no rows, no DPS.
		final SidePanelHistoryTab.Source source = SidePanelPictures.source();
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null, source);
		final SidePanelHistoryTab tab = panel.historyTab();
		tab.refresh();
		final List<Fight> h = source.history();
		tab.open(h.get(h.size() - 1));
		assertTrue(tab.isShowingKill());
		assertTrue(all(tab, SidePanelParts.SourceRowView.class).isEmpty());
	}

	// ---- the tooltip ----

	@Test
	public void theTooltipHasTheDetails()
	{
		final String tip = row(SidePanelModel.fight(SidePanelExamples.sireWeapons()), "Emberlight", false).tooltip;
		assertTrue(tip, tip.startsWith("<html><b>Emberlight</b>"));
		// 30 attacks, 25 landed: the pattern has a miss in every 6; expected to land 82% of the time less the 0 rolls.
		assertTrue(tip, tip.contains("30 attacks, 25 landed (83%, expected "));
		assertTrue(tip, tip.contains("average hit 24.8"));
		assertTrue(tip, tip.contains("biggest hit 40 of max 46"));
		assertTrue(tip, tip.contains("damage 620, expected 546 (75% of your damage)"));
		assertTrue(tip, tip.contains("8.6 DPS (expected 7.6) over 1:12 attacking, 120 ticks"));
	}

	@Test
	public void theRowsTooltipAndHover()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.sireWeapons())));
		final List<SidePanelParts.SourceRowView> rows = all(panel.fightTab(), SidePanelParts.SourceRowView.class);
		final SidePanelParts.SourceRowView ember = rows.get(0);
		assertTrue(ember.getToolTipText().contains("Emberlight"));
		ember.dispatchEvent(new java.awt.event.MouseEvent(ember, java.awt.event.MouseEvent.MOUSE_ENTERED, 0L, 0, 2, 2, 0,
			false));
		assertTrue(ember.hovered());
		ember.dispatchEvent(new java.awt.event.MouseEvent(ember, java.awt.event.MouseEvent.MOUSE_EXITED, 0L, 0, 2, 2, 0,
			false));
		assertFalse(ember.hovered());
		// The thrall's row has no details, so no hover.
		final PvmPerformancePanel other = new PvmPerformancePanel(null, null, null, null);
		other.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.dead())));
		final List<SidePanelParts.SourceRowView> withThrall = all(other.fightTab(), SidePanelParts.SourceRowView.class);
		final SidePanelParts.SourceRowView thrall = withThrall.get(withThrall.size() - 1);
		assertNull(thrall.getToolTipText());
		thrall.dispatchEvent(new java.awt.event.MouseEvent(thrall, java.awt.event.MouseEvent.MOUSE_ENTERED, 0L, 0, 2, 2, 0,
			false));
		assertFalse(thrall.hovered());
	}

	// ---- fit, and no lag ----

	/** The card's weapon rows' sub-lines as drawn, after a render at {@code scale} with Smooth on or off. */
	private static List<String> drawn(SidePanelModel.Input in, boolean smooth, double scale, boolean larger)
	{
		final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
		p.apply(new PanelSnapshot(SidePanelModel.fight(in)));
		p.setText(larger, smooth);
		try
		{
			SidePanelPictures.draw(p.tabs(), p.fightTab(), scale);
			// Twice: a row that found a shorter form than the others asked them to draw again.
			SidePanelPictures.draw(p.tabs(), p.fightTab(), scale);
			final List<String> out = new ArrayList<>();
			for (SidePanelParts.SourceRowView row : all(p.fightTab(), SidePanelParts.SourceRowView.class))
			{
				for (SidePanelText t : all(row, SidePanelText.class))
				{
					assertFalse(smooth + " " + scale + ": " + t.getText() + " => " + t.lastShown(),
						t.lastShown() != null && t.lastShown().contains(LedgerRows.CUT));
				}
				out.add(all(row, SidePanelText.class).get(2).lastShown());
			}
			return out;
		}
		finally
		{
			SidePanelStyle.setText(false, false);
		}
	}

	@Test
	public void everyRowFitsAt1xAnd2xInBothFonts()
	{
		for (boolean smooth : new boolean[]{false, true})
		{
			for (double scale : new double[]{1, 2})
			{
				drawn(SidePanelExamples.sireWeapons(), smooth, scale, false);
				drawn(SidePanelExamples.leviathanWeapons(), smooth, scale, false);
			}
		}
	}

	@Test
	public void theCardsWeaponRowsShareOneForm()
	{
		// Each card's weapon rows are drawn in one form, the longest that fits them all: "30 attacks · 8.6 DPS
		// (expected 7.6)" fits the Emberlight row but not the Scorching bow's, so neither has the attacks.
		for (boolean smooth : new boolean[]{false, true})
		{
			for (double scale : new double[]{1, 2})
			{
				for (boolean larger : new boolean[]{false, true})
				{
					for (SidePanelModel.Input in : Arrays.asList(SidePanelExamples.sireWeapons(),
						SidePanelExamples.leviathanWeapons()))
					{
						final PanelSnapshot.FightTab tab = SidePanelModel.fight(in);
						final List<String> shown = drawn(in, smooth, scale, larger);
						int form = -1;
						for (int i = 0; i < tab.damage.rows.size(); i++)
						{
							final PanelSnapshot.Source s = tab.damage.rows.get(i);
							if (s.dps == null)
							{
								continue;
							}
							final List<String> forms = new ArrayList<>();
							forms.add(s.sub);
							forms.addAll(s.subShort);
							final int f = forms.indexOf(shown.get(i));
							assertTrue(shown.get(i), f >= 0);
							assertTrue(smooth + " " + scale + " " + larger + ": " + shown, form < 0 || form == f);
							form = f;
						}
					}
				}
			}
		}
	}

	@Test
	public void builtOnceAndUpdatedInPlace()
	{
		final PvmPerformancePanel panel = new PvmPerformancePanel(null, null, null, null);
		panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.sireWeapons())));
		final SidePanelFightTab tab = panel.fightTab();
		SidePanelPictures.draw(panel.tabs(), tab, 1);
		final int builds = tab.builds();
		final int layouts = SidePanelParts.relayouts;
		for (int i = 0; i < 10; i++)
		{
			panel.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.sireWeapons())));
		}
		assertEquals(builds, tab.builds());
		assertEquals(layouts, SidePanelParts.relayouts);
	}

	private static <T> List<T> all(Container c, Class<T> type)
	{
		final List<T> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			if (type.isInstance(k))
			{
				out.add(type.cast(k));
			}
			if (k instanceof Container)
			{
				out.addAll(all((Container) k, type));
			}
		}
		return out;
	}
}
