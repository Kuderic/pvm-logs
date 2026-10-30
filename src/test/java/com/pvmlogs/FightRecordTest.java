package com.pvmlogs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Player;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/** T-110, R1: each fight's record of its build, weapons, setup, context and mechanic windows. */
public class FightRecordTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void anOldHistoryLoadsUnchangedWithNoRecord()
	{
		final Fight old = new Fight("Vardorvis", NpcID.VARDORVIS, 1, 700, 1000, null, 0);
		old.recordAttackMade(false);
		old.recordExpected(55, 0.6, 18.0, 300);
		old.recordDamageDealt(30, 1001, true);
		old.end(true, 2000);
		final JsonObject json = GSON.toJsonTree(old).getAsJsonObject();
		assertFalse("a fight that never filled its record saves none", json.has("context"));
		final Fight loaded = GSON.fromJson(json, Fight.class);
		assertNull(loaded.getContext());
		assertEquals(30, loaded.getDamageDealt());
		assertEquals(18.0, loaded.getSumExpectedAverageHit(), 1e-9);
		assertEquals(GSON.toJson(old), GSON.toJson(loaded));
	}

	@Test
	public void theRecordRoundTrips()
	{
		final Fight f = new Fight("Araxxor", NpcID.ARAXXOR, 1, 1020, 0, null, 0);
		final FightContext c = f.context();
		c.build = "1f79693 (master, clean)";
		c.rules = "c446f2e rules 8a1f03c2";
		c.armour = FightContext.sortedIds(new int[]{33443, -1, 6570, 19553, 0});
		c.baseLevels = new int[]{99, 99, 94, 95, 94, 99, 88};
		c.specWeapons = FightContext.addOnce(null, "Dragon warhammer");
		c.specWeapons = FightContext.addOnce(c.specWeapons, "Dragon warhammer");
		c.ammoOrSpell = FightContext.addOnce(null, "Ruby dragon bolts (e)");
		c.worldTypes = Collections.singletonList("MEMBERS");
		c.kc = 123;
		c.gameDurationTicks = 205;
		c.addWindow("enraged", 2, 1);
		final Fight back = GSON.fromJson(GSON.toJson(f), Fight.class);
		final FightContext b = back.getContext();
		assertEquals("1f79693 (master, clean)", b.build);
		assertArrayEquals(new int[]{6570, 19553, 33443}, b.armour);
		assertEquals(Collections.singletonList("Dragon warhammer"), b.specWeapons);
		assertEquals(123, b.kc);
		assertEquals(3, b.mechanicWindows.get(0).idle());
		assertEquals(7, b.baseLevels.length);
	}

	@Test
	public void theWeaponSumsMatchTheTimeline()
	{
		final AtomicInteger tick = new AtomicInteger(100);
		final FightTimeline line = new FightTimeline(tick::get);
		// An axe blow, a crossbow shot and a hammer special, each credited as the plugin credits them.
		swing(line, tick, "Zombie axe", false, 27.8, 404.1, 41, 5);
		swing(line, tick, "Hunters' sunlight crossbow", false, 29.0, 0, 29, 3);
		swing(line, tick, "Zombie axe", false, 27.8, 404.1, 0, 5);
		swing(line, tick, "Dragon warhammer", true, 24.9, 738.0, 54, 6);
		final Map<String, FightContext.Weapon> w = FightContext.weaponsOf(line.attacks());
		assertEquals(Arrays.asList("Zombie axe", "Hunters' sunlight crossbow", "Dragon warhammer (special)"),
			Arrays.asList(w.keySet().toArray()));
		final FightContext.Weapon axe = w.get("Zombie axe");
		assertEquals(2, axe.attacks);
		assertEquals(1, axe.landed);
		assertEquals(41, axe.damage);
		assertEquals(55.6, axe.expected, 1e-9);
		assertEquals(808.2, axe.variance, 1e-9);
		assertEquals(5, axe.speed);
		assertEquals(6, w.get("Dragon warhammer (special)").speed);
		int damage = 0;
		double expected = 0;
		double variance = 0;
		for (FightContext.Weapon v : w.values())
		{
			damage += v.damage;
			expected += v.expected;
			variance += v.variance;
		}
		assertEquals(line.totalDamage(), damage);
		assertEquals(line.totalExpected(), expected, 0.01);
		assertEquals(line.totalVariance(), variance, 0.01);
	}

	private static void swing(FightTimeline line, AtomicInteger tick, String weapon, boolean special, double mean,
		double variance, int damage, int speed)
	{
		final int t = tick.addAndGet(5);
		line.attackMade();
		line.describeLatest(weapon, 1, null, FightTimeline.Style.MELEE, t, speed);
		if (special)
		{
			line.markLatestSpecial();
		}
		line.expected(mean, variance);
		line.damage(damage, damage > 0);
		line.attributeSplat(t);
	}

	@Test
	public void killCountAndDurationAreReadFromTheGamesMessages()
	{
		assertEquals(123, GameMessages.killCount("Your Araxxor kill count is: 123."));
		assertEquals("Araxxor", GameMessages.killCountBoss("Your Araxxor kill count is: 123."));
		assertEquals(1234, GameMessages.killCount("Your Vardorvis kill count is: 1,234."));
		assertEquals(57, GameMessages.killCount("Your completion count for Tombs of Amascut is: 57."));
		assertEquals("Tombs of Amascut", GameMessages.killCountBoss("Your completion count for Tombs of Amascut is: 57."));
		assertEquals(23, GameMessages.killCount("Your completed Theatre of Blood count is: 23."));
		assertEquals(5, GameMessages.killCount("Your Gauntlet completion count is: 5."));
		assertEquals(9, GameMessages.killCount("Your Hespori harvest count is: 9."));
		assertEquals(-1, GameMessages.killCount("Your Araxxor kill count is low."));
		assertEquals(-1, GameMessages.killCount("Welcome to Old School RuneScape."));
		// 2:03.00 is 123 s, 205 ticks.
		assertEquals(205, GameMessages.durationTicks("Fight duration: 2:03.00. Personal best: 1:50.40"));
		// A personal best.
		assertEquals(184, GameMessages.durationTicks("Fight duration: 1:50.40 (new personal best)."));
		assertEquals(184, GameMessages.durationTicks("Fight duration: 1:50.40 (new personal best)"));
		assertEquals(3600 / 0.6, GameMessages.durationTicks("Challenge duration: 1:00:00.00."), 0);
		assertEquals(-1, GameMessages.durationTicks("Fight duration: soon"));
		assertEquals(-1, GameMessages.durationTicks("Your Araxxor kill count is: 123."));
	}

	@Test
	public void theKillsMessagesGoToTheFightTheKillClosed()
	{
		final Fight closed = new Fight("Araxxor", NpcID.ARAXXOR, 1, 1020, 0, null, 0);
		closed.recordAttackMade(false);
		closed.end(true, 10_000);
		closed.closeRoom();
		assertTrue(PvmPerformancePlugin.killMessageFight(closed, null, 12_000) == closed);
		// Too long after, it is nobody's.
		assertNull(PvmPerformancePlugin.killMessageFight(closed, null, 10_000 + PvmPerformancePlugin.KILL_MESSAGE_MILLIS + 1));
		// A fight that ended by switching targets was no kill.
		final Fight switched = new Fight("Araxxor", NpcID.ARAXXOR, 1, 1020, 0, null, 0);
		switched.recordAttackMade(false);
		switched.end(false, 10_000);
		assertNull(PvmPerformancePlugin.killMessageFight(switched, null, 11_000));
	}

	private static Player player()
	{
		return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
			(proxy, method, args) -> "equals".equals(method.getName()) ? proxy == args[0]
				: "hashCode".equals(method.getName()) ? System.identityHashCode(proxy) : null);
	}

	@Test
	public void teamSizeCountsTheOtherPlayers()
	{
		final Player me = player();
		final Player other = player();
		assertEquals(0, PvmPerformancePlugin.othersIn(Collections.singletonList(me), me));
		assertEquals(1, PvmPerformancePlugin.othersIn(Arrays.asList(me, other), me));
		assertEquals(1, PvmPerformancePlugin.othersIn(Arrays.asList(null, other, me), me));
		final FightContext c = new FightContext();
		c.teamSizeStart = 0;
		c.seePlayers(0);
		c.seePlayers(1);
		c.seePlayers(0);
		assertEquals(0, c.teamSizeStart);
		assertEquals(1, c.teamSizeMax);
	}

	@Test
	public void theInstallIdIsMadeOnceAndKept()
	{
		final AtomicReference<String> file = new AtomicReference<>();
		final AtomicInteger writes = new AtomicInteger();
		final String first = InstallId.load(file::get, id ->
		{
			writes.incrementAndGet();
			file.set(id);
		});
		assertEquals(36, first.length());
		// Restarted: read back, nothing written.
		final String again = InstallId.load(file::get, id -> writes.incrementAndGet());
		assertEquals(first, again);
		assertEquals(1, writes.get());
		// A file that holds something else is replaced.
		file.set("not an id");
		final String fresh = InstallId.load(file::get, file::set);
		assertNotEquals(first, fresh);
		assertEquals(fresh, file.get());
	}

	@Test
	public void eachMechanicWindowIsKeptWithItsIdleTicks()
	{
		final MechanicAllowances mechanics = new MechanicAllowances(null);
		final BossRules.UptimeAllowance stun = new BossRules.UptimeAllowance();
		stun.state = "stunned";
		stun.maxTicks = 2;
		final List<BossRules.UptimeAllowance> rules = Collections.singletonList(stun);
		final int[][] open = {{100, 104}};
		for (int t = 100; t < 104; t++)
		{
			mechanics.observe(t, rules, s -> open[0]);
		}
		mechanics.excuse(101);
		mechanics.excuse(102);
		mechanics.excuse(103);
		mechanics.observe(104, rules, s -> null);
		mechanics.finish();
		final List<FightContext.Window> windows = mechanics.closedWindows();
		assertEquals(1, windows.size());
		assertEquals("stunned", windows.get(0).mechanic);
		assertEquals(2, windows.get(0).excused);
		assertEquals(1, windows.get(0).lost);
		mechanics.reset();
		assertTrue(mechanics.closedWindows().isEmpty());
	}

	@Test
	public void theFullestRecordStaysSmall()
	{
		// Every list at its cap: what history.json's budget has to carry per fight at worst.
		final Fight f = new Fight("Araxxor", NpcID.ARAXXOR, 1, 1020, 0, null, 0);
		final int bare = GSON.toJson(f).length();
		final FightContext c = f.context();
		c.build = "1f79693 (t-110/r1-context-and-calibration, dirty: 12 files changed)";
		c.rules = "c446f2e rules 8a1f03c2 override 1b2c3d4e";
		c.monsters = "cache 0a1b2c3d";
		c.weapons = new java.util.LinkedHashMap<>();
		for (int i = 0; i < FightContext.LIST_CAP; i++)
		{
			final FightContext.Weapon w = new FightContext.Weapon();
			w.attacks = 120;
			w.landed = 99;
			w.damage = 2900;
			w.expected = 2876.53;
			w.variance = 41234.17;
			w.speed = 5;
			c.weapons.put("Hunters' sunlight crossbow " + i, w);
			c.specWeapons = FightContext.addOnce(c.specWeapons, "Dragon warhammer " + i);
			c.ammoOrSpell = FightContext.addOnce(c.ammoOrSpell, "Ruby dragon bolts (e) " + i);
		}
		c.armour = new int[]{31097, 31106, 28947, 26382, 26384, 26386, 22981, 13239, 25975, 28310, 28313};
		c.baseLevels = new int[]{99, 99, 99, 99, 99, 99, 99};
		c.idealDpsFullHp = 7.85;
		c.teamSizeStart = 1;
		c.teamSizeMax = 2;
		c.worldTypes = Arrays.asList("MEMBERS", "SEASONAL", "QUEST_SPEEDRUNNING");
		c.kc = 1234;
		c.gameDurationTicks = 205;
		for (int i = 0; i < FightContext.WINDOW_CAP + 5; i++)
		{
			c.addWindow("stunned", 2, 1);
		}
		assertEquals(FightContext.WINDOW_CAP, c.mechanicWindows.size());
		assertEquals(FightContext.LIST_CAP, c.specWeapons.size());
		final int added = GSON.toJson(f).length() - bare;
		assertTrue("the fullest record adds " + added + " bytes", added < 4500);
	}

	@Test
	public void theRulesRecordNamesTheCommit()
	{
		assertEquals("c446f2e", PvmPerformancePlugin.portedCommit("weirdgloop/osrs-dps-calc@c446f2e (2026-10-26)"));
		assertEquals("unknown", PvmPerformancePlugin.portedCommit("unknown"));
		assertEquals("unknown", PvmPerformancePlugin.portedCommit(null));
		assertEquals(FightContext.shortHash("abc"), FightContext.shortHash("abc"));
		assertNotEquals(FightContext.shortHash("abc"), FightContext.shortHash("abd"));
	}
}
