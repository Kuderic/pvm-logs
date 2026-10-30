package com.pvmlogs;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * T-110: the game's own kill messages, read for R1. Patterns written for the
 * plugin, with RuneLite's ChatCommandsPlugin (BSD) as the reference for what
 * the game sends: {@code Your Araxxor kill count is: 12.} and
 * {@code Fight duration: 1:23.40. Personal best: 1:10.20} or
 * {@code Fight duration: 1:10.20 (new personal best).} Read with the colour
 * tags already removed. Pure.
 */
final class GameMessages
{
	private GameMessages()
	{
	}

	private static final Pattern KILL_COUNT = Pattern.compile(
		"^Your (?:completion count for |subdued |completed )?(.+?) (?:(?:(?:kill|harvest|lap|completion|success) )?(?:count|done) )?is: ([0-9,]+)\\.?$");

	private static final Pattern DURATION = Pattern.compile(
		"^(?:Fight |Lap |Challenge |Corrupted challenge |Room )?(?:duration|completion time): ((?:[0-9]+:)?[0-9]+:[0-9]{2}(?:\\.[0-9]{1,2})?)");

	/** The kill count a message gives, or -1 when it isn't one. */
	static int killCount(String message)
	{
		final Matcher m = message == null ? null : KILL_COUNT.matcher(message.trim());
		if (m == null || !m.find())
		{
			return -1;
		}
		try
		{
			return Integer.parseInt(m.group(2).replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	/** The boss a kill count message names, or null. */
	static String killCountBoss(String message)
	{
		final Matcher m = message == null ? null : KILL_COUNT.matcher(message.trim());
		return m != null && m.find() ? m.group(1) : null;
	}

	/** The fight's duration a message gives, in game ticks (0.6 s each, rounded), or -1 when it isn't one. */
	static int durationTicks(String message)
	{
		final Matcher m = message == null ? null : DURATION.matcher(message.trim());
		if (m == null || !m.find())
		{
			return -1;
		}
		final double seconds = seconds(m.group(1));
		return seconds < 0 ? -1 : (int) Math.round(seconds / 0.6);
	}

	/** {@code [h:]m:ss[.cc]} as seconds, or -1. */
	static double seconds(String time)
	{
		try
		{
			final String[] parts = time.split(":");
			double total = 0;
			for (int i = 0; i < parts.length; i++)
			{
				total = total * 60 + (i == parts.length - 1 ? Double.parseDouble(parts[i]) : Integer.parseInt(parts[i]));
			}
			return total;
		}
		catch (RuntimeException e)
		{
			return -1;
		}
	}
}
