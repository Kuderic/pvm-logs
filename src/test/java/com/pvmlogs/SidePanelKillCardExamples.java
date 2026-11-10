package com.pvmlogs;

import net.runelite.api.gameval.ItemID;

/**
 * T-188: the kill card's examples, with Celebrations on: the Vardorvis example live and after its kill, Eric's Araxxor
 * kill at KC 555 live and after (T-186's figures from history.json, read in place), a PB kill, and a kill before the
 * record has it. The standings stand in for T-187's record until it's wired.
 */
final class SidePanelKillCardExamples
{
	private SidePanelKillCardExamples()
	{
	}

	static SidePanelModel.Input on(SidePanelModel.Input in)
	{
		in.celebrations = true;
		return in;
	}

	/**
	 * T-193: the standing's badges as the record would have them: T-192's shared rules ({@link Badges#earned}) on the
	 * kill's figures, the game's count and the streak. Call again after changing any of them.
	 */
	static SidePanelModel.Input recorded(SidePanelModel.Input in)
	{
		final SidePanelModel.Standing st = in.standing;
		if (st != null)
		{
			st.kc = in.kc;
			st.badges = Badges.earned(new LedgerRows.Numbers(in.scope), in.scope.damageTakenAvoidable(), in.kc, st.streak);
		}
		return in;
	}

	/** The Vardorvis example at 1:13. */
	static SidePanelModel.Input vardorvisLive()
	{
		return on(SidePanelExamples.live());
	}

	/**
	 * The Vardorvis example's 2:16 kill, KC 271: illustrative PB 1:44, 12th fastest of 16, a C (lowest of the 16 kills'
	 * spread T-186 measured), 6 ticks from a B; luck-removed PB 1:47.
	 */
	static SidePanelModel.Input vardorvisKill()
	{
		final SidePanelModel.Input in = on(SidePanelExamples.dead());
		in.kc = 271;
		final SidePanelModel.Standing st = new SidePanelModel.Standing();
		st.previousBestClock = 104;
		st.rank = 12;
		st.ranked = 16;
		st.grade = "C";
		st.basis = "lowest of last 16";
		st.ticksToNext = 6;
		st.next = "B";
		st.previousBestLuckRemoved = 107;
		in.standing = st;
		recorded(in);
		return in;
	}

	/** Eric's Araxxor kill at KC 555, 42 s in. */
	static SidePanelModel.Input araxxorLive()
	{
		return on(araxxorLiveInput());
	}

	/**
	 * Eric's Araxxor kill at KC 555 (history.json, T-186): 1:26 against his PB 1:05, 42nd fastest of 199; execution
	 * 98.7%, top 10% of his last 50, an A, 2 ticks from an S; luck rolled 81; luck removed 1:36 against 1:12.
	 */
	static SidePanelModel.Input araxxorKill()
	{
		final SidePanelModel.Input in = on(araxxorKillInput());
		in.kc = 555;
		in.standing = araxxorStanding();
		return recorded(in);
	}

	static SidePanelModel.Standing araxxorStanding()
	{
		final SidePanelModel.Standing st = new SidePanelModel.Standing();
		st.previousBestClock = 65.4;
		st.rank = 42;
		st.ranked = 199;
		st.grade = "A";
		st.basis = "top 10% of last 50";
		st.ticksToNext = 2;
		st.next = "S";
		st.previousBestLuckRemoved = 72.1;
		st.streak = 1;
		return st;
	}

	/**
	 * A PB kill (illustrative): the same Araxxor kill as if early in the farm, its 17th: 1:26 against 1:28, the fastest
	 * of 17, and luck removed 1:36 beating 1:37: the gold PB.
	 */
	static SidePanelModel.Input pbKill()
	{
		final SidePanelModel.Input in = araxxorKill();
		in.kc = 17;
		final SidePanelModel.Standing st = in.standing;
		st.previousBestClock = 88.2;
		st.newPb = true;
		st.rank = 1;
		st.ranked = 17;
		st.basis = "top 12% of last 16";
		st.previousBestLuckRemoved = 97.4;
		st.newBestLuckRemoved = true;
		return recorded(in);
	}

	/**
	 * T-189 (illustrative): the PB kill with every badge: the 600th kill, Flawless (as if Araxxor had attack rules and
	 * nothing avoidable was taken), a Clean run (two ticks of eating, no idle tick, the setup at 99.96%), and a third A
	 * in a row.
	 */
	static SidePanelModel.Input allBadges()
	{
		final SidePanelModel.Input in = pbKill();
		in.kc = 600;
		final LedgerExamples.FakeScope s = (LedgerExamples.FakeScope) in.scope;
		s.avoidableEatingTicks = 0;
		s.damageTakenAvoidable = 0;
		s.ticksLost = 2;
		s.ticksLostEating = 2;
		s.efficiency = 0.9996;
		s.boostCostShare = 0.0003;
		s.switchCostShare = 0;
		in.standing.streak = 3;
		return recorded(in);
	}

	/** The Araxxor kill before the record has it: your usual beside the clock, no grade or rank yet. */
	static SidePanelModel.Input unrecorded()
	{
		final SidePanelModel.Input in = araxxorKill();
		in.standing = null;
		return in;
	}

	/** The same kill with Celebrations off: the same tab, without the kill-end moment (T-202). */
	static SidePanelModel.Input off()
	{
		final SidePanelModel.Input in = araxxorKill();
		in.celebrations = false;
		return in;
	}

