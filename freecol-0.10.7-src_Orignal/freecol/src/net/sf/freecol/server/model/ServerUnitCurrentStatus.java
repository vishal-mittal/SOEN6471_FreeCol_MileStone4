package net.sf.freecol.server.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.logging.Logger;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.w3c.dom.Element;

import net.sf.freecol.client.gui.i18n.Messages;
import net.sf.freecol.common.model.Ability;
import net.sf.freecol.common.model.AbstractGoods;
import net.sf.freecol.common.model.CombatModel;
import net.sf.freecol.common.model.EquipmentType;
import net.sf.freecol.common.model.Europe;
import net.sf.freecol.common.model.FreeColGameObject;
import net.sf.freecol.common.model.Game;
import net.sf.freecol.common.model.GoodsType;
import net.sf.freecol.common.model.HighSeas;
import net.sf.freecol.common.model.HistoryEvent;
import net.sf.freecol.common.model.IndianSettlement;
import net.sf.freecol.common.model.Location;
import net.sf.freecol.common.model.LostCityRumour;
import net.sf.freecol.common.model.LostCityRumour.RumourType;
import net.sf.freecol.common.model.Map;
import net.sf.freecol.common.model.ModelMessage;
import net.sf.freecol.common.model.Modifier;
import net.sf.freecol.common.model.Player;
import net.sf.freecol.common.model.Region;
import net.sf.freecol.common.model.Resource;
import net.sf.freecol.common.model.ResourceType;
import net.sf.freecol.common.model.Settlement;
import net.sf.freecol.common.model.Specification;
import net.sf.freecol.common.model.StringTemplate;
import net.sf.freecol.common.model.Tension;
import net.sf.freecol.common.model.Tile;
import net.sf.freecol.common.model.TileImprovement;
import net.sf.freecol.common.model.TileImprovementType;
import net.sf.freecol.common.model.TileType;
import net.sf.freecol.common.model.Unit;
import net.sf.freecol.common.model.UnitType;
import net.sf.freecol.common.model.UnitTypeChange.ChangeType;
import net.sf.freecol.common.model.WorkLocation;
import net.sf.freecol.common.networking.NewLandNameMessage;
import net.sf.freecol.common.networking.NewRegionNameMessage;
import net.sf.freecol.common.util.RandomChoice;
import net.sf.freecol.common.util.Utils;
import net.sf.freecol.server.control.ChangeSet;
import net.sf.freecol.server.control.ChangeSet.ChangePriority;
import net.sf.freecol.server.control.ChangeSet.See;

public class ServerUnitCurrentStatus extends Unit {

	private static final Logger logger = Logger.getLogger(ServerUnit.class.getName());
	private static int rumourNothing = -1;

	public ServerUnitCurrentStatus() {
		super();
	}

	public ServerUnitCurrentStatus(Game game) {
		super(game);
	}

	public ServerUnitCurrentStatus(Game game, XMLStreamReader in)
			throws XMLStreamException {
		super(game, in);
	}

	public ServerUnitCurrentStatus(Game game, Element e) {
		super(game, e);
	}

	public ServerUnitCurrentStatus(Game game, String id) {
		super(game, id);
	}

