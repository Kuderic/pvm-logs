package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JComponent;
import net.runelite.api.gameval.NpcID;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * T-131: Details splits the room's damage by source (Eric, 2026-10-31 10:58: "instead of room dmg 184 we can show
 * araxxor step under and exploding araxyte damage sources"): {@code hit himself} for Araxxor's enraged cleave, {@code
 * araxyte explosions} for the Ruptura araxytes, from {@code Fight.roomDamageBySource}; a kill saved before keeps T-109's
 * one {@code room dmg} total.
 */
public class RoomDamageBySourceTest
{
	private static final String EXPLOSIONS = "araxyte explosions";
	private static final String HIMSELF = "hit himself";
	private static final String EXPLOSION = "a Ruptura araxyte's explosion";
	private static final String CLEAVE = "his own cleave";

	private static BossRules rules;
	private static List<BossRules.RoomDamageRule> araxxor;

	@BeforeClass
	public static void load()
	{
		rules = BossRules.load(new Gson(), null);
		araxxor = rules.forNpc(NpcID.ARAXXOR).roomDamage;
	}

	/** An Araxxor kill with its room splats booked as the splat branch books them: {amount, hp left before} each. */
	private static Encounter kill(boolean enraged, int[]... splats)
	{
		final Encounter room = SidePanelRoomDamageTest.araxxor(0, SidePanelExamples.at(15, 0));
		final Fight f = room.getFights().get(0);
		for (int[] s : splats)
		{
			final BossRules.RoomDamageRule r = RoomDamage.ruleFor(araxxor, s[0], s[1], state -> enraged);
			f.recordRoomDamage(s[0], r == null ? null : r.name);
		}
		return room;
	}

	private static SidePanelModel.Input input(Encounter room)
	{
		final SidePanelModel.Input in = SidePanelRoomDamageTest.input(room, true);
		SidePanelModel.fillRoomDamageSources(in, Collections.singletonList(room), rules);
		return in;
	}

	// ---- booking ----

	@Test
	public void aCleaveAndAnExplosionOnOneTickGoToTheirOwnSources()
	{
		final Fight f = new Fight("Araxxor", NpcID.ARAXXOR, 1, 1020, 0L, null, 0);
		// Enraged, 300 left: his cleave's 10 and an explosion's 80 on the same tick.
		for (int amount : new int[] {10, 80})
		{
			final BossRules.RoomDamageRule r = RoomDamage.ruleFor(araxxor, amount, 300, "enraged"::equals);
			assertNotNull(r);
			f.recordRoomDamage(amount, r.name);
		}
		assertEquals(90, f.getRoomDamage());
		final Map<String, Integer> want = new LinkedHashMap<>();
		want.put(CLEAVE, 10);
		want.put(EXPLOSION, 80);
		assertEquals(want, f.getRoomDamageBySource());
		assertEquals(" (hit himself 10, araxyte explosions 80)", RoomDamage.reconcileSplit(f.getRoomDamageBySource(),
			araxxor));
	}

	@Test
	public void whichRuleASplatGoesUnder()
	{
		assertEquals(EXPLOSION, RoomDamage.ruleFor(araxxor, 64, 500, s -> true).name);
		// Not enraged, an 8-12 is no rule's: under other.
		assertNull(RoomDamage.ruleFor(araxxor, 10, 500, s -> false));
		// In range of one and a capped kill of the other: the one in range.
		assertEquals(CLEAVE, RoomDamage.ruleFor(araxxor, 10, 10, s -> true).name);
		// A capped kill under both (the 10:35 log's [2170], 6 with 6 left, on his cleave's 4 ticks): the smaller rule.
		assertEquals(CLEAVE, RoomDamage.ruleFor(araxxor, 6, 6, s -> true).name);
		// Under the explosion's least but over the cleave's most: the explosion, capped.
		assertEquals(EXPLOSION, RoomDamage.ruleFor(araxxor, 22, 22, s -> true).name);
		final Fight f = new Fight("Araxxor", NpcID.ARAXXOR, 1, 1020, 0L, null, 0);
		f.recordRoomDamage(10, null);
		assertEquals(Collections.singletonMap(RoomDamage.OTHER, 10), f.getRoomDamageBySource());
	}

	@Test
	public void everyRuleHasALabelAndATip()
	{
		// Every roomDamage rule in the bundled file, read as it ships.
		final com.google.gson.JsonObject file = new Gson().fromJson(new java.io.InputStreamReader(
			BossRules.class.getResourceAsStream("boss-rules.json"), java.nio.charset.StandardCharsets.UTF_8),
			com.google.gson.JsonObject.class);
		int n = 0;
		for (com.google.gson.JsonElement b : file.getAsJsonArray("bosses"))
		{
			final com.google.gson.JsonObject boss = b.getAsJsonObject();
			if (!boss.has("roomDamage"))
			{
				continue;
			}
			for (com.google.gson.JsonElement e : boss.getAsJsonArray("roomDamage"))
			{
				final BossRules.RoomDamageRule r = new Gson().fromJson(e, BossRules.RoomDamageRule.class);
				assertNotNull(r.name, r.label);
				assertFalse(r.name, r.label.isEmpty());
				assertNotNull(r.name, r.tip);
				n++;
			}
		}
		// Araxxor's two (T-131) and the Judge of Yama's fireball on itself (T-137).
		assertEquals(3, n);
	}

