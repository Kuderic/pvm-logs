package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * T-109: the damage a boss took from its own room (T-106's {@code Fight.roomDamage}: Araxxor's Ruptura explosions, his
 * own cleave) as a Details row after the fight, with a tooltip naming its sources (Eric, 2026-10-29 23:52).
 */
public class SidePanelRoomDamageTest
{
	private static BossRules rules;

	@BeforeClass
	public static void load()
	{
		rules = BossRules.load(new Gson(), null);
	}

	/** An Araxxor kill with {@code room} damage from his room. */
	static Encounter araxxor(int room, long start)
	{
		// 30 attacks of a halberd, 18 of them landing for 860 in all.
		final Fight f = new Fight("Araxxor", SidePanelBossesExamples.ARAXXOR, 70, 1020, start, null, 0);
		final int[] hits = {42, 0, 55, 31, 0, 47, 61, 0, 38, 52};
		int dealt = 0;
		for (int i = 0; i < 30; i++)
		{
			f.recordAttackMade(false, 0.9);
			f.recordExpected(61, 0.62, 27.5, 400);
			f.recordAttackResolved(true, true, 0.97, 1.0, 0.01, 0);
			final int hit = Math.min(hits[i % hits.length], 860 - dealt);
			if (hit > 0)
			{
				f.recordDamageDealt(hit, start + i * 3000L, true);
				dealt += hit;
			}
			for (int k = 0; k < 4; k++)
			{
				f.recordTickSpent();
			}
		}
		f.recordRoomDamage(room);
		f.end(true, start + 30 * 3000L);
		f.closeRoom();
		final Encounter e = new Encounter("Araxxor", null, start);
		e.add(f);
		return e;
	}

