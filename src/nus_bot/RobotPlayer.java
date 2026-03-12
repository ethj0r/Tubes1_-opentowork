package nus_bot;

import battlecode.common.*;

import java.util.Random;

public class RobotPlayer {

    // ==================== GLOBAL ====================
    static int turnCount = 0;
    static final Random rng = new Random(6147);
    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };
    static final Direction[] cardinals = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
    };

    // ==================== MESSAGE PROTOCOL ====================
    // Format: (msgType << 12) | (x << 6) | y
    static final int MSG_ENEMY_RUIN = 1;

    // ==================== TOWER STATE ====================
    static int botSpawnedCount = 0;
    static boolean mopperRequested = false;
    static MapLocation requestedMopLoc = null;
    static int lastSpawnRound = -10;
    static final int SPAWN_COOLDOWN = 3; // rounds between spawns
    static final int TOWER_PAINT_RESERVE = 200; // don't let bots drain below this

    // ==================== SOLDIER MOVEMENT STATE ====================
    static Direction currentDir = null;
    static int stepsInCurrentDir = 0;
    static boolean wallFollowing = false;
    static Direction wallOrigDir = null;
    static int wallFollowSteps = 0;
    static Direction prevForcedDir = null;

    // ==================== SOLDIER RUIN STATE ====================
    static MapLocation targetRuin = null;
    static MapLocation knownPaintTower = null;
    static boolean goingToRefuel = false;
    static int refuelWaitTurns = 0; // give up refueling after too many turns
    static MapLocation enemyRuinToReport = null; // ruin with enemy paint, report when near tower

    // ==================== MOPPER STATE ====================
    static MapLocation mopTarget = null;

    // ==================== CONSTANTS ====================
    static final int MAX_STRAIGHT_STEPS = 15;
    static final int MAX_WALL_STEPS = 12;
    static final int EDGE_MARGIN = 3;
    static final int UPGRADE_L2_ROUND = 500;
    static final int UPGRADE_L3_ROUND = 1000;

    // ================================================================
    //                          MAIN LOOP
    // ================================================================
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
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
                System.out.println("Exception");
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
                }
            }
        }
    }

    /**
     * Spawn logic:
     *   - If a soldier requested a mopper, spawn mopper next
     *   - Otherwise: cycle [soldier, soldier, soldier, mopper] (75/25)
     */
    static void towerSpawn(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;

        int round = rc.getRoundNum();

        // Spawn cooldown: wait between spawns (unless mopper urgently requested)
        if (!mopperRequested && round - lastSpawnRound < SPAWN_COOLDOWN) return;

        UnitType toBuild;
        if (mopperRequested) {
            // Priority: mopper request from soldier (skip cooldown)
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

    /**
     * Forward mop target location to nearby moppers so they know where to go.
     */
    static void towerForwardMessages(RobotController rc) throws GameActionException {
        if (requestedMopLoc == null) return;
        RobotInfo[] nearby = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : nearby) {
            if (ally.type == UnitType.MOPPER) {
                int msg = encodeLocation(MSG_ENEMY_RUIN, requestedMopLoc);
                if (rc.canSendMessage(ally.location, msg)) {
                    rc.sendMessage(ally.location, msg);
                }
            }
        }
    }

    // ================================================================
    //                           SOLDIER
    // ================================================================
    public static void runSoldier(RobotController rc) throws GameActionException {
        rememberNearestPaintTower(rc);

        // Refuel loop: go to paint tower, withdraw, then return to ruin
        if (goingToRefuel) {
            refuelWaitTurns++;
            // Give up after 10 turns — don't sit around dying
            if (refuelWaitTurns > 10) {
                goingToRefuel = false;
                refuelWaitTurns = 0;
            } else {
                // When near a tower, report enemy ruin if we have one
                if (enemyRuinToReport != null) {
                    if (sendEnemyRuinMessage(rc, enemyRuinToReport)) {
                        enemyRuinToReport = null;
                    }
                }
                if (tryWithdrawPaint(rc)) {
                    goingToRefuel = false;
                    refuelWaitTurns = 0;
                    return;
                }
                seekPaintTower(rc);
                return;
            }
        }

        // Low paint (25%) — fallback to refuel
        if (rc.getPaint() < rc.getType().paintCapacity / 4) {
            if (!tryWithdrawPaint(rc)) {
                goingToRefuel = true;
                seekPaintTower(rc);
                return;
            }
        }

        // Priority 1: Build towers at ruins
        if (tryBuildTower(rc)) return;

        // Priority 2: Upgrade nearby towers
        tryUpgradeTower(rc);

        // Priority 3: Help complete nearby tower patterns
        if (tryHelpNearbyPattern(rc)) return;

        // Priority 4: Explore
        soldierExplore(rc);
    }

    // ================================================================
    //                           MOPPER
    // ================================================================
    /**
     * Mopper behavior:
     *   1. Read messages from towers — get assigned mop target
     *   2. Mop swing at nearby enemy robots
     *   3. Mop nearest enemy-painted tile
     *   4. Move toward mop target OR toward nearest enemy paint OR explore
     */
    public static void runMopper(RobotController rc) throws GameActionException {
        rememberNearestPaintTower(rc);
        readMopperMessages(rc);

        // Low paint — fallback to refuel at any tower
        if (goingToRefuel) {
            refuelWaitTurns++;
            if (refuelWaitTurns > 10) {
                goingToRefuel = false;
                refuelWaitTurns = 0;
            } else {
                if (tryWithdrawPaintAny(rc)) {
                    goingToRefuel = false;
                    refuelWaitTurns = 0;
                    return;
                }
                seekAnyTower(rc);
                return;
            }
        }
        if (rc.getPaint() < rc.getType().paintCapacity / 2) {
            if (!tryWithdrawPaintAny(rc)) {
                goingToRefuel = true;
                seekAnyTower(rc);
                return;
            }
        }

        tryMopSwing(rc);
        tryMopSingle(rc);

        // Independently scan for nearby enemy paint (don't rely only on messages)
        if (mopTarget == null) {
            mopTarget = findNearestEnemyPaint(rc);
        }

        // Movement
        if (mopTarget != null) {
            // mopTarget is a ruin location — clean enemy paint in its 5x5 area
            if (rc.canSenseLocation(mopTarget)) {
                // Find nearest enemy paint tile around the ruin
                MapLocation dirtyTile = findDirtyTileNearRuin(rc, mopTarget);
                if (dirtyTile == null) {
                    // All clean — done with this ruin
                    mopTarget = null;
                } else {
                    // Move toward the dirty tile and mop it
                    if (rc.getLocation().distanceSquaredTo(dirtyTile) > 2) {
                        moveToward(rc, dirtyTile);
                    }
                    tryMopSingle(rc);
                }
            } else {
                // Can't see ruin yet — walk toward it
                moveToward(rc, mopTarget);
            }
        }

        if (mopTarget == null) {
            // No ruin to clean — look for any enemy paint nearby
            MapLocation enemyPaint = findNearestEnemyPaint(rc);
            if (enemyPaint != null) {
                mopperCloseMove(rc, enemyPaint);
                tryMopSingle(rc);
            } else {
                // Nothing to do — explore the map
                mopperExplore(rc);
            }
        }
    }

    /**
     * Mopper explore: pick a persistent random direction and keep going.
     * Changes direction when blocked, ensuring the mopper roams the map.
     */
    static void mopperExplore(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        if (currentDir == null || !rc.canMove(currentDir)) {
            currentDir = directions[rng.nextInt(directions.length)];
        }
        for (int i = 0; i < 8; i++) {
            if (rc.canMove(currentDir)) {
                rc.move(currentDir);
                return;
            }
            currentDir = currentDir.rotateRight();
        }
    }

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

    /**
     * Find nearest enemy-painted tile in the 5x5 area around a ruin.
     */
    static MapLocation findDirtyTileNearRuin(RobotController rc, MapLocation ruin) throws GameActionException {
        MapInfo[] area = rc.senseNearbyMapInfos(ruin, 8);
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapInfo tile : area) {
            if (!tile.getPaint().isEnemy()) continue;
            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < bestDist) {
                bestDist = dist;
                best = loc;
            }
        }
        return best;
    }

    /**
     * Scan visible tiles for nearest enemy paint, avoiding enemy tower range.
     */
    static MapLocation findNearestEnemyPaint(RobotController rc) throws GameActionException {
        // First, find enemy towers to avoid their range
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

            // Skip tiles near enemy towers (within range 20 = tower attack range)
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

    /**
     * Close-range mopper movement with tile scoring (inspired by mopper_and_srp):
     * enemy paint = +2, empty = +1, ally paint = 0.
     * Ties broken by distance to target.
     */
    static void mopperCloseMove(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;

        Direction bestDir = null;
        int bestScore = -1;
        int bestDist = Integer.MAX_VALUE;

        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            MapLocation dest = rc.getLocation().add(dir);

            // Score the tile we'd step onto
            MapInfo destInfo = rc.senseMapInfo(dest);
            int score = 0;
            PaintType p = destInfo.getPaint();
            if (p.isEnemy()) score = 2;
            else if (p == PaintType.EMPTY) score = 1;

            int dist = dest.distanceSquaredTo(target);

            // Pick highest score, break ties by closest to target
            if (score > bestScore || (score == bestScore && dist < bestDist)) {
                bestScore = score;
                bestDist = dist;
                bestDir = dir;
            }
        }

        if (bestDir != null) {
            rc.move(bestDir);
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

    static boolean tryMopSingle(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;
        MapInfo[] nearby = rc.senseNearbyMapInfos(rc.getLocation(), 2);
        for (MapInfo tile : nearby) {
            if (tile.getPaint().isEnemy() && rc.canAttack(tile.getMapLocation())) {
                rc.attack(tile.getMapLocation());
                return true;
            }
        }
        return false;
    }

    // ================================================================
    //                          SPLASHER
    // ================================================================
    public static void runSplasher(RobotController rc) throws GameActionException {
        // TODO
    }

    // ================================================================
    //                    SOLDIER: TOWER BUILDING
    // ================================================================
    static boolean tryBuildTower(RobotController rc) throws GameActionException {
        if (targetRuin == null) {
            targetRuin = findNearestUnbuiltRuin(rc);
        }
        if (targetRuin == null) return false;

        if (rc.canSenseLocation(targetRuin)) {
            RobotInfo occupant = rc.senseRobotAtLocation(targetRuin);
            if (occupant != null && occupant.getType().isTowerType()) {
                targetRuin = null;
                return false;
            }
        }

        int distToRuin = rc.getLocation().distanceSquaredTo(targetRuin);

        if (distToRuin > 8) {
            moveToward(rc, targetRuin);
            return true;
        }

        // Tower type: 1:2 money:paint
        UnitType towerType;
        if ((targetRuin.x + targetRuin.y) % 3 == 0) {
            towerType = UnitType.LEVEL_ONE_MONEY_TOWER;
        } else {
            towerType = UnitType.LEVEL_ONE_PAINT_TOWER;
        }

        if (rc.canMarkTowerPattern(towerType, targetRuin)) {
            rc.markTowerPattern(towerType, targetRuin);
        }

        // Find tile that needs painting — skip enemy paint
        MapLocation tileToPaint = null;
        int nearestDist = Integer.MAX_VALUE;
        boolean useSecondary = false;
        boolean hasEnemyPaint = false;

        for (MapInfo tile : rc.senseNearbyMapInfos(targetRuin, 8)) {
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

        // Enemy paint blocks us and nothing else to paint:
        //   1. Save ruin location to report when we reach the tower
        //   2. Go refuel at paint tower, then come back
        if (hasEnemyPaint && tileToPaint == null) {
            enemyRuinToReport = targetRuin;
            goingToRefuel = true;
            seekPaintTower(rc);
            return true;
        }

        if (tileToPaint != null) {
            if (!rc.canAttack(tileToPaint)) {
                moveToward(rc, tileToPaint);
            }
            if (rc.canAttack(tileToPaint)) {
                rc.attack(tileToPaint, useSecondary);
            }
            return true;
        }

        if (rc.canCompleteTowerPattern(towerType, targetRuin)) {
            rc.completeTowerPattern(towerType, targetRuin);
            targetRuin = null;
            return true;
        }

        if (distToRuin > 2) {
            moveToward(rc, targetRuin);
        }
        return true;
    }

    static MapLocation findNearestUnbuiltRuin(RobotController rc) throws GameActionException {
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        MapLocation closest = null;
        int closestDist = Integer.MAX_VALUE;
        for (MapLocation ruin : ruins) {
            RobotInfo occupant = rc.senseRobotAtLocation(ruin);
            if (occupant != null && occupant.getType().isTowerType()) continue;

            // Skip ruins where another soldier is already building
            boolean alreadyTaken = false;
            RobotInfo[] nearRuin = rc.senseNearbyRobots(ruin, 8, rc.getTeam());
            for (RobotInfo ally : nearRuin) {
                if (ally.type == UnitType.SOLDIER && ally.ID != rc.getID()) {
                    alreadyTaken = true;
                    break;
                }
            }
            if (alreadyTaken) continue;

            int dist = rc.getLocation().distanceSquaredTo(ruin);
            if (dist < closestDist) {
                closestDist = dist;
                closest = ruin;
            }
        }
        return closest;
    }

    /**
     * Help paint any nearby incomplete tower pattern (not just our targetRuin).
     * If we see marked tiles that need painting, paint them while passing by.
     */
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

    // ================================================================
    //                   SOLDIER: TOWER UPGRADING
    // ================================================================
    static void tryUpgradeTower(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();
        if (round < UPGRADE_L2_ROUND) return;

        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo robot : nearbyRobots) {
            if (!robot.getType().isTowerType()) continue;
            if (!robot.getType().canUpgradeType()) continue;

            int level = robot.getType().level;
            if (level == 1 && round >= UPGRADE_L2_ROUND) {
                // ok
            } else if (level == 2 && round >= UPGRADE_L3_ROUND) {
                // ok
            } else {
                continue;
            }

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
    //                    COMMUNICATION HELPERS
    // ================================================================
    static int encodeLocation(int msgType, MapLocation loc) {
        return (msgType << 12) | (loc.x << 6) | loc.y;
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
    //                    PAINT TOWER / REFUEL
    // ================================================================
    static boolean isPaintTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_PAINT_TOWER
            || type == UnitType.LEVEL_TWO_PAINT_TOWER
            || type == UnitType.LEVEL_THREE_PAINT_TOWER;
    }

    static void rememberNearestPaintTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (isPaintTower(ally.type)) {
                if (knownPaintTower == null ||
                    rc.getLocation().distanceSquaredTo(ally.location) <
                    rc.getLocation().distanceSquaredTo(knownPaintTower)) {
                    knownPaintTower = ally.location;
                }
            }
        }
    }

    static boolean tryWithdrawPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;
        RobotInfo[] nearby = rc.senseNearbyRobots(2, rc.getTeam());
        for (RobotInfo ally : nearby) {
            if (!isPaintTower(ally.type)) continue;
            // Don't drain tower below reserve — it needs paint to spawn
            int available = ally.paintAmount - TOWER_PAINT_RESERVE;
            if (available > 0) {
                int needed = rc.getType().paintCapacity - rc.getPaint();
                int canTake = Math.min(needed, available);
                if (canTake > 0 && rc.canTransferPaint(ally.location, -canTake)) {
                    rc.transferPaint(ally.location, -canTake);
                    return true;
                }
            }
        }
        return false;
    }

    static void seekPaintTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            if (!isPaintTower(ally.type)) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.location);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = ally.location;
            }
        }
        if (nearest != null) {
            knownPaintTower = nearest;
            moveToward(rc, nearest);
        } else if (knownPaintTower != null) {
            moveToward(rc, knownPaintTower);
        }
    }

    /**
     * Withdraw paint from any nearby ally tower (for mopper).
     */
    static boolean tryWithdrawPaintAny(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;
        RobotInfo[] nearby = rc.senseNearbyRobots(2, rc.getTeam());
        for (RobotInfo ally : nearby) {
            if (!ally.type.isTowerType()) continue;
            // Don't drain tower below reserve — it needs paint to spawn
            int available = ally.paintAmount - TOWER_PAINT_RESERVE;
            if (available > 0) {
                int needed = rc.getType().paintCapacity - rc.getPaint();
                int canTake = Math.min(needed, available);
                if (canTake > 0 && rc.canTransferPaint(ally.location, -canTake)) {
                    rc.transferPaint(ally.location, -canTake);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Move toward nearest ally tower (any type, for mopper refuel).
     */
    static void seekAnyTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            if (!ally.type.isTowerType()) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.location);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = ally.location;
            }
        }
        if (nearest != null) {
            moveToward(rc, nearest);
        } else if (knownPaintTower != null) {
            moveToward(rc, knownPaintTower);
        }
    }

    // ================================================================
    //                    MOVEMENT: moveToward
    // ================================================================
    /**
     * Greedy pathfinding (inspired by mopper_and_srp):
     * Check all 8 neighbors, pick the passable one closest to target.
     * Ties broken by preferring the direct direction.
     */
    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        if (rc.getLocation().equals(target)) return;

        Direction bestDir = null;
        int bestDist = rc.getLocation().distanceSquaredTo(target);

        // Check all 8 directions, pick minimum distance to target
        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            int dist = rc.getLocation().add(dir).distanceSquaredTo(target);
            if (dist < bestDist) {
                bestDist = dist;
                bestDir = dir;
            }
        }

        if (bestDir != null) {
            rc.move(bestDir);
        }
    }



    // ================================================================
    //                    SOLDIER GREEDY EXPLORE
    // ================================================================
    static void soldierExplore(RobotController rc) throws GameActionException {
        if (currentDir == null) {
            currentDir = directions[rng.nextInt(directions.length)];
            stepsInCurrentDir = 0;
        }

        if (!wallFollowing && tryEdgeAvoidance(rc)) return;

        if (stepsInCurrentDir >= MAX_STRAIGHT_STEPS) {
            if (tryForceNewDirection(rc)) return;
            stepsInCurrentDir = 0;
        }

        if (wallFollowing) {
            wallFollowSteps++;
            if (wallFollowSteps >= MAX_WALL_STEPS) {
                exitWallFollow(rc);
                return;
            }
            if (tryGap(rc)) return;
            if (tryHugWall(rc, wallOrigDir)) return;
            return;
        }

        if (rc.canMove(currentDir)) {
            rc.move(currentDir);
            stepsInCurrentDir++;
            return;
        }

        wallFollowing = true;
        wallOrigDir = currentDir;
        wallFollowSteps = 0;
        if (tryHugWall(rc, currentDir)) return;
    }

    // ======================== EXPLORE HELPERS ========================
    static boolean tryEdgeAvoidance(RobotController rc) throws GameActionException {
        int x = rc.getLocation().x;
        int y = rc.getLocation().y;
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        if (x >= EDGE_MARGIN && x < w - EDGE_MARGIN &&
            y >= EDGE_MARGIN && y < h - EDGE_MARGIN) {
            return false;
        }

        Direction toCenter = rc.getLocation().directionTo(new MapLocation(w / 2, h / 2));
        return tryMoveInOrder(rc, new Direction[]{
            toCenter, toCenter.rotateRight(), toCenter.rotateLeft()
        }, true);
    }

    static boolean tryForceNewDirection(RobotController rc) throws GameActionException {
        boolean oscillating = (prevForcedDir != null && currentDir.equals(prevForcedDir.opposite()));

        Direction[] choices;
        if (oscillating) {
            choices = new Direction[]{
                currentDir.rotateRight().rotateRight(),
                currentDir.rotateLeft().rotateLeft(),
                currentDir.rotateRight(),
                currentDir.rotateLeft(),
                currentDir.rotateRight().rotateRight().rotateRight(),
                currentDir.rotateLeft().rotateLeft().rotateLeft(),
            };
        } else {
            choices = new Direction[]{
                currentDir.opposite(),
                currentDir.opposite().rotateRight(),
                currentDir.opposite().rotateLeft(),
                currentDir.rotateRight().rotateRight(),
                currentDir.rotateLeft().rotateLeft(),
            };
        }

        boolean moved = tryMoveInOrder(rc, choices, true);
        if (moved) {
            prevForcedDir = currentDir;
        } else if (oscillating) {
            wallFollowing = true;
            wallOrigDir = currentDir.rotateRight().rotateRight();
            wallFollowSteps = 0;
            prevForcedDir = null;
            return tryHugWall(rc, wallOrigDir);
        }
        return moved;
    }

    static boolean tryGap(RobotController rc) throws GameActionException {
        Direction[] gapDirs = {
            wallOrigDir,
            wallOrigDir.rotateRight(),
            wallOrigDir.rotateLeft(),
        };
        for (Direction dir : gapDirs) {
            if (rc.canMove(dir)) {
                MapLocation beyond = rc.getLocation().add(dir).add(wallOrigDir);
                boolean gapIsReal = true;
                if (rc.onTheMap(beyond) && rc.canSenseLocation(beyond)) {
                    MapInfo info = rc.senseMapInfo(beyond);
                    if (info.isWall() || info.hasRuin()) gapIsReal = false;
                }
                if (gapIsReal) {
                    currentDir = dir;
                    wallFollowing = false;
                    stepsInCurrentDir = 0;
                    rc.move(currentDir);
                    return true;
                }
            }
        }
        return false;
    }

    static boolean tryHugWall(RobotController rc, Direction blockedDir) throws GameActionException {
        Direction[] hugOrder = {
            blockedDir.rotateRight().rotateRight(),
            blockedDir.rotateRight(),
            blockedDir.rotateRight().rotateRight().rotateRight(),
            blockedDir.rotateLeft().rotateLeft(),
            blockedDir.rotateLeft(),
            blockedDir.rotateLeft().rotateLeft().rotateLeft(),
            blockedDir.opposite(),
        };
        for (Direction dir : hugOrder) {
            if (rc.canMove(dir)) {
                rc.move(dir);
                wallOrigDir = dir.opposite();
                return true;
            }
        }
        return false;
    }

    static void exitWallFollow(RobotController rc) throws GameActionException {
        wallFollowing = false;
        boolean moved = tryMoveInOrder(rc, new Direction[]{
            wallOrigDir.opposite(),
            wallOrigDir.opposite().rotateRight(),
            wallOrigDir.opposite().rotateLeft(),
            wallOrigDir.rotateRight().rotateRight(),
            wallOrigDir.rotateLeft().rotateLeft(),
        }, true);

        if (!moved) {
            currentDir = directions[rng.nextInt(directions.length)];
        }
        stepsInCurrentDir = 0;
    }

    static boolean tryMoveInOrder(RobotController rc, Direction[] dirs, boolean updateDir)
            throws GameActionException {
        for (Direction dir : dirs) {
            if (rc.canMove(dir)) {
                if (updateDir) {
                    currentDir = dir;
                    stepsInCurrentDir = 0;
                    wallFollowing = false;
                }
                rc.move(dir);
                return true;
            }
        }
        return false;
    }
}
