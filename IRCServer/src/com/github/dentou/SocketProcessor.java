package com.github.dentou;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.util.*;

import static com.github.dentou.IRCUtils.createErrorReplies;
import static com.github.dentou.IRCUtils.createCommandResponse;
import static com.github.dentou.IRCUtils.createRelayMessage;
import static com.github.dentou.IRCConstants.*;
import static com.github.dentou.UserHandler.StatusCode;


/**
 *
 */
public class SocketProcessor implements Runnable {
    private Queue<IRCSocket> socketQueue;
    private Map<Long, IRCSocket> socketMap = new HashMap<Long, IRCSocket>();
    private long nextSocketId = 1024; // Id frm 0 to 1023 is reserved for servers

    private Selector readSelector;
    private Selector writeSelector;


    private Queue<IRCMessage> requestQueue = new LinkedList<>();
    private Queue<IRCMessage> sendQueue = new LinkedList<>();

    private UserHandler userHandler;

    private Set<IRCSocket> emptyToNonEmptySockets = new HashSet<>();
    private Set<IRCSocket> nonEmptyToEmptySockets = new HashSet<>();

    public SocketProcessor(Queue<IRCSocket> socketQueue) throws IOException {
        this.socketQueue = socketQueue;

        this.readSelector = Selector.open();
        this.writeSelector = Selector.open();

        this.userHandler = new UserHandler();
    }