	// ---- Details ----

	@Test
	public void detailsShowsASourceARowLargestFirst()
	{
		// The 10:35 log's kill 6: explosion 80, and his cleave's 104 (the 6 at the end, capped).
		final Encounter room = kill(true, new int[] {80, 900}, new int[] {9, 500}, new int[] {9, 400}, new int[] {10, 390},
			new int[] {11, 380}, new int[] {11, 370}, new int[] {9, 300}, new int[] {8, 200}, new int[] {12, 100},
			new int[] {11, 80}, new int[] {8, 50}, new int[] {6, 6});
		final PanelSnapshot.FightTab t = SidePanelModel.fight(input(room));
		final List<String> labels = labels(t.details);
		assertNull(row(t.details, SidePanelModel.ROOM_DMG));
		assertTrue(labels.toString(), labels.indexOf(HIMSELF) >= 0 && labels.indexOf(HIMSELF) < labels.indexOf(EXPLOSIONS));
		assertEquals("104", row(t.details, HIMSELF).right.text);
		assertEquals("80", row(t.details, EXPLOSIONS).right.text);
		assertEquals("Araxxor's enraged cleave hitting himself (when you stand under him, Eric's read): damage Araxxor "
			+ "took from the room. Not yours, and not part of your luck.", row(t.details, HIMSELF).tooltip);
		assertEquals("Ruptura araxytes exploding next to Araxxor: damage Araxxor took from the room. Not yours, and not "
			+ "part of your luck.", row(t.details, EXPLOSIONS).tooltip);
	}

	@Test
	public void oneSourceIsOneRowUnderItsLabel()
	{
		final PanelSnapshot.FightTab t = SidePanelModel.fight(input(kill(false, new int[] {80, 900}, new int[] {64, 800})));
		assertEquals("144", row(t.details, EXPLOSIONS).right.text);
		assertNull(row(t.details, HIMSELF));
		assertNull(row(t.details, SidePanelModel.ROOM_DMG));
	}

	@Test
	public void anOldKillKeepsItsTotal()
	{
		// Saved by a build with T-106 and before T-131: roomDamage, no split.
		final Fight old = new Gson().fromJson("{\"targetName\":\"Araxxor\",\"targetId\":13668,\"targetIndex\":1,"
			+ "\"maxHp\":1020,\"attempts\":10,\"ended\":true,\"targetDied\":true,\"closedRoom\":true,\"roomDamage\":184}",
			Fight.class);
		assertNull(old.getRoomDamageBySource());
		final Encounter room = new Encounter("Araxxor", null, 0L);
		room.add(old);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(input(room));
		assertEquals("184", row(t.details, SidePanelModel.ROOM_DMG).right.text);
		assertNull(row(t.details, HIMSELF));
		// Room damage no rule names, alone (a boss without rules): the total as before too.
		final PanelSnapshot.FightTab other = SidePanelModel.fight(input(kill(false, new int[] {10, 500})));
		assertEquals("10", row(other.details, SidePanelModel.ROOM_DMG).right.text);
		assertNull(row(other.details, RoomDamage.OTHER_LABEL));
		// Beside named sources it has its own row.
		final PanelSnapshot.FightTab mixed = SidePanelModel.fight(input(kill(false, new int[] {80, 900},
			new int[] {10, 500})));
		assertEquals("10", row(mixed.details, RoomDamage.OTHER_LABEL).right.text);
		assertEquals("80", row(mixed.details, EXPLOSIONS).right.text);
	}

	@Test
	public void historyKeepsTheSplitAndReadsOldFilesWithout()
	{
		final Gson gson = new Gson();
		final Fight f = kill(true, new int[] {80, 900}, new int[] {10, 500}).getFights().get(0);
		final Fight back = gson.fromJson(gson.toJson(new Fight[] {f}), Fight[].class)[0];
		assertEquals(f.getRoomDamageBySource(), back.getRoomDamageBySource());
		assertEquals(90, back.getRoomDamage());
		final JsonObject without = gson.toJsonTree(f).getAsJsonObject();
		without.remove("roomDamageBySource");
		final Fight old = gson.fromJson("[" + without + "]", Fight[].class)[0];
		assertNull(old.getRoomDamageBySource());
		assertEquals(90, old.getRoomDamage());
		// History's picked kill shows the saved split.
		final List<Fight> history = new ArrayList<>(Collections.singletonList(back));
		final Encounter rebuilt = SidePanelHistory.allRooms(history).get(0);
		final SidePanelModel.Input in = SidePanelHistory.savedKill(history, rebuilt, -1, SidePanelHistoryExamples.NOW,
			SidePanelExamples.ZONE, null, null);
		SidePanelModel.fillRoomDamageSources(in, Collections.singletonList(rebuilt), rules);
		final PanelSnapshot.FightTab t = SidePanelModel.fight(in);
		assertEquals("80", row(t.details, EXPLOSIONS).right.text);
		assertEquals("10", row(t.details, HIMSELF).right.text);
	}