	/**
	 * New turn for this unit.
	 *
	 * @param random A <code>Random</code> number source.
	 * @param cs A <code>ChangeSet</code> to update.
	 */
	public void csNewTurn(Random random, ChangeSet cs) {
	    logger.finest("ServerUnit.csNewTurn, for " + toString());
	    ServerPlayer owner = (ServerPlayer) getOwner();
	    Specification spec = getSpecification();
	    Location loc = getLocation();
	    boolean locDirty = false;
	    boolean unitDirty = false;
	
	    // Attrition.  Do it first as the unit might die.
	    if (loc instanceof Tile && ((Tile) loc).getSettlement() == null) {
	        int attrition = getAttrition() + 1;
	        setAttrition(attrition);
	        if (attrition > getType().getMaximumAttrition()) {
	            cs.addMessage(See.only(owner),
	                new ModelMessage(ModelMessage.MessageType.UNIT_LOST,
	                    "model.unit.attrition", this)
	                .addStringTemplate("%unit%", getLabel())
	                .addStringTemplate("%location%",
	                                   loc.getLocationNameFor(owner)));
	            cs.addDispose(See.perhaps().always(owner), loc, this);
	            cs.add(See.perhaps(), (Tile)loc);
	            return;
	        }
	    } else {
	        setAttrition(0);
	    }
	
	    // Check for experience-promotion.
	    GoodsType produce;
	    UnitType learn;
	    if (loc instanceof WorkLocation
	        && (produce = getWorkType()) != null
	        && (learn = spec.getExpertForProducing(produce)) != null
	        && learn != getType()
	        && getType().canBeUpgraded(learn, ChangeType.EXPERIENCE)) {
	        int maximumExperience = getType().getMaximumExperience();
	        int maxValue = (100 * maximumExperience) /
	            getType().getUnitTypeChange(learn).getProbability(ChangeType.EXPERIENCE);
	        if (maxValue > 0
	            && Utils.randomInt(logger, "Experience", random, maxValue)
	            < Math.min(getExperience(), maximumExperience)) {
	            StringTemplate oldName = getLabel();
	            setType(learn);
	            cs.addMessage(See.only(owner),
	                new ModelMessage(ModelMessage.MessageType.UNIT_IMPROVED,
	                    "model.unit.experience",
	                    getColony(), this)
	                .addStringTemplate("%oldName%", oldName)
	                .addStringTemplate("%unit%", getLabel())
	                .addName("%colony%", getColony().getName()));
	            logger.finest("Experience upgrade for unit " + getId()
	                + " to " + getType());
	            unitDirty = true;
	        }
	    }
	
	    // Update moves left.
	    if (isInMission()) {
	        getLocation().getTile().updatePlayerExploredTile(owner, true);
	        setMovesLeft(0);
	    } else if (isUnderRepair()) {
	        setMovesLeft(0);
	    } else {
	        setMovesLeft(getInitialMovesLeft());
	    }
	
	    if (getWorkLeft() > 0) {
	        unitDirty = true;
	        switch (getState()) {
	        case IMPROVING:
	            // Has the improvement been completed already? Do nothing.
	            TileImprovement ti = getWorkImprovement();
	            if (ti.isComplete()) {
	                setState(UnitState.ACTIVE);
	                setWorkLeft(-1);
	            } else {
	                // Otherwise do work
	                int amount = (getType().hasAbility(Ability.EXPERT_PIONEER))
	                    ? 2 : 1;
	                int turns = ti.getTurnsToComplete();
	                if ((turns -= amount) < 0) turns = 0;
	                ti.setTurnsToComplete(turns);
	                setWorkLeft(turns);
	            }
	            break;
	        default:
	            setWorkLeft(getWorkLeft() - 1);
	            break;
	        }
	
	        if (loc instanceof HighSeas && getOwner().isREF()) {
	            // Swift travel to America for the REF
	            setWorkLeft(0);
	        }
	    }
	
	    if (getWorkLeft() == 0) locDirty |= csCompleteWork(random, cs);
	
	    if (getState() == UnitState.SKIPPED) {
	        setState(UnitState.ACTIVE);
	        unitDirty = true;
	    }
	
	    if (locDirty) {
	        cs.add(See.perhaps(), (FreeColGameObject)getLocation());
	    } else if (unitDirty) {
	        cs.add(See.perhaps(), this);
	    } else {
	        cs.addPartial(See.only(owner), this, "movesLeft");
	    }
	}

	/**
	 * Complete the work a unit is doing.
	 *
	 * @param random A pseudo-random number source.
	 * @param cs A <code>ChangeSet</code> to update.
	 * @return True if the unit location needs an update.
	 */
	private boolean csCompleteWork(Random random, ChangeSet cs) {
	    setWorkLeft(-1);
	
	    if (getLocation() instanceof HighSeas) {
	        ServerPlayer owner = (ServerPlayer)getOwner();
	        Europe europe = owner.getEurope();
	        Map map = getGame().getMap();
	        Location dst = getDestination();
	        Location result = resolveDestination();
	        if (result == europe) {
	            logger.info(this + " arrives in Europe");
	            if (getTradeRoute() == null) {
	                setDestination(null);
	                cs.addMessage(See.only(owner),
	                    new ModelMessage(ModelMessage.MessageType.DEFAULT,
	                                     "model.unit.arriveInEurope",
	                                     europe, this)
	                    .add("%europe%", europe.getNameKey()));
	            }
	            setState(UnitState.ACTIVE);
	            setLocation(europe);
	            cs.add(See.only(owner), owner.getHighSeas());
	            return true;
	        } else if (result instanceof Tile) {
	            Tile tile = ((Tile)result).getSafeTile(owner, random);
	            logger.info(this + " arrives in America at " + tile
	                + ((dst == null) ? "" : ", sailing for " + dst));
	            if (dst instanceof Map) setDestination(null);
	            csMove(tile, random, cs);
	        } else {
	            logger.warning(this + " has unsupported destination "
	                           + getDestination());
	        }
	    } else {
	        switch (getState()) {
	        case FORTIFYING:
	            setState(UnitState.FORTIFIED);
	            break;
	        case IMPROVING:
	            csImproveTile(random, cs);
	            return true;
	        default:
	            logger.warning("Unknown work completed, state=" + getState());
	            setState(UnitState.ACTIVE);
	            break;
	        }
	    }
	    return false;
	}

