package de.spurensturm.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Ein Level von Spurensturm. Läuft ohne Android (testbar).
 * Der Trupp steht still bei z = 0, alles kommt auf ihn zu.
 * Spuren von links nach rechts: +1-Tore (x = -2) · Zombies (x = 0) · Schieß-Upgrades (x = +2).
 */
public final class Game {

    public static final float LANE = 2f, ROAD_HALF = 3f;
    public static final float SPAWN_Z = 44f, BULLET_SPEED = 30f, RANGE = 32f;
    public static final float GATE_SPEED = 6.5f;
    public static final int MAX_SHOOTERS = 100;
    /** Sekunden bis zum Boss. Mit Bosskampf dauert ein Level etwa 5 Minuten. */
    public static final float RUN_TIME = 250f;
    public static final int LANE_PLUS = 0, LANE_ZOMBIES = 1, LANE_UPGRADES = 2;

    public static final int RUNNING = 0, WON = 1, LOST = 2;

    // ------------------------------------------------------------------ Bausteine

    public static final int WALKER = 0, RUNNER = 1, BOSS = 3;

    public static final class Zombie {
        public int type;
        public float x, z, hp, maxHp, speed, r, wobble, hitFlash;
        public int bite;
        public boolean dead;
        public long hitBy;
    }

    public static final class Bullet {
        public float x, z, dmg;
        public int pierce;
        public boolean dead;
        public final List<Zombie> hit = new ArrayList<>(2);
    }

    public static final int G_ADD = 0, G_RATE = 3, G_DMG = 4, G_PIERCE = 5;

    public static final class Gate {
        public int lane, type;
        /** G_ADD: Anzahl, G_RATE: Prozent, G_DMG/G_PIERCE: Plus. */
        public int value;
        public int hits;
        public float flash;
    }

    /** Ein einzelnes Tor auf einer Spur (gates[] hat nur auf dieser Spur einen Eintrag). */
    public static final class GateRow {
        public float z;
        public final Gate[] gates = new Gate[3];
        public boolean passed;
    }

    public static final int FX_HIT = 0, FX_DIE = 1, FX_GATE = 2, FX_LOSE = 3, FX_BOSS_HIT = 4, FX_GATE_HIT = 5;

    public static final class Fx {
        public int type;
        public float x, z, value, t, life;
        public boolean seen;
        public String text;
    }

    // ------------------------------------------------------------------ Zustand

    public final int level;
    public final Random rnd;
    public float time;
    public float squadX, targetX;
    public int shooters = 3;
    public float damage = 1f;
    /** Schüsse pro Sekunde und Schütze. */
    public float fireRate = 0.7f;
    /** Wie viele Zombies ein Schuss durchschlägt. */
    public int pierce = 1;
    public int state = RUNNING;
    public final List<Zombie> zombies = new ArrayList<>();
    public final List<Bullet> bullets = new ArrayList<>();
    public final List<GateRow> gateRows = new ArrayList<>();
    public final List<Fx> fx = new ArrayList<>();
    public Zombie boss;
    /** Bleibt konstant – der Trupp läuft nicht, er steht. */
    public float distance;
    public int kills;
    public float lastLoss = 99;

    private float fireAcc, spawnAcc, nextPlus = 1.5f, nextUpgrade = 5f, nextPack = 12f, nextHorde = 45f;
    private final float diff;

    public Game(int level, long seed) {
        this.level = Math.max(1, level);
        this.rnd = new Random(seed);
        this.diff = effLevel(this.level);
    }

    /** Ab Level 10 steigt die Schwierigkeit nur noch langsam – schwer, aber nie unmöglich. */
    static float effLevel(int level) {
        return level <= 10 ? level : 10f + 1.8f * (1f - (float) Math.exp(-(level - 10) / 8.0));
    }

    // ------------------------------------------------------------------ Kenngrößen

    public float dps() { return shooters * damage * fireRate; }

    /** Halbe Breite der Formation (passt immer in eine Spur). */
    public float squadHalfWidth() { return Math.min(0.85f, 0.25f + 0.07f * (float) Math.sqrt(shooters)); }

    public float progress() { return Math.min(1f, time / RUN_TIME); }

    public int squadLane() {
        int lane = Math.round(squadX / LANE) + 1;
        return Math.max(0, Math.min(2, lane));
    }

    public static float BOSS_HP = 110000f, BOSS_GROWTH = 1.1f;
    public float bossMaxHp() { return BOSS_HP * (float) Math.pow(BOSS_GROWTH, diff - 1); }

    /** Stellschrauben der Zombie-Menge (per Simulation eingestellt). */
    public static float ZA = 0.35f, ZB = 0.022f, ZC = 0.00012f, ZL = 0.13f, PACK = 2.5f, HORDE = 7f;

    /** Zombies pro Sekunde in der mittleren Spur. */
    float zombieRate() {
        float ramp = Math.min(1f, time / 70f);
        return (ZA + ZB * time + ZC * time * time) * (1f + ZL * (diff - 1) * ramp);
    }

