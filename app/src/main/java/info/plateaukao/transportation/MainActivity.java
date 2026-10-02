package info.plateaukao.transportation;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int INK = 0xff17253e, PRIMARY = 0xff3e63e9, MUTED = 0xff64748b, BACKGROUND = 0xfff5f7fc;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService searchArrivalWorker = Executors.newFixedThreadPool(3);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Catalogue catalogue;
    private List<Transit.Station> stations = Collections.emptyList();
    private SharedPreferences preferences;
    private LinearLayout root, content, appBar, bottomBar;
    private String tab = "公車", query = "";
    private Catalogue.Route selectedRoute;
    private List<Transit.Direction> directions = Collections.emptyList();
    private Map<Integer, Transit.Arrival> estimates = Collections.emptyMap();
    private int directionIndex, screenVersion, searchVersion, requestVersion;
    private boolean destroyed;
    private TextView arrivalStatus;
    private Button refresh;
    private ListView stopsList;
    private final Map<String, Button> navigation = new HashMap<>();
    private long arrivalTime;
    private int restoreRoute = -1, shortcutDirection = -1;
    private String fromId = "019", toId = "018";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("transportation", MODE_PRIVATE);
        fromId = preferences.getString("from", "019"); toId = preferences.getString("to", "018");
        if (state != null) {
            tab = state.getString("tab", "公車"); query = state.getString("query", "");
            restoreRoute = state.getInt("route", -1); directionIndex = state.getInt("direction", 0);
            fromId = state.getString("from", fromId); toId = state.getString("to", toId);
        }
        if (state == null) openShortcut(getIntent());
        if (!Arrays.asList("公車", "收藏", "捷運").contains(tab)) tab = "公車";
        root = column(); root.setBackgroundColor(BACKGROUND); root.setPadding(dp(20), dp(8), dp(20), 0);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                view.setPadding(bars.left + dp(20), bars.top + dp(8), bars.right + dp(20), bars.bottom);
            } else {
                view.setPadding(insets.getSystemWindowInsetLeft() + dp(20), dp(8), insets.getSystemWindowInsetRight() + dp(20), 0);
            }
            return insets;
        });
        setContentView(root); root.requestApplyInsets();
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::navigateBack);
        }
        appBar = row(); appBar.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = icon(R.drawable.ic_bus, 26, PRIMARY);
        appBar.addView(logo, new LinearLayout.LayoutParams(dp(32), dp(32)));
        TextView brand = text("台北交通", 20, true); brand.setPadding(dp(8), 0, 0, 0); brand.setGravity(Gravity.CENTER_VERTICAL);
        appBar.addView(brand, new LinearLayout.LayoutParams(0, dp(52), 1));
        root.addView(appBar);
        content = column(); root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        bottomBar = column(); bottomBar.addView(rule()); root.addView(bottomBar);
        LinearLayout tabs = row(); tabs.setPadding(0, dp(8), 0, dp(8));
        for (String label : new String[]{"公車", "收藏", "捷運"}) {
            Button button = button(label, () -> { hideKeyboard(); tab = label; selectedRoute = null; render(); });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(68), 1);
            int symbol = "公車".equals(label) ? R.drawable.ic_bus : "收藏".equals(label) ? R.drawable.ic_star : R.drawable.ic_train;
            android.graphics.drawable.Drawable glyph = getDrawable(symbol); glyph.setBounds(0, 0, dp(22), dp(22));
            button.setCompoundDrawables(null, glyph, null, null); button.setCompoundDrawablePadding(dp(5)); button.setTextSize(13);
            params.setMargins(dp(4), 0, dp(4), 0); tabs.addView(button, params); navigation.put(label, button);
        }
        bottomBar.addView(tabs);
        content.addView(text("正在準備離線路線…", 18, false));
        worker.execute(() -> {
            try {
                Catalogue loaded = new Catalogue(this);
                List<Transit.Station> loadedStations;
                try (InputStream input = getAssets().open("tpc_metros.xml")) { loadedStations = Transit.stations(input); }
                runOnUiThread(() -> {
                    if (destroyed) { loaded.close(); return; }
                    catalogue = loaded; stations = loadedStations;
                    if (restoreRoute != -1) selectedRoute = catalogue.route(restoreRoute);
                    render();
                    if (selectedRoute != null) fetchArrivals();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (!destroyed) { content.removeAllViews(); content.addView(text("無法載入離線資料：" + error.getMessage(), 18, false)); }
                });
            }
        });
        worker.execute(this::refreshPinnedShortcuts);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); openShortcut(intent);
    }

    private void openShortcut(Intent intent) {
        if (!intent.hasExtra("shortcut_route")) return;
        restoreRoute = intent.getIntExtra("shortcut_route", -1);
        shortcutDirection = Math.max(0, intent.getIntExtra("shortcut_direction", 0));
        tab = "公車"; selectedRoute = null; directions = Collections.emptyList();
        estimates = Collections.emptyMap(); arrivalTime = 0; directionIndex = 0;
        if (catalogue == null) return;
        selectedRoute = catalogue.route(restoreRoute);
        hideKeyboard(); render();
        if (selectedRoute != null) fetchArrivals();
        else Toast.makeText(this, "找不到此路線", Toast.LENGTH_SHORT).show();
    }

    private Intent shortcutIntent(int route, int direction) {
        return new Intent(this, MainActivity.class).setAction(Intent.ACTION_VIEW)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("shortcut_route", route).putExtra("shortcut_direction", direction);
    }

    private void refreshPinnedShortcuts() {
        ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
        if (shortcuts == null) return;
        List<ShortcutInfo> updated = new ArrayList<>();
        for (ShortcutInfo shortcut : shortcuts.getPinnedShortcuts()) {
            if (!shortcut.getId().startsWith("bus.")) continue;
            Intent previous = shortcut.getIntent();
            if (previous == null || !previous.hasExtra("shortcut_route")) continue;
            updated.add(new ShortcutInfo.Builder(this, shortcut.getId()).setShortLabel(shortcut.getShortLabel())
                .setIcon(routeShortcutIcon(shortcut.getShortLabel().toString()))
                .setIntent(shortcutIntent(previous.getIntExtra("shortcut_route", -1), previous.getIntExtra("shortcut_direction", 0))).build());
        }
        if (!updated.isEmpty()) shortcuts.updateShortcuts(updated);
    }

    private void render() {
        screenVersion++; searchVersion++; requestVersion++;
        if (appBar.getParent() != root) {
            ((android.view.ViewGroup) appBar.getParent()).removeView(appBar);
            root.addView(appBar, 0);
        }
        bottomBar.getLayoutParams().height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
        bottomBar.requestLayout();
        content.removeAllViews();
        for (Map.Entry<String, Button> item : navigation.entrySet()) {
            boolean active = item.getKey().equals(tab);
            item.getValue().setTextColor(active ? PRIMARY : MUTED);
            item.getValue().setBackground(surface(active ? 0xffeaf0ff : Color.TRANSPARENT, Color.TRANSPARENT, 14));
            for (android.graphics.drawable.Drawable glyph : item.getValue().getCompoundDrawables()) if (glyph != null) glyph.setTint(active ? PRIMARY : MUTED);
        }
        if (catalogue == null) { content.addView(text("正在準備離線路線…", 18, false)); return; }
        if (selectedRoute != null) { routeScreen(); return; }
        switch (tab) {
            case "捷運": metroScreen(); break;
            default: searchScreen();
        }
    }

    private Set<String> favourites() { return new HashSet<>(preferences.getStringSet("favourites", Collections.emptySet())); }

    private void searchScreen() {
        boolean onlyFavourites = "收藏".equals(tab);
        LinearLayout searchBox = row(); searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setId(View.generateViewId());
        searchBox.setBackground(surface(Color.WHITE, 0xffdee5f0, 16));
        ImageView searchIcon = icon(R.drawable.ic_search, 22, MUTED);
        searchIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams searchIconParams = new LinearLayout.LayoutParams(dp(22), dp(22));
        searchIconParams.setMargins(dp(16), 0, dp(12), 0); searchBox.addView(searchIcon, searchIconParams);
        AutoCompleteTextView search = new AutoCompleteTextView(this) {
            @Override public boolean enoughToFilter() { return false; }
        };
        search.setDropDownAnchor(searchBox.getId()); search.setDropDownVerticalOffset(dp(6));
        search.setDropDownBackgroundDrawable(new android.graphics.drawable.InsetDrawable(surface(Color.WHITE, 0xffdee5f0, 16), dp(8)));
        Runnable showHistory = () -> {
            if (search.length() != 0) return;
            List<String> history = searchHistory();
            if (history.isEmpty()) return;
            search.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, history) {
                @Override public View getView(int position, View reuse, android.view.ViewGroup parent) {
                    LinearLayout item = row(); item.setGravity(Gravity.CENTER_VERTICAL);
                    item.setPadding(dp(12), dp(10), dp(12), dp(10)); item.setMinimumHeight(dp(64));
                    item.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0xffeaf0ff), surface(Color.WHITE, Color.TRANSPARENT, 10), null));
                    ImageView glyph = icon(R.drawable.ic_search, 20, PRIMARY);
                    glyph.setPadding(dp(10), dp(10), dp(10), dp(10)); glyph.setBackground(surface(0xffedf2ff, Color.TRANSPARENT, 12));
                    glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                    item.addView(glyph, new LinearLayout.LayoutParams(dp(40), dp(40)));
                    TextView term = text(getItem(position), 17, true); term.setPadding(dp(12), 0, dp(8), 0);
                    term.setMaxLines(2); term.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    item.addView(term, new LinearLayout.LayoutParams(0, -2, 1));
                    TextView arrow = text("↗", 20, false); arrow.setTextColor(MUTED);
                    arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); item.addView(arrow);
                    return item;
                }
            });
            search.setDropDownWidth(searchBox.getWidth()); search.showDropDown();
        };
        search.setOnClickListener(view -> showHistory.run());
        search.setOnFocusChangeListener((view, focused) -> { if (focused) search.post(showHistory); });
        search.setOnItemClickListener((parent, view, position, id) -> rememberSearch(search.getText().toString()));
        search.setTextSize(17); search.setTextColor(INK); search.setSingleLine(true);
        search.setPadding(0, 0, dp(8), 0); search.setBackgroundColor(Color.TRANSPARENT);
        search.setHint("路線號碼、目的地或站名"); search.setHintTextColor(MUTED); search.setContentDescription("搜尋公車路線");
        search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(54), 1));
        Button clear = quietButton("×", () -> { rememberSearch(query); search.setText(""); }); clear.setContentDescription("清除搜尋");
        searchBox.addView(clear, new LinearLayout.LayoutParams(dp(44), dp(48)));
        content.addView(searchBox);
        ListView list = list(); content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView empty = caption(onlyFavourites ? "尚無收藏路線" : "找不到符合的路線");
        content.addView(empty); list.setEmptyView(empty);
        List<Catalogue.Route> results = new ArrayList<>();
        Map<Integer, Map<Integer, Transit.Arrival>> arrivals = new HashMap<>();
        Map<Integer, String> failures = new HashMap<>();
        Set<Integer> pending = new HashSet<>();
        ArrayAdapter<Catalogue.Route> adapter = new ArrayAdapter<Catalogue.Route>(this, android.R.layout.simple_list_item_1, results) {
            @Override public View getView(int position, View reuse, android.view.ViewGroup parent) {
                Catalogue.Route route = getItem(position);
                LinearLayout card = row(); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(12), dp(16), dp(12), dp(16)); card.setBackground(surface(Color.WHITE, Color.WHITE, 16));
                String[] parts = route.name.split(" ", 2);
                TextView number = text(parts[0], parts[0].length() > 5 ? 16 : 25, true);
                number.setGravity(Gravity.CENTER); number.setPadding(dp(4), dp(6), dp(4), dp(6));
                number.setMaxLines(2); number.setEllipsize(android.text.TextUtils.TruncateAt.END);
                number.setBackground(surface(0xffedf2ff, 0xffedf2ff, 12)); number.setTextColor(PRIMARY);
                card.addView(number, new LinearLayout.LayoutParams(dp(82), dp(66)));
                LinearLayout details = column(); details.setPadding(dp(14), 0, dp(4), 0);
                TextView destination = text(route.description.replace(" - ", " → "), 16, true);
                destination.setMaxLines(2); destination.setEllipsize(android.text.TextUtils.TruncateAt.END); destination.setPadding(0, 0, 0, dp(6));
                details.addView(destination);
                if (route.matchedStops != null) {
                    TextView matched = caption(matchedStopText(route, arrivals.get(route.key), failures.get(route.key)));
                    details.addView(matched);
                } else if (parts.length > 1) details.addView(caption(parts[1]));
                card.addView(details, new LinearLayout.LayoutParams(0, -2, 1));
                TextView arrow = text("›", 26, false); arrow.setTextColor(MUTED); card.addView(arrow);
                return card;
            }
        };
        list.setAdapter(adapter);
        Runnable fetchVisible = () -> {
            int first = Math.max(0, list.getFirstVisiblePosition());
            int last = Math.min(results.size() - 1, Math.max(first, list.getLastVisiblePosition()));
            int screen = screenVersion, version = searchVersion;
            for (int position = first; position <= last; position++) {
                Catalogue.Route route = results.get(position);
                if (route.matchedStops == null || arrivals.containsKey(route.key) || failures.containsKey(route.key) || !pending.add(route.key)) continue;
                handler.postDelayed(() -> {
                    if (destroyed || screen != screenVersion || version != searchVersion) return;
                    searchArrivalWorker.execute(() -> {
                        if (destroyed || screen != screenVersion || version != searchVersion) return;
                        try {
                            Map<Integer, Transit.Arrival> fresh = Transit.arrivals(new ByteArrayInputStream(Transit.download("https://busserver.bus.yahoo.com/api/route/" + route.key)));
                            runOnUiThread(() -> {
                                if (destroyed || screen != screenVersion || version != searchVersion) return;
                                pending.remove(route.key); arrivals.put(route.key, fresh); adapter.notifyDataSetChanged();
                            });
                        } catch (Exception error) {
                            runOnUiThread(() -> {
                                if (destroyed || screen != screenVersion || version != searchVersion) return;
                                pending.remove(route.key); failures.put(route.key, "更新失敗"); adapter.notifyDataSetChanged();
                            });
                        }
                    });
                }, 400);
            }
        };
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int state) {}
            public void onScroll(AbsListView view, int first, int count, int total) { fetchVisible.run(); }
        });
        Runnable update = () -> {
            if (destroyed || selectedRoute != null || (!"公車".equals(tab) && !"收藏".equals(tab))) return;
            results.clear(); results.addAll(catalogue.search(query, favourites(), onlyFavourites));
            arrivals.clear(); failures.clear(); pending.clear();
            adapter.notifyDataSetChanged(); handler.post(fetchVisible);
        };
        search.setText(query); update.run();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString(); int version = ++searchVersion;
                handler.postDelayed(() -> { if (version == searchVersion) update.run(); }, 120);
            }
            public void afterTextChanged(Editable text) {}
        });
        search.setOnEditorActionListener((v, action, event) -> { hideKeyboard(); update.run(); return true; });
        list.setOnItemClickListener((parent, view, position, id) -> {
            hideKeyboard(); selectedRoute = results.get(position); directionIndex = 0;
            directions = Collections.emptyList(); estimates = Collections.emptyMap(); arrivalTime = 0; render(); fetchArrivals();
        });
    }

    private List<String> searchHistory() {
        List<String> history = new ArrayList<>(Arrays.asList(preferences.getString("search_history", "").split("\n")));
        history.removeIf(String::isEmpty); return history;
    }

    private void rememberSearch(String value) {
        String term = value.trim().replace('\n', ' ');
        if (term.isEmpty()) return;
        List<String> history = searchHistory(); history.remove(term); history.add(0, term);
        if (history.size() > 20) history = history.subList(0, 20);
        preferences.edit().putString("search_history", String.join("\n", history)).apply();
    }

    private String matchedStopText(Catalogue.Route route, Map<Integer, Transit.Arrival> arrivals, String failure) {
        StringJoiner lines = new StringJoiner("\n");
        for (Catalogue.MatchedDirection direction : route.matchedDirections) {
            if (direction.stops.isEmpty()) {
                lines.add(direction.name + " · 不經匹配站點"); continue;
            }
            for (Map.Entry<String, List<Integer>> stop : direction.stops.entrySet()) {
                Transit.Arrival best = null;
                if (arrivals != null) for (int id : stop.getValue()) {
                    Transit.Arrival candidate = arrivals.get(id);
                    if (candidate != null && (best == null || (candidate.seconds >= 0 && (best.seconds < 0 || candidate.seconds < best.seconds)))) best = candidate;
                }
                String eta = failure != null ? failure : arrivals == null ? "更新中…" : best == null ? "暫無資料" : best.label();
                lines.add(stop.getKey() + " · " + direction.name + " · " + eta);
            }
        }
        return lines.toString();
    }

    private void routeScreen() {
        LinearLayout header = column();
        root.removeView(appBar); header.addView(appBar);
        LinearLayout heading = row(); heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView routeName = text(selectedRoute.name, 30, true);
        routeName.setOnLongClickListener(view -> { pinRouteShortcut(); return true; });
        heading.addView(routeName, new LinearLayout.LayoutParams(0, -2, 1));
        String key = String.valueOf(selectedRoute.key);
        Button favourite = quietButton(favourites().contains(key) ? "★ 已收藏" : "☆ 收藏", () -> {});
        favourite.setOnClickListener(v -> {
            Set<String> saved = favourites(); if (!saved.remove(key)) saved.add(key);
            preferences.edit().putStringSet("favourites", saved).apply();
            favourite.setText(saved.contains(key) ? "★ 已收藏" : "☆ 收藏");
        });
        heading.addView(favourite, new LinearLayout.LayoutParams(dp(104), dp(48))); header.addView(heading);
        space(header, 18);
        if (directions.isEmpty()) directions = catalogue.directions(selectedRoute.key);
        int requestedDirection = shortcutDirection >= 0 ? shortcutDirection : preferences.getInt("direction." + key, 0);
        directionIndex = Math.max(0, Math.min(requestedDirection, Math.max(0, directions.size() - 1)));
        if (shortcutDirection >= 0) {
            preferences.edit().putInt("direction." + key, directionIndex).apply(); shortcutDirection = -1;
        }
        LinearLayout controls = row(); controls.setGravity(Gravity.CENTER_VERTICAL);
        if (directions.size() <= 2) {
            Button direction = quietButton(directions.isEmpty() ? "暫無方向資料" : directions.get(directionIndex).toString(), () -> {});
            direction.setBackground(surface(Color.WHITE, 0xffdee5f0, 12));
            direction.setEnabled(directions.size() == 2);
            direction.setContentDescription("公車方向：" + direction.getText());
            direction.setOnClickListener(v -> {
                directionIndex = 1 - directionIndex;
                preferences.edit().putInt("direction." + key, directionIndex).apply();
                direction.setText(directions.get(directionIndex).toString());
                direction.setContentDescription("公車方向：" + direction.getText());
                showStops(); stopsList.setSelection(0);
            });
            controls.addView(direction, new LinearLayout.LayoutParams(0, dp(50), 1));
        } else {
            Spinner direction = new Spinner(this); direction.setBackground(surface(Color.WHITE, 0xffdee5f0, 12));
            direction.setPadding(dp(12), 0, dp(8), 0); direction.setContentDescription("公車方向");
            direction.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, directions));
            direction.setSelection(directionIndex);
            direction.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    boolean changed = position != directionIndex; directionIndex = position;
                    preferences.edit().putInt("direction." + key, directionIndex).apply(); showStops();
                    if (changed) stopsList.setSelection(0);
                }
                public void onNothingSelected(AdapterView<?> parent) {}
            });
            controls.addView(direction, new LinearLayout.LayoutParams(0, dp(50), 1));
        }
        refresh = button("更新", this::fetchArrivals); refresh.setContentDescription("更新到站時間");
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(dp(80), dp(50)); refreshParams.leftMargin = dp(10);
        controls.addView(refresh, refreshParams); header.addView(controls);
        arrivalStatus = caption(arrivalTime == 0 ? "尚未更新 · 點選更新取得預估時間" : "更新 " + time(arrivalTime) + " · Yahoo");
        arrivalStatus.setPadding(0, dp(12), 0, dp(12)); header.addView(arrivalStatus);
        stopsList = list(); stopsList.setVerticalScrollBarEnabled(false); stopsList.setHorizontalScrollBarEnabled(false);
        stopsList.addHeaderView(header, null, false);
        stopsList.addFooterView(caption("時間為預估，請提前至站牌候車"), null, false);
        FrameLayout scrolling = new FrameLayout(this);
        scrolling.addView(stopsList, new FrameLayout.LayoutParams(-1, -1));
        TextView stickyStatus = caption(arrivalStatus.getText().toString());
        stickyStatus.setPadding(0, dp(12), 0, dp(12)); stickyStatus.setBackgroundColor(BACKGROUND);
        stickyStatus.setElevation(dp(4)); stickyStatus.setVisibility(View.GONE);
        scrolling.addView(stickyStatus, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        TextView status = arrivalStatus;
        status.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                stickyStatus.setText(text);
                stickyStatus.post(() -> stickyStatus.setTextColor(status.getCurrentTextColor()));
            }
            public void afterTextChanged(Editable text) {}
        });
        content.addView(scrolling, new LinearLayout.LayoutParams(-1, -1));
        int navigationHeight = dp(85);
        stopsList.setOnScrollListener(new AbsListView.OnScrollListener() {
            public void onScrollStateChanged(AbsListView view, int state) {}
            public void onScroll(AbsListView view, int first, int visible, int total) {
                View top = view.getChildAt(0);
                if (top == null) return;
                stickyStatus.setVisibility(first > 0 || top.getBottom() <= status.getHeight() ? View.VISIBLE : View.GONE);
                int offset = first == 0 ? Math.max(0, -top.getTop()) : navigationHeight;
                int height = Math.max(0, navigationHeight - offset);
                if (bottomBar.getLayoutParams().height != height) {
                    bottomBar.getLayoutParams().height = height; bottomBar.requestLayout();
                }
            }
        });
        showStops();
    }

    private void pinRouteShortcut() {
        ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
        if (shortcuts == null || !shortcuts.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "目前的主畫面不支援新增捷徑", Toast.LENGTH_SHORT).show(); return;
        }
        Intent launch = shortcutIntent(selectedRoute.key, directionIndex);
        ShortcutInfo shortcut = new ShortcutInfo.Builder(this, "bus." + selectedRoute.key + "." + directionIndex)
            .setShortLabel(selectedRoute.name).setLongLabel(selectedRoute.name)
            .setIcon(routeShortcutIcon(selectedRoute.name)).setIntent(launch).build();
        worker.execute(() -> {
            shortcuts.updateShortcuts(Collections.singletonList(shortcut));
            runOnUiThread(() -> {
                if (!destroyed && !shortcuts.requestPinShortcut(shortcut, null)) {
                    Toast.makeText(this, "無法新增捷徑，請重試", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private Icon routeShortcutIcon(String name) {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(288, 288, android.graphics.Bitmap.Config.ARGB_8888);
        String routeNumber = name.split(" ", 2)[0];
        int background = PRIMARY;
        if (routeNumber.startsWith("綠")) background = 0xff07866d;
        else if (routeNumber.startsWith("棕")) background = 0xff795548;
        else if (routeNumber.startsWith("紅")) background = 0xffc93645;
        else if (routeNumber.startsWith("藍")) background = 0xff3157d7;
        else if (routeNumber.startsWith("橘")) background = 0xffe87818;
        else if (routeNumber.startsWith("黃")) background = 0xfff2c94c;
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap); canvas.drawColor(background);
        TextPaint paint = new TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(routeNumber.startsWith("黃") || routeNumber.startsWith("橘") ? INK : Color.WHITE);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextSize(routeNumber.length() <= 3 ? 80 : 52);
        StaticLayout label = StaticLayout.Builder.obtain(routeNumber, 0, routeNumber.length(), paint, 184)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END).build();
        canvas.translate(52, (288 - label.getHeight()) / 2f); label.draw(canvas);
        return Icon.createWithAdaptiveBitmap(bitmap);
    }

    private void showStops() {
        if (stopsList == null || directions.isEmpty()) return;
        List<Transit.Stop> stops = directions.get(directionIndex).stops;
        int position = stopsList.getFirstVisiblePosition();
        View first = stopsList.getChildAt(0); int top = first == null ? 0 : first.getTop();
        stopsList.setAdapter(new ArrayAdapter<Transit.Stop>(this, android.R.layout.simple_list_item_1, stops) {
            @Override public View getView(int position, View reuse, android.view.ViewGroup parent) {
                Transit.Stop stop = getItem(position); Transit.Arrival arrival = estimates.get(stop.id);
                LinearLayout line = row(); line.setGravity(Gravity.CENTER_VERTICAL); line.setPadding(dp(12), dp(14), dp(12), dp(14)); line.setBackground(surface(Color.WHITE, Color.WHITE, 14));
                TextView index = caption(String.format(Locale.TAIWAN, "%02d", position + 1));
                index.setGravity(Gravity.CENTER); index.setBackground(surface(0xffeaf0ff, 0xffeaf0ff, 20)); index.setTextColor(PRIMARY);
                line.addView(index, new LinearLayout.LayoutParams(dp(32), dp(32)));
                TextView name = text(stop.name, 17, true); name.setPadding(dp(12), 0, dp(10), 0); name.setMaxLines(2);
                line.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                String label = arrival == null ? "—" : arrival.label();
                TextView eta = text(label, label.endsWith("分鐘") ? 21 : 16, true);
                eta.setPadding(dp(8), dp(6), dp(8), dp(6)); eta.setGravity(Gravity.CENTER);
                if (arrival != null && arrival.seconds >= 0 && arrival.seconds < 60 && arrival.message.isEmpty()) {
                    eta.setBackground(surface(0xff07866d, 0xff07866d, 8)); eta.setTextColor(Color.WHITE);
                }
                if (arrival != null && arrival.seconds >= 60 && arrival.message.isEmpty()) eta.setTextColor(0xff07866d);
                eta.setContentDescription("預估到站：" + (arrival == null ? "尚無資料" : label));
                line.addView(eta, new LinearLayout.LayoutParams(dp(104), -2));
                return line;
            }
        });
        stopsList.setSelectionFromTop(position, top);
    }

    private void fetchArrivals() {
        if (selectedRoute == null || refresh == null) return;
        int routeKey = selectedRoute.key, version = screenVersion, request = ++requestVersion;
        refresh.setEnabled(false); refresh.setText("更新中"); arrivalStatus.setTextColor(MUTED); arrivalStatus.setText("正在取得公車預估到站時間…");
        worker.execute(() -> {
            try {
                Map<Integer, Transit.Arrival> fresh = Transit.arrivals(new ByteArrayInputStream(Transit.download("https://busserver.bus.yahoo.com/api/route/" + routeKey)));
                long fetched = System.currentTimeMillis();
                runOnUiThread(() -> {
                    if (destroyed || version != screenVersion || request != requestVersion) return;
                    if (fresh.isEmpty()) { refresh.setEnabled(true); refresh.setText("重試"); arrivalStatus.setText("資料服務暫無此路線的到站資訊"); return; }
                    estimates = fresh; arrivalTime = fetched;
                    showStops(); refresh.setEnabled(true); refresh.setText("更新");
                    arrivalStatus.setText("更新 " + time(fetched) + " · Yahoo 公車動態");
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (destroyed || version != screenVersion || request != requestVersion) return;
                    refresh.setEnabled(true); refresh.setText("重試");
                    android.util.Log.e("Transportation", "Arrival request failed for route " + routeKey, error);
                    String reason = error instanceof java.net.UnknownHostException ? "無法連上資料服務，請確認網路"
                        : error instanceof java.net.SocketTimeoutException ? "連線逾時，請重試" : "資料更新失敗，請重試";
                    arrivalStatus.setText(reason + (arrivalTime == 0 ? "" : " · 舊資料 " + time(arrivalTime)));
                    arrivalStatus.setTextColor(0xffb45309);
                });
            }
        });
    }

    private void metroScreen() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout body = column(); scroll.addView(body); content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        body.addView(caption("台北捷運  /  票價查詢"));
        TextView title = text("下一站，去哪？", 30, true); title.setPadding(0, dp(4), 0, dp(24)); body.addView(title);
        LinearLayout journey = row(); journey.setGravity(Gravity.CENTER_VERTICAL);
        journey.setPadding(dp(16), dp(12), dp(12), dp(12));
        journey.setBackground(surface(Color.WHITE, 0xffdee5f0, 16));
        LinearLayout endpoints = column();
        endpoints.addView(caption("出發站")); Spinner from = stationPicker("出發站", fromId); endpoints.addView(from);
        endpoints.addView(rule()); endpoints.addView(caption("到達站")); Spinner to = stationPicker("到達站", toId); endpoints.addView(to);
        journey.addView(endpoints, new LinearLayout.LayoutParams(0, -2, 1));
        Button swap = quietButton("⇅", () -> {
            int origin = from.getSelectedItemPosition(), destination = to.getSelectedItemPosition();
            from.setSelection(destination); to.setSelection(origin);
        });
        swap.setTextSize(28); swap.setContentDescription("交換起迄站");
        journey.addView(swap, new LinearLayout.LayoutParams(dp(48), dp(48)));
        body.addView(journey);
        space(body, 12);
        LinearLayout result = column(); result.setPadding(dp(22), dp(18), dp(22), dp(18));
        android.graphics.drawable.GradientDrawable blue = new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR, new int[]{0xff3157d7, 0xff6a7ff1});
        blue.setCornerRadius(dp(20)); result.setBackground(blue);
        TextView fareLabel = text("單程全票", 14, false); fareLabel.setTextColor(Color.WHITE); result.addView(fareLabel);
        TextView fare = text("", 46, true); fare.setTextColor(Color.WHITE); result.addView(fare);
        TextView duration = text("", 18, false); duration.setTextColor(Color.WHITE); result.addView(duration);
        body.addView(result);
        TextView discount = caption(""); discount.setPadding(0, dp(16), 0, dp(8)); body.addView(discount);
        Runnable calculate = () -> {
            Transit.Station origin = (Transit.Station) from.getSelectedItem(), destination = (Transit.Station) to.getSelectedItem();
            if (origin == null || destination == null) return;
            fromId = origin.id; toId = destination.id;
            preferences.edit().putString("from", fromId).putString("to", toId).apply();
            int[] trip = origin.tripTo(destination);
            if (origin.id.equals(destination.id)) { fare.setText("同一站"); duration.setText("請選擇不同的到達站"); discount.setText(""); return; }
            fare.setText(trip == null || trip[0] < 0 ? "暫無資料" : "NT$ " + trip[0]);
            duration.setText(trip == null || trip[2] < 0 ? "暫無旅程時間" : "預估旅程  " + trip[2] + " 分鐘");
            discount.setText(trip == null || trip[1] < 0 ? "" : "優惠票參考 NT$ " + trip[1] + " · 資格依捷運公告");
        };
        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) { calculate.run(); }
            public void onNothingSelected(AdapterView<?> parent) {}
        };
        from.setOnItemSelectedListener(listener); to.setOnItemSelectedListener(listener);
        body.addView(caption("旅程時間為站間估計，並非列車到站倒數。"));
        body.addView(caption("資料 " + Transit.SOURCE_DATE + " · Yahoo")); calculate.run();
    }

    private Spinner stationPicker(String label, String selected) {
        Spinner spinner = new Spinner(this); spinner.setContentDescription(label); spinner.setMinimumHeight(dp(56)); spinner.setBackgroundColor(Color.TRANSPARENT);
        spinner.setAdapter(new ArrayAdapter<Transit.Station>(this, android.R.layout.simple_spinner_dropdown_item, stations) {
            @Override public View getView(int position, View reuse, android.view.ViewGroup parent) {
                TextView label = text(getItem(position).name + "  ›", 21, true); label.setPadding(0, dp(8), 0, dp(8)); return label;
            }
        });
        for (int i = 0; i < stations.size(); i++) if (stations.get(i).id.equals(selected)) spinner.setSelection(i);
        return spinner;
    }

    // Android 13+ uses the native dispatcher registered above; Android 10-12 uses this callback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { navigateBack(); }
    private void navigateBack() {
        if (selectedRoute != null) { selectedRoute = null; estimates = Collections.emptyMap(); arrivalTime = 0; render(); }
        else if (!"公車".equals(tab)) { tab = "公車"; render(); }
        else finish();
    }
    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString("tab", tab); state.putString("query", query);
        state.putInt("route", selectedRoute == null ? -1 : selectedRoute.key); state.putInt("direction", directionIndex);
        state.putString("from", fromId); state.putString("to", toId);
    }
    @Override public void onPause() {
        rememberSearch(query); super.onPause();
    }
    @Override public void onDestroy() {
        destroyed = true; handler.removeCallbacksAndMessages(null); worker.shutdownNow(); searchArrivalWorker.shutdownNow();
        if (catalogue != null) catalogue.close(); super.onDestroy();
    }
    private void hideKeyboard() {
        rememberSearch(query);
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
        root.setFocusableInTouchMode(true); root.requestFocus();
    }
    private String time(long value) { return new SimpleDateFormat("HH:mm:ss", Locale.TAIWAN).format(new Date(value)); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private ListView list() { ListView list = new ListView(this); list.setDivider(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)); list.setDividerHeight(dp(8)); list.setSelector(android.R.color.transparent); return list; }
    private TextView text(String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(INK);
        view.setPadding(0, dp(6), 0, dp(6)); view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return view;
    }
    private Button button(String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setTextColor(INK);
        button.setTextSize(15); button.setAllCaps(false); button.setMinimumHeight(dp(48)); button.setMinWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0); button.setStateListAnimator(null);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setBackground(surface(PRIMARY, PRIMARY, 12)); button.setTextColor(Color.WHITE);
        button.setOnClickListener(view -> action.run()); return button;
    }
    private Button quietButton(String label, Runnable action) {
        Button button = button(label, action); button.setBackgroundColor(Color.TRANSPARENT); button.setTextColor(INK); return button;
    }
    private TextView caption(String value) {
        TextView view = text(value, 12, false); view.setTextColor(MUTED); return view;
    }
    private android.graphics.drawable.GradientDrawable surface(int fill, int stroke, int radius) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(fill); drawable.setCornerRadius(dp(radius)); drawable.setStroke(dp(1), stroke); return drawable;
    }
    private ImageView icon(int resource, int size, int color) {
        ImageView image = new ImageView(this); image.setImageResource(resource); image.setImageTintList(android.content.res.ColorStateList.valueOf(color));
        image.setScaleType(ImageView.ScaleType.FIT_CENTER); return image;
    }
    private View rule() { View view = new View(this); view.setBackgroundColor(0xffe2e8f0); view.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(1))); return view; }
    private void space(LinearLayout parent, int height) { View view = new View(this); parent.addView(view, new LinearLayout.LayoutParams(-1, dp(height))); }

}
