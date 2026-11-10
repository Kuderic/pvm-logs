package com.pvmlogs;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * T-192: the four badges' rules, in one place beside the grade (T-189's, from T-186's findings), so the kill card and the
 * record's counts read one answer. Each is earned from skill or a count, never luck:
 * <ul>
 * <li>every 100th kill, by the game's kill count ({@code FightContext.kc});</li>
 * <li>Flawless: no avoidable damage taken, only where the boss has attack rules to tell it;</li>
 * <li>Clean run: no idle tick (eating isn't idling), and the setup at 99.95% or better;</li>
 * <li>3 A's in a row: earned on the kill a run of A or better reaches 3 on, so once per run (the card names the run on
 * its later kills too, "5 A's in a row", from the record's streak).</li>
 * </ul>
 */
final class Badges
{
	/** In the card's order. */
	enum Badge
	{
		HUNDREDTH_KILL("100th kill"),
		FLAWLESS("Flawless"),
		CLEAN_RUN("Clean run"),
		THREE_IN_A_ROW("3 A's in a row");

		/** Its name where it's counted (History's "Flawless ×5"); the card names a kill's own ("600th kill"). */
		final String title;

		Badge(String title)
		{
			this.title = title;
		}
	}

	/** The streak that earns its badge: three kills of A or better in a row. */
	static final int STREAK = 3;
	/** Clean run's setup: 99.95% or better. */
	static final double CLEAN_SETUP = 0.9995;

	private Badges()
	{
	}

	/** Every 100th kill, by the game's count; 0 (unknown) never is. */
	static boolean hundredth(int kc)
	{
		return kc > 0 && kc % 100 == 0;
	}

	/** Whether the boss has attack rules to tell avoidable damage by: the after-kill avoidable eating is known. */
	static boolean ruled(LedgerRows.Numbers n)
	{
		return !Double.isNaN(n.avoidableEatingCost);
	}

	/** No avoidable damage taken, where the boss has attack rules; false where it hasn't. */
	static boolean flawless(LedgerRows.Numbers n, int damageTakenAvoidable)
	{
		return ruled(n) && damageTakenAvoidable == 0;
	}

	/** No idle tick (eating isn't idling: uptime holds only idle ticks), and the setup at 99.95% or better. */
	static boolean cleanRun(LedgerRows.Numbers n)
	{
		return !Double.isNaN(n.uptime) && n.uptime >= 1 && n.efficiency >= CLEAN_SETUP;
	}

	/** The kill the A-or-better streak, this one included, reaches 3 on: once per run. */
	static boolean threeInARow(int streak)
	{
		return streak == STREAK;
	}

	/**
	 * The badges an ended kill earned. {@code damageTakenAvoidable} is the scope's ({@code Scope.damageTakenAvoidable});
	 * {@code streak} the record's A-or-better streak with this kill (0 when there's no record).
	 */
	static Set<Badge> earned(LedgerRows.Numbers n, int damageTakenAvoidable, int kc, int streak)
	{
		return earned(kc, flawless(n, damageTakenAvoidable), cleanRun(n), streak);
	}

	/** The same, from what each rule read (the record's saved kill figures). */
	static Set<Badge> earned(int kc, boolean flawless, boolean cleanRun, int streak)
	{
		final EnumSet<Badge> out = EnumSet.noneOf(Badge.class);
		if (hundredth(kc))
		{
			out.add(Badge.HUNDREDTH_KILL);
		}
		if (flawless)
		{
			out.add(Badge.FLAWLESS);
		}
		if (cleanRun)
		{
			out.add(Badge.CLEAN_RUN);
		}
		if (threeInARow(streak))
		{
			out.add(Badge.THREE_IN_A_ROW);
		}
		return Collections.unmodifiableSet(out);
	}
}
