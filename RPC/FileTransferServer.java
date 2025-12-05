import java.io.*;
import java.rmi.*;
import java.rmi.registry.*;
import java.rmi.server.UnicastRemoteObject;
import java.util.Scanner;

public class FileTransferServer extends UnicastRemoteObject implements FileTransferInterface {
    private static final int DEFAULT_PORT = 8080;
    private FileTransferInterface clientStub;
    private volatile boolean running = true;
    
    protected FileTransferServer() throws RemoteException {
        super();
    }
    
    @Override
    public void sendMessage(String message) throws RemoteException {
        if (message.equals("/quit")) {
            System.out.println("Client disconnected");
            running = false;
        } else {
            System.out.println("Client: " + message);
        }
    }
    
    @Override
    public boolean transferFile(String filename, byte[] data) throws RemoteException {
        try {
            File file = new File(filename);
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(data);
            fos.close();
            System.out.println("Received: " + filename + " (" + data.length + " bytes)");
            return true;
        } catch (IOException e) {
            System.err.println("Failed to save file: " + e.getMessage());
            return false;
        }
    }
    
    @Override
    public void disconnect() throws RemoteException {
        System.out.println("Client disconnected");
        running = false;
    }
    
    @Override
    public boolean ping() throws RemoteException {
        return true;
    }
    
    public void setClientStub(FileTransferInterface stub) {
        this.clientStub = stub;
    }
    
    private void handleUserInput() {
        Scanner sc = new Scanner(System.in);
        try {
            while (running) {
                if (!sc.hasNextLine()) break;
                String input = sc.nextLine();
                
                if (input.equals("/quit")) {
                    if (clientStub != null) {
                        clientStub.disconnect();
                    }
                    running = false;
                } else if (input.startsWith("/send ")) {
                    String filepath = input.substring(6).trim();
                    sendFileToClient(filepath);
                } else {
                    if (clientStub != null) {
                        clientStub.sendMessage(input);
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
    
    private void sendFileToClient(String filepath) {
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
            
            if (clientStub != null) {
                boolean success = clientStub.transferFile(file.getName(), data);
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
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        
        try {
            // Create and export the server object
            FileTransferServer server = new FileTransferServer();
            
            // Create RMI registry
            Registry registry = LocateRegistry.createRegistry(port);
            registry.rebind("FileTransferService", server);
            
            System.out.println("Server listening on port " + port);
            System.out.println("Waiting for client to connect...");
            
            // Wait for client to register
            while (server.clientStub == null) {
                try {
                    FileTransferInterface client = (FileTransferInterface) registry.lookup("FileTransferClient");
                    server.setClientStub(client);
                    System.out.println("Client connected");
                } catch (NotBoundException e) {
                    Thread.sleep(1000);
                }
            }
            
            // Handle user input
            server.handleUserInput();
            
            // Cleanup
            registry.unbind("FileTransferService");
            UnicastRemoteObject.unexportObject(server, true);
            System.out.println("Server stopped");
            
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}