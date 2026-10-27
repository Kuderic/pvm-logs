package com.pvmlogs;

/**
 * The overlay spec's worked examples (claude/specs/overlay-design.md section 8)
 * as fake scopes, shared by {@link LedgerRowsTest} and {@link LedgerPictures}.
 * During the fight and Dead are Eric's real Vardorvis fight and kill; the rest
 * are the spec's illustrative figures, which add up.
 */
final class LedgerExamples
{
	private LedgerExamples()
	{
	}

	/** A scope whose every figure is set by hand. */
	static final class FakeScope implements LedgerRows.Scope
	{
		int combatTicks;
		int damage;
		double expectedDamage;
		int thrallDamage;
		int dotDamage;
		int healed;
		int ticksLost;
		int ticksLostEating;
		double efficiency = -1;
		double boostShare = -1;
		double boostCostShare = -1;
		double switchCostShare = -1;
		int attacksMade;
		int attacksPrayed;
		int attacksSwitched;
		int attacksDrained;
		int damageTaken;
		int hits;
		double expectedHits;

		public int combatTicks()
		{
			return combatTicks;
		}

		public int damage()
		{
			return damage;
		}

		public double expectedDamage()
		{
			return expectedDamage;
		}

		public int thrallDamage()
		{
			return thrallDamage;
		}

		public int dotDamage()
		{
			return dotDamage;
		}

		public int healed()
		{
			return healed;
		}

		public int ticksLost()
		{
			return ticksLost;
		}

		public double ticksLostShare()
		{
			return combatTicks <= 0 ? -1 : (double) ticksLost / combatTicks;
		}

		public double efficiency()
		{
			return efficiency;
		}

		public double boostShare()
		{
			return boostShare;
		}

		public double boostCostShare()
		{
			return boostCostShare;
		}

		public double switchCostShare()
		{
			return switchCostShare;
		}

		public int attacksMade()
		{
			return attacksMade;
		}

		public int attacksPrayed()
		{
			return attacksPrayed;
		}

		public int attacksSwitched()
		{
			return attacksSwitched;
		}

		public int attacksDrained()
		{
			return attacksDrained;
		}

		public int damageTaken()
		{
			return damageTaken;
		}

		public int hits()
		{
			return hits;
		}

		public double expectedHits()
		{
			return expectedHits;
		}

		public int ticksLostEating()
		{
			return ticksLostEating;
		}

		/**
		 * Sets the setup loss and splits it the way T-010 reports it: the boost's and
		 * the switches' cost as shares of the ideal damage. The seconds given are the
		 * spec's; they are turned back into shares against the setup's own seconds.
		 */
		FakeScope setup(double efficiency, double boostShare, double boostSeconds, double gearSeconds)
		{
			this.efficiency = efficiency;
			this.boostShare = boostShare;
			final double setupSeconds = (combatTicks - ticksLost) * LedgerRows.SECONDS_PER_TICK * (1 - efficiency);
			final double perSecond = setupSeconds <= 0 ? 0 : (1 - efficiency) / setupSeconds;
			this.boostCostShare = boostSeconds * perSecond;
			this.switchCostShare = gearSeconds * perSecond;
			return this;
		}
	}

	/** Eric's Vardorvis fight at 1:13 (screenshot 1): 370 dealt against 418, thrall 48, 10 ticks lost. */
	static FakeScope midFight()
	{
		final FakeScope s = new FakeScope();
		s.combatTicks = 122;
		s.damage = 370;
		s.expectedDamage = 418;
		s.thrallDamage = 48;
		s.ticksLost = 10;
		s.ticksLostEating = 2;
		s.damageTaken = 112;
		s.attacksMade = 18;
		s.attacksPrayed = 18;
		s.attacksSwitched = 18;
		s.hits = 17;
		s.expectedHits = 16.3;
		// All of the 2% setup loss is the boost: stat boost -1.3 s.
		return s.setup(0.98, 0.84, 1.344, 0);
	}

