package nus_bot;

import battlecode.common.*;

import java.util.Random;

public class RobotPlayer {

    // global stuff
    static int turnCount = 0;
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
    static int lastSpawnRound = -10;
    static final int SPAWN_COOLDOWN = 0;
    static final int TOWER_PAINT_RESERVE = 100;

    // home/refuel system (like mopper_and_srp RefuelManager)
    static MapLocation home = null;
    static int homeState = 0; // 0=ruin, 1=non-paint tower, 2=paint tower
    static boolean reachedHome = false;
    static boolean shouldGoHome = false;
    static MapLocation returnLoc = null;

    // soldier ruin building
    static MapLocation targetRuin = null;
    static MapLocation enemyRuinToReport = null;
    static int ruinBuildTurns = 0;

    // mopper state
    static MapLocation mopTarget = null;

    // enemy tower rally point — all units go attack
    static MapLocation attackTarget = null;
    static MapLocation towerEnemyTowerLoc = null; // tower broadcasts to soldiers
    static MapLocation towerEnemyUnitsLoc = null; // tower broadcasts to moppers
    static MapLocation mopperCallTarget = null;   // mopper: go help here

    // explore target (random map location like mopper_and_srp)
    static MapLocation exploreTarget = null;

    // SRP resource pattern
    static boolean[][] resourcePat = null;
    static MapLocation markedResource = null;
    static boolean[][] moneyTowerPat = null;
    static boolean[][] paintTowerPat = null;

    // constants
    static final int UPGRADE_L2_ROUND = 500;
    static final int UPGRADE_L3_ROUND = 1000;

    // ================================================================
    //                          MAIN LOOP
    // ================================================================
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        // unique RNG per robot (like mopper_and_srp Globals.java)
        rng.setSeed((long) rc.getID());

