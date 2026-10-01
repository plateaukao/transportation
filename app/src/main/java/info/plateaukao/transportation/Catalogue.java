package info.plateaukao.transportation;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.*;
import java.util.*;

final class Catalogue implements AutoCloseable {
    static final class Route {
        final int key;
        final String name, description, city;
        Route(int key, String name, String description, String city) {
            this.key = key; this.name = name; this.description = description; this.city = city;
        }
        @Override public String toString() { return name + "\n" + city + " · " + description; }
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
        String sql = "SELECT route_key,route_name,description,provider FROM routes WHERE "
            + "(route_name LIKE ? ESCAPE '\\' OR description LIKE ? ESCAPE '\\') ORDER BY sequence,route_name";
        try (Cursor cursor = db.rawQuery(sql, new String[]{"%" + escaped + "%", "%" + escaped + "%"})) {
            while (cursor.moveToNext()) {
                int key = cursor.getInt(0);
                if (onlyFavourites && !favourites.contains(String.valueOf(key))) continue;
                result.add(new Route(key, cursor.getString(1), cursor.getString(2), "tpc".equals(cursor.getString(3)) ? "台北" : "新北"));
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
