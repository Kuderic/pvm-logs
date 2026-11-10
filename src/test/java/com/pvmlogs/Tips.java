package com.pvmlogs;

/** T-201: a tooltip's words as the player reads them: its HTML taken off, its breaks as spaces. For the tests. */
final class Tips
{
	private Tips()
	{
	}

	static String plain(String tip)
	{
		if (tip == null)
		{
			return null;
		}
		return tip.replace("<html>", "").replace("</html>", "").replace("<br>", " ").replace("&lt;", "<")
			.replace("&gt;", ">").replace("&amp;", "&");
	}
}