	// ---- T-186's Araxxor kill, as its prototypes had it ----

	/**
	 * Eric's Araxxor kill at KC 555 (history.json, 2026-11-09; read in place): 1:26, 901 against 803.6 expected over a
	 * variance of 11,966, thrall 51; one idle tick and two of eating in 144; setup 99.38% (stat boost 0.61 of it).
	 * Execution 98.7%, luck the 80th percentile, 10 s saved. The room's three fights (Araxxor, a Mirrorback, Araxxor)
	 * are one scope here; the weapons are its own: a hammer spec, the tentacle (or), the sunlight crossbow on the add.
	 */
	static SidePanelModel.Input araxxorKillInput()
	{
		final LedgerExamples.FakeScope s = new LedgerExamples.FakeScope();
		s.combatTicks = 144;
		s.damage = 901;
		s.expectedDamage = 803.6;
		s.thrallDamage = 51;
		s.damageVariance = 11_966;
		s.ticksLost = 3;
		s.ticksLostEating = 2;
		s.damageTaken = 12;
		s.biggestHitTaken = 3;
		s.attacksMade = 35;
		s.attacksPrayed = 35;
		s.attacksSwitched = 35;
		s.hits = 30;
		s.expectedHits = 29.6;
		s.efficiency = 0.9938;
		s.boostShare = 0.95;
		s.boostCostShare = 0.0061;
		s.switchCostShare = 0.0001;
		final SidePanelModel.Input in = araxxor(s, araxxorPlan(32));
		in.ended = true;
		in.killed = true;
		in.average = new LedgerHistory.Average(new LedgerHistory.Spread(94.2, 88.8, 106.2), 20);
		in.luckRemoved = new LedgerHistory.Spread(92.3, 89.9, 102.5);
		return in;
	}

	/** The same kill 42 s in (illustrative: its first 70 ticks, the hammer, 14 tentacle attacks and the add). */
	static SidePanelModel.Input araxxorLiveInput()
	{
		final LedgerExamples.FakeScope s = new LedgerExamples.FakeScope();
		s.combatTicks = 70;
		s.damage = 428;
		s.expectedDamage = 403.2;
		s.thrallDamage = 25;
		s.damageVariance = 5_000;
		s.ticksLost = 2;
		s.ticksLostEating = 0;
		s.damageTaken = 12;
		s.biggestHitTaken = 3;
		s.attacksMade = 17;
		s.attacksPrayed = 17;
		s.attacksSwitched = 17;
		s.hits = 14;
		s.expectedHits = 14.4;
		s.efficiency = 0.9930;
		s.boostShare = 0.95;
		s.boostCostShare = 0.0068;
		s.switchCostShare = 0.0002;
		final SidePanelModel.Input in = araxxor(s, araxxorPlan(14));
		in.average = new LedgerHistory.Average(new LedgerHistory.Spread(94.2, 88.8, 106.2), 20);
		return in;
	}

	private static SidePanelModel.Input araxxor(LedgerExamples.FakeScope s, SidePanelTimelineExamples.Built b)
	{
		final SidePanelModel.Input in = SidePanelTimelineExamples.input(b, false);
		in.scope = s;
		in.name = "Araxxor";
		in.scoredName = "Araxxor";
		in.maxHp = 1020;
		in.solo = true;
		in.scoredThrall = s.thrallDamage;
		in.hitsTaken = 4;
		in.endMillis = SidePanelExamples.at(20, 41);
		return in;
	}

	/** The kill's weapons, played in order; {@code tentacle} attacks of the tentacle in all (32 at the kill). */
	private static SidePanelTimelineExamples.Built araxxorPlan(int tentacle)
	{
		final int first = Math.min(18, tentacle);
		final SidePanelTimelineExamples.Stretch hammer = new SidePanelTimelineExamples.Stretch("Dragon warhammer",
			ItemID.DRAGON_WARHAMMER, FightTimeline.Style.MELEE, true, 6, new int[]{16}, 35.8, 64, 0.7, 0);
		final SidePanelTimelineExamples.Stretch tentA = new SidePanelTimelineExamples.Stretch("Abyssal tentacle (or)",
			ItemID.ABYSSAL_TENTACLE, FightTimeline.Style.MELEE, false, 4,
			SidePanelTimelineExamples.hits(first, 31, 27, 0, 38, 23, 30), 22.24, 42, 0.84, 2);
		final SidePanelTimelineExamples.Stretch xbow = new SidePanelTimelineExamples.Stretch("Hunters' sunlight crossbow",
			ItemID.HUNTING_CROSSBOW_SUNLIGHT, FightTimeline.Style.RANGED, false, 3, new int[]{28, 28}, 28, 28, 1.0, 0);
		if (tentacle <= first)
		{
			return SidePanelTimelineExamples.plan("Araxxor", hammer, tentA, xbow);
		}
		final SidePanelTimelineExamples.Stretch tentB = new SidePanelTimelineExamples.Stretch("Abyssal tentacle (or)",
			ItemID.ABYSSAL_TENTACLE, FightTimeline.Style.MELEE, false, 4,
			SidePanelTimelineExamples.hits(tentacle - first, 31, 35, 28, 0, 37, 26, 34), 22.24, 42, 0.84, 1);
		return SidePanelTimelineExamples.plan("Araxxor", hammer, tentA, xbow, tentB);
	}
}
