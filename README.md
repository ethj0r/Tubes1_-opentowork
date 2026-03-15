# Tubes1_-opentowork — Battlecode 2025

Bot player untuk kompetisi Battlecode 2025 menggunakan strategi Greedy. Terdapat tiga implementasi bot dengan heuristic greedy yang berbeda-beda.

## Algoritma Greedy

### `main_bot` — Economy-First Tower Expansion
Strategi greedy yang memaksimalkan jumlah menara terlebih dahulu (target 25 menara) dengan rasio 2:1 paint:money tower sebelum melakukan ekspansi paint coverage masif menggunakan splasher. Menggunakan Bug2 navigation, dan communication protocol 3 message types antar-unit. Spawn cycle berubah dari 3:1 soldier:mopper (awal) ke 2:2:1 soldier:splasher:mopper (ronde 200+).

### `alternative_bots_1` — Paint-First Aggressive Expansion
Counter-bot yang memaksimalkan luas area cat tim per ronde dengan SRP 4x4 grid (`srpUseSecondary(x%4, y%4)`) untuk bonus resource 3x. Soldier paint ulang tile musuh (bukan hanya tile kosong) dan memiliki mekanisme nge-sabotage ruin lawan. Upgrade tower lebih awal (L2@150, L3@300). Spawn cycle: SOL-SOL-MOP-SOL-SOL-SPL (index % 6).

### `alternative_bots_2` — Nearest-First Minimalist
Strategi greedy paling sederhana, setiap unit selalu milih target terdekat tanpa komunikasi, tanpa SRP, dan tanpa mopper. Soldier mencari ruin terdekat, splasher bergerak ke area non-ally terbanyak. Spawn 50:50 soldier:splasher. Tipe tower deterministik berdasarkan posisi ruin `(x+y)%3`.

## Requirements

- Java 21 atau lebih tinggi
- Gradle 8.10 (sudah termasuk via Gradle Wrapper)

## How to Build

```bash
# Clone repo
git clone https://github.com/ethj0r/Tubes1_-opentowork.git
cd Tubes1_-opentowork

# Build semua bot
./gradlew build
```

## How to Run

```bash
# run match antara dua bot
./gradlew run -PteamA=main_bot -PteamB=alternative_bots_1 -Pmaps=DefaultSmall

# e.g., match lainnya
./gradlew run -PteamA=main_bot -PteamB=alternative_bots_2 -Pmaps=DefaultSmall
./gradlew run -PteamA=alternative_bots_1 -PteamB=alternative_bots_2 -Pmaps=DefaultSmall

# mau run pake client?
# open Battlecode Client, or
./gradlew client
```

Replay file tersimpan di folder `matches/`.

```bash
# List semua bot yang tersedia
./gradlew listPlayers

# List semua map yang tersedia
./gradlew listMaps
```

## Author

13524020 Stevanus Agustaf Wongso
13524026 Made Branenda Jordhy
13524114 Mirza Tsabita Wafa’ana