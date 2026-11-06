package de.spurensturm.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Ein Level von Spurensturm. Läuft ohne Android (testbar).
 * Koordinaten: x quer (Spuren bei -2, 0, +2), z nach vorne (Trupp steht bei z = 0).
 */
public final class Game {

    public static final float LANE = 2f, ROAD_HALF = 3f;
    public static final float SPAWN_Z = 44f, BULLET_SPEED = 30f, RANGE = 30f;
    public static final float GATE_SPEED = 6f, ZOMBIE_BASE_SPEED = 2.2f;
    public static final int MAX_SHOOTERS = 120;
    /** Sekunden bis zum Boss. Mit Bosskampf dauert ein Level etwa 5 Minuten. */
    public static final float RUN_TIME = 250f;

    public static final int RUNNING = 0, WON = 1, LOST = 2;

    // ------------------------------------------------------------------ Bausteine

    public static final int WALKER = 0, RUNNER = 1, BRUTE = 2, BOSS = 3;

    public static final class Zombie {
        public int type;
        public float x, z, hp, maxHp, speed, r, wobble, hitFlash;
        public int bite;
        public boolean dead;
    }

    public static final class Bullet {
        public float x, z, dmg;
        public boolean dead;
    }

    public static final int G_ADD = 0, G_MUL = 1, G_DIV = 2, G_RATE = 3, G_DMG = 4;

    public static final class Gate {
        public int lane, type;
        /** G_ADD: Anzahl (auch negativ), G_MUL/G_DIV: Faktor, G_RATE: Prozent, G_DMG: Plus. */
        public int value;
        public int hits;
        public float flash;
    }

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
    public int shooters = 1;
    public float damage = 1f;
    /** Schüsse pro Sekunde und Schütze. */
    public float fireRate = 2f;
    public int state = RUNNING;
    public final List<Zombie> zombies = new ArrayList<>();
    public final List<Bullet> bullets = new ArrayList<>();
    public final List<GateRow> gateRows = new ArrayList<>();
    public final List<Fx> fx = new ArrayList<>();
    public Zombie boss;
    public float distance;
    public int kills;
    public float lastLoss;

    private float fireAcc, spawnAcc, nextGate = 7f, nextHorde = 40f;
    private int gateIndex;
    private final float diff;

    public Game(int level, long seed) {
        this.level = Math.max(1, level);
        this.rnd = new Random(seed);
        this.diff = (float) Math.pow(1.13, effLevel(this.level) - 1);
    }

    // ------------------------------------------------------------------ Kenngrößen

    /** Ab Level 10 steigt die Schwierigkeit nur noch langsam – schwer, aber nie unmöglich. */
    static float effLevel(int level) {
        return level <= 10 ? level : 10f + 1.8f * (1f - (float) Math.exp(-(level - 10) / 8.0));
    }

    public float dps() { return shooters * damage * fireRate; }

    /** Halbe Breite der Formation. */
    public float squadHalfWidth() { return Math.min(1.15f, 0.28f + 0.11f * (float) Math.sqrt(shooters)); }

    /** Fortschritt bis zum Boss (0..1). */
    public float progress() { return Math.min(1f, time / RUN_TIME); }

    /** Zombie-Lebenspunkte wachsen mit der Zeit im Level und mit dem Level. */
    private float hpScale() {
        // Level-Schwierigkeit steigt in der ersten Minute langsam an – der Start bleibt fair
        float ramp = Math.min(1f, time / 75f);
        float lvl = 1f + (diff - 1f) * ramp * ramp;
        return lvl * (1f + time / 55f + (time / 120f) * (time / 120f));
    }

    public float bossMaxHp() { return 2600f * (float) Math.pow(1.16, effLevel(level) - 1); }

    // ------------------------------------------------------------------ Schleife

