package games.strategy.net;

import static com.google.common.base.Preconditions.checkNotNull;

import java.io.IOException;
import java.io.Serializable;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import javax.annotation.Nullable;

import games.strategy.net.nio.NioSocket;
import games.strategy.net.nio.NioSocketListener;
import games.strategy.net.nio.QuarantineConversation;
import games.strategy.net.nio.ServerQuarantineConversation;
import lombok.extern.java.Log;

/**
 * A Messenger that can have many clients connected to it.
 */
@Log
public class ServerMessenger implements IServerMessenger, NioSocketListener {
  private final Selector acceptorSelector;
  private final ServerSocketChannel socketChannel;
  private final Node node;
  private boolean shutdown = false;
  private final NioSocket nioSocket;
  private final List<IMessageListener> listeners = new CopyOnWriteArrayList<>();
  private final List<IConnectionChangeListener> connectionListeners = new CopyOnWriteArrayList<>();
  private boolean acceptNewConnection = false;
  private ILoginValidator loginValidator;
  // all our nodes
  private final Map<INode, SocketChannel> nodeToChannel = new ConcurrentHashMap<>();
  private final Map<SocketChannel, INode> channelToNode = new ConcurrentHashMap<>();
  private final Map<String, String> cachedMacAddresses = new ConcurrentHashMap<>();
  private final Set<String> miniBannedIpAddresses = new ConcurrentSkipListSet<>();
  private final Set<String> miniBannedMacAddresses = new ConcurrentSkipListSet<>();
  // The following code is used in hosted lobby games by the host for player mini-banning
  private final Map<String, String> playersThatLeftMacsLast10 = new ConcurrentHashMap<>();

  public ServerMessenger(final String name, final int port, final IObjectStreamFactory objectStreamFactory)
      throws IOException {
    socketChannel = ServerSocketChannel.open();
    socketChannel.configureBlocking(false);
    socketChannel.socket().setReuseAddress(true);
    socketChannel.socket().bind(new InetSocketAddress(port), 10);
    final int boundPort = socketChannel.socket().getLocalPort();
    nioSocket = new NioSocket(objectStreamFactory, this);
    acceptorSelector = Selector.open();
    node = new Node(name, IpFinder.findInetAddress(), boundPort);
    new Thread(new ConnectionHandler(), "Server Messenger Connection Handler").start();
  }

  @Override
  public void setLoginValidator(final ILoginValidator loginValidator) {
    this.loginValidator = loginValidator;
  }

  @Override
  public ILoginValidator getLoginValidator() {
    return loginValidator;
  }

  @Override
  public void addMessageListener(final IMessageListener listener) {
    listeners.add(listener);
  }

  @Override
  public Set<INode> getNodes() {
    final Set<INode> nodes = new HashSet<>(nodeToChannel.keySet());
    nodes.add(node);
    return nodes;
  }

  @Override
  public synchronized void shutDown() {
    if (!shutdown) {
      shutdown = true;
      nioSocket.shutDown();
      try {
        socketChannel.close();
      } catch (final Exception e) {
        // ignore
      }
      if (acceptorSelector != null) {
        acceptorSelector.wakeup();
      }
    }
  }

  @Override
  public boolean isConnected() {
    return !shutdown;
  }

  @Override
  public void send(final Serializable msg, final INode to) {
    if (shutdown) {
      return;
    }
    final SocketChannel socketChannel = nodeToChannel.get(to);
    // the socket was removed
    if (socketChannel == null) {
      // the socket has not been added yet
      return;
    }
    nioSocket.send(socketChannel, new MessageHeader(to, node, msg));
  }

  @Override
  public @Nullable String getPlayerMac(final String name) {
    return Optional.ofNullable(cachedMacAddresses.get(name))
        .orElseGet(() -> playersThatLeftMacsLast10.get(name));
  }

  /**
   * Invoked when the node with the specified unique name has successfully logged in. Note that {@code uniquePlayerName}
   * is the node name and may not be identical to the name of the player associated with the node (see
   * {@link #getUniqueName(String)}.
   */
  public void notifyPlayerLogin(final String uniquePlayerName, final String mac) {
    cachedMacAddresses.put(uniquePlayerName, mac);
  }

