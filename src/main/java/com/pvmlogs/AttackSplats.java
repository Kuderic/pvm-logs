package com.pvmlogs;

import java.util.HashSet;
import java.util.Set;
import net.runelite.api.gameval.ItemID;

/**
 * How many hitsplats one attack puts on its target, and over which ticks
 * (T-017). A splat beyond that count is not the attack's: with our thrall out
 * it is the thrall's to claim, and without one it is logged as an extra.
 *
 * <p>Before this, any second "mine" splat on a tick a melee blow had already
 * landed was taken as the same attack's, whatever the weapon: on tick 439 of
 * Eric's 19:34 log a tentacle's miss and the zombie thrall's 0 landed together
 * and both went to the player.
 *
 * <p>From the OSRS Wiki (Multi-hit weapons, the weapons' own pages), read
 * 2026-10-26. Where the wiki gives no timing the splats are allowed to spill
 * onto the next tick: that only matters when fewer than the count arrived on
 * the first, which is exactly when the timing is spread.
 * <ul>
 * <li>Scythe of vitur: 3 against 3x3 and larger, 2 against 2x2, 1 against 1x1
 * (the arc beside it is other targets).</li>
 * <li>Dual macuahuitl: 2, "spaced 1 game tick apart when attacking monsters";
 * the special, Blood Infusion, likewise 2.</li>
 * <li>Twinflame staff: a second spell "one tick after the first" for elemental
 * spells except Strike and Surge, so bolt, blast and wave.</li>
 * <li>Dark bow: 2 arrows "hitting twice on the same game tick", and 2 on the
 * special.</li>
 * <li>Sulphur blades, glacial temotli, earthbound tecpatl, Torag's hammers: 2
 * on the same tick. Infernal tecpatl: 2; its special, Brand, 4.</li>
 * <li>Tonalztics of Ralos, charged: 2, and 2 on its special. Uncharged: 1.</li>
 * <li>Specials: dragon claws 4, burning claws 3 (two, then the third a tick
 * later: Eric's logs, Vardorvis 2300, 2392, 2730, 4487 and 17:15's 997-998),
 * dragon dagger 2 ("a slight delay between the two hits" on NPCs), abyssal
 * dagger 2, dragon and crystal halberd 2 (twice on targets of two tiles or
 * more, and a 1x1 "may be hit a second time", so 2 for any size), Saradomin
 * sword 2 (the melee hit and its magic hit), magic shortbow 2, webweaver bow 4,
 * dragon knives 2, Dinh's bulwark 2 in multicombat and 1 outside it ("The
 * targeted monster gets hit twice by the attack ... only occur in multicombat
 * areas", wiki, read 2026-10-27; T-032).</li>
 * <li>Everything else: 1.</li>
 * </ul>
 *
 * <p>Left out, each a splat that could still be logged as an extra: Karil's
 * crossbow with its set (a 25% chance of a second hit), the ancient godsword's
 * delayed 25 and the ursine chainmace's Bear Down damage (both arrive ticks
 * after the attack, outside any burst), and recoil and vengeance (Part B).
 */
final class AttackSplats
{
	/** An attack that puts one splat on its target, which is nearly all of them. */
	static final AttackSplats ONE = new AttackSplats(1, 1, false);

	private static final Set<Integer> SCYTHES = ids(ItemID.SCYTHE_OF_VITUR, ItemID.SCYTHE_OF_VITUR_UNCHARGED,
		ItemID.SCYTHE_OF_VITUR_OR, ItemID.SCYTHE_OF_VITUR_UNCHARGED_OR, ItemID.SCYTHE_OF_VITUR_BL,
		ItemID.SCYTHE_OF_VITUR_UNCHARGED_BL, ItemID.DEADMAN_BLIGHTED_SCYTHE_OF_VITUR,
		ItemID.DEADMAN_BLIGHTED_SCYTHE_OF_VITUR_UNCHARGED);
	private static final Set<Integer> MACUAHUITLS = ids(ItemID.DUAL_MACUAHUITL, ItemID.BR_DUAL_MACUAHUITL);
	// Two independently rolled hits on the same tick, every attack.
	private static final Set<Integer> TWO_ON_ONE_TICK = ids(ItemID.SULPHUR_BLADES, ItemID.GLACIAL_TEMOTLI,
		ItemID.EARTHBOUND_TECPATL, ItemID.BARROWS_TORAG_WEAPON, ItemID.BARROWS_TORAG_WEAPON_100,
		ItemID.BARROWS_TORAG_WEAPON_75, ItemID.BARROWS_TORAG_WEAPON_50, ItemID.BARROWS_TORAG_WEAPON_25);

