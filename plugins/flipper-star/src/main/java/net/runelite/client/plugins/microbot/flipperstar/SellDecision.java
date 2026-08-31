package net.runelite.client.plugins.microbot.flipperstar;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

/**
 * One hold/sell decision from the scoring service's POST /should-sell response
 * (data/service/main.py's SellDecision pydantic model - field names/types must stay in
 * lockstep with that class).
 */
@Getter
public class SellDecision {
    @SerializedName("item_id")
    private int itemId;

    @SerializedName("item_name")
    private String itemName;

    @SerializedName("decision")
    private String decision;

    @SerializedName("predicted_further_return_pct")
    private double predictedFurtherReturnPct;

    @SerializedName("unrealized_pnl_pct")
    private double unrealizedPnlPct;

    @SerializedName("holding_duration_hours")
    private double holdingDurationHours;

    @SerializedName("current_sell_price")
    private double currentSellPrice;

    @SerializedName("sell_threshold_used")
    private double sellThresholdUsed;

    public boolean isSell() {
        return "SELL".equals(decision);
    }

    @Override
    public String toString() {
        return String.format("%s: %s (predicted further return %.1f%%, held %.1fh, unrealized P&L %.1f%%)",
            itemName, decision, predictedFurtherReturnPct * 100, holdingDurationHours, unrealizedPnlPct * 100);
    }
}
