package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

// Which version of a boss the monster data is read as, and what a boss rule does to it. The fixture is a slice of the DPS
// calculator's monsters.json at c446f2e, every entry copied as it stands.
public class MonsterVersionTest
{
	private static BossRules rules;
	private static MonsterStatsProvider.Parsed data;

	@BeforeClass
	public static void load() throws Exception
	{
		final Gson gson = new Gson();
		rules = BossRules.load(gson, null);
		try (InputStreamReader in = new InputStreamReader(
			MonsterVersionTest.class.getResourceAsStream("monsters-sample.json"), StandardCharsets.UTF_8))
		{
			data = MonsterStatsProvider.parse(gson, in, rules);
		}
	}

	private static List<MonsterStatsProvider.MonsterStats> versions(int id)
	{
		return data.versionsById.get(id);
	}

	private static MonsterStatsProvider.Choice choose(int id, int observed, int hint)
	{
		return MonsterStatsProvider.choose(versions(id), rules.forNpc(id), observed, hint);
	}

	@Test
	public void everyVersionUnderAnIdIsKept()
	{
		assertEquals(2, versions(12223).size());
		assertEquals(9, versions(14707).size());
		assertEquals(1, versions(12224).size());
	}

	/** The bug: "first wins" read every DT2 boss as Awakened. */
	@Test
	public void dt2BossesReadAsTheOrdinaryFightUntilTheBarSaysOtherwise()
	{
		assertEquals("Post-quest", choose(12223, -1, -1).stats.getVersion());
		assertEquals(700, choose(12223, -1, -1).stats.getHitpoints());
		assertEquals("Post-quest", choose(12214, -1, -1).stats.getVersion());
		assertEquals(900, choose(12214, -1, -1).stats.getHitpoints());
		assertEquals("Post-quest, Awake", choose(12191, -1, -1).stats.getVersion());
		assertEquals(275, choose(12191, -1, -1).stats.getDefenceLevel());
	}

	@Test
	public void theBarsFullHitpointsPickAwakened()
	{
		assertEquals("Awakened", choose(12223, 1400, -1).stats.getVersion());
		assertEquals("Awakened", choose(12214, 2700, -1).stats.getVersion());
		assertEquals("Awakened, Awake", choose(12191, 1697, -1).stats.getVersion());
		assertTrue(choose(12214, 2700, -1).reason.contains("2700"));
	}

	@Test
	public void theBarPicksADelve()
	{
		assertEquals("Delve 3", choose(14707, 575, -1).stats.getVersion());
		assertEquals("Delve 8", choose(14707, 675, -1).stats.getVersion());
		// Nothing read yet: the smallest.
		assertEquals("Delve 1", choose(14707, -1, -1).stats.getVersion());
		// Delves differ only in hitpoints, so two that share them are not a phase problem.
		assertNull(choose(14707, 625, -1).stats.getWarning());
	}

	/** RuneLite's table settles a case the smallest gets wrong: ToB's blood spawn is 7 in entry mode, 120 otherwise. */
	@Test
	public void runelitesTableIsTheHintBeforeTheBar()
	{
		assertEquals("Entry", choose(8367, -1, -1).stats.getVersion());
		assertEquals("Normal", choose(8367, -1, 120).stats.getVersion());
		assertTrue(choose(8367, -1, 120).reason.contains("RuneLite"));
		// The bar still beats it.
		assertEquals("Entry", choose(8367, 7, 120).stats.getVersion());
		// A hint no version has is no hint.
		assertEquals("Permanent", choose(15547, -1, 1234).stats.getVersion());
	}

	@Test
	public void phasesThatShareHitpointsCarryAWarning()
	{
		final MonsterStatsProvider.Choice wardens = choose(11761, 880, -1);
		assertEquals("Damaged", wardens.stats.getVersion());
		assertNotNull(wardens.stats.getWarning());
		assertTrue(wardens.stats.getWarning().contains("Enraged"));
		// Yama has a rule saying what is unmodelled AND two same-hitpoints phases; both are said.
		final MonsterStatsProvider.Choice yama = choose(14176, -1, -1);
		assertTrue(yama.stats.getWarning().contains("Magic defence bonus"));
		assertTrue(yama.stats.getWarning().contains("Phase 3"));
	}

