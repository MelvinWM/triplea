package games.strategy.triplea.ui;

import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.swing.Action;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import org.triplea.java.collections.IntegerMap;
import org.triplea.swing.SwingAction;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerId;
import games.strategy.engine.data.RepairRule;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.formatter.MyFormatter;

class RepairPanel extends ActionPanel {
  private static final long serialVersionUID = 3045997038627313714L;
  private static final String CHANGE = "Change...";
  private static final String BUY = "Repair...";
  private final JLabel actionLabel = new JLabel();
  private Map<Unit, IntegerMap<RepairRule>> repair;
  private boolean bid;
  private Collection<PlayerId> allowedPlayersToRepair;
  private final SimpleUnitPanel unitsPanel;
  private final JLabel repairdSoFar = new JLabel();
  private final JButton buyButton;

  private final ActionListener purchaseAction = new ActionListener() {
    @Override
    public void actionPerformed(final ActionEvent e) {
      final PlayerId player = getCurrentPlayer();
      final GameData data = getData();
      repair = ProductionRepairPanel.getProduction(player, allowedPlayersToRepair, (JFrame) getTopLevelAncestor(),
          data, bid, repair, getMap().getUiContext());
      unitsPanel.setUnitsFromRepairRuleMap(repair, player, data);
      final int totalValues = getTotalValues(repair);
      if (totalValues == 0) {
        repairdSoFar.setText("");
        buyButton.setText(BUY);
      } else {
        buyButton.setText(CHANGE);
        repairdSoFar.setText(totalValues + MyFormatter.pluralize(" unit", totalValues) + " to be repaired:");
      }
    }
  };

  private final Action doneAction = SwingAction.of("Done", e -> {
    final boolean hasPurchased = getTotalValues(repair) != 0;
    if (!hasPurchased) {
      final int selectedOption = JOptionPane.showConfirmDialog(JOptionPane.getFrameForComponent(RepairPanel.this),
          "Are you sure you dont want to repair anything?", "End Purchase", JOptionPane.YES_NO_OPTION);
      if (selectedOption != JOptionPane.YES_OPTION) {
        return;
      }
    }
    release();
  });

  RepairPanel(final GameData data, final MapPanel map) {
    super(data, map);
    unitsPanel = new SimpleUnitPanel(map.getUiContext());
    buyButton = new JButton(BUY);
    buyButton.addActionListener(purchaseAction);
  }

  @Override
  public void display(final PlayerId id) {
    super.display(id);
    repair = new HashMap<>();
    SwingUtilities.invokeLater(() -> {
      removeAll();
      actionLabel.setText(id.getName() + " repair");
      buyButton.setText(BUY);
      add(actionLabel);
      add(buyButton);
      add(new JButton(doneAction));
      repairdSoFar.setText("");
      add(Box.createVerticalStrut(9));
      add(repairdSoFar);
      add(Box.createVerticalStrut(4));
      unitsPanel.setUnitsFromRepairRuleMap(new HashMap<>(), id, getData());
      add(unitsPanel);
      add(Box.createVerticalGlue());
      SwingUtilities.invokeLater(refresh);
    });
  }

  private void refreshActionLabelText() {
    SwingUtilities.invokeLater(
        () -> actionLabel.setText(getCurrentPlayer().getName() + " repair " + (bid ? " for bid" : "")));
  }

  Map<Unit, IntegerMap<RepairRule>> waitForRepair(final boolean bid,
      final Collection<PlayerId> allowedPlayersToRepair) {
    this.bid = bid;
    this.allowedPlayersToRepair = allowedPlayersToRepair;
    refreshActionLabelText();
    // automatically "click" the buy button for us!
    SwingUtilities.invokeLater(() -> purchaseAction.actionPerformed(null));
    waitForRelease();
    return repair;
  }

  // Spin through the territories to get this.
  private static int getTotalValues(final Map<Unit, IntegerMap<RepairRule>> repair) {
    return repair.values().stream().mapToInt(IntegerMap::totalValues).sum();
  }

  @Override
  public String toString() {
    return "RepairPanel";
  }
}
