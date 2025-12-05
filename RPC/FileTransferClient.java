import java.io.*;
import java.rmi.*;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.Scanner;

public class FileTransferClient extends UnicastRemoteObject implements FileTransferInterface {
    private static final String DEFAULT_HOST = "127.0.0.1";
    private static final int DEFAULT_PORT = 8080;
    private FileTransferInterface serverStub;
    private volatile boolean running = true;
    
    protected FileTransferClient() throws RemoteException {
        super();
    }
    
    @Override
    public void sendMessage(String message) throws RemoteException {
        if (message.equals("/quit")) {
            System.out.println("Server disconnected");
            running = false;
        } else {
            System.out.println("Server: " + message);
        }
    }
    
    @Override
    public boolean transferFile(String filename, byte[] data) throws RemoteException {
        try {
            File file = new File("recv_" + filename);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data);
            fos.close();
            System.out.println("Received: recv_" + filename + " (" + data.length + " bytes)");
            return true;
        } catch (IOException e) {
            System.err.println("Failed to save file: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public void disconnect() throws RemoteException {
        System.out.println("Server disconnected");
        running = false;
    }
    
    @Override
    public boolean ping() throws RemoteException {
        return true;
    }
    
    private void handleUserInput() {
        Scanner sc = new Scanner(System.in);
        try {
            while (running) {
                if (!sc.hasNextLine()) break;
                String input = sc.nextLine();
                
                if (input.equals("/quit")) {
                    if (serverStub != null) {
                        serverStub.disconnect();
                    }
                    running = false;
                } else if (input.startsWith("/send ")) {
                    String filepath = input.substring(6).trim();
                    sendFileToServer(filepath);
                } else {
                    if (serverStub != null) {
                        serverStub.sendMessage(input);
                    }
                }
            }
        } catch (Exception e) {
            if (running) {
                System.err.println("Error: " + e.getMessage());
            }
        } finally {
            sc.close();
        }
    }
    
    private void sendFileToServer(String filepath) {
        try {
            File file = new File(filepath);
            if (!file.exists()) {
                System.out.println("File not found: " + filepath);
                return;
            }
            
            FileInputStream fis = new FileInputStream(file);
            byte[] data = new byte[(int) file.length()];
            fis.read(data);
            fis.close();
            
            if (serverStub != null) {
                boolean success = serverStub.transferFile(file.getName(), data);
                if (success) {
                    if (file.delete()) {
                        System.out.println("Transferred: " + file.getName());
                    } else {
                        System.out.println("Sent but failed to delete: " + file.getName());
                    }
                } else {
                    System.out.println("Transfer failed");
                }
            }
        } catch (Exception e) {
            System.err.println("Send failed: " + e.getMessage());
        }
    }
    
    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : DEFAULT_HOST;
        int port = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_PORT;
        
        try {
            // Create and export the client object
            FileTransferClient client = new FileTransferClient();
            
            // Connect to the server's registry
            System.out.println("Connecting to " + host + ":" + port);
            Registry registry = LocateRegistry.getRegistry(host, port);
            
            // Lookup the server
            client.serverStub = (FileTransferInterface) registry.lookup("FileTransferService");
            System.out.println("Connected to server");
            
            // Register client in the registry so server can call back
            registry.rebind("FileTransferClient", client);
            
            // Handle user input
            client.handleUserInput();
            
            // Cleanup
            registry.unbind("FileTransferClient");
            UnicastRemoteObject.unexportObject(client, true);
            System.out.println("Client stopped");
            
        } catch (Exception e) {
            System.err.println("Client error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}