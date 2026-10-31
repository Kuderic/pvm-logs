package com.pvmlogs;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * T-128: a member's click-in is their Fight tab, from the {@link LuckVsSkillRoom} they send as their room ends (live,
 * what their update can fill); and the party chart's one x axis, the room's clock, so your line and your echo's
 * coincide. Fights are played through the plugin's own calls ({@link SidePanelTimelineExamples}).
 */
public class SidePanelMemberFightTest
{
	private static final Gson GSON = new Gson();

	/** A 4-minute tentacle fight: 400 ticks, three claw specials, idle and eating spans. */
	private static SidePanelTimelineExamples.Built longRoom()
	{
		return SidePanelTimelineExamples.fight(400, new int[]{5, 40, 80}, new int[]{60, 4, 150, 6, 300, 3},
			new int[]{100, 3, 220, 5}, 2_600, 2_450, 410, 380, 140);
	}

	private static LuckVsSkillRoom roundTrip(LuckVsSkillRoom m)
	{
		return GSON.fromJson(GSON.toJson(m), LuckVsSkillRoom.class);
	}

	private static List<String> texts(PanelSnapshot.FightTab t)
	{
		final List<String> out = new ArrayList<>();
		for (PanelSnapshot.Tile x : t.tiles)
		{
			out.add("tile " + x.label.text + " | " + x.value.text + " | " + x.sub.text);
		}
		if (t.budget != null)
		{
			for (PanelSnapshot.BudgetRow r : t.budget.rows)
			{
				out.add("budget " + r.label.text + " | " + r.value.text);
			}
		}
		if (t.execution != null)
		{
			out.add("execution " + t.execution.hint.text);
			for (PanelSnapshot.Part p : t.execution.parts)
			{
				out.add("part " + p.label.text + " | " + p.value.text);
			}
		}
		if (t.curve != null)
		{
			out.add("curve " + t.curve.headerValue.text + " | " + t.curve.caption);
		}
		if (t.damage != null)
		{
			out.add("damage " + t.damage.hint.text);
			for (PanelSnapshot.Source s : t.damage.rows)
			{
				out.add("source " + s.name + " | " + s.sub + " | " + s.damage + " | " + s.share);
			}
		}
		for (PanelSnapshot.Rows card : Arrays.asList(t.details, t.taken))
		{
			if (card != null)
			{
				out.add("card " + card.hint.text);
				for (PanelSnapshot.Row r : card.rows)
				{
					out.add("row " + r.left.text + " | " + r.right.text);
				}
			}
		}
		if (t.chart != null)
		{
			out.add("chart " + t.chart.end + " | " + t.chart.youLegend + " | " + t.chart.expectedLegend + " | lost "
				+ t.chart.lost + " | eating " + t.chart.eating);
		}
		return out;
	}

	@Test
	public void aMembersViewIsTheFightTabTheySaw()
	{
		final SidePanelTimelineExamples.Built b = longRoom();
		final SidePanelModel.Input theirs = SidePanelMemberFight.input(b.room, b.view(), null, true);
		final PanelSnapshot.FightTab sender = SidePanelModel.fight(theirs);
		final LuckVsSkillRoom sent = roundTrip(SidePanelMemberFight.message(b.room, b.view(), 0, null));
		final PanelSnapshot.FightTab receiver = SidePanelModel.fight(SidePanelMemberFight.decode(sent.f));
		// Every tile, Where the time went, Execution, Luck, Damage, Details and Damage taken, word for word.
		assertEquals(String.join("\n", texts(sender)), String.join("\n", texts(receiver)));
		assertEquals(sender.structure(), receiver.structure());
		// The tick strip exactly; the chart's series within the 5-tick sampling's interpolation.
		assertArrayEquals(sender.chart.states, receiver.chart.states);
		assertArrayEquals(sender.chart.styles, receiver.chart.styles);
		for (int t = 0; t < sender.chart.length; t += LuckVsSkillRoom.Chart.STEP)
		{
			assertEquals("tick " + t, sender.chart.damage[t], receiver.chart.damage[t], 0.51);
			assertEquals("tick " + t, sender.chart.expected[t], receiver.chart.expected[t], 0.01);
		}
		assertEquals(sender.chart.damage[sender.chart.length - 1], receiver.chart.damage[receiver.chart.length - 1], 0.51);
	}

