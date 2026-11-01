package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import java.util.Map;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;
import org.junit.Test;

/**
 * T-141: a fight's drains per stat and per source, each with the expected damage it added. Driven as the plugin drives
 * {@link DrainLuck}, with the real {@link DrainTracker}; the figures are a model of the game's: a melee blow rolls
 * against Defence, a cast against Magic and the magic defence bonus (Yama's +60 while his target uses magic, with the
 * Eye of ayak's reduction on it, the wiki's rule).
 */
public class DrainsPerStatTest
{
	private static final int INDEX = 7;
	private static final int YAMA = 14176;
	/** Yama as the drains see him: 225 Defence (floor 145), 250 Magic, the data's +0 magic defence. */
	private static final DrainRule.Target TARGET = new DrainRule.Target(225, 250, 0, 250, 250, 250, 145, 0, false, true,
		false, false, 100);
	/** T-135: his +60 while his target uses magic, which the figures add as the plugin's modifiers do. */
	private static final int TANK_USES_MAGIC = 60;

	private final DrainTracker tracker = new DrainTracker(null);
	private String inHand = "melee";

	// A melee blow: attack roll 30000 against (Defence + 9) x 64, max 60. A cast: 40000 against (Magic + 9) x (64 + bonus),
	// max 61. A special is the same roll with its own max.
	private double[] figuresAt(DrainRule.Target base, DrainRule.State s)
	{
		final boolean magic = "magic".equals(inHand);
		final int bonus = DrainRule.magicDefenceBonus(base.magicDefence + TANK_USES_MAGIC,
			DrainRule.magicDefenceReduction(s, base));
		final double p = magic ? CombatCalc.hitChanceFrom(40000, (s.magic + 9) * (64 + bonus))
			: CombatCalc.hitChanceFrom(30000, (s.defence + 9) * 64);
		final int max = magic ? 61 : 60;
		final int specMax = magic ? 42 : 60;
		final double[] ordinary = SpecOutcomesTest.roll(p, max);
		final double[] special = SpecOutcomesTest.roll(p, specMax);
		final double[] f = new double[CombatCalc.AT_WIDTH];
		f[CombatCalc.AT_MEAN] = ordinary[0];
		f[CombatCalc.AT_VARIANCE] = ordinary[1];
		f[CombatCalc.AT_SPEC_MEAN] = special[0];
		f[CombatCalc.AT_SPEC_VARIANCE] = special[1];
		f[CombatCalc.AT_SPEC_LAND] = p;
		f[CombatCalc.AT_SPEC_ACCURACY] = p;
		f[CombatCalc.AT_SPEC_MAX] = specMax;
		f[CombatCalc.AT_CAP] = Integer.MAX_VALUE;
		f[CombatCalc.AT_MAGIC_DEFENCE] = bonus;
		return f;
	}

	private DrainLuck luck()
	{
		return new DrainLuck(tracker::tracked, (npcId, base, s) -> figuresAt(base, s), (e, v) -> { }, null);
	}

	private static Fight fight()
	{
		return new Fight("Yama", YAMA, INDEX, 2500, 0L, null, 0);
	}

	private DrainRule.State now()
	{
		return tracker.tracked(INDEX).state;
	}

	private void attack(DrainLuck luck, Fight f, int tick, String style)
	{
		inHand = style;
		luck.snapshot(f, YAMA, tick);
		final double[] fig = figuresAt(TARGET, now());
		f.recordExpected(60, fig[CombatCalc.AT_SPEC_ACCURACY], fig[CombatCalc.AT_MEAN], fig[CombatCalc.AT_VARIANCE]);
		luck.booked(f, tick, tick + 1, false, null, fig[CombatCalc.AT_MEAN], fig[CombatCalc.AT_VARIANCE]);
		tracker.tick(tick + 1);
		luck.tick(tick + 1);
	}

