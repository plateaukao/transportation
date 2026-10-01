package info.plateaukao.transportation;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.InflaterInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** The prototype's Yahoo wire formats. No BusTracker application code is reused. */
public final class Transit {
    public static final String SOURCE_DATE = "2026-10-01";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    public static final class Stop {
        public final int id;
        public final String name;
        public Stop(int id, String name) { this.id = id; this.name = name; }
    }
    public static final class Direction {
        public final String name;
        public final List<Stop> stops = new ArrayList<>();
        Direction(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }
    public static final class Arrival {
        public final int seconds;
        public final String message;
        Arrival(int seconds, String message) { this.seconds = seconds; this.message = message; }
        public String label() {
            if (!message.isEmpty()) return message;
            if (seconds < 0) return "暫無資料";
            if (seconds <= 30) return "即將到站";
            if (seconds < 60) return "進站中";
            return ((seconds + 59L) / 60) + " 分鐘";
        }
    }
    public static final class Station {
        public final String id, name, english;
        private final Map<String, int[]> destinations = new HashMap<>();
        Station(Element e) throws IOException {
            id = e.getAttribute("id"); name = e.getAttribute("zh"); english = e.getAttribute("en");
            for (String row : e.getAttribute("datas").split(";")) {
                if (row.isEmpty()) continue;
                String[] parts = row.split(",");
                if (parts.length < 5) throw new IOException("不完整的捷運票價資料");
                destinations.put(parts[0], new int[]{number(parts[1]), number(parts[3]), number(parts[4])});
            }
        }
        public int[] tripTo(Station other) {
            if (id.equals(other.id)) return new int[]{0, 0, 0};
            int[] trip = destinations.get(other.id);
            return trip == null ? null : trip.clone();
        }
        @Override public String toString() { return name + " · " + english; }
    }

    public static List<Direction> route(InputStream input) throws Exception {
        Document doc = xml(input);
        if (!"root".equals(doc.getDocumentElement().getTagName())) throw new IOException("公車路線格式錯誤");
        List<Direction> result = new ArrayList<>();
        NodeList paths = doc.getElementsByTagName("p");
        for (int i = 0; i < paths.getLength(); i++) {
            Element path = (Element) paths.item(i);
            Direction direction = new Direction(path.getAttribute("nm"));
            NodeList stops = path.getElementsByTagName("s");
            for (int j = 0; j < stops.getLength(); j++) {
                Element stop = (Element) stops.item(j);
                direction.stops.add(new Stop(number(stop.getAttribute("id")), stop.getAttribute("nm")));
            }
            result.add(direction);
        }
        if (result.isEmpty()) throw new IOException("查無路線方向");
        return result;
    }

    public static Map<Integer, Arrival> arrivals(InputStream input) throws Exception {
        Document doc = xml(input);
        if (!"r".equals(doc.getDocumentElement().getTagName())) throw new IOException("到站資料格式錯誤");
        Map<Integer, Arrival> result = new HashMap<>();
        NodeList stops = doc.getElementsByTagName("e");
        for (int i = 0; i < stops.getLength(); i++) {
            Element stop = (Element) stops.item(i);
            result.put(number(stop.getAttribute("id")), new Arrival(number(stop.getAttribute("sec")), stop.getAttribute("msg")));
        }
        return result;
    }

    public static List<Station> stations(InputStream input) throws Exception {
        Document doc = xml(input);
        if (!"stations".equals(doc.getDocumentElement().getTagName())) throw new IOException("捷運資料格式錯誤");
        List<Station> result = new ArrayList<>();
        NodeList stations = doc.getElementsByTagName("station");
        for (int i = 0; i < stations.getLength(); i++) result.add(new Station((Element) stations.item(i)));
        result.sort(Comparator.comparing(s -> s.name));
        return result;
    }

    public static byte[] download(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("資料服務回應 " + status);
            try (InputStream stream = connection.getInputStream()) { return readLimited(stream); }
        } finally { connection.disconnect(); }
    }

    private static Document xml(InputStream input) throws Exception {
        byte[] wire = readLimited(input);
        if (wire.length >= 2 && (wire[0] & 255) == 0x78) {
            try (InputStream inflated = new InflaterInputStream(new ByteArrayInputStream(wire))) { wire = readLimited(inflated); }
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Android's DOM implementation lacks Xerces's disallow-doctype feature.
        // Parse strictly decoded UTF-8 text so alternate encodings cannot evade this check.
        String decoded = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(wire)).toString();
        if (decoded.indexOf('\0') >= 0 || java.util.regex.Pattern.compile("<!\\s*DOCTYPE", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(decoded).find()) {
            throw new IOException("不接受 XML 外部實體或文件類型宣告");
        }
        factory.setExpandEntityReferences(false);
        javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException("External entities disabled"); });
        return builder.parse(new org.xml.sax.InputSource(new StringReader(decoded)));
    }

    private static int number(String value) throws IOException {
        try { return Integer.parseInt(value); }
        catch (NumberFormatException e) { throw new IOException("資料數值格式錯誤", e); }
    }
    private static byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_BYTES) throw new IOException("資料超過大小限制");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