	/**
	 * Completes a tile improvement.
	 *
	 * @param random A pseudo-random number source.
	 * @param cs A <code>ChangeSet</code> to update.
	 */
	private void csImproveTile(Random random, ChangeSet cs) {
	    Tile tile = getTile();
	    AbstractGoods deliver = getWorkImprovement().getType().getProduction(tile.getType());
	    if (deliver != null) { // Deliver goods if any
	        int amount = deliver.getAmount();
	        if (getType().hasAbility(Ability.EXPERT_PIONEER)) {
	            amount *= 2;
	        }
	        Settlement settlement = tile.getSettlement();
	        if (settlement != null
	            && (ServerPlayer) settlement.getOwner() == owner) {
	            amount = (int)settlement.applyModifier(amount,
	                Modifier.TILE_TYPE_CHANGE_PRODUCTION, deliver.getType());
	            settlement.addGoods(deliver.getType(), amount);
	        } else {
	            List<Settlement> adjacent = new ArrayList<Settlement>();
	            int newAmount = amount;
	            for (Tile t : tile.getSurroundingTiles(2)) {
	                Settlement ts = t.getSettlement();
	                if (ts != null && (ServerPlayer)ts.getOwner() == owner) {
	                    adjacent.add(ts);
	                    int modAmount = (int)ts.applyModifier((float)amount,
	                        Modifier.TILE_TYPE_CHANGE_PRODUCTION,
	                        deliver.getType());
	                    if (modAmount > newAmount) {
	                        newAmount = modAmount;
	                    }
	                }
	            }
	            if (adjacent.size() > 0) {
	                int deliverPerCity = newAmount / adjacent.size();
	                for (Settlement s : adjacent) {
	                    s.addGoods(deliver.getType(), deliverPerCity);
	                }
	                // Add residue to first adjacent settlement.
	                adjacent.get(0).addGoods(deliver.getType(),
	                                         newAmount % adjacent.size());
	            }
	        }
	    }
	
	    // Finish up
	    TileImprovement ti = getWorkImprovement();
	    TileType changeType = ti.getChange(tile.getType());
	    if (changeType != null) {
	        // Changes like clearing a forest need to be completed,
	        // whereas for changes like road building the improvement
	        // is already added and now complete.
	        tile.setType(changeType);
	    }
	
	    // Does a resource get exposed?
	    TileImprovementType tileImprovementType = ti.getType();
	    int exposeResource = tileImprovementType.getExposeResourcePercent();
	    if (exposeResource > 0 && !tile.hasResource()) {
	        if (Utils.randomInt(logger, "Expose resource", random, 100)
	            < exposeResource) {
	            ResourceType resType = RandomChoice.getWeightedRandom(logger,
	                                                                  "Resource type", random,
	                                                                  tile.getType().getWeightedResources());
	            int minValue = resType.getMinValue();
	            int maxValue = resType.getMaxValue();
	            int value = minValue + ((minValue == maxValue) ? 0
	                                    : Utils.randomInt(logger, "Resource quantity",
	                                                      random, maxValue - minValue + 1));
	            tile.addResource(new Resource(getGame(), tile, resType, value));
	        }
	    }
	
	    // Expend equipment
	    EquipmentType type = ti.getExpendedEquipmentType();
	    changeEquipment(type, -ti.getExpendedAmount());
	    for (Unit unit : tile.getUnitList()) {
	        if (unit.getWorkImprovement() != null
	            && unit.getWorkImprovement().getType() == ti.getType()
	            && unit.getState() == UnitState.IMPROVING) {
	            unit.setWorkLeft(-1);
	            unit.setWorkImprovement(null);
	            unit.setState(UnitState.ACTIVE);
	            unit.setMovesLeft(0);
	        }
	    }
	    // TODO: make this more generic, currently assumes tools used
	    EquipmentType tools = getSpecification()
	        .getEquipmentType("model.equipment.tools");
	    if (type == tools && getEquipmentCount(tools) == 0) {
	        ServerPlayer owner = (ServerPlayer) getOwner();
	        StringTemplate locName
	            = getLocation().getLocationNameFor(owner);
	        String messageId = (getType().getDefaultEquipmentType() == type)
	            ? getType() + ".noMoreTools"
	            : "model.unit.noMoreTools";
	        cs.addMessage(See.only(owner),
	            new ModelMessage(ModelMessage.MessageType.WARNING,
	                messageId, this)
	            .addStringTemplate("%unit%", getLabel())
	            .addStringTemplate("%location%", locName));
	    }
	}