	// An own special thrown on `tick`, landing for `hit` on the next: booked from its splat, the tracker draining as it lands.
	private void special(DrainLuck luck, Fight f, int tick, SpecialWeapon weapon, String style, int hit)
	{
		inHand = style;
		tracker.ownThrow(tick, weapon, INDEX, YAMA, "Yama", tick + 1, TARGET, false);
		luck.snapshot(f, YAMA, tick);
		final double[] fig = figuresAt(TARGET, now());
		f.recordExpected(60, fig[CombatCalc.AT_SPEC_LAND], fig[CombatCalc.AT_SPEC_MEAN], fig[CombatCalc.AT_SPEC_VARIANCE]);
		luck.booked(f, tick, tick + 1, true, weapon, fig[CombatCalc.AT_SPEC_MEAN], fig[CombatCalc.AT_SPEC_VARIANCE]);
		tracker.ownSplat(INDEX, tick + 1, hit);
		tracker.tick(tick + 1);
		luck.tick(tick + 1);
	}

	/** One Emberlight landing, then melee blows and staff casts in turn: the fight as the 18:30 log's first Yama stretch. */
	private Fight emberlightFight(DrainLuck luck, boolean melee, boolean casts)
	{
		final Fight f = fight();
		luck.start(f, tracker.track(INDEX, YAMA, "Yama", TARGET));
		special(luck, f, 10, SpecialWeapon.EMBERLIGHT, "melee", 48);
		for (int tick = 20; tick < 80; tick += 5)
		{
			if (melee)
			{
				attack(luck, f, tick, "melee");
			}
			if (casts)
			{
				attack(luck, f, tick + 2, "magic");
			}
		}
		return f;
	}

	private Fight soulRendFight(DrainLuck luck, boolean melee, boolean casts)
	{
		final Fight f = fight();
		luck.start(f, tracker.track(INDEX, YAMA, "Yama", TARGET));
		special(luck, f, 10, SpecialWeapon.EYE_OF_AYAK, "magic", 33);
		for (int tick = 20; tick < 80; tick += 5)
		{
			if (melee)
			{
				attack(luck, f, tick, "melee");
			}
			if (casts)
			{
				attack(luck, f, tick + 2, "magic");
			}
		}
		return f;
	}

	private static double value(Map<String, Double> m, String key)
	{
		return m.getOrDefault(key, 0.0);
	}

	private static double sum(Map<String, Double> m)
	{
		return m.values().stream().mapToDouble(Double::doubleValue).sum();
	}

	/** The Emberlight lowers Defence: its value is the melee blows', all of it under Defence and the Emberlight. */
	@Test
	public void anEmberlightOnly()
	{
		final DrainLuck luck = luck();
		final Fight f = emberlightFight(luck, true, false);
		assertEquals(191, now().defence);
		final Map<String, Double> byStat = luck.valueByStat(f);
		assertTrue(byStat.toString(), value(byStat, DrainRecord.StatDrain.DEFENCE) > 1);
		assertEquals(0, value(byStat, DrainRecord.StatDrain.MAGIC_DEFENCE), 1e-9);
		// The combined figure is their sum, as before.
		assertEquals(f.getDrainValueDamage(), sum(byStat), 1e-9);
		assertEquals(value(byStat, DrainRecord.StatDrain.DEFENCE),
			value(luck.valueBySource(f).get("EMBERLIGHT"), DrainRecord.StatDrain.DEFENCE), 1e-9);
	}

	/** A Defence drain adds nothing to a cast: Yama's magic roll is his Magic level and magic defence bonus. */
	@Test
	public void aDefenceDrainAddsNothingToCasts()
	{
		final DrainLuck luck = luck();
		final Fight f = emberlightFight(luck, false, true);
		assertEquals(191, now().defence);
		assertEquals(0, f.getDrainValueDamage(), 1e-9);
		assertEquals(0, sum(luck.valueByStat(f)), 1e-9);
	}

	/** Soul Rend lowers the magic defence bonus: its value is the casts', under magic defence and the Eye of ayak. */
	@Test
	public void aSoulRendOnly()
	{
		final DrainLuck luck = luck();
		final Fight f = soulRendFight(luck, true, true);
		assertEquals(33, DrainRule.magicDefenceReduction(now(), TARGET));
		final Map<String, Double> byStat = luck.valueByStat(f);
		assertTrue(byStat.toString(), value(byStat, DrainRecord.StatDrain.MAGIC_DEFENCE) > 1);
		assertEquals(0, value(byStat, DrainRecord.StatDrain.DEFENCE), 1e-9);
		assertEquals(f.getDrainValueDamage(), sum(byStat), 1e-9);
		assertEquals(value(byStat, DrainRecord.StatDrain.MAGIC_DEFENCE),
			value(luck.valueBySource(f).get("EYE_OF_AYAK"), DrainRecord.StatDrain.MAGIC_DEFENCE), 1e-9);
	}

