package net.sf.freecol.server.model;

import java.net.Socket;
import java.util.List;

import net.sf.freecol.common.networking.Connection;

public class Extracted_ServerPlayer {
	/** The network socket to the player's client. */
	private Socket socket;
	/** The connection for this player. */
	private Connection connection;
	private boolean connected;
	/** Players with respect to which stance has changed. */
	private List<ServerPlayer> stanceDirty;

	public Extracted_ServerPlayer(boolean connected,
			List<ServerPlayer> stanceDirty) {
		this.connected = connected;
		this.stanceDirty = stanceDirty;
	}

	public Socket getSocket() {
		return socket;
	}

	public void setSocket(Socket socket) {
		this.socket = socket;
	}

	public Connection getConnection() {
		return connection;
	}

	public void setConnection(Connection connection) {
		this.connection = connection;
	}

	public boolean isConnected() {
		return connected;
	}

	public void setConnected(boolean connected) {
		this.connected = connected;
	}

	public List<ServerPlayer> getStanceDirty() {
		return stanceDirty;
	}

	public void setStanceDirty(List<ServerPlayer> stanceDirty) {
		this.stanceDirty = stanceDirty;
	}
}