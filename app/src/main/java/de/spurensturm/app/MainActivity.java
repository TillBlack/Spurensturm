package de.spurensturm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import de.spurensturm.game.Game;

public class MainActivity extends Activity implements GameView.Listener {

    private static final int MENU = 0, PLAY = 1, RESULT = 2;
    private static final int BG = Color.rgb(24, 22, 30);
    private static final int SURFACE = Color.rgb(38, 35, 48);
    private static final int TEXT = Color.rgb(240, 236, 230);
    private static final int MUTED = Color.rgb(160, 152, 150);
    private static final int ACCENT = Color.rgb(255, 150, 60);
    private static final int GOOD = Color.rgb(90, 175, 255);

    private SharedPreferences prefs;
    private int screen = MENU;
    private int level = 1, best = 1, wins, losses;
    private GameView view;
    private Object backCallback;
    private boolean backRegistered;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        else getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        if (Build.VERSION.SDK_INT >= 33) backCallback = Back33.create(this);
        prefs = getSharedPreferences("spurensturm", MODE_PRIVATE);
        level = Math.max(1, prefs.getInt("level", 1));
        best = Math.max(level, prefs.getInt("best", 1));
        wins = prefs.getInt("wins", 0);
        losses = prefs.getInt("losses", 0);
        showMenu();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (view != null && screen == PLAY) view.stop();
        save();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (view != null && screen == PLAY) onPauseRequested();
    }

    private void save() {
        prefs.edit().putInt("level", level).putInt("best", best).putInt("wins", wins).putInt("losses", losses).apply();
    }

    // ------------------------------------------------------------------ Zurück-Taste

    private void setScreen(int s) {
        screen = s;
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            boolean need = s != MENU;
            if (need && !backRegistered) { Back33.register(this, backCallback); backRegistered = true; }
            else if (!need && backRegistered) { Back33.unregister(this, backCallback); backRegistered = false; }
        }
    }

    void goBack() {
        if (screen == PLAY) onPauseRequested();
        else if (screen == MENU) finish();
        else showMenu();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (screen != MENU) goBack();
        else super.onBackPressed();
    }

    private static final class Back33 {
        static Object create(MainActivity a) {
            OnBackInvokedCallback cb = a::goBack;
            return cb;
        }
        static void register(MainActivity a, Object cb) {
            a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, (OnBackInvokedCallback) cb);
        }
        static void unregister(MainActivity a, Object cb) {
            a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) cb);
        }
    }

    // ------------------------------------------------------------------ Seiten

    private LinearLayout page() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(28));
        scroll.addView(root, new FrameLayout.LayoutParams(-1, -1));
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            int l, t, r, b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l = i.left; t = i.top; r = i.right; b = i.bottom;
            } else {
                l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
                r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(l, t, r, b);
            return insets;
        });
        setContentView(scroll);
        scroll.requestApplyInsets();
        return root;
    }

    private void showMenu() {
        stopGame();
        setScreen(MENU);
        LinearLayout root = page();
        TextView title = text("SPURENSTURM", 40, ACCENT);
        title.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        title.setLetterSpacing(0.06f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, margin(0, 50, 0, 6));
        TextView sub = text("Sammle Schützen und Upgrades – und halte die Horde in der Mitte auf.", 15, MUTED);
        sub.setGravity(Gravity.CENTER);
        root.addView(sub, margin(0, 0, 0, 40));

        TextView lv = text("Level " + level, 56, TEXT);
        lv.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        lv.setGravity(Gravity.CENTER);
        root.addView(lv);
        TextView info = text("Bestes Level: " + best + "  ·  Geschafft: " + wins, 14, MUTED);
        info.setGravity(Gravity.CENTER);
        root.addView(info, margin(0, 4, 0, 36));

        Button play = button("▶  Spielen", ACCENT, Color.WHITE);
        play.setOnClickListener(v -> startLevel());
        root.addView(play, margin(0, 0, 0, 14));

        if (level > 1) {
            Button restart = button("Von Level 1 neu beginnen", SURFACE, MUTED);
            restart.setTextSize(15);
            restart.setOnClickListener(v -> new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                    .setTitle("Neu beginnen?")
                    .setMessage("Du startest wieder bei Level 1. Dein bestes Level bleibt gespeichert.")
                    .setPositiveButton("Neu beginnen", (d, w) -> { level = 1; save(); showMenu(); })
                    .setNegativeButton("Abbrechen", null).show());
            root.addView(restart, margin(0, 0, 0, 30));
        }

        TextView help = text("So geht's: Wische, um die Spur zu wechseln. Links gibt es +1-Schützen, in der Mitte kommen die Zombies, "
                + "rechts warten Schieß-Upgrades. Bleib nicht zu lange weg – kurz vor dir laufen die Zombies auf deine Spur! "
                + "Am Ende jedes Levels kommt ein Boss durch die Mitte.", 14, MUTED);
        help.setGravity(Gravity.CENTER);
        root.addView(help, margin(0, 10, 0, 0));
    }

    private void startLevel() {
        setScreen(PLAY);
        Game g = new Game(level, System.nanoTime());
        view = new GameView(this, g, this);
        view.setBackgroundColor(Color.BLACK);
        setContentView(view);
        view.start();
    }

    private void stopGame() {
        if (view != null) { view.stop(); view = null; }
    }

    @Override
    public void onPauseRequested() {
        if (view == null || screen != PLAY) return;
        view.stop();
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Pause")
                .setPositiveButton("Weiterspielen", (d, w) -> { if (view != null && screen == PLAY) view.start(); })
                .setNegativeButton("Level abbrechen", (d, w) -> showMenu())
                .setOnCancelListener(d -> { if (view != null && screen == PLAY) view.start(); })
                .show();
    }

    @Override
    public void onLevelEnd(Game g) {
        if (screen != PLAY) return;
        boolean won = g.state == Game.WON;
        if (won) {
            wins++;
            level = g.level + 1;
            best = Math.max(best, level);
        } else {
            losses++;
        }
        save();
        stopGame();
        setScreen(RESULT);
        LinearLayout root = page();
        TextView t = text(won ? "Level " + g.level + " geschafft!" : "Überrannt!", 36, won ? Color.rgb(255, 214, 102) : Color.rgb(255, 110, 110));
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        root.addView(t, margin(0, 80, 0, 10));
        String detail = won ? "Der Boss ist besiegt. Zombies erledigt: " + g.kills
                : (g.boss != null ? "Der Boss hatte noch " + Math.round(100 * g.boss.hp / g.boss.maxHp) + " % Leben." : "Die Horde war zu stark.")
                + " Zombies erledigt: " + g.kills;
        TextView dt = text(detail, 16, MUTED);
        dt.setGravity(Gravity.CENTER);
        root.addView(dt, margin(0, 0, 0, 40));
        if (won) {
            Button next = button("Nächstes Level  ▶", ACCENT, Color.WHITE);
            next.setOnClickListener(v -> startLevel());
            root.addView(next, margin(0, 0, 0, 14));
        } else {
            Button again = button("↻  Nochmal", ACCENT, Color.WHITE);
            again.setOnClickListener(v -> startLevel());
            root.addView(again, margin(0, 0, 0, 14));
            TextView tip = text("Tipp: Sammle früh viele +1-Schützen, aber geh rechtzeitig zurück in die Mitte, wenn ein Rudel kommt. Gegen den Boss helfen Schaden-Upgrades.", 14, MUTED);
            tip.setGravity(Gravity.CENTER);
            root.addView(tip, margin(0, 6, 0, 20));
        }
        Button menu = button("Menü", SURFACE, TEXT);
        menu.setOnClickListener(v -> showMenu());
        root.addView(menu);
    }

    // ------------------------------------------------------------------ Bausteine

    private TextView text(String s, float size, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        return t;
    }

    private Button button(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(19);
        b.setTextColor(fg);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setStateListAnimator(null);
        b.setPadding(dp(12), dp(16), dp(12), dp(16));
        GradientDrawable g = new GradientDrawable();
        g.setColor(bg);
        g.setCornerRadius(dp(18));
        GradientDrawable m = new GradientDrawable();
        m.setColor(Color.WHITE);
        m.setCornerRadius(dp(18));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), g, m));
        return b;
    }

    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
