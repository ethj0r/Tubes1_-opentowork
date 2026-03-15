package nus_bot;

import battlecode.common.*;

import java.util.Random;

public class RobotPlayer {

    // global stuff
    static final Random rng = new Random();
    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };
    static final Direction[] cardinals = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
    };

    // message protocol: (msgType << 12) | (x << 6) | y
    static final int MSG_ENEMY_RUIN = 1;    // soldier->tower: ruin has enemy paint, send mopper
    static final int MSG_ENEMY_TOWER = 2;   // any->tower->soldiers: enemy tower found, attack
    static final int MSG_ENEMY_UNITS = 3;   // soldier->tower->moppers: enemy units spotted

    // tower state
    static int botSpawnedCount = 0;
    static boolean mopperRequested = false;
    static MapLocation requestedMopLoc = null;

    // home/refuel system (like mopper_and_srp RefuelManager)
    static MapLocation home = null;
    static int homeState = 0; // 0=ruin, 1=non-paint tower, 2=paint tower
    static boolean shouldGoHome = false;
    // returnLoc removed — was causing soldiers to walk back to already-built ruins

    // soldier ruin building
    static MapLocation targetRuin = null;
    static UnitType targetTowerType = null;
    static MapLocation enemyRuinToReport = null;
    static int ruinBuildTurns = 0;

    // global stuck detection: if soldier stays in same spot, force reset
    static MapLocation lastSoldierLoc = null;
    static int soldierStuckTurns = 0;

    // mopper state
    static MapLocation mopTarget = null;

    // enemy tower rally point — all units go attack
    static MapLocation attackTarget = null;
    static MapLocation towerEnemyTowerLoc = null; // tower broadcasts to soldiers
    static MapLocation towerEnemyUnitsLoc = null; // tower broadcasts to moppers
    static MapLocation mopperCallTarget = null;   // mopper: go help here

    // explore target (random map location like mopper_and_srp)
    static MapLocation exploreTarget = null;
    static MapLocation splasherExploreTarget = null;


    // refuel cooldown: don't re-enter refuel for N rounds after giving up
    static int refuelGiveUpRound = -100;

    // constants
    static final int UPGRADE_L2_ROUND = 200;
    static final int UPGRADE_L3_ROUND = 400;

    // ================================================================
    //                          MAIN LOOP
    // ================================================================
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        // unique RNG per robot (like mopper_and_srp Globals.java)
        rng.setSeed((long) rc.getID());

        while (true) {
            try {
                switch (rc.getType()) {
                    case SOLDIER:  runSoldier(rc);  break;
                    case MOPPER:   runMopper(rc);   break;
                    case SPLASHER: runSplasher(rc); break;
                    default:       runTower(rc);    break;
                }
            } catch (GameActionException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    // ================================================================
    //                            TOWER
    // ================================================================
    public static void runTower(RobotController rc) throws GameActionException {
        towerAttack(rc);
        towerReadMessages(rc);
        towerSpawn(rc);
        towerForwardMessages(rc);
    }

    static void towerAttack(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length == 0 || !rc.isActionReady()) return;
        RobotInfo target = enemies[0];
        int minDist = rc.getLocation().distanceSquaredTo(target.location);
        for (int i = 1; i < enemies.length; i++) {
            int dist = rc.getLocation().distanceSquaredTo(enemies[i].location);
            if (dist < minDist) {
                minDist = dist;
                target = enemies[i];
            }
        }
        if (rc.canAttack(target.location)) {
            rc.attack(target.location);
        }
    }

    static void towerReadMessages(RobotController rc) throws GameActionException {
        for (int r = rc.getRoundNum() - 1; r <= rc.getRoundNum(); r++) {
            if (r < 0) continue;
            Message[] msgs = rc.readMessages(r);
            for (Message msg : msgs) {
                int data = msg.getBytes();
                int type = (data >> 12) & 0xF;
                if (type == MSG_ENEMY_RUIN) {
                    int x = (data >> 6) & 0x3F;
                    int y = data & 0x3F;
                    requestedMopLoc = new MapLocation(x, y);
                    mopperRequested = true;
                } else if (type == MSG_ENEMY_TOWER) {
                    int x = (data >> 6) & 0x3F;
                    int y = data & 0x3F;
                    towerEnemyTowerLoc = new MapLocation(x, y);
                } else if (type == MSG_ENEMY_UNITS) {
                    int x = (data >> 6) & 0x3F;
                    int y = data & 0x3F;
                    towerEnemyUnitsLoc = new MapLocation(x, y);
                    mopperRequested = true;
                }
            }
        }
    }

    // spawn: mopper request priority, then cycle [sol, sol, sol, mop]
    static void towerSpawn(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        int round = rc.getRoundNum();

        UnitType toBuild;
        if (mopperRequested) {
            toBuild = UnitType.MOPPER;
        } else if (round < 200) {
            // early game: mostly soldiers to build towers
            // cycle: sol, sol, sol, mop (4-cycle)
            int idx = botSpawnedCount % 4;
            if (idx == 3) toBuild = UnitType.MOPPER;
            else toBuild = UnitType.SOLDIER;
        } else {
            // mid/late game: more splashers for painting
            // cycle: sol, splasher, sol, splasher, mop (5-cycle)
            int idx = botSpawnedCount % 5;
            if (idx == 4) toBuild = UnitType.MOPPER;
            else if (idx == 1 || idx == 3) toBuild = UnitType.SPLASHER;
            else toBuild = UnitType.SOLDIER;
        }

        for (Direction dir : directions) {
            MapLocation spawnLoc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(toBuild, spawnLoc)) {
                rc.buildRobot(toBuild, spawnLoc);
                botSpawnedCount++;
                if (mopperRequested && toBuild == UnitType.MOPPER) {
                    mopperRequested = false;
                }
                return;
            }
        }
    }

    // forward: enemy tower → soldiers, enemy units/mop target → moppers
    static void towerForwardMessages(RobotController rc) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : nearby) {
            if (ally.type.isTowerType()) continue;

            if (ally.type == UnitType.SOLDIER || ally.type == UnitType.SPLASHER) {
                // soldiers and splashers get enemy tower locations
                if (towerEnemyTowerLoc != null) {
                    int msg = encodeLocation(MSG_ENEMY_TOWER, towerEnemyTowerLoc);
                    if (rc.canSendMessage(ally.location, msg)) {
                        rc.sendMessage(ally.location, msg);
                    }
                }
            } else if (ally.type == UnitType.MOPPER) {
                // moppers get enemy ruin cleanup + enemy unit locations
                if (requestedMopLoc != null) {
                    int msg = encodeLocation(MSG_ENEMY_RUIN, requestedMopLoc);
                    if (rc.canSendMessage(ally.location, msg)) {
                        rc.sendMessage(ally.location, msg);
                    }
                } else if (towerEnemyUnitsLoc != null) {
                    int msg = encodeLocation(MSG_ENEMY_UNITS, towerEnemyUnitsLoc);
                    if (rc.canSendMessage(ally.location, msg)) {
                        rc.sendMessage(ally.location, msg);
                    }
                }
            }
        }
    }

    // ================================================================
    //                           SOLDIER
    // ================================================================
    public static void runSoldier(RobotController rc) throws GameActionException {
        MapLocation myloc = rc.getLocation();
        int myPaint = rc.getPaint();
        int paintCap = rc.getType().paintCapacity;

        // GLOBAL STUCK DETECTION: if soldier hasn't moved in 3 turns, force reset
        if (lastSoldierLoc != null && myloc.equals(lastSoldierLoc)) {
            soldierStuckTurns++;
        } else {
            soldierStuckTurns = 0;
        }
        lastSoldierLoc = myloc;
        if (soldierStuckTurns >= 3) {
            // force clear ALL targets — soldier is trapped, must explore out
            targetRuin = null; targetTowerType = null;
            attackTarget = null;
            ruinBuildTurns = 0;
            soldierStuckTurns = 0;
            // pick a random direction and go
            exploreTarget = null;
            explore(rc);
            soldierPostTurn(rc);
            return;
        }

        // emergency: if on enemy paint and very low, flee to ally paint
        if (myPaint <= paintCap / 8 && rc.isMovementReady()) {
            MapInfo here = rc.senseMapInfo(myloc);
            if (here.getPaint().isEnemy()) {
                fleeToAllyPaint(rc);
            }
        }

        // update home tower (like RefuelManager.setHome)
        setHome(rc);

        // spot enemy towers and report them
        spotAndReportEnemies(rc);
        readAttackMessages(rc);

        // rally to attack enemy tower — only if close enough (dist <= 100)
        if (attackTarget != null) {
            if (myloc.distanceSquaredTo(attackTarget) > 100) {
                attackTarget = null; // too far, dont bother
            } else if (rc.canSenseLocation(attackTarget)) {
                RobotInfo etower = rc.senseRobotAtLocation(attackTarget);
                if (etower == null || etower.team == rc.getTeam()) {
                    attackTarget = null;
                } else {
                    if (rc.canAttack(attackTarget)) {
                        rc.attack(attackTarget);
                    }
                    moveToward(rc, attackTarget);
                    soldierPostTurn(rc);
                    return;
                }
            } else {
                moveToward(rc, attackTarget);
                soldierPostTurn(rc);
                return;
            }
        }

        // paint management: go home when low, clear when recovered
        if (myPaint > paintCap / 2) {
            // paint recovered enough, clear go-home flag
            shouldGoHome = false;
        } else if (myPaint <= paintCap / 3 && !shouldGoHome
                   && rc.getRoundNum() - refuelGiveUpRound >= 10) {
            // paint dropped below threshold, go refuel
            shouldGoHome = true;

            refuelWaitTurns = 0;
        }

        // low paint tower build (build even when low if nearby and have enough)
        if (rc.getNumberTowers() < 25 && myPaint < 50) {
            MapLocation ruin = findNearestUnbuiltRuin(rc);
            if (ruin != null && enoughPaintForTower(rc, ruin)) {
                targetRuin = ruin;
                doTowerBuild(rc, targetRuin);
                soldierPostTurn(rc);
                return;
            }
        }

        // refuel
        if (shouldGoHome) {
            if (enemyRuinToReport != null) {
                if (sendEnemyRuinMessage(rc, enemyRuinToReport)) {
                    enemyRuinToReport = null;
                }
            }
            refuel(rc);
            soldierPostTurn(rc);
            return;
        }

        // tower building
        if (rc.getNumberTowers() < 25) {
            if (targetRuin == null) {
                targetRuin = findNearestUnbuiltRuin(rc);
                ruinBuildTurns = 0;
            }
            if (targetRuin != null) {
                doTowerBuild(rc, targetRuin);
                soldierPostTurn(rc);
                return;
            }
        }

        // help nearby tower patterns
        tryHelpNearbyPattern(rc);

        // upgrade towers
        tryUpgradeTower(rc);

        // explore
        explore(rc);
        soldierPostTurn(rc);
    }

    // paint the tile im standing on if needed
    static void soldierPostTurn(RobotController rc) throws GameActionException {
        MapLocation myloc = rc.getLocation();
        if (rc.canAttack(myloc) && rc.getPaint() >= 50) {
            MapInfo mi = rc.senseMapInfo(myloc);
            if (mi.getPaint() == PaintType.EMPTY && mi.isPassable()) {
                boolean useSecondary = getGoodColor(rc, myloc);
                rc.attack(myloc, useSecondary);
            }
        }
        // try complete any tower pattern nearby
        if (targetRuin != null) {
            if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetRuin)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetRuin);
                targetRuin = null; targetTowerType = null;
            } else if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, targetRuin)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, targetRuin);
                targetRuin = null; targetTowerType = null;
            }
        }
    }

    // ================================================================
    //                           MOPPER
    // ================================================================
    public static void runMopper(RobotController rc) throws GameActionException {
        int myPaint = rc.getPaint();
        int paintCap = rc.getType().paintCapacity;
        MapInfo[] near = rc.senseNearbyMapInfos();

        // emergency flee if on enemy paint and very low
        if (myPaint <= paintCap / 8 && rc.isMovementReady()) {
            MapInfo here = rc.senseMapInfo(rc.getLocation());
            if (here.getPaint().isEnemy()) {
                fleeToAllyPaint(rc);
            }
        }

        setHome(rc);
        readMopperMessages(rc);
        spotAndReportEnemies(rc);
        readAttackMessages(rc);
        computeMopTarget(rc, near);

        // rally to attack enemy tower — only if close
        if (attackTarget != null) {
            MapLocation mopLoc = rc.getLocation();
            if (mopLoc.distanceSquaredTo(attackTarget) > 100) {
                attackTarget = null;
            } else if (rc.canSenseLocation(attackTarget)) {
                RobotInfo etower = rc.senseRobotAtLocation(attackTarget);
                if (etower == null || etower.team == rc.getTeam()) {
                    attackTarget = null;
                } else {
                    moveToward(rc, attackTarget);
                    tryMopSwing(rc);
                    mopperPostTurn(rc);
                    return;
                }
            } else {
                moveToward(rc, attackTarget);
                mopperPostTurn(rc);
                return;
            }
        }

        // paint management
        if (myPaint > paintCap / 3) {
            shouldGoHome = false;
        } else if (myPaint <= paintCap / 5 && !shouldGoHome
                   && rc.getRoundNum() - refuelGiveUpRound >= 15) {
            shouldGoHome = true;

        }

        // refuel
        if (shouldGoHome && homeState == 2) {
            refuel(rc);
            tryMopSwing(rc);
            mopperPostTurn(rc);
            return;
        }

        // attack enemies with paint
        if (shouldMopperMicro(rc)) {
            mopperAttackMicro(rc);
            mopperPostTurn(rc);
            return;
        }

        // help build towers (mopper can help with tainted ruins)
        if (rc.getNumberTowers() < 25) {
            MapLocation taintedRuin = findTaintedRuin(rc);
            if (taintedRuin != null) {
                handleTaintedRuin(rc, taintedRuin);
                mopperPostTurn(rc);
                return;
            }
        }

        // go to mop target
        if (mopTarget != null) {
            handleMopTarget(rc, mopTarget);
            mopperPostTurn(rc);
            return;
        }

        // called to help fight enemy units
        if (mopperCallTarget != null) {
            if (rc.getLocation().distanceSquaredTo(mopperCallTarget) <= 8) {
                mopperCallTarget = null; // arrived, clear
            } else {
                moveToward(rc, mopperCallTarget);
                tryMopSwing(rc);
                mopperPostTurn(rc);
                return;
            }
        }

        // explore
        explore(rc);
        mopperPostTurn(rc);
    }

    // after moving, mop any adjacent enemy paint
    static void mopperPostTurn(RobotController rc) throws GameActionException {
        MapLocation myloc = rc.getLocation();
        for (Direction dir : directions) {
            MapLocation nloc = myloc.add(dir);
            if (rc.canSenseLocation(nloc)) {
                MapInfo mi = rc.senseMapInfo(nloc);
                if (mi.getPaint().isEnemy() && rc.canAttack(nloc)) {
                    rc.attack(nloc);
                    return;
                }
            }
        }
        // also check center
        if (rc.canSenseLocation(myloc)) {
            MapInfo mi = rc.senseMapInfo(myloc);
            if (mi.getPaint().isEnemy() && rc.canAttack(myloc)) {
                rc.attack(myloc);
            }
        }
    }

    // ================================================================
    //                          SPLASHER
    // ================================================================
    public static void runSplasher(RobotController rc) throws GameActionException {
        setHome(rc);
        spotAndReportEnemies(rc);
        readAttackMessages(rc);
        int myPaint = rc.getPaint();
        int paintCap = rc.getType().paintCapacity;

        // paint management
        if (myPaint > paintCap / 2) {
            shouldGoHome = false;
        } else if (myPaint <= paintCap / 3 && !shouldGoHome
                   && rc.getRoundNum() - refuelGiveUpRound >= 10) {
            shouldGoHome = true;

            refuelWaitTurns = 0;
        }

        // rally to attack enemy tower
        if (attackTarget != null) {
            MapLocation myloc = rc.getLocation();
            if (myloc.distanceSquaredTo(attackTarget) > 100) {
                attackTarget = null;
            } else if (rc.canSenseLocation(attackTarget)) {
                RobotInfo etower = rc.senseRobotAtLocation(attackTarget);
                if (etower == null || etower.team == rc.getTeam()) {
                    attackTarget = null;
                } else {
                    moveToward(rc, attackTarget);
                    splasherDoAttack(rc);
                    return;
                }
            } else {
                moveToward(rc, attackTarget);
                splasherDoAttack(rc);
                return;
            }
        }

        if (shouldGoHome) {
            refuel(rc);
            return;
        }

        // splasher micro: if enemies nearby, approach and splash them
        if (shouldSplasherMicro(rc)) {
            splasherMicro(rc);
            splasherDoAttack(rc);
            return;
        }

        // splash before moving (might be in range already)
        splasherDoAttack(rc);

        // movement priority:
        // 1. if enemy paint nearby, go paint over it
        // 2. if empty tiles nearby, go paint them
        // 3. otherwise, pick a far target and walk there (don't orbit base)
        MapLocation enemyPaint = findNearestEnemyPaint(rc);
        if (enemyPaint != null) {
            moveToward(rc, enemyPaint);
            splasherExploreTarget = null;
        } else {
            MapLocation emptyPaint = findNearestEmptyPaint(rc);
            if (emptyPaint != null) {
                moveToward(rc, emptyPaint);
                splasherExploreTarget = null;
            } else {
                // everything nearby is ally paint — commit to a far target
                splasherExplore(rc);
            }
        }

        // splash after moving too
        splasherDoAttack(rc);
    }

    // check if splasher should micro against enemies
    static boolean shouldSplasherMicro(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(20, rc.getTeam().opponent());
        for (RobotInfo e : enemies) {
            if (!e.type.isTowerType() && e.paintAmount > 0) return true;
        }
        return false;
    }

    // micro toward enemies to splash them
    static void splasherMicro(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(20, rc.getTeam().opponent());
        if (enemies.length == 0) return;
        // find nearest enemy with paint
        RobotInfo target = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo e : enemies) {
            if (e.type.isTowerType()) continue;
            int d = rc.getLocation().distanceSquaredTo(e.location);
            if (d < bestDist) { bestDist = d; target = e; }
        }
        if (target != null) {
            moveToward(rc, target.location);
        }
    }

    // evaluate each attackable tile's 3x3 splash area (like mopper_and_srp)
    // enemy paint = +2, empty = +1, ally = 0
    static void splasherDoAttack(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        if (rc.getPaint() < rc.getType().paintCapacity / 4) return;

        MapLocation bestLoc = null;
        int bestScore = 0;

        // check all attackable locations (within action radius)
        MapInfo[] attackable = rc.senseNearbyMapInfos(rc.getType().actionRadiusSquared);
        for (MapInfo tile : attackable) {
            MapLocation loc = tile.getMapLocation();
            if (!rc.canAttack(loc)) continue;
            if (tile.isWall() || tile.hasRuin()) continue;

            // score the 3x3 area around the target (splash zone)
            int score = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    MapLocation adj = new MapLocation(loc.x + dx, loc.y + dy);
                    if (rc.canSenseLocation(adj)) {
                        MapInfo mi = rc.senseMapInfo(adj);
                        if (mi.isWall() || mi.hasRuin()) continue;
                        PaintType p = mi.getPaint();
                        if (p.isEnemy()) score += 2;
                        else if (p == PaintType.EMPTY) score += 1;
                        // ally paint = 0 (no benefit to paint over our own)
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestLoc = loc;
            }
        }

        if (bestLoc != null && bestScore >= 1 && rc.canAttack(bestLoc)) {
            rc.attack(bestLoc);
        }
    }

    // ================================================================
    //               HOME SYSTEM (like RefuelManager)
    // ================================================================
    static int getTowerState(RobotInfo robot) {
        if (robot == null) return 0; // ruin
        switch (robot.type) {
            case LEVEL_ONE_PAINT_TOWER:
            case LEVEL_TWO_PAINT_TOWER:
            case LEVEL_THREE_PAINT_TOWER:
                return 2; // paint tower
            default:
                return (robot.paintAmount > 0) ? 2 : 1;
        }
    }

    static void setHome(RobotController rc) throws GameActionException {
        if (home != null && rc.canSenseLocation(home)) {
            RobotInfo robot = rc.senseRobotAtLocation(home);
            homeState = getTowerState(robot);
        }
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        for (MapLocation ruin : ruins) {
            RobotInfo robot = rc.senseRobotAtLocation(ruin);
            if (robot != null && robot.team != rc.getTeam()) continue;
            int ts = getTowerState(robot);
            if (home == null || ts >= homeState) {
                home = ruin;
                homeState = ts;
    
            }
        }
    }

    static int refuelWaitTurns = 0;

    static void refuel(RobotController rc) throws GameActionException {
        if (home == null) {
            // no known tower — try to find one
            setHome(rc);
            if (home == null) {
                explore(rc);
                return;
            }
        }

        if (homeState != 2) {
            // home is not a paint tower, find a better one
            home = null;
            homeState = 0;
            setHome(rc);
            if (home == null || homeState != 2) {
                explore(rc);
                return;
            }
        }

        int distToHome = rc.getLocation().distanceSquaredTo(home);

        // still traveling to tower
        if (distToHome > 2) {
            moveToward(rc, home);
            return;
        }

        // we're adjacent to the tower — now count wait turns
        refuelWaitTurns++;
        if (refuelWaitTurns > 6) {
            // waited too long at tower, give up
            shouldGoHome = false;
            refuelWaitTurns = 0;
            refuelGiveUpRound = rc.getRoundNum();
            home = null;
            homeState = 0;
            return;
        }

        RobotInfo r = rc.senseRobotAtLocation(home);
        if (r == null) {
            // tower destroyed
            home = null;
            homeState = 0;
            shouldGoHome = false;
            refuelWaitTurns = 0;
            return;
        }
        if (r.paintAmount < 10) {
            // tower nearly empty — find another
            home = null;
            homeState = 0;
            refuelWaitTurns = 0;
            // don't clear shouldGoHome — still need paint, try another tower
            return;
        }
        int amt = Math.max(rc.getPaint() - rc.getType().paintCapacity, -r.paintAmount);
        if (rc.canTransferPaint(home, amt)) {
            rc.transferPaint(home, amt);
            shouldGoHome = false;
            refuelWaitTurns = 0;
        }
    }

    // ================================================================
    //                    EXPLORE (random map target)
    // ================================================================
    static void explore(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;

        MapLocation myloc = rc.getLocation();
        int mapW = rc.getMapWidth();
        int mapH = rc.getMapHeight();

        // ally repulsion: spread out when crowded
        RobotInfo[] allies = rc.senseNearbyRobots(13, rc.getTeam());
        int mobileAllies = 0;
        int ax = 0, ay = 0;
        for (RobotInfo a : allies) {
            if (!a.type.isTowerType()) {
                mobileAllies++;
                ax += a.location.x;
                ay += a.location.y;
            }
        }
        if (mobileAllies >= 3) {
            ax /= mobileAllies;
            ay /= mobileAllies;
            MapLocation allyCOM = new MapLocation(ax, ay);
            int dx = myloc.x - allyCOM.x;
            int dy = myloc.y - allyCOM.y;
            // if delta is zero (exactly on COM), pick random direction
            if (dx == 0 && dy == 0) {
                dx = rng.nextInt(3) - 1;
                dy = rng.nextInt(3) - 1;
            }
            int tx = Math.max(0, Math.min(mapW - 1, myloc.x + dx * 5));
            int ty = Math.max(0, Math.min(mapH - 1, myloc.y + dy * 5));
            exploreTarget = new MapLocation(tx, ty);
            moveToward(rc, exploreTarget);
            return;
        }

        // bias toward unpainted tiles: if we see lots of ally paint, move toward empty/enemy
        if (exploreTarget == null || myloc.distanceSquaredTo(exploreTarget) <= 5) {
            // try to pick a target biased toward the map edge we haven't explored
            // use distance from center — go outward
            int cx = mapW / 2, cy = mapH / 2;
            int dx = myloc.x - cx;
            int dy = myloc.y - cy;
            // add randomness to avoid all bots going same direction
            int tx = Math.max(0, Math.min(mapW - 1, myloc.x + dx + rng.nextInt(11) - 5));
            int ty = Math.max(0, Math.min(mapH - 1, myloc.y + dy + rng.nextInt(11) - 5));
            // but if we're already near an edge, go to a random unexplored area
            if (myloc.x <= 3 || myloc.x >= mapW - 4 || myloc.y <= 3 || myloc.y >= mapH - 4) {
                tx = rng.nextInt(mapW);
                ty = rng.nextInt(mapH);
            }
            exploreTarget = new MapLocation(tx, ty);
        }
        moveToward(rc, exploreTarget);
    }

    // ================================================================
    //                    MOVEMENT: moveToward (greedy + Bug2)
    // ================================================================
    static boolean bugFollowing = false;
    static int bugBestDist = Integer.MAX_VALUE;
    static int bugTurns = 0;
    static boolean bugRotateRight = true;
    static MapLocation lastMoveTarget = null;
    static MapLocation bugPrevLoc = null;
    static int stuckCount = 0;

    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        if (rc.getLocation().equals(target)) return;

        // reset bug state when target changes
        if (lastMoveTarget == null || !lastMoveTarget.equals(target)) {
            bugFollowing = false;
            bugRotateRight = rng.nextBoolean(); // randomize to avoid systematic bias
            lastMoveTarget = target;
            stuckCount = 0;
        }

        MapLocation myLoc = rc.getLocation();
        int curDist = myLoc.distanceSquaredTo(target);

        // exit bug if we've made progress (closer than best seen during bug)
        if (bugFollowing && curDist < bugBestDist) {
            bugFollowing = false;
        }
        // timeout: switch direction and retry
        if (bugFollowing && bugTurns > 16) {
            bugFollowing = false;
            bugRotateRight = !bugRotateRight;
        }
        // no progress in 8 turns: flip rotation
        if (bugFollowing && bugTurns > 8 && curDist >= bugBestDist) {
            bugRotateRight = !bugRotateRight;
            bugTurns = 0;
            bugBestDist = curDist;
        }

        if (!bugFollowing) {
            // greedy: pick best neighbor — accept equal distance (allows sidestepping)
            Direction bestDir = null;
            int bestDist = curDist;
            int bestPaintScore = Integer.MIN_VALUE;

            // sense enemy towers for soft avoidance (penalty, not hard block)
            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            // check if we're ALREADY in enemy tower range
            boolean alreadyInRange = false;
            for (RobotInfo enemy : enemies) {
                if (enemy.type.isTowerType()
                    && myLoc.distanceSquaredTo(enemy.location) <= enemy.type.actionRadiusSquared) {
                    alreadyInRange = true;
                    break;
                }
            }

            for (Direction dir : directions) {
                if (!rc.canMove(dir)) continue;
                MapLocation next = myLoc.add(dir);
                int dist = next.distanceSquaredTo(target);

                int paintScore = 0;

                // soft tower avoidance: penalize entering tower range (don't hard block)
                for (RobotInfo enemy : enemies) {
                    if (enemy.type.isTowerType()) {
                        int nextDistToTower = next.distanceSquaredTo(enemy.location);
                        if (nextDistToTower <= enemy.type.actionRadiusSquared) {
                            if (!alreadyInRange) {
                                paintScore -= 5; // discourage entering tower range
                            }
                            int curDistToTower = myLoc.distanceSquaredTo(enemy.location);
                            if (nextDistToTower < curDistToTower) {
                                paintScore -= 3; // discourage moving closer to tower
                            }
                        } else if (alreadyInRange) {
                            paintScore += 3; // bonus for escaping tower range
                        }
                    }
                }

                // strictly prefer closer distance; only use paintScore as tiebreaker
                if (dist < bestDist || (dist == bestDist && paintScore > bestPaintScore)) {
                    bestDist = dist;
                    bestDir = dir;
                    bestPaintScore = paintScore;
                }
            }
            if (bestDir != null) {
                rc.move(bestDir);
                stuckCount = 0;
                return;
            }
            // greedy failed — start Bug2 wall following
            bugFollowing = true;
            bugBestDist = curDist;
            bugTurns = 0;
            bugPrevLoc = myLoc;
        }

        // Bug2 wall following
        if (bugFollowing) {
            bugTurns++;
            if (curDist < bugBestDist) bugBestDist = curDist;

            // detect stuck in same spot
            if (bugPrevLoc != null && myLoc.equals(bugPrevLoc)) {
                stuckCount++;
                if (stuckCount > 2) {
                    bugRotateRight = !bugRotateRight;
                    stuckCount = 0;
                }
            } else {
                stuckCount = 0;
            }
            bugPrevLoc = myLoc;

            // first try moving directly toward target (in case wall ended)
            Direction toward = myLoc.directionTo(target);
            if (rc.canMove(toward)) {
                rc.move(toward);
                bugFollowing = false;
                return;
            }

            // also try the two adjacent directions to "toward" before full wall follow
            Direction left1 = toward.rotateLeft();
            Direction right1 = toward.rotateRight();
            if (rc.canMove(left1) && myLoc.add(left1).distanceSquaredTo(target) < curDist) {
                rc.move(left1);
                bugFollowing = false;
                return;
            }
            if (rc.canMove(right1) && myLoc.add(right1).distanceSquaredTo(target) < curDist) {
                rc.move(right1);
                bugFollowing = false;
                return;
            }

            // wall follow: rotate from toward-target to find an opening
            Direction dir = toward;
            for (int i = 0; i < 8; i++) {
                dir = bugRotateRight ? dir.rotateRight() : dir.rotateLeft();
                if (rc.canMove(dir)) {
                    rc.move(dir);
                    return;
                }
            }
            // completely stuck, flip and reset
            bugRotateRight = !bugRotateRight;
            bugFollowing = false;
        }
    }

    // ================================================================
    //                    SOLDIER: TOWER BUILDING
    // ================================================================
    static boolean enoughPaintForTower(RobotController rc, MapLocation ruin) throws GameActionException {
        int needed = 0;
        MapInfo[] area = rc.senseNearbyMapInfos(ruin, 8);
        for (MapInfo tile : area) {
            PaintType mark = tile.getMark();
            if (mark == PaintType.EMPTY) continue;
            if (mark != tile.getPaint()) needed += 5;
        }
        return rc.getPaint() >= needed;
    }

    static MapLocation lastBlockedRuin = null;
    static int lastBlockedRound = -100;

    static void doTowerBuild(RobotController rc, MapLocation ruin) throws GameActionException {
        ruinBuildTurns++;
        // stuck building for too long, give up
        if (ruinBuildTurns > 30) {
            lastBlockedRuin = ruin;
            lastBlockedRound = rc.getRoundNum();
            targetRuin = null; targetTowerType = null;
            ruinBuildTurns = 0;
            return;
        }

        if (rc.canSenseLocation(ruin)) {
            RobotInfo occupant = rc.senseRobotAtLocation(ruin);
            if (occupant != null && occupant.getType().isTowerType()) {
                targetRuin = null; targetTowerType = null;
                ruinBuildTurns = 0;
                return;
            }

            // congestion check: if another soldier with LOWER ID is here, we leave
            // (lower ID wins tiebreak — prevents both soldiers giving up)
            RobotInfo[] nearRuin = rc.senseNearbyRobots(ruin, 8, rc.getTeam());
            for (RobotInfo ally : nearRuin) {
                if (ally.type == UnitType.SOLDIER && ally.ID < rc.getID()) {
                    // a lower-ID soldier is already here, let them handle it
                    targetRuin = null; targetTowerType = null;
                    ruinBuildTurns = 0;
                    return;
                }
            }
        }

        int distToRuin = rc.getLocation().distanceSquaredTo(ruin);

        if (distToRuin > 8) {
            moveToward(rc, ruin);
            return;
        }

        // pick tower type ONCE and remember it for this ruin
        if (targetTowerType == null) {
            targetTowerType = getTowerToBuild(rc, ruin);
        }
        UnitType towerType = targetTowerType;

        if (rc.canMarkTowerPattern(towerType, ruin)) {
            rc.markTowerPattern(towerType, ruin);
        }

        MapLocation tileToPaint = null;
        int nearestDist = Integer.MAX_VALUE;
        boolean useSecondary = false;
        boolean hasEnemyPaint = false;

        for (MapInfo tile : rc.senseNearbyMapInfos(ruin, 8)) {
            PaintType mark = tile.getMark();
            if (mark == PaintType.EMPTY) continue;
            if (tile.getPaint().isEnemy()) {
                hasEnemyPaint = true;
                continue;
            }
            if (mark == tile.getPaint()) continue;
            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < nearestDist) {
                nearestDist = dist;
                tileToPaint = loc;
                useSecondary = (mark == PaintType.ALLY_SECONDARY);
            }
        }

        // enemy paint blocks us — give up this ruin entirely, report, and move on
        if (hasEnemyPaint && tileToPaint == null) {
            enemyRuinToReport = ruin;
            lastBlockedRuin = ruin;
            lastBlockedRound = rc.getRoundNum();
            targetRuin = null; targetTowerType = null;
            ruinBuildTurns = 0;
            return;
        }

        if (tileToPaint != null) {
            if (!rc.canAttack(tileToPaint)) {
                moveToward(rc, tileToPaint);
            }
            if (rc.canAttack(tileToPaint)) {
                rc.attack(tileToPaint, useSecondary);
            }
            return;
        }

        if (rc.canCompleteTowerPattern(towerType, ruin)) {
            rc.completeTowerPattern(towerType, ruin);
            targetRuin = null; targetTowerType = null;
            return;
        }

        // nothing to paint and can't complete — move closer or give up
        if (distToRuin > 2) {
            moveToward(rc, ruin);
        } else {
            // we're adjacent but can't complete — something is wrong, give up
            targetRuin = null; targetTowerType = null;
            ruinBuildTurns = 0;
        }
    }

    // 2:1 paint:money ratio based on total tower count
    static UnitType getTowerToBuild(RobotController rc, MapLocation loc) throws GameActionException {
        int total = rc.getNumberTowers();
        // towers 0,1 = paint, 2 = money, 3,4 = paint, 5 = money, ...
        if (total % 3 == 2)
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        return UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    static MapLocation findNearestUnbuiltRuin(RobotController rc) throws GameActionException {
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation closest = null;
        int closestDist = Integer.MAX_VALUE;
        for (MapLocation ruin : ruins) {
            RobotInfo occupant = rc.senseRobotAtLocation(ruin);
            if (occupant != null && occupant.getType().isTowerType()) continue;

            // skip recently blocked ruins (cooldown 30 rounds)
            if (lastBlockedRuin != null && ruin.equals(lastBlockedRuin)
                && rc.getRoundNum() - lastBlockedRound < 15) continue;

            // skip ruins near enemy towers — building there is suicide
            boolean nearEnemyTower = false;
            for (RobotInfo enemy : enemies) {
                if (enemy.type.isTowerType() && ruin.distanceSquaredTo(enemy.location) <= 36) {
                    nearEnemyTower = true;
                    break;
                }
            }
            if (nearEnemyTower) continue;

            // skip ruins where a lower-ID soldier is already working
            // (lower ID gets priority — prevents oscillation where both give up)
            RobotInfo[] nearRuin = rc.senseNearbyRobots(ruin, 8, rc.getTeam());
            boolean lowerIdSoldierPresent = false;
            for (RobotInfo ally : nearRuin) {
                if (ally.type == UnitType.SOLDIER && ally.ID < rc.getID()) {
                    lowerIdSoldierPresent = true;
                    break;
                }
            }
            if (lowerIdSoldierPresent) continue;

            int dist = rc.getLocation().distanceSquaredTo(ruin);
            if (dist < closestDist) {
                closestDist = dist;
                closest = ruin;
            }
        }
        return closest;
    }

    // help paint nearby incomplete patterns
    static boolean tryHelpNearbyPattern(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;
        MapInfo[] nearby = rc.senseNearbyMapInfos(rc.getLocation(), 4);
        for (MapInfo tile : nearby) {
            PaintType mark = tile.getMark();
            if (mark == PaintType.EMPTY) continue;
            if (tile.getPaint().isEnemy()) continue;
            if (mark == tile.getPaint()) continue;
            MapLocation loc = tile.getMapLocation();
            if (rc.canAttack(loc)) {
                boolean useSecondary = (mark == PaintType.ALLY_SECONDARY);
                rc.attack(loc, useSecondary);
                return true;
            }
        }
        return false;
    }

    static void tryUpgradeTower(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();
        if (round < UPGRADE_L2_ROUND) return;
        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo robot : nearbyRobots) {
            if (!robot.getType().isTowerType()) continue;
            if (!robot.getType().canUpgradeType()) continue;
            int level = robot.getType().level;
            if (level == 1 && round >= UPGRADE_L2_ROUND) { /* ok */ }
            else if (level == 2 && round >= UPGRADE_L3_ROUND) { /* ok */ }
            else continue;
            MapLocation towerLoc = robot.getLocation();
            if (rc.getLocation().distanceSquaredTo(towerLoc) <= 2) {
                if (rc.canUpgradeTower(towerLoc)) {
                    rc.upgradeTower(towerLoc);
                    return;
                }
            }
        }
    }

    // ================================================================
    //                   SRP RESOURCE PATTERN
    // ================================================================
    static boolean shouldUseSecond(MapLocation loc, MapLocation center) {
        return SRP_PATTERN[2 + (loc.x - center.x)][2 + (loc.y - center.y)] == 2;
    }

    // figure out if a tile is in range of a nearby resource pattern center
    static boolean getGoodColor(RobotController rc, MapLocation m) throws GameActionException {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation tmp = new MapLocation(m.x + dx, m.y + dy);
                if (rc.canSenseLocation(tmp)) {
                    MapInfo mi = rc.senseMapInfo(tmp);
                    if (mi.getMark() == PaintType.ALLY_PRIMARY) {
                        return shouldUseSecond(m, tmp);
                    }
                }
            }
        }
        return false;
    }

    // SRP pattern (from bot_2_5): 2=secondary, 1=primary
    static final int[][] SRP_PATTERN = {
        {2,2,1,2,2},{2,1,1,1,2},{1,1,2,1,1},{2,1,1,1,2},{2,2,1,2,2}
    };


    // ================================================================
    //                    MOPPER HELPERS
    // ================================================================
    static void readMopperMessages(RobotController rc) throws GameActionException {
        Message[] msgs = rc.readMessages(rc.getRoundNum() - 1);
        for (Message msg : msgs) {
            int data = msg.getBytes();
            int type = (data >> 12) & 0xF;
            if (type == MSG_ENEMY_RUIN) {
                int x = (data >> 6) & 0x3F;
                int y = data & 0x3F;
                mopTarget = new MapLocation(x, y);
            }
        }
    }

    // compute mop target from visible enemy paint, avoiding enemy towers
    static void computeMopTarget(RobotController rc, MapInfo[] near) throws GameActionException {
        // find enemy towers to avoid
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo enemyTower = null;
        for (RobotInfo enemy : enemies) {
            if (enemy.type.isTowerType()) {
                enemyTower = enemy;
                break;
            }
        }

        MapLocation myloc = rc.getLocation();
        int closest = Integer.MAX_VALUE;
        MapLocation bestLoc = null;

        for (int i = near.length; --i >= 0;) {
            PaintType p = near[i].getPaint();
            if (!p.isEnemy()) continue;
            MapLocation loc = near[i].getMapLocation();
            int d = loc.distanceSquaredTo(myloc);
            if (d < closest) {
                // avoid enemy tower range
                if (enemyTower != null && loc.distanceSquaredTo(enemyTower.location) <= enemyTower.type.actionRadiusSquared + 20)
                    continue;
                closest = d;
                bestLoc = loc;
            }
        }

        // keep current target if still valid
        if (mopTarget != null && rc.canSenseLocation(mopTarget)) {
            MapInfo mi = rc.senseMapInfo(mopTarget);
            if (mi.getPaint().isEnemy()) return;
            mopTarget = null;
        }
        if (bestLoc != null) mopTarget = bestLoc;
    }

    // mopper close movement with tile scoring
    static void handleMopTarget(RobotController rc, MapLocation target) throws GameActionException {
        if (rc.getLocation().distanceSquaredTo(target) >= 9) {
            moveToward(rc, target);
            return;
        }

        // tile scoring: enemy=3 (best to mop), empty=1, ally=0 (already clean)
        int bestDist = Integer.MAX_VALUE;
        int bestScore = -1;
        Direction bestDir = null;

        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            MapLocation nloc = rc.getLocation().add(dir);
            MapInfo mi = rc.senseMapInfo(nloc);
            int score;
            PaintType p = mi.getPaint();
            if (p.isEnemy()) score = 3;       // want to step on enemy paint to mop it
            else if (p == PaintType.EMPTY) score = 1;
            else score = 0;                    // ally paint, nothing to do

            int dist = nloc.distanceSquaredTo(target);
            if (score > bestScore || (score == bestScore && dist < bestDist)) {
                bestScore = score;
                bestDist = dist;
                bestDir = dir;
            }
        }

        if (bestDir != null && rc.canMove(bestDir)) {
            rc.move(bestDir);
        }
    }

    // detect if mopper should micro (enemy units with paint nearby)
    static boolean shouldMopperMicro(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            if (!enemy.type.isTowerType() && enemy.paintAmount > 0) {
                return true;
            }
        }
        return false;
    }

    // attack nearest enemy with paint
    static void mopperAttackMicro(RobotController rc) throws GameActionException {
        tryMopSwing(rc);

        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo target = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : enemies) {
            if (enemy.type.isTowerType()) continue;
            if (enemy.paintAmount <= 0) continue;
            int d = rc.getLocation().distanceSquaredTo(enemy.location);
            if (d < bestDist) {
                bestDist = d;
                target = enemy;
            }
        }
        if (target != null) {
            moveToward(rc, target.location);
        }
        tryMopSwing(rc);
    }

    // find ruin with enemy paint nearby (mopper can help clear)
    static MapLocation findTaintedRuin(RobotController rc) throws GameActionException {
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        for (MapLocation ruin : ruins) {
            RobotInfo occupant = rc.senseRobotAtLocation(ruin);
            if (occupant != null && occupant.getType().isTowerType()) continue;
            // check if any tiles in 5x5 have enemy paint
            MapInfo[] area = rc.senseNearbyMapInfos(ruin, 8);
            for (MapInfo tile : area) {
                if (tile.getPaint().isEnemy()) {
                    return ruin;
                }
            }
        }
        return null;
    }

    // mopper handles tainted ruin by mopping enemy paint in 5x5 area
    static void handleTaintedRuin(RobotController rc, MapLocation ruin) throws GameActionException {
        MapInfo[] area = rc.senseNearbyMapInfos(ruin, 8);
        MapLocation dirtyTile = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapInfo tile : area) {
            if (!tile.getPaint().isEnemy()) continue;
            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < bestDist) {
                bestDist = dist;
                dirtyTile = loc;
            }
        }
        if (dirtyTile != null) {
            if (rc.getLocation().distanceSquaredTo(dirtyTile) > 2) {
                moveToward(rc, dirtyTile);
            }
            if (rc.canAttack(dirtyTile)) {
                rc.attack(dirtyTile);
            }
        }
    }

    static boolean tryMopSwing(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;
        Direction bestSwing = null;
        int bestHits = 0;
        for (Direction dir : cardinals) {
            if (!rc.canMopSwing(dir)) continue;
            int hits = countEnemiesInSwingDir(rc, dir);
            if (hits > bestHits) {
                bestHits = hits;
                bestSwing = dir;
            }
        }
        if (bestSwing != null && bestHits > 0) {
            rc.mopSwing(bestSwing);
            return true;
        }
        return false;
    }

    static int countEnemiesInSwingDir(RobotController rc, Direction dir) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation myLoc = rc.getLocation();
        MapLocation step1 = myLoc.add(dir);
        MapLocation step2 = step1.add(dir);
        int count = 0;
        for (RobotInfo enemy : enemies) {
            MapLocation eLoc = enemy.location;
            if (eLoc.distanceSquaredTo(step1) <= 2 || eLoc.distanceSquaredTo(step2) <= 2) {
                count++;
            }
        }
        return count;
    }

    // ================================================================
    //                    COMMUNICATION HELPERS
    // ================================================================
    static int encodeLocation(int msgType, MapLocation loc) {
        return (msgType << 12) | (loc.x << 6) | loc.y;
    }

    // spot enemies and report: tower→soldiers attack, units→moppers come help
    static void spotAndReportEnemies(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        boolean foundTower = false;
        boolean foundUnits = false;
        MapLocation towerLoc = null;
        MapLocation unitLoc = null;

        for (RobotInfo enemy : enemies) {
            if (enemy.type.isTowerType() && !foundTower) {
                foundTower = true;
                towerLoc = enemy.location;
                attackTarget = towerLoc;
            } else if (!enemy.type.isTowerType() && !foundUnits) {
                foundUnits = true;
                unitLoc = enemy.location;
            }
        }

        if (!foundTower && !foundUnits) return;

        // report to nearest ally tower
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (!ally.type.isTowerType()) continue;
            if (foundTower) {
                int msg = encodeLocation(MSG_ENEMY_TOWER, towerLoc);
                if (rc.canSendMessage(ally.location, msg)) {
                    rc.sendMessage(ally.location, msg);
                }
            }
            if (foundUnits) {
                int msg = encodeLocation(MSG_ENEMY_UNITS, unitLoc);
                if (rc.canSendMessage(ally.location, msg)) {
                    rc.sendMessage(ally.location, msg);
                }
            }
            return;
        }
    }

    // read orders from towers: soldiers get attack targets, moppers get call targets
    static void readAttackMessages(RobotController rc) throws GameActionException {
        for (int r = rc.getRoundNum() - 1; r <= rc.getRoundNum(); r++) {
            if (r < 0) continue;
            Message[] msgs = rc.readMessages(r);
            for (Message msg : msgs) {
                int data = msg.getBytes();
                int type = (data >> 12) & 0xF;
                int x = (data >> 6) & 0x3F;
                int y = data & 0x3F;
                if (type == MSG_ENEMY_TOWER) {
                    attackTarget = new MapLocation(x, y);
                } else if (type == MSG_ENEMY_UNITS) {
                    mopperCallTarget = new MapLocation(x, y);
                }
            }
        }
    }

    static boolean sendEnemyRuinMessage(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.type.isTowerType()) {
                int msg = encodeLocation(MSG_ENEMY_RUIN, ruinLoc);
                if (rc.canSendMessage(ally.location, msg)) {
                    rc.sendMessage(ally.location, msg);
                    return true;
                }
            }
        }
        return false;
    }

    // emergency: move to nearest ally or empty tile to stop dying on enemy paint
    static void fleeToAllyPaint(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        Direction bestDir = null;
        int bestScore = -999;
        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            MapLocation next = rc.getLocation().add(dir);
            if (!rc.canSenseLocation(next)) continue;
            MapInfo mi = rc.senseMapInfo(next);
            int score = 0;
            if (mi.getPaint().isAlly()) score = 3;
            else if (mi.getPaint() == PaintType.EMPTY) score = 1;
            else score = -1; // still enemy
            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }
        if (bestDir != null && bestScore > -1) {
            rc.move(bestDir);
        }
    }

    // ================================================================
    //                  SHARED PAINT HELPERS
    // ================================================================
    // when all nearby tiles are ally paint, move toward the edge of painted territory
    // splasher-specific explore: pick a random far point and walk there
    static void splasherExplore(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        MapLocation myloc = rc.getLocation();
        int mapW = rc.getMapWidth();
        int mapH = rc.getMapHeight();

        // pick a new target if we don't have one or we're close to it
        if (splasherExploreTarget == null || myloc.distanceSquaredTo(splasherExploreTarget) <= 8) {
            // pick a completely random point on the map — forces splashers to spread
            splasherExploreTarget = new MapLocation(rng.nextInt(mapW), rng.nextInt(mapH));
        }
        moveToward(rc, splasherExploreTarget);
    }

    static MapLocation findNearestEnemyPaint(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation[] enemyTowers = new MapLocation[enemies.length];
        int towerCount = 0;
        for (RobotInfo enemy : enemies) {
            if (enemy.type.isTowerType()) {
                enemyTowers[towerCount++] = enemy.location;
            }
        }

        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : tiles) {
            if (!tile.getPaint().isEnemy()) continue;
            MapLocation loc = tile.getMapLocation();
            boolean nearEnemyTower = false;
            for (int i = 0; i < towerCount; i++) {
                if (loc.distanceSquaredTo(enemyTowers[i]) <= 20) {
                    nearEnemyTower = true;
                    break;
                }
            }
            if (nearEnemyTower) continue;
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < bestDist) {
                bestDist = dist;
                best = loc;
            }
        }
        return best;
    }

    static MapLocation findNearestEmptyPaint(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapInfo tile : tiles) {
            if (tile.getPaint() != PaintType.EMPTY) continue;
            if (tile.isWall() || tile.hasRuin()) continue;
            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < bestDist) {
                bestDist = dist;
                best = loc;
            }
        }
        return best;
    }
}
