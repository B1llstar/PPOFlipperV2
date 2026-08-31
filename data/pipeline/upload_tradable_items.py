"""
Fetches the official OSRS Grand Exchange "Most Traded Items" (top 100) list from
secure.runescape.com and uploads it to Firestore's tradableItems collection, one
document per item.

Why this list matters: it's Jagex's own live ranking of the highest-volume,
consistently-liquid items on the GE - runes, ammo, logs, ores, bars. Verified
against real data this session: an item that showed up as a live scoring-service
candidate (Yanillian seed) but isn't on this list turned out to have zero rows in
the buy-side model's training data at all (see build_features.py's --min-rows
liquidity filter) - its "opportunity" was pure model extrapolation, not anything
learned. This list is the intended restriction for both training data (only train
on these items' history) and live trading (only ever queue orders for these
items), replacing the current broad-universe-plus-liquidity-filter approach.

Firestore is used here (not just a local file) so the list is a durable, shared
record - "for posterity," and so both the Python pipeline and the Java plugins
can read the same source of truth without needing to re-scrape this page or ship
a static list that goes stale as Jagex's own top-100 rankings shift over time.

tradableItems is currently admin-only (no firestore.rules entry) - unlike orders,
nothing outside server-side scripts/Cloud Functions reads or writes it yet.

Usage:
    python upload_tradable_items.py
    python upload_tradable_items.py --service-account-path ../../ppoflipperopus-firebase-adminsdk-fbsvc-4e78117dde.json
"""

import argparse
import pathlib
import re
from datetime import datetime, timezone

import httpx
from google.cloud import firestore

TOP100_URL = "https://secure.runescape.com/m=itemdb_oldschool/top100"
# Deliberately generic - no personal/project-identifying terms (see
# GeStarWikiPriceClient.java's User-Agent for the same convention on the Java side).
USER_AGENT = "OSRS-GE-Trading-Client/1.0 (contact: via GitHub)"

DEFAULT_SERVICE_ACCOUNT_PATH = pathlib.Path(__file__).parent.parent.parent / "ppoflipperopus-firebase-adminsdk-fbsvc-4e78117dde.json"

COLLECTION = "tradableItems"

# Matches each item row's link+image block on the top100 page - captures the item id
# from the viewitem?obj= query param and the display name from the image's alt text.
# Verified against the page's real markup (not guessed): a row looks like
# `viewitem?obj=1513" class='table-item-link'>...alt="Magic logs"`.
ITEM_PATTERN = re.compile(r"viewitem\?obj=(\d+)\" class='table-item-link'>.*?alt=\"([^\"]+)\"", re.DOTALL)


def fetch_top100() -> list[tuple[int, str]]:
    """Returns [(item_id, item_name), ...] in the page's own rank order (1st = most
    traded), deduplicated - the page can reference the same item's icon more than
    once in surrounding markup, only the first (highest-ranked) occurrence per item
    id is kept."""
    with httpx.Client(headers={"User-Agent": USER_AGENT}, timeout=15.0) as client:
        response = client.get(TOP100_URL)
        response.raise_for_status()
        html = response.text

    seen: set[int] = set()
    items: list[tuple[int, str]] = []
    for match in ITEM_PATTERN.finditer(html):
        item_id = int(match.group(1))
        name = match.group(2)
        if item_id in seen:
            continue
        seen.add(item_id)
        items.append((item_id, name))

    if len(items) != 100:
        raise RuntimeError(
            f"Expected exactly 100 items from {TOP100_URL}, parsed {len(items)} - "
            f"the page's markup may have changed, check ITEM_PATTERN against a fresh fetch"
        )
    return items


def upload_to_firestore(items: list[tuple[int, str]], service_account_path: pathlib.Path) -> None:
    if not service_account_path.exists():
        raise FileNotFoundError(
            f"{service_account_path} not found - pass --service-account-path if it's elsewhere"
        )

    client = firestore.Client.from_service_account_json(str(service_account_path))
    collection = client.collection(COLLECTION)

    fetched_at = datetime.now(timezone.utc).isoformat()
    batch = client.batch()
    for rank, (item_id, name) in enumerate(items, start=1):
        doc_ref = collection.document(str(item_id))
        batch.set(doc_ref, {
            "itemId": item_id,
            "name": name,
            "rank": rank,
            "source": "https://secure.runescape.com/m=itemdb_oldschool/top100",
            "fetchedAt": fetched_at,
        })
    batch.commit()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--service-account-path", type=pathlib.Path, default=DEFAULT_SERVICE_ACCOUNT_PATH,
                         help=f"Path to the Firebase Admin SDK service account JSON (default: {DEFAULT_SERVICE_ACCOUNT_PATH})")
    parser.add_argument("--dry-run", action="store_true",
                         help="Fetch and print the list without uploading to Firestore")
    args = parser.parse_args()

    print(f"Fetching {TOP100_URL}...")
    items = fetch_top100()
    print(f"Parsed {len(items)} items")
    for rank, (item_id, name) in enumerate(items, start=1):
        print(f"{rank:3d}. {name} (id={item_id})")

    if args.dry_run:
        print("\n--dry-run: not uploading to Firestore")
        return

    print(f"\nUploading to Firestore collection '{COLLECTION}'...")
    upload_to_firestore(items, args.service_account_path)
    print(f"Uploaded {len(items)} documents to '{COLLECTION}'")


if __name__ == "__main__":
    main()
