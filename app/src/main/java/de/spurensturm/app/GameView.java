package de.spurensturm.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import de.spurensturm.game.Game;
import de.spurensturm.game.Game.Zombie;

/** 3D-Ansicht (Perspektive von schräg hinten), Effekte und Wisch-Steuerung. */
public class GameView extends View implements Choreographer.FrameCallback {

    public interface Listener {
        void onLevelEnd(Game game);
        void onPauseRequested();
    }

    // Farben
    private static final int ROAD = Color.rgb(64, 66, 74);
    private static final int ROAD_EDGE = Color.rgb(200, 190, 160);
    private static final int GROUND = Color.rgb(92, 84, 70);
    private static final int GROUND_FAR = Color.rgb(120, 104, 88);
    private static final int GOOD = Color.rgb(64, 160, 255);
    private static final int BAD = Color.rgb(255, 72, 72);

    private final Game game;
    private final Listener listener;
    private final float density;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final Random rnd = new Random();

    private boolean running;
    private long lastNanos;
    private float acc, endTimer;
    private boolean endReported;

    // Kamera
    private float W, H, f, horizon, camX, shakeT, shakeX, shakeY;
    private static final float CAM_H = 5.2f, CAM_BACK = 8.0f;

    // Steuerung
    private int pointer = -1;
    private float touchX0, targetX0;

    // Partikel (Welt)
    private static final int MAX_P = 400;
    private final float[] px = new float[MAX_P], py = new float[MAX_P], pz = new float[MAX_P];
    private final float[] pvx = new float[MAX_P], pvy = new float[MAX_P], pvz = new float[MAX_P];
    private final float[] plife = new float[MAX_P], pmax = new float[MAX_P], psize = new float[MAX_P];
    private final int[] pcol = new int[MAX_P];
    private int pCount;
    private float lossFlash;

    public GameView(Context c, Game game, Listener listener) {
        super(c);
        this.game = game;
        this.listener = listener;
        this.density = c.getResources().getDisplayMetrics().density;
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
    }

    // ------------------------------------------------------------------ Schleife

    public void start() {
        if (running) return;
        running = true;
        lastNanos = 0;
        setKeepScreenOn(true);
        Choreographer.getInstance().postFrameCallback(this);
    }

    public void stop() {
        running = false;
        pointer = -1;
        Choreographer.getInstance().removeFrameCallback(this);
        setKeepScreenOn(false);
    }

    @Override
    public void doFrame(long nanos) {
        if (!running) return;
        float frame = 1 / 60f;
        if (lastNanos != 0) {
            frame = Math.min(0.1f, (nanos - lastNanos) / 1e9f);
            acc += frame;
            int n = 0;
            while (acc >= 1 / 60f && n < 6) {
                game.update(1 / 60f);
                acc -= 1 / 60f;
                n++;
            }
            if (n == 6) acc = 0;
        }
        lastNanos = nanos;
        effects(frame);
        invalidate();
        if (game.state != Game.RUNNING && !endReported) {
            endTimer += frame;
            if (endTimer > 1.6f) { endReported = true; listener.onLevelEnd(game); }
        }
        if (running) Choreographer.getInstance().postFrameCallback(this);
    }

