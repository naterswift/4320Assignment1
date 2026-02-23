import java.net.Socket;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * TCP Client - "Billing System"
 *
 * Usage: java myFirstTCPClient <hostname> <port>
 *
 * Builds request:
 *   Request# (2) | TML (2) | Q1 (2) C1 (2) ... Qn (2) Cn (2) | -1 (2)
 *
 * Receives response:
 *   Request# (2) | TML (2) | TC (4) | (L1 (1) D1 (Li) CS1 (2) Q1 (2)) ... | -1 (2)
 */
public class myFirstTCPClient {

    private static final int BUFSIZE = 4096; // enough for typical messages

    private static short requestNumber = (short) (System.currentTimeMillis() & 0x7FFF);

    public static void main(String[] args) throws IOException {

        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: java myFirstTCPClient <hostname> <port>");
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);

        // Collect (Qi, Ci) pairs
        List<Short> quantities = new ArrayList<>();
        List<Short> codes = new ArrayList<>();

        Scanner sc = new Scanner(System.in);

        while (true) {
            System.out.print("Enter quantity Qi (or -1 to finish): ");
            int q = Integer.parseInt(sc.nextLine().trim());
            if (q == -1) break;
            if (q < 0 || q > 32767) {
                System.out.println("Qi must be in range 0..32767");
                continue;
            }

            System.out.print("Enter code Ci: ");
            int c = Integer.parseInt(sc.nextLine().trim());
            if (c < 0 || c > 32767) {
                System.out.println("Ci must be in range 0..32767");
                continue;
            }

            quantities.add((short) q);
            codes.add((short) c);
        }

        // Build request byte array A
        short reqNum = requestNumber++;
        byte[] request = buildRequest(reqNum, quantities, codes);

        // Display request in hex byte-by-byte
        System.out.println("\nRequest bytes:");
        printHexBytes(request);

        // Send request and receive response
        try (Socket socket = new Socket(host, port)) {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            out.write(request);
            out.flush();

            // Read response: first read 4 bytes (Request# + TML)
            byte[] header = readFully(in, 4);
            if (header == null) {
                throw new IOException("Server closed connection before sending header.");
            }

            short respReqNum = readShortBE(header, 0);
            short tml = readShortBE(header, 2);

            // If server error response is "Request# -1" => total 4 bytes
            // but rubric says error is "Request# -1" (2 + 2) = 4 bytes
            // In that case, TML isn't present. So we must detect it:
            // If the 2 bytes at offset 2 are 0xFF 0xFF, then it's -1 trailer.
            if ((header[2] == (byte)0xFF) && (header[3] == (byte)0xFF)) {
                System.out.println("\nResponse bytes (ERROR):");
                printHexBytes(header);
                System.out.println("Server reported error (Request# followed by -1).");
                return;
            }

            if (tml < 4) {
                throw new IOException("Invalid TML in response: " + tml);
            }

            int remaining = (tml & 0xFFFF) - 4;
            byte[] rest = readFully(in, remaining);
            if (rest == null) {
                throw new IOException("Server closed connection before sending full response.");
            }

            byte[] response = new byte[4 + rest.length];
            System.arraycopy(header, 0, response, 0, 4);
            System.arraycopy(rest, 0, response, 4, rest.length);

            // Display response hex
            System.out.println("\nResponse bytes:");
            printHexBytes(response);

            // Parse response and display bill
            parseAndDisplayBill(response, quantities, codes);

        }
    }

    private static byte[] buildRequest(short reqNum, List<Short> quantities, List<Short> codes) {
        int n = quantities.size();

        // Size:
        // Request# (2) + TML (2) + n*(Q(2)+C(2)) + trailer -1 (2)
        int tml = 2 + 2 + (n * 4) + 2;

        byte[] buf = new byte[tml];
        int idx = 0;

        writeShortBE(buf, idx, reqNum); idx += 2;
        writeShortBE(buf, idx, (short) tml); idx += 2;

        for (int i = 0; i < n; i++) {
            writeShortBE(buf, idx, quantities.get(i)); idx += 2;
            writeShortBE(buf, idx, codes.get(i)); idx += 2;
        }

        writeShortBE(buf, idx, (short) 0xFFFF); // -1 trailer
        return buf;
    }

    private static void parseAndDisplayBill(byte[] response, List<Short> sentQ, List<Short> sentC) throws IOException {
        int idx = 0;

        short reqNum = readShortBE(response, idx); idx += 2;
        short tml = readShortBE(response, idx); idx += 2;

        int totalCost = readIntBE(response, idx); idx += 4;

        List<String> descriptions = new ArrayList<>();
        List<Short> unitCosts = new ArrayList<>();
        List<Short> quantities = new ArrayList<>();

        // Parse items until trailer -1
        while (true) {
            // trailer is 0xFFFF (2 bytes). But items begin with Li (1 byte),
            // so we must check if the next TWO bytes are 0xFF 0xFF by peeking.
            if (idx + 1 < response.length &&
                response[idx] == (byte)0xFF &&
                response[idx + 1] == (byte)0xFF) {
                idx += 2;
                break;
            }

            int Li = response[idx] & 0xFF; idx += 1;

            if (idx + Li > response.length) {
                throw new IOException("Malformed response: description length goes past message end.");
            }

            String Di = new String(response, idx, Li);
            idx += Li;

            short CSi = readShortBE(response, idx); idx += 2;
            short Qi = readShortBE(response, idx); idx += 2;

            descriptions.add(Di);
            unitCosts.add(CSi);
            quantities.add(Qi);
        }

        // Verify TC equals sum(CSi * Qi)
        long computed = 0;
        for (int i = 0; i < unitCosts.size(); i++) {
            computed += (long)(unitCosts.get(i) & 0xFFFF) * (long)(quantities.get(i) & 0xFFFF);
        }

        if (computed != (totalCost & 0xFFFFFFFFL)) {
            System.out.println("\nError: the total cost in the response does not match the total computed by the client.");
            System.out.println("Server TC = " + (totalCost & 0xFFFFFFFFL) + ", Client computed = " + computed);
            return;
        }

        // Display bill
        System.out.println("\nItem #\tDescription\t\tUnit Cost\tQuantity\tCost Per Item");
        long running = 0;
        for (int i = 0; i < descriptions.size(); i++) {
            int u = unitCosts.get(i) & 0xFFFF;
            int q = quantities.get(i) & 0xFFFF;
            int line = u * q;
            running += line;

            System.out.printf("%d\t%s\t\t$%d\t\t%d\t\t$%d%n",
                    (i + 1), descriptions.get(i), u, q, line);
        }
        System.out.println("-----------------------------------------------");
        System.out.println("Total\t" + (totalCost & 0xFFFFFFFFL));
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

    private static int readIntBE(byte[] buf, int idx) {
        int b0 = buf[idx] & 0xFF;
        int b1 = buf[idx + 1] & 0xFF;
        int b2 = buf[idx + 2] & 0xFF;
        int b3 = buf[idx + 3] & 0xFF;
        return (b0 << 24) | (b1 << 16) | (b2 << 8) | b3;
    }
}