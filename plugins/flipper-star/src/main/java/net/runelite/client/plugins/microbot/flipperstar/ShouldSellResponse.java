package net.runelite.client.plugins.microbot.flipperstar;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

import java.util.List;

/** Matches data/service/main.py's ShouldSellResponse pydantic model. */
@Getter
public class ShouldSellResponse {
    private List<SellDecision> decisions;

    @SerializedName("items_skipped_insufficient_data")
    private int itemsSkippedInsufficientData;
}
