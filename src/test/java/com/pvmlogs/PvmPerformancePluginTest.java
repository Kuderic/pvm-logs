package com.pvmlogs;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PvmPerformancePluginTest
{
	public static void main(String[] args) throws Exception
	{
		// T-124's dev loopback: your own party update shown back as "You (echo)", so one client can test the Party card.
		// T-209: opt-in, since in a real party it shows your own damage twice: only with LUCKVSSKILL_PARTY_ECHO=true in the
		// environment (README "Developing"). Only this launcher can set it; a hub build ignores it.
		if (PartyShare.echoAsked(System.getenv(PartyShare.ECHO_ENV)))
		{
			System.setProperty(PartyShare.ECHO_PROPERTY, "true");
		}
		ExternalPluginManager.loadBuiltin(PvmPerformancePlugin.class);
		RuneLite.main(args);
	}
}
