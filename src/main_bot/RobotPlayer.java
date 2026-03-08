package main_bot;

import battlecode.common.*;
import java.util.Random;

/**
 * Main Bot: "Role-Specialized Maximum Coverage" Greedy Strategy
 *
 * Game phases: EARLY (0-500), MID (500-1000), LATE (1000+)
 *
 * Tower spawning (counter-based cycles):
 *   Early : cycle [soldier, soldier, mopper] (70/30)
 *   Mid   : cycle [soldier, soldier, splasher] (2:1)
 *   Late  : cycle [soldier, splasher, splasher, mopper, mopper, mopper]
 *
 * Tower type ratio: 1:2 (money:paint) via deterministic (x+y) % 3
 *
 * Soldier : Ruin hunter & tower builder. No painting unless building tower pattern
 *           or late game. Only refuels at paint towers.
 * Splasher: Area expander. Greedy toward densest blank clusters. Splashes everything.
 * Mopper  : Ruin cleaner. Finds ruins with enemy paint and mops them clean.
 *           Transfers paint between towers (paint → money). Refuels at any tower.
 */
public class RobotPlayer {

    static int turnCount = 0;
    static final Random rng = new Random(6147);

    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    static final Direction[] cardinals = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
    };

    // Game phase threshold
    static final int EARLY_END = 0;

    // Per-robot persistent state
    static Direction exploreDir = null;
    static MapLocation knownPaintTower = null;
    static MapLocation targetRuin = null;
    static int refuelThreshold = 60; // will be set to max(mapW, mapH)

    // Tower persistent state
    static int towerSpawnCount = 0;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        refuelThreshold = Math.max(rc.getMapWidth(), rc.getMapHeight());

        while (true) {
            turnCount += 1;
            try {
                switch (rc.getType()) {
                    case SOLDIER:  runSoldier(rc);  break;
                    case SPLASHER: runSplasher(rc); break;
                    case MOPPER:   runMopper(rc);   break;
                    default:       runTower(rc);    break;
                }
            } catch (GameActionException e) {
                System.out.println("GameActionException");
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
    //  TOWER LOGIC
    // ================================================================

    public static void runTower(RobotController rc) throws GameActionException {
        towerAttack(rc);
        towerSpawn(rc);
    }

    static void towerAttack(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemies.length == 0 || !rc.isActionReady()) return;

        // Greedy: attack the nearest enemy
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

    /**
     * Phase-based spawning using counter cycles:
     *   Early (0-500) : 100% soldiers
     *   Late  (500+)  : cycle [soldier, splasher] (50:50)
     *
     * Won't spawn if tower paint < 500 (save reserves).
     * Won't fallback — waits if can't afford.
     */
    static void towerSpawn(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        if (rc.getPaint() < 500) return;

        int round = rc.getRoundNum();
        UnitType toBuild;

        if (round < EARLY_END) {
            toBuild = UnitType.SOLDIER;
        } else {
            // Late: cycle [soldier, splasher] (50:50)
            int idx = towerSpawnCount % 2;
            if (idx == 0) toBuild = UnitType.SOLDIER;
            else toBuild = UnitType.SPLASHER;
        }

        // Check team chips affordability — no fallback, just wait
        int chipCost;
        if (toBuild == UnitType.SPLASHER) chipCost = 400;
        else chipCost = 250;
        if (rc.getChips() < chipCost) {
            return;
        }

        for (Direction dir : directions) {
            MapLocation spawnLoc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(toBuild, spawnLoc)) {
                rc.buildRobot(toBuild, spawnLoc);
                towerSpawnCount++;
                return;
            }
        }
    }

    // ================================================================
    //  SOLDIER LOGIC — Ruin Hunter & Tower Builder
    // ================================================================

    public static void runSoldier(RobotController rc) throws GameActionException {
        rememberNearestPaintTower(rc);

        // Refuel if paint is low — only at paint towers
        // if (rc.getPaint() < refuelThreshold) {
        //     if (tryWithdrawPaint(rc)) {
        //         // Refueled, continue
        //     } else {
        //         seekRefuelTower(rc);
        //         return;
        //     }
        // }

        // Priority 1: Build towers at ruins
        if (tryBuildTower(rc)) {
            return;
        }

        // Priority 2: Attack enemy towers
        if (tryAttackEnemyTower(rc)) {
            return;
        }

        // Explore to find more ruins
        if (targetRuin != null && rc.canSenseLocation(targetRuin)) {
            RobotInfo occupant = rc.senseRobotAtLocation(targetRuin);
            if (occupant != null) {
                targetRuin = null;
            }
        }

        if (targetRuin == null) {
            targetRuin = findNearestUnbuiltRuin(rc);
        }

        if (targetRuin != null) {
            moveToward(rc, targetRuin);
        } else {
            exploreMove(rc);
        }

        // Late game: paint tiles while walking
        if (rc.getRoundNum() >= EARLY_END) {
            paintCurrentTile(rc);
        }
    }

    /**
     * Find and build a tower at the nearest unbuilt ruin.
     * Tower type ratio 1:2 (money:paint) via (x+y) % 3.
     */
    static boolean tryBuildTower(RobotController rc) throws GameActionException {
        MapLocation ruinLoc = findNearestUnbuiltRuin(rc);
        if (ruinLoc == null) return false;

        int distToRuin = rc.getLocation().distanceSquaredTo(ruinLoc);

        if (distToRuin > 8) {
            targetRuin = ruinLoc;
            moveToward(rc, ruinLoc);
            return false;
        }

        // 1:2 money:paint ratio — (x+y) % 3 == 0 → money, else paint
        UnitType towerType;
        if ((ruinLoc.x + ruinLoc.y) % 3 == 0) {
            towerType = UnitType.LEVEL_ONE_MONEY_TOWER;
        } else {
            towerType = UnitType.LEVEL_ONE_PAINT_TOWER;
        }

        if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
            rc.markTowerPattern(towerType, ruinLoc);
        }

        // Find nearest pattern tile that needs painting
        // Skip enemy-painted tiles — soldier can't overwrite enemy paint
        MapLocation tileToPaint = null;
        int nearestPaintDist = Integer.MAX_VALUE;
        boolean useSecondary = false;
        boolean hasEnemyPaint = false;

        for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (patternTile.getMark() == PaintType.EMPTY) continue;
            if (patternTile.getPaint().isEnemy()) {
                hasEnemyPaint = true;
                continue; // Soldier can't paint over this
            }
            if (patternTile.getMark() == patternTile.getPaint()) continue;
            MapLocation loc = patternTile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < nearestPaintDist) {
                nearestPaintDist = dist;
                tileToPaint = loc;
                useSecondary = patternTile.getMark() == PaintType.ALLY_SECONDARY;
            }
        }

        // If ruin has enemy paint and no paintable tiles left, skip this ruin
        if (hasEnemyPaint && tileToPaint == null) {
            return false;
        }

        if (tileToPaint != null) {
            if (!rc.canAttack(tileToPaint)) {
                moveToward(rc, tileToPaint);
            }
            if (rc.canAttack(tileToPaint)) {
                rc.attack(tileToPaint, useSecondary);
            }
        } else {
            if (distToRuin > 2) {
                moveToward(rc, ruinLoc);
            }
        }

        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
            targetRuin = null;
        }

        return true;
    }

    static boolean tryAttackEnemyTower(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        RobotInfo nearestTower = null;
        int nearestDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : enemies) {
            if (!enemy.type.isTowerType()) continue;
            int dist = rc.getLocation().distanceSquaredTo(enemy.location);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestTower = enemy;
            }
        }
        if (nearestTower == null) return false;

        if (!rc.canAttack(nearestTower.location)) {
            moveToward(rc, nearestTower.location);
        }
        if (rc.canAttack(nearestTower.location)) {
            rc.attack(nearestTower.location);
        }
        return true;
    }

    static MapLocation findNearestUnbuiltRuin(RobotController rc) throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        MapLocation bestRuin = null;
        int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : nearby) {
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            RobotInfo occupant = rc.senseRobotAtLocation(ruinLoc);
            if (occupant != null) continue;
            int dist = rc.getLocation().distanceSquaredTo(ruinLoc);
            if (dist < bestDist) {
                bestDist = dist;
                bestRuin = ruinLoc;
            }
        }
        return bestRuin;
    }

    // ================================================================
    //  SPLASHER LOGIC — Area Expander
    // ================================================================

    public static void runSplasher(RobotController rc) throws GameActionException {
        rememberNearestPaintTower(rc);

        // if (rc.getPaint() < refuelThreshold) {
        //     if (!tryWithdrawPaint(rc)) {
        //         seekRefuelTower(rc);
        //         return;
        //     }
        // }

        boolean splashed = trySplashBestTarget(rc);

        Direction bestDir = greedySplasherDirection(rc);
        if (bestDir != null && rc.canMove(bestDir)) {
            rc.move(bestDir);
        }

        if (!splashed) {
            trySplashBestTarget(rc);
        }
    }

    static Direction greedySplasherDirection(RobotController rc) throws GameActionException {
        Direction bestDir = null;
        int bestScore = -1;

        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            MapLocation dest = rc.getLocation().add(dir);
            int score = countNonAllyTilesAround(rc, dest);
            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestScore <= 0) {
            return getExploreDirection(rc);
        }
        return bestDir;
    }

    /**
     * Splash the position that covers the most non-ally tiles.
     * Always splashes if there's at least 1 tile to paint, prioritizes densest.
     */
    static boolean trySplashBestTarget(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;

        MapLocation bestTarget = null;
        int bestScore = 0;

        MapInfo[] candidates = rc.senseNearbyMapInfos(rc.getLocation(), 4);
        for (MapInfo tile : candidates) {
            MapLocation loc = tile.getMapLocation();
            if (!rc.canAttack(loc)) continue;

            int score = 0;
            MapInfo[] splashArea = rc.senseNearbyMapInfos(loc, 4);
            for (MapInfo s : splashArea) {
                if (!s.isPassable()) continue;
                PaintType p = s.getPaint();
                if (p == PaintType.EMPTY) score += 1;
                else if (p.isEnemy()) score += 2;
            }
            if (score > bestScore) {
                bestScore = score;
                bestTarget = loc;
            }
        }

        if (bestTarget != null && bestScore >= 1 && rc.canAttack(bestTarget)) {
            rc.attack(bestTarget);
            return true;
        }
        return false;
    }

    // ================================================================
    //  MOPPER LOGIC — Enemy Paint Hunter
    // ================================================================

    /**
     * Mopper is simple: find enemy paint and remove it.
     *  1. Refuel at any tower when low
     *  2. Mop swing at nearby enemy robots
     *  3. Mop nearest enemy-painted tile
     *  4. Move toward direction with most enemy paint
     *  5. Explore if no enemy paint visible
     */
    public static void runMopper(RobotController rc) throws GameActionException {
        rememberNearestTower(rc);

        // Refuel at any tower when low on paint
        if (rc.getPaint() < refuelThreshold) {
            if (!tryWithdrawPaint(rc)) {
                seekRefuelTower(rc);
                return;
            }
        }

        // Mop swing at enemy robots if possible
        tryMopSwing(rc);

        // Mop nearest enemy-painted tile
        tryMopSingle(rc);

        // Move toward direction with most enemy paint
        Direction moveDir = greedyMopperDirection(rc);
        if (moveDir != null && rc.canMove(moveDir)) {
            rc.move(moveDir);
        }

        // After moving, try to mop again
        tryMopSingle(rc);
    }

    /**
     * Greedy: move toward the direction with the most enemy-painted tiles.
     */
    static Direction greedyMopperDirection(RobotController rc) throws GameActionException {
        Direction bestDir = null;
        int bestScore = -1;

        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            MapLocation dest = rc.getLocation().add(dir);

            int score = 0;
            MapInfo[] tiles = rc.senseNearbyMapInfos(dest, 8);
            for (MapInfo tile : tiles) {
                if (tile.getPaint().isEnemy()) score += 2;
            }
            // Also attracted to enemy robots (steal paint)
            RobotInfo[] enemies = rc.senseNearbyRobots(dest, 8, rc.getTeam().opponent());
            score += enemies.length * 3;

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestDir == null || bestScore <= 0) {
            return getExploreDirection(rc);
        }
        return bestDir;
    }

    /**
     * Mop swing toward the direction with the most enemies.
     */
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

        // Only swing if we actually hit enemies — don't waste cooldown
        if (bestSwing != null && bestHits > 0) {
            rc.mopSwing(bestSwing);
            return true;
        }
        return false;
    }

    static int countEnemiesInSwingDir(RobotController rc, Direction dir) throws GameActionException {
        int count = 0;
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation myLoc = rc.getLocation();
        MapLocation step1 = myLoc.add(dir);
        MapLocation step2 = step1.add(dir);

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
    //  SHARED UTILITY METHODS
    // ================================================================

    static boolean isPaintTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_PAINT_TOWER
            || type == UnitType.LEVEL_TWO_PAINT_TOWER
            || type == UnitType.LEVEL_THREE_PAINT_TOWER;
    }

    /**
     * Remember nearest visible ally paint tower. Used by soldiers.
     */
    static void rememberNearestPaintTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation bestTower = null;
        int bestDist = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            if (!isPaintTower(ally.type)) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.location);
            if (dist < bestDist) {
                bestDist = dist;
                bestTower = ally.location;
            }
        }

        if (bestTower != null) {
            knownPaintTower = bestTower;
        }
    }

    /**
     * Remember nearest visible ally tower (any type). Used by splashers/moppers.
     */
    static void rememberNearestTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation bestTower = null;
        int bestDist = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            if (!ally.type.isTowerType()) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.location);
            if (dist < bestDist) {
                bestDist = dist;
                bestTower = ally.location;
            }
        }

        if (bestTower != null) {
            knownPaintTower = bestTower;
        }
    }

    /**
     * Withdraw paint from a nearby tower.
     * Soldiers: only from paint towers.
     * Others: from any tower.
     */
    static boolean tryWithdrawPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;

        boolean paintTowerOnly = (rc.getType() == UnitType.SOLDIER || rc.getType() == UnitType.SPLASHER);
        RobotInfo[] nearby = rc.senseNearbyRobots(2, rc.getTeam());
        for (RobotInfo ally : nearby) {
            if (!ally.type.isTowerType()) continue;
            if (paintTowerOnly && !isPaintTower(ally.type)) continue;
            if (ally.paintAmount > 0) {
                int needed = rc.getType().paintCapacity - rc.getPaint();
                int canTake = Math.min(needed, ally.paintAmount);
                if (canTake > 0 && rc.canTransferPaint(ally.location, -canTake)) {
                    rc.transferPaint(ally.location, -canTake);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Move toward nearest known tower for refueling.
     * Soldiers and splashers: only paint towers. Moppers: any tower.
     */
    static void seekRefuelTower(RobotController rc) throws GameActionException {
        boolean paintTowerOnly = (rc.getType() == UnitType.SOLDIER || rc.getType() == UnitType.SPLASHER);

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation nearestTower = null;
        int nearestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            if (!ally.type.isTowerType()) continue;
            if (paintTowerOnly && !isPaintTower(ally.type)) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.location);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestTower = ally.location;
            }
        }

        if (nearestTower != null) {
            knownPaintTower = nearestTower;
            moveToward(rc, nearestTower);
        } else if (knownPaintTower != null) {
            moveToward(rc, knownPaintTower);
        } else {
            MapLocation center = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
            moveToward(rc, center);
        }
    }

    /**
     * Paint the current tile if it's empty/neutral.
     * Ignores enemy paint — soldier focuses on neutral tiles only.
     * Still respects tower pattern marks.
     */
    static void paintCurrentTile(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        PaintType mark = currentTile.getMark();
        PaintType paint = currentTile.getPaint();

        if (mark != PaintType.EMPTY) {
            // Tower pattern tile — paint with correct color regardless
            if (mark != paint && rc.canAttack(rc.getLocation())) {
                boolean useSecondary = mark == PaintType.ALLY_SECONDARY;
                rc.attack(rc.getLocation(), useSecondary);
            }
        } else {
            // Only paint empty tiles, ignore enemy paint
            if (paint == PaintType.EMPTY && rc.canAttack(rc.getLocation())) {
                rc.attack(rc.getLocation());
            }
        }
    }

    static int countNonAllyTilesAround(RobotController rc, MapLocation center) throws GameActionException {
        int count = 0;
        MapInfo[] tiles = rc.senseNearbyMapInfos(center, 8);
        for (MapInfo tile : tiles) {
            if (!tile.isPassable()) continue;
            PaintType p = tile.getPaint();
            if (p == PaintType.EMPTY || p.isEnemy()) count++;
        }
        return count;
    }

    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        if (rc.getLocation().equals(target)) return;

        Direction dir = rc.getLocation().directionTo(target);

        if (rc.canMove(dir)) {
            rc.move(dir);
        } else if (rc.canMove(dir.rotateLeft())) {
            rc.move(dir.rotateLeft());
        } else if (rc.canMove(dir.rotateRight())) {
            rc.move(dir.rotateRight());
        } else if (rc.canMove(dir.rotateLeft().rotateLeft())) {
            rc.move(dir.rotateLeft().rotateLeft());
        } else if (rc.canMove(dir.rotateRight().rotateRight())) {
            rc.move(dir.rotateRight().rotateRight());
        }
    }

    /**
     * Get explore direction by moving AWAY from visible allies.
     * This spreads bots out so they cover more of the map.
     * Falls back to persistent random direction if no allies visible.
     */
    static Direction getExploreDirection(RobotController rc) throws GameActionException {
        // Calculate average position of nearby ally robots
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        if (allies.length > 0) {
            int sumX = 0, sumY = 0, count = 0;
            for (RobotInfo ally : allies) {
                if (!ally.type.isTowerType()) { // Only count mobile robots
                    sumX += ally.location.x;
                    sumY += ally.location.y;
                    count++;
                }
            }
            if (count > 0) {
                MapLocation allyCenter = new MapLocation(sumX / count, sumY / count);
                // Move in opposite direction from ally center
                Direction awayFromAllies = allyCenter.directionTo(rc.getLocation());
                if (rc.canMove(awayFromAllies)) return awayFromAllies;
                if (rc.canMove(awayFromAllies.rotateLeft())) return awayFromAllies.rotateLeft();
                if (rc.canMove(awayFromAllies.rotateRight())) return awayFromAllies.rotateRight();
            }
        }

        // Fallback: persistent random direction
        if (exploreDir == null || !rc.canMove(exploreDir)) {
            exploreDir = directions[rng.nextInt(directions.length)];
        }
        for (int i = 0; i < 8; i++) {
            if (rc.canMove(exploreDir)) return exploreDir;
            exploreDir = exploreDir.rotateRight();
        }
        return null;
    }

    static void exploreMove(RobotController rc) throws GameActionException {
        Direction dir = getExploreDirection(rc);
        if (dir != null && rc.canMove(dir)) {
            rc.move(dir);
        }
    }
}