  private void notifyPlayerRemoval(final INode node) {
    playersThatLeftMacsLast10.put(node.getName(), cachedMacAddresses.get(node.getName()));
    if (playersThatLeftMacsLast10.size() > 10) {
      playersThatLeftMacsLast10.remove(playersThatLeftMacsLast10.entrySet().iterator().next().toString());
    }
    cachedMacAddresses.remove(node.getName());
  }

  @Override
  public void messageReceived(final MessageHeader msg, final SocketChannel channel) {
    final INode expectedReceive = channelToNode.get(channel);
    if (!expectedReceive.equals(msg.getFrom())) {
      throw new IllegalStateException("Expected: " + expectedReceive + " not: " + msg.getFrom());
    }
    if (msg.getTo() == null) {
      forwardBroadcast(msg);
      notifyListeners(msg);
    } else if (msg.getTo().equals(node)) {
      notifyListeners(msg);
    } else {
      forward(msg);
    }
  }

  @Override
  public boolean isPlayerBanned(final String ip, final String mac) {
    return miniBannedIpAddresses.contains(ip) || miniBannedMacAddresses.contains(mac);
  }

  @Override
  public void banPlayer(final String ip, final String mac) {
    miniBannedIpAddresses.add(ip);
    miniBannedMacAddresses.add(mac);
  }

  private void forward(final MessageHeader msg) {
    if (shutdown) {
      return;
    }
    final SocketChannel socketChannel = nodeToChannel.get(msg.getTo());
    if (socketChannel == null) {
      throw new IllegalStateException("No channel for:" + msg.getTo());
    }
    nioSocket.send(socketChannel, msg);
  }

  private void forwardBroadcast(final MessageHeader msg) {
    if (shutdown) {
      return;
    }
    final SocketChannel fromChannel = nodeToChannel.get(msg.getFrom());
    final List<SocketChannel> nodes = new ArrayList<>(nodeToChannel.values());
    log.finest(() -> "broadcasting to" + nodes);
    for (final SocketChannel channel : nodes) {
      if (channel != fromChannel) {
        nioSocket.send(channel, msg);
      }
    }
  }

  private boolean isNameTaken(final String nodeName) {
    return getNodes().stream()
        .map(INode::getName)
        .anyMatch(nodeName::equalsIgnoreCase);
  }

  /**
   * Returns a node name, based on the specified node name, that is unique across all nodes. The node name is made
   * unique by adding a numbered suffix to the existing node name. For example, for the second node with the name "foo",
   * this method will return "foo (1)".
   */
  public String getUniqueName(final String name) {
    String currentName = name;
    if (currentName.length() > 50) {
      currentName = currentName.substring(0, 50);
    }
    if (currentName.length() < 2) {
      currentName = "aa" + currentName;
    }
    synchronized (node) {
      if (isNameTaken(currentName)) {
        int i = 1;
        while (true) {
          final String newName = currentName + " (" + i + ")";
          if (!isNameTaken(newName)) {
            currentName = newName;
            break;
          }
          i++;
        }
      }
    }
    return currentName;
  }

  private void notifyListeners(final MessageHeader msg) {
    for (final IMessageListener listener : listeners) {
      listener.messageReceived(msg.getMessage(), msg.getFrom());
    }
  }

  @Override
  public void addErrorListener(final IMessengerErrorListener listener) {}

  @Override
  public void removeErrorListener(final IMessengerErrorListener listener) {}

  @Override
  public void addConnectionChangeListener(final IConnectionChangeListener listener) {
    connectionListeners.add(listener);
  }

  @Override
  public void removeConnectionChangeListener(final IConnectionChangeListener listener) {
    connectionListeners.remove(listener);
  }

  private void notifyConnectionsChanged(final boolean added, final INode node) {
    for (final IConnectionChangeListener listener : connectionListeners) {
      if (added) {
        listener.connectionAdded(node);
      } else {
        listener.connectionRemoved(node);
      }
    }
  }

