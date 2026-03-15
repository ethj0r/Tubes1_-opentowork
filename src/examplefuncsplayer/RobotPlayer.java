package examplefuncsplayer;

import battlecode.common.*;
import java.util.Random;

/**
 * Core strategy:
 * - Early game: towers build mostly Soldiers
 * - Mid game: mix Soldiers and Moppers
 * - Late game: switch to Splashers and Moppers
 * - Soldiers prioritize ruins, then paint non-ally tiles
 * - Moppers clean enemy paint, then use mop swing
 * - Splashers choose the best attack tile using greedy scoring
 *
 * Key behaviors:
 * - Move toward nearest ruin to build Money Towers
 * - Paint tiles that are not yet ally-controlled
 * - Clean enemy paint to protect map control
 * - Use simple movement: forward, left, right, then random
 * - Send enemy-count messages to nearby allies every 20 rounds
 *
 * Main improvements:
 * - Phase-based tower spawning strategy
 * - Automatic Money Tower construction by Soldiers
 * - Greedy target selection for Splasher attacks
 * - Enemy paint detection and cleanup by Moppers
 * - Simple communication support between allied units
 */

public class RobotPlayer {
    
    static int turnCount = 0;
    static final Random rng = new Random(6147);

    /** Array containing all the possible movement directions. */
    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST,Direction.WEST,Direction.NORTHWEST,
    };
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        // Hello world! Standard output is very useful for debugging.
        // Everything you say here will be directly viewable in your terminal when you run a match!
        System.out.println("I'm alive");

        // You can also use indicators to save debug notes in replays.
        rc.setIndicatorString("Hello world!");

        while (true) {
            // This code runs during the entire lifespan of the robot, which is why it is in an infinite
            // loop. If we ever leave this loop and return from run(), the robot dies! At the end of the
            // loop, we call Clock.yield(), signifying that we've done everything we want to do.

            turnCount += 1;  // We have now been alive for one more turn!

            // Try/catch blocks stop unhandled exceptions, which cause your robot to explode.
            try {
                // The same run() function is called for every robot on your team, even if they are
                // different types. Here, we separate the control depending on the UnitType, so we can
                // use different strategies on different robots. If you wish, you are free to rewrite
                // this into a different control structure!
                switch (rc.getType()){
                    case SOLDIER: runSoldier(rc); break; 
                    case MOPPER: runMopper(rc); break;
                    case SPLASHER: runSplasher(rc); break; // Consider upgrading examplefuncsplayer to use splashers!
                    default: runTower(rc); break;
                    }
                }
             catch (GameActionException e) {
                // Oh no! It looks like we did something illegal in the Battlecode world. You should
                // handle GameActionExceptions judiciously, in case unexpected events occur in the game
                // world. Remember, uncaught exceptions cause your robot to explode!
                System.out.println("GameActionException");
                e.printStackTrace();

            } catch (Exception e) {
                // Oh no! It looks like our code tried to do something bad. This isn't a
                // GameActionException, so it's more likely to be a bug in our code.
                System.out.println("Exception");
                e.printStackTrace();

            } finally {
                // Signify we've done everything we want to do, thereby ending our turn.
                // This will make our code wait until the next turn, and then perform this loop again.
                Clock.yield();
            }
            // End of loop: go back to the top. Clock.yield() has ended, so it's time for another turn!
        }

        // Your code should never reach here (unless it's intentional)! Self-destruction imminent...
    }
    
    //Tower
    public static void runTower(RobotController rc) throws GameActionException {
            int round = rc.getRoundNum();

            // Build Soldiers first
            if (round < 80) {
                if (tryBuildRobotAnywhere(rc, UnitType.SOLDIER)) {
                    rc.setIndicatorString("Built SOLDIER");
                }
            }
            // Mix Soldiers and Moppers
            else if (round < 160) {
                if (round % 3 == 0) {
                    if (tryBuildRobotAnywhere(rc, UnitType.MOPPER)) {
                        rc.setIndicatorString("Built MOPPER");
                    }
                } else {
                    if (tryBuildRobotAnywhere(rc, UnitType.SOLDIER)) {
                        rc.setIndicatorString("Built SOLDIER");
                    }
                }
            }
            // Use Splashers for bigger area control
            else {
                if (round % 2 == 0) {
                    if (tryBuildRobotAnywhere(rc, UnitType.SPLASHER)) {
                        rc.setIndicatorString("Built SPLASHER");
                    }
                } else {
                    if (tryBuildRobotAnywhere(rc, UnitType.MOPPER)) {
                        rc.setIndicatorString("Built MOPPER");
                    }
                }
            }

            // Read messages from nearby allies
            Message[] messages = rc.readMessages(-1);
            for (Message m : messages) {
                System.out.println("Tower received message: '#" + m.getSenderID() + " " + m.getBytes());
            }
    }
    
    //Soldier
    public static void runSoldier(RobotController rc) throws GameActionException {
        MapLocation ruinLoc = findNearestRuin(rc);

        // Priority 1: if there is a ruin, build a money tower
        if (ruinLoc != null) {

            // Move closer if the ruin is still far
            if (rc.getLocation().distanceSquaredTo(ruinLoc) > 2) {
                moveToward(rc, ruinLoc);
                return;
            }

            Direction dirToRuin = rc.getLocation().directionTo(ruinLoc);
            MapLocation shouldBeMarked = ruinLoc.subtract(dirToRuin);

            // Mark the tower pattern if it is still empty
            if (rc.canSenseLocation(shouldBeMarked)) {
                if (rc.senseMapInfo(shouldBeMarked).getMark() == PaintType.EMPTY &&
                    rc.canMarkTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, ruinLoc)) {

                    rc.markTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, ruinLoc);
                    rc.setIndicatorString("Marking MONEY tower at " + ruinLoc);
                }
            }

            // Paint the needed pattern tiles
            for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                if (patternTile.getMark() != PaintType.EMPTY &&
                    patternTile.getMark() != patternTile.getPaint()) {

                    boolean useSecondaryColor = (patternTile.getMark() == PaintType.ALLY_SECONDARY);

                    if (rc.canAttack(patternTile.getMapLocation())) {
                        rc.attack(patternTile.getMapLocation(), useSecondaryColor);
                        return;
                    }
                }
            }

            // Complete the money tower if possible
            if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, ruinLoc)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, ruinLoc);
                rc.setTimelineMarker("Money Tower Built", 255, 215, 0);
                System.out.println("Built MONEY tower at " + ruinLoc);
                return;
            }

            return;
        }

        // Priority 2: paint the nearest tile that is not ally paint
        MapLocation targetTile = findNearestNonAllyTile(rc);

        if (targetTile != null) {
            if (rc.canAttack(targetTile)) {
                rc.attack(targetTile);
                rc.setIndicatorString("Painting non-ally tile");
                return;
            } else {
                moveToward(rc, targetTile);
                return;
            }
        }

        // Priority 3: paint the current tile if needed
        MapInfo currentTile = rc.senseMapInfo(rc.getLocation());
        if (!currentTile.getPaint().isAlly() && rc.canAttack(rc.getLocation())) {
            rc.attack(rc.getLocation());
            return;
        }

        // Last choice: move randomly
        randomMove(rc);
    }

    //Mopper
    public static void runMopper(RobotController rc) throws GameActionException {

        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapLocation myLoc = rc.getLocation();

        MapLocation enemyPaint = null;
        int bestDist = Integer.MAX_VALUE;

        // Find the nearest enemy paint
        for (MapInfo tile : nearbyTiles) {
            if (tile.getPaint().isEnemy()) {
                int dist = myLoc.distanceSquaredTo(tile.getMapLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    enemyPaint = tile.getMapLocation();
                }
            }
        }

        // Clean enemy paint
        if (enemyPaint != null) {
            if (rc.canAttack(enemyPaint)) {
                rc.attack(enemyPaint); rc.setIndicatorString("Cleaning enemy paint");
                return;
            } else {
                moveToward(rc, enemyPaint);
                return;
            }
        }

        // Use mop swing if possible
        for (Direction dir : directions) {
            if (rc.canMopSwing(dir)) {
                rc.mopSwing(dir); rc.setIndicatorString("Mopper swing");
                return;
            }
        }

        // Random movement if nothing to do
        randomMove(rc);

        updateEnemyRobots(rc);
    }

    public static void updateEnemyRobots(RobotController rc) throws GameActionException{
        // Sensing methods can be passed in a radius of -1 to automatically 
        // use the largest possible value.
        RobotInfo[] enemyRobots = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (enemyRobots.length != 0){
            rc.setIndicatorString("There are nearby enemy robots! Scary!");
            // Save an array of locations with enemy robots in them for possible future use.
            MapLocation[] enemyLocations = new MapLocation[enemyRobots.length];
            for (int i = 0; i < enemyRobots.length; i++){
                enemyLocations[i] = enemyRobots[i].getLocation();
            }
            RobotInfo[] allyRobots = rc.senseNearbyRobots(-1, rc.getTeam());
            // Occasionally try to tell nearby allies how many enemy robots we see.
            if (rc.getRoundNum() % 20 == 0){
                for (RobotInfo ally : allyRobots){
                    if (rc.canSendMessage(ally.location, enemyRobots.length)){
                        rc.sendMessage(ally.location, enemyRobots.length);
                    }
                }
            }
        }
    }
    // Move randomly to any possible direction
    public static void randomMove(RobotController rc) throws GameActionException {
    for (int i = 0; i < directions.length; i++) {
        Direction dir = directions[rng.nextInt(directions.length)];
        if (rc.canMove(dir)) {
            rc.move(dir);
            return;
            }
        }
    }
    // Move toward the target, or try nearby directions if blocked
    public static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        Direction dir = rc.getLocation().directionTo(target);
        if (rc.canMove(dir)) {
            rc.move(dir);
            return;
        }

        Direction left = dir.rotateLeft();
        Direction right = dir.rotateRight();

        if (rc.canMove(left)) {
            rc.move(left);
            return;
        }

        if (rc.canMove(right)) {
            rc.move(right);
            return;
        }

        randomMove(rc);
    }
    // Find the closest ruin
    public static MapLocation findNearestRuin(RobotController rc) throws GameActionException {
    MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
    MapLocation myLoc = rc.getLocation();

    MapLocation bestRuin = null;
    int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin()) {
                int dist = myLoc.distanceSquaredTo(tile.getMapLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    bestRuin = tile.getMapLocation();
                }
            }
        }
    return bestRuin;
    }
    // Find the nearest tile that is not ally-painted
    public static MapLocation findNearestNonAllyTile(RobotController rc) throws GameActionException {
    MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
    MapLocation myLoc = rc.getLocation();

    MapLocation bestTile = null;
    int bestDist = Integer.MAX_VALUE;

        for (MapInfo tile : nearbyTiles) {
            if (!tile.getPaint().isAlly()) {
                int dist = myLoc.distanceSquaredTo(tile.getMapLocation());
                if (dist < bestDist) {
                    bestDist = dist;
                    bestTile = tile.getMapLocation();
                }
            }
        }
    return bestTile;
    }
    // Try to build a robot in any adjacent direction
    public static boolean tryBuildRobotAnywhere(RobotController rc, UnitType type) throws GameActionException {
        for (Direction dir : directions) {
            MapLocation nextLoc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(type, nextLoc)) {
                rc.buildRobot(type, nextLoc);
                return true;
            }
        }
    return false;
    }
    //Splasher
    public static void runSplasher(RobotController rc) throws GameActionException {

        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapLocation bestTarget = null;
        int bestScore = -9999;

        for (MapInfo tile : nearbyTiles) {
            MapLocation loc = tile.getMapLocation();
            int score = 0;

            if (tile.getPaint().isEnemy()) score += 3;
            else if (tile.getPaint() == PaintType.EMPTY) score += 2;
            else if (tile.getPaint().isAlly()) score -= 2;

            int dist = rc.getLocation().distanceSquaredTo(loc);
            score -= dist;

            if (score > bestScore) {
                bestScore = score;
                bestTarget = loc;
            }
        }

        if (bestTarget != null) {
            if (rc.canAttack(bestTarget)) {
                rc.attack(bestTarget);
                rc.setIndicatorString("Splasher attacking best area");
                return;
            } else {
                moveToward(rc, bestTarget);
                return;
            }
        }

        randomMove(rc);
    }


}
