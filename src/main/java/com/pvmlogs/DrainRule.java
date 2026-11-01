package com.pvmlogs;

import java.util.Objects;
import net.runelite.client.plugins.specialcounter.SpecialWeapon;

/**
 * What one draining special does to a target's stats (T-049): a pure rule,
 * {@code (state, weapon, hit, landed, target) -> new state}, with the target's
 * floors applied. It touches no tracker, so T-050 can run it on outcomes that
 * did not happen.
 *
 * <p>The game facts, each checked on the OSRS Wiki (2026-10-28; sources in
 * {@code claude/status/08-boss-stats.md}) and against the DPS calculator's
 * {@code DefenceReduction.ts}, Better Party Defence's {@code DefenceTracker} and
 * RuneLite's {@code SpecialWeapon}. The code is our own; see THIRD-PARTY.md.
 * Levels truncate after each step, as the game's integer maths does.
 *
 * <ul>
 * <li><b>Dragon warhammer</b>: a hit takes 30% of the CURRENT Defence; a miss
 * nothing, except 5% on Tekton.</li>
 * <li><b>Elder maul</b>: a hit takes 35% of the current Defence; a miss nothing,
 * except 5% on Tekton.</li>
 * <li><b>Bandos godsword</b>: the damage dealt, off Defence, then whatever is left
 * off Strength, Attack, Magic and Ranged in that order. A stat that does not
 * reach 0, its floor included, stops it. A miss drains nothing, except 10 on
 * Tekton.</li>
 * <li><b>Arclight and Darklight</b>: a hit takes 5% of the BASE Attack, Strength
 * and Defence, plus 1, from each; 10% on a demon.</li>
 * <li><b>Emberlight</b>: as Arclight, 15% on a demon.</li>
 * <li><b>Barrelchest anchor</b>: a tenth of the damage off one stat: Defence, or
 * once Defence is at its floor, Attack, then Ranged, then Magic.</li>
 * <li><b>Bone dagger and Dorgeshuun crossbow</b>: the damage off Defence, only
 * while the Defence has not been lowered.</li>
 * <li><b>Accursed sceptre</b>: a hit takes Defence and Magic to no more than 85%
 * of their base; it does not stack ("an 80 cannot have their stats drained lower
 * than 68").</li>
 * <li><b>Tonalztics of ralos</b>: each glaive that lands takes an eighth of the
 * current Magic level off Defence ({@code hit} is the number that landed, as
 * RuneLite's special attack counter sends it).</li>
 * <li><b>Seercull</b>: the damage off Magic, only while Magic has not been
 * lowered.</li>
 * <li><b>Eye of ayak</b>: the damage off the magic defence bonus (T-141, OSRS Wiki,
 * <i>Eye of ayak</i>, read 2026-11-01): "it reduces the target's Magic Defence by an
 * amount equal to the damage dealt ... Although Magic Defence cannot be reduced below
 * 0, the full reduction persists if the target's Magic Defence subsequently changes.
 * For example, if the special attack deals 50 damage to Yama while he is in his
 * melee stance, which has -30 Magic Defence, his Magic Defence remains at -30. If he
 * then changes to his magic stance, his actual Magic Defence becomes +10 during
 * phases 1 and 2, or +31 during phase 3, rather than +60 or +81 respectively." So
 * the state keeps the whole reduction (its {@code magicDefence} is the data's bonus
 * less it, unclamped), and {@link #magicDefenceBonus} meets it with the bonus the
 * target has when an attack goes out.</li>
 * </ul>
 * Dinh's bulwark is in RuneLite's list, but in multi-combat it lowers the
 * targets' highest offensive stat, never Defence or Magic, so it is not modelled.
 *
 * <p><b>Regeneration</b> ({@link #regenerate}): monsters restore drained stats
 * by one level at a time, as players do (wiki, Temporary skill drain), by
 * default every 100 ticks; some bosses faster ({@code boss-rules.json}'s
 * {@code regenTicks}). The magic defence bonus never comes back (wiki, Eye of
 * ayak).
 */
final class DrainRule
{
	private DrainRule()
	{
	}