    public void update(float dt) {
        if (state != RUNNING) { tickFx(dt); return; }
        time += dt;
        distance += GATE_SPEED * dt;
        lastLoss += dt;
        tickFx(dt);

        // Trupp gleitet zum Ziel
        targetX = clamp(targetX, -ROAD_HALF + 0.6f, ROAD_HALF - 0.6f);
        float maxMove = 9f * dt;
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
        if (time < RUN_TIME) {
            // Tore
            if (time >= nextGate && time < RUN_TIME - 8) {
                spawnGateRow();
                nextGate += 15f + rnd.nextFloat() * 4f;
            }
            // Zombies laufend
            float rate = (0.55f + time * 0.0075f) * (1f + 0.05f * (effLevel(level) - 1) * Math.min(1f, time / 60f));
            spawnAcc += rate * dt;
            while (spawnAcc >= 1f) { spawnAcc -= 1f; spawnZombie(randomType(), randomX(), SPAWN_Z); }
            // Horden
            if (time >= nextHorde) {
                nextHorde += 38f + rnd.nextFloat() * 10f;
                int n = 8 + (int) (time / 25f) + (int) (effLevel(level) / 2);
                float hx = (rnd.nextInt(3) - 1) * LANE;
                for (int i = 0; i < n; i++) spawnZombie(i % 7 == 6 ? BRUTE : WALKER, hx + (rnd.nextFloat() - 0.5f) * 2.2f, SPAWN_Z + i * 0.7f);
            }
        } else if (boss == null) {
            boss = new Zombie();
            boss.type = BOSS;
            boss.x = 0; boss.z = SPAWN_Z;
            boss.hp = boss.maxHp = bossMaxHp();
            boss.speed = 0.85f + 0.02f * Math.min(10, level);
            boss.r = 1.45f;
            boss.bite = 9999;
            zombies.add(boss);
            Fx f = addFx(FX_GATE, 0, SPAWN_Z, 0, 2.5f);
            f.text = "BOSS!";
        } else {
            // Kleine Helfer während des Bosskampfs
            spawnAcc += (0.5f + 0.05f * effLevel(level)) * dt;
            while (spawnAcc >= 1f) { spawnAcc -= 1f; spawnZombie(rnd.nextFloat() < 0.3f ? RUNNER : WALKER, randomX(), SPAWN_Z); }
        }
    }

    private int randomType() {
        float r = rnd.nextFloat();
        if (time > 70 && r < 0.09f + 0.01f * Math.min(8, level)) return BRUTE;
        if (time > 25 && r < 0.32f) return RUNNER;
        return WALKER;
    }

    private float randomX() {
        return (rnd.nextInt(3) - 1) * LANE + (rnd.nextFloat() - 0.5f) * 1.4f;
    }

    private void spawnZombie(int type, float x, float z) {
        Zombie zb = new Zombie();
        zb.type = type;
        zb.x = clamp(x, -ROAD_HALF + 0.4f, ROAD_HALF - 0.4f);
        zb.z = z;
        float s = hpScale();
        switch (type) {
            case RUNNER: zb.hp = 2.2f * s; zb.speed = 3.4f; zb.r = 0.42f; zb.bite = 1; break;
            case BRUTE: zb.hp = 24f * s; zb.speed = 0.9f; zb.r = 0.75f; zb.bite = 5; break;
            default: zb.hp = 3.2f * s; zb.speed = 1.5f; zb.r = 0.45f; zb.bite = 1; break;
        }
        zb.maxHp = zb.hp;
        zb.wobble = rnd.nextFloat() * 10f;
        zombies.add(zb);
    }

    // ------------------------------------------------------------------ Tore

    private void spawnGateRow() {
        GateRow row = new GateRow();
        row.z = SPAWN_Z;
        int i = gateIndex++;
        float growth = 1f + i * 0.55f;
        Gate good = new Gate(), mid = new Gate(), bad = new Gate();
        // Gutes Tor
        float r = rnd.nextFloat();
        if (r < 0.22f && i >= 1) { good.type = G_MUL; good.value = shooters < 25 ? 2 : 2; }
        else if (r < 0.38f) { good.type = G_DMG; good.value = 1; }
        else { good.type = G_ADD; good.value = Math.round((4 + rnd.nextInt(4)) * growth); }
        // Mittleres Tor
        r = rnd.nextFloat();
        if (r < 0.45f) { mid.type = G_RATE; mid.value = 15 + 5 * rnd.nextInt(3); }
        else { mid.type = G_ADD; mid.value = Math.round((2 + rnd.nextInt(3)) * growth); }
        // Schlechtes Tor (wird mit höherem Level fieser)
        r = rnd.nextFloat();
        if (r < 0.2f + 0.02f * Math.min(10, level) && i >= 2) { bad.type = G_DIV; bad.value = 2; }
        else { bad.type = G_ADD; bad.value = -Math.round((3 + rnd.nextInt(4)) * growth * (1f + 0.08f * (effLevel(level) - 1))); }
        Gate[] all = {good, mid, bad};
        // Ab und zu zwei schlechte statt eines mittleren (höhere Level)
        if (level >= 3 && rnd.nextFloat() < 0.12f + 0.02f * Math.min(10, level)) {
            Gate bad2 = new Gate();
            bad2.type = G_ADD;
            bad2.value = -Math.round((2 + rnd.nextInt(3)) * growth);
            all[1] = bad2;
        }
        // Spuren mischen
        for (int k = 2; k > 0; k--) { int j = rnd.nextInt(k + 1); Gate t = all[k]; all[k] = all[j]; all[j] = t; }
        for (int lane = 0; lane < 3; lane++) { all[lane].lane = lane; row.gates[lane] = all[lane]; }
        gateRows.add(row);
    }

