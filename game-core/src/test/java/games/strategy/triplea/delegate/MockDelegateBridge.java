package games.strategy.triplea.delegate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.stubbing.Answer;
import org.mockito.stubbing.OngoingStubbing;
import org.mockito.verification.VerificationMode;

import games.strategy.engine.data.Change;
import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerId;
import games.strategy.engine.delegate.IDelegateBridge;
import games.strategy.engine.history.DelegateHistoryWriter;
import games.strategy.sound.ISound;
import games.strategy.triplea.player.ITripleAPlayer;
import games.strategy.triplea.ui.display.ITripleADisplay;

final class MockDelegateBridge {
  private MockDelegateBridge() {}

  /**
   * Returns a new delegate bridge suitable for testing the given game data and player.
   *
   * @return A mock that can be configured using standard Mockito idioms.
   */
  static IDelegateBridge newInstance(final GameData gameData, final PlayerId playerId) {
    final IDelegateBridge delegateBridge = mock(IDelegateBridge.class);
    doAnswer(invocation -> {
      final Change change = invocation.getArgument(0);
      gameData.performChange(change);
      return null;
    }).when(delegateBridge).addChange(any());
    when(delegateBridge.getData()).thenReturn(gameData);
    when(delegateBridge.getDisplayChannelBroadcaster()).thenReturn(mock(ITripleADisplay.class));
    when(delegateBridge.getHistoryWriter()).thenReturn(DelegateHistoryWriter.NO_OP_INSTANCE);
    when(delegateBridge.getPlayerId()).thenReturn(playerId);
    final ITripleAPlayer remotePlayer = mock(ITripleAPlayer.class);
    when(delegateBridge.getRemotePlayer()).thenReturn(remotePlayer);
    when(delegateBridge.getRemotePlayer(any())).thenReturn(remotePlayer);
    when(delegateBridge.getSoundChannelBroadcaster()).thenReturn(mock(ISound.class));
    return delegateBridge;
  }

  static OngoingStubbing<int[]> whenGetRandom(final IDelegateBridge delegateBridge) {
    return when(delegateBridge.getRandom(anyInt(), anyInt(), any(), any(), anyString()));
  }

  static Answer<int[]> withValues(final int... values) {
    return invocation -> {
      final int count = invocation.getArgument(1);
      assertEquals(values.length, count, "count of requested random values does not match");
      return values;
    };
  }

  static void thenGetRandomShouldHaveBeenCalled(
      final IDelegateBridge delegateBridge,
      final VerificationMode verificationMode) {
    verify(delegateBridge, verificationMode).getRandom(anyInt(), anyInt(), any(), any(), anyString());
  }

  static ITripleAPlayer withRemotePlayer(final IDelegateBridge delegateBridge) {
    return (ITripleAPlayer) delegateBridge.getRemotePlayer();
  }

  static void advanceToStep(final IDelegateBridge delegateBridge, final String stepName) {
    final GameData gameData = delegateBridge.getData();
    gameData.acquireWriteLock();
    try {
      final int length = gameData.getSequence().size();
      int i = 0;
      while ((i < length) && (gameData.getSequence().getStep().getName().indexOf(stepName) == -1)) {
        gameData.getSequence().next();
        i++;
      }
      if ((i > length) && (gameData.getSequence().getStep().getName().indexOf(stepName) == -1)) {
        throw new IllegalArgumentException("Step not found: " + stepName);
      }
    } finally {
      gameData.releaseWriteLock();
    }
  }
}