	/**
	 * If a unit moves, check if an opposing naval unit slows it down.
	 * Note that the unit moves are reduced here.
	 *
	 * @param newTile The <code>Tile</code> the unit is moving to.
	 * @param random A pseudo-random number source.
	 * @return Either an enemy unit that causes a slowdown, or null if none.
	 */
	private Unit getSlowedBy(Tile newTile, Random random) {
	    Player player = getOwner();
	    Game game = getGame();
	    CombatModel combatModel = game.getCombatModel();
	    boolean pirate = hasAbility(Ability.PIRACY);
	    Unit attacker = null;
	    float attackPower = 0, totalAttackPower = 0;
	
	    if (!isNaval() || getMovesLeft() <= 0) return null;
	    for (Tile tile : newTile.getSurroundingTiles(1)) {
	        // Ships in settlements do not slow enemy ships, but:
	        // TODO should a fortress slow a ship?
	        Player enemy;
	        if (tile.isLand()
	            || tile.getColony() != null
	            || tile.getFirstUnit() == null
	            || (enemy = tile.getFirstUnit().getOwner()) == player) continue;
	        for (Unit enemyUnit : tile.getUnitList()) {
	            if ((pirate || enemyUnit.hasAbility(Ability.PIRACY)
	                 || (enemyUnit.isOffensiveUnit() && player.atWarWith(enemy)))
	                && enemyUnit.isNaval()
	                && combatModel.getOffencePower(enemyUnit, this) > attackPower) {
	                attackPower = combatModel.getOffencePower(enemyUnit, this);
	                totalAttackPower += attackPower;
	                attacker = enemyUnit;
	            }
	        }
	    }
	    if (attacker != null) {
	        float defencePower = combatModel.getDefencePower(attacker, this);
	        float totalProbability = totalAttackPower + defencePower;
	        if (Utils.randomInt(logger, "Slowed", random,
	                Math.round(totalProbability) + 1) < totalAttackPower) {
	            int diff = Math.max(0, Math.round(totalAttackPower - defencePower));
	            int moves = Math.min(9, 3 + diff / 3);
	            setMovesLeft(getMovesLeft() - moves);
	            logger.info(getId() + " slowed by " + attacker.getId()
	                + " by " + Integer.toString(moves) + " moves.");
	        } else {
	            attacker = null;
	        }
	    }
	    return attacker;
	}

