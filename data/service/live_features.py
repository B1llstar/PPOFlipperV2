"""
Computes live feature vectors matching (as closely as serving-time data allows)
the 13 features train_model.py's model was trained on, from the OSRS Wiki's bulk
/latest, /1h, /6h, /24h endpoints - 4 HTTP calls total regardless of how many
items get scored, not one call per item (see wiki_client.py's docstring in
pipeline/ for why looping per item is avoided).

This is necessarily an approximation of the training features, not an exact
match - see the docstring on each function below and data/README.md's "Scoring
service" section for what's approximated and why. The three biggest-importance
features (spread_pct, momentum_1h, momentum_6h - 71% of the trained model's
feature importance combined) are close analogs of their training-time
definitions; volatility (11% combined importance) is a real but different proxy
signal, not the same rolling-std computation training used.
"""

from dataclasses import dataclass

import httpx

from wiki_client import get_with_retry


@dataclass
class LiveWindow:
    avg_high_price: float | None
    high_price_volume: int
    avg_low_price: float | None
    low_price_volume: int

    @property
    def mid_price(self) -> float | None:
        if self.avg_high_price is None or self.avg_low_price is None:
            return None
        return (self.avg_high_price + self.avg_low_price) / 2

    @property
    def total_volume(self) -> int:
        return self.high_price_volume + self.low_price_volume


def fetch_all_windows(client: httpx.Client) -> dict[str, dict[int, LiveWindow]]:
    """One bulk call each to /latest, /1h, /6h, /24h - every item's data for
    that window in a single response, matching the wiki API's efficient bulk
    shape (see pipeline/wiki_client.py's docstring)."""
    windows: dict[str, dict[int, LiveWindow]] = {}

    latest_payload = get_with_retry(client, "/latest")
    windows["latest"] = {
        int(item_id): LiveWindow(
            avg_high_price=candle.get("high"),
            high_price_volume=1 if candle.get("high") is not None else 0,
            avg_low_price=candle.get("low"),
            low_price_volume=1 if candle.get("low") is not None else 0,
        )
        for item_id, candle in latest_payload.get("data", {}).items()
    }

    for window_name in ("1h", "6h", "24h"):
        payload = get_with_retry(client, f"/{window_name}")
        windows[window_name] = {
            int(item_id): LiveWindow(
                avg_high_price=candle.get("avgHighPrice"),
                high_price_volume=candle.get("highPriceVolume", 0) or 0,
                avg_low_price=candle.get("avgLowPrice"),
                low_price_volume=candle.get("lowPriceVolume", 0) or 0,
            )
            for item_id, candle in payload.get("data", {}).items()
        }

    return windows


@dataclass
class ItemFeatures:
    item_id: int
    features: dict[str, float]
    current_buy_price: float  # latest.avg_low_price - what a buy order would need to offer
    current_sell_price: float  # latest.avg_high_price - what a sell order would need to offer


def compute_live_features(item_id: int, windows: dict[str, dict[int, LiveWindow]]) -> ItemFeatures | None:
    """Builds one item's feature vector from the four fetched windows. Returns
    None if the item doesn't have enough live data to compute a meaningful
    vector (no current latest price, or missing an entire window) - the caller
    should skip such items rather than score them on incomplete/imputed data.
    """
    latest = windows["latest"].get(item_id)
    w1h = windows["1h"].get(item_id)
    w6h = windows["6h"].get(item_id)
    w24h = windows["24h"].get(item_id)

    if latest is None or w1h is None or w6h is None or w24h is None:
        return None
    if latest.mid_price is None or w1h.mid_price is None or w6h.mid_price is None or w24h.mid_price is None:
        return None
    if latest.avg_low_price is None or latest.avg_low_price <= 0:
        return None

    # spread_pct: training defined this from a single 5-minute block's own
    # high/low - /latest's high/low (the two most recent individual trades)
    # is the direct live analog, not one of the aggregated windows.
    spread_pct = (latest.avg_high_price - latest.avg_low_price) / latest.avg_low_price

    # momentum_Nh: training was pct_change over a rolling mid-price series
    # (price now vs price N hours ago, at a point in time). The live analog
    # compares the current instantaneous mid-price to each window's *average*
    # mid-price over that lookback - not identical (average vs point-in-time),
    # but the same underlying signal: is price higher or lower than it
    # recently has been.
    def momentum(window: LiveWindow) -> float:
        return (latest.mid_price - window.mid_price) / window.mid_price

    # volatility_Nh: training used a rolling standard deviation over many
    # 5-minute candles within the window - always >= 0 by construction (a std
    # dev can't be negative). The bulk endpoints only give one aggregated
    # avgHigh/avgLow per window, not a series, so this is approximated as that
    # window's own high/low spread relative to its mid-price - a real but
    # different signal (dispersion within the window's trades, not variance
    # of the price level over time). See this module's docstring.
    #
    # abs() matters here, not just style: the wiki's avgHighPrice/avgLowPrice
    # for a window are averages of separately-timed high and low trades, and
    # can legitimately cross (avgHigh < avgLow) when trade timing/volume
    # shifts within the window - confirmed on real live data (item 6289,
    # Snakeskin, 6h window: avgHigh=12 < avgLow=15). Without abs(), that
    # produces a negative "volatility," a value shape the model never saw in
    # training and has no learned response to.
    def volatility_proxy(window: LiveWindow) -> float:
        if window.avg_high_price is None or window.avg_low_price is None or window.mid_price in (None, 0):
            return 0.0
        return abs(window.avg_high_price - window.avg_low_price) / window.mid_price

    features = {
        "spread_pct": spread_pct,
        "volatility_1h": volatility_proxy(w1h),
        "mean_price_1h": w1h.mid_price,
        "volume_1h": w1h.total_volume,
        "momentum_1h": momentum(w1h),
        "volatility_6h": volatility_proxy(w6h),
        "mean_price_6h": w6h.mid_price,
        "volume_6h": w6h.total_volume,
        "momentum_6h": momentum(w6h),
        "volatility_24h": volatility_proxy(w24h),
        "mean_price_24h": w24h.mid_price,
        "volume_24h": w24h.total_volume,
        "momentum_24h": momentum(w24h),
    }

    return ItemFeatures(
        item_id=item_id,
        features=features,
        current_buy_price=latest.avg_low_price,
        current_sell_price=latest.avg_high_price,
    )
