package com.pvmlogs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assume.assumeTrue;
import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Five of Eric's Vardorvis kills (2026-10-27, 16:03 kills 4 and 8, 17:03 kills 4,
 * 6 and 9), read back through the classifier and the hitpoints replay, against
 * what T-039's harness ({@code claude/specs/avoidable-harness/avoidable.py}, run
 * with the bundled rules) gives for the same kills.
 *
 * <p>The excerpt holds only the lines the two read: fight START and END, TAKEN,
 * Vardorvis's heals, EAT and the eating ticks. Until it is committed, the test
 * reads it from {@code PVM_AVOIDABLE_FIXTURE} and is skipped without it.
 */
public class AvoidableReplayTest
{
	static final String FIXTURE = "replay/vardorvis-avoidable-5-kills-20261027.log";

	private static Reader fixture() throws IOException
	{
		final InputStream in = AvoidableReplayTest.class.getResourceAsStream(FIXTURE);
		if (in != null)
		{
			return new InputStreamReader(in, StandardCharsets.UTF_8);
		}
		final String path = System.getenv("PVM_AVOIDABLE_FIXTURE");
		assumeTrue("fixture " + FIXTURE + " not committed and PVM_AVOIDABLE_FIXTURE not set", path != null);
		return new StringReader(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
			StandardCharsets.UTF_8));
	}

	static BossRules.AttacksOnPlayer vardorvis()
	{
		final BossRules rules = BossRules.load(new Gson(), null);
		return BossRules.attacksFor(rules.forNpc(12223), 700);
	}

	private static List<AvoidableLogReplay.Kill> kills() throws IOException
	{
		return AvoidableLogReplay.read(fixture(), vardorvis(), 99);
	}

	private static void classes(AvoidableLogReplay.Kill kill, int melee, int offPrayer, int head, int axe, int bleed,
		int spike)
	{
		final Map<String, Integer> by = kill.byClass;
		assertEquals("melee", melee, (int) by.getOrDefault("melee", 0));
		assertEquals("melee off prayer", offPrayer, (int) by.getOrDefault("melee off prayer", 0));
		assertEquals("head", head, (int) by.getOrDefault("head", 0));
		assertEquals("axe", axe, (int) by.getOrDefault("axe", 0));
		assertEquals("bleed", bleed, (int) by.getOrDefault("bleed", 0));
		assertEquals("spike", spike, (int) by.getOrDefault("spike", 0));
		assertEquals("unnamed", 0, (int) by.getOrDefault("unnamed", 0));
	}

	private static void replay(AvoidableLogReplay.Kill kill, int eatingTicks, int eats, int with, int without, int line,
		String source, double ticks)
	{
		assertEquals("eating ticks", eatingTicks, kill.eatingTicks);
		assertEquals("eats", eats, kill.result.eats);
		assertEquals("replay with", with, kill.result.eatsWith);
		assertEquals("replay without", without, kill.result.eatsWithout);
		assertEquals("line", line, kill.result.line);
		assertEquals("line from", source, kill.result.lineSource);
		assertEquals("food", 20, kill.result.foodHeal);
		assertEquals("avoidable eating ticks", ticks, kill.result.avoidableTicks, 1e-9);
	}

	/**
	 * A made-up kill, so the reading back is checked in CI whatever fixture there
	 * is: a melee through prayer, one off it (30, ¾ avoidable), an axe and its bleed,
	 * a spike after a dash, a shark, a 0. The harness gives the same: avoidable
	 * 65.5, the replay eating twice with the avoidable hits and not at all without.
	 */
	static final String MADE_UP_KILL = String.join("\n",
		"[100] fight START Vardorvis (npc 12223, index 1, maxHp 700) group=null room=Vardorvis raid=null",
		"[105] TAKEN 8 type 16 hit (damage), protect melee, hp 99, from npc 12223 #1 (target); target anim 10340 t-1",
		"[105] splat 4 heal (type 6, no owner) on Vardorvis (npc 12223, index 1): HEAL, target has 600 left (bar)",
		"[110] TAKEN 30 type 16 hit (damage), protect none, hp 91, from npc 12223 #1 (target); target anim 10340 t-6, "
			+ "anim 10340 t-1",
		"[110] splat 15 heal (type 6, no owner) on Vardorvis (npc 12223, index 1): HEAL, target has 585 left (bar)",
		"[112] TAKEN 15 type 16 hit (damage), protect melee, hp 61, from npc 12223 #1 (target); target anim 10340 t-3",
		"[114] TAKEN 3 type 67 dot (bleed), protect melee, hp 46, from npc 12223 #1 (target); target nothing seen",
		"[115] TAKEN 25 type 16 hit (damage), protect melee, hp 43, from npc 12223 #1 (target); target anim 10341 t-2",
		"[116] splat 12 heal (type 6, no owner) on Vardorvis (npc 12223, index 1): HEAL, target has 560 left (bar)",
		"[117] tick LOST (eating 1/3): overdue since 116 + lag 1",
		"[118] tick LOST (eating 2/3): overdue since 116 + lag 1",
		"[119] tick LOST (eating 3/3): overdue since 116 + lag 1",
		"[121] EAT Shark (delay 3) on tick 116, clicked 115: attack pending, due 116 -> 119",
		"[121] TAKEN 5 type 16 hit (damage), protect melee, hp 38, from npc 12223 #1 (target); target anim 10340 t-1",
		"[121] splat 2 heal (type 6, no owner) on Vardorvis (npc 12223, index 1): HEAL, target has 0 left (bar)",
		"[123] TAKEN 0 type 12 hit (zero), protect melee, hp 33, from npc 12223 #1 (target); target anim 10340 t-1",
		"[125] fight END Vardorvis (npc 12223) target died: attempts=10 made=10 damage=700 thrallDamage=0 hits=9 "
			+ "ticksLost=3 combatTicks=25 expDamage=650.0");

	@Test
	public void aMadeUpKillReadsAsTheHarnessReadsIt() throws IOException
	{
		final List<AvoidableLogReplay.Kill> kills = AvoidableLogReplay.read(new StringReader(MADE_UP_KILL), vardorvis(), 99);
		assertEquals(1, kills.size());
		final AvoidableLogReplay.Kill kill = kills.get(0);
		classes(kill, 13, 30, 0, 15, 3, 25);
		assertEquals(65.5, kill.avoidable, 1e-9);
		assertEquals(7, kill.named.size());
		assertEquals("melee zero", kill.named.get(6).name);
		replay(kill, 3, 1, 2, 0, 39, AvoidableEating.LINE_DEFAULT, 3.0);
	}

	@Test
	public void allFiveKillsAreRead() throws IOException
	{
		assertEquals(5, kills().size());
	}

	/**
	 * 16:03 kill 4, the worst: 263 taken, an unblocked gaze and the off-prayer
	 * melee after it, spikes, axes, an axe with a melee on one tick. 30 off prayer
	 * counts 22.5. Nine sharks; without the avoidable hits, three.
	 */
	@Test
	public void sixteenOhThreeKillFour() throws IOException
	{
		final AvoidableLogReplay.Kill kill = kills().get(0);
		classes(kill, 122, 30, 6, 26, 30, 49);
		assertEquals(133.5, kill.avoidable, 1e-9);
		replay(kill, 27, 9, 9, 3, 29, AvoidableEating.LINE_THIS_KILL, 18.0);
	}

	/** 16:03 kill 8: the gaze and an axe on one tick (6 and 29), told apart by size. Two sharks: the default line. */
	@Test
	public void sixteenOhThreeKillEight() throws IOException
	{
		final AvoidableLogReplay.Kill kill = kills().get(1);
		classes(kill, 81, 0, 6, 29, 21, 0);
		assertEquals(56.0, kill.avoidable, 1e-9);
		replay(kill, 3, 2, 3, 0, 39, AvoidableEating.LINE_DEFAULT, 3.0);
	}

	/** 17:03 kill 4: axes and their bleed only. Without them no food at all, so all 9 eating ticks are theirs. */
	@Test
	public void seventeenOhThreeKillFour() throws IOException
	{
		final AvoidableLogReplay.Kill kill = kills().get(2);
		classes(kill, 102, 0, 0, 40, 30, 0);
		assertEquals(70.0, kill.avoidable, 1e-9);
		replay(kill, 9, 3, 3, 0, 29, AvoidableEating.LINE_THIS_KILL, 9.0);
	}

	/** 17:03 kill 6: chip only, two sharks, none of it avoidable. */
	@Test
	public void seventeenOhThreeKillSix() throws IOException
	{
		final AvoidableLogReplay.Kill kill = kills().get(3);
		classes(kill, 144, 0, 0, 0, 0, 0);
		assertEquals(0.0, kill.avoidable, 1e-9);
		replay(kill, 3, 2, 4, 4, 39, AvoidableEating.LINE_DEFAULT, 0.0);
	}

	/** 17:03 kill 9: a late prayer, 20 under Protect from Melee where it lets 10 through at most; ¾ of it avoidable. */
	@Test
	public void seventeenOhThreeKillNine() throws IOException
	{
		final AvoidableLogReplay.Kill kill = kills().get(4);
		classes(kill, 100, 20, 0, 0, 0, 0);
		assertEquals(15.0, kill.avoidable, 1e-9);
		replay(kill, 3, 2, 3, 3, 39, AvoidableEating.LINE_DEFAULT, 0.0);
	}
}