	/** A target's stats as drains leave them. Immutable, with equality, so T-050 can merge outcomes that land alike. */
	static final class State
	{
		final int defence;
		final int magic;
		/** The magic defence bonus, which only the Eye of ayak lowers. */
		final int magicDefence;
		final int attack;
		final int strength;
		final int ranged;

		State(int defence, int magic, int magicDefence, int attack, int strength, int ranged)
		{
			this.defence = defence;
			this.magic = magic;
			this.magicDefence = magicDefence;
			this.attack = attack;
			this.strength = strength;
			this.ranged = ranged;
		}

		/** The undrained state of a target. */
		static State of(Target target)
		{
			return new State(target.defence, target.magic, target.magicDefence, target.attack, target.strength,
				target.ranged);
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof State))
			{
				return false;
			}
			final State s = (State) o;
			return defence == s.defence && magic == s.magic && magicDefence == s.magicDefence && attack == s.attack
				&& strength == s.strength && ranged == s.ranged;
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(defence, magic, magicDefence, attack, strength, ranged);
		}

		@Override
		public String toString()
		{
			return String.format("defence %d, magic %d, magic defence %+d", defence, magic, magicDefence);
		}
	}

	/** What a target starts at and what limits it: the levels as the raid leaves them, and its floors. */
	static final class Target
	{
		final int defence;
		final int magic;
		final int magicDefence;
		final int attack;
		final int strength;
		final int ranged;
		/** The lowest its Defence can go: its own floor, or its full Defence where it is immune. */
		final int defenceFloor;
		/** The lowest its Magic level can go. */
		final int magicFloor;
		final boolean immune;
		final boolean demon;
		/** Tekton, in the Chambers: a warhammer or maul that misses still takes 5%, a godsword 10. */
		final boolean tekton;
		/** The Corporeal Beast and Pestilent Bloat while it is up: a godsword drains twice its damage. */
		final boolean godswordDoubled;
		/** Ticks between each level a drained stat gets back; 0 for none. */
		final int regenTicks;

		Target(int defence, int magic, int magicDefence, int attack, int strength, int ranged, int defenceFloor,
			int magicFloor, boolean immune, boolean demon, boolean tekton, boolean godswordDoubled)
		{
			this(defence, magic, magicDefence, attack, strength, ranged, defenceFloor, magicFloor, immune, demon, tekton,
				godswordDoubled, 0);
		}

		Target(int defence, int magic, int magicDefence, int attack, int strength, int ranged, int defenceFloor,
			int magicFloor, boolean immune, boolean demon, boolean tekton, boolean godswordDoubled, int regenTicks)
		{
			this.defence = defence;
			this.magic = magic;
			this.magicDefence = magicDefence;
			this.attack = attack;
			this.strength = strength;
			this.ranged = ranged;
			this.immune = immune;
			this.defenceFloor = immune ? defence : Math.max(0, Math.min(defence, defenceFloor));
			this.magicFloor = immune ? magic : Math.max(0, Math.min(magic, magicFloor));
			this.demon = demon;
			this.tekton = tekton;
			this.godswordDoubled = godswordDoubled;
			this.regenTicks = Math.max(0, regenTicks);
		}
	}

	/** What applying a special came to. */
	static final class Result
	{
		final State after;
		/** A floor (or immunity) stopped some of what the rule would otherwise have taken. */
		final boolean floored;

		Result(State after, boolean floored)
		{
			this.after = after;
			this.floored = floored;
		}

		boolean changed(State before)
		{
			return !after.equals(before);
		}
	}

	/** Whether this weapon's special lowers any stat this rule tracks. */
	static boolean drains(SpecialWeapon weapon)
	{
		if (weapon == null)
		{
			return false;
		}
		switch (weapon)
		{
			case DRAGON_WARHAMMER:
			case ELDER_MAUL:
			case BANDOS_GODSWORD:
			case ARCLIGHT:
			case DARKLIGHT:
			case EMBERLIGHT:
			case BARRELCHEST_ANCHOR:
			case BONE_DAGGER:
			case DORGESHUUN_CROSSBOW:
			case ACCURSED_SCEPTRE:
			case TONALZTICS_OF_RALOS:
			case SEERCULL:
			case EYE_OF_AYAK:
				return true;
			default:
				// Dinh's bulwark, and anything a later RuneLite adds that this has not been told about.
				return false;
		}
	}

	/**
	 * T-112 item 6: whether how much this special drains is set by its landing rather than by its damage: the Dragon
	 * warhammer and elder maul (a share of the current Defence), Arclight, Darklight and Emberlight (a share of the base
	 * stats), the accursed sceptre (to 85% of the base), and the Tonalztics of ralos (an eighth of Magic a glaive that
	 * lands). For these, what landed matters, not the damage. The rest of {@link #drains} drain by the damage dealt.
	 * Read-only; states the facts listed above.
	 */
	static boolean drainsOnLanding(SpecialWeapon weapon)
	{
		if (weapon == null)
		{
			return false;
		}
		switch (weapon)
		{
			case DRAGON_WARHAMMER:
			case ELDER_MAUL:
			case ARCLIGHT:
			case DARKLIGHT:
			case EMBERLIGHT:
			case ACCURSED_SCEPTRE:
			case TONALZTICS_OF_RALOS:
				return true;
			default:
				return false;
		}
	}

	/**
	 * One special landing (or missing) on the target.
	 *
	 * @param hit    the damage its splat showed; for the Tonalztics of ralos, the number of glaives that landed
	 * @param landed whether it hit (dealt more than 0)
	 */
	static Result apply(State s, SpecialWeapon weapon, int hit, boolean landed, Target t)
	{
		if (t.immune || !drains(weapon))
		{
			return new Result(s, t.immune && drains(weapon) && (landed || t.tekton));
		}
		final Floors f = new Floors(t);
		final int damage = Math.max(0, hit);
		State after = s;
		switch (weapon)
		{
			case DRAGON_WARHAMMER:
			case ELDER_MAUL:
			{
				final int percent = weapon == SpecialWeapon.ELDER_MAUL ? 35 : 30;
				if (landed)
				{
					after = f.defence(s, s.defence - s.defence * percent / 100);
				}
				else if (t.tekton)
				{
					after = f.defence(s, s.defence - s.defence * 5 / 100);
				}
				break;
			}
			case BANDOS_GODSWORD:
				if (landed || t.tekton)
				{
					final int drain = landed ? (t.godswordDoubled ? damage * 2 : damage) : 10;
					after = godsword(s, drain, f);
				}
				break;
			case ARCLIGHT:
			case DARKLIGHT:
			case EMBERLIGHT:
				if (landed)
				{
					final int twentieths = !t.demon ? 1 : weapon == SpecialWeapon.EMBERLIGHT ? 3 : 2;
					final int def = s.defence - (t.defence * twentieths / 20 + 1);
					final int atk = s.attack - (t.attack * twentieths / 20 + 1);
					final int str = s.strength - (t.strength * twentieths / 20 + 1);
					after = f.defence(s, def);
					after = f.attackStrength(after, atk, str);
				}
				break;
			case BARRELCHEST_ANCHOR:
				if (landed)
				{
					after = anchor(s, damage / 10, f);
				}
				break;
			case BONE_DAGGER:
			case DORGESHUUN_CROSSBOW:
				if (landed && s.defence >= t.defence)
				{
					after = f.defence(s, s.defence - damage);
				}
				break;
			case ACCURSED_SCEPTRE:
				if (landed)
				{
					after = f.defence(s, t.defence * 17 / 20);
					after = f.magic(after, t.magic * 17 / 20);
				}
				break;
			case TONALZTICS_OF_RALOS:
				for (int glaive = 0; glaive < Math.min(2, damage); glaive++)
				{
					after = f.defence(after, after.defence - after.magic / 8);
				}
				break;
			case SEERCULL:
				if (landed && s.magic >= t.magic)
				{
					after = f.magic(s, s.magic - damage);
				}
				break;
			case EYE_OF_AYAK:
				// T-141: every hit that deals damage adds to the reduction, whatever the bonus is now: it persists.
				if (landed && damage > 0)
				{
					after = new State(s.defence, s.magic, s.magicDefence - damage, s.attack, s.strength, s.ranged);
				}
				break;
			default:
				break;
		}
		return new Result(after, f.floored);
	}

	/**
	 * T-141: a target's magic defence bonus as an attack meets it: {@code bonus}, the bonus it has now (its data's, with
	 * its modifiers: Yama's +60 or -30, his Phase 3 version's +81), less the Eye of ayak's whole {@code reduction}, to no
	 * lower than 0; a bonus at 0 or below is left as it is (the wiki's -30 stays -30).
	 */
	static int magicDefenceBonus(int bonus, int reduction)
	{
		return bonus <= 0 || reduction <= 0 ? bonus : Math.max(0, bonus - reduction);
	}

	/** T-141: the Eye of ayak's reduction in a state: the data's bonus less what the state keeps, never below 0. */
	static int magicDefenceReduction(State s, Target t)
	{
		return Math.max(0, t.magicDefence - s.magicDefence);
	}

	/**
	 * One regeneration step: every drained level (Defence, Magic, Attack,
	 * Strength, Ranged) gets one back, up to its base. The magic defence bonus
	 * stays as it is.
	 */
	static State regenerate(State s, Target t)
	{
		return new State(up(s.defence, t.defence), up(s.magic, t.magic), s.magicDefence, up(s.attack, t.attack),
			up(s.strength, t.strength), up(s.ranged, t.ranged));
	}

	private static int up(int level, int base)
	{
		return level < base ? level + 1 : level;
	}

	// The anchor drains one stat: Defence while it is above its floor, else Attack, then Ranged, then Magic.
	private static State anchor(State s, int drain, Floors f)
	{
		if (drain <= 0)
		{
			return s;
		}
		if (s.defence > f.t.defenceFloor)
		{
			return f.defence(s, s.defence - drain);
		}
		if (s.attack > 0)
		{
			return new State(s.defence, s.magic, s.magicDefence, Math.max(0, s.attack - drain), s.strength, s.ranged);
		}
		if (s.ranged > 0)
		{
			return new State(s.defence, s.magic, s.magicDefence, s.attack, s.strength, Math.max(0, s.ranged - drain));
		}
		return f.magic(s, s.magic - drain);
	}

	// The godsword's damage runs down Defence, Strength, Attack, Magic and Ranged; a stat it leaves above 0 stops it.
	private static State godsword(State s, int damage, Floors f)
	{
		int left = damage;
		final State afterDefence = f.defence(s, s.defence - left);
		left = afterDefence.defence > 0 ? 0 : left - s.defence;
		int strength = s.strength;
		int attack = s.attack;
		int magic = s.magic;
		int ranged = s.ranged;
		if (left > 0)
		{
			final int from = strength;
			strength = Math.max(0, strength - left);
			left = strength > 0 ? 0 : left - from;
		}
		if (left > 0)
		{
			final int from = attack;
			attack = Math.max(0, attack - left);
			left = attack > 0 ? 0 : left - from;
		}
		State out = new State(afterDefence.defence, magic, s.magicDefence, attack, strength, ranged);
		if (left > 0)
		{
			final int from = out.magic;
			out = f.magic(out, out.magic - left);
			left = out.magic > 0 ? 0 : left - from;
		}
		if (left > 0)
		{
			ranged = Math.max(0, out.ranged - left);
			out = new State(out.defence, out.magic, out.magicDefence, out.attack, out.strength, ranged);
		}
		return out;
	}

	// Applies the floors, and notes when one stopped a drain.
	private static final class Floors
	{
		final Target t;
		boolean floored;

		Floors(Target t)
		{
			this.t = t;
		}

		State defence(State s, int wanted)
		{
			final int value = Math.max(t.defenceFloor, wanted);
			floored |= wanted < t.defenceFloor && s.defence > wanted;
			return new State(Math.min(s.defence, value), s.magic, s.magicDefence, s.attack, s.strength, s.ranged);
		}

		State magic(State s, int wanted)
		{
			final int value = Math.max(t.magicFloor, wanted);
			floored |= wanted < t.magicFloor && s.magic > wanted;
			return new State(s.defence, Math.min(s.magic, value), s.magicDefence, s.attack, s.strength, s.ranged);
		}

		State attackStrength(State s, int attack, int strength)
		{
			return new State(s.defence, s.magic, s.magicDefence, Math.max(0, Math.min(s.attack, attack)),
				Math.max(0, Math.min(s.strength, strength)), s.ranged);
		}
	}
}
