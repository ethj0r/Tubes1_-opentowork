package alt_bot_2;

import battlecode.common.*;
import java.util.Random;

/**
 * Strategi Greedy "Nearest First" — setiap unit selalu pilih target terdekat.
 * Soldier mencari ruin terdekat untuk dibangun, Splasher bergerak ke area non-ally terbanyak.
 * Tidak menggunakan mopper, tidak ada SRP, tidak ada komunikasi antar-unit.
 */
public class RobotPlayer {

    static final Random rng = new Random(6147);

    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    /* ---- Per-robot State (persistent antar turn) ---- */
    static Direction exploreDir = null;
    static MapLocation targetRuin = null;

    /* ---- Tower State ---- */
    static int towerSpawnCount = 0;

    /**
     * Entry point utama robot. Dispatch ke handler sesuai tipe unit setiap ronde.
     * @param rc RobotController yang mengontrol robot ini
     */
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        while (true) {
            try {
                switch (rc.getType()) {
                    case SOLDIER:  runSoldier(rc);  break;
                    case SPLASHER: runSplasher(rc); break;
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

    /**
     * Handler utama tower: serang musuh terdekat, lalu spawn unit.
     * @param rc RobotController tower
     */
    public static void runTower(RobotController rc) throws GameActionException {
        towerAttack(rc);
        towerSpawn(rc);
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
     * Spawn unit bergantian 50:50 soldier/splasher. Tidak spawn jika paint < 500
     * atau chips tidak cukup (soldier 250, splasher 400).
     * @param rc RobotController tower
     */
    static void towerSpawn(RobotController rc) throws GameActionException {
        if (!rc.isActionReady()) return;
        if (rc.getPaint() < 500) return;

        int idx = towerSpawnCount % 2;
        UnitType toBuild = (idx == 0) ? UnitType.SOLDIER : UnitType.SPLASHER;

        int chipCost = (toBuild == UnitType.SPLASHER) ? 400 : 250;
        if (rc.getChips() < chipCost) return;

        for (Direction dir : directions) {
            MapLocation spawnLoc = rc.getLocation().add(dir);
            if (rc.canBuildRobot(toBuild, spawnLoc)) {
                rc.buildRobot(toBuild, spawnLoc);
                towerSpawnCount++;
                return;
            }
        }
    }

    /**
     * Handler utama soldier: build tower di ruin terdekat → serang tower musuh →
     * gerak ke ruin/explore → paint tile saat ini.
     * @param rc RobotController soldier
     */
    public static void runSoldier(RobotController rc) throws GameActionException {
        if (tryBuildTower(rc)) return;

        if (tryAttackEnemyTower(rc)) return;

        if (targetRuin != null && rc.canSenseLocation(targetRuin)) {
            RobotInfo occupant = rc.senseRobotAtLocation(targetRuin);
            if (occupant != null) targetRuin = null;
        }

        if (targetRuin == null) targetRuin = findNearestUnbuiltRuin(rc);

        if (targetRuin != null) {
            moveToward(rc, targetRuin);
        } else {
            exploreMove(rc);
        }

        paintCurrentTile(rc);
    }

    /**
     * Bangun tower di ruin terdekat. Tipe tower deterministik berdasarkan posisi ruin:
     * (x+y)%3==0 → money tower, sisanya → paint tower (rasio ~1:2).
     * Skip ruin jika ada enemy paint yang tidak bisa di-overwrite.
     * @param rc RobotController soldier
     * @return true jika sedang dalam proses building (soldier tidak perlu aksi lain)
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
        int nearestPaintDist = Integer.MAX_VALUE;
        boolean useSecondary = false;
        boolean hasEnemyPaint = false;

        for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (patternTile.getMark() == PaintType.EMPTY) continue;
            if (patternTile.getPaint().isEnemy()) {
                hasEnemyPaint = true;
                continue;
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

        if (hasEnemyPaint && tileToPaint == null) return false;

        if (tileToPaint != null) {
            if (!rc.canAttack(tileToPaint)) moveToward(rc, tileToPaint);
            if (rc.canAttack(tileToPaint)) rc.attack(tileToPaint, useSecondary);
        } else {
            if (distToRuin > 2) moveToward(rc, ruinLoc);
        }

        if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
            rc.completeTowerPattern(towerType, ruinLoc);
            targetRuin = null;
        }

        return true;
    }

    /**
     * Serang tower musuh terdekat: approach lalu attack.
     * @param rc RobotController soldier
     * @return true jika ada tower musuh yang diserang
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

        if (!rc.canAttack(nearestTower.location)) moveToward(rc, nearestTower.location);
        if (rc.canAttack(nearestTower.location)) rc.attack(nearestTower.location);
        return true;
    }

    /**
     * Cari ruin terdekat yang belum ada tower di atasnya.
     * @param rc RobotController soldier
     * @return lokasi ruin terdekat, null jika tidak ada
     */
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

    /**
     * Handler utama splasher: splash sebelum gerak → pilih arah greedy ke area
     * non-ally terbanyak → splash lagi setelah gerak.
     * @param rc RobotController splasher
     */
    public static void runSplasher(RobotController rc) throws GameActionException {
        boolean splashed = trySplashBestTarget(rc);

        Direction bestDir = greedySplasherDirection(rc);
        if (bestDir != null && rc.canMove(bestDir)) rc.move(bestDir);

        if (!splashed) trySplashBestTarget(rc);
    }

    /**
     * Pilih arah gerak dengan tile non-ally (empty + enemy) terbanyak di radius 8.
     * Fallback ke explore direction jika semua arah score <= 0.
     * @param rc RobotController splasher
     * @return arah terbaik, null jika tidak bisa bergerak
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

        if (bestScore <= 0) return getExploreDirection(rc);
        return bestDir;
    }

    /**
     * Splash posisi yang cover tile non-ally terbanyak dalam radius 4.
     * Scoring: enemy paint +2, empty +1. Threshold: score >= 1.
     * @param rc RobotController splasher
     * @return true jika splash berhasil dilakukan
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

    /**
     * Hitung jumlah tile non-ally (empty + enemy) di sekitar posisi dalam radius 8.
     * @param rc RobotController unit
     * @param center posisi pusat pengecekan
     * @return jumlah tile non-ally
     */
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

    /**
     * Cat tile saat ini: ikuti mark jika ada, otherwise cat primary jika tile kosong.
     * @param rc RobotController soldier
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
            if (paint == PaintType.EMPTY && rc.canAttack(rc.getLocation())) {
                rc.attack(rc.getLocation());
            }
        }
    }

    /**
     * Navigasi greedy sederhana: arah langsung ke target, rotasi kiri/kanan hingga
     * 2 langkah rotasi jika blocked (total 5 arah dicoba).
     * @param rc RobotController unit yang bergerak
     * @param target lokasi tujuan
     */
    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        if (rc.getLocation().equals(target)) return;

        Direction dir = rc.getLocation().directionTo(target);

        if (rc.canMove(dir)) rc.move(dir);
        else if (rc.canMove(dir.rotateLeft())) rc.move(dir.rotateLeft());
        else if (rc.canMove(dir.rotateRight())) rc.move(dir.rotateRight());
        else if (rc.canMove(dir.rotateLeft().rotateLeft())) rc.move(dir.rotateLeft().rotateLeft());
        else if (rc.canMove(dir.rotateRight().rotateRight())) rc.move(dir.rotateRight().rotateRight());
    }

    /**
     * Tentukan arah explore: menjauhi center-of-mass sekutu non-tower supaya tersebar.
     * Fallback: random direction persistent, rotasi jika blocked.
     * @param rc RobotController unit yang sedang explore
     * @return arah explore, null jika semua arah blocked
     */
    static Direction getExploreDirection(RobotController rc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
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
     * Gerak ke arah explore. Wrapper untuk getExploreDirection + move.
     * @param rc RobotController unit yang sedang explore
     */
    static void exploreMove(RobotController rc) throws GameActionException {
        Direction dir = getExploreDirection(rc);
        if (dir != null && rc.canMove(dir)) rc.move(dir);
    }
}
