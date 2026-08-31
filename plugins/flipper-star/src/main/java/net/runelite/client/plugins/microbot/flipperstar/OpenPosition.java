package net.runelite.client.plugins.microbot.flipperstar;

import lombok.Getter;

/**
 * One open position, parsed from GE Star V2's GeStarPortfolio.getOpenPositionsJson()
 * (called reflectively via GeStarBridge - see that class's javadoc for why the JSON-string
 * cross-plugin pattern, rather than passing CostBasisEntry directly, which can't cross the
 * classloader boundary). Field names match the JSON keys GeStarPortfolio emits exactly
 * (both sides are plain camelCase Java-style names here, unlike Candidate's snake_case
 * Python fields, so Gson matches them without needing @SerializedName).
 */
@Getter
public class OpenPosition {
    private int itemId;
    private String itemName;
    private int quantityHeld;
    private int averageCost;
    private long purchaseTimestampMillis;
}