	/**
	 * Both in one fight: each one's value is what it is alone. The Emberlight's is the melee blows', Soul Rend's the casts',
	 * and the combined value their sum.
	 */
	@Test
	public void bothAreIndependent()
	{
		final DrainLuck luckA = luck();
		final double emberlight = luckA.valueByStat(emberlightFight(luckA, true, true)).get(DrainRecord.StatDrain.DEFENCE);
		tracker.clear();
		final DrainLuck luckB = luck();
		final double soulRend = luckB.valueByStat(soulRendFight(luckB, true, true))
			.get(DrainRecord.StatDrain.MAGIC_DEFENCE);
		tracker.clear();

		final DrainLuck luck = luck();
		final Fight f = fight();
		luck.start(f, tracker.track(INDEX, YAMA, "Yama", TARGET));
		special(luck, f, 5, SpecialWeapon.EMBERLIGHT, "melee", 48);
		special(luck, f, 10, SpecialWeapon.EYE_OF_AYAK, "magic", 33);
		for (int tick = 20; tick < 80; tick += 5)
		{
			attack(luck, f, tick, "melee");
			attack(luck, f, tick + 2, "magic");
		}
		final Map<String, Double> byStat = luck.valueByStat(f);
		assertEquals(emberlight, value(byStat, DrainRecord.StatDrain.DEFENCE), 1e-6);
		assertEquals(soulRend, value(byStat, DrainRecord.StatDrain.MAGIC_DEFENCE), 1e-6);
		assertEquals(f.getDrainValueDamage(), sum(byStat), 1e-9);
		final Map<String, Map<String, Double>> bySource = luck.valueBySource(f);
		assertEquals(emberlight, sum(bySource.get("EMBERLIGHT")), 1e-6);
		assertEquals(soulRend, sum(bySource.get("EYE_OF_AYAK")), 1e-6);

		// The record: both stats, both sources, what each took and added.
		f.recordDrain(tracker.record(INDEX, 0));
		final DrainRecord r = f.getDrainRecord();
		assertEquals(2, r.version);
		final DrainRecord.StatDrain defence = r.stat(DrainRecord.StatDrain.DEFENCE);
		assertEquals(225, defence.start);
		assertEquals(191, defence.end);
		assertEquals(145, defence.floor);
		assertEquals(100, defence.regenTicks);
		assertEquals(emberlight, defence.value, 1e-6);
		final DrainRecord.StatDrain magicDefence = r.stat(DrainRecord.StatDrain.MAGIC_DEFENCE);
		assertEquals(0, magicDefence.base);
		assertEquals(-33, magicDefence.end);
		assertEquals(Integer.valueOf(60), magicDefence.bonusAtStart);
		assertEquals(Integer.valueOf(27), magicDefence.bonusAtEnd);
		assertEquals(soulRend, magicDefence.value, 1e-6);
		assertNull(r.stat(DrainRecord.StatDrain.MAGIC));
		assertEquals(2, r.sources.size());
		final DrainRecord.SourceDrain ember = r.sources.get(0);
		assertEquals("EMBERLIGHT", ember.weapon);
		assertEquals(1, ember.thrown);
		assertEquals(1, ember.landed);
		assertEquals(Integer.valueOf(34), ember.drained.get(DrainRecord.StatDrain.DEFENCE));
		assertEquals(emberlight, ember.value, 1e-6);
		final DrainRecord.SourceDrain eye = r.sources.get(1);
		assertEquals("EYE_OF_AYAK", eye.weapon);
		assertEquals(Integer.valueOf(33), eye.drained.get(DrainRecord.StatDrain.MAGIC_DEFENCE));
		assertEquals(soulRend, eye.value, 1e-6);
		// And through the history file's Gson.
		final DrainRecord back = new Gson().fromJson(new Gson().toJson(r), DrainRecord.class);
		assertEquals(emberlight, back.stat(DrainRecord.StatDrain.DEFENCE).value, 1e-6);
		assertEquals(soulRend, back.sources.get(1).value, 1e-6);
	}

