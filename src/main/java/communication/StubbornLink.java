package communication;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class StubbornLink {

	private final FairLossLink fll;
	private final ScheduledExecutorService scheduler;

	public StubbornLink(DatagramSocket socket, SocketAddress address) {
		this.fll = new FairLossLink(socket, address);
		this.scheduler = Executors.newScheduledThreadPool(2);
	}

	public AtomicBoolean send(Object msg){
        AtomicBoolean isCanceled = new AtomicBoolean(false);
        try {
            this.fll.send(msg);
        } catch (IOException e) {
                if (!fll.socket.isClosed())
                    throw new RuntimeException(e);
        }
        scheduler.schedule(
                () -> resendMessage(msg, isCanceled), 1000, TimeUnit.MILLISECONDS
        );
        return isCanceled;
	}
    public void send(Object msg, int retries){
        for (int i = 0; i < retries; i++){
            try {
                this.fll.send(msg);
            } catch (IOException e) {
                if (!fll.socket.isClosed())
                    throw new RuntimeException(e);
            }
        }
    }

    public void resendMessage(Object msg, AtomicBoolean isCanceled){
        if (!isCanceled.get()){
            try {
                this.fll.send(msg);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            scheduler.schedule(
                    () -> resendMessage(msg, isCanceled), 1000, TimeUnit.MILLISECONDS
            );
        }
    }
}