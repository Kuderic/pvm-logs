package com.pvmlogs;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PvmPerformancePluginTest
{
	public static void main(String[] args) throws Exception
	{
		// T-124's dev loopback: your own party update shown back as "You (echo)", so one client can test the Party card.
		// Only this launcher sets it; a hub build ignores it.
		System.setProperty(PartyShare.ECHO_PROPERTY, "true");
		ExternalPluginManager.loadBuiltin(PvmPerformancePlugin.class);
		RuneLite.main(args);
	}
}
