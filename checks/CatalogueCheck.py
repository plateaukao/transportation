#!/usr/bin/env python3
"""Exercise the app's actual search SQL against its bundled catalogue."""
import json
import re
import sqlite3
from pathlib import Path

root = Path(__file__).resolve().parent.parent
source = (root / "app/src/main/java/info/plateaukao/transportation/Catalogue.java").read_text()
statement = source.split("String sql =", 1)[1].split(";", 1)[0]
sql = "".join(json.loads('"' + value + '"') for value in re.findall(r'"((?:\\.|[^"\\])*)"', statement))
with sqlite3.connect(root / "app/src/main/assets/catalogue.db") as db:
    def search(query):
        escaped = query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        pattern = "%" + escaped + "%"
        args = (escaped, pattern, pattern, pattern)
        assert all(isinstance(arg, str) for arg in args), "Android rawQuery requires string bindings"
        rows = db.execute(sql, args).fetchall()
        assert len({row[0] for row in rows}) == len(rows), "Duplicate route results"
        return rows

    rows = search("")
    assert len(rows) == 1051 and all(row[3] is None for row in rows)
    assert any(row[0] == 200321 for row in search("綠7"))
    stop_statement = source.split("String stopSql =", 1)[1].split(";", 1)[0]
    stop_sql = "".join(json.loads('"' + value + '"') for value in re.findall(r'"((?:\\.|[^"\\])*)"', stop_statement))
    matched_ids = db.execute(stop_sql, ("%順安街%", "200321")).fetchall()
    assert any(row[1] == "往黎明清境" and row[2:] == (3210501, "順安街") for row in matched_ids)
    assert any(row[1] == "往捷運大坪林站" and row[2] is None for row in matched_ids), "Missing direction that skips the stop"
    both = db.execute(stop_sql, ("%捷運辛亥站%", "110747")).fetchall()
    assert len({row[0] for row in both if row[2] is not None}) == 2, "Lost a matching direction"
    rows = search("順安街")
    assert any(row[0] == 200321 and "順安街" in row[3] for row in rows)
    assert all("順安街" in row[3] for row in rows)
    for row in search("大坪林"):
        if row[3]:
            names = row[3].split(" · ")
            assert len(set(names)) == len(names), "Duplicate matched stops"
    for literal in ("%", "_", "' OR 1=1 --", "\\"):
        assert not search(literal), "Search treated literal input as SQL or a wildcard"
print("PASS: route and stop search, unique routes/stops, literal input, empty query")