	/** Eric's kill (screenshot 2): 2:16.8, 688 against 778, thrall 80, healed 68, 32 ticks lost of which 7 eating. */
	static FakeScope kill()
	{
		final FakeScope s = new FakeScope();
		s.combatTicks = 228;
		s.damage = 688;
		s.expectedDamage = 778;
		s.thrallDamage = 80;
		s.healed = 68;
		s.ticksLost = 32;
		s.ticksLostEating = 7;
		s.damageTaken = 248;
		s.attacksMade = 49;
		s.attacksPrayed = 49;
		s.attacksSwitched = 49;
		s.hits = 32;
		s.expectedHits = 30.2;
		// Stat boost share 85% (estimated on the canvas), and the whole 2% setup loss.
		return s.setup(0.98, 0.85, 2.352, 0);
	}

	/** Illustrative: a fight that ended without a kill. */
	static FakeScope ended()
	{
		final FakeScope s = new FakeScope();
		s.combatTicks = 234;
		s.damage = 721;
		s.expectedDamage = 812;
		s.dotDamage = 40;
		s.healed = 71;
		s.ticksLost = 14;
		s.damageTaken = 310;
		s.attacksMade = 40;
		s.attacksPrayed = 40;
		s.attacksSwitched = 40;
		s.hits = 26;
		s.expectedHits = 33.7;
		return s.setup(0.99, 0.95, 1.32, 0);
	}

	/** Illustrative: six kills. Setup 21.7 s = stat boost 17, prayer 3, gear 2. */
	static FakeScope trip()
	{
		final FakeScope s = new FakeScope();
		s.combatTicks = 1267;
		s.damage = 4120;
		s.expectedDamage = 4201;
		s.thrallDamage = 290;
		s.dotDamage = 60;
		s.ticksLost = 63;
		s.ticksLostEating = 11;
		s.damageTaken = 1404;
		s.attacksMade = 170;
		s.attacksPrayed = 168;
		s.attacksSwitched = 168;
		s.hits = 161;
		s.expectedHits = 158.7;
		return s.setup(0.97, 0.88, 17, 2);
	}

	/** Illustrative: three ToA rooms. Setup 19.9 s = stat boost 6, prayer 12, gear 2. */
	static FakeScope raid()
	{
		final FakeScope s = new FakeScope();
		s.combatTicks = 920;
		s.damage = 8410;
		s.expectedDamage = 8200;
		s.thrallDamage = 410;
		s.ticksLost = 92;
		s.damageTaken = 980;
		s.attacksMade = 230;
		s.attacksPrayed = 216;
		s.attacksSwitched = 228;
		s.hits = 212;
		s.expectedHits = 205.3;
		return s.setup(0.96, 0.97, 6, 2);
	}

	/** Illustrative: a boss with no monster data, 48 s in. */
	static FakeScope noData()
	{
		final FakeScope s = new FakeScope();
		s.combatTicks = 80;
		s.damage = 212;
		s.ticksLost = 4;
		s.damageTaken = 36;
		s.attacksMade = 12;
		s.attacksPrayed = 12;
		s.attacksSwitched = 12;
		s.hits = 9;
		return s;
	}

	/** The fight at 1:13 as a scope saved before T-010: no boost share read. */
	static FakeScope midFightBeforeT010()
	{
		final FakeScope s = midFight();
		s.boostShare = -1;
		return s;
	}

	/** The tentacle at Vardorvis's full HP, and an illustrative spec weapon. */
	static LedgerRows.Loadout loadout()
	{
		final LedgerRows.Loadout l = new LedgerRows.Loadout();
		l.attack = "Abyssal tentacle";
		l.maxHit = 49;
		l.accuracy = 0.539;
		l.dps = 5.5;
		l.specMaxHit = 50;
		l.specAccuracy = 0.71;
		l.specAverage = 17.8;
		return l;
	}
}
