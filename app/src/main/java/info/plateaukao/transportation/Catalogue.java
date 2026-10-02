package info.plateaukao.transportation;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;

final class Catalogue implements AutoCloseable {
    static final class Route {
        final int key;
        final String name, description;
        Route(int key, String name, String description) {
            this.key = key; this.name = name; this.description = description;
        }
        @Override public String toString() { return name + "\n" + description; }
    }
    static final class StopRoute {
        final Route route;
        final String direction;
        final List<Integer> stopIds = new ArrayList<>();
        StopRoute(Route route, String direction) { this.route = route; this.direction = direction; }
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
    Route route(int key) {
        try (Cursor cursor = db.rawQuery("SELECT route_key,route_name,description FROM routes WHERE route_key=?", new String[]{String.valueOf(key)})) {
            return cursor.moveToFirst() ? new Route(cursor.getInt(0), cursor.getString(1), cursor.getString(2)) : null;
        }
    }
    List<Route> search(String query, Set<String> favourites, boolean onlyFavourites) {
        String escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<Route> result = new ArrayList<>();
        String sql = "SELECT route_key,route_name,description FROM routes "
            + "WHERE route_name LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\' ORDER BY sequence,route_name";
        String pattern = "%" + escaped + "%";
        try (Cursor cursor = db.rawQuery(sql, new String[]{pattern, pattern})) {
            while (cursor.moveToNext()) {
                int key = cursor.getInt(0);
                if (onlyFavourites && !favourites.contains(String.valueOf(key))) continue;
                result.add(new Route(key, cursor.getString(1), cursor.getString(2)));
            }
        }
        return result;
    }
    List<String> searchStops(String query) {
        String escaped = query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        List<String> result = new ArrayList<>();
        String stopSearchSql = "SELECT DISTINCT stop_name FROM stops WHERE stop_name LIKE ? ESCAPE '\\' ORDER BY stop_name";
        try (Cursor cursor = db.rawQuery(stopSearchSql, new String[]{"%" + escaped + "%"})) {
            while (cursor.moveToNext()) result.add(cursor.getString(0));
        }
        return result;
    }
    List<StopRoute> stopRoutes(String name) {
        List<StopRoute> result = new ArrayList<>();
        String stopRoutesSql = "SELECT r.route_key,r.route_name,r.description,p.path_id,p.path_name,s.stop_id "
            + "FROM stops s JOIN routes r ON r.route_key=s.route_key "
            + "JOIN paths p ON p.route_key=s.route_key AND p.path_id=s.path_id "
            + "WHERE s.stop_name=? ORDER BY r.sequence,r.route_name,p.path_id,s.sequence";
        try (Cursor cursor = db.rawQuery(stopRoutesSql, new String[]{name})) {
            int routeKey = -1, pathId = -1;
            StopRoute entry = null;
            while (cursor.moveToNext()) {
                if (entry == null || routeKey != cursor.getInt(0) || pathId != cursor.getInt(3)) {
                    routeKey = cursor.getInt(0); pathId = cursor.getInt(3);
                    entry = new StopRoute(new Route(routeKey, cursor.getString(1), cursor.getString(2)), cursor.getString(4));
                    result.add(entry);
                }
                if (!entry.stopIds.contains(cursor.getInt(5))) entry.stopIds.add(cursor.getInt(5));
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
