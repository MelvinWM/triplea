package games.strategy.triplea.delegate;

import static games.strategy.triplea.delegate.GameDataTestUtil.addTo;
import static games.strategy.triplea.delegate.GameDataTestUtil.territory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.junit.jupiter.api.Test;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.PlayerId;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.triplea.Constants;
import games.strategy.triplea.TripleAUnit;
import games.strategy.triplea.delegate.data.MoveValidationResult;
import games.strategy.triplea.xml.TestMapGameData;

class MoveValidatorTest extends AbstractDelegateTestCase {

  @Test
  void testEnemyUnitsInPath() {
    // japanese unit in congo
    final Route bad = new Route();
    // the empty case
    assertTrue(MoveValidator.noEnemyUnitsOnPathMiddleSteps(bad, british, gameData));
    bad.add(egypt);
    bad.add(congo);
    bad.add(kenya);
    assertTrue(!MoveValidator.noEnemyUnitsOnPathMiddleSteps(bad, british, gameData));
    final Route good = new Route();
    good.add(egypt);
    good.add(kenya);
    assertTrue(MoveValidator.noEnemyUnitsOnPathMiddleSteps(good, british, gameData));
    // at end so should still be good
    good.add(congo);
    assertTrue(MoveValidator.noEnemyUnitsOnPathMiddleSteps(good, british, gameData));
  }

  @Test
  void testHasUnitsThatCantGoOnWater() {
    final Collection<Unit> units = new ArrayList<>();
    units.addAll(infantry.create(1, british));
    units.addAll(armour.create(1, british));
    units.addAll(transport.create(1, british));
    units.addAll(fighter.create(1, british));
    assertTrue(!MoveValidator.hasUnitsThatCantGoOnWater(units));
    assertTrue(MoveValidator.hasUnitsThatCantGoOnWater(factory.create(1, british)));
  }

  @Test
  void testCarrierCapacity() {
    final Collection<Unit> units = carrier.create(5, british);
    assertEquals(10, AirMovementValidator.carrierCapacity(units, new Territory("TestTerritory", true, gameData)));
  }

  @Test
  void testCarrierCost() {
    final Collection<Unit> units = fighter.create(5, british);
    assertEquals(5, AirMovementValidator.carrierCost(units));
  }

  @Test
  void testGetLeastMovement() {
    final Collection<Unit> collection = bomber.create(1, british);
    assertEquals(6, MoveValidator.getLeastMovement(collection));
    final Object[] objs = collection.toArray();
    ((TripleAUnit) objs[0]).setAlreadyMoved(1);
    assertEquals(5, MoveValidator.getLeastMovement(collection));
    collection.addAll(factory.create(2, british));
    assertEquals(0, MoveValidator.getLeastMovement(collection));
  }

  @Test
  void testCanLand() {
    final Collection<Unit> units = fighter.create(4, british);
    // 2 carriers in red sea
    assertTrue(AirMovementValidator.canLand(units, redSea, british, gameData));
    // britian owns egypt
    assertTrue(AirMovementValidator.canLand(units, egypt, british, gameData));
    // only 2 carriers
    final Collection<Unit> tooMany = fighter.create(6, british);
    assertTrue(!AirMovementValidator.canLand(tooMany, redSea, british, gameData));
    // nowhere to land
    assertTrue(!AirMovementValidator.canLand(units, japanSeaZone, british, gameData));
    // nuetral
    assertTrue(!AirMovementValidator.canLand(units, westAfrica, british, gameData));
  }

  @Test
  void testCanLandInfantry() {
    try {
      final Collection<Unit> units = infantry.create(1, british);
      AirMovementValidator.canLand(units, redSea, british, gameData);
    } catch (final IllegalArgumentException e) {
      return;
    }
    fail("No exception thrown");
  }

  @Test
  void testCanLandBomber() {
    final Collection<Unit> units = bomber.create(1, british);
    assertTrue(!AirMovementValidator.canLand(units, redSea, british, gameData));
  }

  @Test
  void testHasSomeLand() {
    final Collection<Unit> units = transport.create(3, british);
    assertTrue(units.stream().noneMatch(Matches.unitIsLand()));
    units.addAll(infantry.create(2, british));
    assertTrue(units.stream().anyMatch(Matches.unitIsLand()));
  }

  @Test
  void testValidateMoveForRequiresUnitsToMove() throws Exception {

    final GameData twwGameData = TestMapGameData.TWW.getGameData();

    // Move regular units
    final PlayerId germans = GameDataTestUtil.germany(twwGameData);
    final Territory berlin = territory("Berlin", twwGameData);
    final Territory easternGermany = territory("Eastern Germany", twwGameData);
    final Route r = new Route(berlin, easternGermany);
    List<Unit> toMove = berlin.getUnitCollection().getMatches(Matches.unitCanMove());
    MoveValidationResult results = MoveValidator.validateMove(toMove, r, germans, Collections.emptyList(),
        new HashMap<>(), false, null, twwGameData);
    assertTrue(results.isMoveValid());

    // Add germanTrain to units which fails since it requires germainRail
    addTo(berlin, GameDataTestUtil.germanTrain(twwGameData).create(1, germans));
    toMove = berlin.getUnitCollection().getMatches(Matches.unitCanMove());
    results = MoveValidator.validateMove(toMove, r, germans, Collections.emptyList(),
        new HashMap<>(), false, null, twwGameData);
    assertFalse(results.isMoveValid());

    // Add germanRail to only destination so it fails
    final Collection<Unit> germanRail = GameDataTestUtil.germanRail(twwGameData).create(1, germans);
    addTo(easternGermany, germanRail);
    results = MoveValidator.validateMove(toMove, r, germans, Collections.emptyList(),
        new HashMap<>(), false, null, twwGameData);
    assertFalse(results.isMoveValid());

    // Add germanRail to start so move succeeds
    addTo(berlin, GameDataTestUtil.germanRail(twwGameData).create(1, germans));
    results = MoveValidator.validateMove(toMove, r, germans, Collections.emptyList(),
        new HashMap<>(), false, null, twwGameData);
    assertTrue(results.isMoveValid());

    // Remove germanRail from destination so move fails
    GameDataTestUtil.removeFrom(easternGermany, germanRail);
    results = MoveValidator.validateMove(toMove, r, germans, Collections.emptyList(),
        new HashMap<>(), false, null, twwGameData);
    assertFalse(results.isMoveValid());

    // Add allied owned germanRail to destination so move succeeds
    final PlayerId japan = GameDataTestUtil.japan(twwGameData);
    addTo(easternGermany, GameDataTestUtil.germanRail(twwGameData).create(1, japan));
    results = MoveValidator.validateMove(toMove, r, germans, Collections.emptyList(),
        new HashMap<>(), false, null, twwGameData);
    assertTrue(results.isMoveValid());
  }