	@Test
	public void theFightTabsExamplesComeThroughWordForWord()
	{
		for (SidePanelModel.Input in : Arrays.asList(SidePanelExamples.dead(), SidePanelExamples.ruledKill(true),
			SidePanelExamples.leviathanKill(), SidePanelExamples.sireEmberlight(), SidePanelExamples.luckSaved(15),
			SidePanelExamples.twoHammersOneLanded(), SidePanelExamples.unscoredAdds()))
		{
			in.ended = true;
			final LuckVsSkillRoom m = new LuckVsSkillRoom();
			m.r = in.name;
			m.f = SidePanelMemberFight.encode(in);
			final SidePanelModel.Input back = SidePanelMemberFight.decode(roundTrip(m).f);
			// The Fight tab's chart and weapon rows are the timeline's, sent as a member's; the rest word for word.
			in.view = null;
			in.weaponRows = null;
			back.view = null;
			back.weaponRows = null;
			in.charts = back.charts = false;
			assertEquals(in.name, String.join("\n", texts(SidePanelModel.fight(in))),
				String.join("\n", texts(SidePanelModel.fight(back))));
		}
	}

	@Test
	public void theMessageIsSmallVersionTolerantAndSaysNoName()
	{
		final SidePanelTimelineExamples.Built b = longRoom();
		final String json = GSON.toJson(SidePanelMemberFight.message(b.room, b.view(), 0, null));
		System.out.println("T-128 room message, a 4-minute room: " + json.length() + " bytes");
		// RuneLite's client puts no limit on a party message (WSClient sends it as it is); the server's isn't public, so
		// it's kept to a few KB.
		assertTrue(String.valueOf(json.length()), json.length() < 4_000);
		// Fields a newer build adds are ignored; fields an older build never sent read as their defaults.
		final LuckVsSkillRoom newer = GSON.fromJson(json.replace("{\"v\":1,", "{\"v\":2,\"zz\":[1,2],"),
			LuckVsSkillRoom.class);
		assertEquals(2, newer.v);
		assertNotNull(newer.f);
		final LuckVsSkillRoom older = GSON.fromJson("{\"v\":1,\"r\":\"Vardorvis\",\"rs\":5,\"f\":{\"n\":\"Vardorvis\"}}",
			LuckVsSkillRoom.class);
		final SidePanelModel.Input in = SidePanelMemberFight.decode(older.f);
		assertFalse(in.charts);
		assertNotNull(SidePanelModel.fight(in));
		// Its name is the wire's: RuneLite keys a party message by it.
		assertEquals("LuckVsSkillRoom", LuckVsSkillRoom.class.getSimpleName());
		assertFalse(json, json.contains("\"name\""));
	}

	@Test
	public void midFightAMembersViewIsWhatTheirUpdateFills()
	{
		final SidePanelTimelineExamples.Built b = longRoom();
		final LuckVsSkillUpdate u = GSON.fromJson(GSON.toJson(PartyShare.build(LuckVsSkillUpdate.LIVE, b.room,
			b.view().attacks, 0)), LuckVsSkillUpdate.class);
		final SidePanelModel.Input in = SidePanelMemberFight.live(u, SidePanelPartyExamples::itemName);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertEquals(PanelSnapshot.State.LIVE, t.state);
		assertNotNull(t.tiles);
		assertNotNull(t.execution);
		assertNotNull(t.damage);
		// No chart or strip in it (the party chart under it has their line), nor the after-room cards.
		assertNull(t.chart);
		assertNull(t.budget);
		assertNull(t.details);
		// Uptime, Execution and its parts are the sender's own, from what the update sends.
		final LedgerRows.Numbers sender = new LedgerRows.Numbers(LedgerRows.of(b.room));
		final LedgerRows.Numbers member = new LedgerRows.Numbers(in.scope);
		assertEquals(sender.uptime, member.uptime, 0.01);
		assertEquals(sender.executionLive, member.executionLive, 0.01);
		assertEquals(sender.damage, member.damage);
		assertEquals(sender.dps, member.dps, 1e-9);
	}

