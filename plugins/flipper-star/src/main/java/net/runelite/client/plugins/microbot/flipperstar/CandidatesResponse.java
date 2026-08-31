package net.runelite.client.plugins.microbot.flipperstar;

import com.google.gson.annotations.SerializedName;
import lombok.Getter;

import java.util.List;

/** Matches data/service/main.py's CandidatesResponse pydantic model. */
@Getter
public class CandidatesResponse {
    private List<Candidate> candidates;

    @SerializedName("items_scored")
    private int itemsScored;

    @SerializedName("items_skipped_insufficient_data")
    private int itemsSkippedInsufficientData;

    @SerializedName("items_skipped_low_training_coverage")
    private int itemsSkippedLowTrainingCoverage;
}
