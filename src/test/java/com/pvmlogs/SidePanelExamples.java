package com.pvmlogs;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Fight tab's states 1 to 8 (spec section 6) as model inputs, from the
 * overlay's fixture scopes ({@link LedgerExamples}): Eric's Vardorvis fight at
 * 1:13 and kill at 2:17, and the spec's illustrative ended fight, Sire room and
 * raid. Shared by {@link SidePanelModelTest}, {@link SidePanelSmokeTest} and
 * {@link SidePanelPictures}.
 */
final class SidePanelExamples
{
	/** Times on the renders are this zone's, so they read the same on every machine. */
	static final ZoneId ZONE = ZoneId.of("America/Chicago");

	private SidePanelExamples()
	{
	}

	static long at(int hour, int minute)
	{
		return ZonedDateTime.of(2026, 10, 27, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli();
	}

	static SidePanelModel.Input empty()
	{
		final SidePanelModel.Input in = base();
		in.kind = SidePanelModel.Kind.EMPTY;
		return in;
	}

	/** Before the first attack: the tentacle and the burning claws' spec (T-030), and the 2:17 kill as the last fight. */
	static SidePanelModel.Input preAttack()
	{
		final SidePanelModel.Input in = base();
		in.kind = SidePanelModel.Kind.PRE_ATTACK;
		in.name = "Vardorvis";
		in.maxHp = 700;
		final LedgerRows.Loadout l = LedgerExamples.loadout();
		l.specMaxHit = 48;
		l.specAverage = 17.8;
		l.specExtra = 8.2;
		l.specExtraKind = "burn";
		in.loadout = l;
		in.specName = "Burning claws";
		final SidePanelModel.LastFight last = new SidePanelModel.LastFight();
		last.name = "Vardorvis";
		last.killed = true;
		last.endMillis = at(15, 31);
		last.numbers = new LedgerRows.Numbers(killScope());
		in.lastFight = last;
		in.historyFights = 12;
		return in;
	}

	/** During the fight: Eric's 1:13, 370 against 418, thrall 48. */
	static SidePanelModel.Input live()
	{
		final SidePanelModel.Input in = room("Vardorvis", midScope());
		in.scoredThrall = 48;
		in.hitsTaken = 7;
		return in;
	}

	/** Boss dead: Eric's 2:17 kill, against an illustrative usual of 1:54 and a luck-removed 1:57. */
	static SidePanelModel.Input dead()
	{
		final SidePanelModel.Input in = room("Vardorvis", killScope());
		in.ended = true;
		in.killed = true;
		in.endMillis = at(15, 31);
		in.scoredThrall = 80;
		in.hitsTaken = 14;
		in.average = new LedgerHistory.Average(new LedgerHistory.Spread(114, 108, 121), 10);
		in.luckRemoved = new LedgerHistory.Spread(117, 114, 120);
		return in;
	}

	/** Ended without a kill: the overlay spec's illustrative "Ended" row. */
	static SidePanelModel.Input ended()
	{
		final LedgerExamples.FakeScope s = LedgerExamples.ended();
		s.damageVariance = 8000;
		s.biggestHitTaken = 33;
		final SidePanelModel.Input in = room("Vardorvis", s);
		in.ended = true;
		in.killed = false;
		in.endMillis = at(15, 44);
		in.scoredDot = 40;
		in.hitsTaken = 16;
		return in;
	}

	/** Boss data unsure, at 1:13. */
	static SidePanelModel.Input unsure()
	{
		final SidePanelModel.Input in = live();
		in.warning = "Vardorvis: two versions share this HP";
		in.maxHp = 0;
		return in;
	}

	/**
	 * The Sire's room (T-020), shaped on the 01:30 log review: 1:52 on the clock,
	 * 1:21 of it on the Sire, four respiratory systems unscored.
	 */
	static SidePanelModel.Input unscoredAdds()
	{
		final LedgerExamples.FakeScope s = new LedgerExamples.FakeScope();
		s.combatTicks = 187;
		s.scoredCombatTicks = 135;
		s.damage = 544;
		s.expectedDamage = 612;
		s.dotDamage = 8;
		s.ticksLost = 10;
		s.damageTaken = 130;
		s.dotTaken = 27;
		s.biggestHitTaken = 12;
		s.attacksMade = 33;
		s.attacksPrayed = 33;
		s.attacksSwitched = 33;
		s.hits = 23;
		s.expectedHits = 20.2;
		s.damageVariance = 9000;
		s.setup(0.98, 0.9, 1.2, 0);
		final SidePanelModel.Input in = room("Abyssal Sire", s);
		in.maxHp = 400;
		in.ended = true;
		in.killed = true;
		in.endMillis = at(15, 2);
		in.unscored = true;
		in.scoredDot = 8;
		in.hitsTaken = 41;
		in.average = new LedgerHistory.Average(new LedgerHistory.Spread(109, 104, 116), 8);
		final SidePanelModel.Target sire = new SidePanelModel.Target();
		sire.name = "Abyssal Sire";
		sire.fights = 3;
		sire.damage = 544;
		sire.expected = 612;
		sire.attacks = 33;
		sire.combatTicks = 135;
		sire.phases = 4;
		sire.scored = true;
		final SidePanelModel.Target lungs = new SidePanelModel.Target();
		lungs.name = "Respiratory system";
		lungs.fights = 4;
		lungs.damage = 197;
		lungs.attacks = 5;
		lungs.combatTicks = 52;
		lungs.phases = 1;
		lungs.scored = false;
		in.targets = Arrays.asList(sire, lungs);
		return in;
	}

	/** The whole raid: three ToA rooms, the overlay spec's raid row. */
	static SidePanelModel.Input raid()
	{
		final LedgerExamples.FakeScope s = LedgerExamples.raid();
		s.damageVariance = 40_000;
		s.biggestHitTaken = 48;
		s.damageTakenUnprotected = 212;
		final SidePanelModel.Input in = base();
		in.kind = SidePanelModel.Kind.RAID;
		in.scope = s;
		in.name = "Tombs of Amascut";
		in.roomCount = 3;
		in.inRaid = true;
		in.wholeRaid = true;
		in.startMillis = at(15, 58);
		in.ended = true;
		in.scoredThrall = 410;
		in.hitsTaken = 96;
		in.rooms = Arrays.asList(raidRoom("Ba-Ba", 152, 1700, 1640, 0.97), raidRoom("Kephri", 203, 2900, 2760, 0.95),
			raidRoom("Wardens", 565, 3810, 3800, 0.95));
		return in;
	}

	private static SidePanelModel.Room raidRoom(String name, int ticks, int damage, double expected, double efficiency)
	{
		final LedgerExamples.FakeScope s = new LedgerExamples.FakeScope();
		s.combatTicks = ticks;
		s.damage = damage;
		s.expectedDamage = expected;
		s.ticksLost = ticks / 10;
		s.attacksMade = ticks / 4;
		s.damageVariance = expected * 4;
		s.setup(efficiency, 0.95, 1, 0);
		final SidePanelModel.Room r = new SidePanelModel.Room();
		r.name = name;
		r.combatTicks = ticks;
		r.numbers = new LedgerRows.Numbers(s);
		return r;
	}

	/** Every state in the spec's order, keyed by the render's file name. */
	static Map<String, SidePanelModel.Input> states()
	{
		final Map<String, SidePanelModel.Input> m = new LinkedHashMap<>();
		m.put("1-empty", empty());
		m.put("2-pre-attack", preAttack());
		m.put("3-live", live());
		m.put("4-dead", dead());
		m.put("5-ended", ended());
		m.put("6-unsure", unsure());
		m.put("7-unscored-adds", unscoredAdds());
		m.put("8-raid", raid());
		return m;
	}

	static LedgerExamples.FakeScope midScope()
	{
		final LedgerExamples.FakeScope s = LedgerExamples.midFight();
		s.damageVariance = 6000;
		s.biggestHitTaken = 28;
		return s;
	}

	static LedgerExamples.FakeScope killScope()
	{
		final LedgerExamples.FakeScope s = LedgerExamples.kill();
		// The canvas's estimate (spec section 10): 688 against 778 over 11,436, the bottom 20%.
		s.damageVariance = 11436;
		s.biggestHitTaken = 31;
		s.damageTakenUnprotected = 22;
		return s;
	}

	private static SidePanelModel.Input room(String name, LedgerExamples.FakeScope scope)
	{
		final SidePanelModel.Input in = base();
		in.kind = SidePanelModel.Kind.ROOM;
		in.scope = scope;
		in.name = name;
		in.scoredName = name;
		in.maxHp = 700;
		in.solo = true;
		return in;
	}

	private static SidePanelModel.Input base()
	{
		final SidePanelModel.Input in = new SidePanelModel.Input();
		in.zone = ZONE;
		return in;
	}
}