	/** The party chart's one axis: an update every 5 ticks puts your echo's points on your own line. */
	@Test
	public void yourLineAndYourEchosCoincide()
	{
		final int[] clock = {0};
		final FightTimeline.Store store = new FightTimeline.Store();
		store.setClock(() -> clock[0]);
		final Fight f = new Fight("Araxxor", NpcIds.GOBLIN, 1, 1000, 0L, null, 0);
		store.attach(f);
		final FightTimeline line = store.of(f);
		final Encounter room = new Encounter("Araxxor", null, 0L);
		room.add(f);
		final List<PartyBoard.Point> echo = new ArrayList<>();
		int cooldown = 0;
		for (int t = 0; t < 200; t++)
		{
			clock[0] = t;
			if (t >= 60 && t < 72)
			{
				// Idle: scored ticks stop, the room's clock doesn't.
				f.recordTickLost(false);
				cooldown = 0;
			}
			else if (cooldown > 0)
			{
				f.recordTickSpent();
				cooldown--;
			}
			else
			{
				f.recordAttackMade(false, 1.0, false);
				line.describeLatest("Twisted bow", ItemID.TWISTED_BOW, null, FightTimeline.Style.RANGED, t);
				f.recordExpected(60, 0.6, 20, 200);
				f.recordAttackResolved(true, true, 1, 1, 0, 0);
				if (t % 3 != 0)
				{
					f.recordDamageDealt(30 + t % 17, 0L, true);
					line.attributeSplat(Integer.MIN_VALUE);
				}
				cooldown = 4;
			}
			if (t % PartyShare.INTERVAL_TICKS == 0)
			{
				final LuckVsSkillUpdate u = PartyShare.build(LuckVsSkillUpdate.LIVE, room, line.attacks(), 0);
				echo.add(new PartyBoard.Point(u.st, u.d, u.e, u.ct));
			}
		}
		final int[][] you = SidePanelParty.series(FightTimeline.room(room, store).damage);
		final int[][] theirs = SidePanelParty.series(echo);
		// Every echo point lies on your line: the same damage at the same tick of the room's clock.
		for (int i = 0; i < theirs[0].length; i++)
		{
			final int x = theirs[0][i];
			int at = 0;
			for (int k = 0; k < you[0].length && you[0][k] <= x; k++)
			{
				at = you[1][k];
			}
			assertEquals("tick " + x, theirs[1][i], at);
		}
		// Drawn by the room's clock, which they carry.
		assertEquals(echo.get(echo.size() - 1).clockTicks, echo.get(echo.size() - 1).x());
		// An older sender without the clock is drawn by its scored ticks, as before.
		assertEquals(7, new PartyBoard.Point(7, 10, 9).x());
	}

	@Test
	public void theBoardKeepsEachMembersRoomFights()
	{
		final PartyBoard board = new PartyBoard();
		final SidePanelTimelineExamples.Built b = longRoom();
		final LuckVsSkillRoom m = SidePanelMemberFight.message(b.room, b.view(), 0, null);
		board.acceptRoom(7, m, null, 100L);
		final PartyBoard.View v = board.view(id -> "Moss Pike").get(0);
		assertTrue(v.fights.containsKey(m.roomKey()));
		final long before = board.revision();
		board.acceptRoom(7, null, null, 101L);
		assertEquals(before, board.revision());
		board.clear();
		assertTrue(board.view(null).isEmpty());
	}
}
