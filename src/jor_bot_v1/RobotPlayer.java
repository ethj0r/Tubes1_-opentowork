package jor_bot_v1;

import battlecode.common.*;

/**
 * Core strategy (mirip dikit main_bot):
 * - 50/50 soldier/splasher from round 1
 * - Paint >= 500 threshold before spawning
 * - No refueling — units paint until death
 * - Explore by moving AWAY from allies
 * - 1:2 money:paint tower ratio via (x+y)%3
 *
 * Improvements over main_bot:
 * - Bug-nav for soldier ruin navigation
 * - SRP completion for 3x resource bonus
 * - Soldier paints enemy tiles (not just empty)
 * - Spawn toward map center
 * - Tower upgrading with spare chips
 */
public class RobotPlayer {

    static final Direction[] DIRS = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };
    static final Direction[] CARDINALS = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
    };

    // Per-robot state
    static int mapW, mapH;
    static Team myTeam, enemyTeam;
    static MapLocation spawnLoc;
    static MapLocation mapCenter;
    static MapLocation knownPaintTower;
    static Direction exploreDir;

    // Bug-nav state
    static boolean bugTracing = false;
    static Direction bugTracingDir;
    static MapLocation bugStart;
    static int bugTurns = 0;

    // Tower spawn counter
    static int towerSpawnCount = 0;

    // Soldier ruin target
    static MapLocation targetRuin;

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        mapW = rc.getMapWidth();
        mapH = rc.getMapHeight();
        myTeam = rc.getTeam();
        enemyTeam = myTeam.opponent();
        spawnLoc = rc.getLocation();
        mapCenter = new MapLocation(mapW / 2, mapH / 2);
        exploreDir = DIRS[rc.getID() % 8];

        while (true) {
            try {
                switch (rc.getType()) {
                    case SOLDIER:  runSoldier(rc);  break;
                    case MOPPER:   runMopper(rc);   break;
                    case SPLASHER: runSplasher(rc);  break;
                    default:       runTower(rc);     break;
                }
            } catch (GameActionException e) {
            } catch (Exception e) {
            } finally {
                Clock.yield();
            }
        }
    }

    //  TOWER
    static void runTower(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        UnitType myType = rc.getType();

        // Attack enemies
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, enemyTeam);
        if (enemies.length > 0 && rc.isActionReady()) {
            RobotInfo target = enemies[0];
            int minDist = myLoc.distanceSquaredTo(target.location);
            for (int i = 1; i < enemies.length; i++) {
                int dist = myLoc.distanceSquaredTo(enemies[i].location);
                if (dist < minDist) { minDist = dist; target = enemies[i]; }
            }
            if (rc.canAttack(target.location)) {
                rc.attack(target.location);
            }
        }

        // Spawn: 50/50 soldier/splasher, paint >= 500
        if (rc.isActionReady() && rc.getPaint() >= 500) {
            UnitType toBuild;
            int idx = towerSpawnCount % 2;
            if (idx == 0) toBuild = UnitType.SOLDIER;
            else toBuild = UnitType.SPLASHER;

            int chipCost = (toBuild == UnitType.SPLASHER) ? 400 : 250;
            if (rc.getChips() >= chipCost) {
                // Spawn toward map center for better coverage
                Direction bestDir = null;
                int bestScore = Integer.MAX_VALUE;
                for (Direction dir : DIRS) {
                    MapLocation loc = myLoc.add(dir);
                    if (rc.canBuildRobot(toBuild, loc)) {
                        int score = loc.distanceSquaredTo(mapCenter);
                        if (score < bestScore) { bestScore = score; bestDir = dir; }
                    }
                }
                if (bestDir != null) {
                    rc.buildRobot(toBuild, myLoc.add(bestDir));
                    towerSpawnCount++;
                }
            }
        }

        // Upgrade with spare chips
        if (myType.canUpgradeType() && rc.canUpgradeTower(myLoc)) {
            if (rc.getChips() >= myType.getNextLevel().moneyCost + 500) {
                rc.upgradeTower(myLoc);
            }
        }
    }


    //  SOLDIER
    static void runSoldier(RobotController rc) throws GameActionException {
        rememberNearestPaintTower(rc);

        // Priority 1: Build towers at ruins
        if (tryBuildTower(rc)) return;

        // Priority 2: Attack enemy towers
        if (tryAttackEnemyTower(rc)) return;

        // Priority 3: SRP completion
        if (rc.getPaint() > 80 && Clock.getBytecodesLeft() > 5000) {
            trySRPCompletion(rc);
        }

        // Priority 4: Find ruins
        if (targetRuin != null && rc.canSenseLocation(targetRuin)) {
            RobotInfo occupant = rc.senseRobotAtLocation(targetRuin);
            if (occupant != null) targetRuin = null;
        }
        if (targetRuin == null) targetRuin = findNearestUnbuiltRuin(rc);

        if (targetRuin != null) {
            bugNavMove(rc, targetRuin);
        } else {
            exploreMove(rc);
        }

        // Paint current tile
        paintCurrentTile(rc);
    }

    static boolean tryBuildTower(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        MapLocation ruinLoc = findNearestUnbuiltRuin(rc);
        if (ruinLoc == null) return false;

        int dist = myLoc.distanceSquaredTo(ruinLoc);
        if (dist > 8) {
            targetRuin = ruinLoc;
            bugNavMove(rc, ruinLoc);
            return false;
        }

        // 1:2 money:paint tower ratio — prioritize paint supply
        UnitType towerType;
        if ((ruinLoc.x + ruinLoc.y) % 3 == 0) {
            towerType = UnitType.LEVEL_ONE_MONEY_TOWER;
        } else {
            towerType = UnitType.LEVEL_ONE_PAINT_TOWER;
        }

        if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
            rc.markTowerPattern(towerType, ruinLoc);
        }

        MapLocation tileToPaint = null;
        int nearestDist = Integer.MAX_VALUE;
        boolean useSecondary = false;
        boolean hasEnemyPaint = false;

        for (MapInfo tile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (Clock.getBytecodesLeft() < 1500) break;
            PaintType mark = tile.getMark();
            if (mark == PaintType.EMPTY) continue;
            if (tile.getPaint().isEnemy()) { hasEnemyPaint = true; continue; }
            if (mark == tile.getPaint()) continue;
            MapLocation loc = tile.getMapLocation();
            int d = myLoc.distanceSquaredTo(loc);
            if (d < nearestDist) {
                nearestDist = d;
                tileToPaint = loc;
                useSecondary = (mark == PaintType.ALLY_SECONDARY);
            }
        }

        if (hasEnemyPaint && tileToPaint == null) return false;

        if (tileToPaint != null) {
            if (!rc.canAttack(tileToPaint)) moveToward(rc, tileToPaint);
            if (rc.canAttack(tileToPaint)) rc.attack(tileToPaint, useSecondary);
        } else {
            if (dist > 2) moveToward(rc, ruinLoc);
        }

        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
            targetRuin = null;
        }
        return true;
    }

    static boolean tryAttackEnemyTower(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, enemyTeam);
        RobotInfo nearestTower = null;
        int nearestDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : enemies) {
            if (!enemy.type.isTowerType()) continue;
            int dist = rc.getLocation().distanceSquaredTo(enemy.location);
            if (dist < nearestDist) { nearestDist = dist; nearestTower = enemy; }
        }
        if (nearestTower == null) return false;

        if (!rc.canAttack(nearestTower.location)) moveToward(rc, nearestTower.location);
        if (rc.canAttack(nearestTower.location)) rc.attack(nearestTower.location);
        return true;
    }

    static MapLocation findNearestUnbuiltRuin(RobotController rc) throws GameActionException {
        MapInfo[] nearby = rc.senseNearbyMapInfos();
        MapLocation bestRuin = null;
        int bestDist = Integer.MAX_VALUE;
        MapLocation myLoc = rc.getLocation();
        for (MapInfo tile : nearby) {
            if (Clock.getBytecodesLeft() < 2000) break;
            if (!tile.hasRuin()) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            RobotInfo occupant = rc.senseRobotAtLocation(ruinLoc);
            if (occupant != null) continue;
            int dist = myLoc.distanceSquaredTo(ruinLoc);
            if (dist < bestDist) { bestDist = dist; bestRuin = ruinLoc; }
        }
        return bestRuin;
    }

    static void trySRPCompletion(RobotController rc) throws GameActionException {
        MapInfo[] nearbyInfos = rc.senseNearbyMapInfos(rc.getLocation(), 8);
        for (MapInfo info : nearbyInfos) {
            if (Clock.getBytecodesLeft() < 3000) break;
            if (!info.isResourcePatternCenter()) continue;
            MapLocation center = info.getMapLocation();
            if (rc.canCompleteResourcePattern(center)) {
                rc.completeResourcePattern(center);
                return;
            }
            if (rc.canMarkResourcePattern(center)) {
                rc.markResourcePattern(center);
            }
            for (MapInfo srpTile : rc.senseNearbyMapInfos(center, 8)) {
                if (Clock.getBytecodesLeft() < 1500) break;
                PaintType mark = srpTile.getMark();
                if (mark == PaintType.EMPTY) continue;
                if (mark == srpTile.getPaint()) continue;
                MapLocation tileLoc = srpTile.getMapLocation();
                if (rc.canAttack(tileLoc)) {
                    rc.attack(tileLoc, mark == PaintType.ALLY_SECONDARY);
                    return;
                }
            }
        }
    }

    /**
     * Paint the current tile.
     * IMPROVEMENT over main_bot: also paint over enemy paint (not just empty).
     */
    static void paintCurrentTile(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        MapLocation myLoc = rc.getLocation();
        MapInfo currentTile = rc.senseMapInfo(myLoc);
        PaintType mark = currentTile.getMark();
        PaintType paint = currentTile.getPaint();

        if (mark != PaintType.EMPTY) {
            if (mark != paint && rc.canAttack(myLoc)) {
                rc.attack(myLoc, mark == PaintType.ALLY_SECONDARY);
            }
        } else {
            if (!paint.isAlly() && rc.canAttack(myLoc)) {
                rc.attack(myLoc);
            }
        }
    }

    //  SPLASHER - area expander, no refuel
    static void runSplasher(RobotController rc) throws GameActionException {
        rememberNearestPaintTower(rc);

        boolean splashed = trySplashBestTarget(rc);

        Direction bestDir = greedySplasherDirection(rc);
        if (bestDir != null && rc.canMove(bestDir)) {
            rc.move(bestDir);
        }

        if (!splashed) trySplashBestTarget(rc);
    }

    static Direction greedySplasherDirection(RobotController rc) throws GameActionException {
        Direction bestDir = null;
        int bestScore = -1;

        for (Direction dir : DIRS) {
            if (!rc.canMove(dir)) continue;
            MapLocation dest = rc.getLocation().add(dir);
            int score = countNonAllyTilesAround(rc, dest);
            if (score > bestScore) { bestScore = score; bestDir = dir; }
        }

        if (bestScore <= 0) return getExploreDirection(rc);
        return bestDir;
    }

    static boolean trySplashBestTarget(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;
        MapLocation myLoc = rc.getLocation();
        MapLocation bestTarget = null;
        int bestScore = 0;

        MapInfo[] candidates = rc.senseNearbyMapInfos(myLoc, 4);
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
            if (score > bestScore) { bestScore = score; bestTarget = loc; }
        }

        if (bestTarget != null && bestScore >= 1 && rc.canAttack(bestTarget)) {
            rc.attack(bestTarget);
            return true;
        }
        return false;
    }

    //  MOPPER
    static void runMopper(RobotController rc) throws GameActionException {
        rememberNearestTower(rc);

        tryMopSwing(rc);
        tryMopSingle(rc);

        Direction moveDir = greedyMopperDirection(rc);
        if (moveDir != null && rc.canMove(moveDir)) {
            rc.move(moveDir);
        } else {
            exploreMove(rc);
        }

        tryMopSingle(rc);
    }

    static void tryMopSwing(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, enemyTeam);
        if (enemies.length == 0) return;
        MapLocation myLoc = rc.getLocation();

        Direction bestDir = null;
        int bestHits = 0;
        for (Direction d : CARDINALS) {
            if (!rc.canMopSwing(d)) continue;
            int hits = 0;
            MapLocation s1 = myLoc.add(d);
            MapLocation s2 = s1.add(d);
            for (RobotInfo e : enemies) {
                if (e.location.distanceSquaredTo(s1) <= 2 || e.location.distanceSquaredTo(s2) <= 2)
                    hits++;
            }
            if (hits > bestHits) { bestHits = hits; bestDir = d; }
        }
        if (bestDir != null) rc.mopSwing(bestDir);
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

    static Direction greedyMopperDirection(RobotController rc) throws GameActionException {
        Direction bestDir = null;
        int bestScore = -1;
        for (Direction dir : DIRS) {
            if (!rc.canMove(dir)) continue;
            MapLocation dest = rc.getLocation().add(dir);
            int score = 0;
            MapInfo[] tiles = rc.senseNearbyMapInfos(dest, 8);
            for (MapInfo tile : tiles) {
                if (tile.getPaint().isEnemy()) score += 2;
            }
            RobotInfo[] enemies = rc.senseNearbyRobots(dest, 8, enemyTeam);
            score += enemies.length * 3;
            if (score > bestScore) { bestScore = score; bestDir = dir; }
        }
        if (bestScore <= 0) return null;
        return bestDir;
    }

    //  NAVIGATION
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
        if (rc.canMove(dir)) { rc.move(dir); return; }
        if (rc.canMove(dir.rotateLeft())) { rc.move(dir.rotateLeft()); return; }
        if (rc.canMove(dir.rotateRight())) { rc.move(dir.rotateRight()); return; }
        if (rc.canMove(dir.rotateLeft().rotateLeft())) { rc.move(dir.rotateLeft().rotateLeft()); return; }
        if (rc.canMove(dir.rotateRight().rotateRight())) { rc.move(dir.rotateRight().rotateRight()); return; }
    }

    static void bugNavMove(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        MapLocation myLoc = rc.getLocation();
        if (myLoc.equals(target)) return;

        if (!bugTracing) {
            Direction dir = myLoc.directionTo(target);
            if (dir == Direction.CENTER) return;
            if (rc.canMove(dir)) { rc.move(dir); return; }
            if (rc.canMove(dir.rotateLeft())) { rc.move(dir.rotateLeft()); return; }
            if (rc.canMove(dir.rotateRight())) { rc.move(dir.rotateRight()); return; }
            if (rc.canMove(dir.rotateLeft().rotateLeft())) { rc.move(dir.rotateLeft().rotateLeft()); return; }
            if (rc.canMove(dir.rotateRight().rotateRight())) { rc.move(dir.rotateRight().rotateRight()); return; }

            bugTracing = true;
            bugTracingDir = dir;
            bugStart = myLoc;
            bugTurns = 0;
        }

        if (bugTracing) {
            bugTurns++;
            if (bugTurns > 2 * (mapW + mapH) ||
                (bugTurns > 4 && rc.getLocation().isWithinDistanceSquared(bugStart, 2))) {
                bugTracing = false;
                bugTurns = 0;
                return;
            }

            if (rc.canMove(bugTracingDir)) {
                rc.move(bugTracingDir);
                bugTracingDir = bugTracingDir.rotateRight().rotateRight();
            } else {
                for (int i = 0; i < 8; i++) {
                    bugTracingDir = bugTracingDir.rotateLeft();
                    if (rc.canMove(bugTracingDir)) {
                        rc.move(bugTracingDir);
                        bugTracingDir = bugTracingDir.rotateRight().rotateRight();
                        break;
                    }
                }
            }

            Direction directDir = rc.getLocation().directionTo(target);
            if (directDir != Direction.CENTER && rc.canMove(directDir)) {
                bugTracing = false;
                bugTurns = 0;
            }
        }
    }

    static Direction getExploreDirection(RobotController rc) throws GameActionException {
        // Move away from allies to spread out
        RobotInfo[] allies = rc.senseNearbyRobots(-1, myTeam);
        if (allies.length > 0) {
            int sumX = 0, sumY = 0, count = 0;
            for (RobotInfo ally : allies) {
                if (!ally.type.isTowerType()) {
                    sumX += ally.location.x;
                    sumY += ally.location.y;
                    count++;
                }
            }
            if (count > 0) {
                MapLocation allyCenter = new MapLocation(sumX / count, sumY / count);
                Direction awayFromAllies = allyCenter.directionTo(rc.getLocation());
                if (rc.canMove(awayFromAllies)) return awayFromAllies;
                if (rc.canMove(awayFromAllies.rotateLeft())) return awayFromAllies.rotateLeft();
                if (rc.canMove(awayFromAllies.rotateRight())) return awayFromAllies.rotateRight();
            }
        }

        // Fallback: persistent direction
        if (exploreDir == null || !rc.canMove(exploreDir)) {
            exploreDir = DIRS[(rc.getID() + rc.getRoundNum()) % 8];
        }
        for (int i = 0; i < 8; i++) {
            if (rc.canMove(exploreDir)) return exploreDir;
            exploreDir = exploreDir.rotateRight();
        }
        return null;
    }

    static void exploreMove(RobotController rc) throws GameActionException {
        Direction dir = getExploreDirection(rc);
        if (dir != null && rc.canMove(dir)) rc.move(dir);
    }

    //  utils
    static boolean isPaintTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_PAINT_TOWER
            || type == UnitType.LEVEL_TWO_PAINT_TOWER
            || type == UnitType.LEVEL_THREE_PAINT_TOWER;
    }

    static void rememberNearestPaintTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, myTeam);
        MapLocation bestTower = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            if (!isPaintTower(ally.type)) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.location);
            if (dist < bestDist) { bestDist = dist; bestTower = ally.location; }
        }
        if (bestTower != null) knownPaintTower = bestTower;
    }

    static void rememberNearestTower(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, myTeam);
        MapLocation bestTower = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            if (!ally.type.isTowerType()) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.location);
            if (dist < bestDist) { bestDist = dist; bestTower = ally.location; }
        }
        if (bestTower != null) knownPaintTower = bestTower;
    }
}
