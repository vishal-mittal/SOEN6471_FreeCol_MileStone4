/**
 *  Copyright (C) 2002-2012   The FreeCol Team
 *
 *  This file is part of FreeCol.
 *
 *  FreeCol is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  FreeCol is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with FreeCol.  If not, see <http://www.gnu.org/licenses/>.
 */


package net.sf.freecol.server.model;

import java.util.Collection;
import java.util.List;
import java.util.Random;

import net.sf.freecol.common.model.AbstractGoods;
import net.sf.freecol.common.model.Colony;
import net.sf.freecol.common.model.EquipmentType;
import net.sf.freecol.common.model.Europe;
import net.sf.freecol.common.model.FreeColGameObject;
import net.sf.freecol.common.model.Game;
import net.sf.freecol.common.model.Goods;
import net.sf.freecol.common.model.GoodsContainer;
import net.sf.freecol.common.model.GoodsType;
import net.sf.freecol.common.model.Location;
import net.sf.freecol.common.model.Market;
import net.sf.freecol.common.model.ModelMessage;
import net.sf.freecol.common.model.Player;
import net.sf.freecol.common.model.Settlement;
import net.sf.freecol.common.model.TradeRoute.Stop;
import net.sf.freecol.common.model.UnitType;
import net.sf.freecol.common.model.UnitTypeChange.ChangeType;
import net.sf.freecol.server.control.ChangeSet;
import net.sf.freecol.server.control.ChangeSet.See;


/**
 * Server version of a unit.
 *
 */
public class ServerUnit extends ServerUnitCurrentStatus implements ServerModelObject {

    /**
     * Trivial constructor required for all ServerModelObjects.
     */
    public ServerUnit(Game game, String id) {
        super(game, id);
    }

    /**
     * Creates a new ServerUnit.
     *
     * @param game The <code>Game</code> in which this unit belongs.
     * @param location The <code>Location</code> to place this at.
     * @param owner The <code>Player</code> owning this unit.
     * @param type The type of the unit.
     */
    public ServerUnit(Game game, Location location, Player owner,
                      UnitType type) {
        this(game, location, owner, type, type.getDefaultEquipment());
    }

    /**
     * Creates a new ServerUnit.
     *
     * @param game The <code>Game</code> in which this unit belongs.
     * @param location The <code>Location</code> to place this at.
     * @param owner The <code>Player</code> owning this unit.
     * @param type The type of the unit.
     * @param initialEquipment The list of initial EquimentTypes
     */
    public ServerUnit(Game game, Location location, Player owner,
                      UnitType type, EquipmentType... initialEquipment) {
        super(game);

        visibleGoodsCount = -1;

        if (type.canCarryGoods()) {
            setGoodsContainer(new GoodsContainer(game, this));
        }

        UnitType newType = type.getTargetType(ChangeType.CREATION, owner);
        unitType = (newType == null) ? type : newType;
        this.owner = owner;
        if (isPerson()) {
            nationality = owner.getNationID();
            ethnicity = nationality;
        }
        setLocation(location);

        workLeft = -1;
        workType = null;

        this.movesLeft = getInitialMovesLeft();
        hitpoints = unitType.getHitPoints();

        for (EquipmentType equipmentType : initialEquipment) {
            if (EquipmentType.NO_EQUIPMENT.equals(equipmentType)) {
                equipment.clear();
                break;
            }
            equipment.incrementCount(equipmentType, 1);
        }
        setRole();
        setStateUnchecked(state);

        owner.setUnit(this);
        owner.invalidateCanSeeTiles();
        owner.modifyScore(unitType.getScoreValue());
    }


    /**
     * Repair a unit.
     *
     * @param cs A <code>ChangeSet</code> to update.
     */
    public void csRepairUnit(ChangeSet cs) {
        ServerPlayer owner = (ServerPlayer) getOwner();
        setHitpoints(getHitpoints() + 1);
        if (!isUnderRepair()) {
            Location loc = getLocation();
            cs.addMessage(See.only(owner),
                new ModelMessage("model.unit.unitRepaired",
                    this, (FreeColGameObject) loc)
                .addStringTemplate("%unit%", getLabel())
                .addStringTemplate("%repairLocation%",
                    loc.getLocationNameFor(owner)));
        }
        cs.addPartial(See.only(owner), this, "hitpoints");
    }

    /**
     * Remove equipment from a unit.
     *
     * @param settlement The <code>Settlement</code> where the unit is
     *     (may be null if the unit is in Europe).
     * @param remove A collection of <code>EquipmentType</code> to remove.
     * @param amount Override the amount of equipment to remove.
     * @param random A pseudo-random number source.
     * @param cs A <code>ChangeSet</code> to update.
     */
    public void csRemoveEquipment(Settlement settlement,
                                  Collection<EquipmentType> remove,
                                  int amount, Random random, ChangeSet cs) {
        ServerPlayer serverPlayer = (ServerPlayer) getOwner();
        for (EquipmentType e : remove) {
            int a = (amount > 0) ? amount : getEquipmentCount(e);
            for (AbstractGoods ag : e.getRequiredGoods()) {
                GoodsType goodsType = ag.getType();
                int n = ag.getAmount() * a;
                if (isInEurope()) {
                    if (serverPlayer.canTrade(goodsType,
                                              Market.Access.EUROPE)) {
                        serverPlayer.sell(null, goodsType, n, random);
                        serverPlayer.csFlushMarket(goodsType, cs);
                    }
                } else if (settlement != null) {
                    settlement.addGoods(goodsType, n);
                }
            }
            // Removals can not cause incompatible-equipment trouble
            changeEquipment(e, -a);
        }
    }


    /**
     * Is there work for a unit to do at a stop?
     *
     * @param stop The <code>Stop</code> to test.
     * @return True if the unit should load or unload cargo at the stop.
     */
    public boolean hasWorkAtStop(Stop stop) {
        List<GoodsType> stopGoods = stop.getCargo();
        int cargoSize = stopGoods.size();
        for (Goods goods : getGoodsList()) {
            GoodsType type = goods.getType();
            if (stopGoods.contains(type)) {
                if (getLoadableAmount(type) > 0) {
                    // There is space on the unit to load some more
                    // of this goods type, so return true if there is
                    // some available at the stop.
                    Location loc = stop.getLocation();
                    if (loc instanceof Colony) {
                        if (((Colony) loc).getExportAmount(type) > 0) {
                            return true;
                        }
                    } else if (loc instanceof Europe) {
                        return true;
                    }
                } else {
                    cargoSize--; // No room for more of this type.
                }
            } else {
                return true; // This type should be unloaded here.
            }
        }

        // Return true if there is space left, and something to load.
        return hasSpaceLeft() && cargoSize > 0;
    }


    /**
     * Returns the tag name of the root element representing this object.
     *
     * @return "serverUnit"
     */
    public String getServerXMLElementTagName() {
        return "serverUnit";
    }
}