	@Test
	public void onlyVardorvisShapedStatsTripThePlaceholderWarning()
	{
		// The shape itself, without a rule.
		assertNotNull(MonsterStatsProvider.warningFor(0, 0, 280, 700, null, null));
		assertNull(MonsterStatsProvider.warningFor(215, 0, 280, 700, null, null));
		assertNull(MonsterStatsProvider.warningFor(0, 0, 0, 700, null, null));
		assertNull(MonsterStatsProvider.warningFor(0, 0, 1, 120, null, null));
		// Vardorvis is that shape, but every version of him is covered by a rule that scales his defence.
		for (MonsterStatsProvider.MonsterStats s : versions(12223))
		{
			assertNull(s.getVersion(), s.getWarning());
		}
		assertNull(versions(12224).get(0).getWarning());
		// A version the rule does not list is not covered.
		assertNotNull(MonsterStatsProvider.warningFor(0, 0, 280, 700, rules.forNpc(12223), null));
		// Nothing else in the slice trips it: an entry warns only where a rule says what is unmodelled.
		for (java.util.Map.Entry<Integer, List<MonsterStatsProvider.MonsterStats>> e : data.versionsById.entrySet())
		{
			for (MonsterStatsProvider.MonsterStats s : e.getValue())
			{
				final BossRules.Boss boss = rules.forNpc(e.getKey());
				assertEquals(s.getName(), boss == null ? null : boss.unmodelled, s.getWarning());
			}
		}
	}

	/**
	 * Every clean hit of the Vardorvis kills paid x1.55 (log review, bug 2): the
	 * wiki formula on the post-quest entry with his full-hitpoints strength and
	 * defence from the rule, not on the stored zeros.
	 */
	@Test
	public void postQuestVardorvisPaysTheBonusTheGameDoes()
	{
		final MonsterStatsProvider.MonsterStats post = choose(12223, -1, -1).stats;
		assertEquals(1.55, post.getXpMultiplier(), 1e-9);
	}

	@Test
	public void vardorvisDefenceFollowsWhatHeHasLeft()
	{
		final BossRules.Boss vard = rules.forNpc(12223);
		final MonsterStatsProvider.MonsterStats post = choose(12223, -1, -1).stats;
		assertEquals(0, post.getDefenceLevel());
		assertEquals(215, MonsterStatsProvider.atHitpoints(vard, post, -1, 700).getDefenceLevel());
		assertEquals(215, MonsterStatsProvider.atHitpoints(vard, post, -1, -1).getDefenceLevel());
		assertEquals(180, MonsterStatsProvider.atHitpoints(vard, post, -1, 350).getDefenceLevel());
		assertEquals(145, MonsterStatsProvider.atHitpoints(vard, post, -1, 0).getDefenceLevel());
		assertEquals(700, MonsterStatsProvider.atHitpoints(vard, post, -1, 350).getHitpoints());
		assertTrue(post.isDrainImmune());
		final MonsterStatsProvider.MonsterStats awakened = choose(12223, 1400, -1).stats;
		assertEquals(268, MonsterStatsProvider.atHitpoints(vard, awakened, 1400, 1400).getDefenceLevel());
		final MonsterStatsProvider.MonsterStats quest = choose(12224, 500, -1).stats;
		assertEquals(130, MonsterStatsProvider.atHitpoints(vard, quest, 500, 0).getDefenceLevel());
	}

	@Test
	public void aBossWithoutARuleIsLeftAlone()
	{
		final MonsterStatsProvider.MonsterStats leviathan = choose(12214, -1, -1).stats;
		assertEquals(250, MonsterStatsProvider.atHitpoints(rules.forNpc(12214), leviathan, -1, 0).getDefenceLevel());
		assertEquals(1, data.byName.get("goblin").size());
		assertEquals(3, data.byName.get("vardorvis").size());
	}
}