    // ------------------------------------------------------------------ Schleife

    public void update(float dt) {
        if (state != RUNNING) { tickFx(dt); return; }
        time += dt;
        lastLoss += dt;
        tickFx(dt);

        targetX = clamp(targetX, -LANE, LANE);
        float maxMove = 10f * dt;
        squadX += clamp(targetX - squadX, -maxMove, maxMove);

        schedule(dt);
        fire(dt);
        moveBullets(dt);
        moveZombies(dt);
        moveGates(dt);
        zombies.removeIf(z -> z.dead);
        bullets.removeIf(b -> b.dead);
        if (shooters <= 0) { shooters = 0; state = LOST; }
    }

    private void schedule(float dt) {
        // +1-Tore links – immer
        if (time >= nextPlus && boss == null) {
            addGate(LANE_PLUS, G_ADD, 1);
            nextPlus += 2.1f + rnd.nextFloat() * 0.8f;
        }
        // Upgrades rechts
        if (time >= nextUpgrade && time < RUN_TIME + 20) {
            float r = rnd.nextFloat();
            if (r < 0.5f) addGate(LANE_UPGRADES, G_RATE, 10 + 5 * rnd.nextInt(3));
            else if (r < 0.82f) addGate(LANE_UPGRADES, G_DMG, 1);
            else addGate(LANE_UPGRADES, G_PIERCE, 1);
            nextUpgrade += 6.5f + rnd.nextFloat() * 2.5f;
        }
        if (time < RUN_TIME) {
            // Zombies: stetiger Strom plus Rudel
            spawnAcc += zombieRate() * dt;
            while (spawnAcc >= 1f) { spawnAcc -= 1f; spawnZombie(time > 20 && rnd.nextFloat() < 0.25f ? RUNNER : WALKER, SPAWN_Z); }
            if (time >= nextPack) {
                int n = 3 + rnd.nextInt(3) + Math.round(zombieRate() * PACK);
                for (int i = 0; i < n; i++) spawnZombie(WALKER, SPAWN_Z + i * 0.45f);
                nextPack += 10f + rnd.nextFloat() * 4f;
            }
            if (time >= nextHorde) {
                int n = 8 + Math.round(zombieRate() * HORDE);
                for (int i = 0; i < n; i++) spawnZombie(i % 5 == 4 ? RUNNER : WALKER, SPAWN_Z + i * 0.3f);
                nextHorde += 45f + rnd.nextFloat() * 8f;
                Fx f = addFx(FX_GATE, 0, SPAWN_Z, 0, 2f);
                f.text = "HORDE!";
            }
        } else if (boss == null) {
            boss = new Zombie();
            boss.type = BOSS;
            boss.x = 0; boss.z = SPAWN_Z;
            boss.hp = boss.maxHp = bossMaxHp();
            boss.speed = 0.85f;
            boss.r = 1.2f;
            boss.bite = 9999;
            zombies.add(boss);
            Fx f = addFx(FX_GATE, 0, SPAWN_Z, 0, 2.5f);
            f.text = "BOSS!";
        } else {
            spawnAcc += (0.7f + 0.06f * diff) * dt;
            while (spawnAcc >= 1f) { spawnAcc -= 1f; spawnZombie(rnd.nextFloat() < 0.3f ? RUNNER : WALKER, SPAWN_Z); }
        }
    }

    private void spawnZombie(int type, float z) {
        Zombie zb = new Zombie();
        zb.type = type;
        zb.x = (rnd.nextFloat() - 0.5f) * 1.5f;
        zb.z = z + rnd.nextFloat() * 0.6f;
        zb.hp = zb.maxHp = 1;
        zb.speed = type == RUNNER ? 6.5f : 4.2f + rnd.nextFloat() * 0.6f;
        zb.r = 0.42f;
        zb.bite = 1;
        zb.wobble = rnd.nextFloat() * 10f;
        zombies.add(zb);
    }

    // ------------------------------------------------------------------ Tore

    private void addGate(int lane, int type, int value) {
        GateRow row = new GateRow();
        row.z = SPAWN_Z;
        Gate g = new Gate();
        g.lane = lane; g.type = type; g.value = value;
        row.gates[lane] = g;
        gateRows.add(row);
    }

    private void moveGates(float dt) {
        int lane = squadLane();
        for (GateRow row : gateRows) {
            row.z -= GATE_SPEED * dt;
            for (Gate g : row.gates) if (g != null && g.flash > 0) g.flash -= dt;
            if (!row.passed && row.z <= 0.3f) {
                row.passed = true;
                if (row.gates[lane] != null) apply(row.gates[lane]);
            }
        }
        gateRows.removeIf(r -> r.z < -3f);
    }