  @Override
  public void setAcceptNewConnections(final boolean accept) {
    acceptNewConnection = accept;
  }

  @Override
  public INode getLocalNode() {
    return node;
  }

  private class ConnectionHandler implements Runnable {
    @Override
    public void run() {
      try {
        socketChannel.register(acceptorSelector, SelectionKey.OP_ACCEPT);
      } catch (final ClosedChannelException e) {
        log.log(Level.SEVERE, "socket closed", e);
        shutDown();
      }
      while (!shutdown) {
        try {
          acceptorSelector.select();
        } catch (final IOException e) {
          log.log(Level.SEVERE, "Could not accept on server", e);
          shutDown();
        }
        if (shutdown) {
          continue;
        }
        final Set<SelectionKey> keys = acceptorSelector.selectedKeys();
        final Iterator<SelectionKey> iter = keys.iterator();
        while (iter.hasNext()) {
          final SelectionKey key = iter.next();
          iter.remove();
          if (key.isAcceptable() && key.isValid()) {
            final ServerSocketChannel serverSocketChannel = (ServerSocketChannel) key.channel();
            // Accept the connection and make it non-blocking
            SocketChannel socketChannel = null;
            try {
              socketChannel = serverSocketChannel.accept();
              if (socketChannel == null) {
                continue;
              }
              socketChannel.configureBlocking(false);
              socketChannel.socket().setKeepAlive(true);
            } catch (final IOException e) {
              log.log(Level.FINE, "Could not accept channel", e);
              try {
                if (socketChannel != null) {
                  socketChannel.close();
                }
              } catch (final IOException e2) {
                log.log(Level.FINE, "Could not close channel", e2);
              }
              continue;
            }
            // we are not accepting connections
            if (!acceptNewConnection) {
              try {
                socketChannel.close();
              } catch (final IOException e) {
                log.log(Level.FINE, "Could not close channel", e);
              }
              continue;
            }
            final ServerQuarantineConversation conversation = new ServerQuarantineConversation(
                loginValidator,
                socketChannel,
                nioSocket,
                ServerMessenger.this);
            nioSocket.add(socketChannel, conversation);
          } else if (!key.isValid()) {
            key.cancel();
          }
        }
      }
    }
  }

  @Override
  public boolean isServer() {
    return true;
  }

  @Override
  public void removeConnection(final INode nodeToRemove) {
    if (nodeToRemove.equals(this.node)) {
      throw new IllegalArgumentException("Cant remove ourself!");
    }
    notifyPlayerRemoval(nodeToRemove);
    final SocketChannel channel = nodeToChannel.remove(nodeToRemove);
    if (channel == null) {
      log.warning("Could not remove connection to node:" + nodeToRemove);
      return;
    }
    channelToNode.remove(channel);
    nioSocket.close(channel);
    notifyConnectionsChanged(false, nodeToRemove);
    log.info("Connection removed:" + nodeToRemove);
  }

  @Override
  public INode getServerNode() {
    return node;
  }

  @Override
  public void socketError(final SocketChannel channel, final Exception error) {
    checkNotNull(channel);

    // already closed, don't report it again
    final INode node = channelToNode.get(channel);
    if (node != null) {
      removeConnection(node);
    }
  }

  @Override
  public void socketUnqaurantined(final SocketChannel channel, final QuarantineConversation conversation) {
    final ServerQuarantineConversation con = (ServerQuarantineConversation) conversation;
    final INode remote = new Node(con.getRemoteName(), (InetSocketAddress) channel.socket().getRemoteSocketAddress());
    nodeToChannel.put(remote, channel);
    channelToNode.put(channel, remote);
    notifyConnectionsChanged(true, remote);
    log.info("Connection added to:" + remote);
  }

  @Override
  public INode getRemoteNode(final SocketChannel channel) {
    return channelToNode.get(channel);
  }

  @Override
  public InetSocketAddress getRemoteServerSocketAddress() {
    return node.getSocketAddress();
  }

  @Override
  public String toString() {
    return getClass().getSimpleName() + " LocalNode:" + node + " ClientNodes:" + nodeToChannel.keySet();
  }
}
