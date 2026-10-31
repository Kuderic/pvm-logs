package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import net.runelite.api.gameval.NpcID;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * T-106: Araxxor's Ruptura explosions and his enraged cleave on himself show as
 * another player's damage (type 17). With no other player there they are the
 * room's: they take his hitpoints down, aren't the player's, and aren't another
 * player's, so a solo kill stays solo.
 *
 * <p>The plugin's splat handler has no harness here (no mocking library), so these
 * drive the parts it is made of, in its order: {@link RoomDamage#kindOf} on the
 * splat, the kind's {@link Fight} total, {@link TargetHealth} taking the damage,
 * {@link RoomDamage#sourceOf} for the log, and the solo tests that read
 * {@link Fight#getOthersDamage}. Amounts from Eric's logs of 2026-10-29
 * ({@code pvm-debug-20261029-212024}, {@code -214018}, {@code -223259}).
 */
public class RoomDamageTest
{
	private static final int ARAXXOR = NpcID.ARAXXOR;
	private static final int INDEX = 41940;
	private static final String NAME = "Araxxor";
	private static final int TYPE_OTHERS = 17;

	private static BossRules rules;
	private static List<BossRules.RoomDamageRule> araxxor;

	@BeforeClass
	public static void load()
	{
		rules = BossRules.load(new Gson(), null);
		final BossRules.Boss boss = rules.forNpc(ARAXXOR);
		assertNotNull(boss);
		araxxor = boss.roomDamage;
		assertNotNull(araxxor);
	}

	/** What the handler does with a splat the game shows as another's, on the fight and the target's hitpoints. */
	private static HitsplatKind splat(Fight fight, TargetHealth health, int amount, int otherPlayers, int tick)
	{
		final HitsplatKind kind = RoomDamage.kindOf(HitsplatKind.of(false, true, TYPE_OTHERS), otherPlayers);
		if (kind == HitsplatKind.ROOM)
		{
			fight.recordRoomDamage(amount);
		}
		else if (kind == HitsplatKind.OTHER_PLAYER)
		{
			fight.recordOthersDamage(amount);
		}
		if (kind.damagesTarget())
		{
			health.damage(INDEX, ARAXXOR, amount, tick);
		}
		return kind;
	}

	/** A kill under way: the player's attacks so far, their expected damage and 300 of his 1020 taken. */
	private static Fight playersAttacks()
	{
		final Fight f = new Fight(NAME, ARAXXOR, INDEX, 1020, 0L, null, 0);
		for (int i = 0; i < 6; i++)
		{
			f.recordAttackMade(false, 1.0);
			f.recordExpected(62, 0.8, 31, 300);
			f.recordDamageDealt(50, 1_000L * i, true);
		}
		return f;
	}

	private static TargetHealth health(int taken)
	{
		final TargetHealth h = new TargetHealth();
		h.setTableMaxHp(INDEX, ARAXXOR, 1020, 100);
		h.damage(INDEX, ARAXXOR, taken, 100);
		return h;
	}

	/** The fight as saved, less the room's total and its split: what is left is the player's numbers and the others'. */
	private static JsonObject withoutRoom(Fight f)
	{
		final Gson gson = new Gson();
		final JsonObject o = gson.toJsonTree(f).getAsJsonObject();
		o.remove("roomDamage");
		// T-131: and its split by source.
		o.remove("roomDamageBySource");
		return o;
	}

	@Test
	public void anExplosionOnASoloKillIsTheRooms()
	{
		final Fight fight = playersAttacks();
		final JsonObject before = withoutRoom(fight);
		final TargetHealth health = health(300);
		assertEquals(720, health.remaining(INDEX, 101));

		assertEquals(HitsplatKind.ROOM, splat(fight, health, 80, 0, 101));
		assertEquals(80, fight.getRoomDamage());
		assertEquals(0, fight.getOthersDamage());
		// The player's numbers are as they were: damage, expected, luck, DPS all come from these fields.
		assertEquals(before, withoutRoom(fight));
		assertEquals(300, fight.getDamageDealt());
		// It takes his hitpoints down, so the next attack's expected damage is capped at what is left.
		assertEquals(640, health.remaining(INDEX, 101));

		final Encounter room = new Encounter(NAME, null, 0L);
		room.add(fight);
		assertTrue("the kill reads solo", LedgerHistory.Like.of(room).solo);
		assertTrue(new LedgerHistory.Like(NAME, 1020, true).matches(room));
		assertEquals("a Ruptura araxyte's explosion", RoomDamage.sourceOf(araxxor, 80, 720, s -> false));
		assertEquals("a Ruptura araxyte's explosion", RoomDamage.sourceOf(araxxor, 64, 720, s -> false));
	}

	@Test
	public void theSameSplatWithAnotherPlayerThereIsTheirs()
	{
		final Fight fight = playersAttacks();
		final TargetHealth health = health(300);
		assertEquals(HitsplatKind.OTHER_PLAYER, splat(fight, health, 80, 1, 101));
		assertEquals(80, fight.getOthersDamage());
		assertEquals(0, fight.getRoomDamage());
		assertEquals(640, health.remaining(INDEX, 101));
		final Encounter room = new Encounter(NAME, null, 0L);
		room.add(fight);
		assertFalse("a group kill", LedgerHistory.Like.of(room).solo);
	}

	@Test
	public void onlyAnothersSplatCanBeTheRooms()
	{
		assertEquals(HitsplatKind.ROOM, RoomDamage.kindOf(HitsplatKind.OTHER_PLAYER, 0));
		assertEquals(HitsplatKind.OTHER_PLAYER, RoomDamage.kindOf(HitsplatKind.OTHER_PLAYER, 3));
		for (HitsplatKind k : HitsplatKind.values())
		{
			if (k != HitsplatKind.OTHER_PLAYER)
			{
				assertEquals(k, RoomDamage.kindOf(k, 0));
			}
		}
		assertTrue(HitsplatKind.ROOM.damagesTarget());
	}

	@Test
	public void hisOwnCleaveOnceEnraged()
	{
		final Fight fight = playersAttacks();
		final JsonObject before = withoutRoom(fight);
		final TargetHealth health = health(800);
		int tick = 101;
		for (int amount = 8; amount <= 12; amount++)
		{
			assertEquals(HitsplatKind.ROOM, splat(fight, health, amount, 0, tick++));
			assertEquals("his own cleave", RoomDamage.sourceOf(araxxor, amount, 200, "enraged"::equals));
		}
		assertEquals(50, fight.getRoomDamage());
		assertEquals(0, fight.getOthersDamage());
		assertEquals(before, withoutRoom(fight));
		assertEquals(220 - 50, health.remaining(INDEX, tick));
		// Not enraged, an 8-12 isn't his cleave: still the room's (no other player), but nothing names it.
		assertNull(RoomDamage.sourceOf(araxxor, 10, 900, s -> false));
		assertEquals(HitsplatKind.ROOM, RoomDamage.kindOf(HitsplatKind.OTHER_PLAYER, 0));
	}

	@Test
	public void anExplosionThatKillsHim()
	{
		// [1021] of the 21:20 log: a 56 with 56 left, the explosion's 80 capped by the hitpoints he had (Araxyte Betrayal).
		final Fight fight = playersAttacks();
		final JsonObject before = withoutRoom(fight);
		final TargetHealth health = health(1020 - 56);
		assertEquals(HitsplatKind.ROOM, splat(fight, health, 56, 0, 101));
		assertEquals(0, health.remaining(INDEX, 101));
		assertEquals("a Ruptura araxyte's explosion, the killing splat at the 56 left",
			RoomDamage.sourceOf(araxxor, 56, 56, s -> false));
		// [1362]: a 22 with 22 left, under the explosion's least: an explosion capped. Over the cleave's 12, so not that.
		assertEquals("a Ruptura araxyte's explosion, the killing splat at the 22 left",
			RoomDamage.sourceOf(araxxor, 22, 22, "enraged"::equals));
		// A 4 that finished him enraged fits either.
		assertEquals("a Ruptura araxyte's explosion or his own cleave, the killing splat at the 4 left",
			RoomDamage.sourceOf(araxxor, 4, 4, "enraged"::equals));
		// The kill is still the player's: none of their numbers move, and it reads solo.
		fight.end(true, 60_000L);
		assertEquals(before.get("damageDealt"), withoutRoom(fight).get("damageDealt"));
		assertEquals(300, fight.getDamageDealt());
		assertEquals(56, fight.getRoomDamage());
		assertEquals(0, fight.getOthersDamage());
		final Encounter room = new Encounter(NAME, null, 0L);
		room.add(fight);
		assertTrue(LedgerHistory.Like.of(room).solo);
		// A splat under the explosion's least that didn't kill him isn't one.
		assertNull(RoomDamage.sourceOf(araxxor, 22, 300, s -> false));
	}

	@Test
	public void anOldFileLoadsWithNoRoomDamage()
	{
		final Gson gson = new Gson();
		final Fight saved = playersAttacks();
		saved.recordOthersDamage(80);
		final JsonObject json = gson.toJsonTree(saved).getAsJsonObject();
		json.remove("roomDamage");
		final Fight[] loaded = gson.fromJson("[" + json + "]", Fight[].class);
		assertEquals(0, loaded[0].getRoomDamage());
		// Left as saved: before T-106 the room's damage was in othersDamage, and it isn't moved.
		assertEquals(80, loaded[0].getOthersDamage());
		assertEquals(300, loaded[0].getDamageDealt());
	}

	@Test
	public void eachRuleHasASource()
	{
		for (BossRules.RoomDamageRule r : araxxor)
		{
			assertNotNull(r.name, r.source);
			assertEquals(r.name, 2, r.amounts.length);
			assertTrue(r.name, r.amounts[0] > 0 && r.amounts[0] <= r.amounts[1]);
		}
		assertNull(RoomDamage.sourceOf(null, 80, 500, s -> true));
	}
}
