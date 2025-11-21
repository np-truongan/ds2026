import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    private static final int PORT = 8080;
    private static Set<PrintWriter> clients = Collections.synchronizedSet(new HashSet<>());
    
    public static void main(String[] args) {
        System.out.println("Server: Chat Server started on port " + PORT);
        
        try (ServerSocket serverSocket = new ServerSocket(PORT)) {
            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("Server: New client connected");
                
                // Handle each client in a new thread
                new Thread(() -> handleClient(socket)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private static void handleClient(Socket socket) {
        try {
            // Use DataInputStream for proper protocol handling
            DataInputStream dataIn = new DataInputStream(socket.getInputStream());
            PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
            
            // Add to clients list
            clients.add(out);
            broadcast("Server: New client joined the chat");
            
            while (true) {
                // Read message type first
                String messageType = dataIn.readUTF();
                
                if (messageType.equals("CHAT")) {
                    // Regular chat message
                    String chatMessage = dataIn.readUTF();
                    broadcast("Client: " + chatMessage);
                } else if (messageType.equals("FILE")) {
                    // File transfer
                    handleFileTransfer(dataIn, out, socket);
                } else if (messageType.equals("QUIT")) {
                    break;
                }
            }
        } catch (IOException e) {
            System.out.println("Server: Client disconnected");
        } finally {
            broadcast("Server: Client left the chat");
            try { socket.close(); } catch (IOException e) {}
        }
    }
    
    private static void handleFileTransfer(DataInputStream dataIn, PrintWriter out, Socket socket) {
        try {
            // Read file info
            String filename = dataIn.readUTF();
            long fileSize = dataIn.readLong();
            
            System.out.println("Server: Receiving file: " + filename + " (" + fileSize + " bytes)");
            
            // Receive file data
            FileOutputStream fileOut = new FileOutputStream(filename);
            byte[] buffer = new byte[4096];
            long bytesReceived = 0;
            int bytesRead;
            
            while (bytesReceived < fileSize && (bytesRead = dataIn.read(buffer)) != -1) {
                fileOut.write(buffer, 0, bytesRead);
                bytesReceived += bytesRead;
            }
            fileOut.close();
            
            System.out.println("Server: File received: " + filename);
            broadcast("Server: File shared: " + filename);
            
        } catch (Exception e) {
            System.out.println("Server: File transfer failed: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private static void broadcast(String message) {
        System.out.println(message);
        synchronized (clients) {
            for (PrintWriter client : clients) {
                client.println(message);
            }
        }
    }
}