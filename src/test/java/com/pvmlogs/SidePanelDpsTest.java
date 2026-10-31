package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * T-134 (Eric, 2026-10-31: "DPS should include thralls as part of your dps", then "All your damage"): every DPS counts
 * the weapon, thrall, recoil and vengeance and damage over time over the scored clock; compared with expected, the
 * same other damage is on both sides; Luck and Damage/expected stay the weapon's.
 */
public class SidePanelDpsTest
{
	private static PanelSnapshot.Tile tile(PanelSnapshot.FightTab t, String label)
	{
		for (PanelSnapshot.Tile tile : t.tiles)
		{
			if (tile.label.text.equals(label))
			{
				return tile;
			}
		}
		throw new AssertionError(label);
	}

	// ---- The tile ----

	@Test
	public void withAThrallTheTileCountsItAndSaysTheWeaponsAlone()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		// 688 weapon and 80 thrall over 2:16.8.
		assertEquals("5.6", tile(t, "DPS").value.text);
		assertEquals("weapon 5.0", tile(t, "DPS").sub.text);
		assertEquals("<html>5.6 DPS over 2:16 of scored fighting:<br>weapon 5.0, thrall 0.6.</html>", tile(t, "DPS").tooltip);
		// Luck and Damage/expected stay the weapon's against expected, the thrall beside them.
		assertEquals("688/778", tile(t, "Damage/expected").value.text);
		assertEquals("+80 thrall", tile(t, "Damage/expected").sub.text);
		assertEquals(-14.35, n.luck, 0.01);
	}

	@Test
	public void withAllThreeKindsTheTooltipListsEachPart()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) in.scope;
		s.reflectDamage = 41;
		s.dotDamage = 30;
		in.scoredReflect = 41;
		in.scoredDot = 30;
		in.scoredDotByKind = Collections.singletonMap("venom", 30);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		// (688 + 80 + 41 + 30) / 136.8.
		assertEquals("6.1", tile(t, "DPS").value.text);
		assertEquals("weapon 5.0", tile(t, "DPS").sub.text);
		assertEquals("<html>6.1 DPS over 2:16 of scored fighting:<br>weapon 5.0, thrall 0.6, recoil/veng 0.3, venom 0.2.</html>",
			tile(t, "DPS").tooltip);
	}

	@Test
	public void withoutOtherDamageItsTheWeaponsAndSaysSo()
	{
		final SidePanelModel.Input in = SidePanelExamples.dead();
		((LedgerExamples.FakeScope) in.scope).thrallDamage = 0;
		in.scoredThrall = 0;
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertEquals("5.0", tile(t, "DPS").value.text);
		assertEquals("weapon only", tile(t, "DPS").sub.text);
		assertNull(tile(t, "DPS").tooltip);
	}

	/** A party member's live panels: their update's o, its kinds unknown. */
	@Test
	public void aMembersLivePanelsCountTheirOtherDamage()
	{
		final SidePanelTimelineExamples.Built b = SidePanelPartyExamples.longRoom();
		final LuckVsSkillUpdate u = PartyShare.build(LuckVsSkillUpdate.LIVE, b.room, b.view().attacks, 0);
		assertEquals(80, u.o);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(SidePanelMemberFight.live(u, SidePanelPartyExamples::itemName));
		final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(b.room));
		assertEquals(LedgerRows.oneDecimal(n.dps), tile(t, "DPS").value.text);
		assertTrue(tile(t, "DPS").tooltip, tile(t, "DPS").tooltip.contains("thrall, recoil and DoT"));
	}

	// ---- Compared with expected: the same other damage on both sides ----

	@Test
	public void aRunsTilesAndTheOverlaysRowHaveTheOtherDamageOnBothSides()
	{
		final SidePanelTimelineExamples.Built b = SidePanelRunExamples.demonRun(12);
		b.fight.recordThrallDamage(120);
		final SidePanelModel.Input in = SidePanelRunExamples.live(b);
		final LedgerRows.Numbers n = new LedgerRows.Numbers(in.scope);
		assertEquals(120, n.other);
		assertEquals((n.damage + 120) / n.scoredClock, n.dps, 1e-9);
		assertEquals((n.expected + 120) / n.scoredClock, n.expectedDps, 1e-9);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertEquals(LedgerRows.oneDecimal(n.dps), tile(t, "DPS").value.text);
		assertEquals("expected " + LedgerRows.oneDecimal(n.expectedDps), tile(t, "DPS").sub.text);
		// The overlay's run row.
		final List<LedgerRows.Row> rows = LedgerRows.build(in.scope, LedgerRows.State.run(SidePanelRunExamples.DEMON, 12,
			190, false, false), new LedgerRows.Settings());
		boolean found = false;
		for (LedgerRows.Row r : rows)
		{
			if ("DPS".equals(r.left))
			{
				found = true;
				assertEquals(LedgerRows.oneDecimal(n.dps) + " (expected " + LedgerRows.oneDecimal(n.expectedDps) + ")",
					r.right);
			}
		}
		assertTrue(found);
	}

	@Test
	public void historysRunsCardCountsTheOtherDamageOnBothSides()
	{
		final List<Fight> fights = SidePanelRunExamples.runFights(SidePanelExamples.at(13, 0), 12, 12, 7);
		final List<Fight> without = SidePanelRunExamples.runFights(SidePanelExamples.at(13, 0), 12, 12, 7);
		for (Fight f : fights)
		{
			f.recordThrallDamage(10);
		}
		final SidePanelHistory.Card with = SidePanelHistory.runCard(SidePanelRunExamples.DEMON, rooms(fights));
		final SidePanelHistory.Card plain = SidePanelHistory.runCard(SidePanelRunExamples.DEMON, rooms(without));
		final LedgerRows.Numbers n = new LedgerRows.Numbers(LedgerRows.of(rooms(fights).get(0)));
		assertEquals(LedgerRows.oneDecimal((n.damage + 120) / n.scoredClock), dpsTile(with).value.text);
		assertEquals("expected " + LedgerRows.oneDecimal((n.expected + 120) / n.scoredClock), dpsTile(with).sub.text);
		assertEquals(LedgerRows.oneDecimal(n.damage / n.scoredClock), dpsTile(plain).value.text);
	}

	private static List<Encounter> rooms(List<Fight> oldestFirst)
	{
		final List<Fight> newest = new ArrayList<>(oldestFirst);
		Collections.reverse(newest);
		return SidePanelHistory.allRooms(newest);
	}

	private static PanelSnapshot.Tile dpsTile(SidePanelHistory.Card c)
	{
		for (PanelSnapshot.Tile t : c.tiles)
		{
			if (t.label.text.equals("DPS"))
			{
				return t;
			}
		}
		throw new AssertionError("no DPS tile");
	}

	// ---- The Party meter ----

	@Test
	public void theMeterCountsAMembersOtherDamageAndAnOlderSendersWeaponAlone()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.toa();
		in.members.get(2).figures.o = 410;
		final PanelSnapshot.PartyTab t = SidePanelParty.party(in);
		final PanelSnapshot.PartyRow brindle = t.row(22);
		// 1,990 weapon and 410 other.
		assertEquals(SidePanelParty.compact(2_400), brindle.dmg[0]);
		// Moss Pike from a sender before T-134: his weapon alone.
		assertEquals(0, in.members.get(1).figures.o);
		assertEquals(SidePanelParty.compact(3_420), t.row(21).dmg[0]);
		// The others: other players' splats less the members' weapon and other damage.
		assertEquals(Math.max(0, in.othersDamage - 3_420 - 2_400), SidePanelParty.others(in.othersDamage, in.members));
		assertFalse(SidePanelParty.OTHERS_NOTE.contains("thrall"));
	}

	@Test
	public void othersIsNeverNegative()
	{
		final SidePanelParty.Input in = SidePanelPartyExamples.toa();
		in.members.get(1).figures.o = 50_000;
		assertEquals(0, SidePanelParty.others(in.othersDamage, in.members));
		assertNull(SidePanelParty.party(in).row(-1));
	}

	@Test
	public void anOlderUpdateWithoutOReadsZero()
	{
		final com.google.gson.Gson gson = new com.google.gson.Gson();
		final LuckVsSkillUpdate older = gson.fromJson("{\"v\":1,\"k\":1,\"r\":\"Zebak\",\"d\":1990,\"st\":215}",
			LuckVsSkillUpdate.class);
		assertEquals(0, older.o);
		assertEquals(1990, older.all());
	}

	// ---- Fit ----

	@Test
	public void theTileFitsAtEverySize()
	{
		for (boolean larger : new boolean[]{false, true})
		{
			for (boolean smooth : new boolean[]{false, true})
			{
				for (double scale : new double[]{1, 2})
				{
					final PvmPerformancePanel p = new PvmPerformancePanel(null, null, null, null);
					p.setText(larger, smooth);
					try
					{
						final SidePanelModel.Input in = SidePanelExamples.dead();
						final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) in.scope;
						s.thrallDamage = 1_480;
						in.scoredThrall = 1_480;
						s.damage = 12_880;
						p.apply(new PanelSnapshot(SidePanelModel.fight(in)));
						SidePanelPictures.draw(p.tabs(), p.fightTab(), scale);
						for (Component c : all(p.fightTab()))
						{
							if (c instanceof SidePanelText && ((SidePanelText) c).lastShown() != null
								&& ((SidePanelText) c).getText().startsWith("weapon "))
							{
								final String shown = ((SidePanelText) c).lastShown();
								assertFalse(larger + " " + smooth + " " + scale + ": " + shown, shown.contains(LedgerRows.CUT));
							}
						}
					}
					finally
					{
						SidePanelStyle.setText(false, false);
					}
				}
			}
		}
	}

	private static List<Component> all(Container c)
	{
		final List<Component> out = new ArrayList<>();
		for (Component k : c.getComponents())
		{
			out.add(k);
			if (k instanceof Container)
			{
				out.addAll(all((Container) k));
			}
		}
		return out;
	}
}
