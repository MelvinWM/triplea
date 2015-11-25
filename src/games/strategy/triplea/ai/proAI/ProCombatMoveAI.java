package games.strategy.triplea.ai.proAI;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerID;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.Properties;
import games.strategy.triplea.TripleAUnit;
import games.strategy.triplea.ai.proAI.logging.ProLogger;
import games.strategy.triplea.ai.proAI.util.ProMoveOptionsUtils;
import games.strategy.triplea.ai.proAI.util.ProBattleUtils;
import games.strategy.triplea.ai.proAI.util.ProMatches;
import games.strategy.triplea.ai.proAI.util.ProMoveUtils;
import games.strategy.triplea.ai.proAI.util.ProPurchaseUtils;
import games.strategy.triplea.ai.proAI.util.ProTerritoryValueUtils;
import games.strategy.triplea.ai.proAI.util.ProTransportUtils;
import games.strategy.triplea.ai.proAI.util.ProUtils;
import games.strategy.triplea.attatchments.TerritoryAttachment;
import games.strategy.triplea.attatchments.UnitAttachment;
import games.strategy.triplea.delegate.BattleCalculator;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.TransportTracker;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.util.IntegerMap;
import games.strategy.util.Match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Pro combat move AI.
 */
public class ProCombatMoveAI {
  public static double WIN_PERCENTAGE = 95;
  public static double MIN_WIN_PERCENTAGE = 75;

  // Utilities
  private final ProUtils utils;
  private final ProBattleUtils battleUtils;
  private final ProTransportUtils transportUtils;
  private final ProMoveOptionsUtils attackOptionsUtils;
  private final ProMoveUtils moveUtils;
  private final ProTerritoryValueUtils territoryValueUtils;
  private final ProPurchaseUtils purchaseUtils;

  // Current map settings
  private boolean areNeutralsPassableByAir;

  // Current data
  private GameData data;
  private PlayerID player;
  private Territory myCapital;
  private boolean isDefensive;
  private double minCostPerHitPoint;

  public ProCombatMoveAI(final ProUtils utils, final ProBattleUtils battleUtils,
      final ProTransportUtils transportUtils, final ProMoveOptionsUtils attackOptionsUtils,
      final ProMoveUtils moveUtils, final ProTerritoryValueUtils territoryValueUtils,
      final ProPurchaseUtils purchaseUtils) {
    this.utils = utils;
    this.battleUtils = battleUtils;
    this.transportUtils = transportUtils;
    this.attackOptionsUtils = attackOptionsUtils;
    this.moveUtils = moveUtils;
    this.territoryValueUtils = territoryValueUtils;
    this.purchaseUtils = purchaseUtils;
  }

  public Map<Territory, ProTerritory> doCombatMove(final IMoveDelegate moveDel, final GameData data,
      final PlayerID player, final boolean isSimulation) {
    ProLogger.info("Starting combat move phase");

    // Current data at the start of combat move
    this.data = data;
    this.player = player;
    areNeutralsPassableByAir = (Properties.getNeutralFlyoverAllowed(data) && !Properties.getNeutralsImpassable(data));
    myCapital = TerritoryAttachment.getFirstOwnedCapitalOrFirstUnownedCapital(player, data);
    if (!games.strategy.triplea.Properties.getLow_Luck(data)) // Set optimal and min win percentage lower if not LL
    {
      WIN_PERCENTAGE = 90;
      MIN_WIN_PERCENTAGE = 65;
    }

    // Initialize data containers
    final Map<Territory, ProTerritory> attackMap = new HashMap<Territory, ProTerritory>();
    final Map<Unit, Set<Territory>> unitAttackMap = new HashMap<Unit, Set<Territory>>();
    final Map<Unit, Set<Territory>> transportAttackMap = new HashMap<Unit, Set<Territory>>();
    final Map<Unit, Set<Territory>> bombardMap = new HashMap<Unit, Set<Territory>>();
    final List<ProTransport> transportMapList = new ArrayList<ProTransport>();
    final Map<Territory, Set<Territory>> landRoutesMap = new HashMap<Territory, Set<Territory>>();

    // Determine whether capital is threatened and I should be in a defensive stance
    isDefensive = !battleUtils.territoryHasLocalLandSuperiority(myCapital, 3, player);
    ProLogger.debug("Currently in defensive stance: " + isDefensive);

    // Find all purchase options and min cost per hit point
    final List<ProPurchaseOption> specialPurchaseOptions = new ArrayList<ProPurchaseOption>();
    final List<ProPurchaseOption> factoryPurchaseOptions = new ArrayList<ProPurchaseOption>();
    final List<ProPurchaseOption> landPurchaseOptions = new ArrayList<ProPurchaseOption>();
    final List<ProPurchaseOption> airPurchaseOptions = new ArrayList<ProPurchaseOption>();
    final List<ProPurchaseOption> seaPurchaseOptions = new ArrayList<ProPurchaseOption>();
    purchaseUtils.findPurchaseOptions(player, landPurchaseOptions, airPurchaseOptions, seaPurchaseOptions,
        factoryPurchaseOptions, specialPurchaseOptions);
    minCostPerHitPoint = purchaseUtils.getMinCostPerHitPoint(player, landPurchaseOptions);

    // Find the maximum number of units that can attack each territory and max enemy defenders
    final List<Territory> myUnitTerritories =
        Match.getMatches(data.getMap().getTerritories(), Matches.territoryHasUnitsOwnedBy(player));
    attackOptionsUtils.findAttackOptions(player, myUnitTerritories, attackMap, unitAttackMap, transportAttackMap,
        bombardMap, landRoutesMap, transportMapList, new ArrayList<Territory>(), new ArrayList<Territory>(),
        new ArrayList<Territory>(), false, false);
    attackOptionsUtils.findScrambleOptions(player, attackMap);
    final ProMoveOptions alliedAttackOptions = attackOptionsUtils.findAlliedAttackOptions(player);
    final ProMoveOptions enemyDefendOptions = attackOptionsUtils.findEnemyDefendOptions(player);

    // Remove territories that aren't worth attacking and prioritize the remaining ones
    final List<ProTerritory> prioritizedTerritories =
        attackOptionsUtils.removeTerritoriesThatCantBeConquered(player, attackMap, unitAttackMap, transportAttackMap,
            alliedAttackOptions, enemyDefendOptions, false);
    List<Territory> territoriesToAttack = new ArrayList<Territory>();
    for (final ProTerritory patd : prioritizedTerritories) {
      territoriesToAttack.add(patd.getTerritory());
    }
    ProMoveOptions enemyAttackOptions =
        attackOptionsUtils.findEnemyAttackOptions(player, territoriesToAttack, new ArrayList<Territory>());
    Map<Territory, Double> territoryValueMap =
        territoryValueUtils.findTerritoryValues(player, minCostPerHitPoint, new ArrayList<Territory>(),
            territoriesToAttack);
    determineTerritoriesThatCanBeHeld(prioritizedTerritories, attackMap, enemyAttackOptions, territoryValueMap);
    prioritizeAttackOptions(player, prioritizedTerritories);
    removeTerritoriesThatArentWorthAttacking(prioritizedTerritories, enemyAttackOptions);

    // Determine which territories to attack
    determineTerritoriesToAttack(attackMap, unitAttackMap, prioritizedTerritories, transportMapList,
        transportAttackMap, bombardMap);

    // Determine which territories can be held and remove any that aren't worth attacking
    territoriesToAttack = new ArrayList<Territory>();
    final Set<Territory> possibleTransportTerritories = new HashSet<Territory>();
    for (final ProTerritory patd : prioritizedTerritories) {
      territoriesToAttack.add(patd.getTerritory());
      if (!patd.getAmphibAttackMap().isEmpty()) {
        possibleTransportTerritories.addAll(data.getMap().getNeighbors(patd.getTerritory(), Matches.TerritoryIsWater));
      }
    }
    enemyAttackOptions =
        attackOptionsUtils.findEnemyAttackOptions(player, territoriesToAttack, new ArrayList<Territory>(
            possibleTransportTerritories));
    territoryValueMap =
        territoryValueUtils.findTerritoryValues(player, minCostPerHitPoint, new ArrayList<Territory>(),
            territoriesToAttack);
    determineTerritoriesThatCanBeHeld(prioritizedTerritories, attackMap, enemyAttackOptions, territoryValueMap);
    removeTerritoriesThatArentWorthAttacking(prioritizedTerritories, enemyAttackOptions);

    // Determine how many units to attack each territory with
    final List<Unit> alreadyMovedUnits =
        moveOneDefenderToLandTerritoriesBorderingEnemy(attackMap, unitAttackMap, prioritizedTerritories,
            myUnitTerritories);
    determineUnitsToAttackWith(attackMap, enemyAttackOptions, unitAttackMap, prioritizedTerritories, transportMapList,
        transportAttackMap, bombardMap, alreadyMovedUnits);

    // Get all transport final territories
    moveUtils.calculateAmphibRoutes(player, new ArrayList<Collection<Unit>>(), new ArrayList<Route>(),
        new ArrayList<Collection<Unit>>(), attackMap, true);

    // Determine max enemy counter attack units and remove territories where transports are exposed
    removeTerritoriesWhereTransportsAreExposed(attackMap, enemyAttackOptions);

    // Determine if capital can be held if I still own it
    if (myCapital != null && myCapital.getOwner().equals(player)) {
      determineIfCapitalCanBeHeld(attackMap, prioritizedTerritories, landPurchaseOptions);
    }

    // Check if any subs in contested territory that's not being attacked
    checkContestedSeaTerritories(attackMap, myUnitTerritories);

    // Calculate attack routes and perform moves
    doMove(attackMap, moveDel, data, player, isSimulation);

    // Log results
    ProLogger.info("Logging results");
    logAttackMoves(attackMap, unitAttackMap, transportMapList, prioritizedTerritories);
    return attackMap;
  }

  public void doMove(final Map<Territory, ProTerritory> attackMap, final IMoveDelegate moveDel,
      final GameData data, final PlayerID player, final boolean isSimulation) {
    this.data = data;
    this.player = player;
    areNeutralsPassableByAir = (Properties.getNeutralFlyoverAllowed(data) && !Properties.getNeutralsImpassable(data));

    // Calculate attack routes and perform moves
    final List<Collection<Unit>> moveUnits = new ArrayList<Collection<Unit>>();
    final List<Route> moveRoutes = new ArrayList<Route>();
    moveUtils.calculateMoveRoutes(player, areNeutralsPassableByAir, moveUnits, moveRoutes, attackMap, true);
    moveUtils.doMove(moveUnits, moveRoutes, null, moveDel, isSimulation);

    // Calculate amphib attack routes and perform moves
    moveUnits.clear();
    moveRoutes.clear();
    final List<Collection<Unit>> transportsToLoad = new ArrayList<Collection<Unit>>();
    moveUtils.calculateAmphibRoutes(player, moveUnits, moveRoutes, transportsToLoad, attackMap, true);
    moveUtils.doMove(moveUnits, moveRoutes, transportsToLoad, moveDel, isSimulation);

    // Calculate attack routes and perform moves
    moveUnits.clear();
    moveRoutes.clear();
    moveUtils.calculateBombardMoveRoutes(player, moveUnits, moveRoutes, attackMap);
    moveUtils.doMove(moveUnits, moveRoutes, null, moveDel, isSimulation);
  }