    // ------------------------------------------------------------------ Steuerung: wischen

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) {
            if (Math.hypot(e.getX() - 34 * density, e.getY() - 44 * density) < 30 * density) {
                listener.onPauseRequested();
                return true;
            }
            pointer = e.getPointerId(0);
            touchX0 = e.getX();
            targetX0 = game.targetX;
        } else if (a == MotionEvent.ACTION_MOVE && pointer >= 0) {
            int i = e.findPointerIndex(pointer);
            if (i >= 0) {
                float dx = (e.getX(i) - touchX0) / (W * 0.45f);
                game.targetX = Math.max(-Game.ROAD_HALF + 0.6f, Math.min(Game.ROAD_HALF - 0.6f, targetX0 + dx * Game.ROAD_HALF * 2));
            }
        } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            pointer = -1;
        }
        return true;
    }

    // ------------------------------------------------------------------ Projektion

    private float depth(float z) { return Math.max(0.6f, z + CAM_BACK); }
    private float PX(float x, float z) { return W / 2 + (x - camX) * f / depth(z) + shakeX; }
    private float PY(float y, float z) { return horizon + (CAM_H - y) * f / depth(z) + shakeY; }
    private float PS(float z) { return f / depth(z); }

    // ------------------------------------------------------------------ Effekte

    private void spawn(float x, float y, float z, int n, int color, float speed, float life, float size) {
        for (int i = 0; i < n && pCount < MAX_P; i++) {
            px[pCount] = x; py[pCount] = y; pz[pCount] = z;
            pvx[pCount] = (rnd.nextFloat() * 2 - 1) * speed;
            pvy[pCount] = rnd.nextFloat() * speed * 1.2f;
            pvz[pCount] = (rnd.nextFloat() * 2 - 1) * speed;
            plife[pCount] = pmax[pCount] = life * (0.6f + 0.4f * rnd.nextFloat());
            psize[pCount] = size * (0.6f + 0.6f * rnd.nextFloat());
            pcol[pCount] = color;
            pCount++;
        }
    }

    private void effects(float dt) {
        for (int i = 0; i < pCount; i++) {
            plife[i] -= dt;
            if (plife[i] <= 0) {
                pCount--;
                px[i] = px[pCount]; py[i] = py[pCount]; pz[i] = pz[pCount];
                pvx[i] = pvx[pCount]; pvy[i] = pvy[pCount]; pvz[i] = pvz[pCount];
                plife[i] = plife[pCount]; pmax[i] = pmax[pCount]; psize[i] = psize[pCount]; pcol[i] = pcol[pCount];
                i--;
                continue;
            }
            px[i] += pvx[i] * dt; py[i] += pvy[i] * dt; pz[i] += pvz[i] * dt - 6f * dt;
            pvy[i] -= 9f * dt;
            if (py[i] < 0) { py[i] = 0; pvy[i] *= -0.3f; pvx[i] *= 0.6f; pvz[i] *= 0.6f; }
        }
        if (lossFlash > 0) lossFlash -= dt;
        if (shakeT > 0) {
            shakeT -= dt;
            float amp = 7 * density * Math.max(0, shakeT) / 0.3f;
            shakeX = (rnd.nextFloat() * 2 - 1) * amp;
            shakeY = (rnd.nextFloat() * 2 - 1) * amp;
        } else { shakeX = shakeY = 0; }
        for (Game.Fx e : game.fx) {
            if (e.seen) continue;
            e.seen = true;
            switch (e.type) {
                case Game.FX_DIE: {
                    boolean boss = e.value == Game.BOSS;
                    spawn(e.x, 0.8f, e.z, boss ? 60 : 12, Color.rgb(120, 200, 80), boss ? 7 : 4, boss ? 1.2f : 0.7f, boss ? 0.25f : 0.12f);
                    spawn(e.x, 0.8f, e.z, boss ? 30 : 5, Color.rgb(90, 60, 50), 3, 0.9f, 0.15f);
                    if (boss) shakeT = 0.6f;
                    break;
                }
                case Game.FX_LOSE:
                    spawn(e.x, 0.6f, 0.3f, 10, Color.rgb(255, 80, 80), 4, 0.6f, 0.1f);
                    lossFlash = 0.35f;
                    shakeT = Math.max(shakeT, 0.25f);
                    break;
                case Game.FX_GATE:
                    if (e.text != null && e.value >= 0) spawn(e.x, 1.2f, 1f, 18, e.value > 0 || e.text.startsWith("×") ? GOOD : Color.WHITE, 4, 0.7f, 0.1f);
                    break;
                default:
                    break;
            }
        }
    }

    // ------------------------------------------------------------------ Zeichnen

    @Override
    protected void onDraw(Canvas c) {
        W = getWidth(); H = getHeight();
        if (W == 0) return;
        f = W * 1.55f;
        horizon = H * 0.25f;
        camX += (game.squadX * 0.45f - camX) * 0.12f;

        drawSky(c);
        drawGround(c);
        drawScenery(c);

        // Alles in der Welt von hinten nach vorne zeichnen
        List<float[]> order = new ArrayList<>();
        for (int i = 0; i < game.zombies.size(); i++) order.add(new float[]{game.zombies.get(i).z, 0, i});
        for (int i = 0; i < game.gateRows.size(); i++) order.add(new float[]{game.gateRows.get(i).z + 0.01f, 1, i});
        order.add(new float[]{0.05f, 2, 0});
        Collections.sort(order, (a, b) -> Float.compare(b[0], a[0]));
        for (float[] o : order) {
            if (o[1] == 0) { Zombie z = game.zombies.get((int) o[2]); if (!z.dead) drawZombie(c, z); }
            else if (o[1] == 1) drawGateRow(c, game.gateRows.get((int) o[2]));
            else { drawBullets(c); drawSquad(c); }
        }
        drawParticles(c);
        drawFloatingTexts(c);
        drawHud(c);
    }

    private void drawSky(Canvas c) {
        int[] cols = {Color.rgb(40, 34, 58), Color.rgb(70, 50, 72), Color.rgb(120, 70, 70), Color.rgb(190, 110, 70), Color.rgb(230, 150, 90)};
        float h = horizon / cols.length;
        for (int i = 0; i < cols.length; i++) {
            p.setColor(cols[i]);
            c.drawRect(0, i * h, W, (i + 1) * h + 1, p);
        }
        // Ruinen-Silhouette am Horizont
        p.setColor(Color.rgb(55, 45, 60));
        float base = horizon;
        for (int i = 0; i < 14; i++) {
            float x = (i * 97 % 100) / 100f * W * 1.2f - W * 0.1f - camX * 6;
            float bw = W * (0.05f + (i * 37 % 7) / 100f);
            float bh = H * (0.025f + (i * 53 % 9) / 300f);
            c.drawRect(x, base - bh, x + bw, base + 1, p);
        }
    }

    private void drawGround(Canvas c) {
        p.setColor(GROUND_FAR);
        c.drawRect(0, horizon, W, horizon + H * 0.06f, p);
        p.setColor(GROUND);
        c.drawRect(0, horizon + H * 0.06f, W, H, p);
        // Straße
        float zNear = -4.5f, zFar = 70f, hw = Game.ROAD_HALF + 0.3f;
        quad(c, ROAD, -hw, 0, zNear, hw, 0, zNear, hw, 0, zFar, -hw, 0, zFar);
        // Ränder
        quad(c, ROAD_EDGE, -hw - 0.25f, 0, zNear, -hw, 0, zNear, -hw, 0, zFar, -hw - 0.25f, 0, zFar);
        quad(c, ROAD_EDGE, hw, 0, zNear, hw + 0.25f, 0, zNear, hw + 0.25f, 0, zFar, hw, 0, zFar);
        // Spurlinien (bewegt)
        float off = game.distance % 4f;
        p.setColor(Color.argb(170, 240, 235, 210));
        for (int lane = -1; lane <= 1; lane += 2) {
            float x = lane * Game.LANE / 2f;
            for (float z = -4 - off; z < 60; z += 4f) {
                float z2 = z + 2f;
                quad(c, Color.argb(170, 240, 235, 210), x - 0.07f, 0, z, x + 0.07f, 0, z, x + 0.07f, 0, z2, x - 0.07f, 0, z2);
            }
        }
        // Risse / Flecken auf der Straße
        float o2 = game.distance % 9f;
        for (float z = -3 - o2; z < 50; z += 9f) {
            float x = (float) Math.sin(z * 1.7f + Math.floor((game.distance + z) / 9f)) * 2.2f;
            p.setColor(Color.argb(60, 20, 20, 25));
            float sx = PX(x, z), sy = PY(0, z), s = PS(z);
            c.drawOval(sx - s * 0.5f, sy - s * 0.12f, sx + s * 0.5f, sy + s * 0.12f, p);
        }
    }

    private void quad(Canvas c, int color, float x0, float y0, float z0, float x1, float y1, float z1,
                      float x2, float y2, float z2, float x3, float y3, float z3) {
        p.setColor(color);
        path.reset();
        path.moveTo(PX(x0, z0), PY(y0, z0));
        path.lineTo(PX(x1, z1), PY(y1, z1));
        path.lineTo(PX(x2, z2), PY(y2, z2));
        path.lineTo(PX(x3, z3), PY(y3, z3));
        path.close();
        c.drawPath(path, p);
    }

    /** Einfache Quader (Wracks, Fässer, Barrikaden) am Straßenrand. */
    private void drawScenery(Canvas c) {
        float period = 6.5f;
        float off = game.distance % period;
        int baseIdx = (int) Math.floor(game.distance / period);
        for (int k = 9; k >= -1; k--) {
            float z = k * period - off;
            int idx = baseIdx + k;
            for (int side = -1; side <= 1; side += 2) {
                int kind = Math.floorMod(idx * 7 + side * 3, 5);
                float x = side * (Game.ROAD_HALF + 1.4f + (Math.floorMod(idx * 13 + side, 4)) * 0.6f);
                switch (kind) {
                    case 0: box(c, x, z, 1.6f, 0.9f, 1.0f, Color.rgb(120, 60, 50)); break;          // Autowrack
                    case 1: box(c, x, z, 0.5f, 0.8f, 0.5f, Color.rgb(170, 120, 40)); break;          // Fass
                    case 2: box(c, x, z, 2.2f, 0.6f, 0.4f, Color.rgb(150, 150, 140)); break;         // Barrikade
                    case 3: box(c, x + side * 1.5f, z, 1.4f, 3.2f, 1.4f, Color.rgb(90, 82, 96)); break; // Ruine
                    default: break;
                }
            }
        }
    }

    private void box(Canvas c, float x, float z, float w, float h, float d, int color) {
        if (z + CAM_BACK < 1f) return;
        float hw = w / 2, hd = d / 2;
        // Oberseite
        quad(c, lighter(color, 1.25f), x - hw, h, z - hd, x + hw, h, z - hd, x + hw, h, z + hd, x - hw, h, z + hd);
        // Seite zur Straße
        if (x > camX) quad(c, darker(color, 0.7f), x - hw, 0, z - hd, x - hw, h, z - hd, x - hw, h, z + hd, x - hw, 0, z + hd);
        else quad(c, darker(color, 0.7f), x + hw, 0, z - hd, x + hw, h, z - hd, x + hw, h, z + hd, x + hw, 0, z + hd);
        // Front
        quad(c, color, x - hw, 0, z - hd, x + hw, 0, z - hd, x + hw, h, z - hd, x - hw, h, z - hd);
    }

    private void drawGateRow(Canvas c, Game.GateRow row) {
        if (row.passed && row.z < -2.5f) return;
        float z = row.z;
        if (z + CAM_BACK < 1.2f) return;
        float alphaMul = row.passed ? 0.45f : 1f;
        for (Game.Gate g : row.gates) {
            float cx = (g.lane - 1) * Game.LANE;
            boolean good = Game.positive(g);
            int col = good ? GOOD : BAD;
            if (g.flash > 0) col = Color.WHITE;
            float hw = 0.92f, h = 2.3f;
            p.setStyle(Paint.Style.FILL);
            quad(c, withAlpha(col, (int) (110 * alphaMul)), cx - hw, 0, z, cx + hw, 0, z, cx + hw, h, z, cx - hw, h, z);
            // Rahmen
            float l = PX(cx - hw, z), r = PX(cx + hw, z), t = PY(h, z), b = PY(0, z);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(2, PS(z) * 0.08f));
            p.setColor(withAlpha(lighter(col, 1.3f), (int) (230 * alphaMul)));
            c.drawRect(l, t, r, b, p);
            p.setStyle(Paint.Style.FILL);
            // Beschriftung
            String label = Game.label(g);
            float size = PS(z) * (label.length() > 5 ? 0.38f : 0.75f);
            text.setTextSize(size);
            text.setColor(withAlpha(Color.argb(160, 0, 0, 0), (int) (160 * alphaMul)));
            c.drawText(label, (l + r) / 2 + 2, (t + b) / 2 + size * 0.35f + 2, text);
            text.setColor(withAlpha(Color.WHITE, (int) (255 * alphaMul)));
            c.drawText(label, (l + r) / 2, (t + b) / 2 + size * 0.35f, text);
        }
        // Pfosten
        for (int i = 0; i < 4; i++) {
            float x = -3 + i * 2f;
            quad(c, Color.rgb(60, 62, 70), x - 0.07f, 0, z, x + 0.07f, 0, z, x + 0.07f, 2.45f, z, x - 0.07f, 2.45f, z);
        }
    }

    private void drawZombie(Canvas c, Zombie z) {
        if (z.z + CAM_BACK < 1f || z.z > 60) return;
        float s = PS(z.z);
        float scale = z.type == Game.BOSS ? 3.0f : z.type == Game.BRUTE ? 1.55f : z.type == Game.RUNNER ? 0.92f : 1f;
        float cx = PX(z.x, z.z), ground = PY(0, z.z);
        float u = s * 0.5f * scale; // Grundeinheit
        float bob = (float) Math.sin(z.wobble * 2) * u * 0.06f;
        boolean flash = z.hitFlash > 0;

        int skin = z.type == Game.BOSS ? Color.rgb(130, 90, 150) : z.type == Game.BRUTE ? Color.rgb(96, 128, 82) : Color.rgb(132, 168, 108);
        int shirt = z.type == Game.RUNNER ? Color.rgb(150, 60, 55) : z.type == Game.BRUTE ? Color.rgb(70, 66, 80) : z.type == Game.BOSS ? Color.rgb(70, 40, 80) : Color.rgb(96, 104, 120);
        if (flash) { skin = Color.WHITE; shirt = Color.rgb(230, 230, 230); }

        // Schatten
        p.setColor(Color.argb(70, 0, 0, 0));
        c.drawOval(cx - u * 0.9f, ground - u * 0.18f, cx + u * 0.9f, ground + u * 0.18f, p);
        // Beine (abwechselnd)
        float step = (float) Math.sin(z.wobble * 2) * u * 0.18f;
        p.setColor(darker(shirt, 0.6f));
        c.drawRect(cx - u * 0.42f, ground - u * 1.2f - step, cx - u * 0.1f, ground - step * 0.5f, p);
        c.drawRect(cx + u * 0.1f, ground - u * 1.2f + step, cx + u * 0.42f, ground + step * 0.5f, p);
        // Körper
        p.setColor(shirt);
        rect.set(cx - u * 0.6f, ground - u * 2.5f + bob, cx + u * 0.6f, ground - u * 1.1f + bob);
        c.drawRoundRect(rect, u * 0.25f, u * 0.25f, p);
        // Arme nach vorne (Hände Richtung Kamera)
        p.setColor(skin);
        float sway = (float) Math.sin(z.wobble * 1.3f) * u * 0.08f;
        c.drawCircle(cx - u * 0.55f, ground - u * 1.85f + bob + sway, u * 0.2f, p);
        c.drawCircle(cx + u * 0.55f, ground - u * 1.85f + bob - sway, u * 0.2f, p);
        // Kopf
        float headY = ground - u * 2.9f + bob;
        c.drawCircle(cx + (float) Math.sin(z.wobble) * u * 0.05f, headY, u * 0.45f, p);
        // Augen
        p.setColor(z.type == Game.BOSS ? Color.rgb(255, 230, 80) : Color.rgb(255, 60, 50));
        c.drawCircle(cx - u * 0.16f, headY - u * 0.02f, u * 0.08f, p);
        c.drawCircle(cx + u * 0.16f, headY - u * 0.02f, u * 0.08f, p);
        if (z.type == Game.BOSS) {
            // Stacheln auf dem Rücken
            p.setColor(Color.rgb(200, 190, 210));
            for (int i = -2; i <= 2; i++) {
                float sx = cx + i * u * 0.25f, sy = ground - u * 2.55f + bob;
                path.reset();
                path.moveTo(sx - u * 0.1f, sy);
                path.lineTo(sx, sy - u * 0.35f);
                path.lineTo(sx + u * 0.1f, sy);
                path.close();
                c.drawPath(path, p);
            }
        }
        // Lebensbalken für Brocken und Boss (Boss oben im HUD)
        if (z.type == Game.BRUTE && z.hp < z.maxHp) {
            float bw = u * 1.2f;
            p.setColor(Color.argb(170, 0, 0, 0));
            c.drawRect(cx - bw / 2, headY - u * 0.8f, cx + bw / 2, headY - u * 0.65f, p);
            p.setColor(Color.rgb(255, 80, 70));
            c.drawRect(cx - bw / 2, headY - u * 0.8f, cx - bw / 2 + bw * z.hp / z.maxHp, headY - u * 0.65f, p);
        }
    }

    private void drawBullets(Canvas c) {
        p.setStrokeCap(Paint.Cap.ROUND);
        for (Game.Bullet b : game.bullets) {
            float z0 = b.z, z1 = b.z - 0.7f;
            float s = PS(z0);
            p.setStrokeWidth(Math.max(2f, s * 0.07f));
            p.setColor(Color.rgb(255, 230, 120));
            c.drawLine(PX(b.x, z0), PY(1.0f, z0), PX(b.x, z1), PY(1.0f, z1), p);
        }
        p.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawSquad(Canvas c) {
        int n = game.shooters;
        if (n <= 0) return;
        int shown = Math.min(n, 60);
        float hw = game.squadHalfWidth();
        int cols = Math.max(1, Math.min(shown, Math.round(hw * 2 / 0.38f) + 1));
        int rows = (shown + cols - 1) / cols;
        float t = game.time;
        // Hintere Reihen zuerst
        for (int r = rows - 1; r >= 0; r--) {
            int inRow = Math.min(cols, shown - r * cols);
            for (int i = 0; i < inRow; i++) {
                float x = game.squadX + (inRow == 1 ? 0 : (i / (float) (inRow - 1) * 2 - 1) * hw * (inRow / (float) cols));
                float z = -r * 0.42f;
                soldier(c, x, z, t + r * 0.37f + i * 0.61f, r == 0);
            }
        }
        // Anzahl über dem Trupp
        float sx = PX(game.squadX, 0), sy = PY(2.3f, 0);
        String label = String.valueOf(n);
        text.setTextSize(26 * density);
        float tw = text.measureText(label) + 26 * density;
        rect.set(sx - tw / 2, sy - 30 * density, sx + tw / 2, sy + 4 * density);
        p.setColor(Color.argb(200, 20, 24, 34));
        c.drawRoundRect(rect, 17 * density, 17 * density, p);
        text.setColor(game.lastLoss < 0.4f ? Color.rgb(255, 110, 110) : Color.WHITE);
        c.drawText(label, sx, sy - 5 * density, text);
    }

    private void soldier(Canvas c, float x, float z, float t, boolean front) {
        float s = PS(z);
        float u = s * 0.22f;
        float cx = PX(x, z), ground = PY(0, z);
        float bob = (float) Math.abs(Math.sin(t * 9)) * u * 0.15f;
        p.setColor(Color.argb(60, 0, 0, 0));
        c.drawOval(cx - u * 0.9f, ground - u * 0.2f, cx + u * 0.9f, ground + u * 0.2f, p);
        // Beine
        float step = (float) Math.sin(t * 9) * u * 0.25f;
        p.setColor(Color.rgb(50, 56, 66));
        c.drawRect(cx - u * 0.45f, ground - u * 1.3f + step, cx - u * 0.1f, ground, p);
        c.drawRect(cx + u * 0.1f, ground - u * 1.3f - step, cx + u * 0.45f, ground, p);
        // Körper (von hinten)
        p.setColor(Color.rgb(62, 110, 170));
        rect.set(cx - u * 0.65f, ground - u * 2.7f - bob, cx + u * 0.65f, ground - u * 1.15f - bob);
        c.drawRoundRect(rect, u * 0.3f, u * 0.3f, p);
        // Rucksack
        p.setColor(Color.rgb(46, 84, 130));
        rect.set(cx - u * 0.4f, ground - u * 2.45f - bob, cx + u * 0.4f, ground - u * 1.55f - bob);
        c.drawRoundRect(rect, u * 0.15f, u * 0.15f, p);
        // Gewehr (zeigt nach vorne, rechts)
        p.setColor(Color.rgb(35, 35, 40));
        c.drawRect(cx + u * 0.45f, ground - u * 2.9f - bob, cx + u * 0.68f, ground - u * 1.8f - bob, p);
        // Helm
        p.setColor(Color.rgb(70, 92, 62));
        c.drawCircle(cx, ground - u * 3.0f - bob, u * 0.5f, p);
        // Mündungsfeuer
        if (front && rnd.nextFloat() < 0.35f) {
            p.setColor(Color.argb(220, 255, 220, 110));
            c.drawCircle(cx + u * 0.56f, ground - u * 3.0f - bob, u * 0.28f, p);
        }
    }

    private void drawParticles(Canvas c) {
        for (int i = 0; i < pCount; i++) {
            if (pz[i] + CAM_BACK < 1f) continue;
            float k = plife[i] / pmax[i];
            p.setColor(withAlpha(pcol[i], (int) (240 * k)));
            c.drawCircle(PX(px[i], pz[i]), PY(py[i], pz[i]), Math.max(1.5f, psize[i] * PS(pz[i]) * (0.5f + 0.5f * k)), p);
        }
    }

    private void drawFloatingTexts(Canvas c) {
        for (Game.Fx e : game.fx) {
            float k = e.t / e.life;
            if (e.type == Game.FX_GATE && e.text != null) {
                boolean boss = "BOSS!".equals(e.text);
                float sx = boss ? W / 2 : PX(e.x, 0.5f);
                float sy = boss ? H * 0.38f : PY(3.2f, 0.5f) - k * 60 * density;
                text.setTextSize((boss ? 56 : 34) * density * (1 + 0.2f * (1 - k)));
                int col = boss ? Color.rgb(255, 80, 80) : e.value > 0 || e.text.startsWith("×") || e.text.startsWith("Tempo") || e.text.startsWith("Schaden") ? Color.rgb(140, 210, 255) : e.value < 0 ? Color.rgb(255, 110, 110) : Color.WHITE;
                text.setColor(withAlpha(Color.BLACK, (int) (150 * (1 - k))));
                c.drawText(e.text, sx + 3, sy + 3, text);
                text.setColor(withAlpha(col, (int) (255 * (1 - k * k))));
                c.drawText(e.text, sx, sy, text);
            } else if (e.type == Game.FX_LOSE && e.value > 0) {
                text.setTextSize(24 * density);
                text.setColor(withAlpha(Color.rgb(255, 90, 90), (int) (255 * (1 - k))));
                c.drawText("−" + (int) e.value, PX(e.x, 0.4f), PY(2.2f, 0.4f) - k * 40 * density, text);
            }
        }
    }

    private void drawHud(Canvas c) {
        float d = density;
        // Roter Rand bei Verlusten
        if (lossFlash > 0) {
            float k = lossFlash / 0.35f, band = Math.min(W, H) * 0.07f;
            for (int i = 0; i < 4; i++) {
                p.setColor(Color.argb((int) (60 * k * (4 - i) / 4f), 255, 30, 30));
                float o = band * i / 4f;
                c.drawRect(0, o, W, o + band / 4f, p);
                c.drawRect(0, H - o - band / 4f, W, H - o, p);
                c.drawRect(o, 0, o + band / 4f, H, p);
                c.drawRect(W - o - band / 4f, 0, W - o, H, p);
            }
        }
        // Pause
        p.setColor(Color.argb(170, 18, 20, 26));
        c.drawCircle(34 * d, 44 * d, 22 * d, p);
        p.setColor(Color.WHITE);
        c.drawRect(27 * d, 36 * d, 31 * d, 52 * d, p);
        c.drawRect(37 * d, 36 * d, 41 * d, 52 * d, p);

        // Level und Fortschritt / Boss-Leben
        text.setTextSize(18 * d);
        text.setColor(Color.WHITE);
        c.drawText("Level " + game.level, W / 2, 34 * d, text);
        float bw = W * 0.62f, bx = W / 2 - bw / 2, by = 46 * d, bh = 12 * d;
        p.setColor(Color.argb(170, 18, 20, 26));
        rect.set(bx - 3 * d, by - 3 * d, bx + bw + 3 * d, by + bh + 3 * d);
        c.drawRoundRect(rect, 9 * d, 9 * d, p);
        if (game.boss != null) {
            float frac = Math.max(0, game.boss.hp / game.boss.maxHp);
            p.setColor(Color.rgb(200, 60, 230));
            rect.set(bx, by, bx + bw * frac, by + bh);
            c.drawRoundRect(rect, 6 * d, 6 * d, p);
            text.setTextSize(13 * d);
            c.drawText("BOSS  " + Math.round(frac * 100) + " %", W / 2, by + bh + 18 * d, text);
        } else {
            p.setColor(Color.rgb(255, 170, 60));
            rect.set(bx, by, bx + bw * game.progress(), by + bh);
            c.drawRoundRect(rect, 6 * d, 6 * d, p);
            text.setTextSize(16 * d);
            c.drawText("☠", bx + bw + 16 * d, by + bh, text);
        }
        // Werte
        text.setTextSize(13 * d);
        text.setColor(Color.argb(220, 255, 255, 255));
        String stats = "Schaden " + fmt(game.damage) + "   ·   Feuerrate " + fmt(game.fireRate) + "/s";
        c.drawText(stats, W / 2, by + bh + (game.boss != null ? 38 : 22) * d, text);

        // Hinweis zur Steuerung am Anfang
        if (game.time < 4f) {
            text.setTextSize(17 * d);
            text.setColor(withAlpha(Color.WHITE, (int) (255 * Math.min(1f, (4f - game.time)))));
            c.drawText("← Wischen zum Steuern →", W / 2, H - 70 * d, text);
        }
        // Ende
        if (game.state != Game.RUNNING) {
            p.setColor(Color.argb(140, 0, 0, 0));
            c.drawRect(0, 0, W, H, p);
            text.setTextSize(44 * d);
            text.setColor(game.state == Game.WON ? Color.rgb(255, 214, 102) : Color.rgb(255, 110, 110));
            c.drawText(game.state == Game.WON ? "Geschafft!" : "Überrannt!", W / 2, H * 0.42f, text);
        }
    }

    private static String fmt(float v) {
        float r = Math.round(v * 10) / 10f;
        return r == (int) r ? String.valueOf((int) r) : String.valueOf(r);
    }

    static int withAlpha(int color, int a) { return (Math.max(0, Math.min(255, a)) << 24) | (color & 0xFFFFFF); }

    static int darker(int c, float f) {
        return 0xFF000000 | ((int) (((c >> 16) & 255) * f) << 16) | ((int) (((c >> 8) & 255) * f) << 8) | (int) ((c & 255) * f);
    }

    static int lighter(int c, float f) {
        return 0xFF000000 | (Math.min(255, (int) (((c >> 16) & 255) * f)) << 16) | (Math.min(255, (int) (((c >> 8) & 255) * f)) << 8) | Math.min(255, (int) ((c & 255) * f));
    }
}
