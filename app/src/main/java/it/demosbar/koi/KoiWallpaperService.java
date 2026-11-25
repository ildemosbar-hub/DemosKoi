package it.demosbar.koi;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Demo's Koi — sfondo animato: laghetto di carpe koi al neon in stile cyberpunk giapponese.
 * Tocca lo schermo: l'acqua si increspa e le carpe scappano.
 */
public class KoiWallpaperService extends WallpaperService {

    // Palette Demo's
    static final int INK = 0xFF07070A;
    static final int NEON_RED = 0xFFFF2A3D;
    static final int CREAM = 0xFFF2EBE0;

    @Override
    public Engine onCreateEngine() {
        return new KoiEngine();
    }

    class KoiEngine extends Engine implements Choreographer.FrameCallback {

        private final Random rnd = new Random();
        private final List<Koi> fish = new ArrayList<>();
        private final List<Ripple> ripples = new ArrayList<>();
        private final List<Mote> motes = new ArrayList<>();

        private boolean visible = false;
        private boolean useHw = true;
        private int w, h;
        private float dp = 3f;
        private long lastNanos = 0;
        private float time = 0;
        private long lastRippleMs = 0;

        private Bitmap bgBitmap, signBitmap;
        private float signAlpha = 1f;
        private float flickerTimer = 4f;
        private float flickerLeft = 0f;

        // Paint riutilizzati
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Path body = new Path();
        private final Path tail = new Path();
        private final Path finL = new Path();
        private final Path finR = new Path();
        private final float[] polyX = new float[Koi.N * 2 + 4];
        private final float[] polyY = new float[Koi.N * 2 + 4];

        KoiEngine() {
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeCap(Paint.Cap.ROUND);
            glow.setStrokeJoin(Paint.Join.ROUND);
            fill.setStyle(Paint.Style.FILL);
        }

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(true);
            dp = getResources().getDisplayMetrics().density;
        }

        @Override
        public void onVisibilityChanged(boolean v) {
            visible = v;
            Choreographer ch = Choreographer.getInstance();
            ch.removeFrameCallback(this);
            if (v) {
                lastNanos = 0;
                ch.postFrameCallback(this);
            }
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            if (width <= 0 || height <= 0) return;
            // Fold 5: aperto/chiuso cambia dimensione -> riscalo le posizioni
            if (w > 0 && h > 0 && (w != width || h != height)) {
                float sx = width / (float) w, sy = height / (float) h;
                for (Koi k : fish) k.rescale(sx, sy);
                for (Mote m : motes) { m.x *= sx; m.y *= sy; }
                ripples.clear();
            }
            w = width;
            h = height;
            buildBackground();
            buildSign();
            if (fish.isEmpty()) spawn();
            float minSide = Math.min(w, h);
            for (Koi k : fish) k.setScale(minSide);
            if (visible) drawFrame();
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            Choreographer.getInstance().removeFrameCallback(this);
            super.onSurfaceDestroyed(holder);
        }

        @Override
        public void onDestroy() {
            Choreographer.getInstance().removeFrameCallback(this);
            if (bgBitmap != null) { bgBitmap.recycle(); bgBitmap = null; }
            if (signBitmap != null) { signBitmap.recycle(); signBitmap = null; }
            super.onDestroy();
        }

