import java.rmi.Remote;
import java.rmi.RemoteException;

public interface FileTransferInterface extends Remote {
    /**
     * Send a text message to the remote peer
     */
    void sendMessage(String message) throws RemoteException;
    
    /**
     * Transfer a file to the remote peer
     * @param filename Name of the file
     * @param data File content as byte array
     * @return true if file was received successfully
     */
    boolean transferFile(String filename, byte[] data) throws RemoteException;
    
    /**
     * Notify the remote peer that this peer is disconnecting
     */
    void disconnect() throws RemoteException;
    
    /**
     * Check if the remote peer is still alive
     */
    boolean ping() throws RemoteException;
}