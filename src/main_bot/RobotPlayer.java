package main_bot;

import battlecode.common.*;

import java.util.Random;

/**
 * RobotPlayer — Main Bot: "Maximum Unpainted Area" Greedy Strategy
 *
 * GREEDY HEURISTIC:
 *   At each turn, every robot picks the action that maximises the number of
 *   unpainted / enemy-painted tiles it can cover. Concretely:
 *
 *   - Soldiers:  Look at all 8 move directions. For each candidate direction,
 *                count how many tiles within attack range are NOT ally-painted.
 *                Move toward the direction with the highest count, then paint.
 *
 *   - Splashers: Same scanning idea, but because splash covers an area, we
 *                look for the densest cluster of non-ally tiles reachable in
 *                one move + one splash.
 *
 *   - Moppers:   Prioritise (1) transferring paint to nearby low-paint allies,
 *                (2) mopping enemy paint, (3) moving toward enemy territory.
 *
 *   - Towers:    Spawn robots with a weighted ratio (more soldiers early game,
 *                balanced mid-game). Attack any visible enemies.
 *
 * Greedy elements (for your report — Bab 3):
 *   Candidate set      : All legal actions the unit can take this turn
 *   Selection function  : Pick the action with the highest "unpainted tile" score
 *   Feasibility function: rc.canMove(), rc.canAttack(), rc.canBuildRobot(), etc.
 *   Objective function  : Maximise painted area → win condition (>70%)
 *   Solution set        : The sequence of chosen actions across all turns
 */
public class RobotPlayer {

    // ─── Shared state (per-robot copy, NOT shared between robots) ───
    static int turnCount = 0;
    static final Random rng = new Random(6147);

    static final Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    // ─── Configurable parameters (tune these!) ───
    /** Early-game rounds threshold — spawn more soldiers early */
    static final int EARLY_GAME_ROUNDS = 200;
    /** Desired ratio: out of every N robots spawned, how many are soldiers? */
    static final int SOLDIER_WEIGHT = 5;
    static final int SPLASHER_WEIGHT = 3;
    static final int MOPPER_WEIGHT = 2;

    // ─── Per-robot tracking ───
    static MapLocation spawnTowerLocation = null;
    static Direction exploreDir = null;

    // ================================================================
    //  ENTRY POINT
    // ================================================================

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
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

    /**
     * Tower strategy:
     *  1. Attack any visible enemy (single-target + AoE).
     *  2. Spawn robots with a weighted ratio that shifts over time.
     *  3. Read messages from robots for coordination (future improvement).
     */
    public static void runTower(RobotController rc) throws GameActionException {
        // --- 1. Attack nearby enemies ---
        towerAttack(rc);

        // --- 2. Spawn robots ---
        towerSpawn(rc);

        // --- 3. Read messages (for coordination) ---
        towerReadMessages(rc);
    }

    /**
     * Tower attacks the closest visible enemy robot.
     */
    static void towerAttack(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        // TODO: Pick the best target (lowest HP? closest? priority type?)
        if (enemies.length > 0 && rc.isActionReady()) {
            // Attack the nearest enemy
            RobotInfo target = enemies[0];
            int minDist = rc.getLocation().distanceSquaredTo(target.location);
            for (RobotInfo enemy : enemies) {
                int dist = rc.getLocation().distanceSquaredTo(enemy.location);
                if (dist < minDist) {
                    minDist = dist;
                    target = enemy;
                }
            }
            if (rc.canAttack(target.location)) {
                rc.attack(target.location);
            }
        }
    }

