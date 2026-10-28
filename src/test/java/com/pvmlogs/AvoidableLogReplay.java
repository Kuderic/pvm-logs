package com.pvmlogs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a debug log back through {@link AttackClassifier} and
 * {@link AvoidableEating} the way the plugin feeds them (T-041), so a real kill
 * can be checked against T-039's harness ({@code claude/specs/avoidable-harness/avoidable.py}).
 *
 * <p>A log has no line per tick, so the hitpoints are read where the log has them:
 * each TAKEN line's {@code hp}, read before the splat, less that tick's splats.
 * That is the reading the plugin takes at the end of the tick, when nothing else
 * happened in it after the splat; the harness reads them the same way.
 */
final class AvoidableLogReplay
{
	static final Pattern TAKEN = Pattern.compile(
		"\\[(\\d+)] TAKEN (\\d+) type (\\d+) [\\w ]+? \\([^)]*\\), protect ([^,]*), hp (\\d+), from .*?; target (.*)$");
	private static final Pattern ACTION = Pattern.compile(
		"(anim|proj) (-?\\d+)(?: from [^,]*?)?(?: at [^,]*?)? t([+-]\\d+)(?: lands t([+-]\\d+))?");
	private static final Pattern HEAL = Pattern.compile("\\[(\\d+)] splat (\\d+) heal \\(type 6[^)]*\\) on Vardorvis");
	private static final Pattern EAT = Pattern.compile("\\[\\d+] EAT .+? \\(delay (\\d+)\\) on tick (\\d+)");
	private static final Pattern TICK = Pattern.compile("^\\[(\\d+)] ");

	/** One kill, read back. */
	static final class Kill
	{
		Map<String, Integer> byClass;
		double avoidable;
		int eatingTicks;
		AvoidableEating.Result result;
		final List<AttackClassifier.Named> named = new ArrayList<>();
	}

	private AvoidableLogReplay()
	{
	}

	static List<Kill> read(Reader in, BossRules.AttacksOnPlayer rules, int maxHp) throws IOException
	{
		final List<String> lines = new ArrayList<>();
		final List<Integer> eats = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(in))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				lines.add(line);
				// An eat is logged once it settles, which can be after the fight's END line: kept by the tick it was eaten.
				final Matcher e = EAT.matcher(line);
				if (e.find() && Integer.parseInt(e.group(1)) > 0)
				{
					eats.add(Integer.parseInt(e.group(2)));
				}
			}
		}
		final List<Kill> kills = new ArrayList<>();
		AttackClassifier names = null;
		AvoidableEating replay = null;
		Kill kill = null;
		int start = 0;
		int sampleTick = Integer.MIN_VALUE;
		int sampleHp = 0;
		int sampleDamage = 0;
		int lastTick = Integer.MIN_VALUE;
		for (String line : lines)
		{
			final Matcher tm = TICK.matcher(line);
			if (!tm.find())
			{
				continue;
			}
			final int tick = Integer.parseInt(tm.group(1));
			if (line.contains(" fight START Vardorvis"))
			{
				kill = new Kill();
				names = new AttackClassifier(rules);
				replay = null;
				start = tick;
				sampleTick = Integer.MIN_VALUE;
				lastTick = tick;
				continue;
			}
			if (kill == null)
			{
				continue;
			}
			if (tick > lastTick)
			{
				// The tick before is over: its reading, then what is due to be named.
				if (sampleTick != Integer.MIN_VALUE && replay != null)
				{
					replay.hitpoints(sampleTick, sampleHp - sampleDamage);
					sampleTick = Integer.MIN_VALUE;
				}
				for (AttackClassifier.Named n : names.settle(tick - 1))
				{
					kill.named.add(n);
					replay.avoidable(n.tick, n.avoidable);
				}
				lastTick = tick;
			}
			if (line.contains(" fight END Vardorvis"))
			{
				if (sampleTick != Integer.MIN_VALUE && replay != null)
				{
					replay.hitpoints(sampleTick, sampleHp - sampleDamage);
				}
				for (AttackClassifier.Named n : names.settleAll())
				{
					kill.named.add(n);
					replay.avoidable(n.tick, n.avoidable);
				}
				for (int eat : eats)
				{
					if (replay != null && eat >= start && eat <= tick)
					{
						replay.eat(eat);
					}
				}
				kill.byClass = new LinkedHashMap<>(names.damageByClass());
				kill.avoidable = names.avoidable();
				kill.result = replay == null ? null : replay.finish(maxHp, kill.eatingTicks, () -> -1);
				kills.add(kill);
				kill = null;
				continue;
			}
			final Matcher taken = TAKEN.matcher(line);
			if (taken.find() && !line.contains("(no fight)") && !line.contains("TAKEN TOTAL"))
			{
				final int amount = Integer.parseInt(taken.group(2));
				final int hp = Integer.parseInt(taken.group(5));
				if (replay == null)
				{
					replay = new AvoidableEating(tick - 1, hp);
				}
				if (sampleTick != tick)
				{
					sampleTick = tick;
					sampleHp = hp;
					sampleDamage = 0;
				}
				sampleDamage += amount;
				final String protect = taken.group(4);
				names.onHit(tick, amount, Integer.parseInt(taken.group(3)), "none".equals(protect) ? null : protect,
					actions(taken.group(6)));
				replay.damage(tick, amount);
				continue;
			}
			final Matcher heal = HEAL.matcher(line);
			if (heal.find())
			{
				names.onBossHeal(tick, Integer.parseInt(heal.group(2)));
				continue;
			}
			if (line.contains("tick LOST (eating") && !line.contains("NOT COUNTED"))
			{
				kill.eatingTicks++;
			}
		}
		return kills;
	}

	/** The TAKEN line's target part as {@link TargetActions.Action}s. */
	static List<TargetActions.Action> actions(String target)
	{
		final List<TargetActions.Action> out = new ArrayList<>();
		final Matcher m = ACTION.matcher(target);
		while (m.find())
		{
			final int id = Integer.parseInt(m.group(2));
			final int rel = Integer.parseInt(m.group(3));
			out.add("proj".equals(m.group(1))
				? TargetActions.Action.projectile(id, rel, m.group(4) == null ? Integer.MIN_VALUE : Integer.parseInt(m.group(4)))
				: TargetActions.Action.animation(id, rel));
		}
		return out;
	}
}