    public void apply(Gate g) {
        int before = shooters;
        switch (g.type) {
            case G_ADD: shooters += g.value; break;
            case G_RATE: fireRate *= 1f + g.value / 100f; break;
            case G_DMG: damage += g.value; break;
            case G_PIERCE: pierce += g.value; break;
        }
        shooters = Math.min(MAX_SHOOTERS, shooters);
        Fx f = addFx(FX_GATE, squadX, 1f, Math.max(1, shooters - before), 1.1f);
        f.text = label(g);
    }

    public static String label(Gate g) {
        switch (g.type) {
            case G_ADD: return "+" + g.value;
            case G_RATE: return "Tempo +" + g.value + "%";
            case G_DMG: return "Schaden +" + g.value;
            default: return "Durchschuss";
        }
    }

    public static boolean positive(Gate g) { return true; }

    // ------------------------------------------------------------------ Schießen

    private void fire(float dt) {
        float shotsPerSec = Math.min(420f, shooters * fireRate);
        fireAcc += shotsPerSec * dt;
        float w = squadHalfWidth();
        while (fireAcc >= 1f) {
            fireAcc -= 1f;
            Bullet b = new Bullet();
            b.x = squadX + (rnd.nextFloat() * 2 - 1) * w;
            b.z = 0.6f + rnd.nextFloat() * 0.4f;
            b.dmg = damage * Math.max(1f, shooters * fireRate / 420f);
            b.pierce = pierce;
            bullets.add(b);
        }
    }

    private void moveBullets(float dt) {
        for (Bullet b : bullets) {
            float step = BULLET_SPEED * dt / 2f;
            for (int s = 0; s < 2 && !b.dead; s++) {
                b.z += step;
                if (b.z > RANGE) { b.dead = true; break; }
                for (Zombie z : zombies) {
                    if (z.dead || b.hit.contains(z)) continue;
                    float reach = z.type == BOSS ? 1.0f : 0.5f;
                    if (Math.abs(z.z - b.z) > reach || Math.abs(z.x - b.x) > z.r) continue;
                    hit(z, b.dmg);
                    b.hit.add(z);
                    if (--b.pierce <= 0 || z.type == BOSS) { b.dead = true; break; }
                }
                if (b.dead) break;
                for (GateRow row : gateRows) {
                    if (row.passed || Math.abs(row.z - b.z) > 0.4f) continue;
                    int lane = Math.round(b.x / LANE) + 1;
                    if (lane < 0 || lane > 2 || row.gates[lane] == null || Math.abs(b.x - (lane - 1) * LANE) > 0.9f) continue;
                    Gate g = row.gates[lane];
                    b.dead = true;
                    if (g.type == G_ADD) {
                        g.hits++;
                        if (g.hits >= 10 + (int) diff) { g.hits = 0; g.value++; g.flash = 0.15f; }
                    }
                    break;
                }
            }
        }
    }

    private void hit(Zombie z, float dmg) {
        z.hp -= dmg;
        z.hitFlash = 0.08f;
        if (z.hp <= 0) {
            z.dead = true;
            kills++;
            addFx(FX_DIE, z.x, z.z, z.type, 0.6f);
            if (z.type == BOSS) {
                state = WON;
                for (Zombie o : zombies) if (!o.dead) { o.dead = true; addFx(FX_DIE, o.x, o.z, o.type, 0.6f); }
            }
        }
    }

    // ------------------------------------------------------------------ Zombies

    private void moveZombies(float dt) {
        float w = squadHalfWidth();
        for (Zombie z : zombies) {
            if (z.dead) continue;
            if (z.hitFlash > 0) z.hitFlash -= dt;
            z.wobble += dt * (2f + z.speed * 0.6f);
            z.z -= z.speed * dt;
            if (z.type == BOSS) {
                z.x = (float) Math.sin(time * 0.4f) * 0.35f;
                if (z.z <= 1.6f) {
                    state = LOST;
                    shooters = 0;
                    addFx(FX_LOSE, squadX, 0.5f, 0, 1.5f);
                }
                continue;
            }
            // Kurz vor dem Trupp schwenken die Zombies auf dessen Spur ein
            if (z.z < 9f) {
                float pull = (z.type == RUNNER ? 3.2f : 2.4f) * dt;
                z.x += clamp(squadX - z.x, -pull, pull);
            }
            if (z.z <= 0.5f) {
                if (Math.abs(z.x - squadX) < w + z.r) {
                    shooters -= z.bite;
                    lastLoss = 0;
                    z.dead = true;
                    addFx(FX_LOSE, z.x, 0.5f, z.bite, 0.8f);
                } else if (z.z < -1.5f) {
                    z.dead = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------ Effekte

    private Fx addFx(int type, float x, float z, float value, float life) {
        Fx f = new Fx();
        f.type = type; f.x = x; f.z = z; f.value = value; f.life = life;
        fx.add(f);
        return f;
    }

    private void tickFx(float dt) {
        for (Fx f : fx) f.t += dt;
        fx.removeIf(f -> f.t >= f.life);
    }

    static float clamp(float v, float lo, float hi) { return v < lo ? lo : v > hi ? hi : v; }
}
