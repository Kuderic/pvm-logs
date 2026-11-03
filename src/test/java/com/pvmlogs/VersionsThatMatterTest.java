package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * T-157: "boss data unsure" on ordinary goblins. Eric's npc 3034 and 3029 ('Goblin') aren't in the DPS calc's data, so
 * they resolve by name to its four Goblins; two of combat level 2 share 5 hitpoints but not Defence (1 and 4), and the
 * choice called them phases and warned. They're different monsters sharing a name, and the difference moves Eric's
 * blowpipe's hit chance by a fraction of a point.
 */
public class VersionsThatMatterTest
{
	// The DPS calc's four Goblins, as Eric's cached monsters.json has them (2026-11-02), the fields the plugin reads.
	private static final String GOBLINS = "["
		+ goblin(3046, "Level 13", 13, 12, 7, 16, 13, "{\"crush\": 8, \"magic\": 4, \"standard\": 4, \"slash\": 6, \"stab\": 4}")
		+ "," + goblin(3028, "Level 2", 2, 1, 1, 5, 1, minus15())
		+ "," + goblin(5192, "Level 2 (armed)", 2, 3, 4, 5, 1, minus15())
		+ "," + goblin(3045, "Level 5", 5, 3, 4, 12, 1, "{\"crush\": 0, \"magic\": 0, \"standard\": 0, \"slash\": 0, \"stab\": 0}")
		+ "]";

	private static String minus15()
	{
		return "{\"crush\": -15, \"magic\": -15, \"standard\": -15, \"slash\": -15, \"stab\": -15}";
	}

	private static String goblin(int id, String version, int level, int atk, int def, int hp, int str, String defensive)
	{
		return "{\"id\": " + id + ", \"name\": \"Goblin\", \"version\": \"" + version + "\", \"level\": " + level
			+ ", \"size\": 1, \"skills\": {\"atk\": " + atk + ", \"def\": " + def + ", \"hp\": " + hp
			+ ", \"magic\": 1, \"ranged\": 1, \"str\": " + str + "}, \"offensive\": {\"magic\": 0}, \"defensive\": "
			+ defensive + ", \"attributes\": []}";
	}

	private static BossRules rules;
	private static MonsterStatsProvider.Parsed goblins;

	@BeforeClass
	public static void load()
	{
		final Gson gson = new Gson();
		rules = BossRules.load(gson, null);
		goblins = MonsterStatsProvider.parse(gson, new StringReader(GOBLINS), rules);
	}

	private static List<MonsterStatsProvider.MonsterStats> named()
	{
		return goblins.byName.get("goblin");
	}

	@Test
	public void monstersFoundByNameArePickedByCombatLevel()
	{
		assertEquals(4, named().size());
		final List<MonsterStatsProvider.MonsterStats> two = MonsterStatsProvider.byCombatLevel(named(), 2);
		assertEquals(2, two.size());
		for (MonsterStatsProvider.MonsterStats s : two)
		{
			assertEquals(2, s.getCombatLevel());
		}
		assertEquals("Level 13", MonsterStatsProvider.byCombatLevel(named(), 13).get(0).getVersion());
		assertEquals("Level 5", MonsterStatsProvider.byCombatLevel(named(), 5).get(0).getVersion());
		// A level none has, or none read: all of them, as before.
		assertEquals(4, MonsterStatsProvider.byCombatLevel(named(), 7).size());
		assertEquals(4, MonsterStatsProvider.byCombatLevel(named(), -1).size());
	}

	@Test
	public void monstersSharingANameAreNotCalledPhases()
	{
		final MonsterStatsProvider.Choice choice = MonsterStatsProvider.choose(
			MonsterStatsProvider.byCombatLevel(named(), 2), null, -1, 5, true);
		assertEquals("Level 2", choice.stats.getVersion());
		// Nothing shown on the choice; the note names them as monsters sharing a name, not phases.
		assertNull(choice.stats.getWarning());
		assertEquals("Level 2 (armed)", choice.stats.getAlternative().getVersion());
		final String note = choice.stats.getVersionNote();
		assertTrue(note, note.startsWith("monsters named 'Goblin' share this hitpoints but not defences"));
		assertFalse(note, note.contains("phases"));
		// Level 5's 12 hitpoints aren't level 2's, so once the level is read they're not in the running at all.
		assertTrue(MonsterStatsProvider.byCombatLevel(named(), 5).size() == 1);
	}

