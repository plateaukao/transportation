package info.plateaukao.transportation;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Run with checks/run.sh; verifies real wire samples and important boundary cases. */
public final class TransitCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        File samples = new File(args[0]);
        Map<Integer, Transit.Arrival> arrivals;
        try (InputStream input = new FileInputStream(new File(samples, "arrival-100810.xml"))) { arrivals = Transit.arrivals(input); }
        check(arrivals.get(18101).label().equals("未發車"), "Negative ETA must retain its status");
        check(arrivals.get(18105).seconds == 28, "Decode real zlib payload");
        check(new Transit.Arrival(-1, "").label().equals("暫無資料"), "Missing ETA is not a countdown");
        check(new Transit.Arrival(30, "").label().equals("即將到站"), "Arrival threshold");
        check(new Transit.Arrival(61, "").label().equals("2 分鐘"), "Round minutes up");
        check(new Transit.Arrival(Integer.MAX_VALUE, "").label().equals("35791395 分鐘"), "No overflow");
        List<Transit.Direction> directions;
        try (InputStream input = new FileInputStream(new File(samples, "route-100810.dat"))) { directions = Transit.route(input); }
        check(directions.get(0).name.equals("往榮總"), "Keep direction name");
        check(directions.get(0).stops.get(0).id == 18101, "Join route and ETA by stop ID");
        List<Transit.Station> stations;
        try (InputStream input = new FileInputStream(new File(samples, "metro/tpc_metros.xml"))) { stations = Transit.stations(input); }
        Transit.Station zoo = stations.stream().filter(s -> s.id.equals("019")).findFirst().orElseThrow();
        Transit.Station muzha = stations.stream().filter(s -> s.id.equals("018")).findFirst().orElseThrow();
        check(Arrays.equals(zoo.tripTo(muzha), new int[]{20, 8, 2}), "Fare, discount and time columns");
        check(Arrays.equals(zoo.tripTo(zoo), new int[]{0, 0, 0}), "Same station");
        String malicious = "<!DOCTYPE r [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><r><e id='1' sec='1' msg='&x;'/></r>";
        boolean rejected = false;
        try { Transit.arrivals(new ByteArrayInputStream(malicious.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception expected) { rejected = true; }
        check(rejected, "Reject external entities");
        String utf16 = "<!DOCTYPE r [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><r/>";
        rejected = false;
        try { Transit.arrivals(new ByteArrayInputStream(utf16.getBytes(StandardCharsets.UTF_16))); }
        catch (Exception expected) { rejected = true; }
        check(rejected, "Reject alternate-encoding entity bypass");
        rejected = false;
        try { Transit.arrivals(new ByteArrayInputStream("<html>Unavailable</html>".getBytes(StandardCharsets.UTF_8))); }
        catch (Exception expected) { rejected = true; }
        check(rejected, "Reject service error pages");
        System.out.println("PASS: zlib routes, arrival statuses, fare/time matrix and XML trust boundary");
    }
}