  @Test
  void testValidateMoveForLandTransports() throws Exception {

    final GameData twwGameData = TestMapGameData.TWW.getGameData();

    // Move truck 2 territories
    final PlayerId germans = GameDataTestUtil.germany(twwGameData);
    final Territory berlin = territory("Berlin", twwGameData);
    final Territory easternGermany = territory("Eastern Germany", twwGameData);
    final Territory poland = territory("Poland", twwGameData);
    final Route r = new Route(berlin, easternGermany, poland);
    berlin.getUnitCollection().clear();
    GameDataTestUtil.truck(twwGameData).create(1, germans);
    addTo(berlin, GameDataTestUtil.truck(twwGameData).create(1, germans));
    MoveValidationResult results =
        MoveValidator.validateMove(berlin.getUnitCollection(), r, germans, Collections.emptyList(),
            new HashMap<>(), true, null, twwGameData);
    assertTrue(results.isMoveValid());

    // Add an infantry for truck to transport
    addTo(berlin, GameDataTestUtil.germanInfantry(twwGameData).create(1, germans));
    results = MoveValidator.validateMove(berlin.getUnitCollection(), r, germans, Collections.emptyList(),
        new HashMap<>(), true, null, twwGameData);
    assertTrue(results.isMoveValid());

    // Add an infantry and the truck can't transport both
    addTo(berlin, GameDataTestUtil.germanInfantry(twwGameData).create(1, germans));
    results = MoveValidator.validateMove(berlin.getUnitCollection(), r, germans, Collections.emptyList(),
        new HashMap<>(), true, null, twwGameData);
    assertFalse(results.isMoveValid());

    // Add a large truck (has capacity for 2 infantry) to transport second infantry
    addTo(berlin, GameDataTestUtil.largeTruck(twwGameData).create(1, germans));
    results = MoveValidator.validateMove(berlin.getUnitCollection(), r, germans, Collections.emptyList(),
        new HashMap<>(), true, null, twwGameData);
    assertTrue(results.isMoveValid());

    // Add an infantry that the large truck can also transport
    addTo(berlin, GameDataTestUtil.germanInfantry(twwGameData).create(1, germans));
    results = MoveValidator.validateMove(berlin.getUnitCollection(), r, germans, Collections.emptyList(),
        new HashMap<>(), true, null, twwGameData);
    assertTrue(results.isMoveValid());

    // Add an infantry that can't be transported
    addTo(berlin, GameDataTestUtil.germanInfantry(twwGameData).create(1, germans));
    results = MoveValidator.validateMove(berlin.getUnitCollection(), r, germans, Collections.emptyList(),
        new HashMap<>(), true, null, twwGameData);
    assertFalse(results.isMoveValid());
  }

  @Test
  void testValidateUnitsCanLoadInHostileSeaZones() throws Exception {

    final GameData twwGameData = TestMapGameData.TWW.getGameData();

    // Load german unit in sea zone with no enemy ships
    final PlayerId germans = GameDataTestUtil.germany(twwGameData);
    final Territory northernGermany = territory("Northern Germany", twwGameData);
    final Territory sz27 = territory("27 Sea Zone", twwGameData);
    final Route r = new Route(northernGermany, sz27);
    northernGermany.getUnitCollection().clear();
    addTo(northernGermany, GameDataTestUtil.germanInfantry(twwGameData).create(1, germans));
    final List<Unit> transport = sz27.getUnitCollection().getMatches(Matches.unitIsTransport());
    MoveValidationResult results = MoveValidator.validateMove(northernGermany.getUnitCollection(), r, germans,
        transport, new HashMap<>(), false, null, twwGameData);
    assertTrue(results.isMoveValid());

    // Add USA ship to transport sea zone
    final PlayerId usa = GameDataTestUtil.usa(twwGameData);
    addTo(sz27, GameDataTestUtil.americanCruiser(twwGameData).create(1, usa));
    results = MoveValidator.validateMove(northernGermany.getUnitCollection(), r, germans,
        transport, new HashMap<>(), false, null, twwGameData);
    assertFalse(results.isMoveValid());

    // Set 'Units Can Load In Hostile Sea Zones' to true
    twwGameData.getProperties().set(Constants.UNITS_CAN_LOAD_IN_HOSTILE_SEA_ZONES, true);
    results = MoveValidator.validateMove(northernGermany.getUnitCollection(), r, germans,
        transport, new HashMap<>(), false, null, twwGameData);
    assertTrue(results.isMoveValid());
  }

}
