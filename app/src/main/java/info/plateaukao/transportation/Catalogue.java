package info.plateaukao.transportation;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;

final class Catalogue implements AutoCloseable {
    static final class MatchedDirection {
        final String name;
        final Map<String, List<Integer>> stops = new LinkedHashMap<>();
        MatchedDirection(String name) { this.name = name; }
    }
    static final class Route {
        final int key;
        final String name, description, matchedStops;
        final List<MatchedDirection> matchedDirections = new ArrayList<>();
        Route(int key, String name, String description, String matchedStops) {
            this.key = key; this.name = name; this.description = description; this.matchedStops = matchedStops;
        }
        @Override public String toString() { return name + "\n" + description; }
    }
    private final SQLiteDatabase db;
    Catalogue(Context context) throws IOException {
        File file = new File(context.getFilesDir(), "catalogue-" + Transit.SOURCE_DATE + ".db");
        if (!file.exists()) {
            File temporary = new File(context.getFilesDir(), "catalogue.tmp");
            try (InputStream input = context.getAssets().open("catalogue.db"); OutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192]; int size;
                while ((size = input.read(buffer)) != -1) output.write(buffer, 0, size);
            }
            if (!temporary.renameTo(file)) throw new IOException("無法儲存離線路線資料");
        }
        db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
    }
    List<Route> search(String query, Set<String> favourites, boolean onlyFavourites) {
        String escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<Route> result = new ArrayList<>();
        String sql = "SELECT r.route_key,r.route_name,r.description,m.names FROM routes r LEFT JOIN "
            + "(SELECT route_key,group_concat(stop_name,' · ') AS names FROM "
            + "(SELECT DISTINCT route_key,stop_name FROM stops WHERE ? <> '' AND stop_name LIKE ? ESCAPE '\\') "
            + "GROUP BY route_key) m ON m.route_key=r.route_key WHERE "
            + "(r.route_name LIKE ? ESCAPE '\\' OR r.description LIKE ? ESCAPE '\\' OR m.route_key IS NOT NULL) "
            + "ORDER BY r.sequence,r.route_name";
        String pattern = "%" + escaped + "%";
        try (Cursor cursor = db.rawQuery(sql, new String[]{escaped, pattern, pattern, pattern})) {
            while (cursor.moveToNext()) {
                int key = cursor.getInt(0);
                if (onlyFavourites && !favourites.contains(String.valueOf(key))) continue;
                Route route = new Route(key, cursor.getString(1), cursor.getString(2), cursor.getString(3));
                if (route.matchedStops != null) {
                    String stopSql = "SELECT DISTINCT p.path_id,p.path_name,s.stop_id,s.stop_name FROM paths p LEFT JOIN stops s "
                        + "ON s.route_key=p.route_key AND s.path_id=p.path_id AND s.stop_name LIKE ? ESCAPE '\\' "
                        + "WHERE p.route_key=? ORDER BY p.path_id,s.sequence";
                    try (Cursor stops = db.rawQuery(stopSql, new String[]{pattern, String.valueOf(key)})) {
                        int path = -1;
                        MatchedDirection direction = null;
                        while (stops.moveToNext()) {
                            if (direction == null || path != stops.getInt(0)) {
                                path = stops.getInt(0); direction = new MatchedDirection(stops.getString(1)); route.matchedDirections.add(direction);
                            }
                            if (!stops.isNull(2)) direction.stops.computeIfAbsent(stops.getString(3), name -> new ArrayList<>()).add(stops.getInt(2));
                        }
                    }
                }
                result.add(route);
            }
        }
        return result;
    }
    List<Transit.Direction> directions(int key) {
        List<Transit.Direction> result = new ArrayList<>();
        try (Cursor paths = db.rawQuery("SELECT path_id,path_name FROM paths WHERE route_key=? ORDER BY path_id", new String[]{String.valueOf(key)})) {
            while (paths.moveToNext()) {
                Transit.Direction direction = new Transit.Direction(paths.getString(1));
                try (Cursor stops = db.rawQuery("SELECT stop_id,stop_name FROM stops WHERE route_key=? AND path_id=? ORDER BY sequence", new String[]{String.valueOf(key), paths.getString(0)})) {
                    while (stops.moveToNext()) direction.stops.add(new Transit.Stop(stops.getInt(0), stops.getString(1)));
                }
                if (!direction.stops.isEmpty()) result.add(direction);
            }
        }
        return result;
    }
    @Override public void close() { db.close(); }
}
