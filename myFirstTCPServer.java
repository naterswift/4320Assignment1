import java.net.ServerSocket;
import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * TCP Server - "Billing System"
 *
 * Usage: java myFirstTCPServer <port>
 *
 * Data file: data.csv must be accessible to the server.
 * Expected columns per line: Ci,Di,CSi
 * Example: 3,Pencil #HB,1
 */
public class myFirstTCPServer {

    private static class Item {
        String desc;
        short cost;
        Item(String d, short c) { desc = d; cost = c; }
    }

    public static void main(String[] args) throws IOException {

       if (args.length != 2) {
    throw new IllegalArgumentException("Usage: java myFirstTCPServer <port> <datafile>");
}

int port = Integer.parseInt(args[0]);
String dataFile = args[1];

Map<Short, Item> db = loadData(dataFile);

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("Server listening on port " + port);

            while (true) {
                try (Socket clientSocket = serverSocket.accept()) {

                    System.out.println(String.format(
                            "Client connected: %s:%d",
                            clientSocket.getInetAddress().getHostAddress(),
                            clientSocket.getPort()
                    ));

                    InputStream in = clientSocket.getInputStream();
                    OutputStream out = clientSocket.getOutputStream();

                    // Read first 4 bytes: Request# + TML
                    byte[] header = readFully(in, 4);
                    if (header == null) {
                        System.out.println("Client closed before sending header.");
                        continue;
                    }

                    short reqNum = readShortBE(header, 0);
                    short tmlShort = readShortBE(header, 2);
                    int tml = tmlShort & 0xFFFF;

                    if (tml < 6) {
                        // minimal valid request would be 2+2 + trailer(2) = 6
                        System.out.println("Invalid TML: " + tml);
                        sendError(out, reqNum);
                        continue;
                    }

                    int remaining = tml - 4;
                    byte[] rest = readFully(in, remaining);
                    if (rest == null) {
                        System.out.println("Client closed before full request arrived.");
                        sendError(out, reqNum);
                        continue;
                    }

                    byte[] request = new byte[tml];
                    System.arraycopy(header, 0, request, 0, 4);
                    System.arraycopy(rest, 0, request, 4, rest.length);

                    // Display request hex
                    System.out.println("Request bytes:");
                    printHexBytes(request);

                    // Validate request length = TML (we already enforced exact readFully)
                    // Parse pairs
                    List<Short> quantities = new ArrayList<>();
                    List<Short> codes = new ArrayList<>();

                    int idx = 4;
                    while (true) {
                        if (idx + 1 >= request.length) {
                            System.out.println("Malformed request: missing trailer.");
                            sendError(out, reqNum);
                            break;
                        }

                        short q = readShortBE(request, idx); idx += 2;

                        if (q == (short)0xFFFF) {
                            // trailer
                            break;
                        }

                        if (idx + 1 >= request.length) {
                            System.out.println("Malformed request: missing code after quantity.");
                            sendError(out, reqNum);
                            break;
                        }

                        short c = readShortBE(request, idx); idx += 2;

                        quantities.add(q);
                        codes.add(c);
                    }

                    // Build response
                    byte[] response = buildResponse(reqNum, quantities, codes, db);

                    // Display response hex
                    System.out.println("Response bytes:");
                    printHexBytes(response);

                    // Send response
                    out.write(response);
                    out.flush();
                }
            }
        }
    }

    private static byte[] buildResponse(short reqNum, List<Short> quantities, List<Short> codes, Map<Short, Item> db) {
        // First compute total size needed
        // Response:
        // Request# (2) + TML (2) + TC (4) + for each item: Li(1) + Di(Li) + CSi(2) + Qi(2) + trailer -1(2)

        int n = quantities.size();
        List<byte[]> descBytes = new ArrayList<>(n);
        List<Short> unitCosts = new ArrayList<>(n);

        long totalCost = 0;

        for (int i = 0; i < n; i++) {
            short code = codes.get(i);
            short qty = quantities.get(i);

            Item it = db.get(code);
            String desc = (it == null) ? "Article Not Available" : it.desc;
            short cost = (it == null) ? (short)0 : it.cost;

            byte[] dbs = desc.getBytes(); // default encoding per rubric
            if (dbs.length > 255) {
                // truncate to 255 to satisfy Li <= 255
                byte[] truncated = new byte[255];
                System.arraycopy(dbs, 0, truncated, 0, 255);
                dbs = truncated;
            }

            descBytes.add(dbs);
            unitCosts.add(cost);

            totalCost += (long)(cost & 0xFFFF) * (long)(qty & 0xFFFF);
        }

        int tml = 2 + 2 + 4; // req# + tml + TC
        for (int i = 0; i < n; i++) {
            tml += 1 + descBytes.get(i).length + 2 + 2;
        }
        tml += 2; // trailer -1

        byte[] buf = new byte[tml];
        int idx = 0;

        writeShortBE(buf, idx, reqNum); idx += 2;
        writeShortBE(buf, idx, (short) tml); idx += 2;
        writeIntBE(buf, idx, (int) totalCost); idx += 4;

        for (int i = 0; i < n; i++) {
            byte[] dbs = descBytes.get(i);
            int Li = dbs.length;

            buf[idx] = (byte) (Li & 0xFF); idx += 1;
            System.arraycopy(dbs, 0, buf, idx, Li); idx += Li;

            writeShortBE(buf, idx, unitCosts.get(i)); idx += 2;
            writeShortBE(buf, idx, quantities.get(i)); idx += 2;
        }

        writeShortBE(buf, idx, (short)0xFFFF);
        return buf;
    }

    private static void sendError(OutputStream out, short reqNum) throws IOException {
        // Error response: Request# -1
        byte[] err = new byte[4];
        writeShortBE(err, 0, reqNum);
        writeShortBE(err, 2, (short)0xFFFF);
        out.write(err);
        out.flush();

        System.out.println("Sent ERROR response (Request# -1):");
        printHexBytes(err);
    }

    // ---------------- CSV Loader ----------------

   private static Map<Short, Item> loadData(String filename) {
    Map<Short, Item> db = new HashMap<>();

    try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
        String line;
        int lineNum = 0;

        while ((line = br.readLine()) != null) {
            lineNum++;
            line = line.trim();
            if (line.isEmpty()) continue;

            // skip header lines
            String lower = line.toLowerCase();
            if (lower.startsWith("ci") || lower.startsWith("code")) continue;

            // split into 3 fields max
            String[] parts = line.split(",", 3);
            if (parts.length < 3) {
                System.out.println("Skipping malformed line " + lineNum + ": " + line);
                continue;
            }

            String codeStr = parts[0].trim().replace("\uFEFF", ""); // remove BOM if present
            String descStr = parts[1].trim();
            String costStr = parts[2].trim();

            // remove surrounding quotes from description if present
            if (descStr.startsWith("\"") && descStr.endsWith("\"") && descStr.length() >= 2) {
                descStr = descStr.substring(1, descStr.length() - 1);
            }

            // strip $ if present
            costStr = costStr.replace("$", "").trim();

            int codeInt = Integer.parseInt(codeStr);
            int costInt = Integer.parseInt(costStr);

            if (codeInt < 0 || codeInt > 32767) continue;
            if (costInt < 0 || costInt > 32767) costInt = 0;

            db.put((short) codeInt, new Item(descStr, (short) costInt));
        }

    } catch (Exception e) {
        System.out.println("WARNING: Could not load data file '" + filename + "': " + e.getMessage());
        System.out.println("Server will respond with 'Article Not Available' for all codes.");
    }

    return db;
}
    // ---------------- Helpers ----------------

    private static void printHexBytes(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) {
            sb.append(String.format("0x%02X ", b));
        }
        System.out.println(sb.toString().trim());
    }

    private static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r == -1) return null;
            off += r;
        }
        return buf;
    }

    private static void writeShortBE(byte[] buf, int idx, short val) {
        buf[idx] = (byte) ((val >>> 8) & 0xFF);
        buf[idx + 1] = (byte) (val & 0xFF);
    }

    private static short readShortBE(byte[] buf, int idx) {
        int hi = buf[idx] & 0xFF;
        int lo = buf[idx + 1] & 0xFF;
        return (short) ((hi << 8) | lo);
    }

    private static void writeIntBE(byte[] buf, int idx, int val) {
        buf[idx] = (byte) ((val >>> 24) & 0xFF);
        buf[idx + 1] = (byte) ((val >>> 16) & 0xFF);
        buf[idx + 2] = (byte) ((val >>> 8) & 0xFF);
        buf[idx + 3] = (byte) (val & 0xFF);
    }
}