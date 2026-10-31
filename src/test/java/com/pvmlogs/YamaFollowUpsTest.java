package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Skill;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-137, from Eric's Yama logs of 2026-10-31 (read in place, nothing copied):
 * the 14:02 duo log (build 2d9270f) and the 18:00 solo kill (build 4c6b379).
 */
public class YamaFollowUpsTest
{
	private static final BossRules RULES = BossRules.load(new Gson(), null);

	// ---- 1. Soul Rend ----

	@Test
	public void eachSoulRendClaimsItsOwnEnergyFall()
	{
		// [7608] and [7613]: two Soul Rends, each landing five ticks after its throw. The second fell before the first was
		// booked, and one tick held only the latest: the first was scored as an ordinary attack.
		final Deque<Integer> unspent = new ArrayDeque<>();
		unspent.addLast(7608);
		unspent.addLast(7613);
		assertEquals(7608, PvmPerformancePlugin.unspentSpecialFor(unspent, 7608));
		unspent.remove(7608);
		assertEquals(7613, PvmPerformancePlugin.unspentSpecialFor(unspent, 7613));
		unspent.remove(7613);
		// An ordinary cast after them claims nothing.
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.unspentSpecialFor(unspent, 7618));
		// The window is the same as before: the fall on the throw's tick or the one before it.
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.unspentSpecialFor(Collections.singletonList(7606), 7608));
		assertEquals(7607, PvmPerformancePlugin.unspentSpecialFor(Collections.singletonList(7607), 7608));
	}

	@Test
	public void soulRendStartsAFiveTickCooldown()
	{
		// Both Soul Rends booked "speed 3", the staff's ([7613], [7618]).
		assertEquals(5, PvmPerformancePlugin.speedAfter(3, SpecialAttack.EYE_OF_AYAK, false));
		assertEquals(3, PvmPerformancePlugin.speedAfter(3, null, false));
		// A special without a speed of its own keeps the weapon's; a stun's reset still wins.
		assertEquals(5, PvmPerformancePlugin.speedAfter(5, SpecialAttack.PURGING_STAFF, false));
		assertEquals(1, PvmPerformancePlugin.speedAfter(1, SpecialAttack.EYE_OF_AYAK, true));
		// Its XP proves the throw for its own five ticks: [7610]-[7612] were lost against the staff's three.
		for (int now = 7610; now <= 7612; now++)
		{
			assertTrue("tick " + now, PvmPerformancePlugin.throwProvenByXp(now, 7608, 7608,
				PvmPerformancePlugin.speedAfter(3, SpecialAttack.EYE_OF_AYAK, false)));
		}
		assertFalse(PvmPerformancePlugin.throwProvenByXp(7613, 7608, 7608, 5));
	}

	// ---- 2 and 6c. The flares' second splats ----

	@Test
	public void aLandingCappedBelowItsXpIsStillThatCast()
	{
		// [8092] a cast paid 77-78 on a Void Flare; its 77 landed on [8095], leaving 0-1 of the award open. [8097] the next
		// cast paid 63; a thrall's 1 on [8098] left the flare 62, and the cast landed for 62 on [8100]. The oldest open
		// award, [8092]'s, took the 62, thrown before the next due tick: the cast went unbooked ("attempts=1 ... hits=2").
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.DEFENCE, 77);
		ledger.add(Skill.HITPOINTS, 103);
		ledger.add(Skill.MAGIC, 146);
		final XpLedger.Award first = ledger.close(8092, NpcID.YAMA_VOIDFLARE, 1.0, null);
		assertNotNull(first);
		ledger.spend(77, 8095, true, NpcID.YAMA_VOIDFLARE);
		ledger.add(Skill.DEFENCE, 63);
		ledger.add(Skill.HITPOINTS, 84);
		ledger.add(Skill.MAGIC, 128);
		assertNotNull(ledger.close(8097, NpcID.YAMA_VOIDFLARE, 1.0, null));
		assertEquals(8097, ledger.attackTickFor(62, 8100));
		assertTrue(ledger.paidAsShot(62, 8100));
		assertEquals(8097, PvmPerformancePlugin.shotThrownOn(8100, 8097, 8097));
	}

	@Test
	public void aDropAfterTheLastBookedThrowIsANewAttack()
	{
		// The 18:00 log: a cast thrown on 1386 killed a flare on [1389]; the next went out on 1389, three ticks on, its
		// 71 killing the next flare on [1392]. Against the five-tick due (1391) it was refused, and that flare read
		// attempts=0.
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.shotThrownOn(1392, 1389, 1391));
		assertEquals(1389, PvmPerformancePlugin.shotThrownOn(1392, 1389, 1391, 1386));
		// A drop on the booked throw's own tick is that attack, landing late: still refused.
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.shotThrownOn(80, 74, 78, 74));
		// Nothing lands on its throw's tick, and no drop books nothing.
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.shotThrownOn(1389, 1389, 1391, 1386));
		assertEquals(Integer.MIN_VALUE, PvmPerformancePlugin.shotThrownOn(1392, Integer.MIN_VALUE, 1391, 1386));
	}

	@Test
	public void scatterAshesKillingADemonLeavesTheStaffAtThree()
	{
		// [8242] Scatter ashes killed a Void Flare as it landed on [8245]; the next cast went out on 8245.
		assertEquals(3, PvmPerformancePlugin.SCATTER_ASHES_KILL_SPEED);
		assertTrue(PvmPerformancePlugin.scatterAshesKill(SpecialAttack.PURGING_STAFF, true, 0));
		assertFalse(PvmPerformancePlugin.scatterAshesKill(SpecialAttack.PURGING_STAFF, true, 12));
		assertFalse(PvmPerformancePlugin.scatterAshesKill(SpecialAttack.PURGING_STAFF, false, 0));
		assertFalse(PvmPerformancePlugin.scatterAshesKill(null, true, 0));
		assertFalse(PvmPerformancePlugin.scatterAshesKill(SpecialAttack.EYE_OF_AYAK, true, 0));
	}

	// ---- 4. The phase change ----

	@Test
	public void yamasPhaseChangeIsAWindowWithAnAllowanceOf14()
	{
		final BossRules.Boss yama = RULES.forNpc(NpcID.YAMA);
		assertNotNull(yama.damageModifiers);
		final List<String> lines = new ArrayList<>();
		final ModifierTracker tracker = new ModifierTracker(lines::add);
		// Kill 1's first: 12147 on [7672], Eric's first throw at the Judge on 7689.
		tracker.onBossAnim(60992, "Yama", yama.damageModifiers, 12147, 7672);
		assertTrue(lines.get(0), lines.get(0).startsWith("MOD Yama: phase change on at tick 7672"));
		assertTrue(tracker.isOpen(60992, "phase change", 7689));
		assertFalse(tracker.isOpen(60992, "phase change", 7690));
		// No damage modifier rides on it.
		assertTrue(tracker.applying(60992, yama.damageModifiers, NpcID.YAMA, "MAGIC", 7680, true, (id, r) -> false)
			.stream().noneMatch(m -> "phase change".equals(m.state)));

		final BossRules.UptimeAllowance window = yama.uptimeAllowances.get(0);
		assertEquals("phase change", window.state);
		assertEquals(14, window.maxTicks);
		assertEquals(0, window.afterTicks);
		assertFalse(window.fromCast());
		// The window's 15 idle ticks (the most of the 12): 14 excused, one still lost.
		final MechanicAllowances mechanics = new MechanicAllowances(null);
		int excused = 0;
		for (int t = 7672; t <= 7692; t++)
		{
			final int tick = t;
			mechanics.observe(tick, yama.uptimeAllowances, state -> tick < 7690 ? new int[]{7672, 7690} : null);
			if (tick - 2 >= 7675 && tick - 2 <= 7689)
			{
				excused += mechanics.excuse(tick - 2) != null ? 1 : 0;
			}
		}
		assertEquals(14, excused);
	}

	// ---- 5. The MOD line and the Mark's message ----

	@Test
	public void theModLineComesWhenWhatAppliesChanges()
	{
		final List<BossRules.ModifierRule> notMagic = new ArrayList<>();
		final BossRules.ModifierRule rule = new BossRules.ModifierRule();
		rule.name = "tank not using magic";
		rule.tankMagic = false;
		rule.magicDefenceChange = -30;
		notMagic.add(rule);
		final ModifierTracker.Situation at99 = new ModifierTracker.Situation(0.99, false);
		final ModifierTracker.Situation at98 = new ModifierTracker.Situation(0.98, false);
		// [762] and [766]: the same modifier at 99% and 98%, printed twice.
		assertEquals(DamageModifiers.situationKey("you", at99, notMagic), DamageModifiers.situationKey("you", at98, notMagic));
		assertNotEquals(DamageModifiers.situationKey("you", at99, notMagic),
			DamageModifiers.situationKey("you", new ModifierTracker.Situation(0.99, true), notMagic));
		assertNotEquals(DamageModifiers.situationKey("you", at99, notMagic),
			DamageModifiers.situationKey("no player", at99, notMagic));
		assertNotEquals(DamageModifiers.situationKey("you", at99, notMagic),
			DamageModifiers.situationKey("you", at99, Collections.emptyList()));
		// The share stays in the line.
		assertEquals("MOD Yama: targeting you (not magic), 98% of its hitpoints left: tank not using magic (magic defence -30)",
			DamageModifiers.situationLine("Yama", "you", at98, notMagic));
	}

	@Test
	public void theMarksMessageIsLoggedFromTheGameNotFromChat()
	{
		assertTrue(PvmPerformancePlugin.isMarkMessage(ChatMessageType.GAMEMESSAGE,
			"You have placed a Mark of Darkness upon yourself."));
		assertTrue(PvmPerformancePlugin.isMarkMessage(ChatMessageType.SPAM, "<col=ef1020>Mark of Darkness</col>"));
		assertFalse(PvmPerformancePlugin.isMarkMessage(ChatMessageType.PUBLICCHAT, "Mark of Darkness?"));
		assertFalse(PvmPerformancePlugin.isMarkMessage(ChatMessageType.GAMEMESSAGE, "Oh dear, you are dead!"));
		assertFalse(PvmPerformancePlugin.isMarkMessage(ChatMessageType.GAMEMESSAGE, null));
	}

	// ---- 6a. The Judge's casts ----

	@Test
	public void whatPaidASplatIsAskedBeforeTheSplatIsSpent()
	{
		// [968] a Purging staff cast paid 48-49 Magic on the Judge; its 49 landed on [971] with the Emberlight in hand. Asked
		// after the splat was spent, the award was used up and said nothing, so the cast booked as an Emberlight blow and
		// was scored with its max of 37 (MAX EXCEEDED, as on [1206], [1217] and [1226]).
		final XpLedger ledger = new XpLedger();
		ledger.add(Skill.DEFENCE, 49);
		ledger.add(Skill.HITPOINTS, 65);
		ledger.add(Skill.MAGIC, 109);
		assertNotNull(ledger.close(968, NpcID.YAMA_JUDGE_OF_YAMA, 1.0, null));
		assertTrue(ledger.paidAsShot(49, 971));
		ledger.spend(49, 971, true, NpcID.YAMA_JUDGE_OF_YAMA);
		assertFalse(ledger.paidAsShot(49, 971));
	}

	// ---- 6b. The Judge's fireball on itself ----

	@Test
	public void theJudgesFireballOnItselfIsANamedRoomSource()
	{
		final BossRules.Boss judge = RULES.forNpc(NpcID.YAMA_JUDGE_OF_YAMA);
		assertNotNull(judge.roomDamage);
		// [1212]-[1232]: 14-18 every 5 ticks, no other player there.
		for (int amount = 13; amount <= 18; amount++)
		{
			final BossRules.RoomDamageRule rule = RoomDamage.ruleFor(judge.roomDamage, amount, 300, s -> false);
			assertNotNull("amount " + amount, rule);
			assertEquals("hit itself", rule.label);
		}
		assertNull(RoomDamage.ruleFor(judge.roomDamage, 49, 300, s -> false));
		assertNotNull(judge.roomDamage.get(0).tip);
	}
}
