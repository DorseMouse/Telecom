package com.github.dentou;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Scanner;

public class IRCClient {
    private IRCSocket ircSocket;

    private SocketListener socketListener;
    private SocketSpeaker socketSpeaker;

    private volatile boolean isExited = false;

    public IRCClient(String serverAddress, int serverPort) throws IOException {
        SocketChannel socketChannel = SocketChannel.open(new InetSocketAddress(serverAddress, serverPort));
        this.ircSocket = new IRCSocket(socketChannel, false);
    }

    public void start() throws IOException{
        this.socketListener = new SocketListener(ircSocket, this);
        this.socketSpeaker = new SocketSpeaker(ircSocket, this);

        Thread listenerThread = new Thread(socketListener);
        Thread speakerThread = new Thread(socketSpeaker);

        listenerThread.start();
        speakerThread.start();

        while (true) {
            if (isExited) {
                socketSpeaker.close();
                socketListener.close();
                ircSocket.close();
                return;
            }
        }



    }

    public void exit() {
        isExited = true;
    }
}