	/** All the splats the attack can put on this target. */
	final int count;
	/** How many of them can land on the attack's first tick. */
	final int firstTick;
	/** Whether any can land on the tick after the first. */
	final boolean late;

	AttackSplats(int count, int firstTick, boolean late)
	{
		this.count = count;
		this.firstTick = firstTick;
		this.late = late;
	}

	/** Several splats, any number of them on the first tick and the rest, if any, on the next. */
	private static AttackSplats upTo(int count)
	{
		return count <= 1 ? ONE : new AttackSplats(count, count, true);
	}

	/** Several splats, all on one tick. */
	private static AttackSplats sameTick(int count)
	{
		return new AttackSplats(count, count, false);
	}

	/** Several splats, this many on the first tick and the rest on the next. */
	private static AttackSplats thenLate(int count, int first)
	{
		return new AttackSplats(count, first, true);
	}

	/** The dual macuahuitl: two hits a tick apart, the max split, the second only if the first landed (T-032). */
	static boolean isMacuahuitl(int weaponId)
	{
		return MACUAHUITLS.contains(weaponId);
	}

	/**
	 * Two independently rolled hits that split the max between them, ⌊M/2⌋ and
	 * the rest (wiki, Multi-hit weapons, "45 → 22 + 23"): Torag's hammers, the
	 * sulphur blades, the glacial temotli and both tecpatls (T-032).
	 */
	static boolean splitsMaxInTwo(int weaponId)
	{
		return TWO_ON_ONE_TICK.contains(weaponId) || weaponId == ItemID.INFERNAL_TECPATL;
	}

	/** The splats one attack puts on its target, counting Dinh's bulwark's special at its multicombat most. */
	static AttackSplats of(int weaponId, boolean special, int targetSize, Spell spell)
	{
		return of(weaponId, special, targetSize, spell, true);
	}

	/**
	 * The splats one attack puts on its target.
	 *
	 * @param weaponId   the weapon that threw it
	 * @param special    whether it was the weapon's special
	 * @param targetSize the target's size in tiles, which only the scythe reads
	 * @param spell      the spell cast, or null; only the twinflame staff reads it
	 * @param multi      whether the player is in a multicombat area; only Dinh's bulwark's special reads it
	 */
	static AttackSplats of(int weaponId, boolean special, int targetSize, Spell spell, boolean multi)
	{
		if (SCYTHES.contains(weaponId))
		{
			// The scythe has no special, and its swing is the same whatever is asked.
			return upTo(CombatCalc.scytheHits(targetSize));
		}
		if (MACUAHUITLS.contains(weaponId))
		{
			return thenLate(2, 1);
		}
		if (TWO_ON_ONE_TICK.contains(weaponId))
		{
			return sameTick(2);
		}
		if (weaponId == ItemID.INFERNAL_TECPATL)
		{
			return special ? upTo(4) : upTo(2);
		}
		if (weaponId == ItemID.TONALZTICS_OF_RALOS_CHARGED)
		{
			return upTo(2);
		}
		if (weaponId == ItemID.TWINFLAME_STAFF)
		{
			// The same rule as the expected damage's second hit (T-026's Spell.twinflameFiresTwice), so the two cannot drift.
			return spell != null && spell.twinflameFiresTwice() ? thenLate(2, 1) : ONE;
		}
		final SpecialAttack weapon = SpecialAttack.forItem(weaponId);
		if (weapon == SpecialAttack.DARK_BOW)
		{
			return special ? upTo(2) : sameTick(2);
		}
		if (!special || weapon == null)
		{
			return ONE;
		}
		switch (weapon)
		{
			case DRAGON_CLAWS:
			case WEBWEAVER_BOW:
				return upTo(4);
			case BURNING_CLAWS:
				return thenLate(3, 2);
			case DRAGON_DAGGER:
			case ABYSSAL_DAGGER:
			case DRAGON_HALBERD:
			case CRYSTAL_HALBERD:
			case SARADOMIN_SWORD:
			case MAGIC_SHORTBOW:
			case DRAGON_KNIFE:
				return upTo(2);
			case DINHS_BULWARK:
				return multi ? upTo(2) : ONE;
			default:
				return ONE;
		}
	}

	/**
	 * The larger of two counts, splat for splat: what either of two weapons
	 * could have put down. Used either side of a switch on the tick an attack
	 * was thrown, so the switch never costs the attack its own splats.
	 */
	static AttackSplats atLeast(AttackSplats a, AttackSplats b)
	{
		if (a == null)
		{
			return b;
		}
		if (b == null)
		{
			return a;
		}
		final int count = Math.max(a.count, b.count);
		return count == 1 ? ONE : new AttackSplats(count, Math.max(a.firstTick, b.firstTick), a.late || b.late);
	}

