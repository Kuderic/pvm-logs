package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.NpcID;
import net.runelite.client.party.messages.PartyMemberMessage;
import org.junit.Test;

/** T-124: the party update's wire format, what it carries, the rules for sending it, and the board it fills. */
public class PartyShareTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void theWireClassKeepsItsName()
	{
		// RuneLite keys a party message by its class's simple name: renaming it would cut old and new builds apart.
		assertEquals("LuckVsSkillUpdate", LuckVsSkillUpdate.class.getSimpleName());
		assertTrue(PartyMemberMessage.class.isAssignableFrom(LuckVsSkillUpdate.class));
		assertTrue(Modifier.isPublic(LuckVsSkillUpdate.class.getModifiers()));
		// The sender's member id is the party's to fill in, never in the message.
		final LuckVsSkillUpdate u = new LuckVsSkillUpdate();
		u.setMemberId(12345L);
		assertFalse(GSON.toJson(u).contains("12345"));
		assertFalse(GSON.toJsonTree(u).getAsJsonObject().has("memberId"));
	}

	@Test
	public void itCarriesNothingButTheRoomAndThePlayersOwnOutput()
	{
		// No field could hold a name, a place, gear, levels, the install id or history: these are the only ones.
		final List<String> names = new ArrayList<>();
		for (Field f : LuckVsSkillUpdate.class.getDeclaredFields())
		{
			if (!Modifier.isStatic(f.getModifiers()))
			{
				names.add(f.getName());
			}
		}
		// T-134: o, the sender's other damage (thrall, recoil and vengeance, damage over time).
		assertEquals("[v, k, r, rs, rn, ri, d, e, st, a, ct, kill, p, ls, x, u, il, bd, gd, pd, bs, gs, ps, tk, th, w, o]",
			names.toString());
	}

	/** A Tombs room: twelve kinds of attack, so the rows are capped, and a special. */
	private static Encounter akkha(AtomicInteger tick)
	{
		final Encounter room = new Encounter("Akkha", RaidType.TOMBS_OF_AMASCUT, 1_000L);
		final Fight fight = new Fight("Akkha", NpcID.AKKHA_MELEE, 1, 1500, 1_000L, RaidType.TOMBS_OF_AMASCUT, 3);
		room.add(fight);
		return room;
	}

	private static List<FightTimeline.Attack> attacks(AtomicInteger tick)
	{
		final FightTimeline line = new FightTimeline(tick::get);
		final int[] items = {ItemID.ZOMBIE_AXE, ItemID.TWISTED_BOW, ItemID.KODAI_WAND, ItemID.DRAGON_WARHAMMER,
			ItemID.SCYTHE_OF_VITUR, ItemID.TOXIC_BLOWPIPE, ItemID.BOW_OF_FAERDHINEN, ItemID.ABYSSAL_TENTACLE,
			ItemID.OSMUMTENS_FANG, ItemID.ELDER_MAUL, ItemID.VOIDWAKER, ItemID.DRAGON_CLAWS};
		for (int i = 0; i < items.length; i++)
		{
			for (int n = 0; n < 1 + (items.length - i); n++)
			{
				final int t = tick.addAndGet(4);
				line.attackMade();
				line.describeLatest("weapon " + i, items[i], i == 2 ? Spell.ICE_BARRAGE : null, FightTimeline.Style.MELEE,
					t, 4);
				if (i == 3 && n == 0)
				{
					line.markLatestSpecial();
				}
				line.expected(20.123 + i, 300.0);
				// The special lands a big one, so its row is among the eight.
				final int dealt = i == 3 && n == 0 ? 400 : n % 3 == 0 ? 0 : 30 + i;
				line.damage(dealt, dealt > 0);
				line.attributeSplat(t);
			}
		}
		return line.attacks();
	}

	@Test
	public void theRowsAreTheDamageCardsCappedAtEight()
	{
		final List<LuckVsSkillUpdate.Row> rows = PartyShare.rows(attacks(new AtomicInteger(100)));
		assertEquals(LuckVsSkillUpdate.MAX_ROWS, rows.size());
		// Biggest damage first; the last is the rest summed, with no weapon or spell.
		for (int i = 1; i < rows.size() - 1; i++)
		{
			assertTrue(rows.get(i - 1).d >= rows.get(i).d);
		}
		final LuckVsSkillUpdate.Row rest = rows.get(rows.size() - 1);
		assertEquals(-1, rest.i);
		assertEquals(0, rest.s);
		// The special is its own row: thrown and landed.
		final long specials = rows.stream().filter(r -> r.x).count();
		assertEquals(1, specials);
		final LuckVsSkillUpdate.Row special = rows.stream().filter(r -> r.x).findFirst().get();
		assertEquals(ItemID.DRAGON_WARHAMMER, special.i);
		assertEquals(1, special.a);
		assertEquals(1, special.l);
		assertEquals(400, special.d);
		// The barrage names its spell by the game's id.
		assertTrue(rows.stream().anyMatch(r -> r.s == Spell.ICE_BARRAGE.getVarbitValue()));
		// Every attack is in a row.
		int attacks = 0;
		for (LuckVsSkillUpdate.Row r : rows)
		{
			attacks += r.a;
		}
		assertEquals(90, attacks);
		assertTrue(PartyShare.rows(null).isEmpty());
	}

	@Test
	public void aRoomsUpdateRoundTripsAndStaysUnderAKilobyte()
	{
		final AtomicInteger tick = new AtomicInteger(100);
		final Encounter room = akkha(tick);
		final Fight fight = room.getFights().get(0);
		for (int i = 0; i < 30; i++)
		{
			fight.recordAttackMade(false, 0.9);
			fight.recordExpected(45, 0.8, 18.25, 300);
			fight.recordDamageDealt(i % 4 == 0 ? 0 : 25, 1_000L + i, i % 4 != 0);
		}
		final LuckVsSkillUpdate live = PartyShare.build(LuckVsSkillUpdate.LIVE, room, attacks(tick), 3);
		assertEquals("Akkha", live.r);
		assertEquals("Tombs of Amascut", live.rn);
		assertEquals(3, live.ri);
		assertEquals(fight.getDamageDealt(), live.d);
		assertEquals(30, live.a);
		assertFalse(live.kill);
		final String json = GSON.toJson(live);
		final LuckVsSkillUpdate back = GSON.fromJson(json, LuckVsSkillUpdate.class);
		assertEquals(json, GSON.toJson(back));
		assertEquals(LuckVsSkillUpdate.MAX_ROWS, back.rows().size());
		// Sizes (T-124: about 1 KB at most): eight rows, every figure filled.
		assertTrue("live update " + json.length() + " bytes", json.length() < 1024);
		final LuckVsSkillUpdate none = PartyShare.build(LuckVsSkillUpdate.LIVE, room, null, 3);
		assertTrue("without rows " + GSON.toJson(none).length() + " bytes", GSON.toJson(none).length() < 400);
		System.out.printf("T-124 sizes: live with 8 rows %d bytes, without rows %d bytes%n", json.length(),
			GSON.toJson(none).length());
		// Nothing Gson can't write: unknown is -1.
		assertFalse(json.contains("NaN"));
		assertFalse(json.contains("Infinity"));
	}

	@Test
	public void anOlderOrNewerBuildsUpdateStillReads()
	{
		// A newer build's field this one doesn't know is ignored; a field an older build never sent reads as unknown.
		final String newer = "{\"v\":2,\"k\":0,\"r\":\"Vardorvis\",\"rs\":5,\"d\":120,\"e\":110.5,\"st\":40,\"a\":10,"
			+ "\"zz\":{\"what\":\"next\"},\"w\":[{\"i\":28810,\"a\":10,\"l\":8,\"d\":120,\"e\":110.5,\"q\":1}]}";
		final LuckVsSkillUpdate u = GSON.fromJson(newer, LuckVsSkillUpdate.class);
		assertEquals(2, u.v);
		assertEquals(120, u.d);
		assertEquals(-1, u.p, 0);
		assertEquals(-1, u.x, 0);
		assertEquals(-1, u.bs, 0);
		assertEquals(1, u.rows().size());
		assertEquals(0, u.rows().get(0).s);
		assertNull(u.rn);
		assertTrue(u.isLive());
		// And this build's, read by one that knew only the first fields, would carry those the same way.
		final JsonObject mine = GSON.toJsonTree(new LuckVsSkillUpdate()).getAsJsonObject();
		assertEquals(LuckVsSkillUpdate.VERSION, mine.get("v").getAsInt());
	}

	@Test
	public void liveUpdatesGoAtMostEveryFiveTicks()
	{
		assertTrue(PartyShare.due(100, Integer.MIN_VALUE));
		assertFalse(PartyShare.due(101, 100));
		assertFalse(PartyShare.due(104, 100));
		assertTrue(PartyShare.due(105, 100));
		// A tick count that went backwards (a login) doesn't hold sharing off.
		assertTrue(PartyShare.due(3, 100));
		assertEquals(5, PartyShare.INTERVAL_TICKS);
	}

	@Test
	public void nothingIsSentWithTheSettingOffOrOutOfAParty()
	{
		assertTrue(PartyShare.mayShare(true, true));
		assertFalse(PartyShare.mayShare(false, true));
		assertFalse(PartyShare.mayShare(true, false));
		assertFalse(PartyShare.mayShare(false, false));
	}

	@Test
	public void yourOwnEchoIsIgnoredUnlessTheLoopbackIsOn()
	{
		assertTrue(PartyShare.keep(false, false));
		assertFalse(PartyShare.keep(true, false));
		assertTrue(PartyShare.keep(true, true));
	}

	@Test
	public void theLoopbackIsNeverOnInAReleaseBuild()
	{
		assertFalse(PartyShare.echoEnabled(null, "build abc1234 (master, clean)"));
		assertFalse(PartyShare.echoEnabled("false", "build abc1234 (master, clean)"));
		assertTrue(PartyShare.echoEnabled("true", "build abc1234 (master, clean)"));
		assertTrue(PartyShare.echoEnabled(" TRUE ", "build abc1234 (master, clean)"));
		assertFalse(PartyShare.echoEnabled("true", "release 1.0"));
		// No one sets it in this test run: off.
		assertFalse(PartyShare.echoEnabled(System.getProperty(PartyShare.ECHO_PROPERTY), BuildInfo.describe()));
	}

	private static LuckVsSkillUpdate update(int kind, String room, long start, int st, int damage, String raid, int raidNo)
	{
		final LuckVsSkillUpdate u = new LuckVsSkillUpdate();
		u.k = kind;
		u.r = room;
		u.rs = start;
		u.st = st;
		u.d = damage;
		u.e = damage * 0.9;
		u.rn = raid;
		u.ri = raidNo;
		return u;
	}

	@Test
	public void theBoardKeepsEachMembersSeriesAndRaidRooms()
	{
		final PartyBoard board = new PartyBoard();
		assertTrue(board.isEmpty());
		final long before = board.revision();
		// Member 7 in the Tombs: Akkha live twice, then its end; then Kephri live.
		board.accept(7, update(LuckVsSkillUpdate.LIVE, "Akkha", 1, 10, 100, "Tombs of Amascut", 3), null, 1L);
		board.accept(7, update(LuckVsSkillUpdate.LIVE, "Akkha", 1, 15, 160, "Tombs of Amascut", 3), null, 2L);
		// The same figures again add no point.
		board.accept(7, update(LuckVsSkillUpdate.LIVE, "Akkha", 1, 15, 160, "Tombs of Amascut", 3), null, 3L);
		board.accept(7, update(LuckVsSkillUpdate.ROOM_END, "Akkha", 1, 18, 190, "Tombs of Amascut", 3), null, 4L);
		board.accept(7, update(LuckVsSkillUpdate.LIVE, "Kephri", 50, 5, 40, "Tombs of Amascut", 3), null, 5L);
		// Member 9, on their own boss.
		board.accept(9, update(LuckVsSkillUpdate.LIVE, "Vardorvis", 7, 20, 300, null, 0), null, 6L);
		assertTrue(board.revision() > before);
		final List<PartyBoard.View> view = board.view(id -> id == 7 ? "Alice" : null);
		assertEquals(2, view.size());
		final PartyBoard.View alice = view.get(0);
		assertEquals("Alice", alice.name);
		assertEquals("Kephri", alice.live.r);
		assertEquals("Akkha", alice.lastRoom.r);
		assertEquals("Kephri@50", alice.room);
		assertEquals(3, alice.series.get("Akkha@1").size());
		assertEquals(190, alice.series.get("Akkha@1").get(2).damage);
		assertEquals(1, alice.series.get("Kephri@50").size());
		assertEquals(1, alice.raidRooms.size());
		assertEquals("Akkha", alice.raidRooms.get(0).r);
		// Not named by the party yet.
		assertEquals("Party member", view.get(1).name);
		// A new raid starts its rooms afresh.
		board.accept(7, update(LuckVsSkillUpdate.ROOM_END, "Ba-Ba", 90, 30, 400, "Tombs of Amascut", 4), null, 7L);
		assertEquals(1, board.view(null).get(0).raidRooms.size());
		assertEquals("Ba-Ba", board.view(null).get(0).raidRooms.get(0).r);
	}

	@Test
	public void aMemberWhoLeavesGoesAndLeavingThePartyClearsIt()
	{
		final PartyBoard board = new PartyBoard();
		board.accept(7, update(LuckVsSkillUpdate.LIVE, "Akkha", 1, 10, 100, null, 0), null, 1L);
		board.accept(9, update(LuckVsSkillUpdate.LIVE, "Akkha", 1, 10, 80, null, 0), null, 1L);
		board.part(7);
		assertEquals(1, board.view(null).size());
		assertEquals(9, board.view(null).get(0).memberId);
		board.part(42);
		board.clear();
		assertTrue(board.isEmpty());
		// An update with no room is nothing.
		board.accept(9, new LuckVsSkillUpdate(), null, 1L);
		board.accept(9, null, null, 1L);
		assertTrue(board.isEmpty());
	}

	@Test
	public void theLoopbacksMemberIsNamedForWhatItIs()
	{
		final PartyBoard board = new PartyBoard();
		board.accept(5, update(LuckVsSkillUpdate.LIVE, "Akkha", 1, 10, 100, null, 0), PartyBoard.ECHO_NAME, 1L);
		assertEquals("You (echo)", board.view(id -> "Someone").get(0).name);
	}

	@Test
	public void theBoardsCopiesDontChangeUnderTheReader()
	{
		final PartyBoard board = new PartyBoard();
		board.accept(7, update(LuckVsSkillUpdate.LIVE, "Akkha", 1, 10, 100, null, 0), null, 1L);
		final PartyBoard.View first = board.view(null).get(0);
		board.accept(7, update(LuckVsSkillUpdate.LIVE, "Akkha", 1, 15, 150, null, 0), null, 2L);
		assertEquals(1, first.series.get("Akkha@1").size());
		assertEquals(2, board.view(null).get(0).series.get("Akkha@1").size());
		// The series stops at its cap.
		for (int i = 0; i < PartyBoard.MAX_POINTS + 10; i++)
		{
			board.accept(7, update(LuckVsSkillUpdate.LIVE, "Akkha", 1, 20 + i, 200 + i, null, 0), null, 3L);
		}
		assertEquals(PartyBoard.MAX_POINTS, board.view(null).get(0).series.get("Akkha@1").size());
		assertNotNull(board.view(null).get(0).live);
	}
}