	/** A warhammer and a Bandos godsword on one boss: two sources on one stat, their values summing to its value. */
	@Test
	public void aWarhammerAndAGodswordOnOneStat()
	{
		final DrainLuck luck = luck();
		final Fight f = fight();
		luck.start(f, tracker.track(INDEX, YAMA, "Yama", TARGET));
		special(luck, f, 5, SpecialWeapon.DRAGON_WARHAMMER, "melee", 40);
		special(luck, f, 10, SpecialWeapon.BANDOS_GODSWORD, "melee", 20);
		for (int tick = 20; tick < 80; tick += 5)
		{
			attack(luck, f, tick, "melee");
		}
		// 225 - 30% = 158 (the warhammer, of the current Defence), then 20 off: 138, held at the floor of 145.
		assertEquals(145, now().defence);
		final Map<String, Double> byStat = luck.valueByStat(f);
		final Map<String, Map<String, Double>> bySource = luck.valueBySource(f);
		final double hammer = value(bySource.get("DRAGON_WARHAMMER"), DrainRecord.StatDrain.DEFENCE);
		final double godsword = value(bySource.get("BANDOS_GODSWORD"), DrainRecord.StatDrain.DEFENCE);
		assertTrue(bySource.toString(), hammer > godsword && godsword > 0);
		assertEquals(value(byStat, DrainRecord.StatDrain.DEFENCE), hammer + godsword, 1e-9);
		assertEquals(f.getDrainValueDamage(), sum(byStat), 1e-9);
		f.recordDrain(tracker.record(INDEX, 0));
		assertEquals(1, f.getDrainRecord().stats.size());
		assertEquals(2, f.getDrainRecord().sources.size());
	}

	/** A record saved before T-141: no per-stat or per-source fields, and filling values into it does nothing. */
	@Test
	public void anOldRecord()
	{
		final String json = "{\"version\":1,\"defenceBase\":225,\"defenceFloor\":145,\"defenceStart\":225,\"defenceEnd\":191,"
			+ "\"specs\":{\"EMBERLIGHT\":[1,1]},\"partyDrains\":0,\"events\":[]}";
		final DrainRecord r = new Gson().fromJson(json, DrainRecord.class);
		assertEquals(1, r.version);
		assertNull(r.stats);
		assertNull(r.sources);
		r.fillValues(java.util.Collections.singletonMap(DrainRecord.StatDrain.DEFENCE, 12.0), null, 60, 27);
		assertNull(r.stats);
		assertNull(r.stat(DrainRecord.StatDrain.DEFENCE));
		assertEquals(1, r.ownLanded());
		final Fight f = fight();
		f.recordDrain(r);
		f.recordDrainValues(java.util.Collections.singletonMap(DrainRecord.StatDrain.DEFENCE, 12.0), null, null, null);
		assertEquals(191, f.getDefenceEnd());
	}

	/** A form change carries the Eye of ayak's reduction whole onto the new form's bonus (the wiki: Zulrah). */
	@Test
	public void aFormChangeCarriesTheReduction()
	{
		final DrainRule.Target serpentine = new DrainRule.Target(300, 300, 0, 1, 1, 1, 0, 0, false, false, false, false);
		final DrainRule.Target tanzanite = new DrainRule.Target(300, 300, 50, 1, 1, 1, 0, 0, false, false, false, false);
		final DrainRule.State hit = DrainRule.apply(DrainRule.State.of(serpentine), SpecialWeapon.EYE_OF_AYAK, 30, true,
			serpentine).after;
		final DrainRule.State onto = DrainTracker.within(hit, serpentine, tanzanite);
		assertEquals(30, DrainRule.magicDefenceReduction(onto, tanzanite));
		assertEquals(20, DrainRule.magicDefenceBonus(tanzanite.magicDefence, 30));
		assertNotNull(onto);
	}
}