	@Test
	public void theGoblinsDifferenceIsUnderTheBar()
	{
		// Eric's blowpipe, acc 0.990 against 'Level 2' ([4232] on): its attack roll, about 24,600. Ranged defence rolls:
		// (1 + 9) x (-15 + 64) = 490 and (4 + 9) x 49 = 637.
		final int attack = 24600;
		final double plain = CombatCalc.hitChanceFrom(attack, 10 * 49);
		final double armed = CombatCalc.hitChanceFrom(attack, 13 * 49);
		assertEquals(0.990, plain, 0.0005);
		assertTrue(armed < plain);
		// 0.3% of the expected damage: under the 1% bar, so nothing shows.
		assertEquals(0.003, (plain - armed) / plain, 0.0005);
		assertFalse(CombatCalc.versionsMatter(plain, armed));
	}

	@Test
	public void aBossPhaseStillShows()
	{
		// The Wardens' P3, Damaged (Defence 150) and Enraged (180), ranged defence +20: at an attack roll of 40,000, 3.8% of
		// the expected damage apart, past the bar.
		final int attack = 40000;
		final double damaged = CombatCalc.hitChanceFrom(attack, (150 + 9) * (20 + 64));
		final double enraged = CombatCalc.hitChanceFrom(attack, (180 + 9) * (20 + 64));
		assertTrue((damaged - enraged) / damaged > 0.03);
		assertTrue(CombatCalc.versionsMatter(damaged, enraged));
	}

	@Test
	public void theBarIsOnePercentOfTheExpectedDamage()
	{
		assertFalse(CombatCalc.versionsMatter(0.990, 0.9802));
		assertTrue(CombatCalc.versionsMatter(0.990, 0.9800));
		assertFalse(CombatCalc.versionsMatter(0.5, 0.496));
		assertTrue(CombatCalc.versionsMatter(0.5, 0.495));
		assertTrue(CombatCalc.versionsMatter(0, 0.01));
		assertFalse(CombatCalc.versionsMatter(-1, 0.5));
	}

	@Test
	public void aRuleThatSwitchesToTheOtherVersionLeavesNothingToNote()
	{
		// The Wardens' P3 with #175's rule: a damage modifier naming 'Enraged' while the enrage is open. The version is tracked.
		final BossRules.ModifierRule enraged = new BossRules.ModifierRule();
		enraged.name = "enraged";
		enraged.version = "Enraged";
		final BossRules.Boss boss = new BossRules.Boss();
		boss.name = "The Wardens, phase 3";
		boss.damageModifiers = new BossRules.DamageModifierRules();
		boss.damageModifiers.modifiers = new ArrayList<>(Collections.singletonList(enraged));
		assertTrue(BossRules.switchesToVersion(boss, "enraged"));
		final List<MonsterStatsProvider.MonsterStats> two = MonsterStatsProvider.byCombatLevel(named(), 2);
		// Stand-ins for the Wardens' two: the same shape, renamed so the rule names one.
		final List<MonsterStatsProvider.MonsterStats> phases = new ArrayList<>();
		phases.add(two.get(0));
		phases.add(renamed(two.get(1), "Enraged"));
		final MonsterStatsProvider.Choice tracked = MonsterStatsProvider.choose(phases, boss, -1, -1, false);
		assertNull(tracked.stats.getAlternative());
		assertNull(tracked.stats.getVersionNote());
		assertTrue(tracked.reason, tracked.reason.contains("switches to 'Enraged' itself"));
		// Without the rule, the note stays.
		assertNotNull(MonsterStatsProvider.choose(phases, null, -1, -1, false).stats.getVersionNote());
	}

	private static MonsterStatsProvider.MonsterStats renamed(MonsterStatsProvider.MonsterStats s, String version)
	{
		return new MonsterStatsProvider.MonsterStats(s.getName(), s.getSize(), s.getHitpoints(), s.getDefenceLevel(),
			s.getMagicLevel(), s.getOffensiveMagic(), s.getDefStab(), s.getDefSlash(), s.getDefCrush(), s.getDefMagic(),
			s.getDefRanged(), s.getWeaknessElement(), s.getWeaknessSeverity(), s.getAttributes(), s.getXpMultiplier(),
			version, false, null);
	}
}