    @Override
    public void run() {
        userHandler.createChannel("#All", "Everything is discussed");
        while (true) {
            try {
                registerNewSockets();
                readRequests();
                processRequests(); // todo implement processRequests()
            } catch (IOException e) {
                e.printStackTrace();
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }


    private void registerNewSockets() throws ClosedChannelException {

        while (true) {
            IRCSocket newSocket = this.socketQueue.poll();

            if (newSocket == null) {
                return;
            }

            newSocket.setId(nextSocketId++);
            this.socketMap.put(newSocket.getId(), newSocket);
            this.userHandler.addUser(newSocket.getId());
            SelectionKey key = newSocket.register(this.readSelector, SelectionKey.OP_READ);
            key.attach(newSocket);
        }
    }

    private void closeSocket(IRCSocket socket) throws IOException {
        System.out.println("Socket closed: " + socket.getId());
        this.socketMap.remove(socket.getId());
        SelectionKey readKey = socket.getSelectionKey(readSelector);
        readKey.attach(null);
        readKey.cancel();
        readKey.channel().close();
    }

    private void readFromSocket(SelectionKey key) throws IOException {
        IRCSocket socket = (IRCSocket) key.attachment();
        List<IRCMessage> requests = socket.getMessages();
        System.out.println("(readFromSocket) Requests from socket: " + requests);

        if (requests.size() > 0) {
            for (IRCMessage request : requests) {
                if (request.getMessage() != null & !request.getMessage().trim().isEmpty()) {
                    this.requestQueue.add(request);
                }
            }
        }

        if (socket.isEndOfStreamReached()) {
            closeSocket(socket);
        }
    }

    private void readRequests() throws IOException {
        int readReady = this.readSelector.selectNow();

        if (readReady > 0) {
            Set<SelectionKey> selectedKeys = this.readSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();

                readFromSocket(key);

                keyIterator.remove();
            }
            selectedKeys.clear();
        }
    }


    private void processRequests() throws IOException {
        while (true) {
            IRCMessage request = requestQueue.poll();
            if (request == null) {
                break;
            }
            processRequest(request);
        }
        // todo write to socket
        writeResponses();

    }


    private void processRequest(IRCMessage request) throws IOException { // Process command and add response to send queue
        if (request.getMessage() == null || request.getMessage().trim() == "") {
            return;
        }
        List<String> requestParts = IRCUtils.parseRequest(request.getMessage());
        System.out.println("Request " + requestParts);
        String command = requestParts.get(0);

        switch (command) {
            case "NICK": // NICK <nick>
                handleNickCommand(request, requestParts);
                break;
            case "USER":
                handleUSerCommand(request, requestParts);
                break;
            case "QUIT":
                handleQuitCommand(request, requestParts);
                break;
            case "PRIVMSG":
                handlePrivmsgCommand(request, requestParts);
                break;
            case "PING":
                handlePingCommand(request, requestParts);
                break;
            case "WHOIS":
                handleWhoisCommand(request, requestParts);
                break;
            case "JOIN":
                handleJoinCommand(request, requestParts);//todo implements channel
                break;
            default:
                sendQueue.add(createErrorReplies(ErrorReplies.ERR_UNKNOWNCOMMAND, request, requestParts, userHandler));
                break;
        }

    }

    private void handleNickCommand(IRCMessage request, List<String> requestParts) {
        if (requestParts.size() < 2) {
            sendQueue.add(createErrorReplies(ErrorReplies.ERR_NONICKNAMEGIVEN, request, requestParts, userHandler));
            return;
        }
        boolean isRegisteredBefore = userHandler.isRegistered(request.getFromId());
        StatusCode statusCode = userHandler.changeUserInfo(request.getFromId(), "nick", requestParts.get(1));
        if (statusCode == StatusCode.NICK_DUPLICATE) {
            sendQueue.add(createErrorReplies(ErrorReplies.ERR_NICKNAMEINUSE, request, requestParts, userHandler));
            return;
        }
        boolean isRegisteredAfter = userHandler.isRegistered(request.getFromId());
        if (!isRegisteredBefore && isRegisteredAfter) {
            System.out.println("Welcome sent");
            sendQueue.add(createCommandResponse(CommandResponse.RPL_WELCOME, request, requestParts, userHandler));
        }
    }

    private void handleUSerCommand(IRCMessage request, List<String> requestParts) {
        if (requestParts.size() < 5) {
            sendQueue.add(createErrorReplies(ErrorReplies.ERR_NEEDMOREPARAMS, request, requestParts, userHandler));
            return;
        }
        boolean isRegisteredBefore = userHandler.isRegistered(request.getFromId());
        StatusCode statusCode = userHandler.changeUserInfo(request.getFromId(), "userName", requestParts.get(1));
        userHandler.changeUserInfo(request.getFromId(), "userFullName", requestParts.get(4));
        boolean isRegisteredAfter = userHandler.isRegistered(request.getFromId());
        if (!isRegisteredBefore && isRegisteredAfter) {
            System.out.println("Welcome sent");
            sendQueue.add(createCommandResponse(CommandResponse.RPL_WELCOME, request, requestParts, userHandler));
        }


    }

    private void handleQuitCommand(IRCMessage request, List<String> requestParts) throws IOException {
        StatusCode statusCode = userHandler.removeUser(request.getFromId());
        IRCSocket ircSocket = socketMap.get(request.getFromId());
        closeSocket(ircSocket);
    }

    private void handlePrivmsgCommand(IRCMessage request, List<String> requestParts) {
        if (requestParts.size() <= 1) {
            sendQueue.add(createErrorReplies(ErrorReplies.ERR_NEEDMOREPARAMS, request, requestParts, userHandler));
            return;
        }
        if (requestParts.size() < 3) { // Only PRIVMSG and a parameter given
            if (request.getMessage().contains(":")) { // If only text is given, no nickname
                sendQueue.add(createErrorReplies(ErrorReplies.ERR_NONICKNAMEGIVEN, request, requestParts, userHandler));
            } else { // Only nickname is given, no text to send
                sendQueue.add(createErrorReplies(ErrorReplies.ERR_NOTEXTTOSEND, request, requestParts, userHandler));
            }
            return;
        }
        if (!userHandler.isRegistered(request.getFromId())){
            sendQueue.add(createErrorReplies(ErrorReplies.ERR_NOTREGISTERED, request, requestParts, userHandler));
            return;
        }
        long toId = userHandler.getUserId(requestParts.get(1));
        if (toId == -1) {
            sendQueue.add(createErrorReplies(ErrorReplies.ERR_NOSUCHNICK, request, requestParts, userHandler));
            return;
        }
        sendQueue.add(createRelayMessage(request, userHandler, toId));
    }

    private void handlePingCommand(IRCMessage request, List<String> requestParts) {
        sendQueue.add(new IRCMessage("PONG\r\n", 0, request.getFromId()));
    }

    private void handleWhoisCommand(IRCMessage request, List<String> requestParts) {
        if (!userHandler.isRegistered(request.getFromId())) {
            sendQueue.add(createErrorReplies(ErrorReplies.ERR_NOTREGISTERED, request, requestParts, userHandler));
            return;
        }
        if (requestParts.size() < 2) {
            // Not sending ERR_NEEDMOREPARAMS
            return;
        }
        if (!userHandler.containsNick(requestParts.get(1))) {
            sendQueue.add(createErrorReplies(ErrorReplies.ERR_NOSUCHNICK, request, requestParts, userHandler));
            return;
        }
        sendQueue.add(createCommandResponse(CommandResponse.RPL_WHOISUSER, request, requestParts, userHandler));

    }

    private void handleJoinCommand(IRCMessage request, List<String> requestParts) {
        if (requestParts.size() < 2) {
            sendQueue.add(createErrorReplies(ErrorReplies.ERR_NEEDMOREPARAMS, request, requestParts, userHandler));
            return;
        }
        IRCChannel channel = userHandler.getChannel(requestParts.get(1));
        if (channel == null) {
            // todo check if channel name has correct form and create new channel
            return;
        }
        userHandler.addUserToChannel(request.getFromId(), requestParts.get(1));
        if (channel.getTopic() != null || !channel.getTopic().isEmpty()) {
            sendQueue.add(createCommandResponse(CommandResponse.RPL_TOPIC, request, requestParts, userHandler));
        }
        sendQueue.add(createCommandResponse(CommandResponse.RPL_NAMEREPLY, request, requestParts, userHandler));
        sendQueue.add(createCommandResponse(CommandResponse.RPL_ENDOFNAMES, request, requestParts, userHandler));
        return;
    }

    private void writeResponses() throws IOException {
        pullAllRequest();

        cancelEmptySockets();

        registerNonEmptySockets();

        int writeReady = writeSelector.selectNow();

        if (writeReady > 0) {
            Set<SelectionKey> selectedKeys = writeSelector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();

                IRCSocket ircSocket = (IRCSocket) key.attachment();

                ircSocket.sendMessages();

                if (ircSocket.isWriterEmpty()) {
                    this.nonEmptyToEmptySockets.add(ircSocket);
                }

                keyIterator.remove();
            }

            selectedKeys.clear();
        }
    }

    private void registerNonEmptySockets() throws ClosedChannelException {
        for (IRCSocket ircSocket : emptyToNonEmptySockets) {
            SelectionKey key = ircSocket.register(this.writeSelector, SelectionKey.OP_WRITE);
            key.attach(ircSocket);
        }
        emptyToNonEmptySockets.clear();
    }

    private void cancelEmptySockets() {
        for (IRCSocket ircSocket : nonEmptyToEmptySockets) {
            SelectionKey key = ircSocket.getSelectionKey(this.writeSelector);
            key.cancel();
        }
        nonEmptyToEmptySockets.clear();
    }

    private void pullAllRequest() {
        while (true) {
            IRCMessage outMessage = sendQueue.poll();
            if (outMessage == null) {
                return;
            }
            IRCSocket ircSocket = socketMap.get(outMessage.getToId());

            if (ircSocket != null) {
                if (ircSocket.isWriterEmpty()) {
                    nonEmptyToEmptySockets.remove(ircSocket);
                    emptyToNonEmptySockets.add(ircSocket);
                }
                ircSocket.enqueue(outMessage.getMessage());

            }
        }

    }


}
