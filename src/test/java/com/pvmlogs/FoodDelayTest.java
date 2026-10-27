package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.gameval.ItemID;
import org.junit.Test;

/**
 * T-012: an eat while an attack is pending costs exactly its delay, wherever
 * in the cooldown it happens, and those ticks are the eat's; an eat while the
 * weapon is ready costs nothing. Every case runs through the tick loop the way
 * the plugin does, with a 4-tick weapon.
 */
public class FoodDelayTest
{
	private static final int SPEED = 4;
	private static final int MELEE_LAG = 1;
	private static final int PROJECTILE_LAG = 2;

	private static final int SHARK = ItemID.SHARK;
	private static final int KARAMBWAN = ItemID.TBWT_COOKED_KARAMBWAN;
	private static final int CRYSTAL_PADDLEFISH = ItemID.GAUNTLET_COMBO_FOOD;
	private static final int BREW = ItemID._4DOSEPOTIONOFSARADOMIN;

	/** One click on food or drink: the tick it was clicked, the tick the game ate it (or MIN if refused), the item. */
	private static final class Bite
	{
		final int clicked;
		final int eaten;
		final int itemId;
		final String option;

		Bite(int clicked, int eaten, int itemId, String option)
		{
			this.clicked = clicked;
			this.eaten = eaten;
			this.itemId = itemId;
			this.option = option;
		}
	}

	private static Bite eat(int itemId, int eatenOn)
	{
		// Clicked the tick before: the game processes a click on the next tick.
		return new Bite(eatenOn - 1, eatenOn, itemId, "Eat");
	}

	private static Bite drink(int itemId, int drunkOn)
	{
		return new Bite(drunkOn - 1, drunkOn, itemId, "Drink");
	}

	/** {eating, idle} lost ticks between an attack thrown on {@code thrown} and the next thrown on {@code next}. */
	private static int[] split(int thrown, int next, int lag, Bite... bites)
	{
		final FoodDelay food = new FoodDelay();
		final Map<Integer, Integer> carried = new HashMap<>();
		carried.put(SHARK, 10);
		carried.put(KARAMBWAN, 10);
		carried.put(CRYSTAL_PADDLEFISH, 10);
		carried.put(BREW, 10);
		int due = Integer.MIN_VALUE;
		int eating = 0;
		int idle = 0;
		// From the attack's own tick, so a click right after it is seen; the loop starts judging once it is booked.
		for (int tick = thrown; tick <= next + lag; tick++)
		{
			// The packet: anything eaten this tick leaves the inventory before the tick loop runs.
			boolean changed = false;
			for (Bite bite : bites)
			{
				if (bite.eaten == tick)
				{
					carried.merge(bite.itemId, -1, Integer::sum);
					changed = true;
				}
			}
			if (changed)
			{
				food.inventoryChanged(tick, id -> carried.getOrDefault(id, 0));
			}
			// The tick loop, as trackAttackCooldown.
			if (tick < thrown + lag)
			{
				// The previous cooldown, not this test's.
			}
			else if (tick == thrown + lag || tick == next + lag)
			{
				final int attackTick = tick - lag;
				food.attackThrown(attackTick, due);
				due = attackTick + SPEED;
			}
			else
			{
				food.settle(tick - lag, due);
				if (PvmPerformancePlugin.attackOverdue(tick, due, lag))
				{
					if (FoodDelay.isEatingTick(tick, due, lag, food.added(due)))
					{
						eating++;
					}
					else
					{
						idle++;
					}
				}
			}
			// Clicks come between ticks, after the loop.
			for (Bite bite : bites)
			{
				if (bite.clicked == tick)
				{
					food.clicked(tick, bite.itemId, bite.option, carried.getOrDefault(bite.itemId, 0));
				}
			}
		}
		return new int[]{eating, idle};
	}

	private static void assertSplit(int eating, int idle, int[] actual)
	{
		assertEquals("eating", eating, actual[0]);
		assertEquals("idle", idle, actual[1]);
	}

	@Test
	public void aSharkRightAfterAnAttackIsThreeEatingNoneIdle()
	{
		// The old window started at the click and spent itself inside the cooldown: this read 0 eating, 3 idle.
		assertSplit(3, 0, split(100, 107, MELEE_LAG, eat(SHARK, 101)));
	}

	@Test
	public void aSharkOneTickBeforeTheDueTickIsStillThree()
	{
		assertSplit(3, 0, split(100, 107, MELEE_LAG, eat(SHARK, 103)));
	}

	@Test
	public void ericsVardorvisAttackDueOn1235ThrownOn1238IsThreeEating()
	{
		// pvm-debug-recent-20261026-171550.log: thrown 1231, due 1235, next thrown 1238, exactly one food delay late. The
		// plugin booked 2 eating and 1 idle. Eaten on the due tick itself, it is still pending: the eat goes before the attack.
		assertSplit(3, 0, split(1231, 1238, MELEE_LAG, eat(SHARK, 1235)));
		// Or a tick earlier, which the log cannot tell apart: the same split.
		assertSplit(3, 0, split(1231, 1238, MELEE_LAG, eat(SHARK, 1234)));
	}

	@Test
	public void aKarambwanIsTwo()
	{
		assertSplit(2, 0, split(100, 106, MELEE_LAG, eat(KARAMBWAN, 101)));
	}

	@Test
	public void aCrystalPaddlefishIsTwo()
	{
		assertSplit(2, 0, split(100, 106, MELEE_LAG, eat(CRYSTAL_PADDLEFISH, 101)));
	}

	@Test
	public void aSharkAndAKarambwanInOneTickAreFive()
	{
		assertSplit(5, 0, split(100, 109, MELEE_LAG, eat(SHARK, 101), eat(KARAMBWAN, 101)));
	}