    /**
     * Tower spawns robots using a weighted ratio.
     * Early game: mostly soldiers to paint territory fast.
     * Mid/late game: balanced mix including splashers and moppers.
     */
    static void towerSpawn(RobotController rc) throws GameActionException {
        // Decide which type to build based on weighted random selection
        UnitType toBuild = pickRobotType(rc);

        // Try all 8 directions + center-adjacent to find a valid spawn location
        for (Direction dir : directions) {
            MapLocation spawnLoc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(toBuild, spawnLoc)) {
                rc.buildRobot(toBuild, spawnLoc);
                return;
            }
        }
    }

    /**
     * Weighted random pick for which robot type to spawn.
     * TODO: You can make this smarter — e.g. track how many of each type
     *       you've built and adjust dynamically.
     */
    static UnitType pickRobotType(RobotController rc) {
        int totalWeight;
        int soldierW, splasherW, mopperW;

        if (rc.getRoundNum() < EARLY_GAME_ROUNDS) {
            // Early game: heavy on soldiers
            soldierW  = 7;
            splasherW = 1;
            mopperW   = 2;
        } else {
            soldierW  = SOLDIER_WEIGHT;
            splasherW = SPLASHER_WEIGHT;
            mopperW   = MOPPER_WEIGHT;
        }
        totalWeight = soldierW + splasherW + mopperW;

        int roll = rng.nextInt(totalWeight);
        if (roll < soldierW)  return UnitType.SOLDIER;
        if (roll < soldierW + splasherW) return UnitType.SPLASHER;
        return UnitType.MOPPER;
    }

    /**
     * Read messages from allied robots.
     * TODO: Implement inter-unit communication (e.g. enemy positions, ruin locations).
     */
    static void towerReadMessages(RobotController rc) throws GameActionException {
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            // TODO: Decode and act on messages
        }
    }

    // ================================================================
    //  SOLDIER LOGIC  — Greedy: move toward max unpainted tiles
    // ================================================================

    /**
     * Soldier turn:
     *  1. If near a ruin → try to build a tower (high priority).
     *  2. Otherwise → scan all 8 directions, pick the one with the most
     *     unpainted tiles in sensor range (greedy selection), move there.
     *  3. Paint the tile we're standing on (and nearby tiles if possible).
     */
    public static void runSoldier(RobotController rc) throws GameActionException {
        // --- 1. Try to build towers at nearby ruins ---
        if (tryBuildTower(rc)) {
            return; // Spent our turn building
        }

        // --- 2. Greedy directional move ---
        Direction bestDir = greedyDirectionSoldier(rc);
        if (bestDir != null && rc.canMove(bestDir)) {
            rc.move(bestDir);
        }

        // --- 3. Paint current tile ---
        paintCurrentTile(rc);

        // --- 4. Attack/paint a nearby unpainted tile ---
        attackBestTile(rc);
    }

    /**
     * GREEDY SELECTION for Soldier movement.
     *
     * For each of the 8 directions (+ staying still), calculate a score:
     *   score = number of tiles within sensor range of the destination
     *           that are NOT ally-painted (i.e. empty or enemy-painted).
     *
     * Pick the direction with the highest score.
     *
     * @return the best Direction to move, or null if no move is beneficial.
     */
    static Direction greedyDirectionSoldier(RobotController rc) throws GameActionException {
        Direction bestDir = null;
        int bestScore = -1;

        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;

            MapLocation dest = rc.getLocation().add(dir);
            // Score = how many non-ally tiles are around the destination
            int score = countNonAllyTilesAround(rc, dest);

            if (score > bestScore) {
                bestScore = score;
                bestDir = dir;
            }
        }

        // If all directions are equally painted, pick a random one to explore
        if (bestScore <= 0) {
            bestDir = getExploreDirection(rc);
        }

        return bestDir;
    }

    /**
     * Count the number of tiles around `center` (within sensor range) that are
     * not painted by our team. Higher = more unpainted area = better greedy choice.
     */
    static int countNonAllyTilesAround(RobotController rc, MapLocation center) throws GameActionException {
        int count = 0;
        // Sensor range for robots is 20 (sqrt(20) ≈ 4.47 tiles)
        // We check tiles in a smaller radius from the candidate destination
        // to estimate the "gain" of moving there.
        MapInfo[] tiles = rc.senseNearbyMapInfos(center, 8);
        for (MapInfo tile : tiles) {
            if (!tile.isPassable()) continue;
            if (tile.getPaint() == PaintType.EMPTY ||
                tile.getPaint().isEnemy()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get a persistent exploration direction — helps the robot spread out
     * instead of jittering in place when all nearby tiles are painted.
     * Picks a new random direction when the current one is blocked.
     */
    static Direction getExploreDirection(RobotController rc) throws GameActionException {
        if (exploreDir == null || !rc.canMove(exploreDir)) {
            exploreDir = directions[rng.nextInt(directions.length)];
        }
        // Try the chosen direction, if blocked try rotating
        for (int i = 0; i < 8; i++) {
            if (rc.canMove(exploreDir)) return exploreDir;
            exploreDir = exploreDir.rotateRight();
        }
        return null;
    }

    // ================================================================
    //  SPLASHER LOGIC — Greedy: move toward densest unpainted cluster
    // ================================================================

    /**
     * Splasher turn:
     *  1. Find the best splash target: the location where splashing covers
     *     the most non-ally tiles.
     *  2. If in range → splash. Otherwise move toward the best cluster.
     */
    public static void runSplasher(RobotController rc) throws GameActionException {
        // --- 1. Try to splash the densest area ---
        if (trySplashBestTarget(rc)) {
            // Splashed successfully; now move if we can
            Direction moveDir = getExploreDirection(rc);
            if (moveDir != null && rc.canMove(moveDir)) {
                rc.move(moveDir);
            }
            return;
        }

        // --- 2. Move toward unpainted area ---
        Direction bestDir = greedyDirectionSplasher(rc);
        if (bestDir != null && rc.canMove(bestDir)) {
            rc.move(bestDir);
        }

        // --- 3. Try to splash after moving ---
        trySplashBestTarget(rc);
    }

    /**
     * GREEDY SELECTION for Splasher:
     * Evaluate each direction by how many non-ally tiles exist around
     * the destination within splash range.
     */
    static Direction greedyDirectionSplasher(RobotController rc) throws GameActionException {
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
            bestDir = getExploreDirection(rc);
        }
        return bestDir;
    }

    /**
     * Try to find the best splash target and execute the splash.
     * Splash radius = 2 from target center, target must be within 2 tiles.
     *
     * TODO: Implement the actual scoring — for now, attacks the location
     *       with the most non-ally paint reachable.
     *
     * @return true if we successfully splashed.
     */
    static boolean trySplashBestTarget(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;

        MapLocation bestTarget = null;
        int bestScore = 0;

        // Evaluate all tiles within attack range (distance² ≤ 4)
        MapInfo[] nearby = rc.senseNearbyMapInfos(rc.getLocation(), 4);
        for (MapInfo tile : nearby) {
            MapLocation loc = tile.getMapLocation();
            if (!rc.canAttack(loc)) continue;

            // Score: count non-ally tiles in splash area around this target
            int score = 0;
            MapInfo[] splashArea = rc.senseNearbyMapInfos(loc, 4);
            for (MapInfo s : splashArea) {
                if (!s.isPassable()) continue;
                PaintType p = s.getPaint();
                if (p == PaintType.EMPTY || p.isEnemy()) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestTarget = loc;
            }
        }

        if (bestTarget != null && rc.canAttack(bestTarget)) {
            rc.attack(bestTarget);
            return true;
        }
        return false;
    }

    // ================================================================
    //  MOPPER LOGIC — Support: refuel allies & clean enemy paint
    // ================================================================

    /**
     * Mopper turn priority:
     *  1. Transfer paint to a nearby ally that is low on paint.
     *  2. Mop enemy paint (swing or single-target).
     *  3. Move toward enemy territory or allies that need help.
     */
    public static void runMopper(RobotController rc) throws GameActionException {
        // --- 1. Transfer paint to low-paint allies ---
        if (tryTransferPaint(rc)) {
            // After transfer, still try to move
        }

        // --- 2. Mop swing if enemies nearby ---
        if (tryMopSwing(rc)) {
            // Good, we mopped
        }

        // --- 3. Move toward useful area ---
        Direction moveDir = greedyDirectionMopper(rc);
        if (moveDir != null && rc.canMove(moveDir)) {
            rc.move(moveDir);
        }

        // --- 4. Single-target mop after moving ---
        tryMopSingle(rc);
    }

    /**
     * GREEDY SELECTION for Mopper:
     * Prioritise moving toward (a) allies with low paint, (b) enemy-painted tiles.
     */
    static Direction greedyDirectionMopper(RobotController rc) throws GameActionException {
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
            bestDir = getExploreDirection(rc);
        }
        return bestDir;
    }

    /**
     * Scoring for mopper: counts enemy-painted tiles near destination
     * and gives bonus for nearby low-paint allies.
     */
    static int scoreMopperDirection(RobotController rc, MapLocation dest) throws GameActionException {
        int score = 0;
        MapInfo[] tiles = rc.senseNearbyMapInfos(dest, 8);
        for (MapInfo tile : tiles) {
            if (tile.getPaint().isEnemy()) {
                score += 2; // Enemy paint = high value to mop
            }
        }

        // Bonus for nearby allies with low paint
        RobotInfo[] allies = rc.senseNearbyRobots(dest, 8, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (ally.paintAmount < ally.type.paintCapacity / 3) {
                score += 3; // Ally needs paint = high value
            }
        }
        return score;
    }

    /**
     * Transfer paint to an adjacent ally robot that is low on paint.
     * Mopper transfer range: √2 ≈ 1.41 tiles (distance² ≤ 2).
     *
     * @return true if a transfer was made.
     */
    static boolean tryTransferPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;

        RobotInfo[] allies = rc.senseNearbyRobots(2, rc.getTeam());
        RobotInfo neediest = null;
        int lowestPaintRatio = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            // Don't transfer to other moppers (they can get their own)
            if (ally.type == UnitType.MOPPER) continue;
            int ratio = (ally.paintAmount * 100) / Math.max(1, ally.type.paintCapacity);
            if (ratio < 50 && ratio < lowestPaintRatio) {
                lowestPaintRatio = ratio;
                neediest = ally;
            }
        }

        if (neediest != null) {
            int transferAmount = Math.min(rc.getPaint(), neediest.type.paintCapacity - neediest.paintAmount);
            transferAmount = Math.min(transferAmount, rc.getPaint() / 2); // Keep some for ourselves
            if (transferAmount > 0 && rc.canTransferPaint(neediest.location, transferAmount)) {
                rc.transferPaint(neediest.location, transferAmount);
                return true;
            }
        }
        return false;
    }

    /**
     * Try mop swing toward enemies.
     * @return true if swing was performed.
     */
    static boolean tryMopSwing(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;
        // Try all 4 cardinal directions for mop swing
        Direction[] cardinals = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        for (Direction dir : cardinals) {
            if (rc.canMopSwing(dir)) {
                rc.mopSwing(dir);
                return true;
            }
        }
        return false;
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
     * Try to build a tower at a nearby ruin.
     * Soldiers will attempt to:
     *   1. Find the nearest ruin in sensor range
     *   2. Move toward it
     *   3. Mark the tower pattern
     *   4. Paint the marked tiles
     *   5. Complete the tower
     *
     * @return true if we're actively building (consumed our turn actions).
     */
    static boolean tryBuildTower(RobotController rc) throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo closestRuin = null;
        int closestDist = Integer.MAX_VALUE;

        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin()) {
                int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (dist < closestDist) {
                    closestDist = dist;
                    closestRuin = tile;
                }
            }
        }

        if (closestRuin == null) return false;

        MapLocation ruinLoc = closestRuin.getMapLocation();

        // Check if there's already a tower here (ruin occupied by a robot = built tower)
        RobotInfo occupant = rc.senseRobotAtLocation(ruinLoc);
        if (occupant != null) return false;

        // Move toward the ruin
        Direction dirToRuin = rc.getLocation().directionTo(ruinLoc);
        if (rc.canMove(dirToRuin)) {
            rc.move(dirToRuin);
        }

        // Mark the tower pattern (try paint tower first, then money tower)
        // TODO: Make this smarter — decide tower type based on what you need
        UnitType towerType = chooseTowerType(rc);
        if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
            rc.markTowerPattern(towerType, ruinLoc);
        }

        // Fill in marked pattern tiles with the correct paint color
        for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (patternTile.getMark() != PaintType.EMPTY
                && patternTile.getMark() != patternTile.getPaint()) {
                boolean useSecondary = patternTile.getMark() == PaintType.ALLY_SECONDARY;
                if (rc.canAttack(patternTile.getMapLocation())) {
                    rc.attack(patternTile.getMapLocation(), useSecondary);
                }
            }
        }

        // Complete the tower if pattern is done
        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
        }

        return true;
    }

    /**
     * Decide which tower type to build.
     * TODO: Implement logic — e.g. alternate between paint and money,
     *       or check existing tower counts.
     */
    static UnitType chooseTowerType(RobotController rc) {
        // Simple heuristic: alternate between paint and money towers
        // You can improve this by checking how many of each you have
        if (turnCount % 2 == 0) {
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        } else {
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        }
    }

    /**
     * Paint the tile the robot is currently standing on (if not already ally-painted).
     * Avoids wasting paint by re-painting our own tiles.
     */
    static void paintCurrentTile(RobotController rc) throws GameActionException {
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            rc.attack(rc.getLocation());
        }
    }

    /**
     * Attack/paint the best nearby tile (non-ally, passable).
     * Picks the tile closest to an unpainted area for maximum spread.
     */
    static void attackBestTile(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;

        MapInfo[] nearby = rc.senseNearbyMapInfos(rc.getLocation(), 9);
        MapLocation bestTarget = null;
        int bestScore = -1;

        for (MapInfo tile : nearby) {
            MapLocation loc = tile.getMapLocation();
            if (!tile.isPassable()) continue;
            if (tile.getPaint().isAlly()) continue;
            if (!rc.canAttack(loc)) continue;

            // Prefer enemy-painted tiles (flipping them), then empty tiles
            int score = tile.getPaint().isEnemy() ? 2 : 1;
            if (score > bestScore) {
                bestScore = score;
                bestTarget = loc;
            }
        }

        if (bestTarget != null) {
            rc.attack(bestTarget);
        }
    }

    /**
     * Try to withdraw paint from a nearby ally tower.
     * Useful when a robot is running low on paint.
     *
     * @return true if paint was withdrawn.
     */
    static boolean tryWithdrawPaint(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;
        if (rc.getPaint() > rc.getType().paintCapacity / 2) return false; // Don't need refill yet

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
}
