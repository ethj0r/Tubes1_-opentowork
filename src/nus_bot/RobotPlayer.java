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

    // ==================== TOWER STATE ====================
    static int botSpawnedCount = 0;

    // ==================== SOLDIER MOVEMENT STATE ====================
    static Direction currentDir = null;       // current heading
    static int stepsInCurrentDir = 0;         // steps taken in current heading
    static boolean wallFollowing = false;      // currently hugging a wall?
    static Direction wallOrigDir = null;       // heading before we hit the wall
    static int wallFollowSteps = 0;           // steps spent hugging
    static Direction prevForcedDir = null;     // last direction forced by anti-loop

    // ==================== SOLDIER RUIN STATE ====================
    static MapLocation targetRuin = null;

    // ==================== CONSTANTS ====================
    static final int MAX_STRAIGHT_STEPS = 15;  // force direction change after this
    static final int MAX_WALL_STEPS = 12;      // give up wall-following after this
    static final int EDGE_MARGIN = 3;          // steer away from edges within this
    static final int UPGRADE_L2_ROUND = 500;   // start upgrading to L2 after this round
    static final int UPGRADE_L3_ROUND = 1000;  // start upgrading to L3 after this round

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
        for (Direction dir : directions) {
            MapLocation spawnLoc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(UnitType.SOLDIER, spawnLoc)) {
                rc.buildRobot(UnitType.SOLDIER, spawnLoc);
                botSpawnedCount++;
            }
        }
    }

    // ================================================================
    //                           SOLDIER
    // ================================================================
    public static void runSoldier(RobotController rc) throws GameActionException {
        // Priority 1: If we see an unbuilt ruin, try to build a tower on it
        if (tryBuildTower(rc)) return;

        // Priority 2: Upgrade nearby ally towers if enough rounds have passed
        tryUpgradeTower(rc);

        // Priority 3: Explore the map
        soldierExplore(rc);
    }

    // ================================================================
    //                           MOPPER
    // ================================================================
    public static void runMopper(RobotController rc) throws GameActionException {
        // TODO
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
    /**
     * Greedy tower building:
     *   1. Find nearest unbuilt ruin
     *   2. Move toward it
     *   3. Mark the pattern (places marks showing what color each tile needs)
     *   4. Paint tiles to match marks
     *   5. Complete the tower when all tiles are correct
     *
     * Returns true if the soldier is busy building (so it shouldn't explore).
     */
    static boolean tryBuildTower(RobotController rc) throws GameActionException {
        // --- Find a ruin to work on ---
        if (targetRuin == null) {
            targetRuin = findNearestUnbuiltRuin(rc);
        }
        if (targetRuin == null) return false;

        // Validate: if someone else already built a tower there, clear target
        if (rc.canSenseLocation(targetRuin)) {
            RobotInfo occupant = rc.senseRobotAtLocation(targetRuin);
            if (occupant != null && occupant.getType().isTowerType()) {
                targetRuin = null;
                return false;
            }
        }

        int distToRuin = rc.getLocation().distanceSquaredTo(targetRuin);

        // Too far — walk toward the ruin
        if (distToRuin > 8) {
            Direction dir = rc.getLocation().directionTo(targetRuin);
            if (rc.canMove(dir)) {
                rc.move(dir);
            } else if (rc.canMove(dir.rotateRight())) {
                rc.move(dir.rotateRight());
            } else if (rc.canMove(dir.rotateLeft())) {
                rc.move(dir.rotateLeft());
            }
            return true; // busy walking to ruin
        }

        // --- Close enough: pick tower type ---
        // Simple ratio: (x+y) % 3 == 0 → money tower, else paint tower
        UnitType towerType;
        if ((targetRuin.x + targetRuin.y) % 2 == 0) {
            towerType = UnitType.LEVEL_ONE_MONEY_TOWER;
        } else {
            towerType = UnitType.LEVEL_ONE_PAINT_TOWER;
        }

        // --- Step 1: Mark the pattern ---
        if (rc.canMarkTowerPattern(towerType, targetRuin)) {
            rc.markTowerPattern(towerType, targetRuin);
        }

        // --- Step 2: Find a tile that needs painting ---
        MapLocation tileToPaint = null;
        int nearestDist = Integer.MAX_VALUE;
        boolean useSecondary = false;

        for (MapInfo tile : rc.senseNearbyMapInfos(targetRuin, 8)) {
            PaintType mark = tile.getMark();
            if (mark == PaintType.EMPTY) continue;             // not part of pattern
            if (tile.getPaint().isEnemy()) continue;            // soldier can't overwrite enemy paint
            if (mark == tile.getPaint()) continue;              // already correct color

            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < nearestDist) {
                nearestDist = dist;
                tileToPaint = loc;
                useSecondary = (mark == PaintType.ALLY_SECONDARY);
            }
        }

        // --- Step 3: Paint the tile ---
        if (tileToPaint != null) {
            // Move closer if out of attack range
            if (!rc.canAttack(tileToPaint)) {
                Direction dir = rc.getLocation().directionTo(tileToPaint);
                if (rc.canMove(dir)) rc.move(dir);
                else if (rc.canMove(dir.rotateRight())) rc.move(dir.rotateRight());
                else if (rc.canMove(dir.rotateLeft())) rc.move(dir.rotateLeft());
            }
            // Paint it
            if (rc.canAttack(tileToPaint)) {
                rc.attack(tileToPaint, useSecondary);
            }
            return true; // busy painting
        }

        // --- Step 4: All tiles done? Complete the tower! ---
        if (rc.canCompleteTowerPattern(towerType, targetRuin)) {
            rc.completeTowerPattern(towerType, targetRuin);
            targetRuin = null; // done, look for next ruin
            return true;
        }

        // Still close but can't complete yet — move closer to ruin center
        if (distToRuin > 2) {
            Direction dir = rc.getLocation().directionTo(targetRuin);
            if (rc.canMove(dir)) rc.move(dir);
        }

        return true; // busy working on this ruin
    }

    /**
     * Scan all visible ruins and return the nearest one that has no tower on it.
     */
    static MapLocation findNearestUnbuiltRuin(RobotController rc) throws GameActionException {
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        MapLocation closest = null;
        int closestDist = Integer.MAX_VALUE;

        for (MapLocation ruin : ruins) {
            RobotInfo occupant = rc.senseRobotAtLocation(ruin);
            if (occupant != null && occupant.getType().isTowerType()) continue;

            int dist = rc.getLocation().distanceSquaredTo(ruin);
            if (dist < closestDist) {
                closestDist = dist;
                closest = ruin;
            }
        }
        return closest;
    }

    // ================================================================
    //                   SOLDIER: TOWER UPGRADING
    // ================================================================
    /**
     * Scan nearby ally towers and upgrade them if possible.
     *   - After round 500: upgrade L1 towers to L2
     *   - After round 1000: upgrade L2 towers to L3
     */
    static void tryUpgradeTower(RobotController rc) throws GameActionException {
        int round = rc.getRoundNum();
        if (round < UPGRADE_L2_ROUND) return; // too early

        RobotInfo[] nearbyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo robot : nearbyRobots) {
            if (!robot.getType().isTowerType()) continue;
            if (!robot.getType().canUpgradeType()) continue; // already max level

            int level = robot.getType().level;
            // Only upgrade L1→L2 if round >= 500, L2→L3 if round >= 1000
            if (level == 1 && round >= UPGRADE_L2_ROUND) {
                // ok to upgrade
            } else if (level == 2 && round >= UPGRADE_L3_ROUND) {
                // ok to upgrade
            } else {
                continue;
            }

            MapLocation towerLoc = robot.getLocation();

            // Must be adjacent (within √2) to upgrade
            if (rc.getLocation().distanceSquaredTo(towerLoc) <= 2) {
                if (rc.canUpgradeTower(towerLoc)) {
                    rc.upgradeTower(towerLoc);
                    return; // one upgrade per turn
                }
            }
        }
    }

    // ================================================================
    //                    SOLDIER GREEDY EXPLORE
    // ================================================================
    /**
     * Greedy exploration with 3 layers:
     *   1. Edge avoidance  — steer toward center near map borders
     *   2. Anti-loop        — force new heading after too many straight steps
     *   3. Wall-following   — hug wall to find gaps, give up after a limit
     *   4. Normal movement  — go straight, prefer unpainted tiles
     */
    static void soldierExplore(RobotController rc) throws GameActionException {
        // Initialize direction on first turn
        if (currentDir == null) {
            currentDir = directions[rng.nextInt(directions.length)];
            stepsInCurrentDir = 0;
        }

        // --- Layer 1: Edge avoidance ---
        if (!wallFollowing && tryEdgeAvoidance(rc)) return;

        // --- Layer 2: Anti-loop ---
        if (stepsInCurrentDir >= MAX_STRAIGHT_STEPS) {
            if (tryForceNewDirection(rc)) return;
            stepsInCurrentDir = 0; // reset even if we couldn't move
        }

        // --- Layer 3: Wall-following ---
        if (wallFollowing) {
            wallFollowSteps++;

            // 3a: Gave up — too many steps, pick a new direction away
            if (wallFollowSteps >= MAX_WALL_STEPS) {
                exitWallFollow(rc);
                return;
            }

            // 3b: Check for gap (original dir ± 45°)
            if (tryGap(rc)) return;

            // 3c: Keep hugging wall (right-hand rule)
            if (tryHugWall(rc, wallOrigDir)) return;

            return; // completely stuck, wait
        }

        // --- Layer 4: Normal straight movement ---
        if (rc.canMove(currentDir)) {
            rc.move(currentDir);
            stepsInCurrentDir++;
            return;
        }

        // Blocked — enter wall-following
        wallFollowing = true;
        wallOrigDir = currentDir;
        wallFollowSteps = 0;
        if (tryHugWall(rc, currentDir)) return;
        // Can't even start hugging, just wait
    }

    // ======================== HELPERS ========================

    /**
     * If near a map edge, steer toward center.
     * Returns true if a move was made.
     */
    static boolean tryEdgeAvoidance(RobotController rc) throws GameActionException {
        int x = rc.getLocation().x;
        int y = rc.getLocation().y;
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        if (x >= EDGE_MARGIN && x < w - EDGE_MARGIN &&
            y >= EDGE_MARGIN && y < h - EDGE_MARGIN) {
            return false; // not near edge
        }

        Direction toCenter = rc.getLocation().directionTo(new MapLocation(w / 2, h / 2));
        return tryMoveInOrder(rc, new Direction[]{
            toCenter, toCenter.rotateRight(), toCenter.rotateLeft()
        }, true);
    }

    /**
     * Force a new direction to break loops.
     * Detects corridor oscillation: if we'd just reverse the last forced direction,
     * try perpendicular directions first or enter wall-following.
     */
    static boolean tryForceNewDirection(RobotController rc) throws GameActionException {
        boolean oscillating = (prevForcedDir != null && currentDir.equals(prevForcedDir.opposite()));

        Direction[] choices;
        if (oscillating) {
            // We're bouncing back and forth — try perpendicular escape first
            choices = new Direction[]{
                currentDir.rotateRight().rotateRight(),                  // 90° right
                currentDir.rotateLeft().rotateLeft(),                    // 90° left
                currentDir.rotateRight(),                                // 45° right
                currentDir.rotateLeft(),                                 // 45° left
                currentDir.rotateRight().rotateRight().rotateRight(),     // 135° right
                currentDir.rotateLeft().rotateLeft().rotateLeft(),        // 135° left
            };
        } else {
            // Normal anti-loop: prefer opposite
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
            prevForcedDir = currentDir;  // record what we forced
        } else if (oscillating) {
            // Can't escape perpendicular either — enter wall-following sideways
            wallFollowing = true;
            wallOrigDir = currentDir.rotateRight().rotateRight(); // aim perpendicular
            wallFollowSteps = 0;
            prevForcedDir = null;
            return tryHugWall(rc, wallOrigDir);
        }
        return moved;
    }

    /**
     * Check if a gap exists in roughly the original direction.
     * Returns true if we moved through a gap.
     */
    static boolean tryGap(RobotController rc) throws GameActionException {
        Direction[] gapDirs = {
            wallOrigDir,
            wallOrigDir.rotateRight(),
            wallOrigDir.rotateLeft(),
        };
        for (Direction dir : gapDirs) {
            if (rc.canMove(dir)) {
                // Verify the gap leads somewhere open (not just a 1-tile pocket)
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

    /**
     * Hug the wall using right-hand rule relative to `blockedDir`.
     * Returns true if a move was made.
     */
    static boolean tryHugWall(RobotController rc, Direction blockedDir) throws GameActionException {
        Direction[] hugOrder = {
            blockedDir.rotateRight().rotateRight(),                       // 90° right
            blockedDir.rotateRight(),                                     // 45° right
            blockedDir.rotateRight().rotateRight().rotateRight(),          // 135° right
            blockedDir.rotateLeft().rotateLeft(),                          // 90° left
            blockedDir.rotateLeft(),                                       // 45° left
            blockedDir.rotateLeft().rotateLeft().rotateLeft(),             // 135° left
            blockedDir.opposite(),                                         // 180°
        };
        for (Direction dir : hugOrder) {
            if (rc.canMove(dir)) {
                rc.move(dir);
                return true;
            }
        }
        return false;
    }

    /**
     * Exit wall-following: pick a direction away from the original wall heading.
     */
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
            // Truly stuck, randomize
            currentDir = directions[rng.nextInt(directions.length)];
        }
        stepsInCurrentDir = 0;
    }

    /**
     * Try to move in the first valid direction from the list.
     * If `updateDir` is true, also updates currentDir and resets step counter.
     * Returns true if a move was made.
     */
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
