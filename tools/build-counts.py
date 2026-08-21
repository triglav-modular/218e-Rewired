#!/usr/bin/env python3
"""How many people built the firmware, and with what options.

Analytics Engine has no data browser in the dashboard - the dashboard lists
datasets and nothing more - so reading the counts means the SQL API.  This
runs the queries worth running.

    export CF_API_TOKEN=...          # Account Analytics: Read
    python3 tools/build-counts.py
    python3 tools/build-counts.py --days 7
    python3 tools/build-counts.py --sql "SELECT count() FROM builds"

The token is read from the environment and never written anywhere.  Make one
at https://dash.cloudflare.com/profile/api-tokens - see "Counting builds" in
docs/BUILD.md.
"""
import argparse
import json
import os
import sys
import urllib.error
import urllib.request

ACCOUNT = "486d4daefa1b547fc8d9620af5afb0ae"
URL = f"https://api.cloudflare.com/client/v4/accounts/{ACCOUNT}/analytics_engine/sql"

# A real download reports mac or win.  Anything else reached the endpoint some
# other way - it is public - and is not a build, so every count excludes it.
REAL = "blob1 IN ('mac','win')"


def query(sql: str, token: str) -> list[dict]:
    request = urllib.request.Request(
        URL, data=sql.encode(), method="POST",
        headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return json.loads(response.read()).get("data", [])
    except urllib.error.HTTPError as e:
        body = e.read().decode(errors="replace")[:400]
        if e.code in (401, 403):
            sys.exit(f"  the token was refused ({e.code}).  It needs the\n"
                     f"  Account Analytics: Read permission on this account.\n"
                     f"  {body}")
        sys.exit(f"  the query failed ({e.code}): {body}")
    except urllib.error.URLError as e:
        sys.exit(f"  could not reach the SQL API: {e.reason}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--days", type=int, default=30)
    parser.add_argument("--sql", help="run this instead of the usual report")
    parser.add_argument("--raw", action="store_true",
                        help="every row, filtered by nothing - use this to "
                             "find out whether anything is arriving at all")
    args = parser.parse_args()

    token = os.environ.get("CF_API_TOKEN")
    if not token:
        sys.exit("  set CF_API_TOKEN first - see docs/BUILD.md, 'Counting builds'")

    window = f"timestamp > now() - INTERVAL '{args.days}' DAY"

    if args.sql:
        for row in query(args.sql, token):
            print("  " + json.dumps(row))
        return

    if args.raw:
        # No window and no filter: the question here is whether the dataset
        # has anything in it, not what the numbers say.
        rows = query("SELECT count() AS n FROM builds", token)
        n = int(rows[0]["n"]) if rows else 0
        print(f"\n  {n} row(s) in the dataset, all time, nothing excluded")
        if not n:
            print("\n  Nothing has ever been written.  Either no download has")
            print("  happened since the worker was deployed, or the beacon is")
            print("  not reaching it.  Check that a GET on")
            print("  https://triglavmodular.hu/mods/218e-Rewired/beacon")
            print("  answers 405 - a 404 means the deployed worker is not"
                  " this one.\n")
            return
        print()
        for row in query("SELECT timestamp, blob1 AS platform, "
                         "blob2 AS version, blob3 AS volts, double1 AS arp, "
                         "double2 AS knobs, double3 AS pressure, "
                         "double4 AS portamento, double5 AS tunings, "
                         "double6 AS calibration FROM builds "
                         "ORDER BY timestamp DESC LIMIT 20", token):
            print("  " + json.dumps(row))
        print()
        return

    rows = query(f"SELECT count() AS builds FROM builds "
                 f"WHERE {window} AND {REAL}", token)
    total = int(rows[0]["builds"]) if rows else 0
    print(f"\n  {total} build{'' if total == 1 else 's'} in the last "
          f"{args.days} days\n")
    if not total:
        print("  Nothing yet.  A build only counts when someone downloads,")
        print("  and only from the page served at triglavmodular.hu.\n")
        return

    print("  by platform")
    for row in query(f"SELECT blob1 AS platform, count() AS n FROM builds "
                     f"WHERE {window} AND {REAL} GROUP BY platform "
                     f"ORDER BY n DESC", token):
        print(f"    {row['platform']:<8} {row['n']}")

    print("\n  by firmware version")
    for row in query(f"SELECT blob2 AS version, count() AS n FROM builds "
                     f"WHERE {window} AND {REAL} GROUP BY version "
                     f"ORDER BY n DESC", token):
        print(f"    {row['version']:<8} {row['n']}")

    print("\n  by pitch scaling")
    for row in query(f"SELECT blob3 AS volts, count() AS n FROM builds "
                     f"WHERE {window} AND {REAL} GROUP BY volts "
                     f"ORDER BY n DESC", token):
        print(f"    {row['volts']:<8} V/oct  {row['n']}")

    print("\n  options chosen")
    rows = query(
        f"SELECT sum(double1) AS arp, sum(double2) AS knobs, "
        f"sum(double3) AS pressure, sum(double4) AS portamento, "
        f"sum(double6) AS calibration, sum(double5) AS tuning_slots "
        f"FROM builds WHERE {window} AND {REAL}", token)
    if rows:
        r = rows[0]
        for label, key in (("latching arpeggiator", "arp"),
                           ("knobs 1-4 remapped", "knobs"),
                           ("pressure rewired", "pressure"),
                           ("pressure portamento", "portamento"),
                           ("per-note calibration", "calibration")):
            n = int(float(r[key]))
            print(f"    {label:<22} {n:>4}  ({100 * n // total}%)")
        print(f"    {'tuning slots filled':<22} {int(float(r['tuning_slots'])):>4}"
              f"  across all builds")
    print()


if __name__ == "__main__":
    main()