        // init patterns once
        if (!rc.getType().isTowerType()) {
            resourcePat = rc.getResourcePattern();
            moneyTowerPat = rc.getTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER);
            paintTowerPat = rc.getTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER);
        }

        while (true) {
            turnCount += 1;
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
        if (!mopperRequested && round - lastSpawnRound < SPAWN_COOLDOWN) return;

        UnitType toBuild;
        if (mopperRequested) {
            toBuild = UnitType.MOPPER;
        } else {
            int idx = botSpawnedCount % 4;
            if (idx == 3) toBuild = UnitType.MOPPER;
            else toBuild = UnitType.SOLDIER;
        }

        for (Direction dir : directions) {
            MapLocation spawnLoc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(toBuild, spawnLoc)) {
                rc.buildRobot(toBuild, spawnLoc);
                botSpawnedCount++;
                lastSpawnRound = round;
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

            if (ally.type == UnitType.SOLDIER) {
                // soldiers get enemy tower locations
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

        // check low paint threshold
        boolean lowPaint = (myPaint <= paintCap / 4);
        if (lowPaint != shouldGoHome) {
            shouldGoHome = lowPaint;
            if (targetRuin != null && shouldGoHome) returnLoc = targetRuin;
            reachedHome = false;
        }

        // low paint tower build (like mopper_and_srp: build even when low if nearby)
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
        if (rc.getNumberTowers() < 25 && markedResource == null) {
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

        // return to previous location after refueling
        if (returnLoc != null) {
            if (myloc.distanceSquaredTo(returnLoc) <= 5) {
                returnLoc = null;
            } else {
                moveToward(rc, returnLoc);
            }
        }

        // SRP pattern building
        tryStartSRP(rc);
        if (markedResource != null) {
            makeResourcePatch(rc);
            soldierPostTurn(rc);
            return;
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
                targetRuin = null;
            } else if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, targetRuin)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, targetRuin);
                targetRuin = null;
            }
        }
        // complete SRP patterns nearby
        checkCompleteResourcePatterns(rc);
    }

    // ================================================================
    //                           MOPPER
    // ================================================================
    public static void runMopper(RobotController rc) throws GameActionException {
        int myPaint = rc.getPaint();
        int paintCap = rc.getType().paintCapacity;
        MapInfo[] near = rc.senseNearbyMapInfos();

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

        boolean lowPaint = (myPaint <= paintCap / 4);
        if (lowPaint != shouldGoHome) {
            shouldGoHome = lowPaint;
            reachedHome = false;
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
        int myPaint = rc.getPaint();
        int paintCap = rc.getType().paintCapacity;

        boolean lowPaint = (myPaint <= paintCap / 4);
        if (lowPaint != shouldGoHome) {
            shouldGoHome = lowPaint;
            reachedHome = false;
        }

        if (shouldGoHome) {
            refuel(rc);
            return;
        }

        // try splash enemy paint areas
        if (rc.isActionReady()) {
            MapLocation bestSplash = findBestSplash(rc);
            if (bestSplash != null && rc.canAttack(bestSplash)) {
                rc.attack(bestSplash);
            }
        }

        // find enemy paint and move toward it
        MapLocation enemyPaint = findNearestEnemyPaint(rc);
        if (enemyPaint != null) {
            moveToward(rc, enemyPaint);
        } else {
            explore(rc);
        }
    }

    // find tile to splash that has most enemy/empty tiles nearby
    static MapLocation findBestSplash(RobotController rc) throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos(rc.getLocation(), 4);
        MapLocation best = null;
        int bestScore = 0;
        for (MapInfo tile : nearby) {
            MapLocation loc = tile.getMapLocation();
            if (!rc.canAttack(loc)) continue;
            int score = 0;
            for (Direction d : directions) {
                MapLocation adj = loc.add(d);
                if (rc.canSenseLocation(adj)) {
                    PaintType p = rc.senseMapInfo(adj).getPaint();
                    if (p.isEnemy()) score += 3;
                    else if (p == PaintType.EMPTY) score += 1;
                }
            }
            if (tile.getPaint().isEnemy()) score += 3;
            else if (tile.getPaint() == PaintType.EMPTY) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = loc;
            }
        }
        return (bestScore >= 3) ? best : null;
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
                reachedHome = false;
            }
        }
    }

    static int refuelWaitTurns = 0;

    static void refuel(RobotController rc) throws GameActionException {
        if (home == null) {
            explore(rc);
            return;
        }

        refuelWaitTurns++;
        // give up fast so we dont sit and die
        if (refuelWaitTurns > 8) {
            shouldGoHome = false;
            refuelWaitTurns = 0;
            return;
        }

        boolean atHome = (rc.getLocation().distanceSquaredTo(home) <= 9);
        if (atHome && !reachedHome) {
            reachedHome = true;
        }

        if (!reachedHome && homeState == 2) {
            moveToward(rc, home);
        } else if (atHome && homeState == 2) {
            // try to get adjacent to tower for paint transfer
            if (rc.getLocation().distanceSquaredTo(home) > 2) {
                moveToward(rc, home);
            }
            RobotInfo r = rc.senseRobotAtLocation(home);
            if (r == null) {
                homeState = getTowerState(r);
            } else {
                int amt = Math.max(rc.getPaint() - rc.getType().paintCapacity, -r.paintAmount);
                if (rc.canTransferPaint(home, amt)) {
                    rc.transferPaint(home, amt);
                    shouldGoHome = false;
                    refuelWaitTurns = 0;
                }
            }
        } else if (homeState != 2) {
            explore(rc);
        }
    }

    // ================================================================
    //                    EXPLORE (random map target)
    // ================================================================
    static void explore(RobotController rc) throws GameActionException {
        // ally repulsion: if too many allies nearby, move away from their center
        if (rc.isMovementReady()) {
            RobotInfo[] allies = rc.senseNearbyRobots(8, rc.getTeam());
            int mobileAllies = 0;
            int ax = 0, ay = 0;
            for (RobotInfo a : allies) {
                if (!a.type.isTowerType()) {
                    mobileAllies++;
                    ax += a.location.x;
                    ay += a.location.y;
                }
            }
            if (mobileAllies >= 4) {
                // too crowded, move away from ally center of mass
                ax /= mobileAllies;
                ay /= mobileAllies;
                MapLocation allyCOM = new MapLocation(ax, ay);
                MapLocation myloc = rc.getLocation();
                // move in opposite direction from ally center
                int dx = myloc.x - allyCOM.x;
                int dy = myloc.y - allyCOM.y;
                // pick a far target away from the crowd
                int mapW = rc.getMapWidth();
                int mapH = rc.getMapHeight();
                int tx = Math.max(0, Math.min(mapW - 1, myloc.x + dx * 5));
                int ty = Math.max(0, Math.min(mapH - 1, myloc.y + dy * 5));
                exploreTarget = new MapLocation(tx, ty);
                moveToward(rc, exploreTarget);
                return;
            }
        }

        if (exploreTarget == null || rc.getLocation().distanceSquaredTo(exploreTarget) <= 5) {
            exploreTarget = new MapLocation(
                rng.nextInt(rc.getMapWidth()),
                rng.nextInt(rc.getMapHeight())
            );
        }
        moveToward(rc, exploreTarget);
    }

    // ================================================================
    //                    MOVEMENT: moveToward (greedy + bug)
    // ================================================================
    static boolean bugFollowing = false;
    static Direction bugDir = null;
    static int bugStartDist = Integer.MAX_VALUE;
    static int bugTurns = 0;
    static MapLocation lastMoveTarget = null;

    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        if (rc.getLocation().equals(target)) return;

        // reset bug state when target changes
        if (lastMoveTarget == null || !lastMoveTarget.equals(target)) {
            bugFollowing = false;
            lastMoveTarget = target;
        }

        int curDist = rc.getLocation().distanceSquaredTo(target);

        // if bug following and we found a better spot than when we started, stop bug
        if (bugFollowing && curDist < bugStartDist) {
            bugFollowing = false;
        }
        // bug timeout
        if (bugFollowing && bugTurns > 8) {
            bugFollowing = false;
        }

        if (!bugFollowing) {
            // greedy: pick best neighbor, with paint-awareness tiebreaking
            Direction bestDir = null;
            int bestDist = curDist;
            int bestPaintScore = Integer.MIN_VALUE;
            for (Direction dir : directions) {
                if (!rc.canMove(dir)) continue;
                MapLocation next = rc.getLocation().add(dir);
                int dist = next.distanceSquaredTo(target);
                // paint scoring: ally paint=2, empty=1, enemy=-1
                int paintScore = 0;
                if (rc.canSenseLocation(next)) {
                    MapInfo mi = rc.senseMapInfo(next);
                    if (mi.getPaint().isAlly()) paintScore = 2;
                    else if (mi.getPaint() == PaintType.EMPTY) paintScore = 1;
                    else paintScore = -1; // enemy paint drains us
                }
                if (dist < bestDist || (dist == bestDist && paintScore > bestPaintScore)) {
                    bestDist = dist;
                    bestDir = dir;
                    bestPaintScore = paintScore;
                }
            }
            if (bestDir != null) {
                rc.move(bestDir);
                return;
            }
            // greedy failed, start bug pathfinding
            bugFollowing = true;
            bugDir = rc.getLocation().directionTo(target);
            bugStartDist = curDist;
            bugTurns = 0;
        }

        // bug: try rotating right from the blocked direction to find a way around
        if (bugFollowing) {
            bugTurns++;
            Direction dir = bugDir;
            for (int i = 0; i < 8; i++) {
                if (rc.canMove(dir)) {
                    rc.move(dir);
                    // next time, try going left of the wall we just passed
                    bugDir = dir.rotateLeft().rotateLeft();
                    return;
                }
                dir = dir.rotateRight();
            }
            // completely stuck, reset bug
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

    static void doTowerBuild(RobotController rc, MapLocation ruin) throws GameActionException {
        ruinBuildTurns++;
        // stuck building for too long, give up
        if (ruinBuildTurns > 12) {
            targetRuin = null;
            ruinBuildTurns = 0;
            return;
        }

        if (rc.canSenseLocation(ruin)) {
            RobotInfo occupant = rc.senseRobotAtLocation(ruin);
            if (occupant != null && occupant.getType().isTowerType()) {
                targetRuin = null;
                ruinBuildTurns = 0;
                return;
            }
        }

        int distToRuin = rc.getLocation().distanceSquaredTo(ruin);

        if (distToRuin > 8) {
            moveToward(rc, ruin);
            return;
        }

        // pick tower type by counting matching tiles
        UnitType towerType = getTowerToBuild(rc, ruin);

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

        // enemy paint blocks us
        if (hasEnemyPaint && tileToPaint == null) {
            enemyRuinToReport = ruin;
            shouldGoHome = true;
            reachedHome = false;
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
            targetRuin = null;
            return;
        }

        if (distToRuin > 2) {
            moveToward(rc, ruin);
        }
    }

    // count matching tiles to decide tower type (from mopper_and_srp Globals)
    static UnitType getTowerToBuild(RobotController rc, MapLocation loc) throws GameActionException {
        int pt = 0, mt = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (dx == 0 && dy == 0) continue;
                MapLocation tmp = new MapLocation(loc.x + dx, loc.y + dy);
                if (!rc.canSenseLocation(tmp)) continue;
                MapInfo mi = rc.senseMapInfo(tmp);
                if (!mi.getPaint().isAlly()) continue;
                boolean isSecondary = (mi.getPaint() == PaintType.ALLY_SECONDARY);
                if (isSecondary == paintTowerPat[2 + dx][2 + dy]) pt++;
                if (isSecondary == moneyTowerPat[2 + dx][2 + dy]) mt++;
            }
        }
        // big difference -> pick the matching one
        if (mt - pt >= 5) return UnitType.LEVEL_ONE_MONEY_TOWER;
        if (pt - mt >= 5) return UnitType.LEVEL_ONE_PAINT_TOWER;

        // early game build more money towers
        int sz = Math.max(rc.getMapWidth(), rc.getMapHeight());
        int firstPaint = (sz <= 35) ? 3 : (sz <= 50) ? 4 : (sz <= 55) ? 5 : 7;
        if (rc.getMoney() < 2000 && rc.getNumberTowers() != firstPaint)
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        return UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    static MapLocation findNearestUnbuiltRuin(RobotController rc) throws GameActionException {
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        MapLocation closest = null;
        int closestDist = Integer.MAX_VALUE;
        for (MapLocation ruin : ruins) {
            RobotInfo occupant = rc.senseRobotAtLocation(ruin);
            if (occupant != null && occupant.getType().isTowerType()) continue;

            // skip ruins with too many allies nearby (anti-crowding)
            RobotInfo[] nearRuin = rc.senseNearbyRobots(ruin, 8, rc.getTeam());
            int soldierCount = 0;
            for (RobotInfo ally : nearRuin) {
                if (ally.type == UnitType.SOLDIER && ally.ID != rc.getID()) {
                    soldierCount++;
                }
            }
            if (soldierCount >= 2) continue;

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
        return resourcePat[2 + (loc.x - center.x)][2 + (loc.y - center.y)];
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

    // try to start an SRP at current location
    static void tryStartSRP(RobotController rc) throws GameActionException {
        if (markedResource != null) return;
        if (!(rc.getNumberTowers() > 2 || rc.getRoundNum() > 100)) return;

        MapLocation myloc = rc.getLocation();
        MapInfo[] near = rc.senseNearbyMapInfos();

        // check 5x5 area is clear (no walls, enemy paint, ruins, existing SRP centers)
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                MapLocation tmp = new MapLocation(myloc.x + i, myloc.y + j);
                if (!rc.canSenseLocation(tmp)) return;
                MapInfo mi = rc.senseMapInfo(tmp);
                if (!mi.isPassable()) return;
                if (mi.getPaint().isEnemy()) return;
                if (mi.isResourcePatternCenter()) return;
                RobotInfo r = rc.senseRobotAtLocation(tmp);
                if (r != null && r.getType().isTowerType()) return;
            }
        }

        // check no overlap with existing marked SRP
        for (MapInfo tile : near) {
            if (tile.getMark() == PaintType.ALLY_PRIMARY) {
                MapLocation markLoc = tile.getMapLocation();
                // check if our 5x5 overlaps with that mark's 5x5
                if (Math.abs(markLoc.x - myloc.x) <= 4 && Math.abs(markLoc.y - myloc.y) <= 4) {
                    return;
                }
            }
        }

        if (rc.canMark(myloc)) {
            rc.mark(myloc, false);
            markedResource = myloc;
        }
    }

    // paint the SRP pattern
    static void makeResourcePatch(RobotController rc) throws GameActionException {
        MapLocation myloc = rc.getLocation();

        if (!myloc.equals(markedResource)) {
            moveToward(rc, markedResource);
        }

        MapLocation goal = null;
        int bestDist = Integer.MAX_VALUE;
        boolean secondCol = false;

        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                MapLocation tmp = new MapLocation(markedResource.x + i, markedResource.y + j);
                if (!rc.canSenseLocation(tmp)) continue;
                MapInfo mi = rc.senseMapInfo(tmp);
                if (!mi.isPassable()) continue;
                if (mi.getPaint().isEnemy()) {
                    markedResource = null;
                    return;
                }
                boolean wantSecondary = shouldUseSecond(tmp, markedResource);
                if (mi.getPaint().isAlly()) {
                    if (wantSecondary == (mi.getPaint() == PaintType.ALLY_SECONDARY)) {
                        continue; // already correct
                    }
                }
                int d = rc.getLocation().distanceSquaredTo(tmp);
                if (d < bestDist) {
                    bestDist = d;
                    goal = tmp;
                    secondCol = wantSecondary;
                }
            }
        }

        if (goal != null) {
            if (rc.canAttack(goal)) {
                rc.attack(goal, secondCol);
            }
            if (rc.canCompleteResourcePattern(markedResource)) {
                rc.completeResourcePattern(markedResource);
                markedResource = null;
            }
        } else {
            if (rc.canCompleteResourcePattern(markedResource)) {
                rc.completeResourcePattern(markedResource);
            }
            markedResource = null;
        }
    }

    // complete any resource patterns nearby
    static void checkCompleteResourcePatterns(RobotController rc) throws GameActionException {
        MapLocation myloc = rc.getLocation();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation tmp = new MapLocation(myloc.x + dx, myloc.y + dy);
                if (rc.canSenseLocation(tmp)) {
                    MapInfo mi = rc.senseMapInfo(tmp);
                    if (mi.getMark() == PaintType.ALLY_PRIMARY) {
                        if (rc.canCompleteResourcePattern(tmp)) {
                            rc.completeResourcePattern(tmp);
                            return;
                        }
                    }
                }
            }
        }
    }

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

        // tile scoring: enemy=-2 (best), empty=-1, ally=0
        int bestDist = Integer.MAX_VALUE;
        int bestScore = -10000;
        Direction bestDir = null;

        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            MapLocation nloc = rc.getLocation().add(dir);
            MapInfo mi = rc.senseMapInfo(nloc);
            int score;
            PaintType p = mi.getPaint();
            if (p.isEnemy()) score = -2;
            else if (p == PaintType.EMPTY) score = -1;
            else score = 0;

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

    // ================================================================
    //                  SHARED PAINT HELPERS
    // ================================================================
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
}
