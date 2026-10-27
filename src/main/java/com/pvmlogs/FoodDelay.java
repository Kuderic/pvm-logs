package com.pvmlogs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.IntUnaryOperator;
import net.runelite.api.gameval.ItemID;

/**
 * How long eating holds the next attack back, and which lost ticks are the
 * eat's rather than idle (T-012). Pure: the plugin feeds it clicks, inventory
 * counts and the weapon's due tick, so the split is testable without a client.
 *
 * <p>The mechanic (OSRS Wiki, Food and Food/Fast foods, 2026-10-26): with a
 * weapon attack pending, eating adds the food's attack delay to the wait, 3
 * ticks for most food and 2 for fast food; combo eats stack (a shark and a
 * karambwan are 3 + 2). With the weapon already ready, an eat adds nothing.
 * Potions add nothing.
 *
 * <p>Ticks here are the game's, in the numbering the debug log uses: an eat's
 * tick is the one its inventory change arrives on, as an attack's is the one
 * its experience drop arrives on. An eat on the weapon's due tick itself counts
 * as pending - the eat is processed before the attack that tick - which is what
 * puts Eric's attack due on 1235 out on 1238, three ticks late.
 */
final class FoodDelay
{
	/** Most food, pies and pizzas per bite, and anything eaten that is not in the fast-food table. */
	static final int NORMAL = 3;
	/** The wiki's fast foods: karambwan, halibut, the Gauntlet's combo paddlefish, toad's legs, the gnome foods. */
	static final int FAST = 2;
	/** Potions, brews included: "Potions do not incur the standard 3 tick attack or eat delay." */
	static final int NONE = 0;
	// A click that has not been eaten by then never will be: a second food clicked inside the eat delay is refused.
	static final int CLICK_WINDOW_TICKS = 3;

