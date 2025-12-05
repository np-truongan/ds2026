import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.Scanner;

public class NIOFileTransfer {
    private static final int MSG_TAG = 0;
    private static final int FILE_TAG = 1;
    private static final int QUIT_TAG = 2;
    private static final int BUFFER_SIZE = 65536;
    
    private int rank;
    private SocketChannel channel;
    private volatile boolean running = true;
    
    public static void main(String[] args) {
        if (args.length < 1) {
            System.out.println("Usage: java NIOFileTransfer <rank> [host] [port]");
            System.out.println("  rank: 0 for server, 1 for client");
            return;
        }
        
        int rank = Integer.parseInt(args[0]);
        String host = args.length > 1 ? args[1] : "localhost";
        int port = args.length > 2 ? Integer.parseInt(args[2]) : 8080;
        
        try {
            new NIOFileTransfer(rank, host, port).run();
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public NIOFileTransfer(int rank, String host, int port) throws IOException {
        this.rank = rank;
        
        if (rank == 0) {
            // Server
            System.out.println("=== Process 0 (Server) ===");
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(port));
            System.out.println("Waiting for connection on port " + port + "...");
            channel = serverChannel.accept();
            System.out.println("Client connected");
            serverChannel.close();
        } else {
            // Client
            System.out.println("=== Process 1 (Client) ===");
            channel = SocketChannel.open();
            channel.connect(new InetSocketAddress(host, port));
            System.out.println("Connected to server");
        }
        
        channel.configureBlocking(true);
    }
    
    private void run() throws Exception {
        Thread receiverThread = new Thread(this::receiveLoop);
        receiverThread.start();
        
        handleUserInput();
        
        receiverThread.join(2000);
        channel.close();
        
        if (rank == 0) {
            System.out.println("Server stopped");
        } else {
            System.out.println("Client stopped");
        }
    }
    
    private void handleUserInput() {
        Scanner sc = new Scanner(System.in);
        
        try {
            while (running) {
                if (!sc.hasNextLine()) break;
                String input = sc.nextLine();
                
                if (input.equals("/quit")) {
                    sendQuit();
                    running = false;
                    break;
                } else if (input.startsWith("/send ")) {
                    String filepath = input.substring(6).trim();
                    sendFile(filepath);
                } else {
                    sendMessage(input);
                }
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("Input error: " + e.getMessage());
            }
        } finally {
            sc.close();
        }
    }
    
    private synchronized void sendMessage(String message) {
        try {
            byte[] msgBytes = message.getBytes("UTF-8");
            ByteBuffer buffer = ByteBuffer.allocate(8 + msgBytes.length);
            buffer.putInt(MSG_TAG);
            buffer.putInt(msgBytes.length);
            buffer.put(msgBytes);
            buffer.flip();
            
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (Exception e) {
            System.err.println("Failed to send message: " + e.getMessage());
        }
    }
    
    private synchronized void sendQuit() {
        try {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.putInt(QUIT_TAG);
            buffer.putInt(0);
            buffer.flip();
            
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (Exception e) {
            System.err.println("Failed to send quit: " + e.getMessage());
        }
    }
    
    private synchronized void sendFile(String filepath) {
        try {
            File file = new File(filepath);
            if (!file.exists()) {
                System.out.println("File not found: " + filepath);
                return;
            }
            
            String filename = file.getName();
            byte[] filenameBytes = filename.getBytes("UTF-8");
            long fileSize = file.length();
            
            // Send header: tag + filename_length + filename + file_size
            ByteBuffer header = ByteBuffer.allocate(8 + 4 + filenameBytes.length + 8);
            header.putInt(FILE_TAG);
            header.putInt(filenameBytes.length);
            header.put(filenameBytes);
            header.putLong(fileSize);
            header.flip();
            
            while (header.hasRemaining()) {
                channel.write(header);
            }
            
            // Send file data using FileChannel
            //TODO: Resolve resource leak
            FileChannel fileChannel = new FileInputStream(file).getChannel();
            long transferred = 0;
            
            while (transferred < fileSize) {
                long count = fileChannel.transferTo(transferred, fileSize - transferred, channel);
                transferred += count;
            }
            fileChannel.close();
            
            // Wait for acknowledgment
            ByteBuffer ackBuffer = ByteBuffer.allocate(1);
            while (ackBuffer.hasRemaining()) {
                channel.read(ackBuffer);
            }
            ackBuffer.flip();
            
            if (ackBuffer.get() == 1) {
                if (file.delete()) {
                    System.out.println("Transferred: " + filename + " (" + fileSize + " bytes)");
                } else {
                    System.out.println("Sent but failed to delete: " + filename);
                }
            } else {
                System.out.println("Transfer failed");
            }
            
        } catch (Exception e) {
            System.err.println("Send file failed: " + e.getMessage());
        }
    }
    
    private void receiveLoop() {
        try {
            while (running) {
                // Read message header
                ByteBuffer header = ByteBuffer.allocate(8);
                int bytesRead = 0;
                while (bytesRead < 8) {
                    int n = channel.read(header);
                    if (n == -1) {
                        running = false;
                        return;
                    }
                    bytesRead += n;
                }
                header.flip();
                
                int tag = header.getInt();
                int length = header.getInt();
                
                if (tag == MSG_TAG) {
                    receiveMessage(length);
                } else if (tag == FILE_TAG) {
                    receiveFile(length);
                } else if (tag == QUIT_TAG) {
                    String peerName = (rank == 0) ? "Client" : "Server";
                    System.out.println(peerName + " disconnected");
                    running = false;
                    break;
                }
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("Receive error: " + e.getMessage());
            }
        }
    }
    
    private void receiveMessage(int length) throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        while (buffer.hasRemaining()) {
            channel.read(buffer);
        }
        buffer.flip();
        
        String message = new String(buffer.array(), "UTF-8");
        String peerName = (rank == 0) ? "Client" : "Server";
        System.out.println(peerName + ": " + message);
    }
    
    private synchronized void receiveFile(int filenameLength) {
        try {
            // Read filename
            ByteBuffer filenameBuffer = ByteBuffer.allocate(filenameLength);
            while (filenameBuffer.hasRemaining()) {
                channel.read(filenameBuffer);
            }
            filenameBuffer.flip();
            String filename = new String(filenameBuffer.array(), "UTF-8");
            
            // Read file size
            ByteBuffer sizeBuffer = ByteBuffer.allocate(8);
            while (sizeBuffer.hasRemaining()) {
                channel.read(sizeBuffer);
            }
            sizeBuffer.flip();
            long fileSize = sizeBuffer.getLong();
            
            // Receive file data
            String outputFilename = (rank == 1) ? "recv_" + filename : filename;
            //TODO: Resolve resource leak
            FileChannel fileChannel = new FileOutputStream(outputFilename).getChannel();
            
            long totalReceived = 0;
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
            
            while (totalReceived < fileSize) {
                buffer.clear();
                int limit = (int) Math.min(buffer.capacity(), fileSize - totalReceived);
                buffer.limit(limit);
                
                int bytesRead = channel.read(buffer);
                if (bytesRead == -1) break;
                
                buffer.flip();
                fileChannel.write(buffer);
                totalReceived += bytesRead;
            }
            fileChannel.close();
            
            // Send acknowledgment
            ByteBuffer ack = ByteBuffer.allocate(1);
            ack.put((byte) 1);
            ack.flip();
            while (ack.hasRemaining()) {
                channel.write(ack);
            }
            
            System.out.println("Received: " + outputFilename + " (" + totalReceived + " bytes)");
            
        } catch (Exception e) {
            System.err.println("Receive file failed: " + e.getMessage());
            try {
                ByteBuffer ack = ByteBuffer.allocate(1);
                ack.put((byte) 0);
                ack.flip();
                channel.write(ack);
            } catch (Exception ex) {}
        }
    }
}