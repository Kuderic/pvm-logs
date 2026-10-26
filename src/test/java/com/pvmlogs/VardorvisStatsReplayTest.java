package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

/**
 * Replay of the first real-fight log (Eric's Vardorvis kills, 2026-10-26) against
 * the fixed stats. The log header gives the loadout: Abyssal tentacle (or), slash
 * accurate, attack bonus +142, attack 118 (99 + super combat), Piety up in the
 * fight. Before the fix every attack was expected to land 97-98%; Eric reads about
 * half at full health, and 260 attacks of his later kills landed 68.5%.
 */
public class VardorvisStatsReplayTest
{
	// floor(118 * 1.20) for Piety, +3 accurate, +8.
	private static final int EFFECTIVE_ATTACK = 118 * 120 / 100 + 3 + 8;
	private static final int ATTACK_BONUS = 142;
	// Vardorvis's slash defence bonus in the monster data.
	private static final int SLASH_DEFENCE = 65;

	private static MonsterStatsProvider.MonsterStats postQuest(int hpLeft) throws Exception
	{
		final Gson gson = new Gson();
		final BossRules rules = BossRules.load(gson, null);
		try (InputStreamReader in = new InputStreamReader(
			getResource("monsters-sample.json"), StandardCharsets.UTF_8))
		{
			final MonsterStatsProvider.Parsed data = MonsterStatsProvider.parse(gson, in, rules);
			final MonsterStatsProvider.MonsterStats base =
				MonsterStatsProvider.choose(data.versionsById.get(12223), rules.forNpc(12223), -1, -1).stats;
			return MonsterStatsProvider.atHitpoints(rules.forNpc(12223), base, -1, hpLeft);
		}
	}

	private static java.io.InputStream getResource(String name)
	{
		return VardorvisStatsReplayTest.class.getResourceAsStream(name);
	}

	private static double accuracy(MonsterStatsProvider.MonsterStats vard)
	{
		final int attRoll = CombatCalc.attackRoll(EFFECTIVE_ATTACK, ATTACK_BONUS, 1.0);
		final int defRoll = (vard.getDefenceLevel() + 9) * (vard.getDefSlash() + 64);
		return CombatCalc.hitChanceFrom(attRoll, defRoll);
	}

	@Test
	public void theTentacleLandsAboutHalfAtFullHealth() throws Exception
	{
		final MonsterStatsProvider.MonsterStats full = postQuest(700);
		assertEquals(215, full.getDefenceLevel());
		assertEquals(SLASH_DEFENCE, full.getDefSlash());
		assertEquals(0.539, accuracy(full), 0.005);
	}

	@Test
	public void andAboutTwoThirdsAsHeDies() throws Exception
	{
		final MonsterStatsProvider.MonsterStats dying = postQuest(0);
		assertEquals(145, dying.getDefenceLevel());
		assertEquals(0.683, accuracy(dying), 0.005);
	}

	/** What the log's figures were: defence 0 put every swing near certain. */
	@Test
	public void theStoredZeroIsWhatMadeItNinetyEight() throws Exception
	{
		final MonsterStatsProvider.MonsterStats stored = postQuest(700);
		final int attRoll = CombatCalc.attackRoll(EFFECTIVE_ATTACK, ATTACK_BONUS, 1.0);
		final double atZero = CombatCalc.hitChanceFrom(attRoll, (0 + 9) * (stored.getDefSlash() + 64));
		assertTrue("" + atZero, atZero > 0.97);
	}

	/** Over a kill the average sits between the two, which is where the log's 26 of 42 (62%) landed. */
	@Test
	public void aWholeKillAveragesNearWhatLanded() throws Exception
	{
		double sum = 0;
		int n = 0;
		for (int hp = 700; hp >= 0; hp -= 10)
		{
			sum += accuracy(postQuest(hp));
			n++;
		}
		final double mean = sum / n;
		assertEquals(0.61, mean, 0.02);
		// 26 of 42 is well inside luck at that rate.
		assertTrue(Math.abs(26 - 42 * mean) < 2 * Math.sqrt(42 * mean * (1 - mean)));
	}
}
