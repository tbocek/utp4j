package ch.uzh.csg.utp4j.channels;

import static ch.uzh.csg.utp4j.channels.UtpSocketState.CLOSED;
import static ch.uzh.csg.utp4j.channels.UtpSocketState.SYN_SENT;
import static ch.uzh.csg.utp4j.data.bytes.UnsignedTypesUtil.MAX_USHORT;
import static ch.uzh.csg.utp4j.data.bytes.UnsignedTypesUtil.longToUint;
import static ch.uzh.csg.utp4j.data.bytes.UnsignedTypesUtil.longToUshort;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channel;
import java.util.Random;
import java.util.concurrent.locks.ReentrantLock;

import ch.uzh.csg.utp4j.channels.futures.UtpConnectFuture;
import ch.uzh.csg.utp4j.channels.futures.UtpReadFuture;
import ch.uzh.csg.utp4j.channels.futures.UtpWriteFuture;
import ch.uzh.csg.utp4j.channels.impl.UtpSocketChannelImpl;
import ch.uzh.csg.utp4j.channels.impl.conn.UtpConnectFutureImpl;
import ch.uzh.csg.utp4j.data.MicroSecondsTimeStamp;
import ch.uzh.csg.utp4j.data.UtpPacket;
import ch.uzh.csg.utp4j.data.UtpPacketUtils;


public abstract class UtpSocketChannel implements Closeable, Channel {
	

	

	private long connectionIdSending;
	protected MicroSecondsTimeStamp timeStamper = new MicroSecondsTimeStamp();
	private int sequenceNumber;
	private long connectionIdRecieving;
	protected SocketAddress remoteAddress;
	protected int ackNumber;
	protected DatagramSocket dgSocket;
	//an other thread might not see that we have set a reference
	protected volatile UtpConnectFutureImpl connectFuture = null;
	protected final ReentrantLock stateLock = new ReentrantLock();
	
	
	/**
	 * Current State of the Socket. {@link UtpSocketState}
	 */
	protected volatile UtpSocketState state = null;

	/**
	 * Sequencing begin.
	 */
	protected static int DEF_SEQ_START = 1;

	protected UtpSocketChannel() {}
	
	public static UtpSocketChannel open() throws IOException {
		UtpSocketChannelImpl c = new UtpSocketChannelImpl();
		try {
			c.setDgSocket(new DatagramSocket());
			c.setState(CLOSED);
		} catch (IOException exp) {
			throw new IOException("Could not open UtpSocketChannel: " + exp.getMessage());
		}
		return c;
	}
	
	public UtpConnectFuture connect(SocketAddress address) {
		UtpConnectFutureImpl future = null;
		stateLock.lock();
		try {
			try {
				future = new UtpConnectFutureImpl();
				connectFuture = future;
			} catch (InterruptedException e) {
				e.printStackTrace();
				// TODO Auto-generated catch block
			}
			try {

				connectImpl(future);
				setRemoteAddress(address);
				setupConnectionId();
				setSequenceNumber(DEF_SEQ_START);

				UtpPacket synPacket = UtpPacketUtils.createSynPacket();
				synPacket
						.setConnectionId(longToUshort(getConnectionIdRecieving()));
				synPacket.setTimestamp(timeStamper.utpTimeStamp());
				sendPacket(synPacket);
				setState(SYN_SENT);
				printState("[Syn send] ");
				incrementSequenceNumber();
				startConnectionTimeOutCounter(synPacket);
			} catch (IOException exp) {
				// DO NOTHING, let's try later with reconnect runnable
				// setSequenceNumber(DEF_SEQ_START);
				// setRemoteAddress(null);
				// abortImpl();
				// setState(CLOSED);
				// future.finished(exp);
			}
		} finally {
			stateLock.unlock();
		}

		return future;
	}
	
	protected abstract void startConnectionTimeOutCounter(UtpPacket synPacket);

	protected void printState(String msg) {
		String state = "[ConnID Sending: " + connectionIdSending + "] [ConnID Recv: " + connectionIdRecieving +
					   "] [SeqNr. " + sequenceNumber + "] [AckNr: " + ackNumber + "]";
		System.out.println(msg + state);
		
	}