	/**
	 * Explores a lost city, finding a native burial ground.
	 *
	 * @param cs A <code>ChangeSet</code> to add changes to.
	 */
	private void csNativeBurialGround(ChangeSet cs) {
	    ServerPlayer serverPlayer = (ServerPlayer) getOwner();
	    Tile tile = getTile();
	    Player indianPlayer = tile.getOwner();
	    cs.add(See.only(serverPlayer),
	        indianPlayer.modifyTension(serverPlayer,
	            Tension.Level.HATEFUL.getLimit()));
	    cs.add(See.only(serverPlayer), indianPlayer);
	    cs.addMessage(See.only(serverPlayer),
	        new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	            "lostCityRumour.burialGround", serverPlayer, this)
	        .addStringTemplate("%nation%", indianPlayer.getNationName()));
	}

	/**
	 * Explore a lost city.
	 *
	 * @param random A pseudo-random number source.
	 * @param cs A <code>ChangeSet</code> to add changes to.
	 */
	private void csExploreLostCityRumour(Random random, ChangeSet cs) {
	    ServerPlayer serverPlayer = (ServerPlayer) getOwner();
	    Tile tile = getTile();
	    LostCityRumour lostCity = tile.getLostCityRumour();
	    if (lostCity == null) return;
	
	    Game game = getGame();
	    Specification spec = game.getSpecification();
	    int difficulty = spec.getInteger("model.option.rumourDifficulty");
	    int dx = 10 - difficulty;
	    UnitType unitType;
	    Unit newUnit = null;
	    List<UnitType> treasureUnitTypes
	        = spec.getUnitTypesWithAbility(Ability.CARRY_TREASURE);
	
	    RumourType rumour = lostCity.getType();
	    if (rumour == null) {
	        rumour = lostCity.chooseType(this, difficulty, random);
	    }
	    // Filter out failing cases that could only occur if the
	    // type was explicitly set in debug mode.
	    switch (rumour) {
	    case BURIAL_GROUND: case MOUNDS:
	        if (tile.getOwner() == null || !tile.getOwner().isIndian()) {
	            rumour = RumourType.NOTHING;
	        }
	        break;
	    case LEARN:
	        if (getType().getUnitTypesLearntInLostCity().isEmpty()) {
	            rumour = RumourType.NOTHING;
	        }
	        break;
	    default:
	        break;
	    }
	
	    // Mounds are a special case that degrade to other cases.
	    boolean mounds = rumour == RumourType.MOUNDS;
	    if (mounds) {
	        boolean done = false;
	        boolean burial = false;
	        while (!done) {
	            rumour = lostCity.chooseType(this, difficulty, random);
	            switch (rumour) {
	            case EXPEDITION_VANISHES: case NOTHING: case TRIBAL_CHIEF:
	            case RUINS:
	                done = true;
	                break;
	            case BURIAL_GROUND:
	                if (tile.getOwner() != null && tile.getOwner().isIndian()
	                    && !burial) {
	                    csNativeBurialGround(cs);
	                    burial = true;
	                }
	                break;
	            default:
	                ; // unacceptable result for mounds
	            }
	        }
	    }
	
	    logger.info("Unit " + getId() + " is exploring rumour " + rumour);
	    switch (rumour) {
	    case BURIAL_GROUND:
	        csNativeBurialGround(cs);
	        break;
	    case EXPEDITION_VANISHES:
	        cs.addDispose(See.perhaps().always(serverPlayer), tile, this);
	        cs.addMessage(See.only(serverPlayer),
	            new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	                "lostCityRumour.expeditionVanishes", serverPlayer));
	        break;
	    case NOTHING:
	        if (game.getTurn().getYear() % 100 == 12
	            && Utils.randomInt(logger, "Mayans?", random, 4) == 0) {
	            int years = 2012 - game.getTurn().getYear();
	            cs.addMessage(See.only(serverPlayer),
	                new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	                    "lostCityRumour.mayans", serverPlayer, this)
	                .add("%years%", Integer.toString(years)));
	            break;
	        } else if (mounds) {
	            cs.addMessage(See.only(serverPlayer),
	                new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	                    "lostCityRumour.moundsNothing", serverPlayer, this));
	            break;
	        }
	        if (rumourNothing < 0) {
	            int i;
	            for (i = 0; Messages.containsKey("lostCityRumour.nothing."
	                                             + Integer.toString(i)); i++);
	            rumourNothing = i;
	        }
	        cs.addMessage(See.only(serverPlayer),
	            new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	                "lostCityRumour.nothing."
	                + Integer.toString(Utils.randomInt(logger,
	                        "Nothing rumour", random, rumourNothing)),
	                serverPlayer, this));
	        break;
	    case LEARN:
	        StringTemplate oldName = getLabel();
	        List<UnitType> learnTypes = getType().getUnitTypesLearntInLostCity();
	        unitType = Utils.getRandomMember(logger, "Choose learn",
	            learnTypes, random);
	        setType(unitType);
	        cs.addMessage(See.only(serverPlayer),
	            new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	                "lostCityRumour.learn", serverPlayer, this)
	            .addStringTemplate("%unit%", oldName)
	            .add("%type%", getType().getNameKey()));
	        break;
	    case TRIBAL_CHIEF:
	        int chiefAmount = Utils.randomInt(logger, "Chief base amount",
	                                          random, dx * 10) + dx * 5;
	        serverPlayer.modifyGold(chiefAmount);
	        cs.addPartial(See.only(serverPlayer), serverPlayer, "gold", "score");
	        cs.addMessage(See.only(serverPlayer),
	            new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	                ((mounds) ? "lostCityRumour.moundsTrinkets"
	                    : "lostCityRumour.tribalChief"),
	                serverPlayer, this)
	            .addAmount("%money%", chiefAmount));
	        break;
	    case COLONIST:
	        List<UnitType> foundTypes = spec.getUnitTypesWithAbility("model.ability.foundInLostCity");
	        unitType = Utils.getRandomMember(logger, "Choose found",
	            foundTypes, random);
	        newUnit = new ServerUnit(game, tile, serverPlayer, unitType);
	        cs.addMessage(See.only(serverPlayer),
	            new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	                "lostCityRumour.colonist", serverPlayer, newUnit));
	        break;
	    case CIBOLA:
	        String cityName = game.getCityOfCibola();
	        if (cityName != null) {
	            int treasureAmount = Utils.randomInt(logger,
	                "Base treasure amount", random, dx * 600) + dx * 300;
	            unitType = Utils.getRandomMember(logger, "Choose train",
	                treasureUnitTypes, random);
	            newUnit = new ServerUnit(game, tile, serverPlayer, unitType);
	            newUnit.setTreasureAmount(treasureAmount);
	            cs.addMessage(See.only(serverPlayer),
	                new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	                    "lostCityRumour.cibola", serverPlayer, newUnit)
	                .add("%city%", cityName)
	                .addAmount("%money%", treasureAmount));
	            cs.addGlobalHistory(game,
	                new HistoryEvent(game.getTurn(),
	                    HistoryEvent.EventType.CITY_OF_GOLD)
	                .addStringTemplate("%nation%", serverPlayer.getNationName())
	                .add("%city%", cityName)
	                .addAmount("%treasure%", treasureAmount));
	            break;
	        }
	        // Fall through, found all the cities of gold.
	    case RUINS:
	        int ruinsAmount = Utils.randomInt(logger,
	            "Base ruins amount", random, dx * 2) * 300 + 50;
	        if (ruinsAmount < 500) { // TODO remove magic number
	            serverPlayer.modifyGold(ruinsAmount);
	            cs.addPartial(See.only(serverPlayer), serverPlayer,
	                          "gold", "score");
	        } else {
	            unitType = Utils.getRandomMember(logger, "Choose train",
	                                             treasureUnitTypes, random);
	            newUnit = new ServerUnit(game, tile, serverPlayer, unitType);
	            newUnit.setTreasureAmount(ruinsAmount);
	        }
	        cs.addMessage(See.only(serverPlayer),
	             new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	                              ((mounds) ? "lostCityRumour.moundsTreasure"
	                               : "lostCityRumour.ruins"),
	                              serverPlayer, ((newUnit != null) ? newUnit
	                                             : this))
	                 .addAmount("%money%", ruinsAmount));
	        break;
	    case FOUNTAIN_OF_YOUTH:
	        Europe europe = serverPlayer.getEurope();
	        if (europe == null) {
	            cs.addMessage(See.only(serverPlayer),
	                 new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	                     "lostCityRumour.fountainOfYouthWithoutEurope",
	                     serverPlayer, this));
	        } else {
	            if (serverPlayer.hasAbility("model.ability.selectRecruit")
	                && !serverPlayer.isAI()) { // TODO: let the AI select
	                // Remember, and ask player to select
	                serverPlayer.setRemainingEmigrants(dx);
	                cs.addTrivial(See.only(serverPlayer), "fountainOfYouth",
	                    ChangeSet.ChangePriority.CHANGE_LATE,
	                    "migrants", Integer.toString(dx));
	            } else {
	                List<RandomChoice<UnitType>> recruitables
	                    = serverPlayer.generateRecruitablesList();
	                for (int k = 0; k < dx; k++) {
	                    UnitType type = RandomChoice
	                        .getWeightedRandom(logger,
	                            "Choose FoY", random, recruitables);
	                    new ServerUnit(game, europe, serverPlayer, type);
	                }
	                cs.add(See.only(serverPlayer), europe);
	            }
	            cs.addMessage(See.only(serverPlayer),
	                 new ModelMessage(ModelMessage.MessageType.LOST_CITY_RUMOUR,
	                     "lostCityRumour.fountainOfYouth", serverPlayer, this));
	        }
	        cs.addAttribute(See.only(serverPlayer),
	            "sound", "sound.event.fountainOfYouth");
	        break;
	    case NO_SUCH_RUMOUR: case MOUNDS:
	    default:
	        logger.warning("Bogus rumour type: " + rumour);
	        break;
	    }
	    tile.removeLostCityRumour();
	}

	/**
	 * Activate sentried units on a tile.
	 *
	 * @param tile The <code>Tile</code> to activate sentries on.
	 * @param cs A <code>ChangeSet</code> to update.
	 */
	private void csActivateSentries(Tile tile, ChangeSet cs) {
	    for (Unit u : tile.getUnitList()) {
	        if (u.getState() == UnitState.SENTRY) {
	            u.setState(UnitState.ACTIVE);
	            cs.add(See.perhaps(), u);
	        }
	    }
	}

	/**
	 * Collects the tiles surrounding this unit that the player
	 * can not currently see, but now should as a result of a move.
	 *
	 * @param tile The center tile to look from.
	 * @return A list of new tiles to see.
	 */
	public List<Tile> collectNewTiles(Tile tile) {
	    List<Tile> newTiles = new ArrayList<Tile>();
	    int los = getLineOfSight();
	    for (Tile t : tile.getSurroundingTiles(los)) {
	        if (!getOwner().canSee(t)) newTiles.add(t);
	    }
	    return newTiles;
	}

	/**
	 * Move a unit.
	 *
	 * @param newTile The <code>Tile</code> to move to.
	 * @param random A pseudo-random number source.
	 * @param cs A <code>ChangeSet</code> to update.
	 */
	public void csMove(Tile newTile, Random random, ChangeSet cs) {
	    ServerPlayer serverPlayer = (ServerPlayer) getOwner();
	
	    // Plan to update tiles that could not be seen before but will
	    // now be within the line-of-sight.
	    List<Tile> newTiles = collectNewTiles(newTile);
	
	    // Update unit state.
	    Location oldLocation = getLocation();
	    setState(UnitState.ACTIVE);
	    setStateToAllChildren(UnitState.SENTRY);
	    if (oldLocation instanceof HighSeas) {
	        ; // Do not try to calculate move cost from Europe!
	    } else if (oldLocation instanceof Unit) {
	        setMovesLeft(0); // Disembark always consumes all moves.
	    } else {
	        if (getMoveCost(newTile) <= 0) {
	            logger.warning("Move of unit: " + getId()
	                + " from: " + ((oldLocation == null) ? "null"
	                    : oldLocation.getTile().getId())
	                + " to: " + newTile.getId()
	                + " has bogus cost: " + getMoveCost(newTile));
	            setMovesLeft(0);
	        }
	        setMovesLeft(getMovesLeft() - getMoveCost(newTile));
	    }
	
	    // Do the move and explore a rumour if needed.
	    setLocation(newTile);
	    if (newTile.hasLostCityRumour() && serverPlayer.isEuropean()) {
	        csExploreLostCityRumour(random, cs);
	    }
	
	    // Unless moving in from off-map, update the old location and
	    // make sure the move is always visible even if the unit
	    // dies (including the animation).  However, dead units
	    // make no discoveries.  Always update the new tile.
	    if (oldLocation instanceof Tile) {
	        cs.addMove(See.perhaps().always(serverPlayer), this,
	            oldLocation, newTile);
	        cs.add(See.perhaps().always(serverPlayer),
	            (FreeColGameObject) oldLocation);
	    } else {
	        cs.add(See.only(serverPlayer), (FreeColGameObject) oldLocation);
	    }
	    cs.add(See.perhaps().always(serverPlayer), newTile);
	    if (isDisposed()) return;
	    serverPlayer.csSeeNewTiles(newTiles, cs);
	
	    if (newTile.isLand()) {
	        Settlement settlement;
	        Unit unit = null;
	        int d;
	        // Claim land for tribe?
	        if (newTile.getOwner() == null
	            && serverPlayer.isIndian()
	            && (settlement = getIndianSettlement()) != null
	            && ((d = newTile.getPosition()
	                    .getDistance(settlement.getTile().getPosition()))
	                < (settlement.getRadius()
	                    + settlement.getType().getExtraClaimableRadius()))
	            && Utils.randomInt(logger, "Claim tribal land", random,
	                d + 1) == 0) {
	            newTile.setOwner(serverPlayer);
	            newTile.changeOwningSettlement(settlement);
	        }
	
	        // Check for first landing
	        String newLand = null;
	        if (serverPlayer.isEuropean()
	            && !serverPlayer.isNewLandNamed()) {
	            newLand = Messages.getNewLandName(serverPlayer);
	            cs.addMessage(See.only(serverPlayer),
	                new ModelMessage(ModelMessage.MessageType.DEFAULT,
	                    "EventPanel.FIRST_LANDING", serverPlayer));
	            // Set the default value now to prevent multiple attempts.
	            // The user setNewLandName can override.
	            serverPlayer.setNewLandName(newLand);
	        }
	
	        // Check for new contacts.
	        ServerPlayer welcomer = null;
	        for (Tile t : newTile.getSurroundingTiles(1, 1)) {
	            if (t == null || !t.isLand()) {
	                continue; // Invalid tile for contact
	            }
	
	            settlement = t.getSettlement();
	            ServerPlayer other = (settlement != null)
	                ? (ServerPlayer)settlement.getOwner()
	                : ((unit = t.getFirstUnit()) != null)
	                ? (ServerPlayer)unit.getOwner()
	                : null;
	            if (other == null
	                || other == serverPlayer) continue; // No contact
	
	            if (serverPlayer.csContact(other, newTile, cs) != null) {
	                welcomer = other;
	            }
	            // Initialize alarm for native settlements or units and
	            // notify of contact.
	            ServerPlayer contactPlayer = serverPlayer;
	            IndianSettlement is = (settlement instanceof IndianSettlement)
	                ? (IndianSettlement)settlement
	                : null;
	            if (is != null
	                || (unit != null
	                    && (is = unit.getIndianSettlement()) != null)
	                || (unit != null
	                    && (contactPlayer = (ServerPlayer)unit.getOwner())
	                        .isEuropean()
	                    && (is = getIndianSettlement()) != null)) {
	                if (contactPlayer.hasExplored(is.getTile())
	                    && is.setContacted(contactPlayer)) {
	                    cs.add(See.only(contactPlayer), is);
	                    // First European contact with native settlement.
	                    StringTemplate nation = is.getOwner().getNationName();
	                    cs.addMessage(See.only(contactPlayer),
	                        new ModelMessage(ModelMessage.MessageType.FOREIGN_DIPLOMACY,
	                                         "model.unit.nativeSettlementContact",
	                                         this, is)
	                            .addStringTemplate("%nation%", nation)
	                            .addName("%settlement%", is.getName()));
	                }                   
	            }
	            csActivateSentries(t, cs);
	        }
	        if (newLand != null) {
	            cs.add(See.only(serverPlayer), ChangePriority.CHANGE_LATE,
	                new NewLandNameMessage(this, newLand, welcomer,
	                    ((welcomer == null) ? -1
	                        : welcomer.getNumberOfSettlements()), false));
	        }
	    } else { // water
	        for (Tile t : newTile.getSurroundingTiles(1, 1)) {
	            if (t == null || t.isLand() || t.getFirstUnit() == null) {
	                continue;
	            }
	            if ((ServerPlayer) t.getFirstUnit().getOwner()
	                != serverPlayer) csActivateSentries(t, cs);
	        }
	    }
	
	    // Check for slowing units.
	    Unit slowedBy = getSlowedBy(newTile, random);
	    if (slowedBy != null) {
	        StringTemplate enemy = slowedBy.getApparentOwnerName();
	        cs.addMessage(See.only(serverPlayer),
	            new ModelMessage(ModelMessage.MessageType.FOREIGN_DIPLOMACY,
	                "model.unit.slowed", this, slowedBy)
	            .addStringTemplate("%unit%", Messages.getLabel(this))
	            .addStringTemplate("%enemyUnit%", Messages.getLabel(slowedBy))
	            .addStringTemplate("%enemyNation%", enemy));
	    }
	
	    // Check for region discovery
	    Region region = newTile.getDiscoverableRegion();
	    if (serverPlayer.isEuropean() && region != null) {
	        if (region.isPacific()) {
	            cs.addMessage(See.only(serverPlayer),
	                new ModelMessage(ModelMessage.MessageType.DEFAULT,
	                    "EventPanel.DISCOVER_PACIFIC", serverPlayer));
	            cs.addRegion(serverPlayer, region,
	                Messages.message("model.region.pacific"));
	        } else if (region.getName() == null) {
	            // Really newly discovered.
	            String defaultName = Messages.getDefaultRegionName(serverPlayer,
	                region.getType());
	            cs.add(See.only(serverPlayer), ChangePriority.CHANGE_LATE,
	                new NewRegionNameMessage(region, newTile, defaultName));
	            // Set the default name to prevent multiple attempts.
	            region.setName(defaultName);
	        }
	    }
	}

}