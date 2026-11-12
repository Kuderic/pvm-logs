package com.pvmlogs;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// One entry of the combat tab: the pairing of an AttackType with a CombatStyle, identified by the value the COM_MODE
// varplayer takes while it is selected. Ported from the LlemonDuck dps-calculator (BSD-2), (c) Paul Norton.
@Getter
@RequiredArgsConstructor
class AttackStyle
{
	/** Value of {@code VarPlayerID.COM_MODE} while this option is selected. */
	private final int varpValue;
	private final String displayName;
	private final AttackType attackType;
	private final CombatStyle combatStyle;

	/**
	 * Invisible boost to the attack roll's effective level: melee and ranged +3
	 * on accurate and +1 on controlled, magic +2 on accurate.
	 */
	/**
	 * T-221: the type and stance the calc scores with, and the combat tab's name: {@code stab/aggressive (Lunge)}. For the
	 * {@code expected vs} and {@code ATTACK} lines, which didn't say (Eric's 11-11 Kephri: "i was on stab").
	 */
	String label()
	{
		final String type = attackType == null ? "?" : attackType.name().toLowerCase(java.util.Locale.ENGLISH);
		final String stance = combatStyle == null ? "?" : combatStyle.name().toLowerCase(java.util.Locale.ENGLISH);
		return type + "/" + stance + (displayName == null || displayName.isEmpty() ? "" : " (" + displayName + ")");
	}

	int attackLevelBonus()
	{
		if (attackType == AttackType.MAGIC)
		{
			return combatStyle == CombatStyle.ACCURATE ? 2 : 0;
		}
		if (combatStyle == CombatStyle.ACCURATE)
		{
			return 3;
		}
		return combatStyle == CombatStyle.CONTROLLED ? 1 : 0;
	}

	/**
	 * Invisible boost to the max hit's effective strength level: melee +3 on
	 * aggressive and +1 on controlled, ranged +3 on accurate.
	 */
	int strengthLevelBonus()
	{
		if (attackType == AttackType.RANGED)
		{
			return combatStyle == CombatStyle.ACCURATE ? 3 : 0;
		}
		if (!attackType.isMelee())
		{
			return 0;
		}
		if (combatStyle == CombatStyle.AGGRESSIVE)
		{
			return 3;
		}
		return combatStyle == CombatStyle.CONTROLLED ? 1 : 0;
	}
}