        @Override
        public void onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            long now = System.currentTimeMillis();
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                int i = e.getActionIndex();
                touch(e.getX(i), e.getY(i), true);
                lastRippleMs = now;
            } else if (a == MotionEvent.ACTION_MOVE && now - lastRippleMs > 90) {
                touch(e.getX(), e.getY(), false);
                lastRippleMs = now;
            }
            super.onTouchEvent(e);
        }

        @Override
        public android.os.Bundle onCommand(String action, int x, int y, int z,
                                           android.os.Bundle extras, boolean resultRequested) {
            // Alcuni launcher inviano i tap come comandi invece che come touch
            if ("android.wallpaper.tap".equals(action)) {
                touch(x, y, true);
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        private void touch(float x, float y, boolean strong) {
            float minSide = Math.min(w, h);
            ripples.add(new Ripple(x, y, minSide * (strong ? 0.32f : 0.18f), strong ? 1f : 0.55f));
            if (ripples.size() > 14) ripples.remove(0);
            float radius = minSide * (strong ? 0.55f : 0.35f);
            for (Koi k : fish) k.scare(x, y, radius, strong);
        }

        private void spawn() {
            int count = 7;
            for (int i = 0; i < count; i++) {
                int style;
                if (i < 3) style = Koi.STYLE_RED;
                else if (i < 6) style = Koi.STYLE_KOHAKU;
                else style = Koi.STYLE_GHOST;
                fish.add(new Koi(rnd, w, h, style));
            }
            for (int i = 0; i < 46; i++) motes.add(new Mote(rnd, w, h));
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (!visible) return;
            float dt = lastNanos == 0 ? 0.016f : (frameTimeNanos - lastNanos) / 1e9f;
            if (dt > 0.05f) dt = 0.05f;
            if (dt < 0f) dt = 0.016f;
            lastNanos = frameTimeNanos;
            update(dt);
            drawFrame();
            Choreographer.getInstance().postFrameCallback(this);
        }

        private void update(float dt) {
            time += dt;
            for (Koi k : fish) k.update(dt, w, h, fish);
            Iterator<Ripple> it = ripples.iterator();
            while (it.hasNext()) if (!it.next().update(dt)) it.remove();
            for (Mote m : motes) m.update(dt, w, h, rnd);

            // sfarfallio dell'insegna al neon
            flickerTimer -= dt;
            if (flickerTimer <= 0) {
                flickerLeft = 0.35f + rnd.nextFloat() * 0.4f;
                flickerTimer = 6f + rnd.nextFloat() * 9f;
            }
            if (flickerLeft > 0) {
                flickerLeft -= dt;
                signAlpha = rnd.nextFloat() < 0.5f ? 0.25f : 0.95f;
            } else {
                signAlpha = 0.9f + 0.1f * (float) Math.sin(time * 2.1f);
            }
        }

        private void drawFrame() {
            SurfaceHolder holder = getSurfaceHolder();
            Canvas c = null;
            try {
                if (useHw) {
                    try {
                        c = holder.lockHardwareCanvas();
                    } catch (Throwable t) {
                        useHw = false;
                    }
                }
                if (c == null) c = holder.lockCanvas();
                if (c != null) render(c);
            } catch (Throwable ignored) {
            } finally {
                if (c != null) {
                    try { holder.unlockCanvasAndPost(c); } catch (Throwable ignored) { }
                }
            }
        }

        private void render(Canvas c) {
            if (bgBitmap != null) c.drawBitmap(bgBitmap, 0, 0, null);
            else c.drawColor(INK);

            if (signBitmap != null) {
                bmpPaint.setAlpha((int) (255 * signAlpha));
                c.drawBitmap(signBitmap, 0, 0, bmpPaint);
            }

            // pulviscolo / bollicine
            fill.setShader(null);
            for (Mote m : motes) {
                fill.setColor(m.red ? NEON_RED : CREAM);
                fill.setAlpha((int) (m.alpha(time) * 255));
                c.drawCircle(m.x, m.y, m.r * dp, fill);
            }

            for (Ripple r : ripples) drawRipple(c, r);
            for (Koi k : fish) drawKoi(c, k);
        }

        // ---------- disegno neon ----------

        private void neon(Canvas c, Path p, int color, float core, float intensity) {
            glow.setColor(color);
            glow.setAlpha((int) (16 * intensity));
            glow.setStrokeWidth(core * 9f);
            c.drawPath(p, glow);
            glow.setAlpha((int) (38 * intensity));
            glow.setStrokeWidth(core * 5f);
            c.drawPath(p, glow);
            glow.setAlpha((int) (105 * intensity));
            glow.setStrokeWidth(core * 2.4f);
            c.drawPath(p, glow);
            glow.setAlpha((int) (255 * intensity));
            glow.setStrokeWidth(core * 1.1f);
            c.drawPath(p, glow);
            glow.setColor(blendWhite(color, 0.7f));
            glow.setAlpha((int) (230 * intensity));
            glow.setStrokeWidth(core * 0.45f);
            c.drawPath(p, glow);
        }

        private void neonCircle(Canvas c, float x, float y, float r, int color, float core, float intensity) {
            float[] widths = {9f, 5f, 2.4f, 1.1f};
            int[] alphas = {16, 38, 105, 255};
            glow.setColor(color);
            for (int i = 0; i < widths.length; i++) {
                glow.setAlpha((int) (alphas[i] * intensity));
                glow.setStrokeWidth(core * widths[i]);
                c.drawCircle(x, y, r, glow);
            }
        }

        private void drawRipple(Canvas c, Ripple r) {
            float p = r.progress();
            float fade = (1f - p) * r.strength;
            float core = 1.3f * dp;
            neonCircle(c, r.x, r.y, r.radius(), NEON_RED, core, fade * 0.9f);
            if (p > 0.12f) {
                neonCircle(c, r.x, r.y, r.radius() * 0.62f, CREAM, core * 0.7f, fade * 0.45f);
            }
            if (p > 0.25f) {
                neonCircle(c, r.x, r.y, r.radius() * 0.32f, NEON_RED, core * 0.6f, fade * 0.35f);
            }
        }

        private void drawKoi(Canvas c, Koi k) {
            final int N = Koi.N;
            float[] px = k.px, py = k.py;
            float len = k.len;

            // contorno del corpo (lato sinistro testa->coda, poi destro coda->testa)
            int n = 0;
            float hdx = px[0] - px[1], hdy = py[0] - py[1];
            float hl = (float) Math.hypot(hdx, hdy);
            if (hl < 0.0001f) { hdx = 1; hdy = 0; hl = 1; }
            hdx /= hl; hdy /= hl;
            // muso
            polyX[n] = px[0] + hdx * len * 0.07f;
            polyY[n] = py[0] + hdy * len * 0.07f;
            n++;
            for (int i = 0; i < N; i++) {
                float[] nv = normalAt(px, py, i);
                float wv = k.width(i);
                polyX[n] = px[i] + nv[0] * wv;
                polyY[n] = py[i] + nv[1] * wv;
                n++;
            }
            for (int i = N - 1; i >= 0; i--) {
                float[] nv = normalAt(px, py, i);
                float wv = k.width(i);
                polyX[n] = px[i] - nv[0] * wv;
                polyY[n] = py[i] - nv[1] * wv;
                n++;
            }
            smoothClosed(body, polyX, polyY, n);

            // pinna caudale
            float tx = px[N - 1], ty = py[N - 1];
            float tdx = px[N - 1] - px[N - 2], tdy = py[N - 1] - py[N - 2];
            float tl = (float) Math.hypot(tdx, tdy);
            if (tl < 0.0001f) { tdx = -hdx; tdy = -hdy; tl = 1; }
            tdx /= tl; tdy /= tl;
            float sway = (float) Math.sin(k.phase * 1.0f) * 0.38f;
            float fl = len * 0.30f;
            float aA = 0.55f + sway, aB = -0.55f + sway;
            float Ax = tx + rotX(tdx, tdy, aA) * fl, Ay = ty + rotY(tdx, tdy, aA) * fl;
            float Bx = tx + rotX(tdx, tdy, aB) * fl, By = ty + rotY(tdx, tdy, aB) * fl;
            float Mx = tx + rotX(tdx, tdy, sway) * fl * 0.45f, My = ty + rotY(tdx, tdy, sway) * fl * 0.45f;
            float cA = 1.1f + sway, cB = -1.1f + sway;
            tail.reset();
            tail.moveTo(tx, ty);
            tail.quadTo(tx + rotX(tdx, tdy, cA) * fl * 0.55f, ty + rotY(tdx, tdy, cA) * fl * 0.55f, Ax, Ay);
            tail.quadTo(Mx, My, Bx, By);
            tail.quadTo(tx + rotX(tdx, tdy, cB) * fl * 0.55f, ty + rotY(tdx, tdy, cB) * fl * 0.55f, tx, ty);
            tail.close();

            // pinne pettorali
            int fi = 3;
            float[] nv = normalAt(px, py, fi);
            float bdx = px[fi + 1] - px[fi], bdy = py[fi + 1] - py[fi];
            float bl = (float) Math.hypot(bdx, bdy);
            if (bl < 0.0001f) bl = 1;
            bdx /= bl; bdy /= bl;
            float flap = 0.5f + 0.5f * (float) Math.sin(k.phase * 1.3f);
            float wv = k.width(fi);
            buildFin(finL, px[fi], py[fi], nv[0], nv[1], bdx, bdy, wv, len, flap);
            buildFin(finR, px[fi], py[fi], -nv[0], -nv[1], bdx, bdy, wv, len, 1f - flap * 0.8f);

            int line = k.style == Koi.STYLE_KOHAKU || k.style == Koi.STYLE_GHOST ? CREAM : NEON_RED;
            float core = Math.max(1.2f * dp, len * 0.012f);
            float intensity = k.style == Koi.STYLE_GHOST ? 0.55f : 1f;

            // riempimento tenue
            fill.setShader(null);
            fill.setColor(line);
            fill.setAlpha(k.style == Koi.STYLE_RED ? 34 : 22);
            c.drawPath(tail, fill);
            c.drawPath(finL, fill);
            c.drawPath(finR, fill);
            fill.setAlpha(k.style == Koi.STYLE_RED ? 46 : 26);
            c.drawPath(body, fill);

            // macchie rosse (kohaku)
            if (k.style == Koi.STYLE_KOHAKU) {
                c.save();
                c.clipPath(body);
                for (int s = 0; s < k.spotT.length; s++) {
                    float tt = k.spotT[s] * (N - 1);
                    int i0 = (int) tt;
                    int i1 = Math.min(N - 1, i0 + 1);
                    float f = tt - i0;
                    float sx = px[i0] + (px[i1] - px[i0]) * f;
                    float sy = py[i0] + (py[i1] - py[i0]) * f;
                    float[] sn = normalAt(px, py, i0);
                    float side = k.spotSide[s] * k.width(i0);
                    sx += sn[0] * side;
                    sy += sn[1] * side;
                    float rr = k.spotR[s] * len;
                    fill.setColor(NEON_RED);
                    fill.setAlpha(60);
                    c.drawCircle(sx, sy, rr * 1.5f, fill);
                    fill.setAlpha(170);
                    c.drawCircle(sx, sy, rr, fill);
                }
                c.restore();
            }

            neon(c, tail, line, core * 0.8f, intensity * 0.85f);
            neon(c, finL, line, core * 0.7f, intensity * 0.8f);
            neon(c, finR, line, core * 0.7f, intensity * 0.8f);
            neon(c, body, line, core, intensity);

            // pinna dorsale (linea centrale)
            glow.setColor(line);
            glow.setAlpha((int) (90 * intensity));
            glow.setStrokeWidth(core * 0.9f);
            for (int i = 3; i < 8; i++) {
                c.drawLine(px[i], py[i], px[i + 1], py[i + 1], glow);
            }

            // occhi
            float[] en = normalAt(px, py, 1);
            float ew = k.width(1) * 0.62f;
            float ex = px[0] * 0.6f + px[1] * 0.4f, ey = py[0] * 0.6f + py[1] * 0.4f;
            fill.setColor(CREAM);
            fill.setAlpha((int) (240 * intensity));
            float er = Math.max(1.3f * dp, len * 0.017f);
            c.drawCircle(ex + en[0] * ew, ey + en[1] * ew, er, fill);
            c.drawCircle(ex - en[0] * ew, ey - en[1] * ew, er, fill);
            // baffi
            glow.setColor(line);
            glow.setAlpha((int) (120 * intensity));
            glow.setStrokeWidth(core * 0.5f);
            float mx = polyX[0], my = polyY[0];
            float wob = (float) Math.sin(k.phase * 0.7f) * 0.3f;
            for (int sgn = -1; sgn <= 1; sgn += 2) {
                float a = sgn * (2.3f + wob);
                c.drawLine(mx, my, mx + rotX(hdx, hdy, a) * len * 0.09f,
                        my + rotY(hdx, hdy, a) * len * 0.09f, glow);
            }
        }

        private void buildFin(Path p, float x, float y, float ox, float oy,
                              float bdx, float bdy, float wv, float len, float flap) {
            float baseX = x + ox * wv * 0.85f, baseY = y + oy * wv * 0.85f;
            float dirX = bdx * 0.75f + ox * (0.45f + 0.55f * flap);
            float dirY = bdy * 0.75f + oy * (0.45f + 0.55f * flap);
            float dl = (float) Math.hypot(dirX, dirY);
            dirX /= dl; dirY /= dl;
            float fl = len * 0.17f;
            float tipX = baseX + dirX * fl, tipY = baseY + dirY * fl;
            p.reset();
            p.moveTo(baseX - bdx * len * 0.03f, baseY - bdy * len * 0.03f);
            p.quadTo(tipX + ox * len * 0.05f - bdx * len * 0.03f, tipY + oy * len * 0.05f - bdy * len * 0.03f, tipX, tipY);
            p.quadTo(baseX + dirX * fl * 0.4f + bdx * len * 0.05f, baseY + dirY * fl * 0.4f + bdy * len * 0.05f,
                    baseX + bdx * len * 0.06f, baseY + bdy * len * 0.06f);
            p.close();
        }

        private final float[] tmpN = new float[2];

        private float[] normalAt(float[] px, float[] py, int i) {
            int a = Math.max(0, i - 1), b = Math.min(px.length - 1, i + 1);
            float dx = px[a] - px[b], dy = py[a] - py[b]; // verso la testa
            float l = (float) Math.hypot(dx, dy);
            if (l < 0.0001f) { tmpN[0] = 0; tmpN[1] = 1; return tmpN; }
            tmpN[0] = -dy / l;
            tmpN[1] = dx / l;
            return tmpN;
        }

        private void smoothClosed(Path p, float[] xs, float[] ys, int n) {
            p.reset();
            float mx0 = (xs[n - 1] + xs[0]) * 0.5f, my0 = (ys[n - 1] + ys[0]) * 0.5f;
            p.moveTo(mx0, my0);
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                p.quadTo(xs[i], ys[i], (xs[i] + xs[j]) * 0.5f, (ys[i] + ys[j]) * 0.5f);
            }
            p.close();
        }

        // ---------- livelli statici ----------

        private void buildBackground() {
            if (bgBitmap != null) bgBitmap.recycle();
            bgBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bgBitmap);
            c.drawColor(INK);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            float maxSide = Math.max(w, h);

            // alone rosso dell'acqua
            p.setShader(new RadialGradient(w * 0.5f, h * 0.42f, maxSide * 0.75f,
                    new int[]{0x2EFF2A3D, 0x10FF2A3D, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);

            // griglia da monitor
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1f, dp * 0.6f));
            p.setColor(0x16FF2A3D);
            float step = 44 * dp;
            for (float x = (w % step) / 2f; x < w; x += step) c.drawLine(x, 0, x, h, p);
            for (float y = (h % step) / 2f; y < h; y += step) c.drawLine(0, y, w, y, p);

            // seigaiha (onde giapponesi) in basso
            drawSeigaiha(c, h * 0.84f);

            // scanline
            p.setStrokeWidth(Math.max(1f, dp * 0.5f));
            p.setColor(0x14000000);
            for (float y = 0; y < h; y += 3 * dp) c.drawLine(0, y, w, y, p);

            // vignettatura
            p.setStyle(Paint.Style.FILL);
            p.setShader(new RadialGradient(w * 0.5f, h * 0.5f, maxSide * 0.72f,
                    new int[]{0x00000000, 0x00000000, 0xB0000000}, new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);

            // cornice rossa sottile con angoli a greca
            drawFrameBorder(c);
        }

        private void drawSeigaiha(Canvas c, float top) {
            float r = 24 * dp;
            Paint f = new Paint(Paint.ANTI_ALIAS_FLAG);
            f.setStyle(Paint.Style.FILL);
            f.setColor(INK);
            Paint s = new Paint(Paint.ANTI_ALIAS_FLAG);
            s.setStyle(Paint.Style.STROKE);
            s.setStrokeWidth(1.2f * dp);
            int row = 0;
            for (float y = top; y < h + r * 2; y += r * 0.5f, row++) {
                float off = (row % 2 == 0) ? 0 : r;
                float depth = Math.min(1f, (y - top) / (h - top + 1));
                // sfuma verso l'alto senza bande scure
                float fadeIn = Math.min(1f, depth * 3f);
                int alpha = (int) (110 * Math.pow(depth, 0.7));
                f.setAlpha((int) (255 * fadeIn));
                for (float x = -r * 2 + off; x < w + r * 2; x += r * 2) {
                    c.drawCircle(x, y, r, f);
                    for (int k = 0; k < 3; k++) {
                        s.setColor(NEON_RED);
                        s.setAlpha(Math.max(0, alpha - k * 10));
                        c.drawCircle(x, y, r * (1f - k * 0.3f), s);
                    }
                }
            }
        }

        private void drawFrameBorder(Canvas c) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setColor(NEON_RED);
            float m = 10 * dp;
            p.setAlpha(70);
            p.setStrokeWidth(1f * dp);
            c.drawRect(m, m, w - m, h - m, p);
            // angoli a greca
            p.setAlpha(150);
            p.setStrokeWidth(1.4f * dp);
            float u = 7 * dp;
            drawGreek(c, p, m, m, u, 1, 1);
            drawGreek(c, p, w - m, m, u, -1, 1);
            drawGreek(c, p, m, h - m, u, 1, -1);
            drawGreek(c, p, w - m, h - m, u, -1, -1);
        }

        private void drawGreek(Canvas c, Paint p, float x, float y, float u, int sx, int sy) {
            Path g = new Path();
            g.moveTo(x, y + sy * u * 4);
            g.lineTo(x, y);
            g.lineTo(x + sx * u * 4, y);
            g.lineTo(x + sx * u * 4, y + sy * u * 3);
            g.lineTo(x + sx * u * 1, y + sy * u * 3);
            g.lineTo(x + sx * u * 1, y + sy * u * 1);
            g.lineTo(x + sx * u * 3, y + sy * u * 1);
            g.lineTo(x + sx * u * 3, y + sy * u * 2);
            c.drawPath(g, p);
        }

        private void buildSign() {
            if (signBitmap != null) signBitmap.recycle();
            signBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(signBitmap);
            float minSide = Math.min(w, h);
            float cx = w * 0.5f, cy = h * 0.40f;
            float R = minSide * 0.21f;

            // ensō: pennellata circolare con spessore variabile
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            int segs = 140;
            double start = Math.toRadians(118), sweep = Math.toRadians(326);
            float[][] layers = {{3.2f, 12}, {2.0f, 30}, {1.25f, 90}, {1f, 200}};
            for (float[] L : layers) {
                p.setColor(NEON_RED);
                p.setAlpha((int) L[1]);
                for (int i = 0; i < segs; i++) {
                    double t0 = i / (double) segs, t1 = (i + 1) / (double) segs;
                    double a0 = start + sweep * t0, a1 = start + sweep * t1;
                    float thick = (float) (R * 0.085 * (0.25 + 0.75 * Math.pow(Math.sin(Math.PI * Math.min(1, t0 * 1.15)), 0.7)));
                    if (t0 > 0.87) thick *= (float) (1 - (t0 - 0.87) / 0.13 * 0.8);
                    p.setStrokeWidth(Math.max(dp, thick * L[0]));
                    c.drawLine(cx + (float) Math.cos(a0) * R, cy + (float) Math.sin(a0) * R,
                            cx + (float) Math.cos(a1) * R, cy + (float) Math.sin(a1) * R, p);
                }
            }
            // anima chiara dell'ensō
            p.setColor(0xFFFFC7CC);
            p.setAlpha(170);
            for (int i = 0; i < segs; i++) {
                double t0 = i / (double) segs, t1 = (i + 1) / (double) segs;
                double a0 = start + sweep * t0, a1 = start + sweep * t1;
                float thick = (float) (R * 0.02 * (0.3 + 0.7 * Math.sin(Math.PI * Math.min(1, t0 * 1.15))));
                p.setStrokeWidth(Math.max(dp * 0.6f, thick));
                c.drawLine(cx + (float) Math.cos(a0) * R, cy + (float) Math.sin(a0) * R,
                        cx + (float) Math.cos(a1) * R, cy + (float) Math.sin(a1) * R, p);
            }

            // D corsiva al neon
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC));
            t.setTextAlign(Paint.Align.CENTER);
            t.setTextSize(R * 1.25f);
            float ty = cy + R * 0.43f;
            t.setColor(CREAM);
            t.setShadowLayer(R * 0.12f, 0, 0, NEON_RED);
            c.drawText("D", cx, ty, t);
            t.setShadowLayer(R * 0.04f, 0, 0, 0xFFFF6A78);
            c.drawText("D", cx, ty, t);

            // scritta Demo's sotto l'ensō
            t.setTextSize(R * 0.34f);
            t.setShadowLayer(R * 0.06f, 0, 0, NEON_RED);
            t.setColor(CREAM);
            c.drawText("Demo's", cx, cy + R * 1.55f, t);

            // kanji verticali
            drawVertical(c, "デモズ", w - 30 * dp, h * 0.16f, 22 * dp, NEON_RED, 200);
        }

        private void drawVertical(Canvas c, String s, float x, float y, float size, int color, int alpha) {
            Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
            t.setTextSize(size);
            t.setTextAlign(Paint.Align.CENTER);
            t.setColor(color);
            t.setAlpha(alpha);
            t.setShadowLayer(size * 0.35f, 0, 0, NEON_RED);
            for (int i = 0; i < s.length(); i++) {
                c.drawText(String.valueOf(s.charAt(i)), x, y + i * size * 1.25f, t);
            }
        }
    }

    // ---------- utilità ----------

    static float rotX(float x, float y, float a) {
        return (float) (x * Math.cos(a) - y * Math.sin(a));
    }

    static float rotY(float x, float y, float a) {
        return (float) (x * Math.sin(a) + y * Math.cos(a));
    }

    static int blendWhite(int c, float f) {
        int r = Color.red(c), g = Color.green(c), b = Color.blue(c);
        r = (int) (r + (255 - r) * f);
        g = (int) (g + (255 - g) * f);
        b = (int) (b + (255 - b) * f);
        return Color.rgb(r, g, b);
    }

    // ---------- entità ----------

    static final class Ripple {
        final float x, y, maxR, strength;
        float t = 0;
        static final float LIFE = 1.6f;

        Ripple(float x, float y, float maxR, float strength) {
            this.x = x; this.y = y; this.maxR = maxR; this.strength = strength;
        }

        boolean update(float dt) { t += dt; return t < LIFE; }

        float progress() { return Math.min(1f, t / LIFE); }

        float radius() {
            float p = progress();
            return maxR * (1f - (1f - p) * (1f - p)); // ease-out
        }
    }

    static final class Mote {
        float x, y, vx, vy, r, phase;
        boolean red;

        Mote(Random rnd, int w, int h) {
            x = rnd.nextFloat() * Math.max(1, w);
            y = rnd.nextFloat() * Math.max(1, h);
            vx = (rnd.nextFloat() - 0.5f) * 8f;
            vy = -4f - rnd.nextFloat() * 10f;
            r = 0.6f + rnd.nextFloat() * 1.3f;
            phase = rnd.nextFloat() * 6.28f;
            red = rnd.nextFloat() < 0.4f;
        }

        void update(float dt, int w, int h, Random rnd) {
            x += vx * dt;
            y += vy * dt;
            if (y < -10) { y = h + 10; x = rnd.nextFloat() * w; }
            if (x < -10) x = w + 10;
            if (x > w + 10) x = -10;
        }

        float alpha(float time) {
            return 0.18f + 0.22f * (0.5f + 0.5f * (float) Math.sin(time * 1.3f + phase));
        }
    }

    static final class Koi {
        static final int N = 14;
        static final int STYLE_RED = 0, STYLE_KOHAKU = 1, STYLE_GHOST = 2;

        final float[] px = new float[N], py = new float[N];
        final int style;
        final Random rnd;
        final float sizeFactor;
        float len, seg;
        float angle, speed, baseSpeed, wander, phase;
        float fear, fx, fy;
        final float[] spotT, spotSide, spotR;

        Koi(Random rnd, int w, int h, int style) {
            this.rnd = rnd;
            this.style = style;
            sizeFactor = (style == STYLE_GHOST ? 0.75f : 0.85f) + rnd.nextFloat() * 0.45f;
            setScale(Math.min(w, h));
            float x = w * (0.2f + rnd.nextFloat() * 0.6f);
            float y = h * (0.2f + rnd.nextFloat() * 0.6f);
            angle = rnd.nextFloat() * 6.283f;
            for (int i = 0; i < N; i++) {
                px[i] = x - (float) Math.cos(angle) * seg * i;
                py[i] = y - (float) Math.sin(angle) * seg * i;
            }
            phase = rnd.nextFloat() * 6.28f;
            speed = baseSpeed;
            int spots = 2 + rnd.nextInt(3);
            spotT = new float[spots];
            spotSide = new float[spots];
            spotR = new float[spots];
            for (int i = 0; i < spots; i++) {
                spotT[i] = 0.05f + rnd.nextFloat() * 0.6f;
                spotSide[i] = (rnd.nextFloat() - 0.5f) * 1.2f;
                spotR[i] = 0.05f + rnd.nextFloat() * 0.06f;
            }
        }

        void setScale(float minSide) {
            float newLen = minSide * 0.20f * sizeFactor;
            len = newLen;
            seg = len / (N - 1);
            baseSpeed = len * (0.45f + rnd.nextFloat() * 0.25f);
        }

        void rescale(float sx, float sy) {
            for (int i = 0; i < N; i++) { px[i] *= sx; py[i] *= sy; }
            fx *= sx; fy *= sy;
        }

        float width(int i) {
            float t = i / (float) (N - 1);
            float s;
            if (t < 0.2f) s = 0.62f + 0.38f * (float) Math.sin(t / 0.2f * Math.PI / 2);
            else s = (float) Math.pow(1f - (t - 0.2f) / 0.8f, 1.25f) * 0.94f + 0.06f;
            return len * 0.105f * s;
        }

        void scare(float x, float y, float radius, boolean strong) {
            float d = (float) Math.hypot(px[0] - x, py[0] - y);
            float dMid = (float) Math.hypot(px[N / 2] - x, py[N / 2] - y);
            d = Math.min(d, dMid);
            if (d < radius) {
                float f = (1f - d / radius) * (strong ? 1.6f : 0.9f) + 0.4f;
                if (f > fear) { fear = f; fx = x; fy = y; }
            }
        }

        void update(float dt, int w, int h, List<Koi> all) {
            float hx = px[0], hy = py[0];
            wander += (rnd.nextFloat() - 0.5f) * 3f * dt;
            wander *= (1f - 0.3f * dt);
            if (wander > 1.1f) wander = 1.1f;
            if (wander < -1.1f) wander = -1.1f;
            float turn = wander * 0.9f;

            float margin = Math.max(len * 0.7f, Math.min(w, h) * 0.1f);
            float edge = 0f;
            if (hx < margin) edge = Math.max(edge, (margin - hx) / margin);
            if (hx > w - margin) edge = Math.max(edge, (hx - (w - margin)) / margin);
            if (hy < margin) edge = Math.max(edge, (margin - hy) / margin);
            if (hy > h - margin) edge = Math.max(edge, (hy - (h - margin)) / margin);
            if (edge > 0) {
                float a = (float) Math.atan2(h * 0.5f - hy, w * 0.5f - hx);
                turn += angleDiff(a, angle) * (1.5f + 3f * edge);
            }

            // separazione dagli altri pesci
            for (Koi o : all) {
                if (o == this) continue;
                float dx = hx - o.px[N / 3], dy = hy - o.py[N / 3];
                float d = (float) Math.hypot(dx, dy);
                if (d > 0.01f && d < len * 0.6f) {
                    float a = (float) Math.atan2(dy, dx);
                    turn += angleDiff(a, angle) * 0.8f * (1f - d / (len * 0.6f));
                }
            }

            float boost = 0f;
            if (fear > 0) {
                float a = (float) Math.atan2(hy - fy, hx - fx);
                turn += angleDiff(a, angle) * 5f * Math.min(1f, fear);
                boost = 3.2f * Math.min(1f, fear);
                fear -= dt * 0.9f;
            }

            float maxTurn = fear > 0 ? 7f : 1.6f;
            if (turn > maxTurn) turn = maxTurn;
            if (turn < -maxTurn) turn = -maxTurn;
            angle += turn * dt;

            float target = baseSpeed * (1f + boost);
            speed += (target - speed) * Math.min(1f, dt * (boost > 0 ? 6f : 1.5f));

            phase += dt * (2.5f + 6f * speed / len);
            float heading = angle + (float) Math.sin(phase) * 0.22f;
            px[0] += (float) Math.cos(heading) * speed * dt;
            py[0] += (float) Math.sin(heading) * speed * dt;

            // rientro di sicurezza se fuori schermo
            if (px[0] < -len || px[0] > w + len || py[0] < -len || py[0] > h + len) {
                angle = (float) Math.atan2(h * 0.5f - py[0], w * 0.5f - px[0]);
            }

            for (int i = 1; i < N; i++) {
                float dx = px[i] - px[i - 1], dy = py[i] - py[i - 1];
                float d = (float) Math.hypot(dx, dy);
                if (d > 0.0001f) {
                    px[i] = px[i - 1] + dx / d * seg;
                    py[i] = py[i - 1] + dy / d * seg;
                }
            }
        }

        static float angleDiff(float a, float b) {
            float d = a - b;
            while (d > Math.PI) d -= (float) (2 * Math.PI);
            while (d < -Math.PI) d += (float) (2 * Math.PI);
            return d;
        }
    }
}
