package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import net.runelite.api.Prayer;
import net.runelite.api.gameval.NpcID;
import org.junit.Test;

/**
 * T-221 (Eric, 2026-11-12: "Keris partisan of breaching has low expected damage in kephri fight"): his 11-11 raid at level
 * 350 ({@code pvm-debug-20261111-231111.log}, build {@code 14c2164}, read in place). The same keris, gear and stab read
 * accuracy 0.822 on Kephri's 11720 ('Aggressive', [5148]) and 0.570 on 11719 ('Shielded', [5215]): the raid level scaled
 * her defence roll on 11719 and 11721 but not on 11720, her middle form, missing from the list. Her entries' defences are
 * the same (monsters.json: def 80, +60 stab), and 11720 resolves by name to 11721's.
 */
public class KephriFormsTest
{
	private static final int[] FORMS = {NpcID.TOA_KEPHRI_BOSS_SHIELDED, NpcID.TOA_KEPHRI_BOSS_WEAK,
		NpcID.TOA_KEPHRI_BOSS_ENRAGE};
	/** Her stab defence roll from the table: (80 + 9) x (60 + 64). */
	private static final int TABLE_ROLL = (80 + 9) * (60 + 64);

	@Test
	public void everyFormHasItsDefenceRollScaledByTheRaidLevel()
	{
		for (int id : FORMS)
		{
			assertTrue(String.valueOf(id), RaidScaling.tombsScalesDefence(id));
			// Level 350: (250 + 350) / 250 = 2.4, the same for each.
			assertEquals(TABLE_ROLL * 600 / 250, RaidScaling.tombsDefenceRoll(TABLE_ROLL, id, 350));
		}
		// Her overlords and swarm aren't scaled (T-152).
		assertEquals(TABLE_ROLL, RaidScaling.tombsDefenceRoll(TABLE_ROLL, NpcID.TOA_KEPHRI_GUARDIAN_MELEE, 350));
	}

	@Test
	public void theBreachingKerisReadsTheSameAgainstEachForm()
	{
		// An attack roll that read 0.822 against the unscaled roll (11720 before): against the scaled one, 0.573, what 11719
		// read with the same keris and stab (0.570). Now each form gives that.
		final int attackRoll = 31_000;
		assertEquals(0.822, CombatCalc.hitChanceFrom(attackRoll, TABLE_ROLL), 0.001);
		double first = -1;
		for (int id : FORMS)
		{
			final double chance = CombatCalc.hitChanceFrom(attackRoll, RaidScaling.tombsDefenceRoll(TABLE_ROLL, id, 350));
			assertEquals(0.573, chance, 0.001);
			if (first >= 0)
			{
				assertEquals(first, chance, 0.0);
			}
			first = chance;
		}
	}

	@Test
	public void prayersTwoHalvesMoveTogether()
	{
		// [5412], scored prayed=false (Piety, the goal, not up): its row read accuracy 0.58 and an own max of 47, against
		// 0.599 and 50 at [5413] with Piety. Chivalry's halves (1.15 attack, 1.18 strength) against Piety's (1.20, 1.23)
		// give both, so the row had a prayer up, for both halves: scored as made, not with none and not half of one.
		final int defence = TABLE_ROLL * 600 / 250;
		final int pietyRoll = (int) Math.round(defence / (2 * (1 - 0.599)));
		final int chivalryRoll = (int) (pietyRoll * 1.15 / 1.20);
		assertEquals(0.58, CombatCalc.hitChanceFrom(chivalryRoll, defence), 0.005);
		// The max: Piety's 50 is a base of 38 through the keris's 1.33; Chivalry's strength takes the base to 36, 47.
		assertEquals(36, (int) (38 * 1.18 / 1.23));
		assertEquals(47, (int) (36 * 1.33));
		// Without any prayer the base would be 30 (x 1/1.23), a max of 39: not what the row read.
		assertEquals(39, (int) ((int) (38 / 1.23) * 1.33));
	}

	@Test
	public void theStyleAndThePrayerAreNamedInTheLog()
	{
		assertEquals("stab/aggressive (Lunge)", new AttackStyle(1, "Lunge", AttackType.STAB, CombatStyle.AGGRESSIVE).label());
		assertEquals("crush/aggressive (Pound)", new AttackStyle(2, "Pound", AttackType.CRUSH, CombatStyle.AGGRESSIVE)
			.label());
		assertEquals("Piety", CombatCalc.prayerName(Prayer.PIETY));
		assertEquals("Chivalry", CombatCalc.prayerName(Prayer.CHIVALRY));
		assertEquals("Incredible reflexes", CombatCalc.prayerName(Prayer.INCREDIBLE_REFLEXES));
		assertEquals("Decimate", CombatCalc.prayerName(Prayer.RP_DECIMATE));
	}
}
