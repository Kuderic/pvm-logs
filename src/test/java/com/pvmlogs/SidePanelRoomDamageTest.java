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
	public void withoutTheRulesTheTooltipIsGeneric()
	{
		assertEquals("Damage Araxxor took from the room. Not yours, and not part of your luck.",
			SidePanelModel.roomDamageTip("Araxxor", Collections.emptyList()));
		assertTrue(SidePanelModel.roomDamageSources(Collections.singletonList(araxxor(160, 0L)), null).isEmpty());
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