  private List<ProTerritory> prioritizeAttackOptions(final PlayerID player,
      final List<ProTerritory> prioritizedTerritories) {

    ProLogger.info("Prioritizing territories to try to attack");

    // Calculate value of attacking territory
    for (final Iterator<ProTerritory> it = prioritizedTerritories.iterator(); it.hasNext();) {
      final ProTerritory patd = it.next();
      final Territory t = patd.getTerritory();

      // Determine territory attack properties
      final int isLand = !t.isWater() ? 1 : 0;
      final int isNeutral = (!t.isWater() && t.getOwner().isNull()) ? 1 : 0;
      final int isCanHold = patd.isCanHold() ? 1 : 0;
      final int isAmphib = patd.isNeedAmphibUnits() ? 1 : 0;
      final List<Unit> defendingUnits =
          Match.getMatches(patd.getMaxEnemyDefenders(player, data), ProMatches.unitIsEnemyAndNotInfa(player, data));
      final int isEmptyLand = (defendingUnits.isEmpty() && !patd.isNeedAmphibUnits()) ? 1 : 0;
      final boolean isAdjacentToMyCapital = !data.getMap().getNeighbors(t, Matches.territoryIs(myCapital)).isEmpty();
      final int isNotNeutralAdjacentToMyCapital =
          (isAdjacentToMyCapital && ProMatches.territoryIsEnemyNotNeutralLand(player, data).match(t)) ? 1 : 0;
      final int isFactory = ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t) ? 1 : 0;
      final int isFFA = utils.isFFA(data, player) ? 1 : 0;

      // Determine production value and if it is an enemy capital
      int production = 0;
      int isEnemyCapital = 0;
      final TerritoryAttachment ta = TerritoryAttachment.get(t);
      if (ta != null) {
        production = ta.getProduction();
        if (ta.isCapital()) {
          isEnemyCapital = 1;
        }
      }

      // Calculate attack value for prioritization
      double TUVSwing = patd.getMaxBattleResult().getTUVSwing();
      if (isFFA == 1 && TUVSwing > 0) {
        TUVSwing *= 0.5;
      }
      final double territoryValue =
          (1 + isLand + isCanHold * (1 + 2 * isFFA)) * (1 + isEmptyLand) * (1 + isFactory) * (1 - 0.5 * isAmphib)
              * production;
      double attackValue =
          (TUVSwing + territoryValue) * (1 + 4 * isEnemyCapital) * (1 + 2 * isNotNeutralAdjacentToMyCapital)
              * (1 - 0.9 * isNeutral);

      // Check if a negative value neutral territory should be attacked
      if (attackValue <= 0 && !patd.isNeedAmphibUnits() && !t.isWater() && t.getOwner().isNull()) {

        // Determine enemy neighbor territory production value for neutral land territories
        double nearbyEnemyValue = 0;
        final List<Territory> cantReachEnemyTerritories = new ArrayList<Territory>();
        final Set<Territory> nearbyTerritories =
            data.getMap().getNeighbors(t, ProMatches.territoryCanMoveLandUnits(player, data, true));
        final List<Territory> nearbyEnemyTerritories =
            Match.getMatches(nearbyTerritories, Matches.isTerritoryEnemy(player, data));
        final List<Territory> nearbyTerritoriesWithOwnedUnits =
            Match.getMatches(nearbyTerritories, Matches.territoryHasUnitsOwnedBy(player));
        for (final Territory nearbyEnemyTerritory : nearbyEnemyTerritories) {
          boolean allAlliedNeighborsHaveRoute = true;
          for (final Territory nearbyAlliedTerritory : nearbyTerritoriesWithOwnedUnits) {
            final int distance =
                data.getMap().getDistance_IgnoreEndForCondition(nearbyAlliedTerritory, nearbyEnemyTerritory,
                    ProMatches.territoryIsEnemyNotNeutralOrAllied(player, data));
            if (distance < 0 || distance > 2) {
              allAlliedNeighborsHaveRoute = false;
              break;
            }
          }
          if (!allAlliedNeighborsHaveRoute) {
            final double value =
                territoryValueUtils.findTerritoryAttackValue(player, nearbyEnemyTerritory, minCostPerHitPoint);
            if (value > 0) {
              nearbyEnemyValue += value;
            }
            cantReachEnemyTerritories.add(nearbyEnemyTerritory);
          }
        }
        ProLogger.debug(t.getName() + " calculated nearby enemy value=" + nearbyEnemyValue + " from "
            + cantReachEnemyTerritories);
        if (nearbyEnemyValue > 0) {
          ProLogger.trace(t.getName() + " updating negative neutral attack value=" + attackValue);
          attackValue = nearbyEnemyValue * .001 / (1 - attackValue);
        } else {

          // Check if overwhelming attack strength (more than 5 times)
          final double strengthDifference =
              battleUtils.estimateStrengthDifference(t, patd.getMaxUnits(), patd.getMaxEnemyDefenders(player, data));
          ProLogger.debug(t.getName() + " calculated strengthDifference=" + strengthDifference);
          if (strengthDifference > 500) {
            ProLogger.trace(t.getName() + " updating negative neutral attack value=" + attackValue);
            attackValue = strengthDifference * .00001 / (1 - attackValue);
          }
        }
      }

      // Remove negative value territories
      patd.setValue(attackValue);
      if (attackValue <= 0 || (isDefensive && attackValue <= 8 && data.getMap().getDistance(myCapital, t) <= 3)) {
        ProLogger.debug("Removing territory that has a negative attack value: " + t.getName() + ", AttackValue="
            + patd.getValue());
        it.remove();
      }
    }

    // Sort attack territories by value
    Collections.sort(prioritizedTerritories, new Comparator<ProTerritory>() {
      @Override
      public int compare(final ProTerritory t1, final ProTerritory t2) {
        final double value1 = t1.getValue();
        final double value2 = t2.getValue();
        return Double.compare(value2, value1);
      }
    });