	private static final Set<Integer> FAST_FOODS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		ItemID.TBWT_COOKED_KARAMBWAN, ItemID.BR_TBWT_COOKED_KARAMBWAN, ItemID.BLIGHTED_KARAMBWAN,
		ItemID.HALIBUT,
		// Crystal and corrupted paddlefish; the Gauntlet's plain paddlefish (GAUNTLET_FOOD) is a normal food.
		ItemID.GAUNTLET_COMBO_FOOD, ItemID.GAUNTLET_COMBO_FOOD_HM,
		ItemID.TOADS_LEGS,
		ItemID.TOAD_CRUNCHIES, ItemID.PREMADE_TOAD_CRUNCHIES, ItemID.ALUFT_TOAD_CRUNCHIES,
		ItemID.SPICY_CRUNCHIES, ItemID.PREMADE_SPICY_CRUNCHIES, ItemID.ALUFT_SPICY_CRUNCHIES,
		ItemID.WORM_CRUNCHIES, ItemID.PREMADE_WORM_CRUNCHIES, ItemID.ALUFT_WORM_CRUNCHIES,
		ItemID.CHOCCHIP_CRUNCHIES, ItemID.PREMADE_CHOCCHIP_CRUNCHIES, ItemID.ALUFT_CHOCCHIP_CRUNCHIES,
		ItemID.FRUIT_BATTA, ItemID.PREMADE_FRUIT_BATTA,
		ItemID.TOAD_BATTA, ItemID.PREMADE_TOAD_BATTA, ItemID.ALUFT_TOAD_BATTA,
		ItemID.WORM_BATTA, ItemID.PREMADE_WORM_BATTA,
		ItemID.VEGETABLE_BATTA, ItemID.PREMADE_VEGETABLE_BATTA, ItemID.ALUFT_VEGETABLE_BATTA,
		ItemID.CHEESE_TOM_BATTA, ItemID.PREMADE_CHEESE_TOM_BATTA, ItemID.ALUFT_CHEESE_TOM_BATTA,
		ItemID.WORM_HOLE, ItemID.PREMADE_WORM_HOLE,
		ItemID.VEG_BALL, ItemID.PREMADE_VEG_BALL,
		ItemID.CHOCOLATE_BOMB, ItemID.PREMADE_CHOCOLATE_BOMB,
		ItemID.TANGLED_TOADS_LEGS, ItemID.PREMADE_TANGLED_TOADS_LEGS)));

	// Drinks that are food. The wiki calls wine food but gives it no delay of its own, so this is an assumption: that it
	// holds the attack back like any other food. Every other drink is taken as a potion.
	private static final Set<Integer> FOOD_DRINKS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
		ItemID.JUG_WINE, ItemID.HALF_FULL_WINE_JUG, ItemID.JUG_BAD_WINE)));

	/** The attack delay one item adds, by the menu option it was taken with. Anything else adds none. */
	static int delayOf(int itemId, String option)
	{
		if ("Eat".equals(option))
		{
			return FAST_FOODS.contains(itemId) ? FAST : NORMAL;
		}
		if ("Drink".equals(option))
		{
			return FOOD_DRINKS.contains(itemId) ? NORMAL : NONE;
		}
		return NONE;
	}

	/** One eat the inventory confirmed. */
	static final class Eat
	{
		final int tick;
		final int clickTick;
		final int itemId;
		final int delay;
		// Filled in when the eat is settled, for the debug line.
		boolean settled;
		boolean pending;
		int dueBefore;
		int dueAfter;

		Eat(int tick, int clickTick, int itemId, int delay)
		{
			this.tick = tick;
			this.clickTick = clickTick;
			this.itemId = itemId;
			this.delay = delay;
		}
	}

	private static final class Click
	{
		final int tick;
		final int itemId;
		final int delay;
		// How many of the item were carried when it was clicked: the eat is the count going below this.
		int countBefore;

		Click(int tick, int itemId, int delay, int countBefore)
		{
			this.tick = tick;
			this.itemId = itemId;
			this.delay = delay;
			this.countBefore = countBefore;
		}
	}

	private final List<Click> clicks = new ArrayList<>();
	// The eats since the last attack went out, in the order they happened.
	private final List<Eat> eats = new ArrayList<>();

	/**
	 * An "Eat" or "Drink" click. Nothing is counted yet: a click can be
	 * refused, and two on the same food in a tick eat it once.
	 */
	void clicked(int tick, int itemId, String option, int countNow)
	{
		clicks.add(new Click(tick, itemId, delayOf(itemId, option), countNow));
	}

	/**
	 * The inventory changed on this tick. Every clicked item now carried fewer
	 * times than when it was clicked was eaten here - once per item gone, however
	 * many clicks it had. A bite counts too: a pie becomes half a pie, so there is
	 * one pie fewer.
	 *
	 * @param countOf how many of an item the inventory holds now
	 * @return the eats confirmed
	 */
	List<Eat> inventoryChanged(int tick, IntUnaryOperator countOf)
	{
		final List<Eat> confirmed = new ArrayList<>();
		for (Iterator<Click> it = clicks.iterator(); it.hasNext(); )
		{
			final Click click = it.next();
			if (tick - click.tick > CLICK_WINDOW_TICKS)
			{
				it.remove();
				continue;
			}
			final int now = countOf.applyAsInt(click.itemId);
			if (now < click.countBefore)
			{
				final Eat eat = new Eat(tick, click.tick, click.itemId, click.delay);
				eats.add(eat);
				confirmed.add(eat);
				it.remove();
				// Any other click on the same item is measured from here, so a double click eats one.
				for (Click other : clicks)
				{
					if (other.itemId == click.itemId)
					{
						other.countBefore = Math.min(other.countBefore, click.countBefore - 1);
					}
				}
			}
		}
		return confirmed;
	}

	/**
	 * The delay the eats so far add to an attack due on {@code dueTick}. Each
	 * counts only if it came while that attack was still pending, the delays
	 * before it included; one after the weapon was ready adds nothing.
	 */
	int added(int dueTick)
	{
		return added(dueTick, eats);
	}

	static int added(int dueTick, List<Eat> eats)
	{
		if (dueTick == Integer.MIN_VALUE)
		{
			return 0;
		}
		int added = 0;
		for (Eat eat : eats)
		{
			if (eat.tick <= dueTick + added)
			{
				added += eat.delay;
			}
		}
		return added;
	}

	/**
	 * Whether a lost tick is the eat's. The tick loop at {@code now} judges the
	 * attack tick {@code now - lag}, which is overdue against the weapon's own
	 * due tick; it is the eat's while it falls inside the delay the eats added,
	 * and idle after it.
	 */
	static boolean isEatingTick(int now, int dueTick, int lag, int added)
	{
		return dueTick != Integer.MIN_VALUE && now - lag < dueTick + added;
	}

	/** Which tick of the added delay this lost tick is, from 1: the k of "eating k/N". */
	static int eatingTickNumber(int now, int dueTick, int lag)
	{
		return now - lag - dueTick + 1;
	}

	/**
	 * Settles the eats the bookings can no longer change: those on or before the
	 * attack tick the loop is judging, against the due tick now known. Each is
	 * returned once, with what it did, for the debug log.
	 */
	List<Eat> settle(int judgedAttackTick, int dueTick)
	{
		final List<Eat> settled = new ArrayList<>();
		int added = 0;
		for (Eat eat : eats)
		{
			final boolean pending = dueTick != Integer.MIN_VALUE && eat.tick <= dueTick + added;
			if (!eat.settled && eat.tick <= judgedAttackTick)
			{
				eat.settled = true;
				eat.pending = pending;
				eat.dueBefore = dueTick == Integer.MIN_VALUE ? dueTick : dueTick + added;
				eat.dueAfter = pending ? eat.dueBefore + eat.delay : eat.dueBefore;
				settled.add(eat);
			}
			if (pending)
			{
				added += eat.delay;
			}
		}
		return settled;
	}

	/**
	 * An attack went out on this tick. The eats on or before it were spent
	 * waiting for it (anything not yet settled is settled here, against the due
	 * tick it was waiting on); any after it belong to the next cooldown.
	 */
	List<Eat> attackThrown(int attackTick, int dueTick)
	{
		final List<Eat> settled = settle(attackTick, dueTick);
		eats.removeIf(eat -> eat.tick <= attackTick);
		return settled;
	}

	/** The fight ended or the plugin reset: nothing carries over. */
	void reset()
	{
		clicks.clear();
		eats.clear();
	}
}
