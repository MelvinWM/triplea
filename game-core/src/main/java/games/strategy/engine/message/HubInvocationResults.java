package games.strategy.engine.message;

import games.strategy.engine.message.unifiedmessenger.InvocationResults;
import games.strategy.net.GUID;

/**
 * The results of a remote method call invoked via {@link HubInvoke}.
 */
public class HubInvocationResults extends InvocationResults {
  private static final long serialVersionUID = -1769876896858969L;

  public HubInvocationResults() {}

  public HubInvocationResults(final RemoteMethodCallResults results, final GUID methodCallId) {
    super(results, methodCallId);
  }
}
