import java.io.*;
import java.net.*;
import java.util.*;

public class Server {
    private static final int SERVER_PORT = 8080;

    private static final int BUFFER_SIZE = 4096;
    private Socket client;
    private DataInputStream dis;
    private DataOutputStream dos;
    private volatile boolean running = true;

    public void start(int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(port));
        
        System.out.println("Server listening on port " + port);
        client = serverSocket.accept();
        System.out.println("Client connected: " + client.getInetAddress().getHostAddress());
        
        dis = new DataInputStream(client.getInputStream());
        dos = new DataOutputStream(client.getOutputStream());
        
        new Thread(this::receive).start();
        send();
        
        cleanup();
        serverSocket.close();
    }
    
    private void send() {
        Scanner sc = new Scanner(System.in);
        try {
            while (running) {
                String input = sc.nextLine();
                if (input.equals("/quit")) {
                    dos.writeUTF("MSG:/quit");
                    running = false;
                } else if (input.startsWith("/send ")) {
                    sendFile(input.substring(6).trim());
                } else {
                    dos.writeUTF("MSG:" + input);
                }
                dos.flush();
            }
        } catch (IOException e) {
            if (running) System.err.println("Connection error");
        }
        sc.close();
    }
    
    private void receive() {
        try {
            while (running) {
                String data = dis.readUTF();
                if (data.startsWith("MSG:")) {
                    String msg = data.substring(4);
                    if (msg.equals("/quit")) {
                        System.out.println("Client disconnected");
                        running = false;
                    } else {
                        System.out.println("Client: " + msg);
                    }
                } else if (data.equals("FILE:")) {
                    receiveFile();
                }
            }
        } catch (IOException e) {
            if (running) System.err.println("Connection lost");
        }
    }
    
    private void sendFile(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                System.out.println("File not found: " + path);
                return;
            }
            
            dos.writeUTF("FILE:");
            dos.writeUTF(file.getName());
            dos.writeLong(file.length());
            
            FileInputStream fis = new FileInputStream(file);
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                dos.write(buffer, 0, read);
            }
            dos.flush();
            fis.close();
            
            String ack = dis.readUTF();
            if (ack.equals("ACK")) {
                if (file.delete()) {
                    System.out.println("Transferred: " + file.getName());
                } else {
                    System.out.println("Sent but failed to delete: " + file.getName());
                }
            }
        } catch (IOException e) {
            System.err.println("Send failed: " + e.getMessage());
        }
    }
    
    private void receiveFile() {
        try {
            String filename = dis.readUTF();
            long size = dis.readLong();
            
            FileOutputStream fos = new FileOutputStream("recv_" + filename);
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = size;
            int read;
            
            while (remaining > 0 && (read = dis.read(buffer, 0, (int)Math.min(buffer.length, remaining))) != -1) {
                fos.write(buffer, 0, read);
                remaining -= read;
            }
            fos.close();
            
            dos.writeUTF("ACK");
            dos.flush();
            
            File tempFile = new File("recv_" + filename);
            File finalFile = new File(filename);
            if (tempFile.renameTo(finalFile)) {
                System.out.println("Received: " + filename);
            } else {
                System.out.println("Received but rename failed: recv_" + filename);
            }
        } catch (IOException e) {
            System.err.println("Receive failed: " + e.getMessage());
        }
    }
    
    private void cleanup() {
        try {
            if (dis != null) dis.close();
            if (dos != null) dos.close();
            if (client != null) client.close();
        } catch (IOException e) {}
    }
    
    public static void main(String[] args) {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : SERVER_PORT;
        try {
            new Server().start(port);
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        }
    }
}