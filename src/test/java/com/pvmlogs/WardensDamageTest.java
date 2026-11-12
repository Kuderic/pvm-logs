package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/**
 * T-219 (Eric, 2026-11-12: "a lot of damage in wardens got attributed to the other party member but actually weren't"):
 * his duo Tombs of 2026-11-11 ({@code pvm-debug-20261111-231111.log}, build {@code 14c2164}, read in place).
 * <ul>
 * <li>P2: all of "others 5245" was 25 splats on the exposed Warden (11755), multiples of 5, only while her core was out:
 * the partner's core hits passed on five times over, against Eric's own counted at face value (T-172).</li>
 * <li>P3: four 200s, each the tick after she started 9680 coming back from 11764: the skull mechanic's.</li>
 * <li>P3: "enraged on" logged at each of those returns, the state shut until [8001].</li>
 * </ul>
 */
public class WardensDamageTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);
	private static final int EXPOSED = 11755;
	private static final int P3_WARDEN = 11762;
	private static final int P3_AWAY = 11764;
	private static final int SKULL_ANIM = 9680;

	/** P2's others' splats on 11755 in Eric's raid, in order. */
	private static final int[] PARTNER_CORE = {210, 210, 210, 210, 210, 210, 210, 210, 220, 165, 200, 200, 200, 200, 210,
		210, 210, 210, 0, 220, 220, 220, 220, 220, 220, 220};

	private static Fight fight(String name, int npcId)
	{
		return new Fight(name, npcId, 35106, 340, 0L, null, 0);
	}

	@Test
	public void othersCoreDamageCountsAtAFifthWithItsOwnReconcileTerm()
	{
		final Fight p2 = fight("Elidinis' Warden", 11753);
		int tick = 7373;
		int passed = 0;
		for (int amount : PARTNER_CORE)
		{
			// The plugin's test for the passed-on, the same as for the player's own (T-153): on her exposed form, a multiple
			// of 5, at least 5.
			if (PvmPerformancePlugin.coreDamagePassedOn(EXPOSED, amount))
			{
				p2.recordOthersCore(amount, tick++);
				passed += amount;
			}
		}
		assertEquals(5245, passed);
		assertEquals(5245, p2.getOthersPassedOn());
		// On the board: the partner's core hits at face value, 1,049, the units Eric's 1,259 count in.
		assertEquals(1049, p2.getOthersDamage());
		assertEquals(1049, p2.getOthersOnTheCore());
		// The party chart's line has them as they came.
		final int[][] series = p2.getOthersSeries();
		assertEquals(25, series[0].length);
		assertEquals(1049, series[1][24]);
		// And the board's "others" figure is what's left after members' own figures: all of it here.
		assertEquals(1049, SidePanelParty.others(p2.getOthersDamage(), Collections.emptyList()));
	}

	@Test
	public void aSplatNotAMultipleOfFiveOrOffTheExposedFormIsntTheCores()
	{
		// A body hit, a thrall's 1-3, or a 0: as before, another player's at face value.
		assertFalse(PvmPerformancePlugin.coreDamagePassedOn(EXPOSED, 47));
		assertFalse(PvmPerformancePlugin.coreDamagePassedOn(EXPOSED, 2));
		assertFalse(PvmPerformancePlugin.coreDamagePassedOn(EXPOSED, 0));
		// Her poise forms (core in) and every other room: never.
		assertFalse(PvmPerformancePlugin.coreDamagePassedOn(11753, 210));
		assertFalse(PvmPerformancePlugin.coreDamagePassedOn(P3_WARDEN, 200));
		assertFalse(PvmPerformancePlugin.coreDamagePassedOn(11751, 200));
	}

	private static BossRules.RoomDamageRule mechanic(int npcId, int amount, int left, int ticksSinceSkullAnim)
	{
		final BossRules.Boss boss = RULES.forNpc(npcId);
		return boss == null ? null : RoomDamage.mechanicFor(boss.roomDamage, amount, left, state -> false,
			anim -> anim == SKULL_ANIM ? ticksSinceSkullAnim : -1);
	}

	@Test
	public void aTwoHundredTheTickAfterNinetySixEightyIsTheRooms()
	{
		// [7726], [7792], [7876], [7961]: at 3094, 2376, 1526 and 725 left.
		for (int left : new int[]{3094, 2376, 1526, 725})
		{
			final BossRules.RoomDamageRule r = mechanic(P3_WARDEN, 200, left, 1);
			assertEquals("the skull mechanic", r.name);
			assertEquals("skull mechanic", r.label);
			assertTrue(r.evenWithOthers);
		}
		// Killing her below 200 with what was left: still the mechanic's.
		assertEquals("the skull mechanic", mechanic(P3_WARDEN, 150, 150, 1).name);
		// Not on that tick, not 200, no 9680 seen: a player's, as before.
		assertNull(mechanic(P3_WARDEN, 200, 3094, 0));
		assertNull(mechanic(P3_WARDEN, 200, 3094, 2));
		assertNull(mechanic(P3_WARDEN, 200, 3094, -1));
		assertNull(mechanic(P3_WARDEN, 199, 3094, 1));
		assertNull(mechanic(P3_WARDEN, 45, 3094, 1));
	}

	@Test
	public void theObeliskAndTheOtherRoomsAreUnchanged()
	{
		// P1's obelisk and P2 have no mechanic rule; Araxxor's and the Judge's room damage still only name a splat.
		assertNull(mechanic(11751, 200, 600, 1));
		assertNull(mechanic(EXPOSED, 200, 5000, 1));
		assertNull(mechanic(13668, 80, 1000, 1));
		assertNull(mechanic(14180, 15, 1000, 1));
		final BossRules.RulesFile file = new Gson().fromJson(new java.io.InputStreamReader(
			BossRules.class.getResourceAsStream("boss-rules.json"), java.nio.charset.StandardCharsets.UTF_8),
			BossRules.RulesFile.class);
		for (BossRules.Boss b : file.bosses)
		{
			if (b.roomDamage == null)
			{
				continue;
			}
			for (BossRules.RoomDamageRule r : b.roomDamage)
			{
				assertEquals(b.name + ": " + r.name, "The Wardens, phase 3".equals(b.name), r.evenWithOthers);
			}
		}
		// The rule names the splat for a solo raid too, where it was already the room's.
		assertEquals("the skull mechanic", RoomDamage.ruleFor(RULES.forNpc(P3_WARDEN).roomDamage, 200, 3094, s -> false)
			.name);
	}

	@Test
	public void enragedStaysShutOnAReturnFromHerUntargetableForm()
	{
		final List<String> lines = new ArrayList<>();
		final ModifierTracker tracker = new ModifierTracker(lines::add);
		final BossRules.DamageModifierRules rules = RULES.forNpc(P3_WARDEN).damageModifiers;
		final String name = "The Wardens, phase 3";
		tracker.onNpcId(42537, name, rules, P3_WARDEN, 7617);
		tracker.onHitpoints(42537, name, rules, 3935, 4010, 7618);
		// Away and back, four times, her hitpoints never at 5% nor healed: no "enraged on".
		int t = 7708;
		for (int i = 0; i < 4; i++)
		{
			tracker.onNpcId(42537, name, rules, P3_AWAY, t);
			tracker.onNpcId(42537, name, rules, P3_WARDEN, t + 17);
			t += 70;
		}
		for (String l : lines)
		{
			assertFalse(l, l.contains("enraged on") || l.contains("enraged off"));
		}
		assertFalse(tracker.isOpen(42537, "enraged", t));
		// The heal at [8001] opens it, and from then a return logs it on.
		tracker.onHitpoints(42537, name, rules, 205, 4010, 8000);
		tracker.onHitpoints(42537, name, rules, 984, 4010, 8001);
		assertTrue(lines.toString(), lines.get(lines.size() - 1).startsWith(
			"MOD The Wardens, phase 3: enraged on at tick 8001 (healed 19% to 984 of 4010 hitpoints"));
		assertTrue(tracker.isOpen(42537, "enraged", 8002));
		tracker.onNpcId(42537, name, rules, P3_AWAY, 8050);
		tracker.onNpcId(42537, name, rules, P3_WARDEN, 8067);
		assertEquals("MOD The Wardens, phase 3: enraged on at tick 8067 (npc 11762)", lines.get(lines.size() - 1));
	}
}