    private void moveGates(float dt) {
        for (GateRow row : gateRows) {
            row.z -= GATE_SPEED * dt;
            for (Gate g : row.gates) if (g.flash > 0) g.flash -= dt;
            if (!row.passed && row.z <= 0.3f) {
                row.passed = true;
                int lane = Math.round(squadX / LANE) + 1;
                lane = Math.max(0, Math.min(2, lane));
                apply(row.gates[lane]);
            }
        }
        gateRows.removeIf(r -> r.z < -3f);
    }

    public void apply(Gate g) {
        int before = shooters;
        switch (g.type) {
            case G_ADD: shooters += g.value; break;
            case G_MUL: shooters *= g.value; break;
            case G_DIV: shooters = Math.max(1, shooters / g.value); break;
            case G_RATE: fireRate *= 1f + g.value / 100f; break;
            case G_DMG: damage += g.value; break;
        }
        shooters = Math.min(MAX_SHOOTERS, shooters);
        Fx f = addFx(FX_GATE, squadX, 1f, shooters - before, 1.2f);
        f.text = label(g);
        if (shooters < before) lastLoss = 0;
    }

    public static String label(Gate g) {
        switch (g.type) {
            case G_ADD: return g.value >= 0 ? "+" + g.value : String.valueOf(g.value);
            case G_MUL: return "×" + g.value;
            case G_DIV: return "÷" + g.value;
            case G_RATE: return "Tempo +" + g.value + "%";
            default: return "Schaden +" + g.value;
        }
    }

    public static boolean positive(Gate g) {
        return g.type == G_MUL || g.type == G_RATE || g.type == G_DMG || (g.type == G_ADD && g.value >= 0);
    }

    // ------------------------------------------------------------------ Schießen

    private void fire(float dt) {
        float shotsPerSec = shooters * fireRate;
        // Bei sehr großen Trupps werden Schüsse gebündelt (gleicher Gesamtschaden)
        float cap = 110f;
        float bundle = Math.max(1f, shotsPerSec / cap);
        fireAcc += shotsPerSec / bundle * dt;
        float w = squadHalfWidth();
        while (fireAcc >= 1f) {
            fireAcc -= 1f;
            Bullet b = new Bullet();
            b.x = squadX + (rnd.nextFloat() * 2 - 1) * w;
            b.z = 0.6f;
            b.dmg = damage * bundle;
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
                    if (z.dead || Math.abs(z.z - b.z) > 0.55f + (z.type == BOSS ? 0.6f : 0) || Math.abs(z.x - b.x) > z.r) continue;
                    hit(z, b.dmg);
                    b.dead = true;
                    break;
                }
                if (b.dead) break;
                for (GateRow row : gateRows) {
                    if (row.passed || Math.abs(row.z - b.z) > 0.4f) continue;
                    int lane = Math.round(b.x / LANE) + 1;
                    if (lane < 0 || lane > 2 || Math.abs(b.x - (lane - 1) * LANE) > 0.9f) continue;
                    Gate g = row.gates[lane];
                    b.dead = true;
                    if (g.type == G_ADD) {
                        g.hits++;
                        int need = 4 + level / 2;
                        if (g.hits >= need) { g.hits = 0; g.value++; g.flash = 0.15f; }
                    }
                    break;
                }
            }
        }
    }

    private void hit(Zombie z, float dmg) {
        z.hp -= dmg;
        z.hitFlash = 0.08f;
        if (z.type == BOSS) z.z += 0.012f * Math.min(4f, dmg / 4f); // Treffer bremsen den Boss minimal
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
            z.wobble += dt * (2f + z.speed);
            float approach = z.type == BOSS ? z.speed : ZOMBIE_BASE_SPEED + z.speed;
            z.z -= approach * dt;
            if (z.type == BOSS) {
                z.x = (float) Math.sin(time * 0.45f) * 1.4f;
                if (z.z <= 1.6f) {
                    state = LOST;
                    shooters = 0;
                    addFx(FX_LOSE, squadX, 0.5f, 0, 1.5f);
                }
                continue;
            }
            // In der Nähe laufen Zombies auf den Trupp zu
            if (z.z < 11f) {
                float pull = (z.type == RUNNER ? 1.1f : 0.6f) * dt;
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