    // Log prioritized territories
    for (final ProTerritory patd : prioritizedTerritories) {
      ProLogger.debug("AttackValue=" + patd.getValue() + ", TUVSwing=" + patd.getMaxBattleResult().getTUVSwing()
          + ", isAmphib=" + patd.isNeedAmphibUnits() + ", " + patd.getTerritory().getName());
    }
    return prioritizedTerritories;
  }

  private void determineTerritoriesToAttack(final Map<Territory, ProTerritory> attackMap,
      final Map<Unit, Set<Territory>> unitAttackMap, final List<ProTerritory> prioritizedTerritories,
      final List<ProTransport> transportMapList, final Map<Unit, Set<Territory>> transportAttackMap,
      final Map<Unit, Set<Territory>> bombardMap) {

    ProLogger.info("Determine which territories to attack");

    // Assign units to territories by prioritization
    int numToAttack = Math.min(1, prioritizedTerritories.size());
    boolean haveRemovedAllAmphibTerritories = false;
    while (true) {
      final List<ProTerritory> territoriesToTryToAttack = prioritizedTerritories.subList(0, numToAttack);
      ProLogger.debug("Current number of territories: " + numToAttack);
      tryToAttackTerritories(attackMap, new ProMoveOptions(utils, battleUtils), unitAttackMap,
          territoriesToTryToAttack, transportMapList, transportAttackMap, bombardMap, new ArrayList<Unit>());

      // Determine if all attacks are successful
      boolean areSuccessful = true;
      for (final ProTerritory patd : territoriesToTryToAttack) {
        final Territory t = patd.getTerritory();
        if (patd.getBattleResult() == null) {
          patd.setBattleResult(battleUtils.estimateAttackBattleResults(player, t, patd.getUnits(),
              patd.getMaxEnemyDefenders(player, data), patd.getBombardTerritoryMap().keySet()));
        }
        ProLogger.trace(patd.getResultString() + " with attackers: " + patd.getUnits());
        final double estimate =
            battleUtils.estimateStrengthDifference(t, patd.getUnits(), patd.getMaxEnemyDefenders(player, data));
        final ProBattleResult result = patd.getBattleResult();
        double winPercentage = WIN_PERCENTAGE;
        if (patd.isCanAttack() || territoriesToTryToAttack.size() == 1) {
          winPercentage = MIN_WIN_PERCENTAGE;
        }
        if (!patd.isStrafing() && estimate < patd.getStrengthEstimate()
            && (result.getWinPercentage() < winPercentage || !result.isHasLandUnitRemaining())) {
          areSuccessful = false;
        }
      }

      // Determine whether to try more territories, remove a territory, or end
      if (areSuccessful) {
        for (final ProTerritory patd : territoriesToTryToAttack) {
          patd.setCanAttack(true);
          final double estimate =
              battleUtils.estimateStrengthDifference(patd.getTerritory(), patd.getUnits(),
                  patd.getMaxEnemyDefenders(player, data));
          if (estimate < patd.getStrengthEstimate()) {
            patd.setStrengthEstimate(estimate);
          }
        }

        // If already used all transports then remove any remaining amphib territories
        if (!haveRemovedAllAmphibTerritories) {
          final Set<Unit> movedTransports = new HashSet<Unit>();
          for (final ProTerritory patd : prioritizedTerritories) {
            movedTransports.addAll(patd.getAmphibAttackMap().keySet());
            movedTransports.addAll(Match.getMatches(patd.getUnits(), Matches.UnitIsTransport));
          }
          if (movedTransports.size() >= transportMapList.size()) {
            final List<ProTerritory> amphibTerritoriesToRemove = new ArrayList<ProTerritory>();
            for (int i = numToAttack; i < prioritizedTerritories.size(); i++) {
              if (prioritizedTerritories.get(i).isNeedAmphibUnits()) {
                amphibTerritoriesToRemove.add(prioritizedTerritories.get(i));
                ProLogger.debug("Removing amphib territory since already used all transports: "
                    + prioritizedTerritories.get(i).getTerritory().getName());
              }
            }
            prioritizedTerritories.removeAll(amphibTerritoriesToRemove);
            haveRemovedAllAmphibTerritories = true;
          }
        }

        // Can attack all territories in list so end
        numToAttack++;
        if (numToAttack > prioritizedTerritories.size()) {
          break;
        }
      } else {
        ProLogger.debug("Removing territory: " + prioritizedTerritories.get(numToAttack - 1).getTerritory().getName());
        prioritizedTerritories.remove(numToAttack - 1);
        if (numToAttack > prioritizedTerritories.size()) {
          numToAttack--;
        }
      }
    }
    ProLogger.debug("Final number of territories: " + (numToAttack - 1));
  }

  private void determineTerritoriesThatCanBeHeld(final List<ProTerritory> prioritizedTerritories,
      final Map<Territory, ProTerritory> attackMap, final ProMoveOptions enemyAttackOptions,
      final Map<Territory, Double> territoryValueMap) {

    ProLogger.info("Check if we should try to hold attack territories");

    // Determine which territories to try and hold
    final Map<Unit, Territory> unitTerritoryMap = utils.createUnitTerritoryMap(player);
    for (final ProTerritory patd : prioritizedTerritories) {
      final Territory t = patd.getTerritory();

      // If strafing then can't hold
      if (patd.isStrafing()) {
        patd.setCanHold(false);
        ProLogger.debug(t + ", strafing so CanHold=false");
        continue;
      }

      // Set max enemy attackers
      if (enemyAttackOptions.getMax(t) != null) {
        final Set<Unit> enemyAttackingUnits = new HashSet<Unit>(enemyAttackOptions.getMax(t).getMaxUnits());
        enemyAttackingUnits.addAll(enemyAttackOptions.getMax(t).getMaxAmphibUnits());
        patd.setMaxEnemyUnits(new ArrayList<Unit>(enemyAttackingUnits));
        patd.setMaxEnemyBombardUnits(enemyAttackOptions.getMax(t).getMaxBombardUnits());
      }

      // Add strategic value for factories
      int isFactory = 0;
      if (ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t)) {
        isFactory = 1;
      }

      // Determine whether its worth trying to hold territory
      double totalValue = 0.0;
      final List<Unit> nonAirAttackers = Match.getMatches(patd.getMaxUnits(), Matches.UnitIsNotAir);
      for (final Unit u : nonAirAttackers) {
        totalValue += territoryValueMap.get(unitTerritoryMap.get(u));
      }
      final double averageValue = totalValue / nonAirAttackers.size() * 0.75;
      final double territoryValue = territoryValueMap.get(t) * (1 + 4 * isFactory);
      if (!t.isWater() && territoryValue < averageValue) {
        attackMap.get(t).setCanHold(false);
        ProLogger.debug(t + ", CanHold=false, value=" + territoryValueMap.get(t) + ", averageAttackFromValue="
            + averageValue);
        continue;
      }
      if (enemyAttackOptions.getMax(t) != null) {

        // Find max remaining defenders
        final Set<Unit> attackingUnits = new HashSet<Unit>(patd.getMaxUnits());
        attackingUnits.addAll(patd.getMaxAmphibUnits());
        final ProBattleResult result =
            battleUtils.estimateAttackBattleResults(player, t, new ArrayList<Unit>(attackingUnits),
                patd.getMaxEnemyDefenders(player, data), patd.getMaxBombardUnits());
        final List<Unit> remainingUnitsToDefendWith =
            Match.getMatches(result.getAverageAttackersRemaining(), Matches.UnitIsAir.invert());
        ProLogger.debug(t + ", value=" + territoryValueMap.get(t) + ", averageAttackFromValue=" + averageValue
            + ", MyAttackers=" + attackingUnits.size() + ", RemainingUnits=" + remainingUnitsToDefendWith.size());

        // Determine counter attack results to see if I can hold it
        final ProBattleResult result2 =
            battleUtils.calculateBattleResults(player, t, patd.getMaxEnemyUnits(), remainingUnitsToDefendWith,
                enemyAttackOptions.getMax(t).getMaxBombardUnits(), false);
        final boolean canHold =
            (!result2.isHasLandUnitRemaining() && !t.isWater()) || (result2.getTUVSwing() < 0)
                || (result2.getWinPercentage() < MIN_WIN_PERCENTAGE);
        patd.setCanHold(canHold);
        ProLogger
            .debug(t + ", CanHold=" + canHold + ", MyDefenders=" + remainingUnitsToDefendWith.size()
                + ", EnemyAttackers=" + patd.getMaxEnemyUnits().size() + ", win%=" + result2.getWinPercentage()
                + ", EnemyTUVSwing=" + result2.getTUVSwing() + ", hasLandUnitRemaining="
                + result2.isHasLandUnitRemaining());
      } else {
        attackMap.get(t).setCanHold(true);
        ProLogger.debug(t + ", CanHold=true since no enemy counter attackers, value=" + territoryValueMap.get(t)
            + ", averageAttackFromValue=" + averageValue);
      }
    }
  }

  private void removeTerritoriesThatArentWorthAttacking(final List<ProTerritory> prioritizedTerritories,
      final ProMoveOptions enemyAttackOptions) {
    ProLogger.info("Remove territories that aren't worth attacking");

    // Loop through all prioritized territories
    for (final Iterator<ProTerritory> it = prioritizedTerritories.iterator(); it.hasNext();) {
      final ProTerritory patd = it.next();
      final Territory t = patd.getTerritory();
      ProLogger.debug("Checking territory=" + patd.getTerritory().getName() + " with isAmphib="
          + patd.isNeedAmphibUnits());

      // Remove empty convoy zones that can't be held
      if (!patd.isCanHold() && enemyAttackOptions.getMax(t) != null && t.isWater()
          && !t.getUnits().someMatch(Matches.enemyUnit(player, data))) {
        ProLogger.debug("Removing convoy zone that can't be held: " + t.getName() + ", enemyAttackers="
            + enemyAttackOptions.getMax(t).getMaxUnits());
        it.remove();
        continue;
      }

      // Remove neutral and low value amphib land territories that can't be held
      final boolean isNeutral = t.getOwner().isNull();
      final double strengthDifference =
          battleUtils.estimateStrengthDifference(t, patd.getMaxUnits(), patd.getMaxEnemyDefenders(player, data));
      if (!patd.isCanHold() && enemyAttackOptions.getMax(t) != null && !t.isWater()) {
        if (isNeutral && strengthDifference <= 500) {

          // Remove neutral territories that can't be held and don't have overwhelming attack strength
          ProLogger.debug("Removing neutral territory that can't be held: " + t.getName() + ", enemyAttackers="
              + enemyAttackOptions.getMax(t).getMaxUnits() + ", enemyAmphibAttackers="
              + enemyAttackOptions.getMax(t).getMaxAmphibUnits() + ", strengthDifference=" + strengthDifference);
          it.remove();
          continue;
        } else if (patd.isNeedAmphibUnits() && patd.getValue() < 2) {

          // Remove amphib territories that aren't worth attacking
          ProLogger.debug("Removing low value amphib territory that can't be held: " + t.getName()
              + ", enemyAttackers=" + enemyAttackOptions.getMax(t).getMaxUnits() + ", enemyAmphibAttackers="
              + enemyAttackOptions.getMax(t).getMaxAmphibUnits());
          it.remove();
          continue;
        }
      }
      // Remove neutral territories where attackers are adjacent to enemy territories that aren't being attacked
      if (isNeutral && !t.isWater() && strengthDifference <= 500) {

        // Get list of territories I'm attacking
        final List<Territory> prioritizedTerritoryList = new ArrayList<Territory>();
        for (final ProTerritory prioritizedTerritory : prioritizedTerritories) {
          prioritizedTerritoryList.add(prioritizedTerritory.getTerritory());
        }

        // Find all territories units are attacking from that are adjacent to territory
        final Map<Unit, Territory> unitTerritoryMap = utils.createUnitTerritoryMap(player);
        final Set<Territory> attackFromTerritories = new HashSet<Territory>();
        for (final Unit u : patd.getMaxUnits()) {
          attackFromTerritories.add(unitTerritoryMap.get(u));
        }
        attackFromTerritories.retainAll(data.getMap().getNeighbors(t));

        // Determine if any of the attacking from territories has enemy neighbors that aren't being attacked
        boolean attackersHaveEnemyNeighbors = false;
        Territory attackFromTerritoryWithEnemyNeighbors = null;
        for (final Territory attackFromTerritory : attackFromTerritories) {
          final Set<Territory> enemyNeighbors =
              data.getMap().getNeighbors(attackFromTerritory, ProMatches.territoryIsEnemyNotNeutralLand(player, data));
          if (!prioritizedTerritoryList.containsAll(enemyNeighbors)) {
            attackersHaveEnemyNeighbors = true;
            attackFromTerritoryWithEnemyNeighbors = attackFromTerritory;
            break;
          }
        }
        if (attackersHaveEnemyNeighbors) {
          ProLogger.debug("Removing neutral territory that has attackers that are adjacent to enemies: " + t.getName()
              + ", attackFromTerritory=" + attackFromTerritoryWithEnemyNeighbors);
          it.remove();
          continue;
        }
      }
    }
  }

  private List<Unit> moveOneDefenderToLandTerritoriesBorderingEnemy(
      final Map<Territory, ProTerritory> attackMap, final Map<Unit, Set<Territory>> unitAttackMap,
      final List<ProTerritory> prioritizedTerritories, final List<Territory> myUnitTerritories) {

    ProLogger.info("Determine which territories to defend with one land unit");

    // Get list of territories to attack
    final List<Territory> territoriesToAttack = new ArrayList<Territory>();
    for (final ProTerritory patd : prioritizedTerritories) {
      territoriesToAttack.add(patd.getTerritory());
    }

    // Find land territories with no can't move units and adjacent to enemy land units
    final List<Unit> alreadyMovedUnits = new ArrayList<Unit>();
    final IntegerMap<UnitType> playerCostMap = BattleCalculator.getCostsForTUV(player, data);
    for (final Territory t : myUnitTerritories) {
      final boolean hasAlliedLandUnits =
          Match.someMatch(t.getUnits().getUnits(),
              ProMatches.unitCantBeMovedAndIsAlliedDefenderAndNotInfra(player, data, t));
      final Set<Territory> enemyNeighbors =
          data.getMap().getNeighbors(t,
              Matches.territoryIsEnemyNonNeutralAndHasEnemyUnitMatching(data, player, Matches.UnitIsLand));
      enemyNeighbors.removeAll(territoriesToAttack);
      if (!t.isWater() && !hasAlliedLandUnits && !enemyNeighbors.isEmpty()) {
        int minCost = Integer.MAX_VALUE;
        Unit minUnit = null;
        for (final Unit u : t.getUnits().getMatches(Matches.unitIsOwnedBy(player))) {
          if (playerCostMap.getInt(u.getType()) < minCost) {
            minCost = playerCostMap.getInt(u.getType());
            minUnit = u;
          }
        }
        if (minUnit != null) {
          unitAttackMap.remove(minUnit);
          alreadyMovedUnits.add(minUnit);
          ProLogger.debug(t + ", added one land unit: " + minUnit);
        }
      }
    }
    return alreadyMovedUnits;
  }

  private void removeTerritoriesWhereTransportsAreExposed(final Map<Territory, ProTerritory> attackMap,
      final ProMoveOptions enemyAttackOptions) {

    ProLogger.info("Remove territories where transports are exposed");

    // Find maximum defenders for each transport territory
    final List<Territory> clearedTerritories = new ArrayList<Territory>();
    for (final Territory t : attackMap.keySet()) {
      if (!attackMap.get(t).getUnits().isEmpty()) {
        clearedTerritories.add(t);
      }
    }
    final Match<Territory> myUnitTerritoriesMatch =
        Matches.territoryHasUnitsThatMatch(ProMatches.unitCanBeMovedAndIsOwned(player));
    final List<Territory> myUnitTerritories = Match.getMatches(data.getMap().getTerritories(), myUnitTerritoriesMatch);
    final Map<Territory, ProTerritory> moveMap = new HashMap<Territory, ProTerritory>();
    final Map<Unit, Set<Territory>> unitMoveMap = new HashMap<Unit, Set<Territory>>();
    final Map<Unit, Set<Territory>> transportMoveMap = new HashMap<Unit, Set<Territory>>();
    final List<ProTransport> transportMapList = new ArrayList<ProTransport>();
    final Map<Territory, Set<Territory>> landRoutesMap = new HashMap<Territory, Set<Territory>>();
    attackOptionsUtils.findDefendOptions(player, myUnitTerritories, moveMap, unitMoveMap, transportMoveMap,
        landRoutesMap, transportMapList, clearedTerritories, false);

    // Remove units that have already attacked
    final Set<Unit> alreadyAttackedWithUnits = new HashSet<Unit>();
    for (final Territory t : attackMap.keySet()) {
      alreadyAttackedWithUnits.addAll(attackMap.get(t).getUnits());
      alreadyAttackedWithUnits.addAll(attackMap.get(t).getAmphibAttackMap().keySet());
    }
    for (final Territory t : moveMap.keySet()) {
      moveMap.get(t).getMaxUnits().removeAll(alreadyAttackedWithUnits);
    }

    // Loop through all prioritized territories
    for (final Territory t : attackMap.keySet()) {
      final ProTerritory patd = attackMap.get(t);
      ProLogger.debug("Checking territory=" + patd.getTerritory().getName() + " with tranports size="
          + patd.getTransportTerritoryMap().size());
      if (!patd.getTerritory().isWater() && !patd.getTransportTerritoryMap().isEmpty()) {

        // Find all transports for each unload territory
        final Map<Territory, List<Unit>> territoryTransportAndBombardMap = new HashMap<Territory, List<Unit>>();
        for (final Unit u : patd.getTransportTerritoryMap().keySet()) {
          final Territory unloadTerritory = patd.getTransportTerritoryMap().get(u);
          if (territoryTransportAndBombardMap.containsKey(unloadTerritory)) {
            territoryTransportAndBombardMap.get(unloadTerritory).add(u);
          } else {
            final List<Unit> transports = new ArrayList<Unit>();
            transports.add(u);
            territoryTransportAndBombardMap.put(unloadTerritory, transports);
          }
        }

        // Find all bombard units for each unload territory
        for (final Unit u : patd.getBombardTerritoryMap().keySet()) {
          final Territory unloadTerritory = patd.getBombardTerritoryMap().get(u);
          if (territoryTransportAndBombardMap.containsKey(unloadTerritory)) {
            territoryTransportAndBombardMap.get(unloadTerritory).add(u);
          } else {
            final List<Unit> transports = new ArrayList<Unit>();
            transports.add(u);
            territoryTransportAndBombardMap.put(unloadTerritory, transports);
          }
        }

        // Determine counter attack results for each transport territory
        double totalEnemyTUVSwing = 0.0;
        for (final Territory unloadTerritory : territoryTransportAndBombardMap.keySet()) {
          if (enemyAttackOptions.getMax(unloadTerritory) != null) {
            final List<Unit> enemyAttackers = enemyAttackOptions.getMax(unloadTerritory).getMaxUnits();
            final Set<Unit> defenders =
                new HashSet<Unit>(unloadTerritory.getUnits().getMatches(ProMatches.unitIsAlliedNotOwned(player, data)));
            defenders.addAll(territoryTransportAndBombardMap.get(unloadTerritory));
            if (moveMap.get(unloadTerritory) != null) {
              defenders.addAll(moveMap.get(unloadTerritory).getMaxUnits());
            }
            final ProBattleResult result =
                battleUtils.calculateBattleResults(player, unloadTerritory, enemyAttackOptions.getMax(unloadTerritory)
                    .getMaxUnits(), new ArrayList<Unit>(defenders), new HashSet<Unit>(), false);
            final ProBattleResult minResult =
                battleUtils.calculateBattleResults(player, unloadTerritory, enemyAttackOptions.getMax(unloadTerritory)
                    .getMaxUnits(), territoryTransportAndBombardMap.get(unloadTerritory), new HashSet<Unit>(), false);
            final double minTUVSwing = Math.min(result.getTUVSwing(), minResult.getTUVSwing());
            if (minTUVSwing > 0) {
              totalEnemyTUVSwing += minTUVSwing;
            }
            ProLogger.trace(unloadTerritory + ", EnemyAttackers=" + enemyAttackers.size() + ", MaxDefenders="
                + defenders.size() + ", MaxEnemyTUVSwing=" + result.getTUVSwing() + ", MinDefenders="
                + territoryTransportAndBombardMap.get(unloadTerritory).size() + ", MinEnemyTUVSwing="
                + minResult.getTUVSwing());
          } else {
            ProLogger.trace("Territory=" + unloadTerritory.getName() + " has no enemy attackers");
          }
        }

        // Determine whether its worth attacking
        final ProBattleResult result =
            battleUtils.calculateBattleResults(player, t, patd.getUnits(), patd.getMaxEnemyDefenders(player, data),
                patd.getBombardTerritoryMap().keySet(), true);
        int production = 0;
        final TerritoryAttachment ta = TerritoryAttachment.get(t);
        if (ta != null) {
          production = ta.getProduction();
        }
        final double attackValue = result.getTUVSwing() + production;
        if (!patd.isStrafing() && (0.75 * totalEnemyTUVSwing) > attackValue) {
          ProLogger.debug("Removing amphib territory: " + patd.getTerritory() + ", totalEnemyTUVSwing="
              + totalEnemyTUVSwing + ", attackValue=" + attackValue);
          attackMap.get(t).getUnits().clear();
          attackMap.get(t).getAmphibAttackMap().clear();
          attackMap.get(t).getBombardTerritoryMap().clear();
        } else {
          ProLogger.debug("Keeping amphib territory: " + patd.getTerritory() + ", totalEnemyTUVSwing="
              + totalEnemyTUVSwing + ", attackValue=" + attackValue);
        }
      }
    }
  }

  private void determineUnitsToAttackWith(final Map<Territory, ProTerritory> attackMap,
      final ProMoveOptions enemyAttackOptions, final Map<Unit, Set<Territory>> unitAttackMap,
      final List<ProTerritory> prioritizedTerritories, final List<ProTransport> transportMapList,
      final Map<Unit, Set<Territory>> transportAttackMap, final Map<Unit, Set<Territory>> bombardMap,
      final List<Unit> alreadyMovedUnits) {

    ProLogger.info("Determine units to attack each territory with");
    final Map<Unit, Territory> unitTerritoryMap = utils.createUnitTerritoryMap(player);
    final IntegerMap<UnitType> playerCostMap = BattleCalculator.getCostsForTUV(player, data);

    // Assign units to territories by prioritization
    while (true) {
      Map<Unit, Set<Territory>> sortedUnitAttackOptions =
          tryToAttackTerritories(attackMap, enemyAttackOptions, unitAttackMap, prioritizedTerritories,
              transportMapList, transportAttackMap, bombardMap, alreadyMovedUnits);

      // Re-sort attack options
      sortedUnitAttackOptions =
          attackOptionsUtils.sortUnitNeededOptionsThenAttack(player, sortedUnitAttackOptions, attackMap,
              unitTerritoryMap);

      // Set air units in any territory with no AA (don't move planes to empty territories)
      for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();) {
        final Unit unit = it.next();
        final boolean isAirUnit = UnitAttachment.get(unit.getType()).getIsAir();
        if (!isAirUnit) {
          continue; // skip non-air units
        }
        Territory minWinTerritory = null;
        double minWinPercentage = Double.MAX_VALUE;
        for (final Territory t : sortedUnitAttackOptions.get(unit)) {
          final ProTerritory patd = attackMap.get(t);

          // Check if air unit should avoid this territory due to no guaranteed safe landing location
          final boolean isEnemyFactory = ProMatches.territoryHasInfraFactoryAndIsEnemyLand(player, data).match(t);
          final boolean isAdjacentToAlliedFactory =
              Matches.territoryHasNeighborMatching(data,
                  ProMatches.territoryHasInfraFactoryAndIsAlliedLand(player, data)).match(t);
          final int range = TripleAUnit.get(unit).getMovementLeft();
          final int distance =
              data.getMap().getDistance_IgnoreEndForCondition(unitTerritoryMap.get(unit), t,
                  ProMatches.territoryCanMoveAirUnitsAndNoAA(player, data, true));
          final boolean usesMoreThanHalfOfRange = distance > range / 2;
          if (!isEnemyFactory && !isAdjacentToAlliedFactory && usesMoreThanHalfOfRange) {
            continue;
          }
          if (patd.getBattleResult() == null) {
            patd.setBattleResult(battleUtils.estimateAttackBattleResults(player, t, patd.getUnits(),
                patd.getMaxEnemyDefenders(player, data), patd.getBombardTerritoryMap().keySet()));
          }
          final ProBattleResult result = patd.getBattleResult();
          if (result.getWinPercentage() < minWinPercentage
              || (!result.isHasLandUnitRemaining() && minWinTerritory == null)) {
            final List<Unit> attackingUnits = patd.getUnits();
            final List<Unit> defendingUnits = patd.getMaxEnemyDefenders(player, data);
            final boolean isOverwhelmingWin =
                battleUtils.checkForOverwhelmingWin(player, t, attackingUnits, defendingUnits);
            final boolean hasAA = Match.someMatch(defendingUnits, Matches.UnitIsAAforAnything);
            if (!hasAA && !isOverwhelmingWin) {
              minWinPercentage = result.getWinPercentage();
              minWinTerritory = t;
            }
          }
        }
        if (minWinTerritory != null) {
          attackMap.get(minWinTerritory).addUnit(unit);
          attackMap.get(minWinTerritory).setBattleResult(null);
          it.remove();
        }
      }

      // Re-sort attack options
      sortedUnitAttackOptions =
          attackOptionsUtils.sortUnitNeededOptionsThenAttack(player, sortedUnitAttackOptions, attackMap,
              unitTerritoryMap);

      // Find territory that we can try to hold that needs unit
      for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();) {
        final Unit unit = it.next();
        Territory minWinTerritory = null;
        for (final Territory t : sortedUnitAttackOptions.get(unit)) {
          final ProTerritory patd = attackMap.get(t);
          if (patd.isCanHold()) {

            // Check if I already have enough attack units to win in 2 rounds
            if (patd.getBattleResult() == null) {
              patd.setBattleResult(battleUtils.estimateAttackBattleResults(player, t, patd.getUnits(),
                  patd.getMaxEnemyDefenders(player, data), patd.getBombardTerritoryMap().keySet()));
            }
            final ProBattleResult result = patd.getBattleResult();
            final List<Unit> attackingUnits = patd.getUnits();
            final List<Unit> defendingUnits = patd.getMaxEnemyDefenders(player, data);
            final boolean isOverwhelmingWin =
                battleUtils.checkForOverwhelmingWin(player, t, attackingUnits, defendingUnits);
            if (!isOverwhelmingWin && result.getBattleRounds() > 2) {
              minWinTerritory = t;
              break;
            }
          }
        }
        if (minWinTerritory != null) {
          attackMap.get(minWinTerritory).addUnit(unit);
          attackMap.get(minWinTerritory).setBattleResult(null);
          it.remove();
        }
      }

      // Re-sort attack options
      sortedUnitAttackOptions =
          attackOptionsUtils.sortUnitNeededOptionsThenAttack(player, sortedUnitAttackOptions, attackMap,
              unitTerritoryMap);

      // Add sea units to any territory that significantly increases TUV gain
      for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();) {
        final Unit unit = it.next();
        final boolean isSeaUnit = UnitAttachment.get(unit.getType()).getIsSea();
        if (!isSeaUnit) {
          continue; // skip non-sea units
        }
        for (final Territory t : sortedUnitAttackOptions.get(unit)) {
          final ProTerritory patd = attackMap.get(t);
          if (attackMap.get(t).getBattleResult() == null) {
            attackMap.get(t).setBattleResult(
                battleUtils.estimateAttackBattleResults(player, t, patd.getUnits(),
                    patd.getMaxEnemyDefenders(player, data), patd.getBombardTerritoryMap().keySet()));
          }
          final ProBattleResult result = attackMap.get(t).getBattleResult();
          final List<Unit> attackers = new ArrayList<Unit>(patd.getUnits());
          attackers.add(unit);
          final ProBattleResult result2 =
              battleUtils.estimateAttackBattleResults(player, t, attackers, patd.getMaxEnemyDefenders(player, data),
                  patd.getBombardTerritoryMap().keySet());
          final double unitValue = playerCostMap.getInt(unit.getType());
          if ((result2.getTUVSwing() - unitValue / 3) > result.getTUVSwing()) {
            attackMap.get(t).addUnit(unit);
            attackMap.get(t).setBattleResult(null);
            it.remove();
            break;
          }
        }
      }

      // Determine if all attacks are worth it
      final List<Unit> usedUnits = new ArrayList<Unit>();
      for (final ProTerritory patd : prioritizedTerritories) {
        usedUnits.addAll(patd.getUnits());
      }
      ProTerritory territoryToRemove = null;
      for (final ProTerritory patd : prioritizedTerritories) {
        final Territory t = patd.getTerritory();

        // Find battle result
        if (patd.getBattleResult() == null) {
          patd.setBattleResult(battleUtils.estimateAttackBattleResults(player, t, patd.getUnits(),
              patd.getMaxEnemyDefenders(player, data), patd.getBombardTerritoryMap().keySet()));
        }
        final ProBattleResult result = patd.getBattleResult();

        // Determine enemy counter attack results
        boolean canHold = true;
        double enemyCounterTUVSwing = 0;
        if (enemyAttackOptions.getMax(t) != null
            && !ProMatches.territoryIsWaterAndAdjacentToOwnedFactory(player, data).match(t)) {
          List<Unit> remainingUnitsToDefendWith =
              Match.getMatches(result.getAverageAttackersRemaining(), Matches.UnitIsAir.invert());
          ProBattleResult result2 =
              battleUtils.calculateBattleResults(player, t, patd.getMaxEnemyUnits(), remainingUnitsToDefendWith,
                  patd.getMaxBombardUnits(), false);
          if (patd.isCanHold() && result2.getTUVSwing() > 0) {
            final List<Unit> unusedUnits = new ArrayList<Unit>(patd.getMaxUnits());
            unusedUnits.addAll(patd.getMaxAmphibUnits());
            unusedUnits.removeAll(usedUnits);
            unusedUnits.addAll(remainingUnitsToDefendWith);
            final ProBattleResult result3 =
                battleUtils.calculateBattleResults(player, t, patd.getMaxEnemyUnits(), unusedUnits,
                    patd.getMaxBombardUnits(), false);
            if (result3.getTUVSwing() < result2.getTUVSwing()) {
              result2 = result3;
              remainingUnitsToDefendWith = unusedUnits;
            }
          }
          canHold =
              (!result2.isHasLandUnitRemaining() && !t.isWater()) || (result2.getTUVSwing() < 0)
                  || (result2.getWinPercentage() < MIN_WIN_PERCENTAGE);
          if (result2.getTUVSwing() > 0) {
            enemyCounterTUVSwing = result2.getTUVSwing();
          }
          ProLogger.trace("Territory=" + t.getName() + ", CanHold=" + canHold + ", MyDefenders="
              + remainingUnitsToDefendWith.size() + ", EnemyAttackers=" + patd.getMaxEnemyUnits().size() + ", win%="
              + result2.getWinPercentage() + ", EnemyTUVSwing=" + result2.getTUVSwing() + ", hasLandUnitRemaining="
              + result2.isHasLandUnitRemaining());
        }

        // Find attack value
        final boolean isNeutral = (!t.isWater() && t.getOwner().isNull());
        final int isLand = !t.isWater() ? 1 : 0;
        final int isCanHold = canHold ? 1 : 0;
        final int isCantHoldAmphib = !canHold && !patd.getAmphibAttackMap().isEmpty() ? 1 : 0;
        final int isFactory = ProMatches.territoryHasInfraFactoryAndIsLand(player).match(t) ? 1 : 0;
        final int isFFA = utils.isFFA(data, player) ? 1 : 0;
        final int production = TerritoryAttachment.getProduction(t);
        double capitalValue = 0;
        final TerritoryAttachment ta = TerritoryAttachment.get(t);
        if (ta != null && ta.isCapital()) {
          capitalValue = utils.getPlayerProduction(t.getOwner(), data);
        }
        final double territoryValue =
            (1 + isLand - isCantHoldAmphib + isFactory + isCanHold * (1 + 2 * isFFA + 2 * isFactory)) * production
                + capitalValue;
        double TUVSwing = result.getTUVSwing();
        if (isFFA == 1 && TUVSwing > 0) {
          TUVSwing *= 0.5;
        }
        final double attackValue =
            TUVSwing + territoryValue * result.getWinPercentage() / 100 - enemyCounterTUVSwing * 2 / 3;
        boolean allUnitsCanAttackOtherTerritory = true;
        if (isNeutral && attackValue < 0) {
          for (final Unit u : patd.getUnits()) {
            boolean canAttackOtherTerritory = false;
            for (final ProTerritory patd2 : prioritizedTerritories) {
              if (!patd.equals(patd2) && unitAttackMap.get(u) != null
                  && unitAttackMap.get(u).contains(patd2.getTerritory())) {
                canAttackOtherTerritory = true;
                break;
              }
            }
            if (!canAttackOtherTerritory) {
              allUnitsCanAttackOtherTerritory = false;
              break;
            }
          }
        }

        // Determine whether to remove attack
        if (!patd.isStrafing()
            && (result.getWinPercentage() < MIN_WIN_PERCENTAGE || !result.isHasLandUnitRemaining()
                || (isNeutral && !canHold) || (attackValue < 0 && (!isNeutral || allUnitsCanAttackOtherTerritory || result
                .getBattleRounds() >= 4)))) {
          territoryToRemove = patd;
        }
        ProLogger.debug(patd.getResultString() + ", attackValue=" + attackValue + ", territoryValue=" + territoryValue
            + ", allUnitsCanAttackOtherTerritory=" + allUnitsCanAttackOtherTerritory + " with attackers="
            + patd.getUnits());
      }

      // Determine whether all attacks are successful or try to hold fewer territories
      if (territoryToRemove == null) {
        break;
      } else {
        prioritizedTerritories.remove(territoryToRemove);
        ProLogger.debug("Removing " + territoryToRemove.getTerritory().getName());
      }
    }
  }

  private Map<Unit, Set<Territory>> tryToAttackTerritories(final Map<Territory, ProTerritory> attackMap,
      final ProMoveOptions enemyAttackOptions, final Map<Unit, Set<Territory>> unitAttackMap,
      final List<ProTerritory> prioritizedTerritories, final List<ProTransport> transportMapList,
      final Map<Unit, Set<Territory>> transportAttackMap, final Map<Unit, Set<Territory>> bombardMap,
      final List<Unit> alreadyMovedUnits) {

    final Map<Unit, Territory> unitTerritoryMap = utils.createUnitTerritoryMap(player);
    final IntegerMap<UnitType> playerCostMap = BattleCalculator.getCostsForTUV(player, data);

    // Reset lists
    for (final Territory t : attackMap.keySet()) {
      attackMap.get(t).getUnits().clear();
      attackMap.get(t).getBombardTerritoryMap().clear();
      attackMap.get(t).getAmphibAttackMap().clear();
      attackMap.get(t).getTransportTerritoryMap().clear();
      attackMap.get(t).setBattleResult(null);
    }

    // Loop through all units and determine attack options
    final Map<Unit, Set<Territory>> unitAttackOptions = new HashMap<Unit, Set<Territory>>();
    for (final Unit unit : unitAttackMap.keySet()) {

      // Find number of attack options
      final Set<Territory> canAttackTerritories = new HashSet<Territory>();
      for (final ProTerritory attackTerritoryData : prioritizedTerritories) {
        if (unitAttackMap.get(unit).contains(attackTerritoryData.getTerritory())) {
          canAttackTerritories.add(attackTerritoryData.getTerritory());
        }
      }

      // Add units with attack options to map
      if (canAttackTerritories.size() >= 1) {
        unitAttackOptions.put(unit, canAttackTerritories);
      }
    }

    // Sort units by number of attack options and cost
    Map<Unit, Set<Territory>> sortedUnitAttackOptions =
        attackOptionsUtils.sortUnitMoveOptions(player, unitAttackOptions);

    // Try to set at least one destroyer in each sea territory with subs
    for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();) {
      final Unit unit = it.next();
      final boolean isDestroyerUnit = UnitAttachment.get(unit.getType()).getIsDestroyer();
      if (!isDestroyerUnit) {
        continue; // skip non-destroyer units
      }
      for (final Territory t : sortedUnitAttackOptions.get(unit)) {

        // Add destroyer if territory has subs and a destroyer has been already added
        final List<Unit> defendingUnits = attackMap.get(t).getMaxEnemyDefenders(player, data);
        if (Match.someMatch(defendingUnits, Matches.UnitIsSub)
            && Match.noneMatch(attackMap.get(t).getUnits(), Matches.UnitIsDestroyer)) {
          attackMap.get(t).addUnit(unit);
          it.remove();
          break;
        }
      }
    }

    // Set enough land and sea units in territories to have at least a chance of winning
    for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();) {
      final Unit unit = it.next();
      final boolean isAirUnit = UnitAttachment.get(unit.getType()).getIsAir();
      if (isAirUnit) {
        continue; // skip air units
      }
      final TreeMap<Double, Territory> estimatesMap = new TreeMap<Double, Territory>();
      for (final Territory t : sortedUnitAttackOptions.get(unit)) {
        if (t.isWater() && !attackMap.get(t).isCanHold()) {
          continue; // ignore sea territories that can't be held
        }
        final List<Unit> defendingUnits = attackMap.get(t).getMaxEnemyDefenders(player, data);
        double estimate = battleUtils.estimateStrengthDifference(t, attackMap.get(t).getUnits(), defendingUnits);
        final boolean hasAA = Match.someMatch(defendingUnits, Matches.UnitIsAAforAnything);
        if (hasAA) {
          estimate -= 10;
        }
        estimatesMap.put(estimate, t);
      }
      if (!estimatesMap.isEmpty() && estimatesMap.firstKey() < 40) {
        final Territory minWinTerritory = estimatesMap.entrySet().iterator().next().getValue();
        attackMap.get(minWinTerritory).addUnit(unit);
        it.remove();
      }
    }

    // Re-sort attack options
    sortedUnitAttackOptions =
        attackOptionsUtils
            .sortUnitNeededOptionsThenAttack(player, sortedUnitAttackOptions, attackMap, unitTerritoryMap);

    // Set non-air units in territories that can be held
    for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();) {
      final Unit unit = it.next();
      final boolean isAirUnit = UnitAttachment.get(unit.getType()).getIsAir();
      if (isAirUnit) {
        continue; // skip air units
      }
      Territory minWinTerritory = null;
      double minWinPercentage = WIN_PERCENTAGE;
      for (final Territory t : sortedUnitAttackOptions.get(unit)) {
        final ProTerritory patd = attackMap.get(t);
        if (!attackMap.get(t).isCurrentlyWins() && attackMap.get(t).isCanHold()) {
          if (attackMap.get(t).getBattleResult() == null) {
            attackMap.get(t).setBattleResult(
                battleUtils.estimateAttackBattleResults(player, t, patd.getUnits(),
                    patd.getMaxEnemyDefenders(player, data), patd.getBombardTerritoryMap().keySet()));
          }
          final ProBattleResult result = attackMap.get(t).getBattleResult();
          if (result.getWinPercentage() < minWinPercentage
              || (!result.isHasLandUnitRemaining() && minWinTerritory == null)) {
            minWinPercentage = result.getWinPercentage();
            minWinTerritory = t;
          }
        }
      }
      if (minWinTerritory != null) {
        attackMap.get(minWinTerritory).addUnit(unit);
        attackMap.get(minWinTerritory).setBattleResult(null);
        it.remove();
      }
    }

    // Re-sort attack options
    sortedUnitAttackOptions =
        attackOptionsUtils
            .sortUnitNeededOptionsThenAttack(player, sortedUnitAttackOptions, attackMap, unitTerritoryMap);

    // Set air units in territories that can't be held (don't move planes to empty territories)
    for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();) {
      final Unit unit = it.next();
      final boolean isAirUnit = UnitAttachment.get(unit.getType()).getIsAir();
      if (!isAirUnit) {
        continue; // skip non-air units
      }
      Territory minWinTerritory = null;
      double minWinPercentage = WIN_PERCENTAGE;
      for (final Territory t : sortedUnitAttackOptions.get(unit)) {
        final ProTerritory patd = attackMap.get(t);
        if (!patd.isCurrentlyWins() && !patd.isCanHold()) {

          // Check if air unit should avoid this territory due to no guaranteed safe landing location
          final boolean isEnemyCapital = utils.getLiveEnemyCapitals(data, player).contains(t);
          final boolean isAdjacentToAlliedCapital =
              Matches.territoryHasNeighborMatching(data,
                  Matches.territoryIsInList(utils.getLiveAlliedCapitals(data, player))).match(t);
          final int range = TripleAUnit.get(unit).getMovementLeft();
          final int distance =
              data.getMap().getDistance_IgnoreEndForCondition(unitTerritoryMap.get(unit), t,
                  ProMatches.territoryCanMoveAirUnitsAndNoAA(player, data, true));
          final boolean usesMoreThanHalfOfRange = distance > range / 2;
          if (isAirUnit && !isEnemyCapital && !isAdjacentToAlliedCapital && usesMoreThanHalfOfRange) {
            continue;
          }

          // Check battle results
          if (patd.getBattleResult() == null) {
            patd.setBattleResult(battleUtils.estimateAttackBattleResults(player, t, patd.getUnits(),
                patd.getMaxEnemyDefenders(player, data), patd.getBombardTerritoryMap().keySet()));
          }
          final ProBattleResult result = patd.getBattleResult();
          if (result.getWinPercentage() < minWinPercentage
              || (!result.isHasLandUnitRemaining() && minWinTerritory == null)) {
            final List<Unit> defendingUnits = patd.getMaxEnemyDefenders(player, data);
            final boolean hasNoDefenders =
                Match.noneMatch(defendingUnits, ProMatches.unitIsEnemyAndNotInfa(player, data));
            final boolean isOverwhelmingWin =
                battleUtils.checkForOverwhelmingWin(player, t, patd.getUnits(), defendingUnits);
            final boolean hasAA = Match.someMatch(defendingUnits, Matches.UnitIsAAforAnything);
            if (!hasNoDefenders && !isOverwhelmingWin && (!hasAA || result.getWinPercentage() < minWinPercentage)) {
              minWinPercentage = result.getWinPercentage();
              minWinTerritory = t;
              if (patd.isStrafing()) {
                break;
              }
            }
          }
        }
      }
      if (minWinTerritory != null) {
        attackMap.get(minWinTerritory).addUnit(unit);
        attackMap.get(minWinTerritory).setBattleResult(null);
        it.remove();
      }
    }

    // Re-sort attack options
    sortedUnitAttackOptions =
        attackOptionsUtils
            .sortUnitNeededOptionsThenAttack(player, sortedUnitAttackOptions, attackMap, unitTerritoryMap);

    // Set remaining units in any territory that needs it (don't move planes to empty territories)
    for (final Iterator<Unit> it = sortedUnitAttackOptions.keySet().iterator(); it.hasNext();) {
      final Unit unit = it.next();
      final boolean isAirUnit = UnitAttachment.get(unit.getType()).getIsAir();
      Territory minWinTerritory = null;
      double minWinPercentage = WIN_PERCENTAGE;
      for (final Territory t : sortedUnitAttackOptions.get(unit)) {
        final ProTerritory patd = attackMap.get(t);
        if (!patd.isCurrentlyWins()) {

          // Check if air unit should avoid this territory due to no guaranteed safe landing location
          final boolean isAdjacentToAlliedFactory =
              Matches.territoryHasNeighborMatching(data,
                  ProMatches.territoryHasInfraFactoryAndIsAlliedLand(player, data)).match(t);
          final int range = TripleAUnit.get(unit).getMovementLeft();
          final int distance =
              data.getMap().getDistance_IgnoreEndForCondition(unitTerritoryMap.get(unit), t,
                  ProMatches.territoryCanMoveAirUnitsAndNoAA(player, data, true));
          final boolean usesMoreThanHalfOfRange = distance > range / 2;
          final boolean territoryValueIsLessThanUnitValue = patd.getValue() < playerCostMap.getInt(unit.getType());
          if (isAirUnit && !isAdjacentToAlliedFactory && usesMoreThanHalfOfRange
              && (territoryValueIsLessThanUnitValue || (!t.isWater() && !patd.isCanHold()))) {
            continue;
          }
          if (patd.getBattleResult() == null) {
            patd.setBattleResult(battleUtils.estimateAttackBattleResults(player, t, patd.getUnits(),
                patd.getMaxEnemyDefenders(player, data), patd.getBombardTerritoryMap().keySet()));
          }
          final ProBattleResult result = patd.getBattleResult();
          if (result.getWinPercentage() < minWinPercentage
              || (!result.isHasLandUnitRemaining() && minWinTerritory == null)) {
            final List<Unit> defendingUnits = patd.getMaxEnemyDefenders(player, data);
            final boolean hasNoDefenders =
                Match.noneMatch(defendingUnits, ProMatches.unitIsEnemyAndNotInfa(player, data));
            final boolean isOverwhelmingWin =
                battleUtils.checkForOverwhelmingWin(player, t, patd.getUnits(), defendingUnits);
            final boolean hasAA = Match.someMatch(defendingUnits, Matches.UnitIsAAforAnything);
            if (!isAirUnit
                || (!hasNoDefenders && !isOverwhelmingWin && (!hasAA || result.getWinPercentage() < minWinPercentage))) {
              minWinPercentage = result.getWinPercentage();
              minWinTerritory = t;
            }
          }
        }
      }
      if (minWinTerritory != null) {
        attackMap.get(minWinTerritory).addUnit(unit);
        attackMap.get(minWinTerritory).setBattleResult(null);
        it.remove();
      }
    }

    // Re-sort attack options
    sortedUnitAttackOptions = attackOptionsUtils.sortUnitNeededOptions(player, sortedUnitAttackOptions, attackMap);

    // If transports can take casualties try placing in naval battles first
    final List<Unit> alreadyAttackedWithTransports = new ArrayList<Unit>();
    if (!Properties.getTransportCasualtiesRestricted(data)) {

      // Loop through all my transports and see which territories they can attack from current list
      final Map<Unit, Set<Territory>> transportAttackOptions = new HashMap<Unit, Set<Territory>>();
      for (final Unit unit : transportAttackMap.keySet()) {

        // Find number of attack options
        final Set<Territory> canAttackTerritories = new HashSet<Territory>();
        for (final ProTerritory attackTerritoryData : prioritizedTerritories) {
          if (transportAttackMap.get(unit).contains(attackTerritoryData.getTerritory())) {
            canAttackTerritories.add(attackTerritoryData.getTerritory());
          }
        }
        if (!canAttackTerritories.isEmpty()) {
          transportAttackOptions.put(unit, canAttackTerritories);
        }
      }

      // Loop through transports with attack options and determine if any naval battle needs it
      for (final Unit transport : transportAttackOptions.keySet()) {

        // Find current naval battle that needs transport if it isn't transporting units
        for (final Territory t : transportAttackOptions.get(transport)) {
          final ProTerritory patd = attackMap.get(t);
          final List<Unit> defendingUnits = patd.getMaxEnemyDefenders(player, data);
          if (!patd.isCurrentlyWins() && !TransportTracker.isTransporting(transport) && !defendingUnits.isEmpty()) {
            if (patd.getBattleResult() == null) {
              patd.setBattleResult(battleUtils.estimateAttackBattleResults(player, t, patd.getUnits(),
                  patd.getMaxEnemyDefenders(player, data), patd.getBombardTerritoryMap().keySet()));
            }
            final ProBattleResult result = patd.getBattleResult();
            if (result.getWinPercentage() < WIN_PERCENTAGE || !result.isHasLandUnitRemaining()) {
              patd.addUnit(transport);
              patd.setBattleResult(null);
              alreadyAttackedWithTransports.add(transport);
              ProLogger.trace("Adding attack transport to: " + t.getName());
              break;
            }
          }
        }
      }
    }

    // Loop through all my transports and see which can make amphib attack
    final Map<Unit, Set<Territory>> amphibAttackOptions = new HashMap<Unit, Set<Territory>>();
    for (final ProTransport proTransportData : transportMapList) {

      // If already used to attack then ignore
      if (alreadyAttackedWithTransports.contains(proTransportData.getTransport())) {
        continue;
      }

      // Find number of attack options
      final Set<Territory> canAmphibAttackTerritories = new HashSet<Territory>();
      for (final ProTerritory attackTerritoryData : prioritizedTerritories) {
        if (proTransportData.getTransportMap().containsKey(attackTerritoryData.getTerritory())) {
          canAmphibAttackTerritories.add(attackTerritoryData.getTerritory());
        }
      }
      if (!canAmphibAttackTerritories.isEmpty()) {
        amphibAttackOptions.put(proTransportData.getTransport(), canAmphibAttackTerritories);
      }
    }

    // Loop through transports with amphib attack options and determine if any land battle needs it
    for (final Unit transport : amphibAttackOptions.keySet()) {

      // Find current land battle results for territories that unit can amphib attack
      Territory minWinTerritory = null;
      double minWinPercentage = WIN_PERCENTAGE;
      List<Unit> minAmphibUnitsToAdd = null;
      Territory minUnloadFromTerritory = null;
      for (final Territory t : amphibAttackOptions.get(transport)) {
        final ProTerritory patd = attackMap.get(t);
        if (!patd.isCurrentlyWins() && !patd.isStrafing()) {
          if (patd.getBattleResult() == null) {
            patd.setBattleResult(battleUtils.estimateAttackBattleResults(player, t, patd.getUnits(),
                patd.getMaxEnemyDefenders(player, data), patd.getBombardTerritoryMap().keySet()));
          }
          final ProBattleResult result = patd.getBattleResult();
          if (result.getWinPercentage() < minWinPercentage
              || (!result.isHasLandUnitRemaining() && minWinTerritory == null)) {

            // Get all units that have already attacked
            final List<Unit> alreadyAttackedWithUnits = new ArrayList<Unit>(alreadyMovedUnits);
            for (final Territory t2 : attackMap.keySet()) {
              alreadyAttackedWithUnits.addAll(attackMap.get(t2).getUnits());
            }

            // Find units that haven't attacked and can be transported
            for (final ProTransport proTransportData : transportMapList) {
              if (proTransportData.getTransport().equals(transport)) {

                // Find units to load
                final Set<Territory> territoriesCanLoadFrom = proTransportData.getTransportMap().get(t);
                final List<Unit> amphibUnitsToAdd =
                    transportUtils.getUnitsToTransportFromTerritories(player, transport, territoriesCanLoadFrom,
                        alreadyAttackedWithUnits);
                if (amphibUnitsToAdd.isEmpty()) {
                  continue;
                }

                // Find best territory to move transport
                double minStrengthDifference = Double.POSITIVE_INFINITY;
                minUnloadFromTerritory = null;
                final Set<Territory> territoriesToMoveTransport =
                    data.getMap().getNeighbors(t, ProMatches.territoryCanMoveSeaUnits(player, data, false));
                final Set<Territory> loadFromTerritories = new HashSet<Territory>();
                for (final Unit u : amphibUnitsToAdd) {
                  loadFromTerritories.add(unitTerritoryMap.get(u));
                }
                for (final Territory territoryToMoveTransport : territoriesToMoveTransport) {
                  if (proTransportData.getSeaTransportMap().containsKey(territoryToMoveTransport)
                      && proTransportData.getSeaTransportMap().get(territoryToMoveTransport)
                          .containsAll(loadFromTerritories)) {
                    List<Unit> attackers = new ArrayList<Unit>();
                    if (enemyAttackOptions.getMax(territoryToMoveTransport) != null) {
                      attackers = enemyAttackOptions.getMax(territoryToMoveTransport).getMaxUnits();
                    }
                    final List<Unit> defenders =
                        territoryToMoveTransport.getUnits().getMatches(Matches.isUnitAllied(player, data));
                    defenders.add(transport);
                    final double strengthDifference =
                        battleUtils.estimateStrengthDifference(territoryToMoveTransport, attackers, defenders);
                    if (strengthDifference < minStrengthDifference) {
                      minStrengthDifference = strengthDifference;
                      minUnloadFromTerritory = territoryToMoveTransport;
                    }
                  }
                }
                minWinTerritory = t;
                minWinPercentage = result.getWinPercentage();
                minAmphibUnitsToAdd = amphibUnitsToAdd;
                break;
              }
            }
          }
        }
      }
      if (minWinTerritory != null) {
        if (minUnloadFromTerritory != null) {
          attackMap.get(minWinTerritory).getTransportTerritoryMap().put(transport, minUnloadFromTerritory);
        }
        attackMap.get(minWinTerritory).addUnits(minAmphibUnitsToAdd);
        attackMap.get(minWinTerritory).putAmphibAttackMap(transport, minAmphibUnitsToAdd);
        attackMap.get(minWinTerritory).setBattleResult(null);
        for (final Unit unit : minAmphibUnitsToAdd) {
          sortedUnitAttackOptions.remove(unit);
        }
        ProLogger.trace("Adding amphibious attack to " + minWinTerritory + ", units=" + minAmphibUnitsToAdd.size()
            + ", unloadFrom=" + minUnloadFromTerritory);
      }
    }

    // Get all units that have already moved
    final Set<Unit> alreadyAttackedWithUnits = new HashSet<Unit>();
    for (final Territory t : attackMap.keySet()) {
      alreadyAttackedWithUnits.addAll(attackMap.get(t).getUnits());
      alreadyAttackedWithUnits.addAll(attackMap.get(t).getAmphibAttackMap().keySet());
    }

    // Loop through all my bombard units and see which can bombard
    final Map<Unit, Set<Territory>> bombardOptions = new HashMap<Unit, Set<Territory>>();
    for (final Unit u : bombardMap.keySet()) {

      // If already used to attack then ignore
      if (alreadyAttackedWithUnits.contains(u)) {
        continue;
      }

      // Find number of bombard options
      final Set<Territory> canBombardTerritories = new HashSet<Territory>();
      for (final ProTerritory patd : prioritizedTerritories) {
        final List<Unit> defendingUnits = patd.getMaxEnemyDefenders(player, data);
        final boolean hasDefenders = Match.someMatch(defendingUnits, Matches.UnitIsInfrastructure.invert());
        if (bombardMap.get(u).contains(patd.getTerritory()) && !patd.getTransportTerritoryMap().isEmpty()
            && hasDefenders && !TransportTracker.isTransporting(u)) {
          canBombardTerritories.add(patd.getTerritory());
        }
      }
      if (!canBombardTerritories.isEmpty()) {
        bombardOptions.put(u, canBombardTerritories);
      }
    }

    // Loop through bombard units to see if any amphib battles need
    for (final Unit u : bombardOptions.keySet()) {

      // Find current land battle results for territories that unit can bombard
      Territory minWinTerritory = null;
      double minWinPercentage = Double.MAX_VALUE;
      Territory minBombardFromTerritory = null;
      for (final Territory t : bombardOptions.get(u)) {
        final ProTerritory patd = attackMap.get(t);
        if (patd.getBattleResult() == null) {
          patd.setBattleResult(battleUtils.estimateAttackBattleResults(player, t, patd.getUnits(),
              patd.getMaxEnemyDefenders(player, data), patd.getBombardTerritoryMap().keySet()));
        }
        final ProBattleResult result = patd.getBattleResult();
        if (result.getWinPercentage() < minWinPercentage
            || (!result.isHasLandUnitRemaining() && minWinTerritory == null)) {

          // Find territory to bombard from
          Territory bombardFromTerritory = null;
          for (final Territory unloadFromTerritory : patd.getTransportTerritoryMap().values()) {
            if (patd.getBombardOptionsMap().get(u).contains(unloadFromTerritory)) {
              bombardFromTerritory = unloadFromTerritory;
            }
          }
          if (bombardFromTerritory != null) {
            minWinTerritory = t;
            minWinPercentage = result.getWinPercentage();
            minBombardFromTerritory = bombardFromTerritory;
          }
        }
      }
      if (minWinTerritory != null) {
        attackMap.get(minWinTerritory).getBombardTerritoryMap().put(u, minBombardFromTerritory);
        attackMap.get(minWinTerritory).setBattleResult(null);
        sortedUnitAttackOptions.remove(u);
        ProLogger.trace("Adding bombard to " + minWinTerritory + ", units=" + u + ", bombardFrom="
            + minBombardFromTerritory);
      }
    }
    return sortedUnitAttackOptions;
  }

  private void determineIfCapitalCanBeHeld(final Map<Territory, ProTerritory> attackMap,
      final List<ProTerritory> prioritizedTerritories, final List<ProPurchaseOption> landPurchaseOptions) {

    ProLogger.info("Determine if capital can be held");

    // Determine max number of defenders I can purchase
    final List<Unit> placeUnits = purchaseUtils.findMaxPurchaseDefenders(player, myCapital, landPurchaseOptions);

    // Remove attack until capital can be defended
    final Map<Unit, Territory> unitTerritoryMap = utils.createUnitTerritoryMap(player);
    while (true) {
      if (prioritizedTerritories.isEmpty()) {
        break;
      }

      // Determine max enemy counter attack units
      final List<Territory> territoriesToAttack = new ArrayList<Territory>();
      for (final ProTerritory t : prioritizedTerritories) {
        territoriesToAttack.add(t.getTerritory());
      }
      ProLogger.trace("Remaining territories to attack=" + territoriesToAttack);
      final List<Territory> territoriesToCheck = new ArrayList<Territory>();
      territoriesToCheck.add(myCapital);
      final ProMoveOptions enemyAttackOptions =
          attackOptionsUtils.findEnemyAttackOptions(player, territoriesToAttack, territoriesToCheck);
      if (enemyAttackOptions.getMax(myCapital) == null) {
        break;
      }

      // Find max remaining defenders
      final Set<Territory> territoriesAdjacentToCapital =
          data.getMap().getNeighbors(myCapital, Matches.TerritoryIsLand);
      final List<Unit> defenders = myCapital.getUnits().getMatches(Matches.isUnitAllied(player, data));
      defenders.addAll(placeUnits);
      for (final Territory t : territoriesAdjacentToCapital) {
        defenders.addAll(t.getUnits().getMatches(ProMatches.unitCanBeMovedAndIsOwnedLand(player, false)));
      }
      for (final Territory t : attackMap.keySet()) {
        defenders.removeAll(attackMap.get(t).getUnits());
      }

      // Determine counter attack results to see if I can hold it
      final Set<Unit> enemyAttackingUnits = new HashSet<Unit>(enemyAttackOptions.getMax(myCapital).getMaxUnits());
      enemyAttackingUnits.addAll(enemyAttackOptions.getMax(myCapital).getMaxAmphibUnits());
      final ProBattleResult result =
          battleUtils.estimateDefendBattleResults(player, myCapital, new ArrayList<Unit>(enemyAttackingUnits),
              defenders, enemyAttackOptions.getMax(myCapital).getMaxBombardUnits());
      ProLogger.trace("Current capital result hasLandUnitRemaining=" + result.isHasLandUnitRemaining() + ", TUVSwing="
          + result.getTUVSwing() + ", defenders=" + defenders.size() + ", attackers=" + enemyAttackingUnits.size());

      // Determine attack that uses the most units per value from capital and remove it
      if (result.isHasLandUnitRemaining()) {
        double maxUnitsNearCapitalPerValue = 0.0;
        Territory maxTerritory = null;
        final Set<Territory> territoriesNearCapital = data.getMap().getNeighbors(myCapital, Matches.TerritoryIsLand);
        territoriesNearCapital.add(myCapital);
        for (final Territory t : attackMap.keySet()) {
          int unitsNearCapital = 0;
          for (final Unit u : attackMap.get(t).getUnits()) {
            if (territoriesNearCapital.contains(unitTerritoryMap.get(u))) {
              unitsNearCapital++;
            }
          }
          final double unitsNearCapitalPerValue = unitsNearCapital / attackMap.get(t).getValue();
          ProLogger.trace(t.getName() + " has unit near capital per value: " + unitsNearCapitalPerValue);
          if (unitsNearCapitalPerValue > maxUnitsNearCapitalPerValue) {
            maxUnitsNearCapitalPerValue = unitsNearCapitalPerValue;
            maxTerritory = t;
          }
        }
        if (maxTerritory != null) {
          prioritizedTerritories.remove(attackMap.get(maxTerritory));
          attackMap.get(maxTerritory).getUnits().clear();
          attackMap.get(maxTerritory).getAmphibAttackMap().clear();
          attackMap.get(maxTerritory).setBattleResult(null);
          ProLogger.debug("Removing territory to try to hold capital: " + maxTerritory.getName());
        } else {
          break;
        }
      } else {
        ProLogger.debug("Can hold capital: " + myCapital.getName());
        break;
      }
    }
  }

  private void checkContestedSeaTerritories(final Map<Territory, ProTerritory> attackMap,
      final List<Territory> myUnitTerritories) {
    for (final Territory t : myUnitTerritories) {
      if (t.isWater() && Matches.territoryHasEnemyUnits(player, data).match(t)
          && (attackMap.get(t) == null || attackMap.get(t).getUnits().isEmpty())) {

        // Move into random adjacent safe sea territory
        final Set<Territory> possibleMoveTerritories =
            data.getMap().getNeighbors(t, ProMatches.territoryCanMoveSeaUnitsThrough(player, data, true));
        if (!possibleMoveTerritories.isEmpty()) {
          final Territory moveToTerritory = possibleMoveTerritories.iterator().next();
          final List<Unit> mySeaUnits = t.getUnits().getMatches(ProMatches.unitCanBeMovedAndIsOwnedSea(player, true));
          if (attackMap.containsKey(moveToTerritory)) {
            attackMap.get(moveToTerritory).addUnits(mySeaUnits);
          } else {
            final ProTerritory moveTerritoryData = new ProTerritory(moveToTerritory);
            moveTerritoryData.addUnits(mySeaUnits);
            attackMap.put(moveToTerritory, moveTerritoryData);
          }
          ProLogger.info(t + " is a contested territory so moving subs to " + moveToTerritory);
        }
      }
    }
  }

  private void logAttackMoves(final Map<Territory, ProTerritory> attackMap,
      final Map<Unit, Set<Territory>> unitAttackMap, final List<ProTransport> transportMapList,
      final List<ProTerritory> prioritizedTerritories) {

    // Print prioritization
    ProLogger.debug("Prioritized territories:");
    for (final ProTerritory attackTerritoryData : prioritizedTerritories) {
      ProLogger.trace("  " + attackTerritoryData.getMaxBattleResult().getTUVSwing() + "  "
          + attackTerritoryData.getValue() + "  " + attackTerritoryData.getTerritory().getName());
    }

    // Print enemy territories with enemy units vs my units
    ProLogger.debug("Territories that can be attacked:");
    int count = 0;
    for (final Territory t : attackMap.keySet()) {
      count++;
      ProLogger.trace(count + ". ---" + t.getName());
      final Set<Unit> combinedUnits = new HashSet<Unit>(attackMap.get(t).getMaxUnits());
      combinedUnits.addAll(attackMap.get(t).getMaxAmphibUnits());
      ProLogger.trace("  --- My max units ---");
      final Map<String, Integer> printMap = new HashMap<String, Integer>();
      for (final Unit unit : combinedUnits) {
        if (printMap.containsKey(unit.toStringNoOwner())) {
          printMap.put(unit.toStringNoOwner(), printMap.get(unit.toStringNoOwner()) + 1);
        } else {
          printMap.put(unit.toStringNoOwner(), 1);
        }
      }
      for (final String key : printMap.keySet()) {
        ProLogger.trace("    " + printMap.get(key) + " " + key);
      }
      ProLogger.trace("  --- My max bombard units ---");
      final Map<String, Integer> printBombardMap = new HashMap<String, Integer>();
      for (final Unit unit : attackMap.get(t).getMaxBombardUnits()) {
        if (printBombardMap.containsKey(unit.toStringNoOwner())) {
          printBombardMap.put(unit.toStringNoOwner(), printBombardMap.get(unit.toStringNoOwner()) + 1);
        } else {
          printBombardMap.put(unit.toStringNoOwner(), 1);
        }
      }
      for (final String key : printBombardMap.keySet()) {
        ProLogger.trace("    " + printBombardMap.get(key) + " " + key);
      }
      final List<Unit> units3 = attackMap.get(t).getUnits();
      ProLogger.trace("  --- My actual units ---");
      final Map<String, Integer> printMap3 = new HashMap<String, Integer>();
      for (final Unit unit : units3) {
        if (printMap3.containsKey(unit.toStringNoOwner())) {
          printMap3.put(unit.toStringNoOwner(), printMap3.get(unit.toStringNoOwner()) + 1);
        } else {
          printMap3.put(unit.toStringNoOwner(), 1);
        }
      }
      for (final String key : printMap3.keySet()) {
        ProLogger.trace("    " + printMap3.get(key) + " " + key);
      }
      ProLogger.trace("  --- Enemy units ---");
      final Map<String, Integer> printMap2 = new HashMap<String, Integer>();
      final List<Unit> units2 = attackMap.get(t).getMaxEnemyDefenders(player, data);
      for (final Unit unit : units2) {
        if (printMap2.containsKey(unit.toStringNoOwner())) {
          printMap2.put(unit.toStringNoOwner(), printMap2.get(unit.toStringNoOwner()) + 1);
        } else {
          printMap2.put(unit.toStringNoOwner(), 1);
        }
      }
      for (final String key : printMap2.keySet()) {
        ProLogger.trace("    " + printMap2.get(key) + " " + key);
      }
      ProLogger.trace("  --- Enemy Counter Attack Units ---");
      final Map<String, Integer> printMap4 = new HashMap<String, Integer>();
      final List<Unit> units4 = attackMap.get(t).getMaxEnemyUnits();
      for (final Unit unit : units4) {
        if (printMap4.containsKey(unit.toStringNoOwner())) {
          printMap4.put(unit.toStringNoOwner(), printMap4.get(unit.toStringNoOwner()) + 1);
        } else {
          printMap4.put(unit.toStringNoOwner(), 1);
        }
      }
      for (final String key : printMap4.keySet()) {
        ProLogger.trace("    " + printMap4.get(key) + " " + key);
      }
      ProLogger.trace("  --- Enemy Counter Bombard Units ---");
      final Map<String, Integer> printMap5 = new HashMap<String, Integer>();
      final Set<Unit> units5 = attackMap.get(t).getMaxEnemyBombardUnits();
      for (final Unit unit : units5) {
        if (printMap5.containsKey(unit.toStringNoOwner())) {
          printMap5.put(unit.toStringNoOwner(), printMap5.get(unit.toStringNoOwner()) + 1);
        } else {
          printMap5.put(unit.toStringNoOwner(), 1);
        }
      }
      for (final String key : printMap5.keySet()) {
        ProLogger.trace("    " + printMap4.get(key) + " " + key);
      }
    }
  }
}