	protected abstract void abortImpl();
	protected abstract void connectImpl(UtpConnectFutureImpl future);
	
	
	protected void incrementSequenceNumber() {
		int seqNumber = getSequenceNumber() + 1;
		if (seqNumber > MAX_USHORT) {
			seqNumber = 1;
		}
		setSequenceNumber(seqNumber);
	}
	
	protected void sendPacket(UtpPacket packet) throws IOException {
		if (packet != null) {
			byte[] utpPacketBytes = packet.toByteArray();
			int length = packet.getPacketLength();
			DatagramPacket pkt = new DatagramPacket(utpPacketBytes, length, getRemoteAdress());
			sendPacket(pkt);
		}
	}

	protected abstract void sendPacket(DatagramPacket pkt) throws IOException;

	private void setupConnectionId() {
		Random rnd = new Random();
		int max = (int) (MAX_USHORT - 1);
		long rndInt = rnd.nextInt(max);
		setConnectionIdRecieving(rndInt);
		setConnectionIdsending(rndInt + 1);
		
	}
	
	protected abstract UtpWriteFuture writeImpl(ByteBuffer src);
	
	
	public UtpWriteFuture write(ByteBuffer src) {
		return writeImpl(src);
	}


	public UtpReadFuture read(ByteBuffer dst) throws IOException {
		return readImpl(dst);
	}


	protected abstract UtpReadFuture readImpl(ByteBuffer dst) throws IOException;

	@Override
	public boolean isOpen() {
		return state != CLOSED;
	}

	@Override
	public void close() throws IOException {
		closeImpl();
		
	}
	
	protected abstract void closeImpl();
	
	public long getConnectionIdsending() {
		return connectionIdSending;
	}
	

	protected void setConnectionIdsending(long connectionIdSending) {
		this.connectionIdSending = connectionIdSending;
		
	}

	
	protected UtpPacket createAckPacket(UtpPacket pkt, int timedifference, long advertisedWindow) {
		UtpPacket ackPacket = new UtpPacket();
		setAckNrFromPacketSqNr(pkt);
		ackPacket.setAckNumber(longToUshort(getAckNumber()));
		
		ackPacket.setTimestampDifference(timedifference);
		ackPacket.setTimestamp(timeStamper.utpTimeStamp());
		ackPacket.setConnectionId(longToUshort(getConnectionIdsending()));
		ackPacket.setTypeVersion(UtpPacketUtils.ST_STATE);
		ackPacket.setWindowSize(longToUint(advertisedWindow));
		return ackPacket;		
	}
	
	protected abstract void setAckNrFromPacketSqNr(UtpPacket utpPacket);
	
	protected UtpPacket createDataPacket() {
		UtpPacket pkt = new UtpPacket();
		pkt.setSequenceNumber(longToUshort(getSequenceNumber()));
		incrementSequenceNumber();
		pkt.setAckNumber(longToUshort(getAckNumber()));
		pkt.setConnectionId(longToUshort(getConnectionIdsending()));
		pkt.setTimestamp(timeStamper.utpTimeStamp());
		return pkt;
	}


	public long getConnectionIdRecieving() {
		return connectionIdRecieving;
	}

	protected void setConnectionIdRecieving(long connectionIdRecieving) {
		this.connectionIdRecieving = connectionIdRecieving;
	}

	public UtpSocketState getState() {
		return state;
	}

	protected void setState(UtpSocketState state) {
		this.state = state;
	}

	public int getSequenceNumber() {
		return sequenceNumber;
	}

	protected void setSequenceNumber(int sequenceNumber) {
		this.sequenceNumber = sequenceNumber;
	}

	protected abstract void setRemoteAddress(SocketAddress remoteAdress);
	
	public SocketAddress getRemoteAdress() {
		return remoteAddress;
	}

	public int getAckNumber() {
		return ackNumber;
	}

	protected abstract void setAckNumber(int ackNumber);

	public DatagramSocket getDgSocket() {
		return dgSocket;
	}

	protected abstract void setDgSocket(DatagramSocket dgSocket); 
	
	public boolean isConnected() {
		return getState() == UtpSocketState.CONNECTED;
	}

	public abstract boolean isReading();
	public abstract boolean isWriting();


}