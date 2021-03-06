package games.strategy.engine.chat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import games.strategy.net.INode;
import games.strategy.net.Messengers;

/**
 * Manages the status of all chat participants and keeps the status synchronized among all nodes.
 */
public class StatusManager {
  private final List<IStatusListener> listeners = new CopyOnWriteArrayList<>();
  private final Map<INode, String> status = new HashMap<>();
  private final Messengers messengers;
  private final Object mutex = new Object();
  private final IStatusChannel statusChannelSubscriber;

  public StatusManager(final Messengers messengers) {
    this.messengers = messengers;
    statusChannelSubscriber = (node, status1) -> {
      synchronized (mutex) {
        if (status1 == null) {
          StatusManager.this.status.remove(node);
        } else {
          StatusManager.this.status.put(node, status1);
        }
      }
      notifyStatusChanged(node, status1);
    };
    if (messengers.isServer()
        && !messengers.hasLocalImplementor(IStatusController.STATUS_CONTROLLER)) {
      final StatusController controller = new StatusController(messengers);
      messengers.registerRemote(controller, IStatusController.STATUS_CONTROLLER);
    }
    this.messengers.registerChannelSubscriber(statusChannelSubscriber,
        IStatusChannel.STATUS_CHANNEL);
    final IStatusController controller =
        (IStatusController) messengers.getRemote(IStatusController.STATUS_CONTROLLER);
    final Map<INode, String> values = controller.getAllStatus();
    synchronized (mutex) {
      status.putAll(values);
      // at this point we are just being constructed, so we have no listeners
      // and we do not need to notify if anything has changed
    }
  }

  public void shutDown() {
    messengers.unregisterChannelSubscriber(statusChannelSubscriber,
        IStatusChannel.STATUS_CHANNEL);
  }

  /**
   * Get the status for the given node.
   */
  public String getStatus(final INode node) {
    synchronized (mutex) {
      return status.get(node);
    }
  }

  void setStatus(final String status) {
    ((IStatusController) messengers.getRemote(IStatusController.STATUS_CONTROLLER))
        .setStatus(status);
  }

  public void addStatusListener(final IStatusListener listener) {
    listeners.add(listener);
  }

  public void removeStatusListener(final IStatusListener listener) {
    listeners.remove(listener);
  }

  private void notifyStatusChanged(final INode node, final String newStatus) {
    for (final IStatusListener listener : listeners) {
      listener.statusChanged(node, newStatus);
    }
  }
}
