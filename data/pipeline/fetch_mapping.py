"""
Pulls the OSRS Wiki's item mapping (id -> name, GE limit, members, alch values) and
writes it to data/raw/item_mapping.parquet. One-shot, small (~4000 rows) - this is
reference metadata joined against the bulk price history later, not something that
needs the resumable/parallel machinery fetch_5m_history.py has.
"""

import pathlib

import pandas as pd

from wiki_client import get_with_retry, make_client

OUTPUT_PATH = pathlib.Path(__file__).parent.parent / "raw" / "item_mapping.parquet"


def main() -> None:
    with make_client() as client:
        mapping = get_with_retry(client, "/mapping")

    df = pd.DataFrame(mapping)
    df = df.rename(columns={"id": "item_id"})

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    df.to_parquet(OUTPUT_PATH, index=False)
    print(f"Wrote {len(df)} items to {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
