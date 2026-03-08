package main_bot;

import battlecode.common.*;
import java.util.Random;

/**
 * Main Bot: "Role-Specialized Maximum Coverage" Greedy Strategy
 *
 * Game phases: EARLY (0-500), MID (500-1000), LATE (1000+)
 *
 * Tower spawning:
 *   Early : 100% soldiers (build economy via towers)
 *   Mid   : 70% soldiers, 30% splashers
 *   Late  : 30% soldiers, 40% splashers, 30% moppers
 *
 * Soldier : Ruin hunter & tower builder. No painting unless building tower pattern
 *           or late game. Builds money:paint towers in 2:3 ratio (deterministic).
 * Splasher: Area expander. Greedy toward densest blank clusters. Only splashes
 *           when coverage is worth the 50 paint cost.
 * Mopper  : Paint deliverer & defender. Steals enemy paint, removes enemy tiles,
 *           transfers paint to allies.
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

    // Game phase thresholds
    static final int EARLY_END = 500;
    static final int MID_END = 1000;

    // Per-robot persistent state
    static Direction exploreDir = null;
    static MapLocation knownPaintTower = null;
    static MapLocation targetRuin = null;
    static int refuelThreshold = 60; // will be set to max(mapW, mapH)

    // Tower persistent state: commit to a unit type until it's built
    static UnitType towerQueuedUnit = null;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        // Set refuel threshold based on map size
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
     * Phase-based spawning:
     *   Early (0-500)   : 100% soldiers
     *   Mid   (500-1000): 70% soldiers, 30% splashers
     *   Late  (1000+)   : 30% soldiers, 40% splashers, 30% moppers
     */
    static void towerSpawn(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;

        int round = rc.getRoundNum();
        UnitType toBuild;

        if (round < EARLY_END) {
            // Early: all soldiers
            toBuild = UnitType.SOLDIER;
        } else if (round < MID_END) {
            // Mid: 70% soldier, 30% splasher
            int roll = rng.nextInt(10);
            if (roll < 7) toBuild = UnitType.SOLDIER;
            else toBuild = UnitType.SPLASHER;
        } else {
            // Late: 30% soldier, 40% splasher, 30% mopper
            int roll = rng.nextInt(10);
            if (roll < 3) toBuild = UnitType.SOLDIER;
            else if (roll < 7) toBuild = UnitType.SPLASHER;
            else toBuild = UnitType.MOPPER;
        }

        // No fallback — wait and save if we can't afford the chosen unit
        // Check both paint (from tower storage) and chips (from team pool)
        int towerPaint = rc.getPaint();
        int teamChips = rc.getChips();
        int paintCost, chipCost;
        switch (toBuild) {
            case SPLASHER: paintCost = 300; chipCost = 400; break;
            case SOLDIER:  paintCost = 200; chipCost = 250; break;
            case MOPPER:   paintCost = 100; chipCost = 300; break;
            default:       paintCost = 200; chipCost = 250; break;
        }
        if (towerPaint < paintCost || teamChips < chipCost) {
            if (toBuild == UnitType.SPLASHER) {
                System.out.println("Round " + rc.getRoundNum() + " CANT AFFORD SPLASHER paint=" + towerPaint + " chips=" + teamChips);
            }
            return; // Wait and save for the intended unit
        }

        // Try all 8 directions for a valid spawn location
        for (Direction dir : directions) {
            MapLocation spawnLoc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(toBuild, spawnLoc)) {
                rc.buildRobot(toBuild, spawnLoc);
                System.out.println("Round " + rc.getRoundNum() + " spawned " + toBuild);
                return;
            }
        }
    }

    // ================================================================
    //  SOLDIER LOGIC — Ruin Hunter & Tower Builder
    // ================================================================

    public static void runSoldier(RobotController rc) throws GameActionException {
        rememberNearestTower(rc);

        // Refuel if paint is low
        if (rc.getPaint() < refuelThreshold) {
            if (tryWithdrawPaint(rc)) {
                // Refueled, continue
            } else {
                seekRefuelTower(rc);
                return;
            }
        }

        // Priority 1: Build towers at ruins
        if (tryBuildTower(rc)) {
            return;
        }

        // Priority 2: Attack enemy towers (always, not just late game)
        if (tryAttackEnemyTower(rc)) {
            return;
        }

        // Priority 3: Late game — paint blank tiles while exploring
        int round = rc.getRoundNum();

        // Explore to find more ruins
        if (targetRuin != null && rc.canSenseLocation(targetRuin)) {
            RobotInfo occupant = rc.senseRobotAtLocation(targetRuin);
            if (occupant != null) {
                targetRuin = null; // Tower already built
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
        if (round >= MID_END) {
            paintCurrentTile(rc);
        }
    }

    /**
     * Find and build a tower at the nearest unbuilt ruin.
     * Uses deterministic 2:3 money:paint ratio based on ruin coordinates.
     */
    static boolean tryBuildTower(RobotController rc) throws GameActionException {
        MapLocation ruinLoc = findNearestUnbuiltRuin(rc);
        if (ruinLoc == null) return false;

        int distToRuin = rc.getLocation().distanceSquaredTo(ruinLoc);

        // Far away — set target and walk, but don't enter build mode
        if (distToRuin > 8) {
            targetRuin = ruinLoc;
            moveToward(rc, ruinLoc);
            return false;
        }

        // Close enough to build. Decide tower type with 2:3 money:paint ratio.
        // Deterministic: (x + y) % 5 < 2 → money, else paint
        // This works without communication since all soldiers compute the same result for the same ruin.
        UnitType towerType;
        if ((ruinLoc.x + ruinLoc.y) % 5 < 2) {
            towerType = UnitType.LEVEL_ONE_MONEY_TOWER;
        } else {
            towerType = UnitType.LEVEL_ONE_PAINT_TOWER;
        }

        // Mark the tower pattern
        if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
            rc.markTowerPattern(towerType, ruinLoc);
        }

        // Find nearest pattern tile that needs painting and paint it
        MapLocation tileToPaint = null;
        int nearestPaintDist = Integer.MAX_VALUE;
        boolean useSecondary = false;

        for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (patternTile.getMark() == PaintType.EMPTY) continue;
            if (patternTile.getMark() == patternTile.getPaint()) continue;
            MapLocation loc = patternTile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < nearestPaintDist) {
                nearestPaintDist = dist;
                tileToPaint = loc;
                useSecondary = patternTile.getMark() == PaintType.ALLY_SECONDARY;
            }
        }

        if (tileToPaint != null) {
            if (!rc.canAttack(tileToPaint)) {
                moveToward(rc, tileToPaint);
            }
            if (rc.canAttack(tileToPaint)) {
                rc.attack(tileToPaint, useSecondary);
            }
        } else {
            // All tiles painted or none marked — move adjacent to ruin
            if (distToRuin > 2) {
                moveToward(rc, ruinLoc);
            }
        }

        // Complete the tower if pattern is done
        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
            targetRuin = null;
        }

        return true;
    }

    /**
     * Attack the nearest visible enemy tower. Move toward it if not in range.
     */
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
            if (occupant != null) continue; // Tower already built
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
        rememberNearestTower(rc);

        // Refuel if paint is low
        if (rc.getPaint() < refuelThreshold) {
            if (!tryWithdrawPaint(rc)) {
                seekRefuelTower(rc);
                return;
            }
        }

        // Try splash before moving
        boolean splashed = trySplashBestTarget(rc);

        // Move toward densest unpainted area
        Direction bestDir = greedySplasherDirection(rc);
        if (bestDir != null && rc.canMove(bestDir)) {
            rc.move(bestDir);
        }

        // Try splash after moving
        if (!splashed) {
            trySplashBestTarget(rc);
        }
    }

    /**
     * Greedy: pick direction leading to the most non-ally tiles.
     */
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

        // If all directions have 0 unpainted tiles, explore
        if (bestScore <= 0) {
            return getExploreDirection(rc);
        }
        return bestDir;
    }

    /**
     * Splash the position that covers the most non-ally tiles.
     * Only splashes if score >= 5 (worth the 50 paint cost — at least 5 tiles).
     */
    static boolean trySplashBestTarget(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;

        MapLocation bestTarget = null;
        int bestScore = 0;

        // Splash target must be within attack range (distance² <= 4)
        MapInfo[] candidates = rc.senseNearbyMapInfos(rc.getLocation(), 4);
        for (MapInfo tile : candidates) {
            MapLocation loc = tile.getMapLocation();
            if (!rc.canAttack(loc)) continue;

            int score = 0;
            // Score all tiles in splash radius (distance² <= 4 from target)
            MapInfo[] splashArea = rc.senseNearbyMapInfos(loc, 4);
            for (MapInfo s : splashArea) {
                if (!s.isPassable()) continue;
                PaintType p = s.getPaint();
                if (p == PaintType.EMPTY) {
                    score += 1;
                } else if (p.isEnemy()) {
                    score += 2; // Flipping enemy paint is more valuable
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestTarget = loc;
            }
        }

        // Only splash if covering at least 5 tiles worth of value
        if (bestTarget != null && bestScore >= 5 && rc.canAttack(bestTarget)) {
            rc.attack(bestTarget);
            return true;
        }
        return false;
    }

    // ================================================================
    //  MOPPER LOGIC — Paint Deliverer & Defender
    // ================================================================

    public static void runMopper(RobotController rc) throws GameActionException {
        rememberNearestTower(rc);

        // Refuel self if low on paint
        if (rc.getPaint() < refuelThreshold) {
            if (!tryWithdrawPaint(rc)) {
                seekRefuelTower(rc);
                tryMopSwing(rc); // Mop along the way
                return;
            }
        }

        // Priority 1: Transfer paint to neediest nearby ally
        tryTransferPaint(rc);

        // Priority 2: Mop swing at enemy robots
        tryMopSwing(rc);

        // Priority 3: Move toward best objective
        Direction moveDir = greedyMopperDirection(rc);
        if (moveDir != null && rc.canMove(moveDir)) {
            rc.move(moveDir);
        }

        // After moving, try actions again
        tryTransferPaint(rc);
        tryMopSingle(rc);
    }

    /**
     * Greedy mopper direction scoring:
     *  - Enemy paint tiles to mop (weight 2)
     *  - Allies with low paint to refuel (weight 5)
     *  - Enemy robots nearby to steal from (weight 3)
     */
    static Direction greedyMopperDirection(RobotController rc) throws GameActionException {
        Direction bestDir = null;
        int bestScore = -1;

        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            MapLocation dest = rc.getLocation().add(dir);
            int score = scoreMopperDirection(rc, dest);
            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        if (bestDir == null) {
            return getExploreDirection(rc);
        }
        return bestDir;
    }

    static int scoreMopperDirection(RobotController rc, MapLocation dest) throws GameActionException {
        int score = 0;

        // Score enemy-painted tiles nearby
        MapInfo[] tiles = rc.senseNearbyMapInfos(dest, 8);
        for (MapInfo tile : tiles) {
            if (tile.getPaint().isEnemy()) {
                score += 2;
            }
        }

        // Score nearby allies with low paint (main delivery job)
        RobotInfo[] allies = rc.senseNearbyRobots(dest, 8, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.type == UnitType.MOPPER) continue; // Don't chase other moppers
            int pct = (ally.paintAmount * 100) / Math.max(1, ally.type.paintCapacity);
            if (pct < 50) score += 5;
            else if (pct < 75) score += 2;
        }

        // Score enemy robots nearby (steal paint opportunity)
        RobotInfo[] enemies = rc.senseNearbyRobots(dest, 8, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            if (!enemy.type.isTowerType()) {
                score += 3; // Enemy robot = steal paint target
            }
        }

        return score;
    }

    /**
     * Transfer paint to the adjacent ally that needs it most.
     * Greedy: lowest paint percentage gets priority.
     */
    static boolean tryTransferPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;

        RobotInfo[] allies = rc.senseNearbyRobots(2, rc.getTeam());
        RobotInfo neediest = null;
        int lowestPct = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            if (ally.type == UnitType.MOPPER) continue;
            int pct = (ally.paintAmount * 100) / Math.max(1, ally.type.paintCapacity);
            if (pct < 60 && pct < lowestPct) {
                lowestPct = pct;
                neediest = ally;
            }
        }

        if (neediest != null) {
            int needed = neediest.type.paintCapacity - neediest.paintAmount;
            int canGive = Math.min(needed, rc.getPaint() - 10); // Keep 10 for self
            if (canGive > 0 && rc.canTransferPaint(neediest.location, canGive)) {
                rc.transferPaint(neediest.location, canGive);
                return true;
            }
        }
        return false;
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

        // Fallback: swing any available direction (even with 0 enemies, still removes paint)
        if (bestSwing == null) {
            for (Direction dir : cardinals) {
                if (rc.canMopSwing(dir)) {
                    bestSwing = dir;
                    break;
                }
            }
        }

        if (bestSwing != null) {
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

    /**
     * Single-target mop on an adjacent enemy-painted tile.
     */
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

    /**
     * Remember nearest visible ally tower for refueling.
     * Prefers paint towers over others.
     */
    static void rememberNearestTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation bestTower = null;
        int bestDist = Integer.MAX_VALUE;
        boolean bestIsPaint = false;

        for (RobotInfo ally : allies) {
            if (!ally.type.isTowerType()) continue;
            boolean isPaint = (ally.type == UnitType.LEVEL_ONE_PAINT_TOWER
                || ally.type == UnitType.LEVEL_TWO_PAINT_TOWER
                || ally.type == UnitType.LEVEL_THREE_PAINT_TOWER);
            int dist = rc.getLocation().distanceSquaredTo(ally.location);

            if ((isPaint && !bestIsPaint) || (isPaint == bestIsPaint && dist < bestDist)) {
                bestDist = dist;
                bestTower = ally.location;
                bestIsPaint = isPaint;
            }
        }

        if (bestTower != null) {
            knownPaintTower = bestTower;
        }
    }

    /**
     * Try to withdraw paint from a nearby ally tower (distance² <= 2).
     */
    static boolean tryWithdrawPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;

        RobotInfo[] nearby = rc.senseNearbyRobots(2, rc.getTeam());
        for (RobotInfo ally : nearby) {
            if (ally.type.isTowerType() && ally.paintAmount > 0) {
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
     * Move toward the nearest known tower for refueling.
     */
    static void seekRefuelTower(RobotController rc) throws GameActionException {
        // Scan for any visible tower
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation nearestTower = null;
        int nearestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            if (ally.type.isTowerType()) {
                int dist = rc.getLocation().distanceSquaredTo(ally.location);
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearestTower = ally.location;
                }
            }
        }

        if (nearestTower != null) {
            knownPaintTower = nearestTower;
            moveToward(rc, nearestTower);
        } else if (knownPaintTower != null) {
            moveToward(rc, knownPaintTower);
        } else {
            // No tower known — move toward map center as fallback
            MapLocation center = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
            moveToward(rc, center);
        }
    }

    /**
     * Paint the tile the robot is standing on if not already ally-painted.
     * Respects marks for tower patterns.
     */
    static void paintCurrentTile(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        PaintType mark = currentTile.getMark();
        PaintType paint = currentTile.getPaint();

        if (mark != PaintType.EMPTY) {
            if (mark != paint && rc.canAttack(rc.getLocation())) {
                boolean useSecondary = mark == PaintType.ALLY_SECONDARY;
                rc.attack(rc.getLocation(), useSecondary);
            }
        } else {
            if (!paint.isAlly() && rc.canAttack(rc.getLocation())) {
                rc.attack(rc.getLocation());
            }
        }
    }

    /**
     * Count non-ally tiles (empty + enemy) around a center point.
     */
    static int countNonAllyTilesAround(RobotController rc, MapLocation center) throws GameActionException {
        int count = 0;
        MapInfo[] tiles = rc.senseNearbyMapInfos(center, 8);
        for (MapInfo tile : tiles) {
            if (!tile.isPassable()) continue;
            PaintType p = tile.getPaint();
            if (p == PaintType.EMPTY || p.isEnemy()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Greedy pathfinding: try direct direction, then rotate left/right.
     */
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
     * Explore in a persistent random direction. Rotates when blocked.
     */
    static Direction getExploreDirection(RobotController rc) {
        if (exploreDir == null || !rc.canMove(exploreDir)) {
            exploreDir = directions[rng.nextInt(directions.length)];
        }
        for (int i = 0; i < 8; i++) {
            if (rc.canMove(exploreDir)) return exploreDir;
            exploreDir = exploreDir.rotateRight();
        }
        return null;
    }

    /**
     * Explore: move in persistent direction.
     */
    static void exploreMove(RobotController rc) throws GameActionException {
        Direction dir = getExploreDirection(rc);
        if (dir != null && rc.canMove(dir)) {
            rc.move(dir);
        }
    }
}
