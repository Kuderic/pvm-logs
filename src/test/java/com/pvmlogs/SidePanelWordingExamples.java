package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-119: a trip like the one in Eric's screenshot (2026-10-30 09:11): Araxxor, heavy venom (868 against 719 from hits),
 * the room's damage on the boss, and warhammer and godsword specials. Illustrative figures. Shared by {@link
 * SidePanelWordingTest} and {@link SidePanelPictures}.
 */
final class SidePanelWordingExamples
{
	private SidePanelWordingExamples()
	{
	}

	static SidePanelModel.Input ericsTrip()
	{
		final SidePanelModel.Input in = SidePanelExamples.trip();
		final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) in.scope;
		s.damageTaken = 719;
		s.dotTaken = 868;
		s.dotTakenByKind = new LinkedHashMap<>();
		s.dotTakenByKind.put("venom", 868);
		s.biggestHitTaken = 44;
		s.damageTakenUnprotected = 112;
		in.hitsTaken = 301;
		in.missedHitsTaken = 52;
		in.roomDamage = 630;
		in.roomDamageSources = Arrays.asList("a Ruptura araxyte's explosion", "his own cleave");
		final List<PanelSnapshot.TripRow> rows = new ArrayList<>();
		rows.add(SidePanelExamples.tripRow("Araxxor", false, "2:41", "bottom 20%", LedgerRows.Colour.COST));
		rows.add(SidePanelExamples.tripRow("Araxxor", false, "2:22", "average", LedgerRows.Colour.NEUTRAL));
		rows.add(SidePanelExamples.tripRow("Araxxor", false, "2:09", "top 18%", LedgerRows.Colour.BETTER));
		rows.add(SidePanelExamples.tripRow("Araxxor", true, "1:04", "average", LedgerRows.Colour.NEUTRAL));
		rows.add(SidePanelExamples.tripRow("Araxxor", false, "2:30", "average", LedgerRows.Colour.NEUTRAL));
		in.tripRows = rows;
		in.drain = drains(true);
		return in;
	}

	/**
	 * The trip's drains: 630 expected damage added, the specials' luck, and the specials by weapon: 8 warhammer specials,
	 * 5 landed, and 3 godsword specials. With {@code timelines}, what this session's timelines expected of them.
	 */
	static SidePanelModel.Drain drains(boolean timelines)
	{
		final SidePanelModel.Drain d = SidePanelExamples.drain(-1, -1, -1, -1, 0, 630, 67.2, 41, 1.6);
		final Map<String, int[]> specs = new LinkedHashMap<>();
		specs.put("DRAGON_WARHAMMER", new int[]{8, 5});
		specs.put("BANDOS_GODSWORD", new int[]{3, 3});
		d.specs = specs;
		if (timelines)
		{
			final SidePanelModel.WeaponRow dwh = new SidePanelModel.WeaponRow();
			dwh.name = "Dragon warhammer";
			dwh.special = true;
			dwh.itemId = net.runelite.api.gameval.ItemID.DRAGON_WARHAMMER;
			dwh.attacks = 8;
			dwh.landed = 5;
			dwh.landedKnown = 8;
			dwh.landedExpected = 4.8;
			dwh.damage = 318;
			dwh.expected = 290;
			dwh.expectedKnown = true;
			final SidePanelModel.WeaponRow bgs = new SidePanelModel.WeaponRow();
			bgs.name = "Bandos godsword";
			bgs.special = true;
			bgs.itemId = net.runelite.api.gameval.ItemID.BGS;
			bgs.attacks = 3;
			bgs.landed = 3;
			bgs.landedKnown = 3;
			bgs.landedExpected = 2.5;
			bgs.damage = 152;
			bgs.expected = 130;
			bgs.expectedKnown = true;
			d.specRows = Arrays.asList(dwh, bgs);
		}
		return d;
	}
}
