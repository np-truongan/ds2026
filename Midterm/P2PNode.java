import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class P2PNode {
    // --- Configuration ---
    private int myPort;
    private String myIP;
    private final String sharedFolder = "shared_files";

    // Thread-safe list for neighbors
    private final List<Connection> neighbors = Collections.synchronizedList(new ArrayList<>());

    // Routing tables
    private final Map<String, Connection> routingTable = new ConcurrentHashMap<>();
    private final Set<String> seenQueries = Collections.synchronizedSet(new HashSet<>());

    // Background scheduler for Keep-Alives and Timeouts
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // --- Protocol Constants ---
    private static final byte TYPE_HANDSHAKE = 0x01;
    private static final byte TYPE_KEEP_ALIVE = 0x02;
    private static final byte TYPE_QUERY = 0x03;
    private static final byte TYPE_QUERY_HIT = 0x04;
    private static final byte TYPE_DOWNLOAD_REQ = 0x05;
    private static final byte TYPE_DOWNLOAD_RES = 0x06;

    // Timeouts (ms)
    private static final long KEEP_ALIVE_INTERVAL = 20000; // 20 seconds
    private static final long PEER_TIMEOUT = 45000;        // 45 seconds

    private volatile boolean isRunning = true;

    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java P2PNode <port>");
            return;
        }
        new P2PNode(Integer.parseInt(args[0])).start();
    }

    public P2PNode(int port) {
        this.myPort = port;
        this.myIP = getPublicIp(); // <--- FIXED: Smart IP detection
        new File(sharedFolder).mkdirs();
    }

    // --- NEW: Smart IP Detection ---
    private String getPublicIp() {
        // Method 1: Ask the OS "If I wanted to reach the internet, which IP would I use?"
        // This effectively filters out VirtualBox/VMWare adapters that don't have internet access.
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.connect(InetAddress.getByName("8.8.8.8"), 10002);
            return socket.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            // Method 2: Fallback to manual iteration if offline
            try {
                Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
                while (interfaces.hasMoreElements()) {
                    NetworkInterface iface = interfaces.nextElement();
                    // Skip Loopback (127.0.0.1) and inactive interfaces
                    if (iface.isLoopback() || !iface.isUp()) continue;

                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        // We want IPv4 (not IPv6) and not a link-local address
                        if (addr instanceof Inet4Address && !addr.isLinkLocalAddress()) {
                            return addr.getHostAddress();
                        }
                    }
                }
            } catch (SocketException ex) {
                ex.printStackTrace();
            }
            return "127.0.0.1"; // Final fallback
        }
    }

    public void start() {
        // 1. Start Server Thread
        new Thread(this::listenForConnections).start();

        // 2. Start Peer Maintenance (Pings + Timeouts)
        startPeerMaintenance();

        // 3. Start CLI Loop
        Scanner scanner = new Scanner(System.in);
        System.out.println("=== P2P Node (" + myIP + ":" + myPort + ") ===");
        System.out.println("Commands:");
        System.out.println("  connect <ip> <port>         -> Connect to a neighbor");
        System.out.println("  search <filename>           -> Flood network for file");
        System.out.println("  download <ip> <port> <file> -> Direct download");
        System.out.println("  neighbors                   -> List connected peers");
        System.out.println("  exit                        -> Exit the application");

        System.out.print("> ");

        while (isRunning) {
            if (!scanner.hasNextLine()) break;

            String line = scanner.nextLine();
            if (line.trim().isEmpty()) {
                System.out.print("> ");
                continue;
            }

            String[] parts = line.trim().split(" ");
            String command = parts[0].toLowerCase();

            try {
                switch (command) {
                    case "connect":
                        if (parts.length < 3) System.out.println("Usage: connect <ip> <port>");
                        else connectToPeer(parts[1], Integer.parseInt(parts[2]));
                        break;

                    case "search":
                        if (parts.length < 2) {
                            System.out.println("Usage: search <filename>");
                        } else {
                            String filename = line.substring(line.indexOf(" ") + 1).trim();
                            initiateSearch(filename);
                        }
                        break;

                    case "download":
                        String[] dlParts = line.trim().split(" ", 4);
                        if (dlParts.length < 4) {
                            System.out.println("Usage: download <ip> <port> <filename>");
                        } else {
                            initiateDownload(dlParts[1], Integer.parseInt(dlParts[2]), dlParts[3]);
                        }
                        break;

                    case "neighbors":
                        printNeighbors();
                        break;

                    case "exit":
                        isRunning = false;
                        scheduler.shutdownNow();
                        System.exit(0);
                        break;

                    default:
                        System.out.println("Unknown command");
                }
            } catch (Exception e) {
                System.out.println("Error executing command: " + e.getMessage());
            }

            if (isRunning) System.out.print("> ");
        }
    }

    // --- HELPER: Handles Async Notifications ---
    private void asyncLog(String message) {
        System.out.print("\r" + message + "\n> ");
    }

    // --- Keep-Alive & Timeout System ---
    private void startPeerMaintenance() {
        scheduler.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            byte[] ping = "PING".getBytes(StandardCharsets.UTF_8);

            List<Connection> snapshot;
            synchronized (neighbors) {
                snapshot = new ArrayList<>(neighbors);
            }

            for (Connection c : snapshot) {
                if (now - c.lastSeen > PEER_TIMEOUT) {
                    asyncLog("[Timeout] Dropping " + c.socket.getInetAddress() + " (Last seen " + ((now - c.lastSeen)/1000) + "s ago)");
                    c.close();
                    continue;
                }

                if (now - c.lastPingSent > KEEP_ALIVE_INTERVAL) {
                    c.sendPacket(TYPE_KEEP_ALIVE, 1, ping);
                    c.lastPingSent = now;
                }
            }
        }, 0, 5000, TimeUnit.MILLISECONDS);
    }

    // --- Networking Logic ---

    private void listenForConnections() {
        try (ServerSocket serverSocket = new ServerSocket(myPort)) {
            while (isRunning) {
                Socket socket = serverSocket.accept();
                try {
                    Connection conn = new Connection(socket);
                    neighbors.add(conn);
                    new Thread(conn).start();
                } catch (IOException e) {
                    asyncLog("Error accepting connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void connectToPeer(String ip, int port) {
        try {
            InetAddress targetAddr = InetAddress.getByName(ip);
            if (targetAddr.isLoopbackAddress() && port == myPort) {
                System.out.println("Error: Cannot connect to yourself!");
                return;
            }
        } catch (UnknownHostException e) {
            System.out.println("Invalid IP address");
            return;
        }

        try {
            Socket socket = new Socket(ip, port);
            Connection conn = new Connection(socket);
            neighbors.add(conn);
            new Thread(conn).start();

            String identity = myIP + ":" + myPort;
            conn.sendPacket(TYPE_HANDSHAKE, 1, identity.getBytes(StandardCharsets.UTF_8));

            System.out.println("Connected to " + ip + ":" + port);
        } catch (IOException e) {
            System.out.println("Connection failed: " + e.getMessage());
        }
    }

    private void initiateSearch(String filename) {
        String queryId = UUID.randomUUID().toString();
        seenQueries.add(queryId);

        String payloadStr = queryId + "|" + filename;
        byte[] payload = payloadStr.getBytes(StandardCharsets.UTF_8);

        System.out.println("Flooding search for: " + filename);

        synchronized (neighbors) {
            for (Connection c : neighbors) {
                c.sendPacket(TYPE_QUERY, 5, payload);
            }
        }
    }

    private void initiateDownload(String ip, int port, String filename) {
        new Thread(() -> {
            try (Socket socket = new Socket(ip, port);
                 DataInputStream dis = new DataInputStream(socket.getInputStream());
                 DataOutputStream dos = new DataOutputStream(socket.getOutputStream())) {

                asyncLog("Requesting " + filename + " from " + ip + ":" + port);

                byte[] filenameBytes = filename.getBytes(StandardCharsets.UTF_8);
                dos.writeInt(filenameBytes.length);
                dos.writeByte(TYPE_DOWNLOAD_REQ);
                dos.writeByte(1);
                dos.write(filenameBytes);
                dos.flush();

                int len = dis.readInt();
                byte type = dis.readByte();
                dis.readByte(); // skip TTL

                if (type == TYPE_DOWNLOAD_RES) {
                    byte[] data = new byte[len];
                    dis.readFully(data);

                    File outFile = new File("downloaded_" + filename);
                    try (FileOutputStream fos = new FileOutputStream(outFile)) {
                        fos.write(data);
                    }
                    asyncLog("Download complete: " + outFile.getAbsolutePath());
                } else {
                    asyncLog("Unexpected response type: " + type);
                }
            } catch (IOException e) {
                asyncLog("Download failed: " + e.getMessage());
            }
        }).start();
    }

    private void printNeighbors() {
        synchronized (neighbors) {
            System.out.println("Connected Neighbors:");
            if (neighbors.isEmpty()) {
                System.out.println("  (none)");
            } else {
                for (Connection c : neighbors) {
                    long ago = (System.currentTimeMillis() - c.lastSeen) / 1000;
                    System.out.println(" - " + c.socket.getInetAddress() + ":" + c.socket.getPort() + " (Last seen " + ago + "s ago)");
                }
            }
        }
    }

    // --- Inner Class: Handles One Peer Connection ---
    private class Connection implements Runnable {
        Socket socket;
        DataInputStream dis;
        DataOutputStream dos;
        volatile long lastSeen;
        volatile long lastPingSent;

        public Connection(Socket socket) throws IOException {
            this.socket = socket;
            this.dos = new DataOutputStream(socket.getOutputStream());
            this.dis = new DataInputStream(socket.getInputStream());
            this.lastSeen = System.currentTimeMillis();
            this.lastPingSent = System.currentTimeMillis();
        }

        @Override
        public void run() {
            try {
                while (isRunning && !socket.isClosed()) {
                    int length = dis.readInt();
                    byte type = dis.readByte();
                    byte ttl = dis.readByte();

                    byte[] payload = new byte[length];
                    if (length > 0) {
                        dis.readFully(payload);
                    }
                    this.lastSeen = System.currentTimeMillis();
                    handleMessage(type, ttl, payload);
                }
            } catch (EOFException | SocketException e) {
            } catch (IOException e) {
            } finally {
                close();
            }
        }

        private void close() {
            try {
                if (!socket.isClosed()) socket.close();
            } catch (IOException ignored) {}
            neighbors.remove(this);
        }

        private void handleMessage(byte type, byte ttl, byte[] payload) {
            switch (type) {
                case TYPE_HANDSHAKE:
                    asyncLog("[Handshake] Connected to: " + new String(payload, StandardCharsets.UTF_8));
                    break;
                case TYPE_KEEP_ALIVE:
                    break;
                case TYPE_QUERY:
                    handleQuery(ttl, payload);
                    break;
                case TYPE_QUERY_HIT:
                    handleQueryHit(ttl, payload);
                    break;
                case TYPE_DOWNLOAD_REQ:
                    handleDownloadRequest(payload);
                    break;
            }
        }

        private void handleQuery(byte ttl, byte[] payload) {
            String content = new String(payload, StandardCharsets.UTF_8);
            String[] parts = content.split("\\|");
            if (parts.length < 2) return;

            String queryId = parts[0];
            String filename = parts[1];

            if (seenQueries.contains(queryId)) return;
            seenQueries.add(queryId);

            routingTable.put(queryId, this);

            File file = new File(sharedFolder, filename);
            if (file.exists()) {
                asyncLog("[Search] Found file locally! Sending Hit.");
                String hitPayload = queryId + "|" + filename + "|" + file.length() + "|" +
                        myIP + "|" + myPort;
                sendPacket(TYPE_QUERY_HIT, ttl, hitPayload.getBytes(StandardCharsets.UTF_8));
            }

            if (ttl > 1) {
                byte newTTL = (byte)(ttl - 1);
                synchronized (neighbors) {
                    for (Connection neighbor : neighbors) {
                        if (neighbor != this) {
                            neighbor.sendPacket(TYPE_QUERY, newTTL, payload);
                        }
                    }
                }
            }
        }

        private void handleQueryHit(byte ttl, byte[] payload) {
            String content = new String(payload, StandardCharsets.UTF_8);
            String[] parts = content.split("\\|");
            String queryId = parts[0];

            if (routingTable.containsKey(queryId)) {
                Connection source = routingTable.get(queryId);
                if (source != null) {
                    source.sendPacket(TYPE_QUERY_HIT, ttl, payload);
                }
                routingTable.remove(queryId);
            } else {
                asyncLog("\n>>> QUERY HIT! <<<\n" +
                        "File: " + parts[1] + "\n" +
                        "Size: " + parts[2] + " bytes\n" +
                        "Peer: " + parts[3] + ":" + parts[4] + "\n" +
                        "To download: download " + parts[3] + " " + parts[4] + " " + parts[1]);
            }
        }

        private void handleDownloadRequest(byte[] payload) {
            String filename = new String(payload, StandardCharsets.UTF_8);
            File file = new File(sharedFolder, filename);
            try {
                if (file.exists()) {
                    byte[] fileData = java.nio.file.Files.readAllBytes(file.toPath());
                    sendPacket(TYPE_DOWNLOAD_RES, 1, fileData);
                } else {
                    asyncLog("Peer requested non-existent file: " + filename);
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public synchronized void sendPacket(byte type, int ttl, byte[] payload) {
            try {
                dos.writeInt(payload.length);
                dos.writeByte(type);
                dos.writeByte(ttl);
                dos.write(payload);
                dos.flush();
            } catch (IOException e) {
                close();
            }
        }
    }
}