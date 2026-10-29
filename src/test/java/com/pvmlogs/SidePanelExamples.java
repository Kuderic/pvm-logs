package com.pvmlogs;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
		// T-036: the tentacle and one claw spec, as the canvas splits it.
		in.view = SidePanelTimelineExamples.live().view();
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
		in.view = SidePanelTimelineExamples.kill().view();
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
		in.scoredDotByKind = kinds("burn", 40);
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
		in.scoredDotByKind = kinds("burn", 8);
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

	/** State 9: the overlay spec's trip row (12:40, six kills) with one ended fight among them. */
	static SidePanelModel.Input trip()
	{
		final LedgerExamples.FakeScope s = LedgerExamples.trip();
		s.damageVariance = 60_000;
		s.biggestHitTaken = 33;
		s.damageTakenUnprotected = 64;
		final SidePanelModel.Input in = base();
		in.kind = SidePanelModel.Kind.TRIP;
		in.scope = s;
		in.tripSince = at(14, 52);
		in.hitsTaken = 88;
		in.scoredThrall = in.thrall = 290;
		in.scoredDot = in.dot = 60;
		// As LedgerExamples.trip's: 20 burn and 40 venom (T-062).
		in.scoredDotByKind = in.dotByKind = kinds("burn", 20, "venom", 40);
		final List<PanelSnapshot.TripRow> rows = new ArrayList<>();
		rows.add(tripRow("Vardorvis", false, "2:16", "bottom 20%", LedgerRows.Colour.COST));
		rows.add(tripRow("Vardorvis", false, "1:52", "average", LedgerRows.Colour.NEUTRAL));
		rows.add(tripRow("Vardorvis", false, "1:44", "top 18%", LedgerRows.Colour.BETTER));
		rows.add(tripRow("Vardorvis", false, "2:03", "average", LedgerRows.Colour.NEUTRAL));
		rows.add(tripRow("Vardorvis", true, "0:54", "average", LedgerRows.Colour.NEUTRAL));
		rows.add(tripRow("Vardorvis", false, "1:41", "top 9%", LedgerRows.Colour.BETTER));
		rows.add(tripRow("Vardorvis", false, "2:09", "bottom 15%", LedgerRows.Colour.COST));
		in.tripRows = rows;
		// Every room saved, none running: the trip's after-fight rows show (T-068), as the panel reads it.
		in.ended = true;
		return in;
	}

	/** T-068: the trip while a fight runs: its Damage taken card is the total only. */
	static SidePanelModel.Input tripWhileFighting()
	{
		final SidePanelModel.Input in = trip();
		in.ended = false;
		return in;
	}

	private static PanelSnapshot.TripRow tripRow(String name, boolean ended, String clock, String chip,
		LedgerRows.Colour colour)
	{
		return new PanelSnapshot.TripRow(name, ended, clock, PanelSnapshot.Text.small(chip, colour == LedgerRows.Colour.NEUTRAL
			? SidePanelStyle.BODY : SidePanelStyle.of(colour)), SidePanelStyle.chip(colour),
			SidePanelBossIcons.sprite(name, null));
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

	/**
	 * T-042: Eric's 20:15 kill, shaped on his screenshot: 1:16 on the clock,
	 * uptime +2 s, eating +2 s, stat boost +1 s, thrall 49 and burn 47, and
	 * weapon damage set so luck comes to about {@code luckSeconds} (L = T(A − E)
	 * ÷ (E + O), E 600, O 96). The spread puts 15 s in the top few per cent and
	 * 2 s in the middle half. Your average 1:45, off the bar.
	 */
	static SidePanelModel.Input luckSaved(double luckSeconds)
	{
		final LedgerExamples.FakeScope s = new LedgerExamples.FakeScope();
		s.combatTicks = 127;
		s.expectedDamage = 600;
		s.thrallDamage = 49;
		s.dotDamage = 47;
		s.damage = (int) Math.round(600 + luckSeconds * 696 / (127 * LedgerRows.SECONDS_PER_TICK));
		s.damageVariance = 2500;
		s.healed = 48;
		s.ticksLost = 7;
		s.ticksLostEating = 4;
		s.attacksMade = 41;
		s.attacksPrayed = 41;
		s.attacksSwitched = 41;
		s.hits = 26;
		s.expectedHits = 25.1;
		s.damageTaken = 170;
		s.setup(1 - 1.0 / 72, 0.97, 1.0, 0);
		final SidePanelModel.Input in = room("Vardorvis", s);
		in.ended = true;
		in.killed = true;
		in.endMillis = at(20, 15);
		in.scoredThrall = 49;
		in.scoredDot = 47;
		in.scoredDotByKind = kinds("burn", 47);
		in.average = new LedgerHistory.Average(new LedgerHistory.Spread(105, 98, 112), 10);
		in.luckRemoved = new LedgerHistory.Spread(106, 100, 112);
		return in;
	}

	/** T-042: the same kill, luck 15 s faster, against a usual of 1:24 that falls on the bar. */
	static SidePanelModel.Input luckSavedAverageOnTheBar()
	{
		final SidePanelModel.Input in = luckSaved(15);
		in.average = new LedgerHistory.Average(new LedgerHistory.Spread(84, 80, 90), 10);
		in.luckRemoved = new LedgerHistory.Spread(88, 84, 92);
		return in;
	}

	/**
	 * T-044: Eric's 16:03 kill 4 at Vardorvis (T-041's replay of it, #46): 216
	 * combat ticks; 27 eating ticks, 18 of them for the avoidable hits; 263
	 * taken, 134 of it avoidable (off prayer counts ¾); by attack melee 122,
	 * melee off prayer 30, head 6, axe 26, bleed 30, spike 49. {@code ended}
	 * false is the same figures on a scope still running.
	 */
	static SidePanelModel.Input ruledKill(boolean ended)
	{
		final LedgerExamples.FakeScope s = LedgerExamples.kill();
		s.combatTicks = 216;
		s.ticksLost = 41;
		s.ticksLostEating = 27;
		s.setup(0.98, 0.85, 2.352, 0);
		s.damageVariance = 11436;
		s.damageTaken = 263;
		s.biggestHitTaken = 31;
		s.damageTakenUnprotected = 36;
		s.avoidableEatingTicks = 18.0;
		s.damageTakenAvoidable = 134;
		final SidePanelModel.Input in = room("Vardorvis", s);
		in.ended = ended;
		in.killed = ended;
		in.endMillis = ended ? at(16, 3) : 0;
		in.scoredThrall = 80;
		in.hitsTaken = 21;
		final Map<String, Integer> by = new LinkedHashMap<>();
		by.put("melee", 122);
		by.put("melee off prayer", 30);
		by.put("head", 6);
		by.put("axe", 26);
		by.put("bleed", 30);
		by.put("spike", 49);
		in.takenByAttack = by;
		in.avoidableAttacks = SidePanelModel.avoidableAttacks(AvoidableReplayTest.vardorvis());
		return in;
	}

	/** T-044: the same kill at a boss with no attack rules, or saved before T-041: no avoidable figures. */
	static SidePanelModel.Input unruledKill()
	{
		final SidePanelModel.Input in = ruledKill(true);
		final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) in.scope;
		s.avoidableEatingTicks = -1;
		s.damageTakenAvoidable = -1;
		in.takenByAttack = Collections.emptyMap();
		in.avoidableAttacks = Collections.emptySet();
		return in;
	}

	// ---- T-052: defence drains (illustrative figures, shaped on T-049's rules) ----

	/** A drain over a room: Defence start → end of base, floor, and T-050's figures in damage and seconds. */
	static SidePanelModel.Drain drain(int start, int end, int base, int floor, int party, double value, double valueSeconds,
		double specLuck, double specLuckSeconds)
	{
		final SidePanelModel.Drain d = new SidePanelModel.Drain();
		d.defenceStart = start;
		d.defenceEnd = end;
		d.defenceBase = base;
		d.defenceFloor = floor;
		d.magicStart = 0;
		d.magicEnd = 0;
		d.partyDrains = party;
		d.valueDamage = value;
		d.valueSeconds = valueSeconds;
		d.specLuckDamage = specLuck;
		d.specLuckSeconds = specLuckSeconds;
		return d;
	}

	/** The Fight tab after a kill with this drain, at this boss. */
	static SidePanelModel.Input drainKill(String boss, SidePanelModel.Drain d)
	{
		final SidePanelModel.Input in = dead();
		in.name = boss;
		in.scoredName = boss;
		in.average = null;
		in.luckRemoved = null;
		in.drain = d;
		return in;
	}

	/** Two Dragon warhammers at General Graardor, one landed: 250 → 175. The miss cost about 3 s. */
	static SidePanelModel.Input twoHammersOneLanded()
	{
		final SidePanelModel.Drain d = drain(250, 175, 250, 0, 0, 182, 14.2, -41, -3.2);
		d.specs.put("DRAGON_WARHAMMER", new int[]{2, 1});
		return drainKill("General Graardor", d);
	}

	/** A Bandos godsword at the Corporeal Beast, which drains twice its damage: a 39 took 78. */
	static SidePanelModel.Input bandosGodsword()
	{
		final SidePanelModel.Drain d = drain(310, 232, 310, 0, 0, 96, 7.6, 52, 4.1);
		d.specs.put("BANDOS_GODSWORD", new int[]{1, 1});
		return drainKill("Corporeal Beast", d);
	}

	/** Xarth at its floor (60%: 102 of 170): two hammers and a party member's; the second hammer had nothing left. */
	static SidePanelModel.Input atItsFloor()
	{
		final SidePanelModel.Drain d = drain(170, 102, 170, 102, 1, 60, 5.1, 2, 0.2);
		d.specs.put("DRAGON_WARHAMMER", new int[]{2, 2});
		return drainKill("Xarth", d);
	}

	/** Vardorvis is immune: the record says so, so the room has no drain to show. */
	static SidePanelModel.Input immune()
	{
		return drainKill("Vardorvis", null);
	}

	/** The fight still running, a hammer landed on Graardor: the header's live line. */
	static SidePanelModel.Input liveDrained()
	{
		final SidePanelModel.Input in = live();
		in.name = "General Graardor";
		in.scoredName = in.name;
		final SidePanelModel.LiveDefence l = new SidePanelModel.LiveDefence();
		l.defence = 175;
		l.defenceBase = 250;
		l.defenceFloor = 0;
		l.magic = 80;
		l.magicBase = 80;
		in.liveDefence = l;
		return in;
	}

	/** The trip with its drains: T-050's sums over its ended fights. */
	static SidePanelModel.Input tripWithDrains()
	{
		final SidePanelModel.Input in = trip();
		in.drain = drain(-1, -1, -1, -1, 0, 338, 26.9, 13, 1.1);
		return in;
	}

	// ---- T-068: the Damage taken card waits for the fight's end ----

	/** The ruled kill still running: its Damage taken card mid-fight. */
	static SidePanelModel.Input takenMidFight()
	{
		final SidePanelModel.Input in = ruledKill(false);
		return in;
	}

	/**
	 * T-068 part 2: a Sire room with a respiratory system dead, read as the panel reads it: running, by T-070's rule.
	 * {@code killed}: the Sire's death too.
	 */
	static SidePanelModel.Input sireRoom(boolean killed)
	{
		final Encounter room = new Encounter("Abyssal Sire", null, at(15, 0));
		final Fight sire = sireFight(net.runelite.api.gameval.NpcID.ABYSSALSIRE_SIRE_STASIS_AWAKE, 1, 40);
		sire.end(false, at(15, 1));
		final Fight lung = sireFight(net.runelite.api.gameval.NpcID.ABYSSALSIRE_LUNG, 2, 12);
		lung.end(true, at(15, 2));
		room.add(sire);
		room.add(lung);
		Fight onShow = lung;
		if (killed)
		{
			final Fight puppet = sireFight(net.runelite.api.gameval.NpcID.ABYSSALSIRE_SIRE_PUPPET, 1, 60);
			room.add(puppet);
			puppet.end(true, at(15, 4));
			onShow = puppet;
		}
		final SidePanelModel.Input in = base();
		in.kind = SidePanelModel.Kind.ROOM;
		SidePanelModel.fillRoom(in, room);
		in.ended = PvmPerformancePanel.roomEnded(room, onShow);
		in.killed = in.ended && room.isKilled();
		in.endMillis = in.ended ? at(15, 4) : 0;
		return in;
	}

	private static Fight sireFight(int npcId, int index, int attacks)
	{
		final Fight f = new Fight(npcId == net.runelite.api.gameval.NpcID.ABYSSALSIRE_LUNG ? "Respiratory system"
			: "Abyssal Sire", npcId, index, 400, at(15, 0), null, 0);
		for (int i = 0; i < attacks; i++)
		{
			f.recordAttackMade(true, 1.0);
			f.recordAttackResolved(true, true, 10, 10);
			f.recordDamageDealt(9 + i % 7, at(15, 0), true);
			f.recordExpected(30, 0.8, 11, 100);
			f.recordTickSpent(true);
			f.recordTickSpent(true);
			f.recordTickSpent(true);
			f.recordTickSpent(true);
			if (i % 5 == 0)
			{
				f.recordHitTaken(8 + i % 9, false, at(15, 0));
			}
		}
		return f;
	}

	// ---- T-076: Eric's Sire recording, the Defence card's layouts, one expectation, hits that did damage ----

	/**
	 * Shaped on Eric's recorded Sire kill (2026-10-28 23:50, illustrative figures): 446 of 576 expected, the attacks as
	 * thrown 588 (two Emberlight specs landed, 12 better than average), Defence 250 → 174, and 27 taken in 6 hits that
	 * did damage among 100 splats, 94 of them 0s.
	 */
	static SidePanelModel.Input sireEmberlight()
	{
		final SidePanelTimelineExamples.Built b = SidePanelTimelineExamples.fight("Abyssal Sire", "Emberlight",
			net.runelite.api.gameval.ItemID.EMBERLIGHT, 140, new int[]{4, 9}, new int[]{70, 6}, new int[0], 399, 541, 47,
			47, 150);
		// T-050: the specials' outcomes averaged 12 below the attacks as thrown; the drains were worth 42.
		b.fight.recordSpecOutcomes(-12, 0, 42, 40);
		for (int hit : new int[]{8, 5, 4, 4, 3, 3})
		{
			b.fight.recordHitTaken(hit, false, at(23, 50));
		}
		for (int i = 0; i < 94; i++)
		{
			b.fight.recordHitTaken(0, false, at(23, 50));
		}
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(b, true);
		in.zone = ZONE;
		in.endMillis = at(23, 52);
		in.solo = true;
		in.maxHp = 700;
		final SidePanelModel.Drain d = drain(250, 174, 250, 0, 0, 42, b.room.luckSeconds(42), 12,
			b.room.luckSeconds(12));
		d.specs.put("EMBERLIGHT", new int[]{2, 2});
		in.drain = d;
		return in;
	}

	/** The same kill with an average far shorter than it (Sire kills saved split into rooms before T-055): 0:35. */
	static SidePanelModel.Input sireShortAverage()
	{
		final SidePanelModel.Input in = sireEmberlight();
		in.average = new LedgerHistory.Average(new LedgerHistory.Spread(35, 33, 37), 4);
		return in;
	}

	/** A boss with a Defence floor, not reached: Xarth 170 → 130, floor 102, a hammer and a miss. */
	static SidePanelModel.Input floorNotReached()
	{
		final SidePanelModel.Drain d = drain(170, 130, 170, 102, 0, 31, 2.6, -18, -1.5);
		d.specs.put("DRAGON_WARHAMMER", new int[]{2, 1});
		return drainKill("Xarth", d);
	}

	// ---- T-081: forced downtime (T-074's mechanics ticks) in the side panel ----

	/**
	 * A Leviathan kill (illustrative): three stuns (7 ticks each, T-074's cap) and two specials (4 each) forced 29 idle
	 * ticks, 17 s; besides 10 lost ticks and 4 of eating.
	 */
	static SidePanelModel.Input leviathanKill()
	{
		final SidePanelTimelineExamples.Built b = SidePanelTimelineExamples.fight("The Leviathan", "Zaryte crossbow",
			net.runelite.api.gameval.ItemID.ZARYTE_XBOW, 260, new int[]{6, 30}, new int[]{150, 10}, new int[]{200, 4},
			new int[]{40, 7, 95, 4, 120, 7, 175, 4, 225, 7}, 520, 560, 80, 74, 210);
		b.fight.end(true, at(14, 12));
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(b, true);
		in.zone = ZONE;
		in.endMillis = at(14, 12);
		in.solo = true;
		in.maxHp = 900;
		return in;
	}

	// ---- T-085: each weapon's DPS on the Damage card ----

	/**
	 * Shaped on Eric's Sire kills (illustrative): Emberlight's attacks (4 ticks), two of its specials, and the Scorching
	 * bow's (4 ticks, rapid) at the respiratory systems' phase.
	 */
	static SidePanelModel.Input sireWeapons()
	{
		final SidePanelTimelineExamples.Built b = SidePanelTimelineExamples.plan("Abyssal Sire",
			new SidePanelTimelineExamples.Stretch("Scorching bow", net.runelite.api.gameval.ItemID.SCORCHING_BOW,
				FightTimeline.Style.RANGED, false, 4, SidePanelTimelineExamples.hits(12, 18, 0, 25, 9), 13.5, 38, 0.7, 6),
			new SidePanelTimelineExamples.Stretch("Emberlight", net.runelite.api.gameval.ItemID.EMBERLIGHT,
				FightTimeline.Style.MELEE, true, 4, new int[]{31, 16}, 22, 50, 0.8, 0),
			new SidePanelTimelineExamples.Stretch("Emberlight", net.runelite.api.gameval.ItemID.EMBERLIGHT,
				FightTimeline.Style.MELEE, false, 4, SidePanelTimelineExamples.hits(30, 24, 31, 0, 17, 40, 12), 18.2, 46, 0.82,
				0));
		return weaponsKill(b, 23, 50);
	}

	/** A Leviathan kill (illustrative): the Twisted bow (5 ticks) and the blowpipe (2 ticks) between its stuns. */
	static SidePanelModel.Input leviathanWeapons()
	{
		final SidePanelTimelineExamples.Built b = SidePanelTimelineExamples.plan("The Leviathan",
			new SidePanelTimelineExamples.Stretch("Twisted bow", net.runelite.api.gameval.ItemID.TWISTED_BOW,
				FightTimeline.Style.RANGED, false, 5, SidePanelTimelineExamples.hits(26, 52, 61, 0, 47, 70), 44.5, 79, 0.78, 8),
			new SidePanelTimelineExamples.Stretch("Toxic blowpipe", net.runelite.api.gameval.ItemID.TOXIC_BLOWPIPE,
				FightTimeline.Style.RANGED, false, 2, SidePanelTimelineExamples.hits(30, 14, 0, 19, 22, 9), 12.4, 27, 0.72, 4),
			new SidePanelTimelineExamples.Stretch("Twisted bow", net.runelite.api.gameval.ItemID.TWISTED_BOW,
				FightTimeline.Style.RANGED, false, 5, SidePanelTimelineExamples.hits(14, 55, 0, 64, 49), 44.5, 79, 0.78, 0));
		return weaponsKill(b, 14, 20);
	}

	private static SidePanelModel.Input weaponsKill(SidePanelTimelineExamples.Built b, int hour, int minute)
	{
		b.fight.end(true, at(hour, minute));
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(b, true);
		in.zone = ZONE;
		in.endMillis = at(hour, minute);
		in.solo = true;
		in.maxHp = 900;
		return in;
	}

	// ---- T-072: times of day with am and pm ----

	/** The kill, ended at this time of day: the header's {@code 12:00 am}, {@code 12:00 pm}, {@code 12:05 am}. */
	static SidePanelModel.Input deadAt(int hour, int minute)
	{
		final SidePanelModel.Input in = dead();
		in.endMillis = at(hour, minute);
		return in;
	}

	// ---- T-063: damage over time by kind ----

	/** A kill with this damage over time dealt and taken, by kind; the remainder is what has no kind. */
	static SidePanelModel.Input dotKill(String boss, int dealt, Map<String, Integer> dealtByKind, int taken,
		Map<String, Integer> takenByKind)
	{
		final SidePanelModel.Input in = dead();
		in.name = boss;
		in.scoredName = boss;
		final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) in.scope;
		s.dotDamage = dealt;
		s.dotDamageByKind = new LinkedHashMap<>(dealtByKind);
		s.dotTaken = taken;
		s.dotTakenByKind = new LinkedHashMap<>(takenByKind);
		in.scoredDot = dealt;
		in.scoredDotByKind = dealtByKind;
		return in;
	}

	/** Zulrah: 30 venom dealt (the blowpipe), 25 poison taken. */
	static SidePanelModel.Input venomDealtPoisonTaken()
	{
		return dotKill("Zulrah", 30, kinds("venom", 30), 25, kinds("poison", 25));
	}

	/** Burning claws at Vardorvis: 47 burn. */
	static SidePanelModel.Input burnClaws()
	{
		return dotKill("Vardorvis", 47, kinds("burn", 47), 0, kinds());
	}

	/** Two kinds dealt: 8 burn and 30 venom, so the tile says 38 DoT. */
	static SidePanelModel.Input twoKindsDealt()
	{
		return dotKill("Vardorvis", 38, kinds("burn", 8, "venom", 30), 0, kinds());
	}

	/** A kill saved before T-062: totals, no kinds. */
	static SidePanelModel.Input oldKill()
	{
		return dotKill("Vardorvis", 40, kinds(), 12, kinds());
	}

	/** A trip mixing old and new kills: 20 burn and 40 venom from new ones, 40 with no kind from old. */
	static SidePanelModel.Input tripMixedOldAndNew()
	{
		final SidePanelModel.Input in = trip();
		final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) in.scope;
		s.dotDamage = 100;
		s.dotDamageByKind = kinds("burn", 20, "venom", 40);
		in.scoredDot = in.dot = 100;
		in.scoredDotByKind = in.dotByKind = kinds("burn", 20, "venom", 40);
		return in;
	}

	/** T-063: damage over time by kind, {@code kinds("burn", 8, "venom", 30)}. */
	static Map<String, Integer> kinds(Object... kindsAndAmounts)
	{
		final Map<String, Integer> m = new LinkedHashMap<>();
		for (int i = 0; i < kindsAndAmounts.length; i += 2)
		{
			m.put((String) kindsAndAmounts[i], (Integer) kindsAndAmounts[i + 1]);
		}
		return m;
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
