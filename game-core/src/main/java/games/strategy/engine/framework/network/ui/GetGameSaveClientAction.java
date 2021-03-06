package games.strategy.engine.framework.network.ui;

import java.awt.Component;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.logging.Level;

import javax.swing.AbstractAction;
import javax.swing.JOptionPane;

import games.strategy.engine.framework.startup.mc.IServerStartupRemote;
import games.strategy.triplea.ui.menubar.TripleAMenuBar;
import lombok.extern.java.Log;

/**
 * An action for downloading a save game from the server node to a client node.
 */
@Log
public class GetGameSaveClientAction extends AbstractAction {
  private static final long serialVersionUID = 1118264715230932068L;
  private final Component parent;
  private final IServerStartupRemote serverRemote;

  public GetGameSaveClientAction(final Component parent, final IServerStartupRemote serverRemote) {
    super("Download Gamesave (Save Game)");
    this.parent = JOptionPane.getFrameForComponent(parent);
    this.serverRemote = serverRemote;
  }

  @Override
  public void actionPerformed(final ActionEvent e) {
    final Frame frame = JOptionPane.getFrameForComponent(parent);
    final File f = TripleAMenuBar.getSaveGameLocation(frame);
    if (f != null) {
      final byte[] bytes = serverRemote.getSaveGame();
      try (FileOutputStream fout = new FileOutputStream(f)) {
        fout.write(bytes);
      } catch (final IOException exception) {
        log.log(Level.SEVERE, "Failed to download save game from server", exception);
      }
      JOptionPane.showMessageDialog(frame, "Game Saved", "Game Saved", JOptionPane.INFORMATION_MESSAGE);
    }
  }
}
