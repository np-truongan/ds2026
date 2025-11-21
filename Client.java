import java.io.*;
import java.net.*;
import java.util.Scanner;

public class Client {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 8080;
    
    public static void main(String[] args) {
        try {
            Socket socket = new Socket(SERVER_HOST, SERVER_PORT);
            System.out.println("Client: Connected to chat server!");
            
            // Start thread for receiving messages from server
            startServerReader(socket);
            
            // Handle user input
            handleUserInput(socket);
            
        } catch (IOException e) {
            System.out.println("Client: Cannot connect to server: " + e.getMessage());
        }
    }
    
    private static void handleUserInput(Socket socket) {
        try {
            DataOutputStream dataOut = new DataOutputStream(socket.getOutputStream());
            Scanner scanner = new Scanner(System.in);
            
            System.out.println("Client: Type messages to chat, /file <filename> to send file, /quit to exit");
            
            while (true) {
                String input = scanner.nextLine();
                
                if (input.equals("/quit")) {
                    dataOut.writeUTF("QUIT");
                    dataOut.flush();
                    break;
                } else if (input.startsWith("/file ")) {
                    sendFile(input.substring(6), dataOut);
                } else {
                    dataOut.writeUTF("CHAT");
                    dataOut.writeUTF(input);
                    dataOut.flush();
                }
            }
            
            scanner.close();
            socket.close();
            System.out.println("Client: Disconnected from server");
        } catch (IOException e) {
            System.out.println("Client: Connection error: " + e.getMessage());
        }
    }
    
    private static void startServerReader(Socket socket) {
        new Thread(() -> {
            try {
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String message;
                while ((message = in.readLine()) != null) {
                    System.out.println(message);
                }
            } catch (IOException e) {
                System.out.println("Client: Disconnected from server");
            }
        }).start();
    }
    
    private static void sendFile(String filename, DataOutputStream dataOut) {
        try {
            File file = new File(filename);
            if (!file.exists()) {
                System.out.println("Client: File not found: " + filename);
                return;
            }
            
            System.out.println("Client: Sending file: " + filename);
            
            // Send file protocol
            dataOut.writeUTF("FILE");
            dataOut.writeUTF(file.getName());
            dataOut.writeLong(file.length());
            dataOut.flush();
            
            // Send file data
            FileInputStream fileIn = new FileInputStream(file);
            byte[] buffer = new byte[4096];
            int bytesRead;
            
            while ((bytesRead = fileIn.read(buffer)) != -1) {
                dataOut.write(buffer, 0, bytesRead);
            }
            dataOut.flush();
            fileIn.close();
            
            System.out.println("Client: File sent successfully: " + filename);
            
        } catch (IOException e) {
            System.out.println("Client: Error sending file: " + e.getMessage());
            e.printStackTrace();
        }
    }
}