	// ---- the Trip tab ----

	@Test
	public void theTripSumsByLabel()
	{
		final SidePanelModel.Input in = SidePanelExamples.trip();
		final Fight old = new Gson().fromJson("{\"targetName\":\"Araxxor\",\"targetId\":13668,\"targetIndex\":9,"
			+ "\"maxHp\":1020,\"attempts\":10,\"ended\":true,\"targetDied\":true,\"closedRoom\":true,\"roomDamage\":50}",
			Fight.class);
		final Encounter saved = new Encounter("Araxxor", null, 0L);
		saved.add(old);
		SidePanelModel.fillTripRoomDamage(in, Arrays.asList(kill(true, new int[] {80, 900}, new int[] {10, 500}),
			kill(true, new int[] {64, 900}, new int[] {12, 500}, new int[] {8, 400}), saved), rules);
		assertEquals(224, in.roomDamage);
		final PanelSnapshot.TripTab trip = SidePanelModel.trip(in);
		assertEquals("144", row(trip.other, EXPLOSIONS).right.text);
		assertEquals("30", row(trip.other, HIMSELF).right.text);
		// The old kill's total, with no split, on a row of its own.
		assertEquals("50", row(trip.other, SidePanelModel.ROOM_DMG).right.text);
		in.ended = false;
		assertNull(row(SidePanelModel.trip(in).other, EXPLOSIONS));
	}

	// ---- a member's panels on the Party tab ----

	@Test
	public void theRoomMessageCarriesTheSplit()
	{
		final Gson gson = new Gson();
		final SidePanelModel.Input in = input(kill(true, new int[] {80, 900}, new int[] {10, 500}));
		final LuckVsSkillRoom m = new LuckVsSkillRoom();
		m.r = "Araxxor";
		m.f = SidePanelMemberFight.encode(in);
		final LuckVsSkillRoom sent = gson.fromJson(gson.toJson(m), LuckVsSkillRoom.class);
		final PanelSnapshot.FightTab theirs = SidePanelModel.fight(SidePanelMemberFight.decode(sent.f));
		assertEquals("80", row(theirs.details, EXPLOSIONS).right.text);
		assertEquals("10", row(theirs.details, HIMSELF).right.text);
		assertEquals(row(SidePanelModel.fight(in).details, HIMSELF).tooltip, row(theirs.details, HIMSELF).tooltip);
		// An older sender: rd and rds only. Its total shows as one row, as before.
		final JsonObject older = gson.toJsonTree(m).getAsJsonObject();
		older.getAsJsonObject("f").remove("rdx");
		final LuckVsSkillRoom old = gson.fromJson(older, LuckVsSkillRoom.class);
		final PanelSnapshot.FightTab oldView = SidePanelModel.fight(SidePanelMemberFight.decode(old.f));
		assertEquals("90", row(oldView.details, SidePanelModel.ROOM_DMG).right.text);
		assertNull(row(oldView.details, EXPLOSIONS));
		// What the plugin sends at the room's end (SidePanelMemberFight.message, from the room and the rules).
		final LuckVsSkillRoom fromPlugin = gson.fromJson(gson.toJson(SidePanelMemberFight.message(
			kill(true, new int[] {80, 900}, new int[] {10, 500}), (FightTimeline.View) null, 0, rules)), LuckVsSkillRoom.class);
		assertEquals(2, fromPlugin.f.rdx.size());
		assertEquals(Arrays.asList(EXPLOSION, CLEAVE), fromPlugin.f.rds);
		final PanelSnapshot.FightTab member = SidePanelModel.fight(SidePanelMemberFight.decode(fromPlugin.f));
		assertEquals("80", row(member.details, EXPLOSIONS).right.text);
		assertEquals("10", row(member.details, HIMSELF).right.text);
		// Rows with nothing in them are dropped; none left is the total.
		final LuckVsSkillRoom.RoomSource empty = new LuckVsSkillRoom.RoomSource();
		assertNull(SidePanelMemberFight.roomSourcesFromWire(Collections.singletonList(empty)));
	}

	// ---- fit ----