	@Test
	public void twoSharksThreeTicksApartBothWhilePendingAreSix()
	{
		// The second comes on 104, inside the first's delay (due 107), so the attack is still pending.
		assertSplit(6, 0, split(100, 110, MELEE_LAG, eat(SHARK, 101), eat(SHARK, 104)));
	}

	@Test
	public void aSharkWhileTheWeaponIsReadyCostsNothing()
	{
		// Due 104, nothing thrown, eaten on 106: the weapon was ready, so no delay - four idle ticks.
		assertSplit(0, 4, split(100, 108, MELEE_LAG, eat(SHARK, 106)));
	}

	@Test
	public void aBrewWhilePendingCostsNothing()
	{
		assertSplit(0, 0, split(100, 104, MELEE_LAG, drink(BREW, 101)));
		// And a late attack after one is idle, not eating.
		assertSplit(0, 2, split(100, 106, MELEE_LAG, drink(BREW, 101)));
	}

	@Test
	public void aDoubleClickOnOneSharkInATickIsThree()
	{
		final Bite first = eat(SHARK, 101);
		final Bite second = new Bite(100, Integer.MIN_VALUE, SHARK, "Eat");
		assertSplit(3, 0, split(100, 107, MELEE_LAG, first, second));
	}

	@Test
	public void aSecondSharkClickedInsideTheEatDelayIsRefusedAndCountsNothing()
	{
		// Clicked on 101 and never eaten: the inventory never loses a second shark.
		assertSplit(3, 0, split(100, 107, MELEE_LAG, eat(SHARK, 101), new Bite(101, Integer.MIN_VALUE, SHARK, "Eat")));
	}

	@Test
	public void aSharkThenTheAttackTwoTicksAfterTheDelayEndsIsThreeEatingTwoIdle()
	{
		assertSplit(3, 2, split(100, 109, MELEE_LAG, eat(SHARK, 101)));
	}

	@Test
	public void anEatJustAfterAnAttackNotYetBookedBelongsToTheNextCooldown()
	{
		// A projectile thrown on 100 is booked on 102. The shark eaten on 101 is after it, so it holds back the attack due
		// on 104 - the booking must not wipe it.
		assertSplit(3, 0, split(100, 107, PROJECTILE_LAG, eat(SHARK, 101)));
	}

	@Test
	public void noAttackDueMeansNoDelay()
	{
		assertEquals(0, FoodDelay.added(Integer.MIN_VALUE, new ArrayList<>()));
		assertFalse(FoodDelay.isEatingTick(200, Integer.MIN_VALUE, MELEE_LAG, 3));
	}

	@Test
	public void eachEatIsSettledOnceWithWhatItDidToTheDueTick()
	{
		final FoodDelay food = new FoodDelay();
		food.clicked(100, SHARK, "Eat", 5);
		food.clicked(100, KARAMBWAN, "Eat", 5);
		food.inventoryChanged(101, id -> 4);
		// Not settled before the loop has judged its tick.
		assertTrue(food.settle(100, 104).isEmpty());
		final List<FoodDelay.Eat> settled = food.settle(101, 104);
		assertEquals(2, settled.size());
		assertTrue(settled.get(0).pending);
		assertEquals(104, settled.get(0).dueBefore);
		assertEquals(107, settled.get(0).dueAfter);
		assertEquals(107, settled.get(1).dueBefore);
		assertEquals(109, settled.get(1).dueAfter);
		assertEquals(100, settled.get(0).clickTick);
		assertTrue(food.settle(102, 104).isEmpty());
		assertEquals(5, food.added(104));
		assertEquals("eating 1/5 on the first lost tick", 1, FoodDelay.eatingTickNumber(105, 104, MELEE_LAG));
	}

	@Test
	public void delaysByItem()
	{
		assertEquals(3, FoodDelay.delayOf(SHARK, "Eat"));
		assertEquals(3, FoodDelay.delayOf(ItemID.MANTARAY, "Eat"));
		assertEquals(2, FoodDelay.delayOf(KARAMBWAN, "Eat"));
		assertEquals(2, FoodDelay.delayOf(ItemID.BR_TBWT_COOKED_KARAMBWAN, "Eat"));
		assertEquals(2, FoodDelay.delayOf(ItemID.HALIBUT, "Eat"));
		assertEquals(2, FoodDelay.delayOf(CRYSTAL_PADDLEFISH, "Eat"));
		assertEquals(2, FoodDelay.delayOf(ItemID.GAUNTLET_COMBO_FOOD_HM, "Eat"));
		// The Gauntlet's plain paddlefish is normal food.
		assertEquals(3, FoodDelay.delayOf(ItemID.GAUNTLET_FOOD, "Eat"));
		assertEquals(2, FoodDelay.delayOf(ItemID.TOADS_LEGS, "Eat"));
		assertEquals(2, FoodDelay.delayOf(ItemID.PREMADE_CHOCOLATE_BOMB, "Eat"));
		// Something not in the table that is eaten: normal food.
		assertEquals(3, FoodDelay.delayOf(123456, "Eat"));
		// Potions: none.
		assertEquals(0, FoodDelay.delayOf(BREW, "Drink"));
		assertEquals(0, FoodDelay.delayOf(ItemID._4DOSEDIVINECOMBAT, "Drink"));
		// Wine is food with a Drink option (an assumption: the wiki gives it no delay of its own).
		assertEquals(3, FoodDelay.delayOf(ItemID.JUG_WINE, "Drink"));
		assertEquals(0, FoodDelay.delayOf(SHARK, "Use"));
	}
}
