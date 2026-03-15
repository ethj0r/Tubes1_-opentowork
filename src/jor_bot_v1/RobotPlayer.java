package jor_bot_v1;
import battlecode.common.*;
import java.util.Random;

public class RobotPlayer {

    /* Global State*/
    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };
    static final Direction[] cardinals = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
    };
    static final Random rng = new Random();

    // message protocol: (msgType << 12) | (x << 6) | y
    static final int MSG_ENEMY_RUIN = 1;
    static final int MSG_ENEMY_TOWER = 2;
    static final int MSG_ENEMY_UNITS = 3;

    // tower state
    static int botSpawnedCount = 0;
    static boolean mopperRequested = false;
    static MapLocation requestedMopLoc = null;
    static MapLocation towerEnemyTowerLoc = null;
    static MapLocation towerEnemyUnitsLoc = null;

    // home/refuel system
    static MapLocation home = null;
    static int homeState = 0; // 0=ruin, 1=non-paint tower, 2=paint tower
    static boolean reachedHome = false;
    static boolean shouldGoHome = false;
    static int refuelWaitTurns = 0;
    static int refuelGiveUpRound = -100;

    // soldier ruin building
    static MapLocation targetRuin = null;
    static UnitType targetTowerType = null;
    static MapLocation enemyRuinToReport = null;
    static int ruinBuildTurns = 0;
    static MapLocation lastBlockedRuin = null;
    static int lastBlockedRound = -100;

    // global stuck detection
    static MapLocation lastSoldierLoc = null;
    static int soldierStuckTurns = 0;

    // mopper state
    static MapLocation mopTarget = null;
    static MapLocation mopperCallTarget = null;

    // enemy tower rally
    static MapLocation attackTarget = null;

    // explore target
    static MapLocation exploreTarget = null;

    // constants
    static final int UPGRADE_L2_ROUND = 150;
    static final int UPGRADE_L3_ROUND = 300;

    /**
     * Entry point utama robot. Dispatch ke handler sesuai tipe unit setiap ronde.
     * @param rc RobotController yang mengontrol robot ini
     */
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        rng.setSeed((long) rc.getID());

        while (true) {
            try {
                switch (rc.getType()) {
                    case SOLDIER:  runSoldier(rc);  break;
                    case MOPPER:   runMopper(rc);   break;
                    case SPLASHER: runSplasher(rc);  break;
                    default:       runTower(rc);     break;
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

    /**
     * Handler utama tower: attack, baca pesan, spawn unit, forward pesan.
     * @param rc RobotController tower
     */
    public static void runTower(RobotController rc) throws GameActionException {
        towerAttack(rc);
        towerReadMessages(rc);
        towerSpawn(rc);
        towerForwardMessages(rc);
    }

    /**
     * Serang musuh terdekat berdasarkan jarak squared minimum.
     * @param rc RobotController tower
     */
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

    /**
     * Baca pesan dari unit (2 ronde terakhir): decode MSG_ENEMY_RUIN/TOWER/UNITS.
     * @param rc RobotController tower
     */
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

    /**
     * Spawn unit sesuai cycle idx%6 (S,S,M,S,S,Sp) ke arah pusat peta.
     * Emergency: spawn mopper jika requested, soldier jika under attack >= 2.
     * Tidak spawn jika paint < 200 (kecuali under attack) untuk reserve refueling.
     * @param rc RobotController tower
     */
    static void towerSpawn(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;

        MapLocation myLoc = rc.getLocation();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        boolean underAttack = enemies.length > 0;

        // save paint for refueling unless under attack
        if (!underAttack && rc.getPaint() < 200) return;

        UnitType toBuild;
        if (mopperRequested) {
            toBuild = UnitType.MOPPER;
        } else if (underAttack && enemies.length >= 2) {
            toBuild = UnitType.SOLDIER;
        } else {
            // cycle: sol, sol, mop, sol, sol, splasher (nus_bot proven cycle)
            int idx = botSpawnedCount % 6;
            if (idx == 5) toBuild = UnitType.SPLASHER;
            else if (idx == 2) toBuild = UnitType.MOPPER;
            else toBuild = UnitType.SOLDIER;
        }

        // spawn toward map center
        MapLocation center = new MapLocation(rc.getMapWidth() / 2, rc.getMapHeight() / 2);
        Direction bestDir = null;
        int bestDist = Integer.MAX_VALUE;
        for (Direction dir : directions) {
            MapLocation spawnLoc = myLoc.add(dir);
            if (rc.canBuildRobot(toBuild, spawnLoc)) {
                int dist = spawnLoc.distanceSquaredTo(center);
                if (dist < bestDist) { bestDist = dist; bestDir = dir; }
            }
        }
        if (bestDir != null) {
            rc.buildRobot(toBuild, myLoc.add(bestDir));
            botSpawnedCount++;
            if (mopperRequested && toBuild == UnitType.MOPPER) {
                mopperRequested = false;
            }
        }
    }

    /**
     * Forward pesan ke unit sekitar: MSG_ENEMY_TOWER ke soldier/splasher,
     * MSG_ENEMY_RUIN/UNITS ke mopper.
     * @param rc RobotController tower
     */
    static void towerForwardMessages(RobotController rc) throws GameActionException {
        RobotInfo[] nearby = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : nearby) {
            if (ally.type.isTowerType()) continue;
            if (ally.type == UnitType.SOLDIER || ally.type == UnitType.SPLASHER) {
                if (towerEnemyTowerLoc != null) {
                    int msg = encodeLocation(MSG_ENEMY_TOWER, towerEnemyTowerLoc);
                    if (rc.canSendMessage(ally.location, msg)) {
                        rc.sendMessage(ally.location, msg);
                    }
                }
            } else if (ally.type == UnitType.MOPPER) {
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

    /**
     * Handler utama soldier dengan hierarki 14 prioritas greedy:
     * stuck detection → flee → attack rally → refuel → tower build → help pattern →
     * upgrade → sabotage → explore. Post-turn: paint tile dengan SRP color.
     * @param rc RobotController soldier
     */
    public static void runSoldier(RobotController rc) throws GameActionException {
        MapLocation myloc = rc.getLocation();
        int myPaint = rc.getPaint();
        int paintCap = rc.getType().paintCapacity;

        // STUCK DETECTION
        if (lastSoldierLoc != null && myloc.equals(lastSoldierLoc)) {
            soldierStuckTurns++;
        } else {
            soldierStuckTurns = 0;
        }
        lastSoldierLoc = myloc;
        if (soldierStuckTurns >= 3) {
            targetRuin = null; targetTowerType = null;
            attackTarget = null;
            ruinBuildTurns = 0;
            soldierStuckTurns = 0;
            exploreTarget = null;
            explore(rc);
            soldierPostTurn(rc);
            return;
        }

        // emergency: flee enemy paint when very low
        if (myPaint <= paintCap / 8 && rc.isMovementReady()) {
            MapInfo here = rc.senseMapInfo(myloc);
            if (here.getPaint().isEnemy()) {
                fleeToAllyPaint(rc);
            }
        }

        setHome(rc);
        spotAndReportEnemies(rc);
        readAttackMessages(rc);

        // rally to attack enemy tower
        if (attackTarget != null) {
            if (myloc.distanceSquaredTo(attackTarget) > 100) {
                attackTarget = null;
            } else if (rc.canSenseLocation(attackTarget)) {
                RobotInfo etower = rc.senseRobotAtLocation(attackTarget);
                if (etower == null || etower.team == rc.getTeam()) {
                    attackTarget = null;
                } else {
                    if (rc.canAttack(attackTarget)) rc.attack(attackTarget);
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

        // paint management
        if (myPaint > paintCap / 2) {
            shouldGoHome = false;
        } else if (myPaint <= paintCap / 3 && !shouldGoHome
                   && rc.getRoundNum() - refuelGiveUpRound >= 10) {
            shouldGoHome = true;
            reachedHome = false;
            refuelWaitTurns = 0;
        }

        // low-paint tower build
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

        // taint enemy ruins: paint one tile to block their construction
        trySabotageEnemyRuin(rc);

        // explore
        explore(rc);
        soldierPostTurn(rc);
    }

    /**
     * Sabotase ruin musuh: cat satu tile di area ruin yang hanya punya enemy paint
     * (tanpa ally paint) untuk memblokir konstruksi tower lawan.
     * @param rc RobotController soldier
     */
    static void trySabotageEnemyRuin(RobotController rc) throws GameActionException {
        if (!rc.isActionReady() || rc.getPaint() < 60) return;
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        for (MapLocation ruin : ruins) {
            RobotInfo occupant = rc.senseRobotAtLocation(ruin);
            if (occupant != null) continue; // already built
            // skip ruins we're targeting
            if (targetRuin != null && ruin.equals(targetRuin)) continue;
            // check if ruin pattern area has enemy paint but no ally paint (enemy side)
            MapInfo[] area = rc.senseNearbyMapInfos(ruin, 8);
            boolean hasAllyPaint = false;
            boolean hasEnemyPaint = false;
            MapLocation emptyTile = null;
            for (MapInfo tile : area) {
                PaintType p = tile.getPaint();
                if (p.isAlly()) hasAllyPaint = true;
                else if (p.isEnemy()) hasEnemyPaint = true;
                else if (p == PaintType.EMPTY && emptyTile == null
                         && !tile.isWall() && !tile.hasRuin()
                         && rc.canAttack(tile.getMapLocation())) {
                    emptyTile = tile.getMapLocation();
                }
            }
            // only taint if enemy side (has enemy paint, no ally paint)
            if (hasEnemyPaint && !hasAllyPaint && emptyTile != null) {
                boolean useSecondary = srpUseSecondary(emptyTile.x, emptyTile.y);
                rc.attack(emptyTile, useSecondary);
                return;
            }
        }
    }

    /**
     * Post-turn soldier: cat tile saat ini dengan warna SRP yang benar (termasuk overwrite
     * enemy paint), lalu coba selesaikan tower pattern dan SRP pattern terdekat.
     * @param rc RobotController soldier
     */
    static void soldierPostTurn(RobotController rc) throws GameActionException {
        MapLocation myloc = rc.getLocation();
        if (rc.canAttack(myloc) && rc.getPaint() >= 50) {
            MapInfo mi = rc.senseMapInfo(myloc);
            PaintType p = mi.getPaint();
            if (mi.isPassable()) {
                if (p == PaintType.EMPTY || p.isEnemy()) {
                    boolean useSecondary = srpUseSecondary(myloc.x, myloc.y);
                    PaintType mark = mi.getMark();
                    if (mark != PaintType.EMPTY) {
                        useSecondary = (mark == PaintType.ALLY_SECONDARY);
                    }
                    rc.attack(myloc, useSecondary);
                }
            }
        }

        // try complete tower patterns
        if (targetRuin != null) {
            if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetRuin)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetRuin);
                targetRuin = null; targetTowerType = null;
            } else if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, targetRuin)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_MONEY_TOWER, targetRuin);
                targetRuin = null; targetTowerType = null;
            } else if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_DEFENSE_TOWER, targetRuin)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_DEFENSE_TOWER, targetRuin);
                targetRuin = null; targetTowerType = null;
            }
        }

        // complete SRP patterns nearby
        checkCompleteSRP(rc);
    }

    /**
     * Cek apakah soldier punya cukup paint untuk menyelesaikan tower pattern di ruin.
     * @param rc RobotController soldier
     * @param ruin lokasi ruin yang akan dibangun
     * @return true jika paint cukup untuk mengecat semua tile pattern yang belum sesuai
     */
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

    /**
     * Bangun tower di ruin: mark pattern, cat tile terdekat yang belum sesuai,
     * complete jika pattern lengkap. Timeout 30 ronde, congestion avoidance via lower-ID priority.
     * @param rc RobotController soldier
     * @param ruin lokasi ruin target
     */
    static void doTowerBuild(RobotController rc, MapLocation ruin) throws GameActionException {
        ruinBuildTurns++;
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
            RobotInfo[] nearRuin = rc.senseNearbyRobots(ruin, 8, rc.getTeam());
            for (RobotInfo ally : nearRuin) {
                if (ally.type == UnitType.SOLDIER && ally.ID < rc.getID()) {
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
            if (rc.canCompleteTowerPattern(towerType, ruin)) {
                rc.completeTowerPattern(towerType, ruin);
                targetRuin = null; targetTowerType = null;
                ruinBuildTurns = 0;
            }
            return;
        }

        if (rc.canCompleteTowerPattern(towerType, ruin)) {
            rc.completeTowerPattern(towerType, ruin);
            targetRuin = null; targetTowerType = null;
            ruinBuildTurns = 0;
            return;
        }

        if (distToRuin > 2) {
            moveToward(rc, ruin);
        } else {
            targetRuin = null; targetTowerType = null;
            ruinBuildTurns = 0;
        }
    }

    /**
     * Tentukan tipe tower: 2 paint tower pertama untuk refueling, lalu 1:1 paint:money.
     * @param rc RobotController soldier
     * @param loc lokasi ruin
     * @return UnitType tower yang akan dibangun
     */
    static UnitType getTowerToBuild(RobotController rc, MapLocation loc) throws GameActionException {
        int total = rc.getNumberTowers();
        if (total < 2) return UnitType.LEVEL_ONE_PAINT_TOWER;
        if (total % 2 == 0) return UnitType.LEVEL_ONE_MONEY_TOWER;
        return UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    /**
     * Cari ruin terdekat yang belum dibangun, hindari ruin dekat enemy tower dan
     * ruin yang sudah ada soldier dengan ID lebih rendah (congestion avoidance).
     * @param rc RobotController soldier
     * @return lokasi ruin terdekat, null jika tidak ada
     */
    static MapLocation findNearestUnbuiltRuin(RobotController rc) throws GameActionException {
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation closest = null;
        int closestDist = Integer.MAX_VALUE;
        for (MapLocation ruin : ruins) {
            RobotInfo occupant = rc.senseRobotAtLocation(ruin);
            if (occupant != null && occupant.getType().isTowerType()) continue;
            if (lastBlockedRuin != null && ruin.equals(lastBlockedRuin)
                && rc.getRoundNum() - lastBlockedRound < 15) continue;
            boolean nearEnemyTower = false;
            for (RobotInfo enemy : enemies) {
                if (enemy.type.isTowerType() && ruin.distanceSquaredTo(enemy.location) <= 36) {
                    nearEnemyTower = true;
                    break;
                }
            }
            if (nearEnemyTower) continue;
            RobotInfo[] nearRuin = rc.senseNearbyRobots(ruin, 8, rc.getTeam());
            boolean lowerIdPresent = false;
            for (RobotInfo ally : nearRuin) {
                if (ally.type == UnitType.SOLDIER && ally.ID < rc.getID()) {
                    lowerIdPresent = true;
                    break;
                }
            }
            if (lowerIdPresent) continue;
            int dist = rc.getLocation().distanceSquaredTo(ruin);
            if (dist < closestDist) {
                closestDist = dist;
                closest = ruin;
            }
        }
        return closest;
    }

    /**
     * Cat tile tower pattern terdekat (radius 4) yang belum sesuai mark-nya.
     * @param rc RobotController soldier
     * @return true jika berhasil mengecat satu tile
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
                boolean sec = (mark == PaintType.ALLY_SECONDARY);
                rc.attack(loc, sec);
                return true;
            }
        }
        return false;
    }

    /**
     * Upgrade tower sekutu terdekat (jarak² <= 2) jika ronde sudah mencapai threshold.
     * L2 di ronde >= 150, L3 di ronde >= 300.
     * @param rc RobotController soldier
     */
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

    /**
     * Tentukan warna SRP berdasarkan pola 4x4 repeating grid.
     * @param x koordinat x tile
     * @param y koordinat y tile
     * @return true jika tile harus dicat SECONDARY, false untuk PRIMARY
     */
    static boolean srpUseSecondary(int x, int y) {
        int mx = x & 3, my = y & 3;
        if (mx == 0 && my != 2) return true;
        if (my == 0 && mx != 2) return true;
        if (mx == 2 && my == 2) return true;
        return false;
    }

    /**
     * Selesaikan SRP pattern terdekat secara oportunistik jika bytecode cukup.
     * @param rc RobotController unit yang sedang aktif
     */
    static void checkCompleteSRP(RobotController rc) throws GameActionException {
        if (Clock.getBytecodesLeft() < 2000) return;
        MapInfo[] nearby = rc.senseNearbyMapInfos(rc.getLocation(), 8);
        for (MapInfo tile : nearby) {
            if (Clock.getBytecodesLeft() < 1000) break;
            if (tile.isResourcePatternCenter()) {
                if (rc.canCompleteResourcePattern(tile.getMapLocation())) {
                    rc.completeResourcePattern(tile.getMapLocation());
                    return;
                }
            }
        }
    }

    /**
     * Handler utama splasher: refuel → attack rally → micro → splash attack →
     * movement ke enemy/empty paint → frontier push. Setiap aksi diakhiri checkCompleteSRP.
     * @param rc RobotController splasher
     */
    public static void runSplasher(RobotController rc) throws GameActionException {
        setHome(rc);
        spotAndReportEnemies(rc);
        readAttackMessages(rc);
        int myPaint = rc.getPaint();
        int paintCap = rc.getType().paintCapacity;

        if (myPaint > paintCap / 2) {
            shouldGoHome = false;
        } else if (myPaint <= paintCap / 3 && !shouldGoHome
                   && rc.getRoundNum() - refuelGiveUpRound >= 10) {
            shouldGoHome = true;
            reachedHome = false;
            refuelWaitTurns = 0;
        }

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
                    checkCompleteSRP(rc);
                    return;
                }
            } else {
                moveToward(rc, attackTarget);
                splasherDoAttack(rc);
                checkCompleteSRP(rc);
                return;
            }
        }

        if (shouldGoHome) {
            refuel(rc);
            checkCompleteSRP(rc);
            return;
        }

        if (shouldSplasherMicro(rc)) {
            splasherMicro(rc);
            splasherDoAttack(rc);
            checkCompleteSRP(rc);
            return;
        }

        splasherDoAttack(rc);

        RobotInfo[] nearAllies = rc.senseNearbyRobots(8, rc.getTeam());
        int nearbySplashers = 0;
        for (RobotInfo a : nearAllies) {
            if (a.type == UnitType.SPLASHER) nearbySplashers++;
        }

        MapLocation enemyPaint = findNearestEnemyPaint(rc);
        if (enemyPaint != null && nearbySplashers < 3) {
            moveToward(rc, enemyPaint);
        } else {
            MapLocation emptyPaint = findNearestEmptyPaint(rc);
            if (emptyPaint != null && nearbySplashers < 3) {
                moveToward(rc, emptyPaint);
            } else {
                splasherPushFrontier(rc);
            }
        }

        splasherDoAttack(rc);
        checkCompleteSRP(rc);
    }

    /**
     * Cek apakah ada musuh non-tower dengan paint > 0 dalam jarak 20 untuk micro.
     * @param rc RobotController splasher
     * @return true jika ada target micro
     */
    static boolean shouldSplasherMicro(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(20, rc.getTeam().opponent());
        for (RobotInfo e : enemies) {
            if (!e.type.isTowerType() && e.paintAmount > 0) return true;
        }
        return false;
    }

    /**
     * Splasher micro: bergerak mendekati musuh non-tower terdekat.
     * @param rc RobotController splasher
     */
    static void splasherMicro(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(20, rc.getTeam().opponent());
        if (enemies.length == 0) return;
        RobotInfo target = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo e : enemies) {
            if (e.type.isTowerType()) continue;
            int d = rc.getLocation().distanceSquaredTo(e.location);
            if (d < bestDist) { bestDist = d; target = e; }
        }
        if (target != null) moveToward(rc, target.location);
    }

    /**
     * Splash attack: evaluasi semua tile attackable, scoring 3x3 area (enemy +5, empty +1).
     * Attack jika score >= 3 dan paint >= 1/4 capacity.
     * @param rc RobotController splasher
     */
    static void splasherDoAttack(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        if (rc.getPaint() < rc.getType().paintCapacity / 4) return;

        MapLocation bestLoc = null;
        int bestScore = 0;

        MapInfo[] attackable = rc.senseNearbyMapInfos(rc.getType().actionRadiusSquared);
        for (MapInfo tile : attackable) {
            MapLocation loc = tile.getMapLocation();
            if (!rc.canAttack(loc)) continue;
            if (tile.isWall() || tile.hasRuin()) continue;

            int score = 0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    MapLocation adj = new MapLocation(loc.x + dx, loc.y + dy);
                    if (rc.canSenseLocation(adj)) {
                        MapInfo mi = rc.senseMapInfo(adj);
                        if (mi.isWall() || mi.hasRuin()) continue;
                        PaintType p = mi.getPaint();
                        if (p.isEnemy()) score += 5;
                        else if (p == PaintType.EMPTY) score += 1;
                    }
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestLoc = loc;
            }
        }

        if (bestLoc != null && bestScore >= 3 && rc.canAttack(bestLoc)) {
            rc.attack(bestLoc);
        }
    }

    /**
     * Dorong frontier: pilih arah dengan tile empty+enemy terbanyak (radius 8) untuk ekspansi.
     * @param rc RobotController splasher
     */
    static void splasherPushFrontier(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;
        MapLocation myloc = rc.getLocation();

        Direction bestDir = null;
        int bestEmpty = -1;
        for (Direction dir : Direction.allDirections()) {
            if (dir == Direction.CENTER) continue;
            if (!rc.canMove(dir)) continue;
            MapLocation next = myloc.add(dir);
            int emptyCount = 0;
            for (MapInfo tile : rc.senseNearbyMapInfos(next, 8)) {
                PaintType p = tile.getPaint();
                if (p == PaintType.EMPTY || p.isEnemy()) emptyCount++;
            }
            if (emptyCount > bestEmpty) {
                bestEmpty = emptyCount;
                bestDir = dir;
            }
        }
        if (bestDir != null && bestEmpty > 0) {
            rc.move(bestDir);
        } else {
            explore(rc);
        }
    }

    /**
     * Handler utama mopper: flee → attack rally → refuel (paint tower only) →
     * micro vs enemy units → clear tainted ruins → mop target → explore.
     * @param rc RobotController mopper
     */
    public static void runMopper(RobotController rc) throws GameActionException {
        int myPaint = rc.getPaint();
        int paintCap = rc.getType().paintCapacity;
        MapInfo[] near = rc.senseNearbyMapInfos();

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

        if (myPaint > paintCap / 3) {
            shouldGoHome = false;
        } else if (myPaint <= paintCap / 5 && !shouldGoHome
                   && rc.getRoundNum() - refuelGiveUpRound >= 15) {
            shouldGoHome = true;
            reachedHome = false;
        }

        if (shouldGoHome && homeState == 2) {
            refuel(rc);
            tryMopSwing(rc);
            mopperPostTurn(rc);
            return;
        }

        if (shouldMopperMicro(rc)) {
            mopperAttackMicro(rc);
            mopperPostTurn(rc);
            return;
        }

        if (rc.getNumberTowers() < 25) {
            MapLocation taintedRuin = findTaintedRuin(rc);
            if (taintedRuin != null) {
                handleTaintedRuin(rc, taintedRuin);
                mopperPostTurn(rc);
                return;
            }
        }

        if (mopTarget != null) {
            handleMopTarget(rc, mopTarget);
            mopperPostTurn(rc);
            return;
        }

        if (mopperCallTarget != null) {
            if (rc.getLocation().distanceSquaredTo(mopperCallTarget) <= 8) {
                mopperCallTarget = null;
            } else {
                moveToward(rc, mopperCallTarget);
                tryMopSwing(rc);
                mopperPostTurn(rc);
                return;
            }
        }

        explore(rc);
        mopperPostTurn(rc);
    }

    /**
     * Post-turn mopper: attack enemy paint di 8 tile adjacent, fallback ke tile saat ini.
     * @param rc RobotController mopper
     */
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
        if (rc.canSenseLocation(myloc)) {
            MapInfo mi = rc.senseMapInfo(myloc);
            if (mi.getPaint().isEnemy() && rc.canAttack(myloc)) {
                rc.attack(myloc);
            }
        }
    }

    /**
     * Baca pesan MSG_ENEMY_RUIN dari tower untuk mendapatkan lokasi ruin yang perlu di-mop.
     * @param rc RobotController mopper
     */
    static void readMopperMessages(RobotController rc) throws GameActionException {
        int prevRound = rc.getRoundNum() - 1;
        if (prevRound < 0) return;
        Message[] msgs = rc.readMessages(prevRound);
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
     * Cari enemy paint terdekat sebagai mop target, hindari area dalam range enemy tower.
     * @param rc RobotController mopper
     * @param near MapInfo[] tiles di sekitar mopper
     */
    static void computeMopTarget(RobotController rc, MapInfo[] near) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo enemyTower = null;
        for (RobotInfo enemy : enemies) {
            if (enemy.type.isTowerType()) { enemyTower = enemy; break; }
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
                if (enemyTower != null && loc.distanceSquaredTo(enemyTower.location) <= enemyTower.type.actionRadiusSquared + 20)
                    continue;
                closest = d;
                bestLoc = loc;
            }
        }

        if (mopTarget != null && rc.canSenseLocation(mopTarget)) {
            MapInfo mi = rc.senseMapInfo(mopTarget);
            if (mi.getPaint().isEnemy()) return;
            mopTarget = null;
        }
        if (bestLoc != null) mopTarget = bestLoc;
    }

    /**
     * Bergerak ke mop target: jika jauh moveToward, jika dekat pilih arah dengan enemy paint terbanyak.
     * @param rc RobotController mopper
     * @param target lokasi enemy paint target
     */
    static void handleMopTarget(RobotController rc, MapLocation target) throws GameActionException {
        if (rc.getLocation().distanceSquaredTo(target) >= 9) {
            moveToward(rc, target);
            return;
        }
        int bestDist = Integer.MAX_VALUE;
        int bestScore = -1;
        Direction bestDir = null;
        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            MapLocation nloc = rc.getLocation().add(dir);
            MapInfo mi = rc.senseMapInfo(nloc);
            int score;
            PaintType p = mi.getPaint();
            if (p.isEnemy()) score = 3;
            else if (p == PaintType.EMPTY) score = 1;
            else score = 0;
            int dist = nloc.distanceSquaredTo(target);
            if (score > bestScore || (score == bestScore && dist < bestDist)) {
                bestScore = score; bestDist = dist; bestDir = dir;
            }
        }
        if (bestDir != null && rc.canMove(bestDir)) rc.move(bestDir);
    }

    /**
     * Cek apakah ada musuh non-tower dengan paint > 0 untuk mopper micro.
     * @param rc RobotController mopper
     * @return true jika ada target micro
     */
    static boolean shouldMopperMicro(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        for (RobotInfo enemy : enemies) {
            if (!enemy.type.isTowerType() && enemy.paintAmount > 0) return true;
        }
        return false;
    }

    /**
     * Mopper micro: mop swing, lalu chase musuh non-tower terdekat yang punya paint.
     * @param rc RobotController mopper
     */
    static void mopperAttackMicro(RobotController rc) throws GameActionException {
        tryMopSwing(rc);
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo target = null;
        int bestDist = Integer.MAX_VALUE;
        for (RobotInfo enemy : enemies) {
            if (enemy.type.isTowerType()) continue;
            if (enemy.paintAmount <= 0) continue;
            int d = rc.getLocation().distanceSquaredTo(enemy.location);
            if (d < bestDist) { bestDist = d; target = enemy; }
        }
        if (target != null) moveToward(rc, target.location);
        tryMopSwing(rc);
    }

    /**
     * Cari ruin yang belum dibangun tapi area-nya punya enemy paint (tainted).
     * @param rc RobotController mopper
     * @return lokasi ruin tainted, null jika tidak ada
     */
    static MapLocation findTaintedRuin(RobotController rc) throws GameActionException {
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        for (MapLocation ruin : ruins) {
            RobotInfo occupant = rc.senseRobotAtLocation(ruin);
            if (occupant != null && occupant.getType().isTowerType()) continue;
            MapInfo[] area = rc.senseNearbyMapInfos(ruin, 8);
            for (MapInfo tile : area) {
                if (tile.getPaint().isEnemy()) return ruin;
            }
        }
        return null;
    }

    /**
     * Bersihkan enemy paint terdekat di area ruin yang tainted.
     * @param rc RobotController mopper
     * @param ruin lokasi ruin yang area-nya terkontaminasi
     */
    static void handleTaintedRuin(RobotController rc, MapLocation ruin) throws GameActionException {
        MapInfo[] area = rc.senseNearbyMapInfos(ruin, 8);
        MapLocation dirtyTile = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapInfo tile : area) {
            if (!tile.getPaint().isEnemy()) continue;
            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < bestDist) { bestDist = dist; dirtyTile = loc; }
        }
        if (dirtyTile != null) {
            if (rc.getLocation().distanceSquaredTo(dirtyTile) > 2)
                moveToward(rc, dirtyTile);
            if (rc.canAttack(dirtyTile)) rc.attack(dirtyTile);
        }
    }

    /**
     * Mop swing ke arah cardinal dengan hit count musuh tertinggi.
     * @param rc RobotController mopper
     * @return true jika swing berhasil dilakukan
     */
    static boolean tryMopSwing(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return false;
        Direction bestSwing = null;
        int bestHits = 0;
        for (Direction dir : cardinals) {
            if (!rc.canMopSwing(dir)) continue;
            int hits = countEnemiesInSwingDir(rc, dir);
            if (hits > bestHits) { bestHits = hits; bestSwing = dir; }
        }
        if (bestSwing != null && bestHits > 0) {
            rc.mopSwing(bestSwing);
            return true;
        }
        return false;
    }

    /**
     * Hitung jumlah musuh yang terkena mop swing di arah tertentu (step1 + step2, radius 2).
     * @param rc RobotController mopper
     * @param dir arah cardinal swing
     * @return jumlah musuh yang terkena
     */
    static int countEnemiesInSwingDir(RobotController rc, Direction dir) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation myLoc = rc.getLocation();
        MapLocation step1 = myLoc.add(dir);
        MapLocation step2 = step1.add(dir);
        int count = 0;
        for (RobotInfo enemy : enemies) {
            MapLocation eLoc = enemy.location;
            if (eLoc.distanceSquaredTo(step1) <= 2 || eLoc.distanceSquaredTo(step2) <= 2)
                count++;
        }
        return count;
    }

    /**
     * Klasifikasi state tower: 0=ruin kosong, 1=non-paint tower, 2=paint tower.
     * @param robot RobotInfo tower yang dicek, null jika tidak ada
     * @return integer state tower (0, 1, atau 2)
     */
    static int getTowerState(RobotInfo robot) {
        if (robot == null) return 0;
        switch (robot.type) {
            case LEVEL_ONE_PAINT_TOWER:
            case LEVEL_TWO_PAINT_TOWER:
            case LEVEL_THREE_PAINT_TOWER:
                return 2;
            default:
                return (robot.paintAmount > 0) ? 2 : 1;
        }
    }

    /**
     * Update referensi home tower: prioritaskan paint tower (state 2) terdekat.
     * @param rc RobotController unit
     */
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

    /**
     * Refuel di home tower: bergerak ke tower, tunggu max 6 ronde, transfer paint.
     * Give up jika tower paint < 10 atau timeout, cooldown 10 ronde sebelum retry.
     * @param rc RobotController unit yang butuh refuel
     */
    static void refuel(RobotController rc) throws GameActionException {
        if (home == null) {
            setHome(rc);
            if (home == null) { explore(rc); return; }
        }
        if (homeState != 2) {
            home = null; homeState = 0;
            setHome(rc);
            if (home == null || homeState != 2) { explore(rc); return; }
        }
        int distToHome = rc.getLocation().distanceSquaredTo(home);
        if (distToHome > 2) { moveToward(rc, home); return; }

        refuelWaitTurns++;
        if (refuelWaitTurns > 6) {
            shouldGoHome = false; refuelWaitTurns = 0;
            refuelGiveUpRound = rc.getRoundNum();
            home = null; homeState = 0;
            return;
        }
        RobotInfo r = rc.senseRobotAtLocation(home);
        if (r == null) {
            home = null; homeState = 0; shouldGoHome = false; refuelWaitTurns = 0;
            return;
        }
        if (r.paintAmount < 10) {
            home = null; homeState = 0; refuelWaitTurns = 0;
            return;
        }
        int amt = Math.max(rc.getPaint() - rc.getType().paintCapacity, -r.paintAmount);
        if (rc.canTransferPaint(home, amt)) {
            rc.transferPaint(home, amt);
            shouldGoHome = false; refuelWaitTurns = 0;
        }
    }

    /**
     * Explore peta dengan ally repulsion (jauhi COM jika >= 3 sekutu dekat)
     * dan bias ke tepi peta. Fallback: target random dengan jitter.
     * @param rc RobotController unit yang sedang explore
     */
    static void explore(RobotController rc) throws GameActionException {
        if (!rc.isMovementReady()) return;

        MapLocation myloc = rc.getLocation();
        int mapW = rc.getMapWidth();
        int mapH = rc.getMapHeight();

        // ally repulsion
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

        if (exploreTarget == null || myloc.distanceSquaredTo(exploreTarget) <= 5) {
            int cx = mapW / 2, cy = mapH / 2;
            int dx = myloc.x - cx;
            int dy = myloc.y - cy;
            int tx = Math.max(0, Math.min(mapW - 1, myloc.x + dx + rng.nextInt(11) - 5));
            int ty = Math.max(0, Math.min(mapH - 1, myloc.y + dy + rng.nextInt(11) - 5));
            if (myloc.x <= 3 || myloc.x >= mapW - 4 || myloc.y <= 3 || myloc.y >= mapH - 4) {
                tx = rng.nextInt(mapW);
                ty = rng.nextInt(mapH);
            }
            exploreTarget = new MapLocation(tx, ty);
        }
        moveToward(rc, exploreTarget);
    }

    /* ---- Bug2 Navigation State ---- */
    static boolean bugFollowing = false;
    static Direction bugWallDir = null;
    static int bugStartDist = Integer.MAX_VALUE;
    static int bugBestDist = Integer.MAX_VALUE;
    static int bugTurns = 0;
    static boolean bugRotateRight = true;
    static MapLocation lastMoveTarget = null;
    static MapLocation bugPrevLoc = null;
    static int stuckCount = 0;

    /**
     * Navigasi hybrid greedy + Bug2 wall-following menuju target.
     * Greedy phase: pilih arah terdekat ke target dengan soft enemy tower avoidance.
     * Bug2 phase: aktif jika greedy gagal, wall-following dengan flip arah otomatis.
     * @param rc RobotController unit yang bergerak
     * @param target lokasi tujuan
     */
    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        if (rc.getLocation().equals(target)) return;

        if (lastMoveTarget == null || !lastMoveTarget.equals(target)) {
            bugFollowing = false;
            bugRotateRight = rng.nextBoolean();
            lastMoveTarget = target;
            stuckCount = 0;
        }

        MapLocation myLoc = rc.getLocation();
        int curDist = myLoc.distanceSquaredTo(target);

        if (bugFollowing && curDist < bugBestDist) bugFollowing = false;
        if (bugFollowing && bugTurns > 16) {
            bugFollowing = false; bugRotateRight = !bugRotateRight;
        }
        if (bugFollowing && bugTurns > 8 && curDist >= bugBestDist) {
            bugRotateRight = !bugRotateRight; bugTurns = 0; bugBestDist = curDist;
        }

        if (!bugFollowing) {
            Direction bestDir = null;
            int bestDist = curDist;
            int bestPaintScore = Integer.MIN_VALUE;

            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
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

                for (RobotInfo enemy : enemies) {
                    if (enemy.type.isTowerType()) {
                        int nextDistToTower = next.distanceSquaredTo(enemy.location);
                        if (nextDistToTower <= enemy.type.actionRadiusSquared) {
                            if (!alreadyInRange) paintScore -= 5;
                            int curDistToTower = myLoc.distanceSquaredTo(enemy.location);
                            if (nextDistToTower < curDistToTower) paintScore -= 3;
                        } else if (alreadyInRange) {
                            paintScore += 3;
                        }
                    }
                }

                if (dist < bestDist || (dist == bestDist && paintScore > bestPaintScore)) {
                    bestDist = dist; bestDir = dir; bestPaintScore = paintScore;
                }
            }
            if (bestDir != null) {
                rc.move(bestDir);
                stuckCount = 0;
                return;
            }
            bugFollowing = true;
            bugWallDir = myLoc.directionTo(target);
            bugStartDist = curDist;
            bugBestDist = curDist;
            bugTurns = 0;
            bugPrevLoc = myLoc;
        }

        if (bugFollowing) {
            bugTurns++;
            if (curDist < bugBestDist) bugBestDist = curDist;

            if (bugPrevLoc != null && myLoc.equals(bugPrevLoc)) {
                stuckCount++;
                if (stuckCount > 2) { bugRotateRight = !bugRotateRight; stuckCount = 0; }
            } else {
                stuckCount = 0;
            }
            bugPrevLoc = myLoc;

            Direction toward = myLoc.directionTo(target);
            if (rc.canMove(toward)) { rc.move(toward); bugFollowing = false; return; }

            Direction left1 = toward.rotateLeft();
            Direction right1 = toward.rotateRight();
            if (rc.canMove(left1) && myLoc.add(left1).distanceSquaredTo(target) < curDist) {
                rc.move(left1); bugFollowing = false; return;
            }
            if (rc.canMove(right1) && myLoc.add(right1).distanceSquaredTo(target) < curDist) {
                rc.move(right1); bugFollowing = false; return;
            }

            Direction dir = toward;
            for (int i = 0; i < 8; i++) {
                dir = bugRotateRight ? dir.rotateRight() : dir.rotateLeft();
                if (rc.canMove(dir)) {
                    rc.move(dir);
                    bugWallDir = bugRotateRight
                        ? dir.rotateLeft().rotateLeft()
                        : dir.rotateRight().rotateRight();
                    return;
                }
            }
            bugRotateRight = !bugRotateRight;
            bugFollowing = false;
        }
    }

    /**
     * Encode tipe pesan dan lokasi ke integer 16-bit: (msgType << 12) | (x << 6) | y.
     * @param msgType tipe pesan (MSG_ENEMY_RUIN, MSG_ENEMY_TOWER, MSG_ENEMY_UNITS)
     * @param loc lokasi yang di-encode (max 64x64)
     * @return integer encoded message
     */
    static int encodeLocation(int msgType, MapLocation loc) {
        return (msgType << 12) | (loc.x << 6) | loc.y;
    }

    /**
     * Deteksi musuh dan laporkan ke tower terdekat: MSG_ENEMY_TOWER untuk tower musuh,
     * MSG_ENEMY_UNITS untuk unit musuh non-tower.
     * @param rc RobotController unit pelapor
     */
    static void spotAndReportEnemies(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        boolean foundTower = false, foundUnits = false;
        MapLocation towerLoc = null, unitLoc = null;

        for (RobotInfo enemy : enemies) {
            if (enemy.type.isTowerType() && !foundTower) {
                foundTower = true; towerLoc = enemy.location; attackTarget = towerLoc;
            } else if (!enemy.type.isTowerType() && !foundUnits) {
                foundUnits = true; unitLoc = enemy.location;
            }
        }

        if (!foundTower && !foundUnits) return;

        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (!ally.type.isTowerType()) continue;
            if (foundTower) {
                int msg = encodeLocation(MSG_ENEMY_TOWER, towerLoc);
                if (rc.canSendMessage(ally.location, msg)) rc.sendMessage(ally.location, msg);
            }
            if (foundUnits) {
                int msg = encodeLocation(MSG_ENEMY_UNITS, unitLoc);
                if (rc.canSendMessage(ally.location, msg)) rc.sendMessage(ally.location, msg);
            }
            return;
        }
    }

    /**
     * Baca pesan attack dari tower (2 ronde terakhir): MSG_ENEMY_TOWER → attackTarget,
     * MSG_ENEMY_UNITS → mopperCallTarget.
     * @param rc RobotController unit penerima
     */
    static void readAttackMessages(RobotController rc) throws GameActionException {
        for (int r = rc.getRoundNum() - 1; r <= rc.getRoundNum(); r++) {
            if (r < 0) continue;
            Message[] msgs = rc.readMessages(r);
            for (Message msg : msgs) {
                int data = msg.getBytes();
                int type = (data >> 12) & 0xF;
                int x = (data >> 6) & 0x3F;
                int y = data & 0x3F;
                if (type == MSG_ENEMY_TOWER) attackTarget = new MapLocation(x, y);
                else if (type == MSG_ENEMY_UNITS) mopperCallTarget = new MapLocation(x, y);
            }
        }
    }

    /**
     * Kirim MSG_ENEMY_RUIN ke tower terdekat untuk meminta mopper membersihkan ruin.
     * @param rc RobotController soldier pengirim
     * @param ruinLoc lokasi ruin yang punya enemy paint
     * @return true jika pesan berhasil dikirim
     */
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

    /**
     * Emergency flee: pindah ke tile ally paint terdekat saat paint kritis di enemy paint.
     * Scoring: ally paint +3, empty +1, enemy -1.
     * @param rc RobotController unit yang melarikan diri
     */
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
            else score = -1;
            if (score > bestScore) { bestScore = score; bestDir = dir; }
        }
        if (bestDir != null && bestScore > -1) rc.move(bestDir);
    }

    /**
     * Cari tile enemy paint terdekat yang tidak berada dalam range enemy tower.
     * @param rc RobotController unit pencari
     * @return lokasi enemy paint terdekat, null jika tidak ada
     */
    static MapLocation findNearestEnemyPaint(RobotController rc) throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        MapLocation[] enemyTowers = new MapLocation[enemies.length];
        int towerCount = 0;
        for (RobotInfo enemy : enemies) {
            if (enemy.type.isTowerType()) enemyTowers[towerCount++] = enemy.location;
        }
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapInfo tile : tiles) {
            if (!tile.getPaint().isEnemy()) continue;
            MapLocation loc = tile.getMapLocation();
            boolean nearEnemyTower = false;
            for (int i = 0; i < towerCount; i++) {
                if (loc.distanceSquaredTo(enemyTowers[i]) <= 20) { nearEnemyTower = true; break; }
            }
            if (nearEnemyTower) continue;
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < bestDist) { bestDist = dist; best = loc; }
        }
        return best;
    }

    /**
     * Cari tile kosong (PaintType.EMPTY) terdekat yang passable.
     * @param rc RobotController unit pencari
     * @return lokasi tile kosong terdekat, null jika tidak ada
     */
    static MapLocation findNearestEmptyPaint(RobotController rc) throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos();
        MapLocation best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MapInfo tile : tiles) {
            if (tile.getPaint() != PaintType.EMPTY) continue;
            if (tile.isWall() || tile.hasRuin()) continue;
            MapLocation loc = tile.getMapLocation();
            int dist = rc.getLocation().distanceSquaredTo(loc);
            if (dist < bestDist) { bestDist = dist; best = loc; }
        }
        return best;
    }
}
