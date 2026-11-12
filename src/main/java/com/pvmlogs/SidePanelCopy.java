package com.pvmlogs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * T-218: the Fight tab's input made safe to build from on another thread. The client thread reads the plugin's state into
 * a {@link SidePanelModel.Input} as before ({@code PvmPerformancePanel.read}); this copies the parts of it that still
 * point at that live state, so the build ({@link SidePanelModel#fight}) on the panel's build thread reads only values
 * nobody changes:
 * <ul>
 * <li>each {@link LedgerRows.Scope} (a view over the live room, raid or trip) becomes {@link Frozen}, every figure read
 * once, here;</li>
 * <li>each timeline view's attacks are copied (the timeline fills in its newest attack's name, item and damage as the
 * fight goes on); its arrays are already the view's own;</li>
 * <li>the maps are copied; the boss and adds sides get the same, and their {@code part} (the live sub-room, which the
 * build only asks whether it's there) becomes the {@code side} flag.</li>
 * </ul>
 * Everything else in the input is already a value or a new object of its own (the targets, rooms, drains, weapon rows,
 * the standing, the loadout, the strings). Client thread, after the read and before the hand-over.
 */
final class SidePanelCopy
{
	private SidePanelCopy()
	{
	}

	/** {@code in}, with its live parts copied; the same object. */
	static SidePanelModel.Input freeze(SidePanelModel.Input in)
	{
		if (in == null)
		{
			return null;
		}
		in.scope = scope(in.scope);
		in.luckScope = scope(in.luckScope);
		in.view = view(in.view);
		in.takenByAttack = map(in.takenByAttack);
		in.scoredDotByKind = map(in.scoredDotByKind);
		in.dotByKind = map(in.dotByKind);
		if (in.part != null)
		{
			in.side = true;
			in.part = null;
		}
		if (in.bossOnly != null && in.bossOnly != in)
		{
			freeze(in.bossOnly);
		}
		if (in.addsOnly != null && in.addsOnly != in)
		{
			freeze(in.addsOnly);
		}
		return in;
	}

	static <K, V> Map<K, V> map(Map<K, V> m)
	{
		return m == null ? null : new LinkedHashMap<>(m);
	}

	/** A view with its attacks copied; the arrays are already its own. */
	static FightTimeline.View view(FightTimeline.View v)
	{
		if (v == null || v == FightTimeline.View.EMPTY)
		{
			return v;
		}
		final List<FightTimeline.Attack> attacks = new ArrayList<>(v.attacks.size());
		for (FightTimeline.Attack a : v.attacks)
		{
			attacks.add(attack(a));
		}
		return new FightTimeline.View(v.startTick, v.states, v.damage, v.expected, v.variance, attacks);
	}

	static FightTimeline.Attack attack(FightTimeline.Attack a)
	{
		final FightTimeline.Attack c = new FightTimeline.Attack(a.tick);
		c.thrownTick = a.thrownTick;
		c.key = a.key;
		c.itemId = a.itemId;
		c.spell = a.spell;
		c.style = a.style;
		c.special = a.special;
		c.mean = a.mean;
		c.variance = a.variance;
		c.damage = a.damage;
		c.hits = a.hits;
		c.landedChance = a.landedChance;
		c.maxHit = a.maxHit;
		c.biggest = a.biggest;
		c.speed = a.speed;
		return c;
	}

	static LedgerRows.Scope scope(LedgerRows.Scope s)
	{
		return s == null || s instanceof Frozen ? s : new Frozen(s);
	}

	/**
	 * Every figure of a scope, read once. {@code SidePanelPanelWorkTest} checks it answers every method of {@link
	 * LedgerRows.Scope}, so a figure added there can't fall back to a default here unnoticed.
	 */
	static final class Frozen implements LedgerRows.Scope
	{
		private final int combatTicks;
		private final int scoredCombatTicks;
		private final int damage;
		private final double expectedDamage;
		private final int thrallDamage;
		private final int reflectDamage;
		private final int scoredOtherDamage;
		private final double damageVariance;
		private final int dotDamage;
		private final Map<String, Integer> dotDamageByKind;
		private final int healed;
		private final int ticksLost;
		private final double ticksLostShare;
		private final double efficiency;
		private final double boostShare;
		private final double boostCostShare;
		private final double switchCostShare;
		private final double idealSetupDamage;
		private final int attacksMade;
		private final int attacksPrayed;
		private final int attacksSwitched;
		private final int attacksDrained;
		private final int damageTaken;
		private final int dotTaken;
		private final Map<String, Integer> dotTakenByKind;
		private final int biggestHitTaken;
		private final int damageTakenUnprotected;
		private final int hits;
		private final double expectedHits;
		private final int ticksLostEating;
		private final int ticksMechanics;
		private final int ticksStall;
		private final double avoidableEatingTicks;
		private final int damageTakenAvoidable;
		private final int defenceStart;
		private final int defenceEnd;
		private final int magicStart;
		private final int magicEnd;
		private final double drainValueDamage;
		private final double specLuckDamage;
		/** {@link #luckSeconds}: the seconds a point of damage is worth here (linear: DrainValue.seconds); NaN for none. */
		private final double secondsPerDamage;

		Frozen(LedgerRows.Scope s)
		{
			combatTicks = s.combatTicks();
			scoredCombatTicks = s.scoredCombatTicks();
			damage = s.damage();
			expectedDamage = s.expectedDamage();
			thrallDamage = s.thrallDamage();
			reflectDamage = s.reflectDamage();
			scoredOtherDamage = s.scoredOtherDamage();
			damageVariance = s.damageVariance();
			dotDamage = s.dotDamage();
			dotDamageByKind = map(s.dotDamageByKind());
			healed = s.healed();
			ticksLost = s.ticksLost();
			ticksLostShare = s.ticksLostShare();
			efficiency = s.efficiency();
			boostShare = s.boostShare();
			boostCostShare = s.boostCostShare();
			switchCostShare = s.switchCostShare();
			idealSetupDamage = s.idealSetupDamage();
			attacksMade = s.attacksMade();
			attacksPrayed = s.attacksPrayed();
			attacksSwitched = s.attacksSwitched();
			attacksDrained = s.attacksDrained();
			damageTaken = s.damageTaken();
			dotTaken = s.dotTaken();
			dotTakenByKind = map(s.dotTakenByKind());
			biggestHitTaken = s.biggestHitTaken();
			damageTakenUnprotected = s.damageTakenUnprotected();
			hits = s.hits();
			expectedHits = s.expectedHits();
			ticksLostEating = s.ticksLostEating();
			ticksMechanics = s.ticksMechanics();
			ticksStall = s.ticksStall();
			avoidableEatingTicks = s.avoidableEatingTicks();
			damageTakenAvoidable = s.damageTakenAvoidable();
			defenceStart = s.defenceStart();
			defenceEnd = s.defenceEnd();
			magicStart = s.magicStart();
			magicEnd = s.magicEnd();
			drainValueDamage = s.drainValueDamage();
			specLuckDamage = s.specLuckDamage();
			secondsPerDamage = s.luckSeconds(1);
		}

		@Override
		public int combatTicks()
		{
			return combatTicks;
		}

		@Override
		public int scoredCombatTicks()
		{
			return scoredCombatTicks;
		}

		@Override
		public int damage()
		{
			return damage;
		}

		@Override
		public double expectedDamage()
		{
			return expectedDamage;
		}

		@Override
		public int thrallDamage()
		{
			return thrallDamage;
		}

		@Override
		public int reflectDamage()
		{
			return reflectDamage;
		}

		@Override
		public int scoredOtherDamage()
		{
			return scoredOtherDamage;
		}

		@Override
		public double damageVariance()
		{
			return damageVariance;
		}

		@Override
		public int dotDamage()
		{
			return dotDamage;
		}

		@Override
		public Map<String, Integer> dotDamageByKind()
		{
			return dotDamageByKind;
		}

		@Override
		public int healed()
		{
			return healed;
		}

		@Override
		public int ticksLost()
		{
			return ticksLost;
		}

		@Override
		public double ticksLostShare()
		{
			return ticksLostShare;
		}

		@Override
		public double efficiency()
		{
			return efficiency;
		}

		@Override
		public double boostShare()
		{
			return boostShare;
		}

		@Override
		public double boostCostShare()
		{
			return boostCostShare;
		}

		@Override
		public double switchCostShare()
		{
			return switchCostShare;
		}

		@Override
		public double idealSetupDamage()
		{
			return idealSetupDamage;
		}

		@Override
		public int attacksMade()
		{
			return attacksMade;
		}

		@Override
		public int attacksPrayed()
		{
			return attacksPrayed;
		}

		@Override
		public int attacksSwitched()
		{
			return attacksSwitched;
		}

		@Override
		public int attacksDrained()
		{
			return attacksDrained;
		}

		@Override
		public int damageTaken()
		{
			return damageTaken;
		}

		@Override
		public int dotTaken()
		{
			return dotTaken;
		}

		@Override
		public Map<String, Integer> dotTakenByKind()
		{
			return dotTakenByKind;
		}

		@Override
		public int biggestHitTaken()
		{
			return biggestHitTaken;
		}

		@Override
		public int damageTakenUnprotected()
		{
			return damageTakenUnprotected;
		}

		@Override
		public int hits()
		{
			return hits;
		}

		@Override
		public double expectedHits()
		{
			return expectedHits;
		}

		@Override
		public int ticksLostEating()
		{
			return ticksLostEating;
		}

		@Override
		public int ticksMechanics()
		{
			return ticksMechanics;
		}

		@Override
		public int ticksStall()
		{
			return ticksStall;
		}

		@Override
		public double avoidableEatingTicks()
		{
			return avoidableEatingTicks;
		}

		@Override
		public int damageTakenAvoidable()
		{
			return damageTakenAvoidable;
		}

		@Override
		public int defenceStart()
		{
			return defenceStart;
		}

		@Override
		public int defenceEnd()
		{
			return defenceEnd;
		}

		@Override
		public int magicStart()
		{
			return magicStart;
		}

		@Override
		public int magicEnd()
		{
			return magicEnd;
		}

		@Override
		public double drainValueDamage()
		{
			return drainValueDamage;
		}

		@Override
		public double specLuckDamage()
		{
			return specLuckDamage;
		}

		@Override
		public double luckSeconds(double damage)
		{
			return Double.isNaN(damage) ? Double.NaN : damage * secondsPerDamage;
		}
	}
}
