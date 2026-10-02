#!/usr/bin/env python3
"""Exercise the app's actual route/stop queries against its bundled catalogue."""
import json
import re
import sqlite3
from pathlib import Path
root = Path(__file__).resolve().parent.parent
source = (root / "app/src/main/java/info/plateaukao/transportation/Catalogue.java").read_text()
def statement(name):
    value = source.split("String " + name + " =", 1)[1].split(";", 1)[0]
    return "".join(json.loads('"' + x + '"') for x in re.findall(r'"((?:\\.|[^"\\])*)"', value))
def pattern(query):
    return "%" + query.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
with sqlite3.connect(root / "app/src/main/assets/catalogue.db") as db:
    def routes(query): return db.execute(statement("sql"), (pattern(query), pattern(query))).fetchall()
    def stops(query): return [r[0] for r in db.execute(statement("stopSearchSql"), (pattern(query),))]
    def through(name): return db.execute(statement("stopRoutesSql"), (name,)).fetchall()
    assert len(routes("")) == 1051
    assert any(r[0] == 200321 for r in routes("綠7"))
    assert not routes("順安街"), "Route search must not return stop matches"
    assert "順安街" in stops("順安街")
    assert len(stops("民生")) == len(set(stops("民生"))), "Duplicate stop names"
    matched = through("順安街")
    assert any(r[0] == 200321 and r[4:] == ("往黎明清境", 3210501) for r in matched)
    assert not any(r[0] == 200321 and r[4] == "往捷運大坪林站" for r in matched), "Includes direction that skips this stop"
    both = [r for r in through("捷運辛亥站") if r[0] == 110747]
    assert len({r[3] for r in both}) == 2, "Lost matching direction"
    exact = through("民生社區活動中心")
    expected = db.execute("SELECT DISTINCT route_key,path_id,stop_id FROM stops WHERE stop_name=?", ("民生社區活動中心",)).fetchall()
    assert exact and {(r[0], r[3], r[5]) for r in exact} == set(expected), "Stop detail omitted route/direction"
    assert not through("民生"), "Stop details must use exact stop name"
    for literal in ("%", "_", "' OR 1=1 --", "\\"):
        assert not routes(literal) and not stops(literal), "Input treated as SQL/wildcard"
print("PASS: separate route/stop search, exact stop routes, matching directions, literal input")