	@Test
	public void nothingIsCutAtAnyTextSize()
	{
		final SidePanelModel.Input kill = input(kill(true, new int[] {80, 900}, new int[] {64, 800}, new int[] {10, 500},
			new int[] {12, 400}, new int[] {10, 500}));
		final SidePanelModel.Input trip = SidePanelExamples.trip();
		SidePanelModel.fillTripRoomDamage(trip, Arrays.asList(kill(true, new int[] {80, 900}, new int[] {10, 500}),
			kill(false, new int[] {10, 500}, new int[] {80, 900})), rules);
		for (boolean larger : new boolean[] {false, true})
		{
			for (boolean smooth : new boolean[] {false, true})
			{
				for (double scale : new double[] {1, 2})
				{
					final PvmPerformancePanel fight = new PvmPerformancePanel(null, null, null, null);
					fight.apply(new PanelSnapshot(SidePanelModel.fight(kill)));
					fits(fight, fight.fightTab(), "details", larger, smooth, scale);
					final PvmPerformancePanel t = new PvmPerformancePanel(null, null, null, null);
					t.apply(new PanelSnapshot(SidePanelModel.fight(SidePanelExamples.empty()), SidePanelModel.trip(trip)));
					t.tabs().select(t.tabs().getTab(2));
					fits(t, t.tripTab(), "tripOther", larger, smooth, scale);
				}
			}
		}
	}

	private static void fits(PvmPerformancePanel p, JComponent tab, String id, boolean larger, boolean smooth,
		double scale)
	{
		p.setText(larger, smooth);
		try
		{
			SidePanelPictures.draw(p.tabs(), tab, scale);
			final SidePanelSection section = SidePanelPictures.sectionOf(tab, id);
			assertNotNull(id, section);
			int seen = 0;
			for (Component c : all(section))
			{
				if (c instanceof SidePanelText && ((SidePanelText) c).lastShown() != null)
				{
					final SidePanelText t = (SidePanelText) c;
					assertFalse(larger + " " + smooth + " " + scale + "x " + id + ": " + t.getText() + " => " + t.lastShown(),
						t.lastShown().contains(LedgerRows.CUT));
					if (t.getText().equals(HIMSELF) || t.getText().equals(EXPLOSIONS))
					{
						assertEquals(t.getText(), t.lastShown());
						seen++;
					}
				}
			}
			assertEquals(id, 2, seen);
		}
		finally
		{
			SidePanelStyle.setText(false, false);
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

	// ---- the 10:35 log ----

	@Test
	public void ericsSixKillsAddUpToReconcilesRoom()
	{
		// pvm-debug-20261031-103506 (build eec325f): each kill's room splats as the log names them (E an explosion, C
		// his cleave, EC either, the capped 6 at [2170]), and the sum of its RECONCILE lines' room terms.
		final String[] kills = {
			"212 E80 C8 C8 C10 E80 C9 C9 C8",
			"160 E80 C12 C8 C12 C8 C10 C8 C10 C12",
			"210 E80 C9 C12 C9 C9 C11 E80",
			"137 E80 C8 C10 C12 C8 C11 C8",
			"198 E80 E80 C8 C8 C12 C10",
			"184 E80 C9 C9 C10 C11 C11 C9 C8 C12 C11 C8 EC6"};
		final int[][] want = {{160, 52}, {80, 80}, {160, 50}, {80, 57}, {160, 38}, {80, 104}};
		for (int k = 0; k < kills.length; k++)
		{
			final String[] parts = kills[k].split(" ");
			final Fight f = new Fight("Araxxor", NpcID.ARAXXOR, 1, 1020, 0L, null, 0);
			for (int i = 1; i < parts.length; i++)
			{
				final int amount = Integer.parseInt(parts[i].replaceAll("[A-Z]", ""));
				// Every cleave came enraged; the EC 6 was all he had left.
				final int left = parts[i].startsWith("EC") ? amount : 500;
				final BossRules.RoomDamageRule r = RoomDamage.ruleFor(araxxor, amount, left, s -> true);
				assertNotNull(parts[i], r);
				assertEquals(parts[i], parts[i].startsWith("E") && !parts[i].startsWith("EC") ? EXPLOSION : CLEAVE, r.name);
				f.recordRoomDamage(amount, r.name);
			}
			assertEquals("kill " + (k + 1), Integer.parseInt(parts[0]), f.getRoomDamage());
			final int sum = f.getRoomDamageBySource().values().stream().mapToInt(Integer::intValue).sum();
			assertEquals("kill " + (k + 1), f.getRoomDamage(), sum);
			assertEquals("kill " + (k + 1), want[k][0], (int) f.getRoomDamageBySource().getOrDefault(EXPLOSION, 0));
			assertEquals("kill " + (k + 1), want[k][1], (int) f.getRoomDamageBySource().getOrDefault(CLEAVE, 0));
		}
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