	static SidePanelModel.Input input(Encounter room, boolean ended)
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = SidePanelExamples.ZONE;
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		in.roomDamageSources = SidePanelModel.roomDamageSources(Collections.singletonList(room), rules);
		in.ended = ended;
		in.killed = ended;
		in.endMillis = ended ? SidePanelExamples.at(15, 3) : 0;
		return in;
	}

	@Test
	public void afterAKillDetailsShowsTheRoomsDamageWithItsSources()
	{
		final PanelSnapshot.FightTab t = SidePanelModel.fight(input(araxxor(160, SidePanelExamples.at(15, 0)), true));
		final PanelSnapshot.Row row = row(t.details, SidePanelModel.ROOM_DMG);
		assertNotNull(row);
		assertEquals("160", row.right.text);
		assertEquals("Damage Araxxor took from the room: a Ruptura araxyte's explosion and his own cleave. Not yours, "
			+ "and not part of your luck.", row.tooltip);
		// Beside the other things that aren't your attacks: after boss healed's place, before eating.
		final List<String> labels = labels(t.details);
		assertTrue(labels.toString(), labels.indexOf(SidePanelModel.ROOM_DMG) >= 0);
	}

	@Test
	public void neverWhileTheFightRuns()
	{
		final PanelSnapshot.FightTab t = SidePanelModel.fight(input(araxxor(160, SidePanelExamples.at(15, 0)), false));
		assertNull(row(t.details, SidePanelModel.ROOM_DMG));
	}

	@Test
	public void killsWithoutRoomDamageAreUnchanged()
	{
		final SidePanelModel.Input in = input(araxxor(0, SidePanelExamples.at(15, 0)), true);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertNull(row(t.details, SidePanelModel.ROOM_DMG));
		// The same rows as before T-109.
		final PanelSnapshot.Rows before = SidePanelModel.details(in.scope, false, false);
		assertEquals(labels(before), labels(t.details));
		// A boss whose room deals nothing: the Sire's kill as ever.
		assertNull(row(SidePanelModel.fight(SidePanelExamples.sireEmberlight()).details, SidePanelModel.ROOM_DMG));
	}

	@Test
	public void anOldKillHasNone()
	{
		// Saved before T-106: no roomDamage in the file.
		final Fight old = new Gson().fromJson("{\"targetName\":\"Araxxor\",\"targetId\":13668,\"targetIndex\":1,"
			+ "\"maxHp\":1020,\"attempts\":10,\"ended\":true,\"targetDied\":true,\"closedRoom\":true}", Fight.class);
		assertEquals(0, old.getRoomDamage());
		final Encounter room = new Encounter("Araxxor", null, 0L);
		room.add(old);
		assertNull(row(SidePanelModel.fight(input(room, true)).details, SidePanelModel.ROOM_DMG));
	}

	@Test
	public void withoutTheRulesTheTooltipIsGeneric()
	{
		assertEquals("Damage Araxxor took from the room. Not yours, and not part of your luck.",
			SidePanelModel.roomDamageTip("Araxxor", Collections.emptyList()));
		assertTrue(SidePanelModel.roomDamageSources(Collections.singletonList(araxxor(160, 0L)), null).isEmpty());
	}

	@Test
	public void notForARun()
	{
		final PanelSnapshot.Rows d = SidePanelModel.details(SidePanelExamples.killScope(), false, true, 160, "Araxxor",
			Collections.emptyList());
		assertNull(row(d, SidePanelModel.ROOM_DMG));
	}

	@Test
	public void historysPickedKillShowsIt()
	{
		final Encounter room = araxxor(160, SidePanelExamples.at(15, 0));
		final List<Fight> history = new ArrayList<>(room.getFights());
		final Encounter rebuilt = SidePanelHistory.allRooms(history).get(0);
		final SidePanelModel.Input in = SidePanelHistory.savedKill(history, rebuilt, -1, SidePanelHistoryExamples.NOW,
			SidePanelExamples.ZONE, null, null);
		assertEquals(160, in.roomDamage);
		assertNotNull(row(SidePanelModel.fight(in).details, SidePanelModel.ROOM_DMG));
	}

	@Test
	public void theTripSumsItsKillsOnceNoFightRuns()
	{
		final SidePanelModel.Input in = SidePanelExamples.trip();
		SidePanelModel.fillTripRoomDamage(in, Arrays.asList(araxxor(160, SidePanelExamples.at(15, 0)),
			araxxor(120, SidePanelExamples.at(15, 5))), rules);
		assertEquals(280, in.roomDamage);
		final PanelSnapshot.Row row = row(SidePanelModel.trip(in).other, SidePanelModel.ROOM_DMG);
		assertNotNull(row);
		assertEquals("280", row.right.text);
		assertTrue(row.tooltip, row.tooltip.startsWith("Damage Araxxor took from the room: a Ruptura"));
		// While a fight runs: none.
		in.ended = false;
		assertNull(row(SidePanelModel.trip(in).other, SidePanelModel.ROOM_DMG));
	}

	@Test
	public void theTooltipIsOnTheRowsLine()
	{
		final PanelSnapshot.Row row = row(SidePanelModel.fight(input(araxxor(160, SidePanelExamples.at(15, 0)), true))
			.details, SidePanelModel.ROOM_DMG);
		final SidePanelParts.Line line = new SidePanelParts.Line(row);
		assertEquals(row.tooltip, line.getToolTipText());
		assertEquals(row.tooltip, line.left.getToolTipText());
		assertEquals(row.tooltip, line.right.getToolTipText());
	}

	private static PanelSnapshot.Row row(PanelSnapshot.Rows rows, String label)
	{
		if (rows == null)
		{
			return null;
		}
		for (PanelSnapshot.Row r : rows.rows)
		{
			if (r.left.text.equals(label))
			{
				return r;
			}
		}
		return null;
	}

	private static List<String> labels(PanelSnapshot.Rows rows)
	{
		final List<String> out = new ArrayList<>();
		if (rows != null)
		{
			for (PanelSnapshot.Row r : rows.rows)
			{
				out.add(r.left.text);
			}
		}
		return out;
	}
}
