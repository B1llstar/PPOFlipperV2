package net.runelite.client.plugins.microbot.flipperstar;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

/**
 * One ranked flip candidate from the scoring service's GET /candidates response
 * (data/service/main.py's Candidate pydantic model - field names/types must stay in
 * lockstep with that class).
 */
@Getter
public class Candidate {
    @SerializedName("item_id")
    private int itemId;

    @SerializedName("item_name")
    private String itemName;

    @SerializedName("predicted_margin_pct")
    private double predictedMarginPct;

    @SerializedName("current_buy_price")
    private double currentBuyPrice;

    @SerializedName("current_sell_price")
    private double currentSellPrice;

    @SerializedName("absolute_margin_gp")
    private double absoluteMarginGp;

    @SerializedName("ge_limit")
    private Integer geLimit;

    @SerializedName("max_position_value_gp")
    private Double maxPositionValueGp;

    @Override
    public String toString() {
        return String.format("%s (%.1f%% margin, %.1f gp/unit, limit %s)",
            itemName, predictedMarginPct * 100, absoluteMarginGp, geLimit != null ? geLimit : "?");
    }
}
