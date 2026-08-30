"""
Thin client for the OSRS Wiki real-time prices API (prices.runescape.wiki).

The wiki asks that callers set a descriptive User-Agent (default agents like
curl/python-requests/Java get blocked) and warns against heavy per-item looping -
see https://oldschool.runescape.wiki/w/RuneScape:Real-time_Prices. This pipeline
respects both: a real contact-identifying User-Agent, and bulk per-timestamp pulls
(one request returns every item's candle for that 5-minute block) rather than
looping per item.
"""

import time

import httpx

BASE_URL = "https://prices.runescape.wiki/api/v1/osrs"
USER_AGENT = "BotStar-GEFlipper-DataPipeline/1.0 (contact: billborkowski7@gmail.com)"

MAX_RETRIES = 4
RETRY_BACKOFF_SECONDS = 2.0


def make_client(timeout: float = 15.0) -> httpx.Client:
    return httpx.Client(
        base_url=BASE_URL,
        headers={"User-Agent": USER_AGENT},
        timeout=timeout,
    )


def get_with_retry(client: httpx.Client, path: str, params: dict | None = None) -> dict:
    """GETs a path, retrying on transient errors (timeouts, 5xx, 429) with backoff.
    Raises on a non-retryable error (4xx other than 429) so bad requests fail loud
    instead of silently retrying something that will never succeed."""
    last_exc: Exception | None = None
    for attempt in range(MAX_RETRIES):
        try:
            response = client.get(path, params=params)
            if response.status_code == 429 or response.status_code >= 500:
                last_exc = httpx.HTTPStatusError(
                    f"HTTP {response.status_code}", request=response.request, response=response
                )
                time.sleep(RETRY_BACKOFF_SECONDS * (attempt + 1))
                continue
            response.raise_for_status()
            return response.json()
        except (httpx.TimeoutException, httpx.TransportError) as exc:
            last_exc = exc
            time.sleep(RETRY_BACKOFF_SECONDS * (attempt + 1))

    raise RuntimeError(f"Failed to fetch {path} after {MAX_RETRIES} attempts") from last_exc
