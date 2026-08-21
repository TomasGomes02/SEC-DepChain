package communication;

import utils.Utils;

import java.io.*;
import java.net.*;

public class FairLossLink {
  protected DatagramSocket socket;
  protected SocketAddress address;

  public FairLossLink(DatagramSocket socket, SocketAddress address) {
      this.socket = socket;
      this.address = address;
  }

  public void send(Object msg) throws IOException {
    byte[] data = Utils.serialize(msg);
    DatagramPacket packet = new DatagramPacket(data, data.length, this.address);
    socket.send(packet);
  }
}