	private static Set<Integer> ids(int... ids)
	{
		final Set<Integer> out = new HashSet<>();
		for (int id : ids)
		{
			out.add(id);
		}
		return out;
	}

	/**
	 * Whether the special energy fell close enough to an attack thrown on this
	 * tick to have been its special. The energy falls on the throw (a traced
	 * burning claws special: the drop on 45, its first splat on 46), but its varp
	 * can arrive late, and a projectile can be seen a tick after it was thrown,
	 * so a tick either side. Never wider: the fastest weapon attacks every two
	 * ticks.
	 */
	static boolean specialNear(Iterable<Integer> energyDrops, int thrownTick)
	{
		for (int drop : energyDrops)
		{
			if (drop >= thrownTick - 1 && drop <= thrownTick + 1)
			{
				return true;
			}
		}
		return false;
	}

	@Override
	public String toString()
	{
		return count == 1 ? "1" : count + (firstTick < count ? " (" + firstTick + " then " + (count - firstTick) + ")" : "");
	}

	/** Where a splat on the target stands against the attack landing there. */
	enum State
	{
		/** No attack of the player's is landing on this target now. */
		NONE,
		/** The attack landed here this tick and has splats still to come on it. */
		ROOM_THIS_TICK,
		/** The attack landed here this tick and has put down all it can on this tick. */
		SPENT_THIS_TICK,
		/** The attack landed last tick and its remaining splats land now: a dual macuahuitl, burning claws' third. */
		LATE_DUE,
		/** The attack landed last tick and may still have a splat to land: a special whose timing is not known. */
		LATE_MAYBE
	}

	/**
	 * One attack's splats on one target as they arrive: how many it can put
	 * down, and how many it has. Pure, so the plugin and the replay tests count
	 * alike.
	 */
	static final class Burst
	{
		private final int thrownTick;
		private final int startTick;
		private final AttackSplats ordinary;
		private final AttackSplats specialSplats;
		private final String weapon;
		private boolean special;
		private int given;
		private int lastTick;

		/**
		 * The attack's first splat, which has just landed.
		 *
		 * @param thrownTick the tick the attack went out on
		 * @param startTick  the tick its first splat landed on: now
		 * @param ordinary   the splats if it was an ordinary attack
		 * @param special    the splats if it was the weapon's special
		 * @param isSpecial  whether it is known to have been the special; it can be
		 *                   marked later, as the energy can fall after its first splat
		 */
		Burst(int thrownTick, int startTick, AttackSplats ordinary, AttackSplats special, boolean isSpecial,
			String weapon)
		{
			this.thrownTick = thrownTick;
			this.startTick = startTick;
			this.ordinary = ordinary;
			this.specialSplats = special;
			this.special = isSpecial;
			this.weapon = weapon;
			this.given = 1;
			this.lastTick = startTick;
		}

		AttackSplats splats()
		{
			return special ? specialSplats : ordinary;
		}

		void markSpecial()
		{
			special = true;
		}

		boolean isSpecial()
		{
			return special;
		}

		int thrownTick()
		{
			return thrownTick;
		}

		int startTick()
		{
			return startTick;
		}

		int given()
		{
			return given;
		}

		int lastTick()
		{
			return lastTick;
		}

		String weapon()
		{
			return weapon;
		}

		/** Where a splat landing on this target now stands. */
		State stateOn(int tick)
		{
			final AttackSplats s = splats();
			if (tick == lastTick)
			{
				return given < (tick == startTick ? Math.min(s.firstTick, s.count) : s.count)
					? State.ROOM_THIS_TICK : State.SPENT_THIS_TICK;
			}
			if (tick == startTick + 1 && lastTick == startTick && s.late && given < s.count)
			{
				// Known to land a tick apart when the first tick could not take them all.
				return s.firstTick < s.count ? State.LATE_DUE : State.LATE_MAYBE;
			}
			return State.NONE;
		}

		/** Whether a splat landing now can still be this attack's, on its own tick or the one after. */
		boolean belongsOn(int tick)
		{
			return tick == lastTick || tick == lastTick + 1;
		}

		/**
		 * Counts a splat given to the player as this attack's.
		 *
		 * @return whether it was within the count; one beyond it is an extra
		 */
		boolean give(int tick)
		{
			final State before = stateOn(tick);
			given++;
			lastTick = tick;
			return before == State.ROOM_THIS_TICK || before == State.LATE_DUE || before == State.LATE_MAYBE;
		}

		/** Takes back a splat that was counted and then re-judged as the thrall's. */
		void takeBack()
		{
			given = Math.max(1, given - 1);
		}
	}
}
