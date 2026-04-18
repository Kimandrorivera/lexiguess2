import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.Timer;

public class LexiGuess extends JFrame {

    // ── Loading Screen ────────────────────────────────────────────────────────────
    static class LoadingScreen extends JWindow {
 
        // ── Palette
        private static final Color BG = new Color(12, 24, 48);
        private static final Color AMBER     = new Color(255, 185,  40);
        private static final Color CYAN      = new Color( 41, 205, 255);
        private static final Color CYAN_DIM  = new Color( 20,  94, 122);
        private static final Color TEXT_DIM  = new Color( 58,  56,  40);
    
        // ── Rising ember ─────────────────────────────────────────────────────────
        private static final class Ember {
            float x, y, vx, vy, life, max, size;
            boolean amber;
            Ember(int w, int h) {
                x = (float)(Math.random() * w);
                y = h + 6f;
                float ang = (float)((Math.random() - 0.5) * 0.6 - Math.PI / 2.0);
                float spd = 0.3f + (float)(Math.random() * 0.55f);
                vx = (float)Math.cos(ang) * spd;
                vy = (float)Math.sin(ang) * spd;
                life = max = 180 + 140 * (float)Math.random();
                size = 0.5f + (float)(Math.random() * 1.8f);
                amber = Math.random() > 0.45;
            }
            void    step()   { x += vx; y += vy; vy -= 0.004f; life--; }
            boolean dead()   { return life <= 0 || y < -10; }
            float   alpha()  { return Math.max(0f, life / max); }
        }
    
        // ── L-shaped circuit trace ────────────────────────────────────────────────
        private static final class Circuit {
            final int x1, y1, xm, ym, x2, y2;
            final Color col;
            float pulseT;
            final float pulseSpeed;
            Circuit(int x1, int y1, int xm, int ym, int x2, int y2,
                    Color col, float ps) {
                this.x1 = x1; this.y1 = y1; this.xm = xm; this.ym = ym;
                this.x2 = x2; this.y2 = y2; this.col = col;
                this.pulseT = (float)Math.random();
                this.pulseSpeed = ps;
            }
            void  advance()  { pulseT += pulseSpeed; if (pulseT > 1f) pulseT -= 1f; }
            float[] pulseXY() {
                if (pulseT < 0.5f) {
                    float u = pulseT * 2f;
                    return new float[]{ x1 + (xm - x1) * u, y1 + (ym - y1) * u };
                } else {
                    float u = (pulseT - 0.5f) * 2f;
                    return new float[]{ xm + (x2 - xm) * u, ym + (y2 - ym) * u };
                }
            }
        }
    
        // ── State ─────────────────────────────────────────────────────────────────
        private final java.util.List<Ember>   embers   = new java.util.ArrayList<>();
        private final java.util.List<Circuit> circuits = new java.util.ArrayList<>();
    
        private int    frame    = 0;
        private float  progress = 0f;
        private String taskText = "Initializing systems...";
        private float  scanY    = 0f;
    
        // Glitch ghost
        private long    glitchStart  = -1L;
        private float[] glitchBounds = null; 
    
        // Flicker
        private float flickerAlpha = 1f;
    
        // Corner blink
        private float   cornerAlpha = 0.4f;
        private boolean cornerUp    = true;
    
        // Orbital rotation (degrees)
        private float outerAngle = 0f;
        private float innerAngle = 0f;
    
        // Cipher strips
        private static final char[] HEX = "0123456789ABCDEF".toCharArray();
        private final char[] cipherL = new char[26];
        private final char[] cipherR = new char[26];
        private final java.util.Random rng = new java.util.Random();
    
        // Timers
        private Timer anim, loader;
    
        private static final String[] TASKS = {
            "Initializing systems...",
            "Loading word banks...",
            "Connecting to database...",
            "Calibrating difficulty tiers...",
            "Compiling skill trees...",
            "Syncing leaderboard...",
            "System ready."
        };
    
        // ── Constructor (with callback) ───────────────────────────────────────────
        LoadingScreen(Runnable onFinished) {
            setSize(720, 500);
            setLocationRelativeTo(null);
            setBackground(BG);
    
            for (int i = 0; i < 26; i++) {
                cipherL[i] = HEX[rng.nextInt(HEX.length)];
                cipherR[i] = HEX[rng.nextInt(HEX.length)];
            }
            buildCircuits(720, 500);
    
            JPanel panel = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    paint2D((Graphics2D) g.create(), getWidth(), getHeight());
                }
            };
            panel.setBackground(BG);
            panel.setOpaque(true);
            setContentPane(panel);
    
            // ~45 fps animation
            anim = new Timer(22, e -> {
                frame++;
                int W = getWidth(), H = getHeight();
    
                if (frame % 5 == 0 && embers.size() < 65) embers.add(new Ember(W, H));
                embers.removeIf(Ember::dead);
                embers.forEach(Ember::step);
                circuits.forEach(Circuit::advance);
    
                scanY += 0.8f;
                if (scanY > H + 4) scanY = -4f;
    
                outerAngle = (outerAngle + 0.45f) % 360f;
                innerAngle = (innerAngle - 0.72f + 360f) % 360f;
    
                cornerAlpha += cornerUp ? 0.012f : -0.012f;
                if (cornerAlpha >= 1f)   { cornerAlpha = 1f;    cornerUp = false; }
                if (cornerAlpha <= 0.35f){ cornerAlpha = 0.35f; cornerUp = true;  }
    
                if (Math.random() < 0.003)
                    flickerAlpha = 0.65f + (float) Math.random() * 0.3f;
                else
                    flickerAlpha = Math.min(1f, flickerAlpha + 0.06f);
    
                if (glitchStart < 0 && Math.random() < 0.002) {
                    glitchStart  = System.currentTimeMillis();
                    glitchBounds = new float[]{
                        (float)(Math.random() * 0.3f),
                        (float)(Math.random() * 0.3f + 0.5f)
                    };
                }
                if (glitchStart > 0 && System.currentTimeMillis() - glitchStart > 130) {
                    glitchStart = -1L; glitchBounds = null;
                }
    
                if (frame % 8 == 0) {
                    cipherL[rng.nextInt(26)] = HEX[rng.nextInt(HEX.length)];
                    cipherR[rng.nextInt(26)] = HEX[rng.nextInt(HEX.length)];
                }
    
                panel.repaint();
            });
            anim.start();
    
            // Progress
            loader = new Timer(55, null);
            loader.addActionListener(e -> {
                progress = Math.min(100f, progress + 1.1f + (float)(Math.random() * 2.6f));
                int step = Math.min(TASKS.length - 1, (int)(progress / 100f * TASKS.length));
                taskText = TASKS[step];
                if (progress >= 100f) {
                    loader.stop();
                    anim.stop();
                    SwingUtilities.invokeLater(() -> {
                        // Show main window FIRST so something is always on screen,
                        // then dispose splash. No delay, no flicker, no window gap.
                        if (onFinished != null) onFinished.run();
                        dispose();
                    });
                }
            });
            loader.start();
        }

        LoadingScreen() { this(() -> new LexiGuess()); }
    
        // ── Build circuits
        private void buildCircuits(int W, int H) {
            circuits.clear();
            float sx = (float) W / 800f, sy = (float) H / 500f;
            // HTML trace 1 – amber  : 0,200 → 120,200 → 280,120
            addC(sx,sy,   0,200, 120,200, 280,120, new Color(255,160, 30, 28), 0.006f);
            // HTML trace 2 – cyan   : 800,310 → 660,310 → 520,390
            addC(sx,sy, 800,310, 660,310, 520,390, new Color( 41,205,255, 22), 0.005f);
            // HTML trace 3 – amber  : 200,500 → 200,420 → 380,380
            addC(sx,sy, 200,500, 200,420, 380,380, new Color(200,130, 20, 18), 0.007f);
            // HTML trace 4 – cyan   : 600,0 → 600,80 → 480,140
            addC(sx,sy, 600,  0, 600, 80, 480,140, new Color( 41,205,255, 16), 0.008f);
            // extra coverage
            addC(sx,sy,   0,380, 150,380, 320,300, new Color(255,185, 40, 14), 0.009f);
            addC(sx,sy, 800,150, 620,150, 440,240, new Color( 41,205,255, 12), 0.006f);
        }
    
        private void addC(float sx, float sy,
                        int ax1, int ay1, int axm, int aym, int ax2, int ay2,
                        Color c, float ps) {
            circuits.add(new Circuit(
                sc(ax1,sx), sc(ay1,sy),
                sc(axm,sx), sc(aym,sy),
                sc(ax2,sx), sc(ay2,sy),
                c, ps));
        }
        private static int sc(int v, float s) { return Math.round(v * s); }
    
        // ── Master paint ──────────────────────────────────────────────────────────
        private void paint2D(Graphics2D g2, int W, int H) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,         RenderingHints.VALUE_RENDER_QUALITY);
    
            g2.setColor(BG); g2.fillRect(0, 0, W, H);
    
            glow(g2,    0f,   H,       W*0.85f, new Color(180, 75,  0,110), new Color(120, 40,  0, 40), new Color(0,0,0,0));
            glow(g2,    W,    0f,      W*0.70f, new Color(  0,100,180, 80), new Color(  0, 55,110, 28), new Color(0,0,0,0));
            glow(g2,  W/2f,   H*0.38f, W*0.55f, new Color( 55, 15,120, 60), new Color( 25,  5, 55, 20), new Color(0,0,0,0));
            glow(g2, W*0.85f, H*0.9f,  W*0.45f, new Color(200,128,  0, 50), new Color(0,0,0,0),         new Color(0,0,0,0));
    
            hexGrid(g2, W, H);
            drawCircuits(g2);
            scatter(g2, W, H);
            drawEmbers(g2);
    
            // Vignette
            glow(g2, W/2f, H/2f, Math.max(W,H)*0.72f,
                new Color(0,0,0,0), new Color(0,0,0,100), new Color(0,0,0,200));
    
            for (int y = 0; y < H; y += 3) { g2.setColor(new Color(0,0,0,9)); g2.drawLine(0,y,W,y); }
    
            scanBeam(g2, W);
            corners(g2, W, H);
            cipherStrips(g2, W, H);
            content(g2, W, H);
    
            g2.dispose();
        }
    
        // ── Radial glow ───────────────────────────────────────────────────────────
        private void glow(Graphics2D g2, float cx, float cy, float r,
                        Color c0, Color c1, Color c2) {
            if (r <= 0) return;
            float[] fracs; Color[] cols;
            if (c1.getAlpha() == 0 && c2.getAlpha() == 0)
                { fracs = new float[]{0f,1f};         cols = new Color[]{c0,c2}; }
            else if (c2.getAlpha() == 0)
                { fracs = new float[]{0f,0.5f,1f};    cols = new Color[]{c0,c1,c2}; }
            else
                { fracs = new float[]{0f,0.45f,1f};   cols = new Color[]{c0,c1,c2}; }
            try {
                g2.setPaint(new RadialGradientPaint(new Point2D.Float(cx,cy), r, fracs, cols));
                g2.fillRect((int)(cx-r), (int)(cy-r), (int)(r*2+1), (int)(r*2+1));
            } catch (Exception ignored) {}
        }
    
        // ── Hex grid ──────────────────────────────────────────────────────────────
        private void hexGrid(Graphics2D g2, int W, int H) {
            final int hexR = 22, hexW = (int)(hexR * Math.sqrt(3)), hexH = hexR * 2;
            g2.setStroke(new BasicStroke(0.45f));
            for (int row = -1; row < H / (hexH*3/4) + 2; row++) {
                for (int col = -1; col < W / hexW + 2; col++) {
                    int cx = col * hexW + (row % 2 == 1 ? hexW/2 : 0);
                    int cy = row * (hexH * 3 / 4);
                    int tint = (row + col) % 2, alpha = 7 + (tint == 0 ? 1 : 0);
                    g2.setColor(tint == 0 ? new Color(180,90,20,alpha) : new Color(60,130,190,alpha));
                    int[] xs = new int[6], ys = new int[6];
                    for (int i = 0; i < 6; i++) {
                        double a = Math.PI / 180.0 * (60 * i - 30);
                        xs[i] = cx + (int)(hexR*Math.cos(a));
                        ys[i] = cy + (int)(hexR*Math.sin(a));
                    }
                    g2.drawPolygon(xs, ys, 6);
                }
            }
        }
    
        // ── Circuit traces ────────────────────────────────────────────────────────
        private void drawCircuits(Graphics2D g2) {
            for (Circuit cl : circuits) {
                g2.setColor(cl.col);
                g2.setStroke(new BasicStroke(0.9f));
                g2.drawLine(cl.x1, cl.y1, cl.xm, cl.ym);
                g2.drawLine(cl.xm, cl.ym, cl.x2, cl.y2);
                node(g2, cl.x1, cl.y1, cl.col, 3);
                node(g2, cl.xm, cl.ym, cl.col, 4);
                node(g2, cl.x2, cl.y2, cl.col, 3);
    
                float[] pp = cl.pulseXY();
                int px = (int)pp[0], py = (int)pp[1];
                int baseA = cl.col.getAlpha(), glowA = Math.min(220, baseA * 5);
                int r = cl.col.getRed(), gv = cl.col.getGreen(), b = cl.col.getBlue();
                g2.setColor(new Color(r, gv, b, glowA/3));
                g2.fill(new Ellipse2D.Float(px-6, py-6, 12, 12));
                g2.setColor(new Color(r, gv, b, glowA));
                g2.fill(new Ellipse2D.Float(px-2.5f, py-2.5f, 5, 5));
                g2.setColor(new Color(220, 235, 255, Math.min(255, glowA+50)));
                g2.fill(new Ellipse2D.Float(px-1f, py-1f, 2, 2));
            }
        }
    
        private void node(Graphics2D g2, int cx, int cy, Color c, int r) {
            int a = c.getAlpha();
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), a/2));
            g2.setStroke(new BasicStroke(0.5f));
            g2.drawOval(cx-r-2, cy-r-2, (r+2)*2, (r+2)*2);
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(255, a*3)));
            g2.fillOval(cx-r, cy-r, r*2, r*2);
        }
    
        // ── Scatter nodes ─────────────────────────────────────────────────────────
        private void scatter(Graphics2D g2, int W, int H) {
            float sx = (float)W/800f, sy = (float)H/500f;
            int[][] pts = {{70,380},{340,60},{680,440},{730,160},{180,140},{500,460}};
            Color[] col = {AMBER,CYAN,AMBER,CYAN,AMBER,CYAN};
            int[] al = {50,63,46,56,38,46}, rd = {5,4,5,4,4,4};
            for (int i = 0; i < pts.length; i++) {
                int cx = (int)(pts[i][0]*sx), cy = (int)(pts[i][1]*sy);
                g2.setColor(new Color(col[i].getRed(),col[i].getGreen(),col[i].getBlue(),al[i]));
                g2.fillOval(cx-rd[i], cy-rd[i], rd[i]*2, rd[i]*2);
            }
        }
    
        // ── Embers ────────────────────────────────────────────────────────────────
        private void drawEmbers(Graphics2D g2) {
            for (Ember e : embers) {
                float a = e.alpha();
                Color base = e.amber ? AMBER : CYAN;
                int ha = Math.max(0, Math.min(255, (int)(a * 45)));
                int ca = Math.max(0, Math.min(255, (int)(a * 210)));
                g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), ha));
                g2.fill(new Ellipse2D.Float(e.x - e.size*5, e.y - e.size*5, e.size*10, e.size*10));
                g2.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), ca));
                g2.fill(new Ellipse2D.Float(e.x - e.size/2, e.y - e.size/2, e.size, e.size));
            }
        }
    
        // ── Scanline beam ─────────────────────────────────────────────────────────
        private void scanBeam(Graphics2D g2, int W) {
            int sy = (int)scanY;
            g2.setPaint(new GradientPaint(0, sy-3, new Color(0,0,0,0), 0, sy, new Color(255,185,40,18)));
            g2.fillRect(0, sy-3, W, 3);
            g2.setPaint(new GradientPaint(0, sy, new Color(255,185,40,18), 0, sy+3, new Color(0,0,0,0)));
            g2.fillRect(0, sy, W, 3);
        }
    
        // ── Corner brackets ───────────────────────────────────────────────────────
        private void corners(Graphics2D g2, int W, int H) {
            int a = (int)(cornerAlpha * 140) + 60;
            g2.setColor(new Color(255, 185, 40, a));
            g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER));
            final int m = 22, s = 22;
            g2.drawLine(m, m+s, m, m);   g2.drawLine(m, m, m+s, m);
            g2.drawLine(W-m, m+s, W-m, m);   g2.drawLine(W-m, m, W-m-s, m);
            g2.drawLine(m, H-m-s, m, H-m);   g2.drawLine(m, H-m, m+s, H-m);
            g2.drawLine(W-m, H-m-s, W-m, H-m); g2.drawLine(W-m, H-m, W-m-s, H-m);
        }
    
        // ── Cipher side strips ────────────────────────────────────────────────────
        private void cipherStrips(Graphics2D g2, int W, int H) {
            final int stripW = 26, spacing = H / 26;
            g2.setColor(new Color(255, 185, 40, 16));
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(stripW, 0, stripW, H);
            g2.drawLine(W - stripW, 0, W - stripW, H);
            g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
            for (int i = 0; i < 26; i++) {
                int y = spacing / 2 + i * spacing;
                g2.setColor(new Color(255, 185, 40, 30));
                g2.drawString(String.valueOf(cipherL[i]), 9, y);
                g2.drawString(String.valueOf(cipherR[i]), W - 19, y);
            }
        }
    
        // ── Central content layout ────────────────────────────────────────────────
        private void content(Graphics2D g2, int W, int H) {
            int cx = W / 2;
            int y  = 56;
    
            y = drawBadge(g2, cx, y) + 20;
            drawOrbital(g2, cx, y + 44);
            y += 100;
            drawTitle(g2, cx, y);
            y += 56;
    
            g2.setFont(new Font("Monospaced", Font.PLAIN, 11));
            g2.setColor(new Color(75, 72, 48, 200));
            String sub = "PROGRAMMING ENHANCEMENT GAME";
            FontMetrics fms = g2.getFontMetrics();
            g2.drawString(sub, cx - fms.stringWidth(sub)/2, y);
            y += 18;
    
            drawDivider(g2, cx, y);
            y += 22;
    
            drawProgress(g2, cx, y, W);
            y += 86;
    
            g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
            g2.setColor(new Color(26, 25, 16));
            String foot = "v1.0  ·  programming_challenge.exe  ·  build 2025";
            FontMetrics fmf = g2.getFontMetrics();
            g2.drawString(foot, cx - fmf.stringWidth(foot)/2, y + 4);
        }
    
        private int drawBadge(Graphics2D g2, int cx, int y) {
            final String text = "◈   v1.0   ·   BOOT SEQUENCE";
            g2.setFont(new Font("Monospaced", Font.PLAIN, 10));
            FontMetrics fm = g2.getFontMetrics();
            int bw = fm.stringWidth(text) + 36, bh = 24, bx = cx - bw/2;
            g2.setColor(new Color(41, 205, 255, 18));
            g2.fillRoundRect(bx, y, bw, bh, 20, 20);
            g2.setColor(new Color(41, 205, 255, 55));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(bx, y, bw-1, bh-1, 20, 20);
            g2.setColor(CYAN_DIM);
            g2.drawString(text, bx + (bw - fm.stringWidth(text))/2, y + 16);
            return y + bh;
        }
    
        private void drawOrbital(Graphics2D g2, int cx, int cy) {
            final int outerR = 40, innerR = 30;
    
            // Breathing pulse ring
            double pulse = 0.18 + 0.22 * Math.sin(frame * 0.04);
            g2.setColor(new Color(255, 185, 40, (int)(pulse * 255)));
            g2.setStroke(new BasicStroke(4f));
            g2.drawOval(cx-outerR-2, cy-outerR-2, (outerR+2)*2, (outerR+2)*2);
    
            // Outer dashed amber ring — CW
            Graphics2D go = (Graphics2D) g2.create();
            go.rotate(Math.toRadians(outerAngle), cx, cy);
            go.setStroke(new BasicStroke(0.9f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                        1f, new float[]{5, 7}, 0f));
            go.setColor(new Color(255, 185, 40, 100));
            go.drawOval(cx-outerR, cy-outerR, outerR*2, outerR*2);
            go.setColor(new Color(255, 185, 40, 190));
            for (int i = 0; i < 4; i++) {
                double a = Math.toRadians(90 * i);
                go.fillOval(cx + (int)(outerR*Math.cos(a)) - 3,
                            cy + (int)(outerR*Math.sin(a)) - 3, 6, 6);
            }
            go.dispose();
    
            // Inner dashed cyan ring — CCW
            Graphics2D gi = (Graphics2D) g2.create();
            gi.rotate(Math.toRadians(innerAngle), cx, cy);
            gi.setStroke(new BasicStroke(0.75f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                                        1f, new float[]{3, 9}, 0f));
            gi.setColor(new Color(41, 205, 255, 82));
            gi.drawOval(cx-innerR, cy-innerR, innerR*2, innerR*2);
            gi.setColor(new Color(41, 205, 255, 165));
            for (int i = 0; i < 4; i++) {
                double a = Math.toRadians(90 * i);
                gi.fillOval(cx + (int)(innerR*Math.cos(a)) - 2,
                            cy + (int)(innerR*Math.sin(a)) - 2, 5, 5);
            }
            gi.dispose();
    
            // Centre hex with "L"
            drawCentreHex(g2, cx, cy);
        }
    
        private void drawCentreHex(Graphics2D g2, int cx, int cy) {
            final int r = 20, r2 = 12;
            int[] xs = new int[6], ys = new int[6];
            int[] xs2 = new int[6], ys2 = new int[6];
            for (int i = 0; i < 6; i++) {
                double a = Math.PI / 180.0 * (60 * i - 30);
                xs[i]  = cx + (int)(r  * Math.cos(a)); ys[i]  = cy + (int)(r  * Math.sin(a));
                xs2[i] = cx + (int)(r2 * Math.cos(a)); ys2[i] = cy + (int)(r2 * Math.sin(a));
            }
            g2.setStroke(new BasicStroke(1.5f));
            g2.setColor(new Color(255, 185, 40, 234)); g2.drawPolygon(xs, ys, 6);
            g2.setColor(new Color(255, 185, 40,  25)); g2.fillPolygon(xs2, ys2, 6);
            g2.setColor(new Color(255, 185, 40, 115));
            g2.setStroke(new BasicStroke(0.6f));        g2.drawPolygon(xs2, ys2, 6);
            g2.setFont(new Font("Serif", Font.BOLD | Font.ITALIC, 17));
            g2.setColor(new Color(255, 185, 40, 242));
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString("L", cx - fm.stringWidth("L")/2, cy + fm.getAscent()/2 - 1);
        }
    
        private void drawTitle(Graphics2D g2, int cx, int topY) {
            Font f = new Font("Serif", Font.BOLD | Font.ITALIC, 66);
            g2.setFont(f);
            FontMetrics fm = g2.getFontMetrics();
            final String title = "LexiGuess";
            int tx = cx - fm.stringWidth(title) / 2;
            int ty = topY + fm.getAscent();
    
            // Halo bloom
            try {
                g2.setPaint(new RadialGradientPaint(new Point2D.Float(cx, topY + 30), 240,
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{new Color(255,160,0,90), new Color(180,80,0,30), new Color(0,0,0,0)}));
                g2.fillRect(cx - 260, topY - 10, 520, 80);
            } catch (Exception ignored) {}
    
            // Shadow
            g2.setColor(new Color(180, 80, 0, 80));
            g2.drawString(title, tx + 4, ty + 4);
    
            // Glitch ghost
            if (glitchBounds != null) {
                int totalH = fm.getAscent() + fm.getDescent();
                int clipTop = topY + (int)(glitchBounds[0] * totalH);
                int clipH   = (int)((glitchBounds[1] - glitchBounds[0]) * totalH);
                if (clipH > 0) {
                    Graphics2D gg = (Graphics2D) g2.create();
                    gg.setClip(0, clipTop, cx * 2, clipH);
                    int offX = (int)((Math.random() - 0.5) * 8);
                    gg.setPaint(new GradientPaint(0, topY, new Color(255,220,100,90),
                                                0, ty,   new Color(185,100,  0,70)));
                    gg.drawString(title, tx + offX, ty);
                    gg.dispose();
                }
            }
    
            // Main title (flicker)
            int mainA = (int)(flickerAlpha * 255);
            g2.setPaint(new GradientPaint(0, topY, new Color(255,220,100,mainA),
                                        0, ty,   new Color(185,100,  0,mainA)));
            g2.drawString(title, tx, ty);
            g2.setColor(new Color(255, 140, 0, 25));
            g2.drawString(title, tx - 1, ty);
            g2.drawString(title, tx + 1, ty);
        }
    
        private void drawDivider(Graphics2D g2, int cx, int y) {
            g2.setStroke(new BasicStroke(1f));
            g2.setPaint(new GradientPaint(cx-80, y, new Color(255,185,40,0),
                                        cx-4,  y, new Color(255,185,40,180)));
            g2.drawLine(cx - 80, y, cx - 4, y);
            g2.setPaint(new GradientPaint(cx+4,  y, new Color(255,185,40,180),
                                        cx+80, y, new Color(255,185,40,0)));
            g2.drawLine(cx + 4, y, cx + 80, y);
            // Rotating diamond
            Graphics2D gd = (Graphics2D) g2.create();
            gd.translate(cx, y);
            gd.rotate(Math.PI / 4);
            gd.setColor(AMBER);
            gd.fillRect(-3, -3, 6, 6);
            gd.dispose();
        }
    
        private void drawProgress(Graphics2D g2, int cx, int topY, int W) {
            final int barW = 310, barH = 3, barX = cx - barW / 2;
    
            // Segment ticks (7)
            for (int i = 0; i < 7; i++) {
                int tx = barX + (barW / 8) * (i + 1);
                boolean active = progress / 100f * 7 > i;
                g2.setColor(active ? new Color(255,185,40,165) : new Color(255,185,40,50));
                g2.fillRect(tx - 1, topY - 6, 1, 5);
            }
    
            // Track
            g2.setColor(new Color(13, 17, 34));
            g2.fillRoundRect(barX, topY, barW, barH, 2, 2);
            g2.setColor(new Color(30, 40, 70));
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(barX, topY, barW-1, barH-1, 2, 2);
    
            // Fill
            int filled = Math.max(0, (int)(progress / 100f * barW));
            if (filled > 0) {
                g2.setPaint(new GradientPaint(barX, 0, new Color(106,50,0), barX+filled, 0, AMBER));
                g2.fillRoundRect(barX, topY, filled, barH, 2, 2);
                g2.setColor(new Color(255, 255, 255, 35));
                g2.fillRoundRect(barX, topY, filled, 1, 2, 2);
                // Tip glow
                if (filled > 4) {
                    int tx = barX + filled - 2;
                    g2.setColor(new Color(255, 185,  40, 100)); g2.fillRoundRect(tx-4, topY-3, 12, 9, 4, 4);
                    g2.setColor(new Color(255, 230, 160, 200)); g2.fillRoundRect(tx,   topY-2,  4, 7, 3, 3);
                }
            }
    
            int infoY = topY + barH + 14;
    
            // Percentage (left)
            g2.setFont(new Font("Monospaced", Font.BOLD, 12));
            g2.setColor(new Color(255, 185, 40, 190));
            g2.drawString((int)progress + "%", barX, infoY);
    
            // Task text (right)
            g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
            FontMetrics fmT = g2.getFontMetrics();
            g2.setColor(TEXT_DIM);
            g2.drawString(taskText, barX + barW - fmT.stringWidth(taskText), infoY);
    
            // Animated 3-dot pulse
            double t = frame * 0.06;
            for (int i = 0; i < 3; i++) {
                double sv    = (Math.sin(t - i * 0.4) + 1) / 2.0;
                double alpha = 0.2 + 0.8 * Math.pow(Math.max(0, sv), 2);
                int dia      = Math.max(2, 4 + (int)(Math.sin(t - i * 0.4) * 1.5));
                g2.setColor(new Color(AMBER.getRed(), AMBER.getGreen(), AMBER.getBlue(),
                                    (int)(alpha * 200)));
                g2.fillOval(cx - 14 + i*14 - dia/2, infoY + 14 - dia/2, dia, dia);
            }
        }
    }

    // ── Word Entry ────────────────────────────────────────────────────────────
    static class WordEntry {
        String word, clue, difficulty;
        WordEntry(String word, String clue, String difficulty) {
            this.word = word.toUpperCase().trim();
            this.clue = clue;
            this.difficulty = difficulty.toUpperCase().trim();
        }
    }

    // ── JSON Loader ───────────────────────────────────────────────────────────
    private static List<WordEntry> loadJson(String filePath) {
        List<WordEntry> entries = new ArrayList<>();
        try {
            String raw = new String(Files.readAllBytes(Paths.get(filePath)));
            String[] objects = raw.split("\\{");
            for (String obj : objects) {
                obj = obj.trim();
                if (obj.isEmpty()) continue;
                obj = obj.replaceAll("[}\\]]+\\s*$", "").trim();
                String word = extractJsonValue(obj, "words");
                if (word == null || word.isEmpty()) word = extractJsonValue(obj, "word");
                String clue = extractJsonValue(obj, "clue");
                String difficulty = extractJsonValue(obj, "difficulty");
                if (word != null && !word.isEmpty() && clue != null && difficulty != null)
                    entries.add(new WordEntry(word, clue, difficulty));
            }
        } catch (IOException e) {
            System.err.println("Could not load " + filePath + ": " + e.getMessage());
        }
        return entries;
    }

    private static String extractJsonValue(String obj, String key) {
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").matcher(obj);
        return m.find() ? m.group(1).trim() : null;
    }

    // ── Word Banks ────────────────────────────────────────────────────────────
    private List<WordEntry> easyWords   = new ArrayList<>();
    private List<WordEntry> mediumWords = new ArrayList<>();
    private List<WordEntry> hardWords   = new ArrayList<>();

    private void loadAllWords() {
        easyWords   = loadJson("easy.json");
        mediumWords = loadJson("medium.json");
        hardWords   = loadJson("hard.json");
        if (easyWords.isEmpty()) for (String[] e : new String[][]{
            {"CODE","Instructions written for a computer to execute."},
            {"LOOP","A structure that repeats a block of code."},
            {"BYTE","A unit of data equal to 8 bits."},
            {"TYPE","A classification specifying the kind of value a variable holds."},
            {"VOID","A return type indicating a function returns nothing."}
        }) easyWords.add(new WordEntry(e[0], e[1], "EASY"));
        if (mediumWords.isEmpty()) for (String[] e : new String[][]{
            {"ARRAY","A collection of elements stored at contiguous memory locations."},
            {"STACK","A linear data structure following Last-In-First-Out order."},
            {"QUEUE","A linear data structure following First-In-First-Out order."},
            {"PARSE","To analyse a string of symbols according to formal grammar rules."},
            {"CACHE","Temporary storage that speeds up future data requests."}
        }) mediumWords.add(new WordEntry(e[0], e[1], "MEDIUM"));
        if (hardWords.isEmpty()) for (String[] e : new String[][]{
            {"MUTEX","A synchronisation primitive that prevents simultaneous resource access."},
            {"PRAGMA","A compiler directive that provides additional information to the compiler."},
            {"LAMBDA","An anonymous function defined without a name."},
            {"DAEMON","A background process that runs without direct user interaction."},
            {"ENDIAN","Describes the byte order used to represent multi-byte data."}
        }) hardWords.add(new WordEntry(e[0], e[1], "HARD"));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  COLOUR PALETTE
    // ══════════════════════════════════════════════════════════════════════════
    private static final Color BG_VOID        = new Color( 12,  28,  55);
    private static final Color BG_DEEP        = new Color( 15,  33,  65);
    private static final Color BG_SURFACE     = new Color( 18,  38,  75);
    private static final Color BG_ELEVATED    = new Color( 22,  44,  85);
    private static final Color BG_CARD        = new Color( 15,  35,  68);

    private static final Color AMBER          = new Color(255, 185,  40);
    private static final Color AMBER_BRIGHT   = new Color(255, 210, 100);
    private static final Color AMBER_DIM      = new Color(150,  95,   8);
    private static final Color AMBER_GLOW     = new Color(255, 140,   0,  80);
    private static final Color AMBER_SUBTLE   = new Color(255, 180,  30,  18);

    private static final Color CYAN           = new Color( 45, 205, 255);
    private static final Color CYAN_DIM       = new Color( 20, 100, 150);
    private static final Color CYAN_GLOW      = new Color( 45, 205, 255,  55);

    private static final Color TEXT_BRIGHT    = new Color(235, 232, 220);
    private static final Color TEXT_MID       = new Color(140, 138, 158);
    private static final Color TEXT_DIM       = new Color( 60,  58,  75);
    private static final Color TEXT_FAINT     = new Color( 35,  33,  48);

    private static final Color TILE_EMPTY_BG  = new Color(13,  17,  34);
    private static final Color TILE_EMPTY_BD  = new Color(35,  40,  62);
    private static final Color GREEN_CORRECT  = new Color( 35, 185, 105);
    private static final Color YELLOW_PRESENT = new Color(205, 160,  25);
    private static final Color GRAY_ABSENT    = new Color( 32,  32,  46);
    private static final Color RED_DANGER     = new Color(210,  55,  55);

    private static final Color EASY_CLR       = new Color( 45, 210, 120);
    private static final Color MEDIUM_CLR     = new Color(210, 160,  25);
    private static final Color HARD_CLR       = new Color(220,  70,  70);
    private static final Color CLUE_CLR       = new Color( 95, 175, 255);

    // ── Sound Constants ───────────────────────────────────────────────────────
    private static final String SOUND_CORRECT  = "Correct.wav";
    private static final String SOUND_LOSE     = "Lose.wav";
    private static final String SOUND_KEYCLICK = "Click.wav";
    private static final String SOUND_START    = "Start.wav";

    // ── Sound Manager ─────────────────────────────────────────────────────────
    class SoundManager {
        private boolean soundEnabled = true;

        public boolean isSoundEnabled()           { return soundEnabled; }
        public void    setSoundEnabled(boolean b) { soundEnabled = b; }
        public void    toggleSound()              { soundEnabled = !soundEnabled; }

        /** Core playback — skips silently if sound is off or file is missing. */
        private void play(String filePath) {
            if (!soundEnabled) return;
            new Thread(() -> {
                try {
                    File f = new File(filePath);
                    if (!f.exists()) return;
                    AudioInputStream ai = AudioSystem.getAudioInputStream(f);
                    Clip clip = AudioSystem.getClip();
                    clip.open(ai);
                    clip.start();
                    clip.addLineListener(ev -> {
                        if (ev.getType() == LineEvent.Type.STOP) clip.close();
                    });
                } catch (Exception ignored) {}
            }).start();
        }

        public void playClick()   { play(SOUND_KEYCLICK); }
        public void playStart()   { play(SOUND_START);    }
        public void playLose()    { play(SOUND_LOSE);     }
        public void playCorrect() { play(SOUND_CORRECT);  }
    }

    private final SoundManager soundManager = new SoundManager();

    // ── Game State ────────────────────────────────────────────────────────────
    private WordEntry  currentEntry;
    private String     targetWord     = "";
    private String     currentClue    = "";
    private String     difficulty     = "EASY";
    private int        maxAttempts    = 6;
    private int        currentAttempt = 0;
    private int        sessionScore   = 0;
    private int        hintsUsed      = 0;
    private static final int MAX_HINTS = 3;
    private int        currentStage   = 1;
    private boolean    gameOver       = false;
    private boolean    gameWon        = false;
    private List<String>   guesses      = new ArrayList<>();
    private List<int[]>    feedbackList = new ArrayList<>();
    private Set<Character> correctLetters = new HashSet<>();
    private Set<Character> presentLetters = new HashSet<>();
    private Set<Character> absentLetters  = new HashSet<>();
    private Set<String>    usedWords      = new HashSet<>();
    private int            currentLevel   = 1;
    private int            totalLevels    = 0;
    private int            wordLength;

    private Map<String, Integer> difficultyProgress = new HashMap<>();
    private List<LeaderboardEntry> leaderboard       = new ArrayList<>();
    private String                 currentPlayerName = "Player";
    private final DatabaseManager  db = new DatabaseManager();

    // ── UI ────────────────────────────────────────────────────────────────────
    private CardLayout   cardLayout;
    private JPanel       cardPanel;
    private JLabel[][]   tiles;
    private JPanel       gridPanel;
    private HangmanPanel hangmanPanel;
    private JTextField   inputField;
    private JButton      submitBtn;
    private JLabel       messageLabel;
    private JLabel       clueLabel;
    private JLabel       difficultyLabel;
    private JLabel       inGameScoreLabel;
    private JLabel       sessionScoreLabel;
    private JLabel       stageLabel;
    private Map<Character, JLabel> keyLabels = new HashMap<>();
    private JPanel       keyboardPanel;
    private JPanel       leaderboardListPanel;
    private JPanel       levelSelectPanel;
    private JButton      hintBtn;

    // ── Particle System ───────────────────────────────────────────────────────
    static class Particle {
        float x, y, vx, vy, life, maxLife, size;
        Color color;
        Particle(float x, float y, Color c) {
            this.x = x; this.y = y;
            float angle = (float)(Math.random() * Math.PI * 2);
            float speed = (float)(Math.random() * 0.5 + 0.1);
            vx = (float)Math.cos(angle) * speed;
            vy = (float)Math.sin(angle) * speed - 0.45f;
            life = maxLife = (float)(Math.random() * 240 + 100);
            size = (float)(Math.random() * 2.0 + 0.5);
            color = c;
        }
        void update() { x += vx; y += vy; vy += 0.010f; life--; }
        boolean dead() { return life <= 0; }
        float alpha() { return Math.max(0f, Math.min(1f, life / maxLife)); }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  IMPROVED FUTURISTIC BACKGROUND
    // ══════════════════════════════════════════════════════════════════════════
    static class FuturisticBackground extends JPanel {

        // Particles (rising embers)
        private final List<Particle> particles = new ArrayList<>();

        // Circuit pulse system
        static class CircuitLine {
            int x1, y1, x2mid, ymid, x2, y2;
            Color color;
            float pulseT = 0f;
            float pulseSpeed;
            boolean hasPulse;
            CircuitLine(int x1, int y1, int xm, int ym, int x2, int y2, Color c, float speed, boolean pulse) {
                this.x1=x1; this.y1=y1; this.x2mid=xm; this.ymid=ym; this.x2=x2; this.y2=y2;
                this.color=c; this.pulseSpeed=speed; this.hasPulse=pulse;
            }
        }
        private final List<CircuitLine> circuits = new ArrayList<>();

        // Star field
        static class Star {
            float x, y, size, brightness, twinkleOffset;
        }
        private final Star[] stars = new Star[80];

        private Timer animTimer;
        private int frame = 0;
        private boolean circuitsBuilt = false;

        FuturisticBackground() {
            setOpaque(true);
            // Pre-generate stars with fixed seed
            Random rng = new Random(12345L);
            for (int i = 0; i < stars.length; i++) {
                stars[i] = new Star();
                stars[i].x = rng.nextFloat();          // stored as fraction [0,1]
                stars[i].y = rng.nextFloat();
                stars[i].size = 0.5f + rng.nextFloat() * 1.3f;
                stars[i].brightness = 0.3f + rng.nextFloat() * 0.7f;
                stars[i].twinkleOffset = rng.nextFloat() * (float)(Math.PI * 2);
            }
            animTimer = new Timer(22, e -> {
                frame++;
                spawnParticles();
                updateParticles();
                repaint();
            });
            animTimer.start();
        }

        private void buildCircuits(int w, int h) {
            circuits.clear();
            // Use a deterministic layout so circuits don't jump on resize
            Random rng = new Random(99L);
            Color[] palette = {
                new Color(255, 150, 20),   // amber
                new Color(255, 185, 40),   // bright amber
                new Color( 30, 180, 240),  // cyan
                new Color( 45, 205, 255),  // bright cyan
                new Color(120,  60, 200),  // purple
            };
            // Larger set of circuit traces spread across the full canvas
            int[][] anchors = {
                {0, h/5},    {w/5, h/3},  {w*2/5, h/6}, {w*3/5, h/4},
                {w*4/5, h/5},{w, h/3},    {0, h*2/3},   {w/4, h*4/5},
                {w/2, h*2/3},{w*3/4, h*4/5},{w, h*2/3},
                {w/3, 0},    {w*2/3, 0},  {w/6, h},     {w*5/6, h},
                {0, h/2},    {w, h/2},
            };
            for (int i = 0; i < 28; i++) {
                int[] a1 = anchors[rng.nextInt(anchors.length)];
                int[] a2 = anchors[rng.nextInt(anchors.length)];
                int x1 = a1[0] + (rng.nextInt(80) - 40);
                int y1 = a1[1] + (rng.nextInt(80) - 40);
                int x2 = a2[0] + (rng.nextInt(80) - 40);
                int y2 = a2[1] + (rng.nextInt(80) - 40);
                // L-shaped: go horizontal first, then vertical (or vice versa)
                int xm = rng.nextBoolean() ? x2 : x1;
                int ym = rng.nextBoolean() ? y2 : y1;
                Color base = palette[rng.nextInt(palette.length)];
                int alpha = 14 + rng.nextInt(22);
                Color c = new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha);
                float speed = 0.003f + rng.nextFloat() * 0.009f;
                boolean pulse = rng.nextFloat() > 0.35f;
                CircuitLine cl = new CircuitLine(x1, y1, xm, ym, x2, y2, c, speed, pulse);
                cl.pulseT = rng.nextFloat(); // stagger start
                circuits.add(cl);
            }
            circuitsBuilt = true;
        }

        private void spawnParticles() {
            if (frame % 5 == 0 && particles.size() < 70) {
                int w = getWidth(); int h = getHeight();
                if (w == 0) return;
                // Spawn from bottom edge at various x positions
                float spawnX = (float)(Math.random() * w);
                Color c = Math.random() > 0.5
                    ? new Color(255, 185, 40)
                    : new Color(45, 205, 255);
                particles.add(new Particle(spawnX, h + 6f, c));
            }
        }

        private void updateParticles() {
            particles.removeIf(Particle::dead);
            particles.forEach(Particle::update);
            // Advance circuit pulses
            for (CircuitLine cl : circuits) {
                if (cl.hasPulse) {
                    cl.pulseT += cl.pulseSpeed;
                    if (cl.pulseT > 1f) cl.pulseT -= 1f;
                }
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING,       RenderingHints.VALUE_RENDER_QUALITY);
            int w = getWidth(), h = getHeight();
            if (!circuitsBuilt || circuits.size() < 5) buildCircuits(w, h);

            // ── 1. Deep void base ─────────────────────────────────────────
            g2.setColor(new Color(12, 24, 48));
            g2.fillRect(0, 0, w, h);

            // ── 2. Multi-zone radial glows ────────────────────────────────
            // Zone 1: teal bloom lower-left
            paintRadialGlow(g2, 0, h, Math.max(w, h) * 0.75f,
                new Color(17, 100, 102, 120), new Color(10, 60, 62, 50), new Color(0, 0, 0, 0));
            paintRadialGlow(g2, w, 0, Math.max(w, h) * 0.65f,
                new Color(180, 140, 30, 90), new Color(120, 90, 10, 35), new Color(0, 0, 0, 0));
            paintRadialGlow(g2, w / 2f, h * 0.42f, Math.max(w, h) * 0.48f,
                new Color(17, 100, 102, 70), new Color(10, 55, 58, 30), new Color(0, 0, 0, 0));
            paintRadialGlow(g2, w * 0.85f, h * 0.9f, Math.max(w, h) * 0.45f,
                new Color(200, 160, 20, 60), new Color(0, 0, 0, 0), new Color(0, 0, 0, 0));
            paintRadialGlow(g2, w * 0.1f, h * 0.12f, Math.max(w, h) * 0.38f,
                new Color(17, 100, 102, 55), new Color(0, 0, 0, 0), new Color(0, 0, 0, 0));

            // ── 3. Star field ─────────────────────────────────────────────
            double t = frame * 0.018;
            for (Star s : stars) {
                float twinkle = 0.55f + 0.45f * (float)Math.sin(t + s.twinkleOffset);
                int alpha = (int)(s.brightness * twinkle * 160);
                // Stars that are "closer" (larger) have a slight color tint
                Color starColor;
                int idx = (int)(s.twinkleOffset * 3) % 3;
                if (idx == 0)      starColor = new Color(220, 210, 255, alpha);  // blue-white
                else if (idx == 1) starColor = new Color(255, 230, 190, alpha);  // warm white
                else               starColor = new Color(190, 230, 255, alpha);  // cool cyan
                g2.setColor(starColor);
                float sx = s.x * w, sy = s.y * h;
                if (s.size < 1.2f) {
                    g2.fillRect((int)sx, (int)sy, 1, 1);
                } else {
                    g2.fill(new Ellipse2D.Float(sx - s.size/2, sy - s.size/2, s.size, s.size));
                    // Subtle cross-flare on brighter stars
                    if (s.size > 1.6f && s.brightness > 0.7f) {
                        int fa = (int)(s.brightness * twinkle * 70);
                        g2.setColor(new Color(starColor.getRed(), starColor.getGreen(), starColor.getBlue(), fa));
                        g2.setStroke(new BasicStroke(0.5f));
                        g2.drawLine((int)sx - 3, (int)sy, (int)sx + 3, (int)sy);
                        g2.drawLine((int)sx, (int)sy - 3, (int)sx, (int)sy + 3);
                    }
                }
            }

            // ── 4. Hex grid (two-layer depth) ─────────────────────────────
            drawHexGrid(g2, w, h, 46, 8,  false); // large outer hex
            drawHexGrid(g2, w, h, 20, 5,  true);  // small inner hex, offset

            // ── 5. Circuit traces with glowing pulses ─────────────────────
            drawCircuitTraces(g2, w, h);

            // ── 6. Rising ember particles ─────────────────────────────────
            for (Particle p : particles) {
                float a = p.alpha();
                int alphaMain = Math.max(0, Math.min(255, (int)(a * 200)));
                int alphaHalo = Math.max(0, Math.min(255, (int)(a * 28)));
                Color halo = new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), alphaHalo);
                g2.setColor(halo);
                g2.fill(new Ellipse2D.Float(p.x - p.size * 5, p.y - p.size * 5, p.size * 10, p.size * 10));
                Color pc = new Color(p.color.getRed(), p.color.getGreen(), p.color.getBlue(), alphaMain);
                g2.setColor(pc);
                g2.fill(new Ellipse2D.Float(p.x - p.size / 2, p.y - p.size / 2, p.size, p.size));
            }

            // ── 7. Edge vignette (darkens corners for depth) ──────────────
            RadialGradientPaint vignette = new RadialGradientPaint(
                new Point2D.Float(w / 2f, h / 2f), Math.max(w, h) * 0.72f,
                new float[]{0.45f, 1f},
                new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 130)});
            g2.setPaint(vignette);
            g2.fillRect(0, 0, w, h);

            // ── 8. Subtle scanlines ───────────────────────────────────────
            for (int y = 0; y < h; y += 3) {
                int sl = 6 + (int)(Math.sin(y * 0.04 + frame * 0.01) * 2);
                g2.setColor(new Color(0, 0, 0, sl));
                g2.drawLine(0, y, w, y);
            }

            // ── 9. Horizontal horizon glow band ───────────────────────────
            // A faint amber shimmer across roughly the lower third
            GradientPaint horizGlow = new GradientPaint(
                0, h * 0.68f, new Color(0, 0, 0, 0),
                0, h * 0.78f, new Color(140, 60, 0, 18));
            g2.setPaint(horizGlow);
            g2.fillRect(0, (int)(h * 0.68f), w, (int)(h * 0.12f));
            GradientPaint horizGlow2 = new GradientPaint(
                0, h * 0.78f, new Color(140, 60, 0, 18),
                0, h, new Color(0, 0, 0, 0));
            g2.setPaint(horizGlow2);
            g2.fillRect(0, (int)(h * 0.78f), w, (int)(h * 0.22f));

            g2.dispose();
            super.paintChildren(g);
        }

        /** Radial glow helper with 3-stop gradient */
        private void paintRadialGlow(Graphics2D g2, float cx, float cy, float r,
                                      Color c0, Color c1, Color c2) {
            float mid = (c1.getAlpha() == 0 && c2.getAlpha() == 0) ? 1f : 0.45f;
            RadialGradientPaint glow;
            if (mid == 1f) {
                // Two-stop: center -> edge
                glow = new RadialGradientPaint(new Point2D.Float(cx, cy), r,
                    new float[]{0f, 1f}, new Color[]{c0, c2});
            } else {
                glow = new RadialGradientPaint(new Point2D.Float(cx, cy), r,
                    new float[]{0f, mid, 1f}, new Color[]{c0, c1, c2});
            }
            g2.setPaint(glow);
            // Use a large rect that covers the glow area
            int x = (int)(cx - r), y = (int)(cy - r);
            g2.fillRect(x, y, (int)(r * 2), (int)(r * 2));
        }

        private void drawHexGrid(Graphics2D g2, int w, int h, int hexR, int baseAlpha, boolean offset) {
            int hexW = (int)(hexR * Math.sqrt(3));
            int hexH = hexR * 2;
            g2.setStroke(new BasicStroke(0.45f));
            int offX = offset ? hexW / 2 : 0;
            int offY = offset ? hexH / 2 : 0;
            for (int row = -1; row < h / (hexH * 3 / 4) + 2; row++) {
                for (int col = -1; col < w / hexW + 2; col++) {
                    int cx = col * hexW + (row % 2 == 1 ? hexW / 2 : 0) + offX;
                    int cy = row * (hexH * 3 / 4) + offY;
                    // Vary alpha by distance from center for depth
                    double dist = Math.sqrt(
                        Math.pow(cx - w / 2.0, 2) + Math.pow(cy - h / 2.0, 2))
                        / Math.sqrt(w * w / 4.0 + h * h / 4.0);
                    // Closer to edges = slightly more visible (parallax feel)
                    int alpha = (int)(baseAlpha * (0.5 + dist * 0.85));
                    alpha = Math.min(alpha, 32);
                    // Alternate between amber and cyan tint
                    int tint = (row + col) % 3;
                    Color hexColor;
                    if (tint == 0)      hexColor = new Color(70, 130, 190, alpha);   // blue
                    else if (tint == 1) hexColor = new Color(180, 120, 30, alpha);   // amber
                    else                hexColor = new Color(50, 160, 170, alpha/2); // teal (subtle)
                    g2.setColor(hexColor);
                    drawHex(g2, cx, cy, hexR);
                }
            }
        }

        private void drawHex(Graphics2D g2, int cx, int cy, int r) {
            int[] xs = new int[6], ys = new int[6];
            for (int i = 0; i < 6; i++) {
                double angle = Math.PI / 180.0 * (60 * i - 30);
                xs[i] = cx + (int)(r * Math.cos(angle));
                ys[i] = cy + (int)(r * Math.sin(angle));
            }
            g2.drawPolygon(xs, ys, 6);
        }

        private void drawCircuitTraces(Graphics2D g2, int w, int h) {
            for (CircuitLine cl : circuits) {
                // Draw the L-shaped trace (two segments)
                g2.setColor(cl.color);
                g2.setStroke(new BasicStroke(0.8f));
                g2.drawLine(cl.x1, cl.y1, cl.x2mid, cl.ymid);
                g2.drawLine(cl.x2mid, cl.ymid, cl.x2, cl.y2);

                // Node dots at corners and endpoints
                drawNode(g2, cl.x1,    cl.y1,    cl.color, 3);
                drawNode(g2, cl.x2mid, cl.ymid,  cl.color, 4);
                drawNode(g2, cl.x2,    cl.y2,    cl.color, 3);

                // Traveling pulse dot
                if (cl.hasPulse) {
                    // Interpolate along the two segments
                    float t = cl.pulseT;
                    float px, py;
                    // Segment 1: x1,y1 -> x2mid,ymid   (t 0..0.5)
                    // Segment 2: x2mid,ymid -> x2,y2   (t 0.5..1.0)
                    if (t < 0.5f) {
                        float u = t * 2f;
                        px = cl.x1 + (cl.x2mid - cl.x1) * u;
                        py = cl.y1 + (cl.ymid   - cl.y1) * u;
                    } else {
                        float u = (t - 0.5f) * 2f;
                        px = cl.x2mid + (cl.x2 - cl.x2mid) * u;
                        py = cl.ymid  + (cl.y2 - cl.ymid)  * u;
                    }
                    // Pulse glow
                    int baseA = cl.color.getAlpha();
                    int glowA = Math.min(255, baseA * 5);
                    Color glowC = new Color(cl.color.getRed(), cl.color.getGreen(), cl.color.getBlue(), glowA);
                    g2.setColor(new Color(glowC.getRed(), glowC.getGreen(), glowC.getBlue(), glowA / 3));
                    g2.fill(new Ellipse2D.Float(px - 6, py - 6, 12, 12));
                    g2.setColor(glowC);
                    g2.fill(new Ellipse2D.Float(px - 2.5f, py - 2.5f, 5, 5));
                    // Bright core
                    g2.setColor(new Color(220, 235, 255, Math.min(255, glowA + 50)));
                    g2.fill(new Ellipse2D.Float(px - 1f, py - 1f, 2, 2));
                }
            }
        }

        private void drawNode(Graphics2D g2, int cx, int cy, Color c, int r) {
            int a = c.getAlpha();
            // Outer ring
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), a / 2));
            g2.setStroke(new BasicStroke(0.5f));
            g2.drawOval(cx - r - 2, cy - r - 2, (r + 2) * 2, (r + 2) * 2);
            // Filled dot
            g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.min(255, a * 3)));
            g2.fillOval(cx - r, cy - r, r * 2, r * 2);
        }

        void stopAnimation() { if (animTimer != null) animTimer.stop(); }
    }

    // ── Transparent panel helper ──────────────────────────────────────────────
    private JPanel makeTransparentPanel() {
        JPanel p = new JPanel(); p.setOpaque(false); return p;
    }

    // ── Glow border button ────────────────────────────────────────────────────
    private JButton makeGlowButton(String text, Color accent) {
        JButton btn = new JButton(text) {
            private boolean hover = false;
            { addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true;  repaint(); }
                public void mouseExited (MouseEvent e) { hover = false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int alpha = hover ? 38 : 18;
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), alpha));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                if (hover) {
                    GradientPaint sheen = new GradientPaint(0, 0,
                        new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 30),
                        0, getHeight() / 2, new Color(0,0,0,0));
                    g2.setPaint(sheen);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight() / 2, 8, 8);
                }
                super.paintComponent(g2);
                g2.dispose();
            }
        };
        btn.setFont(new Font("Monospaced", Font.BOLD, 12));
        btn.setForeground(accent);
        btn.setContentAreaFilled(false); btn.setBorderPainted(false);
        btn.setFocusPainted(false); btn.setOpaque(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setBorder(BorderFactory.createCompoundBorder(
            new GlowBorder(accent, 1, 8),
            BorderFactory.createEmptyBorder(7, 20, 7, 20)));
        btn.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent e) {
                btn.setForeground(accent.brighter());
                btn.setBorder(BorderFactory.createCompoundBorder(
                    new GlowBorder(new Color(
                        Math.min(255, accent.getRed() + 30),
                        Math.min(255, accent.getGreen() + 30),
                        Math.min(255, accent.getBlue() + 30)), 2, 8),
                    BorderFactory.createEmptyBorder(6, 19, 6, 19)));
                btn.repaint();
            }
            public void mouseExited(MouseEvent e) {
                btn.setForeground(accent);
                btn.setBorder(BorderFactory.createCompoundBorder(
                    new GlowBorder(accent, 1, 8),
                    BorderFactory.createEmptyBorder(7, 20, 7, 20)));
                btn.repaint();
            }
        });
        return btn;
    }

    // ── Primary CTA button ────────────────────────────────────────────────────
    private JButton makePrimaryButton(String text) {
        JButton btn = new JButton(text) {
            private boolean hover = false;
            { addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true;  repaint(); }
                public void mouseExited (MouseEvent e) { hover = false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                Color top = hover ? new Color(255, 210, 80) : new Color(255, 190, 50);
                Color bot = hover ? new Color(210, 130, 10) : new Color(185, 105, 0);
                GradientPaint gp = new GradientPaint(0, 0, top, 0, getHeight(), bot);
                g2.setPaint(gp);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(new Color(255, 255, 255, hover ? 35 : 20));
                g2.fillRoundRect(3, 2, getWidth()-6, getHeight()/2 - 2, 10, 10);
                g2.setColor(new Color(255, 185, 40, hover ? 100 : 60));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(1, 1, getWidth()-3, getHeight()-3, 11, 11);
                g2.setColor(new Color(0, 0, 0, 60));
                g2.setStroke(new BasicStroke(1f));
                g2.drawLine(4, getHeight()-1, getWidth()-4, getHeight()-1);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        btn.setFont(new Font("Monospaced", Font.BOLD, 16));
        btn.setForeground(new Color(8, 5, 0));
        btn.setContentAreaFilled(false); btn.setBorderPainted(false);
        btn.setFocusPainted(false); btn.setOpaque(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setPreferredSize(new Dimension(270, 54));
        btn.setMaximumSize(new Dimension(270, 54));
        btn.setAlignmentX(CENTER_ALIGNMENT);
        return btn;
    }

    // ── Custom glow border ────────────────────────────────────────────────────
    static class GlowBorder extends javax.swing.border.AbstractBorder {
        private final Color color; private final int thick, radius;
        GlowBorder(Color c, int t, int r) { color = c; thick = t; radius = r; }
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 25));
            g2.setStroke(new BasicStroke(thick + 5));
            g2.drawRoundRect(x+2, y+2, w-5, h-5, radius+3, radius+3);
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 40));
            g2.setStroke(new BasicStroke(thick + 2));
            g2.drawRoundRect(x+1, y+1, w-3, h-3, radius+1, radius+1);
            g2.setColor(color);
            g2.setStroke(new BasicStroke(thick));
            g2.drawRoundRect(x, y, w-1, h-1, radius, radius);
            g2.dispose();
        }
        @Override public Insets getBorderInsets(Component c) { return new Insets(thick+3, thick+3, thick+3, thick+3); }
    }

    // ── Pill badge label ──────────────────────────────────────────────────────
    private JLabel makePillBadge(String text, Color accent) {
        JLabel lbl = new JLabel(text, SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 22));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, getHeight(), getHeight());
                g2.dispose();
                super.paintComponent(g);
            }
        };
        lbl.setFont(new Font("Monospaced", Font.BOLD, 10));
        lbl.setForeground(accent);
        lbl.setOpaque(false);
        lbl.setBorder(BorderFactory.createEmptyBorder(3, 10, 3, 10));
        return lbl;
    }

    // ── Section title ─────────────────────────────────────────────────────────
    private JLabel makeSectionTitle(String text) {
        JLabel lbl = new JLabel(text, SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                int mid = getWidth() / 2;
                int y = getHeight() - 4;
                GradientPaint fade  = new GradientPaint(mid - 80, y, new Color(255, 185, 40, 0), mid - 10, y, AMBER, false);
                GradientPaint fade2 = new GradientPaint(mid + 10, y, AMBER, mid + 80, y, new Color(255, 185, 40, 0), false);
                g2.setStroke(new BasicStroke(1f));
                g2.setPaint(fade);  g2.drawLine(mid - 80, y, mid - 10, y);
                g2.setPaint(fade2); g2.drawLine(mid + 10, y, mid + 80, y);
                g2.setColor(AMBER);
                g2.fillOval(mid - 2, y - 2, 4, 4);
                g2.dispose();
            }
        };
        lbl.setFont(new Font("Monospaced", Font.BOLD, 13));
        lbl.setForeground(new Color(180, 140, 40));
        lbl.setAlignmentX(CENTER_ALIGNMENT);
        lbl.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        return lbl;
    }

    private JLabel makeTagLabel(String text) {
        JLabel lbl = new JLabel(text, SwingConstants.CENTER);
        lbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
        lbl.setForeground(new Color(60, 55, 30));
        lbl.setAlignmentX(CENTER_ALIGNMENT);
        return lbl;
    }

    // ── Sound (delegates to SoundManager) ────────────────────────────────────
    private void playSound(String filePath) {
        if      (filePath.equals(SOUND_CORRECT))  soundManager.playCorrect();
        else if (filePath.equals(SOUND_LOSE))     soundManager.playLose();
        else if (filePath.equals(SOUND_KEYCLICK)) soundManager.playClick();
        else if (filePath.equals(SOUND_START))    soundManager.playStart();
        else {
            // generic fallback — still honours the soundEnabled flag
            if (!soundManager.isSoundEnabled()) return;
            new Thread(() -> {
                try {
                    File f = new File(filePath); if (!f.exists()) return;
                    AudioInputStream ai = AudioSystem.getAudioInputStream(f);
                    Clip clip = AudioSystem.getClip(); clip.open(ai); clip.start();
                    clip.addLineListener(ev -> { if (ev.getType() == LineEvent.Type.STOP) clip.close(); });
                } catch (Exception ignored) {}
            }).start();
        }
    }

    // ── Settings Dialog ───────────────────────────────────────────────────────
    private void showSettingsDialog() {
        JDialog dlg = new JDialog(this, "Settings", true);
        dlg.setUndecorated(true);
        dlg.setSize(340, 200);
        dlg.setLocationRelativeTo(this);

        JPanel panel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                // Dark card background
                g2.setColor(new Color(8, 12, 26));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                // Amber border
                g2.setColor(new Color(255, 185, 40, 80));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(1, 1, getWidth()-3, getHeight()-3, 15, 15);
                // Top accent line
                GradientPaint accent = new GradientPaint(
                    0, 0, new Color(255, 185, 40, 0),
                    getWidth()/2f, 0, new Color(255, 185, 40, 180));
                GradientPaint accent2 = new GradientPaint(
                    getWidth()/2f, 0, new Color(255, 185, 40, 180),
                    getWidth(), 0, new Color(255, 185, 40, 0));
                g2.setPaint(accent);  g2.fillRect(0, 0, getWidth()/2, 2);
                g2.setPaint(accent2); g2.fillRect(getWidth()/2, 0, getWidth()/2, 2);
                g2.dispose();
            }
        };
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(24, 36, 24, 36));

        // Title
        JLabel title = new JLabel("\u2699  SETTINGS", SwingConstants.CENTER);
        title.setFont(new Font("Monospaced", Font.BOLD, 16));
        title.setForeground(AMBER);
        title.setAlignmentX(CENTER_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(20));

        // Sound toggle row
        JPanel row = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 0));
        row.setOpaque(false);

        JLabel soundLbl = new JLabel("Sound Effects:");
        soundLbl.setFont(new Font("Monospaced", Font.PLAIN, 13));
        soundLbl.setForeground(new Color(200, 195, 180));

        // The toggle pill button
        JButton toggleBtn = new JButton(soundManager.isSoundEnabled() ? "ON" : "OFF") {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                boolean on = soundManager.isSoundEnabled();
                // Pill fill
                g2.setColor(on ? new Color(35, 185, 105) : new Color(160, 50, 50));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                // Highlight strip
                g2.setColor(on ? new Color(60, 220, 130, 70) : new Color(220, 80, 80, 60));
                g2.fillRoundRect(2, 2, getWidth()-4, getHeight()/2 - 2, getHeight(), getHeight());
                // Border
                g2.setColor(on ? new Color(80, 240, 150, 160) : new Color(230, 90, 90, 140));
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawRoundRect(1, 1, getWidth()-3, getHeight()-3, getHeight(), getHeight());
                // Draw ON/OFF label manually (reliable across L&Fs)
                String label = on ? "ON" : "OFF";
                g2.setFont(new Font("Monospaced", Font.BOLD, 13));
                FontMetrics fm = g2.getFontMetrics();
                int tx = (getWidth()  - fm.stringWidth(label)) / 2;
                int ty = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.setColor(new Color(0, 0, 0, 90));
                g2.drawString(label, tx + 1, ty + 1);
                g2.setColor(Color.WHITE);
                g2.drawString(label, tx, ty);
                g2.dispose();
                // Skip super.paintComponent so the default button face cannot
                // overdraw our pill or the manually drawn ON/OFF label.
            }
        };
        toggleBtn.setFont(new Font("Monospaced", Font.BOLD, 12));
        toggleBtn.setForeground(Color.WHITE);
        toggleBtn.setContentAreaFilled(false); toggleBtn.setBorderPainted(false);
        toggleBtn.setFocusPainted(false);      toggleBtn.setOpaque(false);
        toggleBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        toggleBtn.setPreferredSize(new Dimension(72, 30));
        toggleBtn.addActionListener(ev -> {
            soundManager.toggleSound();
            toggleBtn.setText(soundManager.isSoundEnabled() ? "ON" : "OFF");
            toggleBtn.repaint();
            // Give audio confirmation when turning back on
            if (soundManager.isSoundEnabled()) soundManager.playClick();
        });

        row.add(soundLbl);
        row.add(toggleBtn);
        panel.add(row);
        panel.add(Box.createVerticalStrut(22));

        // Close button
        JButton closeBtn = makeGlowButton("Close", AMBER_DIM);
        closeBtn.setAlignmentX(CENTER_ALIGNMENT);
        closeBtn.addActionListener(ev -> { soundManager.playClick(); dlg.dispose(); });
        JPanel closeRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        closeRow.setOpaque(false);
        closeRow.add(closeBtn);
        panel.add(closeRow);

        dlg.setContentPane(panel);
        dlg.getContentPane().setBackground(new Color(8, 12, 26));
        dlg.setVisible(true);
    }

    // ── Constructor ───────────────────────────────────────────────────────────
    public LexiGuess() { this(true); }

    public LexiGuess(boolean showImmediately) {
        loadAllWords(); db.connect();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (db.isConnected()) db.saveSessionScore(currentPlayerName, sessionScore, currentStage);
            db.disconnect();
        }));
        difficultyProgress.put("EASY", 1); difficultyProgress.put("MEDIUM", 1); difficultyProgress.put("HARD", 1);

        setTitle("LexiGuess — Programming Enhancement Game");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);
        getContentPane().setBackground(BG_VOID);

        cardLayout = new CardLayout(); cardPanel = new JPanel(cardLayout);
        cardPanel.setBackground(BG_VOID);
        cardPanel.add(buildStartScreen(),        "START");
        cardPanel.add(buildMenuScreen(),         "MENU");
        cardPanel.add(buildLevelSelectScreen(),  "LEVELSELECT");
        cardPanel.add(buildGameScreen(),         "GAME");
        cardPanel.add(buildInstructionsScreen(), "INSTRUCTIONS");
        cardPanel.add(buildLeaderboardScreen(),  "LEADERBOARD");

        add(cardPanel);
        setSize(980, 840);
        setLocationRelativeTo(null);
        cardLayout.show(cardPanel, "START");   // pre-select start screen while hidden
        if (showImmediately) setVisible(true); // legacy callers still work
    }

    // ── End Game Dialog ───────────────────────────────────────────────────────
    private void showEndGameDialog(String title, String message, String word,
                                    String subMessage, Color accentColor, String retryLabel) {
        JDialog dialog = new JDialog(this, title, true);
        dialog.setUndecorated(true);
        if (title.equals("GAME OVER")) {
            dialog.setSize(420, 280);
        } else {
            dialog.setSize(500, 420);
        }
        dialog.setLocationRelativeTo(this);

        JPanel panel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(8, 11, 22));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                GradientPaint overlay = new GradientPaint(0, 0,
                    new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 18),
                    0, getHeight(), new Color(0, 0, 0, 0));
                g2.setPaint(overlay); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                GradientPaint topBar = new GradientPaint(0, 0,
                    new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0),
                    getWidth() / 2, 0,
                    new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 200), false);
                g2.setPaint(topBar); g2.fillRoundRect(0, 0, getWidth(), 3, 20, 20);
                GradientPaint topBar2 = new GradientPaint(getWidth() / 2, 0,
                    new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 200),
                    getWidth(), 0,
                    new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 0), false);
                g2.setPaint(topBar2); g2.fillRoundRect(0, 0, getWidth(), 3, 20, 20);
                g2.setColor(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 60));
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawRoundRect(1, 1, getWidth()-3, getHeight()-3, 19, 19);
                RadialGradientPaint innerGlow = new RadialGradientPaint(
                    new Point2D.Float(getWidth()/2f, 30), getWidth()/2.2f,
                    new float[]{0f, 1f},
                    new Color[]{new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 20), new Color(0,0,0,0)});
                g2.setPaint(innerGlow); g2.fillRect(0, 0, getWidth(), 90);
                g2.dispose();
            }
        };
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(32, 50, 30, 50));
        panel.setOpaque(false);

        JLabel tagLbl = makeTagLabel("◈  SYSTEM REPORT  ◈");
        tagLbl.setForeground(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 110));
        panel.add(tagLbl); panel.add(Box.createVerticalStrut(12));

        JLabel titleLabel = new JLabel(title, SwingConstants.CENTER);
        titleLabel.setFont(new Font("Monospaced", Font.BOLD, 24));
        titleLabel.setForeground(accentColor.equals(RED_DANGER) ? RED_DANGER : AMBER_BRIGHT);
        titleLabel.setAlignmentX(CENTER_ALIGNMENT);
        panel.add(titleLabel); panel.add(Box.createVerticalStrut(8));

        JPanel div = makeSeparatorLine(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 100), 280);
        div.setAlignmentX(CENTER_ALIGNMENT); panel.add(div);
        panel.add(Box.createVerticalStrut(14));

        JLabel msgLabel = new JLabel(message, SwingConstants.CENTER);
        msgLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));
        msgLabel.setForeground(TEXT_MID); msgLabel.setAlignmentX(CENTER_ALIGNMENT);
        panel.add(msgLabel); panel.add(Box.createVerticalStrut(10));

        JPanel wordBox = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 22));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(new Color(accentColor.getRed(), accentColor.getGreen(), accentColor.getBlue(), 70));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 12, 12);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        wordBox.setOpaque(false); wordBox.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 8));
        wordBox.setMaximumSize(new Dimension(380, 70)); wordBox.setAlignmentX(CENTER_ALIGNMENT);
        JLabel wordLabel = new JLabel(word, SwingConstants.CENTER);
        wordLabel.setFont(new Font("Monospaced", Font.BOLD, 38));
        wordLabel.setForeground(TEXT_BRIGHT);
        wordBox.add(wordLabel);
        if (!word.isEmpty()) {
            panel.add(wordBox); panel.add(Box.createVerticalStrut(12));
        } else {
            JLabel outLabel = new JLabel("You ran out of attempts.", SwingConstants.CENTER);
            outLabel.setFont(new Font("Monospaced", Font.PLAIN, 13));
            outLabel.setForeground(TEXT_MID);
            outLabel.setAlignmentX(CENTER_ALIGNMENT);
            panel.add(outLabel); panel.add(Box.createVerticalStrut(12));
}

        JLabel clueDialogLabel = new JLabel(
            "<html><div style='text-align:center;width:350px;font-family:monospace'>" + currentClue + "</div></html>",
            SwingConstants.CENTER);
        clueDialogLabel.setFont(new Font("Monospaced", Font.ITALIC, 11));
        clueDialogLabel.setForeground(CLUE_CLR); clueDialogLabel.setAlignmentX(CENTER_ALIGNMENT);
        if (!word.isEmpty()) panel.add(clueDialogLabel); panel.add(Box.createVerticalStrut(8));

        JLabel subLabel = new JLabel(subMessage, SwingConstants.CENTER);
        subLabel.setFont(new Font("Monospaced", Font.BOLD, 13));
        subLabel.setForeground(accentColor.equals(RED_DANGER) ? RED_DANGER : AMBER);
        subLabel.setAlignmentX(CENTER_ALIGNMENT);
        panel.add(subLabel); panel.add(Box.createVerticalStrut(24));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        btnPanel.setOpaque(false);
        Color btnAccent = accentColor.equals(RED_DANGER) ? RED_DANGER : GREEN_CORRECT;
        JButton retryBtn = makeGlowButton(retryLabel, btnAccent);
        retryBtn.addActionListener(e -> { dialog.dispose(); startGame(); });
        JButton lvlBtn = makeGlowButton("Level Select", AMBER);
        lvlBtn.addActionListener(e -> { dialog.dispose(); updateLevelSelectScreen(); cardLayout.show(cardPanel, "LEVELSELECT"); });
        JButton exitBtn = makeGlowButton("Exit", TEXT_MID);
        exitBtn.addActionListener(e -> { dialog.dispose(); cardLayout.show(cardPanel, "MENU"); });
        btnPanel.add(retryBtn); btnPanel.add(lvlBtn); btnPanel.add(exitBtn);
        btnPanel.setAlignmentX(CENTER_ALIGNMENT);
        panel.add(btnPanel);

        dialog.setContentPane(panel);
        dialog.getContentPane().setBackground(new Color(8, 11, 22));
        dialog.setVisible(true);
    }

    // ── Start Screen ──────────────────────────────────────────────────────────
    private JPanel buildStartScreen() {
        FuturisticBackground root = new FuturisticBackground();
        root.setLayout(new BorderLayout());

        JPanel center = makeTransparentPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(70, 120, 70, 120));

        JLabel versionPill = makePillBadge("◈  v1.0  ·  programming_challenge.exe", CYAN_DIM);
        versionPill.setAlignmentX(CENTER_ALIGNMENT);
        center.add(versionPill);
        center.add(Box.createVerticalStrut(22));

        JLabel title = new JLabel("LexiGuess", SwingConstants.CENTER) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setFont(getFont());
                FontMetrics fm = g2.getFontMetrics();
                int tw = fm.stringWidth(getText());
                int tx = (getWidth() - tw) / 2;
                RadialGradientPaint halo = new RadialGradientPaint(
                    new Point2D.Float(getWidth()/2f, getHeight()/2f), 240,
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{new Color(255, 160, 0, 55), new Color(180, 80, 0, 18), new Color(0,0,0,0)});
                g2.setPaint(halo); g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(180, 80, 0, 55));
                g2.drawString(getText(), tx + 4, getHeight() - 14);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        title.setFont(new Font("Serif", Font.BOLD | Font.ITALIC, 86));
        title.setForeground(AMBER);
        title.setAlignmentX(CENTER_ALIGNMENT);
        center.add(title);

        JLabel sub = new JLabel("Programming Enhancement Game", SwingConstants.CENTER);
        sub.setFont(new Font("Monospaced", Font.PLAIN, 14));
        sub.setForeground(new Color(110, 108, 125));
        sub.setAlignmentX(CENTER_ALIGNMENT);
        center.add(sub);
        center.add(Box.createVerticalStrut(8));

        JPanel sep = makeSeparatorLine(AMBER, 200);
        sep.setAlignmentX(CENTER_ALIGNMENT);
        center.add(sep);
        center.add(Box.createVerticalStrut(36));

        JLabel nameLbl = new JLabel("Enter your username", SwingConstants.CENTER);
        nameLbl.setFont(new Font("Monospaced", Font.PLAIN, 12));
        nameLbl.setForeground(new Color(80, 100, 130));
        nameLbl.setAlignmentX(CENTER_ALIGNMENT);
        center.add(nameLbl); center.add(Box.createVerticalStrut(8));

        JTextField nameField = new JTextField(15) {
            private boolean focused = false;
            { addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { focused = true;  repaint(); }
                public void focusLost (FocusEvent e) { focused = false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(10, 14, 28)); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                GradientPaint inner = new GradientPaint(0, 0, new Color(20, 28, 55), 0, getHeight(), new Color(8, 12, 24));
                g2.setPaint(inner); g2.fillRoundRect(1, 1, getWidth()-2, getHeight()-2, 9, 9);
                Color bColor = focused ? new Color(45, 205, 255, 150) : new Color(40, 50, 80, 120);
                g2.setColor(bColor);
                g2.setStroke(new BasicStroke(focused ? 1.5f : 1f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                if (focused) {
                    g2.setColor(new Color(45, 205, 255, 20));
                    g2.setStroke(new BasicStroke(4f));
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                }
                g2.dispose();
                super.paintComponent(g);
            }
        };
        nameField.setFont(new Font("Monospaced", Font.BOLD, 17));
        nameField.setForeground(TEXT_BRIGHT); nameField.setOpaque(false);
        nameField.setBackground(new Color(0,0,0,0)); nameField.setCaretColor(AMBER);
        nameField.setHorizontalAlignment(SwingConstants.CENTER);
        nameField.setBorder(BorderFactory.createEmptyBorder(10, 18, 10, 18));
        nameField.setMaximumSize(new Dimension(290, 52)); nameField.setAlignmentX(CENTER_ALIGNMENT);
        center.add(nameField);
        center.add(Box.createVerticalStrut(32));

        JButton startBtn = makePrimaryButton("▶   START GAME");
        startBtn.addActionListener(e -> {
            String name = nameField.getText().trim();
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(null, "Please enter a valid username.", "Invalid Username", JOptionPane.WARNING_MESSAGE);
                return;
            }
            currentPlayerName = name;
            if (db.isConnected()) {
                java.util.Map<String, Integer> saved = db.loadAllProgress(currentPlayerName);
                difficultyProgress.put("EASY",   saved.get("EASY"));
                difficultyProgress.put("MEDIUM", saved.get("MEDIUM"));
                difficultyProgress.put("HARD",   saved.get("HARD"));
            }
            if (db.isConnected()) {
                int[] savedSession = db.loadSessionScore(currentPlayerName);
                sessionScore  = savedSession[0];
                currentStage  = savedSession[1];
                sessionScoreLabel.setText("Session Score:  " + sessionScore + "   ·   Stage " + currentStage);
            }
            playSound(SOUND_START); cardLayout.show(cardPanel, "MENU");
        });
        center.add(startBtn); center.add(Box.createVerticalStrut(16));

        JPanel rowBtns = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 0));
        rowBtns.setOpaque(false); rowBtns.setAlignmentX(CENTER_ALIGNMENT);
        JButton leaderBtn = makeGlowButton("★  Leaderboard", AMBER_DIM);
        leaderBtn.addActionListener(e -> { updateLeaderboardDisplay(); cardLayout.show(cardPanel, "LEADERBOARD"); });
        JButton exitBtn = makeGlowButton("✕  Quit", new Color(130, 40, 40));
        exitBtn.addActionListener(e -> saveAndExit());
        rowBtns.add(leaderBtn); rowBtns.add(exitBtn);
        center.add(rowBtns);
        center.add(Box.createVerticalStrut(30));

        JLabel hint = makeTagLabel("Awaiting input  ·  ready to compile your first guess");
        hint.setForeground(new Color(35, 50, 25));
        center.add(hint);

        root.add(center, BorderLayout.CENTER);
        return root;
    }

    private JPanel makeSeparatorLine(Color c, int width) {
        return new JPanel() {
            { setOpaque(false); setPreferredSize(new Dimension(width, 2));
              setMaximumSize(new Dimension(width, 2)); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                GradientPaint gp  = new GradientPaint(0, 0, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0), getWidth()/2, 0, c, false);
                GradientPaint gp2 = new GradientPaint(getWidth()/2, 0, c, getWidth(), 0, new Color(c.getRed(), c.getGreen(), c.getBlue(), 0), false);
                g2.setPaint(gp);  g2.fillRect(0, 0, getWidth()/2, getHeight());
                g2.setPaint(gp2); g2.fillRect(getWidth()/2, 0, getWidth()/2, getHeight());
                g2.dispose();
            }
        };
    }

    // ── Menu Screen ───────────────────────────────────────────────────────────
    private JPanel buildMenuScreen() {
        FuturisticBackground root = new FuturisticBackground();
        root.setLayout(new BorderLayout());

        JPanel topBar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(5, 7, 16, 210)); g2.fillRect(0, 0, getWidth(), getHeight());
                GradientPaint fade = new GradientPaint(0, getHeight()-2, new Color(255, 185, 40, 60),
                    getWidth(), getHeight()-2, new Color(45, 205, 255, 20), false);
                g2.setPaint(fade); g2.fillRect(0, getHeight()-1, getWidth(), 1);
                g2.dispose();
            }
        };
        topBar.setOpaque(false);
        topBar.setBorder(BorderFactory.createEmptyBorder(12, 22, 12, 22));

        JPanel logoGroup = makeTransparentPanel();
        logoGroup.setLayout(new BoxLayout(logoGroup, BoxLayout.Y_AXIS));
        JLabel logoSmall = new JLabel("⬡ LexiGuess");
        logoSmall.setFont(new Font("Serif", Font.BOLD | Font.ITALIC, 22)); logoSmall.setForeground(AMBER);
        JLabel logoSub = new JLabel("Programming Challenge");
        logoSub.setFont(new Font("Monospaced", Font.PLAIN, 10)); logoSub.setForeground(new Color(70, 65, 35));
        logoGroup.add(logoSmall); logoGroup.add(logoSub);
        topBar.add(logoGroup, BorderLayout.WEST);

        JPanel navRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        navRight.setOpaque(false);
        JButton instrBtn  = makeGlowButton("How to Play", AMBER_DIM);
        instrBtn.addActionListener(e -> cardLayout.show(cardPanel, "INSTRUCTIONS"));
        JButton leaderBtn = makeGlowButton("Leaderboard", CYAN_DIM);
        leaderBtn.addActionListener(e -> { updateLeaderboardDisplay(); cardLayout.show(cardPanel, "LEADERBOARD"); });
        JButton exitBtn   = makeGlowButton("Exit", new Color(110, 35, 35));
        exitBtn.addActionListener(e -> {
            if (db.isConnected()) {
                db.saveSessionScore(currentPlayerName, sessionScore, currentStage);
                db.saveProgress(currentPlayerName, difficulty, difficultyProgress.getOrDefault(difficulty, 1));
            }
            sessionScore = 0; currentStage = 1; currentPlayerName = "Player";
            difficultyProgress.put("EASY", 1); difficultyProgress.put("MEDIUM", 1); difficultyProgress.put("HARD", 1);
            cardLayout.show(cardPanel, "START");
        });
        navRight.add(instrBtn); navRight.add(leaderBtn); navRight.add(exitBtn);
        topBar.add(navRight, BorderLayout.EAST);
        root.add(topBar, BorderLayout.NORTH);

        JPanel center = makeTransparentPanel();
        center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
        center.setBorder(BorderFactory.createEmptyBorder(30, 60, 20, 60));

        center.add(makeSectionTitle("SELECT DIFFICULTY"));
        center.add(Box.createVerticalStrut(24));

        JPanel diffRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 0));
        diffRow.setOpaque(false);
        diffRow.add(makeDiffCard("EASY",   "Novice",     "Common concepts",    EASY_CLR,   easyWords.size()));
        diffRow.add(makeDiffCard("MEDIUM", "Scholar",    "Intermediate terms", MEDIUM_CLR, mediumWords.size()));
        diffRow.add(makeDiffCard("HARD",   "Mastermind", "Advanced jargon",    HARD_CLR,   hardWords.size()));
        center.add(diffRow);
        center.add(Box.createVerticalStrut(28));

        JPanel scoreBox = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(14, 18, 34));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                g2.setColor(new Color(255, 185, 40, 45));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        scoreBox.setOpaque(false);
        scoreBox.setLayout(new FlowLayout(FlowLayout.CENTER, 30, 0));
        scoreBox.setMaximumSize(new Dimension(480, 50));
        scoreBox.setPreferredSize(new Dimension(480, 50));
        scoreBox.setAlignmentX(CENTER_ALIGNMENT);

        sessionScoreLabel = new JLabel("Session Score:  0   ·   Stage 1", SwingConstants.CENTER);
        sessionScoreLabel.setFont(new Font("Monospaced", Font.BOLD, 14));
        sessionScoreLabel.setForeground(AMBER);
        scoreBox.add(sessionScoreLabel);
        center.add(scoreBox);

        root.add(center, BorderLayout.CENTER);

        JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.CENTER)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(4, 6, 14, 200)); g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(255, 185, 40, 28)); g2.drawLine(0, 0, getWidth(), 0);
                g2.dispose();
            }
        };
        statusBar.setOpaque(false);
        JLabel statusLbl = new JLabel("◈  SYSTEM ONLINE  ·  DATABASE CONNECTED  ·  AWAITING SELECTION  ◈");
        statusLbl.setFont(new Font("Monospaced", Font.PLAIN, 10)); statusLbl.setForeground(new Color(40, 40, 28));
        statusBar.add(statusLbl);
        root.add(statusBar, BorderLayout.SOUTH);
        return root;
    }

    // ── Difficulty Card ───────────────────────────────────────────────────────
    private JPanel makeDiffCard(String diff, String rankTitle, String descriptor,
                                 Color accent, int wordCount) {
        JPanel card = new JPanel() {
            private boolean hover = false;
            { setOpaque(false);
              addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true;  repaint(); }
                public void mouseExited (MouseEvent e) { hover = false; repaint(); }
              }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                GradientPaint bgGrad = new GradientPaint(0, 0,
                    new Color(accent.getRed()/6, accent.getGreen()/6, accent.getBlue()/6, 255),
                    0, getHeight(),
                    new Color(accent.getRed()/10, accent.getGreen()/10, accent.getBlue()/10, 255));
                g2.setPaint(bgGrad); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 16, 16);
                g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 6));
                g2.setStroke(new BasicStroke(0.8f));
                for (int y = 0; y < getHeight(); y += 24)
                    for (int x = 0; x < getWidth(); x += 30)
                        drawMiniHex(g2, x + (y/24 % 2 == 0 ? 0 : 15), y);
                if (hover) {
                    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 35));
                    g2.setStroke(new BasicStroke(6f));
                    g2.drawRoundRect(2, 2, getWidth()-5, getHeight()-5, 15, 15);
                    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 120));
                    g2.setStroke(new BasicStroke(2f));
                    g2.drawRoundRect(1, 1, getWidth()-3, getHeight()-3, 15, 15);
                    GradientPaint topGlow = new GradientPaint(0, 0,
                        new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 180),
                        getWidth(), 0, new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 20));
                    g2.setPaint(topGlow); g2.fillRoundRect(0, 0, getWidth(), 3, 16, 16);
                } else {
                    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 55));
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 15, 15);
                    GradientPaint topLine = new GradientPaint(getWidth()/4, 0,
                        new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0),
                        getWidth()/2, 0, new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 80), false);
                    g2.setPaint(topLine);
                    g2.fillRoundRect(0, 0, getWidth(), 2, 16, 16);
                }
                g2.dispose();
                super.paintComponent(g);
            }
            private void drawMiniHex(Graphics2D g2, int cx, int cy) {
                int r = 10; int[] xs = new int[6], ys = new int[6];
                for (int i = 0; i < 6; i++) {
                    double ang = Math.PI / 180.0 * (60*i - 30);
                    xs[i] = cx + (int)(r * Math.cos(ang)); ys[i] = cy + (int)(r * Math.sin(ang));
                }
                g2.drawPolygon(xs, ys, 6);
            }
        };
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setPreferredSize(new Dimension(222, 268));
        card.setBorder(BorderFactory.createEmptyBorder(22, 20, 22, 20));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPanel accentLine = makeSeparatorLine(accent, 160);
        accentLine.setAlignmentX(CENTER_ALIGNMENT);

        String icon = diff.equals("EASY") ? "◉" : diff.equals("MEDIUM") ? "◈" : "⬡";
        JLabel iconLbl = new JLabel(icon, SwingConstants.CENTER);
        iconLbl.setFont(new Font("Monospaced", Font.BOLD, 28));
        iconLbl.setForeground(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 180));
        iconLbl.setAlignmentX(CENTER_ALIGNMENT);

        JLabel diffLbl = new JLabel(diff, SwingConstants.CENTER);
        diffLbl.setFont(new Font("Monospaced", Font.BOLD, 20)); diffLbl.setForeground(accent);
        diffLbl.setAlignmentX(CENTER_ALIGNMENT);

        JLabel rankLbl = makePillBadge(rankTitle, accent);
        rankLbl.setAlignmentX(CENTER_ALIGNMENT);

        JPanel rankWrapper = makeTransparentPanel();
        rankWrapper.setLayout(new FlowLayout(FlowLayout.CENTER, 0, 0));
        rankWrapper.add(rankLbl);
        rankWrapper.setAlignmentX(CENTER_ALIGNMENT);

        JLabel descLbl = new JLabel(descriptor, SwingConstants.CENTER);
        descLbl.setFont(new Font("Monospaced", Font.PLAIN, 10));
        descLbl.setForeground(TEXT_MID); descLbl.setAlignmentX(CENTER_ALIGNMENT);

        JLabel countLbl = new JLabel("6 attempts  ·  " + wordCount + " levels", SwingConstants.CENTER);
        countLbl.setFont(new Font("Monospaced", Font.PLAIN, 9)); countLbl.setForeground(TEXT_DIM);
        countLbl.setAlignmentX(CENTER_ALIGNMENT);

        JLabel clueLbl = new JLabel("✦  Clue included", SwingConstants.CENTER);
        clueLbl.setFont(new Font("Monospaced", Font.PLAIN, 9)); clueLbl.setForeground(CYAN_DIM);
        clueLbl.setAlignmentX(CENTER_ALIGNMENT);

        JButton playBtn = makeGlowButton("SELECT LEVEL  →", accent);
        playBtn.setFont(new Font("Monospaced", Font.BOLD, 11));
        playBtn.setMaximumSize(new Dimension(175, 36)); playBtn.setAlignmentX(CENTER_ALIGNMENT);
        playBtn.addActionListener(e -> {
            difficulty = diff;
            currentLevel = difficultyProgress.getOrDefault(diff, 1);
            updateLevelSelectScreen();
            cardLayout.show(cardPanel, "LEVELSELECT");
        });

        card.add(iconLbl); card.add(Box.createVerticalStrut(4));
        card.add(accentLine); card.add(Box.createVerticalStrut(10));
        card.add(diffLbl); card.add(Box.createVerticalStrut(6));
        card.add(rankWrapper); card.add(Box.createVerticalStrut(12));
        card.add(descLbl); card.add(Box.createVerticalStrut(4));
        card.add(countLbl); card.add(Box.createVerticalStrut(6));
        card.add(clueLbl); card.add(Box.createVerticalGlue());
        card.add(Box.createVerticalStrut(12)); card.add(playBtn);
        return card;
    }

    // ── Level Select Screen ───────────────────────────────────────────────────
    private JPanel buildLevelSelectScreen() {
        FuturisticBackground root = new FuturisticBackground();
        root.setLayout(new BorderLayout());

        JPanel topBar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(5, 7, 18, 220)); g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(new Color(255, 185, 40, 40)); g2.setStroke(new BasicStroke(1f));
                g2.drawLine(0, getHeight()-1, getWidth(), getHeight()-1);
                g2.dispose();
            }
        };
        topBar.setOpaque(false);
        topBar.setBorder(BorderFactory.createEmptyBorder(12, 22, 12, 22));
        JButton backBtn = makeGlowButton("← Back", AMBER_DIM);
        backBtn.addActionListener(e -> cardLayout.show(cardPanel, "MENU"));
        topBar.add(backBtn, BorderLayout.WEST);
        JLabel titleLbl = new JLabel("◈  SKILL TREE  ◈", SwingConstants.CENTER);
        titleLbl.setFont(new Font("Monospaced", Font.BOLD, 20)); titleLbl.setForeground(AMBER);
        topBar.add(titleLbl, BorderLayout.CENTER);
        JPanel ph = new JPanel(); ph.setOpaque(false); ph.setPreferredSize(backBtn.getPreferredSize());
        topBar.add(ph, BorderLayout.EAST);
        root.add(topBar, BorderLayout.NORTH);

        levelSelectPanel = new JPanel();
        levelSelectPanel.setOpaque(false);
        levelSelectPanel.setBorder(BorderFactory.createEmptyBorder(28, 50, 28, 50));
        JScrollPane scroll = new JScrollPane(levelSelectPanel);
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false); scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getVerticalScrollBar().setBackground(BG_DEEP);
        root.add(scroll, BorderLayout.CENTER);

        JPanel botBar = new JPanel(new FlowLayout(FlowLayout.CENTER)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(4, 6, 14, 180)); g2.fillRect(0, 0, getWidth(), getHeight());
                g2.dispose();
            }
        };
        botBar.setOpaque(false);
        JLabel botLbl = new JLabel("◈  Complete each level to unlock the next node  ◈");
        botLbl.setFont(new Font("Monospaced", Font.PLAIN, 10)); botLbl.setForeground(new Color(45, 45, 30));
        botBar.add(botLbl); root.add(botBar, BorderLayout.SOUTH);
        return root;
    }

    private void updateLevelSelectScreen() {
        levelSelectPanel.removeAll();
        levelSelectPanel.setLayout(new BoxLayout(levelSelectPanel, BoxLayout.Y_AXIS));
        List<WordEntry> bank = difficulty.equals("EASY") ? easyWords
                             : difficulty.equals("MEDIUM") ? mediumWords : hardWords;
        totalLevels = bank.size();
        Color accent = difficulty.equals("EASY") ? EASY_CLR : difficulty.equals("MEDIUM") ? MEDIUM_CLR : HARD_CLR;
        int highestUnlocked = difficultyProgress.getOrDefault(difficulty, 1);

        JPanel headerPanel = makeTransparentPanel();
        headerPanel.setLayout(new BoxLayout(headerPanel, BoxLayout.Y_AXIS));

        JLabel diffLbl = new JLabel(difficulty + "  —  Skill Tree", SwingConstants.CENTER);
        diffLbl.setFont(new Font("Monospaced", Font.BOLD, 20)); diffLbl.setForeground(accent);
        diffLbl.setAlignmentX(CENTER_ALIGNMENT); headerPanel.add(diffLbl);
        headerPanel.add(Box.createVerticalStrut(6));

        int cleared = highestUnlocked - 1;
        JLabel progressLbl = new JLabel(cleared + " of " + totalLevels + " nodes cleared", SwingConstants.CENTER);
        progressLbl.setFont(new Font("Monospaced", Font.PLAIN, 11)); progressLbl.setForeground(TEXT_MID);
        progressLbl.setAlignmentX(CENTER_ALIGNMENT); headerPanel.add(progressLbl);
        headerPanel.add(Box.createVerticalStrut(10));

        JPanel progressBarWrapper = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(18, 22, 36)); g2.fillRoundRect(0, 3, getWidth(), 8, 8, 8);
                g2.setColor(new Color(30, 35, 55)); g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 3, getWidth()-1, 7, 8, 8);
                if (totalLevels > 0 && cleared > 0) {
                    int filled = Math.max(8, (int)((double)cleared / totalLevels * getWidth()));
                    GradientPaint gp = new GradientPaint(0, 0, accent.darker().darker(), filled, 0, accent);
                    g2.setPaint(gp); g2.fillRoundRect(0, 3, filled, 8, 8, 8);
                    g2.setColor(new Color(255, 255, 255, 30));
                    g2.fillRoundRect(0, 3, filled, 4, 8, 8);
                }
                if (totalLevels > 0) {
                    int pct = (int)((double)cleared / totalLevels * 100);
                    String pctStr = pct + "%";
                    g2.setFont(new Font("Monospaced", Font.BOLD, 9));
                    g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 180));
                    FontMetrics fm = g2.getFontMetrics();
                    g2.drawString(pctStr, getWidth() - fm.stringWidth(pctStr) - 2, 2);
                }
                g2.dispose();
            }
        };
        progressBarWrapper.setOpaque(false);
        progressBarWrapper.setPreferredSize(new Dimension(600, 18));
        progressBarWrapper.setMaximumSize(new Dimension(600, 18));
        progressBarWrapper.setAlignmentX(CENTER_ALIGNMENT);
        headerPanel.add(progressBarWrapper);
        headerPanel.setAlignmentX(CENTER_ALIGNMENT);
        levelSelectPanel.add(headerPanel);
        levelSelectPanel.add(Box.createVerticalStrut(26));

        int cols = 5;
        int rows = (int) Math.ceil((double) totalLevels / cols);
        JPanel grid = new JPanel(new GridLayout(rows, cols, 16, 16));
        grid.setOpaque(false);
        grid.setMaximumSize(new Dimension(680, rows * 112));
        grid.setAlignmentX(CENTER_ALIGNMENT);

        for (int i = 1; i <= totalLevels; i++) {
            final int lvl = i;
            boolean isCleared  = (i < highestUnlocked);
            boolean isUnlocked = (i <= highestUnlocked);
            boolean isCurrent  = (i == highestUnlocked);

            JPanel cell = new JPanel() {
                private boolean hover = false;
                { if (isUnlocked) addMouseListener(new MouseAdapter() {
                    public void mouseEntered(MouseEvent e) { hover = true;  repaint(); }
                    public void mouseExited (MouseEvent e) { hover = false; repaint(); }
                }); }
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    GradientPaint bgGp;
                    if (isCleared)
                        bgGp = new GradientPaint(0, 0,
                            new Color(accent.getRed()/6, accent.getGreen()/6, accent.getBlue()/6, 255),
                            0, getHeight(),
                            new Color(accent.getRed()/10, accent.getGreen()/10, accent.getBlue()/10, 255));
                    else if (isUnlocked)
                        bgGp = new GradientPaint(0, 0, new Color(18, 22, 42), 0, getHeight(), new Color(12, 16, 32));
                    else
                        bgGp = new GradientPaint(0, 0, new Color(9, 11, 22), 0, getHeight(), new Color(7, 9, 18));
                    g2.setPaint(bgGp); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
                    float thick = (hover || isCurrent) ? 2f : 1f;
                    Color bd;
                    if (isCleared)       bd = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 160);
                    else if (isCurrent)  bd = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 220);
                    else if (isUnlocked) bd = hover ? AMBER_DIM : new Color(AMBER_DIM.getRed(), AMBER_DIM.getGreen(), AMBER_DIM.getBlue(), 60);
                    else                 bd = new Color(25, 28, 42);
                    g2.setColor(bd); g2.setStroke(new BasicStroke(thick));
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 13, 13);
                    if (hover && isUnlocked) {
                        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 18));
                        g2.setStroke(new BasicStroke(5f));
                        g2.drawRoundRect(1, 1, getWidth()-3, getHeight()-3, 13, 13);
                    }
                    if (isCurrent) {
                        g2.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 50));
                        g2.fillRoundRect(0, 0, getWidth(), 3, 14, 14);
                    }
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
            cell.setOpaque(false);
            cell.setPreferredSize(new Dimension(112, 96));
            cell.setBorder(BorderFactory.createEmptyBorder(10, 8, 10, 8));
            if (isUnlocked) cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            String numText = isUnlocked ? String.valueOf(lvl) : "⬡";
            JLabel numLbl  = new JLabel(numText, SwingConstants.CENTER);
            numLbl.setFont(new Font("Monospaced", Font.BOLD, isUnlocked ? 26 : 16));
            numLbl.setForeground(isCleared ? accent : isUnlocked ? TEXT_BRIGHT : new Color(32, 32, 46));
            numLbl.setAlignmentX(CENTER_ALIGNMENT);

            String statusText = isCleared ? "✦ Cleared" : isCurrent ? "▶ Play" : isUnlocked ? "Available" : "Locked";
            JLabel statusLbl  = new JLabel(statusText, SwingConstants.CENTER);
            statusLbl.setFont(new Font("Monospaced", Font.PLAIN, 9));
            statusLbl.setForeground(isCleared ? accent : isCurrent ? AMBER : isUnlocked ? TEXT_MID : new Color(32, 32, 46));
            statusLbl.setAlignmentX(CENTER_ALIGNMENT);

            cell.add(Box.createVerticalGlue());
            cell.add(numLbl); cell.add(Box.createVerticalStrut(4)); cell.add(statusLbl);
            cell.add(Box.createVerticalGlue());

            if (isUnlocked) cell.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    currentLevel = lvl; usedWords.clear(); startGame();
                }
            });
            grid.add(cell);
        }
        levelSelectPanel.add(grid);
        levelSelectPanel.revalidate(); levelSelectPanel.repaint();
    }

    // ── Instructions Screen ───────────────────────────────────────────────────
    private JPanel buildInstructionsScreen() {
        FuturisticBackground root = new FuturisticBackground();
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(28, 65, 28, 65));

        JLabel title = new JLabel("◈  HOW TO PLAY  ◈", SwingConstants.CENTER);
        title.setFont(new Font("Monospaced", Font.BOLD, 26)); title.setForeground(AMBER);
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 16, 0));
        root.add(title, BorderLayout.NORTH);

        String html = "<html><body style='background:#06080f;color:#8a88a0;font-family:monospace;padding:20px;line-height:1.6;'>"
            + "<h2 style='color:#ffb928;font-size:13px;letter-spacing:2px;'>◈  OBJECTIVE</h2>"
            + "<p style='margin-left:12px;'>Decipher the hidden programming word within 6 attempts to claim victory and advance.</p><br>"
            + "<h2 style='color:#ffb928;font-size:13px;letter-spacing:2px;'>◈  SKILL TREE</h2>"
            + "<p style='margin-left:12px;'>Each difficulty has its own levels. Complete a level to unlock the next node.</p><br>"
            + "<h2 style='color:#ffb928;font-size:13px;letter-spacing:2px;'>◈  DIFFICULTY TIERS</h2>"
            + "<table border='0' cellpadding='8' style='margin-left:12px;'>"
            + "<tr><td style='color:#2dd278;font-weight:bold;'>[ EASY ]</td><td>Everyday concepts any beginner knows</td></tr>"
            + "<tr><td style='color:#d2a019;font-weight:bold;'>[ MEDIUM ]</td><td>Intermediate terms encountered during learning</td></tr>"
            + "<tr><td style='color:#dc4646;font-weight:bold;'>[ HARD ]</td><td>Advanced / niche jargon for seasoned developers</td></tr>"
            + "</table><br>"
            + "<h2 style='color:#ffb928;font-size:13px;letter-spacing:2px;'>◈  COLOUR FEEDBACK</h2>"
            + "<table border='0' cellpadding='8' style='margin-left:12px;'>"
            + "<tr><td style='color:#23b969;font-weight:bold;width:80px;'>GREEN</td><td>Correct letter, correct position</td></tr>"
            + "<tr><td style='color:#d2a019;font-weight:bold;'>AMBER</td><td>Letter exists but in the wrong position</td></tr>"
            + "<tr><td style='color:#606075;font-weight:bold;'>GREY</td><td>Letter not present in the word at all</td></tr>"
            + "</table><br>"
            + "<h2 style='color:#ffb928;font-size:13px;letter-spacing:2px;'>◈  SCORING</h2>"
            + "<table border='0' cellpadding='8' style='margin-left:12px;'>"
            + "<tr><td style='color:#2dd278;width:90px;'>[ EASY ]</td><td>100 pts base  +  10 × remaining attempts</td></tr>"
            + "<tr><td style='color:#d2a019;'>[ MEDIUM ]</td><td>200 pts base  +  20 × remaining attempts</td></tr>"
            + "<tr><td style='color:#dc4646;'>[ HARD ]</td><td>300 pts base  +  30 × remaining attempts</td></tr>"
            + "</table><br>"
            + "<h2 style='color:#ffb928;font-size:13px;letter-spacing:2px;'>◈  HINTS</h2>"
            + "<p style='margin-left:12px;'>Up to 3 hints per round. Each reveals a random letter at a score cost.</p><br>"
            + "<h2 style='color:#ffb928;font-size:13px;letter-spacing:2px;'>◈  THE FIGURE</h2>"
            + "<p style='margin-left:12px;'>Each wrong guess detaches a body part with physics simulation. 6 wrong guesses = game over.</p>"
            + "</body></html>";

        JEditorPane ep = new JEditorPane("text/html", html);
        ep.setEditable(false); ep.setBackground(new Color(6, 8, 15));
        JScrollPane scroll = new JScrollPane(ep);
        scroll.setBorder(new GlowBorder(AMBER_DIM, 1, 6));
        scroll.getViewport().setBackground(new Color(6, 8, 15));
        root.add(scroll, BorderLayout.CENTER);

        JPanel bot = new JPanel(new FlowLayout(FlowLayout.CENTER));
        bot.setOpaque(false); bot.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        JButton back = makeGlowButton("← Return to Hub", AMBER_DIM);
        back.addActionListener(e -> cardLayout.show(cardPanel, "MENU"));
        bot.add(back); root.add(bot, BorderLayout.SOUTH);
        return root;
    }

    // ── Leaderboard Screen ────────────────────────────────────────────────────
    private JPanel buildLeaderboardScreen() {
        FuturisticBackground root = new FuturisticBackground();
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(28, 65, 28, 65));

        JLabel title = new JLabel("◈  LEADERBOARD  ◈", SwingConstants.CENTER);
        title.setFont(new Font("Monospaced", Font.BOLD, 26)); title.setForeground(AMBER);
        title.setBorder(BorderFactory.createEmptyBorder(0, 0, 16, 0));
        root.add(title, BorderLayout.NORTH);

        leaderboardListPanel = new JPanel();
        leaderboardListPanel.setLayout(new BoxLayout(leaderboardListPanel, BoxLayout.Y_AXIS));
        leaderboardListPanel.setBackground(new Color(6, 8, 15));
        leaderboardListPanel.setBorder(BorderFactory.createEmptyBorder(14, 24, 14, 24));
        JScrollPane scroll = new JScrollPane(leaderboardListPanel);
        scroll.setBorder(new GlowBorder(AMBER_DIM, 1, 6));
        scroll.getViewport().setBackground(new Color(6, 8, 15));
        root.add(scroll, BorderLayout.CENTER);

        JPanel bot = new JPanel(new FlowLayout(FlowLayout.CENTER, 18, 0));
        bot.setOpaque(false); bot.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0));
        JButton backBtn = makeGlowButton("← Return", AMBER_DIM);
        backBtn.addActionListener(e -> {
            if (currentPlayerName.equals("Player") && sessionScore == 0) cardLayout.show(cardPanel, "START");
            else cardLayout.show(cardPanel, "MENU");
        });
        JButton clearBtn = makeGlowButton("✕  Clear Board", RED_DANGER);
        clearBtn.addActionListener(e -> {
            leaderboard.clear();
            if (db.isConnected()) db.clearLeaderboard();
            updateLeaderboardDisplay();
        });
        bot.add(backBtn); bot.add(clearBtn); root.add(bot, BorderLayout.SOUTH);
        return root;
    }

    private void updateLeaderboardDisplay() {
        leaderboardListPanel.removeAll();
        if (db.isConnected()) {
            leaderboard.clear();
            for (DatabaseManager.LeaderboardRow row : db.getTopScores(10))
                leaderboard.add(new LeaderboardEntry(row.name, row.score, row.stage));
        }
        leaderboard.sort((a, b) -> Integer.compare(b.score, a.score));
        if (leaderboard.isEmpty()) {
            JLabel e = new JLabel("[ No entries yet — play to get on the board ]", SwingConstants.CENTER);
            e.setFont(new Font("Monospaced", Font.ITALIC, 13)); e.setForeground(TEXT_MID);
            e.setAlignmentX(CENTER_ALIGNMENT);
            leaderboardListPanel.add(Box.createVerticalStrut(60)); leaderboardListPanel.add(e);
        } else {
            JPanel header = new JPanel(new GridLayout(1, 4, 10, 0)) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setColor(new Color(10, 14, 26)); g2.fillRect(0, 0, getWidth(), getHeight());
                    GradientPaint bottomLine = new GradientPaint(0, getHeight()-1,
                        new Color(AMBER.getRed(), AMBER.getGreen(), AMBER.getBlue(), 0),
                        getWidth()/2, getHeight()-1, AMBER, false);
                    GradientPaint bottomLine2 = new GradientPaint(getWidth()/2, getHeight()-1,
                        AMBER, getWidth(), getHeight()-1,
                        new Color(AMBER.getRed(), AMBER.getGreen(), AMBER.getBlue(), 0), false);
                    g2.setPaint(bottomLine); g2.fillRect(0, getHeight()-1, getWidth()/2, 1);
                    g2.setPaint(bottomLine2); g2.fillRect(getWidth()/2, getHeight()-1, getWidth()/2, 1);
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            header.setOpaque(false);
            header.setMaximumSize(new Dimension(640, 40));
            header.setBorder(BorderFactory.createEmptyBorder(8, 6, 8, 6));
            for (String h : new String[]{"Rank", "Player", "Score", "Stage"}) {
                JLabel lbl = new JLabel(h, SwingConstants.CENTER);
                lbl.setFont(new Font("Monospaced", Font.BOLD, 12));
                lbl.setForeground(new Color(180, 140, 40));
                header.add(lbl);
            }
            leaderboardListPanel.add(header); leaderboardListPanel.add(Box.createVerticalStrut(4));

            int rank = 1;
            for (LeaderboardEntry entry : leaderboard) {
                if (rank > 10) break;
                final int r = rank;
                JPanel row = new JPanel(new GridLayout(1, 4, 10, 0)) {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        Color rowBg = r % 2 == 0 ? new Color(8, 11, 22) : new Color(11, 14, 26);
                        g2.setColor(rowBg); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                        if (r <= 3) {
                            Color rankAccent = r == 1 ? AMBER : r == 2 ? new Color(175, 180, 195) : new Color(200, 120, 45);
                            g2.setColor(new Color(rankAccent.getRed(), rankAccent.getGreen(), rankAccent.getBlue(), 30));
                            g2.fillRoundRect(0, 0, 4, getHeight(), 4, 4);
                        }
                        g2.dispose(); super.paintComponent(g);
                    }
                };
                row.setOpaque(false);
                row.setMaximumSize(new Dimension(640, 38));
                row.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
                Color rc = rank == 1 ? AMBER : rank == 2 ? new Color(175, 180, 195)
                         : rank == 3 ? new Color(200, 120, 45) : TEXT_MID;
                String rankIcon = rank == 1 ? "🥇" : rank == 2 ? "🥈" : rank == 3 ? "🥉" : "#" + rank;
                for (String val : new String[]{rankIcon, entry.name, String.valueOf(entry.score), String.valueOf(entry.stage)}) {
                    JLabel lbl = new JLabel(val, SwingConstants.CENTER);
                    lbl.setFont(new Font("Monospaced", Font.BOLD, 12)); lbl.setForeground(rc);
                    row.add(lbl);
                }
                leaderboardListPanel.add(row); leaderboardListPanel.add(Box.createVerticalStrut(3));
                rank++;
            }
        }
        leaderboardListPanel.revalidate(); leaderboardListPanel.repaint();
    }

    // ── Game Screen ───────────────────────────────────────────────────────────
    private JPanel buildGameScreen() {
        JPanel root = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();

                // Rich layered background for game screen
                // Base dark void
                g2.setColor(new Color(12, 24, 48));
                g2.fillRect(0, 0, w, h);

                RadialGradientPaint bloom1 = new RadialGradientPaint(
                    new Point2D.Float(w * 0.08f, h * 0.88f), h * 0.65f,
                    new float[]{0f, 0.45f, 1f},
                    new Color[]{new Color(17, 100, 102, 100), new Color(10, 55, 60, 40), new Color(0, 0, 0, 0)});
                g2.setPaint(bloom1); g2.fillRect(0, 0, w, h);

                RadialGradientPaint bloom2 = new RadialGradientPaint(
                    new Point2D.Float(w * 0.92f, h * 0.1f), h * 0.55f,
                    new float[]{0f, 0.4f, 1f},
                    new Color[]{new Color(180, 140, 30, 80), new Color(120, 90, 10, 28), new Color(0, 0, 0, 0)});
                g2.setPaint(bloom2); g2.fillRect(0, 0, w, h);

                RadialGradientPaint bloom3 = new RadialGradientPaint(
                    new Point2D.Float(w * 0.5f, h * 0.22f), h * 0.4f,
                    new float[]{0f, 1f},
                    new Color[]{new Color(17, 100, 102, 60), new Color(0, 0, 0, 0)});
                g2.setPaint(bloom3); g2.fillRect(0, 0, w, h);

                // Finer hex grid at two scales
                g2.setStroke(new BasicStroke(0.4f));
                int hexR = 30;
                for (int row = -1; row < h / (hexR * 3 / 2) + 2; row++) {
                    for (int col = -1; col < w / (int)(hexR * Math.sqrt(3)) + 2; col++) {
                        int cx = col * (int)(hexR * Math.sqrt(3)) + (row % 2 == 1 ? (int)(hexR * Math.sqrt(3) / 2) : 0);
                        int cy = row * (hexR * 3 / 2);
                        int[] xs = new int[6], ys = new int[6];
                        for (int i = 0; i < 6; i++) {
                            double a = Math.PI / 180.0 * (60 * i - 30);
                            xs[i] = cx + (int)(hexR * Math.cos(a));
                            ys[i] = cy + (int)(hexR * Math.sin(a));
                        }
                        // Tint based on position
                        int tint = (row + col) % 2;
                        Color hexC = tint == 0 ? new Color(25, 45, 80, 9) : new Color(180, 110, 20, 5);
                        g2.setColor(hexC);
                        g2.drawPolygon(xs, ys, 6);
                    }
                }

                // Inner hex (smaller, offset)
                int hexR2 = 14;
                g2.setStroke(new BasicStroke(0.3f));
                for (int row = -1; row < h / (hexR2 * 3 / 2) + 2; row++) {
                    for (int col = -1; col < w / (int)(hexR2 * Math.sqrt(3)) + 2; col++) {
                        int cx = col * (int)(hexR2 * Math.sqrt(3)) + (row % 2 == 0 ? (int)(hexR2 * Math.sqrt(3) / 2) : 0) + 20;
                        int cy = row * (hexR2 * 3 / 2) + 12;
                        int[] xs = new int[6], ys = new int[6];
                        for (int i = 0; i < 6; i++) {
                            double a = Math.PI / 180.0 * (60 * i - 30);
                            xs[i] = cx + (int)(hexR2 * Math.cos(a));
                            ys[i] = cy + (int)(hexR2 * Math.sin(a));
                        }
                        g2.setColor(new Color(40, 80, 140, 6));
                        g2.drawPolygon(xs, ys, 6);
                    }
                }

                // Vignette
                RadialGradientPaint vignette = new RadialGradientPaint(
                    new Point2D.Float(w / 2f, h / 2f), Math.max(w, h) * 0.68f,
                    new float[]{0.35f, 1f},
                    new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 115)});
                g2.setPaint(vignette); g2.fillRect(0, 0, w, h);

                // Scanlines
                for (int y = 0; y < h; y += 3) {
                    g2.setColor(new Color(0, 0, 0, 7));
                    g2.drawLine(0, y, w, y);
                }

                g2.dispose();
                super.paintComponent(g);
            }
        };
        root.setOpaque(false);

        // ── Top bar ────────────────────────────────────────────────────────
        JPanel topBar = new JPanel(new BorderLayout()) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(4, 6, 15, 235)); g2.fillRect(0, 0, getWidth(), getHeight());
                GradientPaint sep = new GradientPaint(0, getHeight()-1,
                    new Color(AMBER_DIM.getRed(), AMBER_DIM.getGreen(), AMBER_DIM.getBlue(), 0),
                    getWidth()/2, getHeight()-1, AMBER_DIM, false);
                GradientPaint sep2 = new GradientPaint(getWidth()/2, getHeight()-1,
                    AMBER_DIM, getWidth(), getHeight()-1,
                    new Color(AMBER_DIM.getRed(), AMBER_DIM.getGreen(), AMBER_DIM.getBlue(), 0), false);
                g2.setStroke(new BasicStroke(1f));
                g2.setPaint(sep);  g2.drawLine(0, getHeight()-1, getWidth()/2, getHeight()-1);
                g2.setPaint(sep2); g2.drawLine(getWidth()/2, getHeight()-1, getWidth(), getHeight()-1);
                g2.dispose();
            }
        };
        topBar.setOpaque(false);
        topBar.setBorder(BorderFactory.createEmptyBorder(10, 20, 10, 20));

        JButton backBtn = makeGlowButton("← Skill Tree", AMBER_DIM);
        backBtn.addActionListener(e -> {
            sessionScoreLabel.setText("Session Score:  " + sessionScore + "   ·   Stage " + currentStage);
            updateLevelSelectScreen(); cardLayout.show(cardPanel, "LEVELSELECT");
        });
        topBar.add(backBtn, BorderLayout.WEST);

        JPanel titleGroup = makeTransparentPanel();
        titleGroup.setLayout(new BoxLayout(titleGroup, BoxLayout.Y_AXIS));
        JLabel gTitle = new JLabel("⬡ LexiGuess", SwingConstants.CENTER);
        gTitle.setFont(new Font("Serif", Font.BOLD | Font.ITALIC, 22)); gTitle.setForeground(AMBER);
        gTitle.setAlignmentX(CENTER_ALIGNMENT);
        difficultyLabel = new JLabel("", SwingConstants.CENTER);
        difficultyLabel.setFont(new Font("Monospaced", Font.PLAIN, 10));
        difficultyLabel.setForeground(new Color(120, 100, 40)); difficultyLabel.setAlignmentX(CENTER_ALIGNMENT);
        titleGroup.add(gTitle); titleGroup.add(difficultyLabel);
        topBar.add(titleGroup, BorderLayout.CENTER);

        JPanel scorePanel = makeTransparentPanel();
        scorePanel.setLayout(new BoxLayout(scorePanel, BoxLayout.Y_AXIS));
        inGameScoreLabel = new JLabel("Score: 0", SwingConstants.RIGHT);
        inGameScoreLabel.setFont(new Font("Monospaced", Font.BOLD, 15)); inGameScoreLabel.setForeground(AMBER);
        inGameScoreLabel.setAlignmentX(RIGHT_ALIGNMENT);
        stageLabel = new JLabel("Stage 1", SwingConstants.RIGHT);
        stageLabel.setFont(new Font("Monospaced", Font.PLAIN, 10)); stageLabel.setForeground(AMBER_DIM);
        stageLabel.setAlignmentX(RIGHT_ALIGNMENT);
        scorePanel.add(inGameScoreLabel); scorePanel.add(stageLabel);

        // ── Gear / Settings button ─────────────────────────────────────────
        JButton gearBtn = new JButton("SETTINGS") {
            private boolean hover = false;
            {
                addMouseListener(new MouseAdapter() {
                    public void mouseEntered(MouseEvent e) { hover = true;  repaint(); }
                    public void mouseExited (MouseEvent e) { hover = false; repaint(); }
                });
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(hover ? new Color(255, 185, 40, 45) : new Color(255, 185, 40, 18));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(new Color(255, 185, 40, hover ? 150 : 70));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        gearBtn.setFont(new Font("Monospaced", Font.BOLD, 11));
        gearBtn.setForeground(AMBER);
        gearBtn.setContentAreaFilled(false); gearBtn.setBorderPainted(false);
        gearBtn.setFocusPainted(false);      gearBtn.setOpaque(false);
        gearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        gearBtn.setPreferredSize(new Dimension(96, 32));
        gearBtn.setToolTipText("Settings");
        gearBtn.addActionListener(e -> {
            soundManager.playClick();
            showSettingsDialog();
        });

        // Pack score + gear into the EAST slot
        JPanel eastPanel = makeTransparentPanel();
        eastPanel.setLayout(new BorderLayout(8, 0));
        eastPanel.add(scorePanel, BorderLayout.CENTER);
        eastPanel.add(gearBtn,   BorderLayout.EAST);
        topBar.add(eastPanel, BorderLayout.EAST);
        root.add(topBar, BorderLayout.NORTH);

        // ── Center: hangman + grid ─────────────────────────────────────────
        JPanel center = makeTransparentPanel();
        center.setLayout(new GridBagLayout());
        center.setBorder(BorderFactory.createEmptyBorder(14, 20, 10, 20));

        hangmanPanel = new HangmanPanel();
        hangmanPanel.setPreferredSize(new Dimension(220, 300));
        hangmanPanel.setMinimumSize(new Dimension(220, 300));
        hangmanPanel.setMaximumSize(new Dimension(220, 300));

        GridBagConstraints gbcH = new GridBagConstraints();
        gbcH.gridx = 0; gbcH.gridy = 0;
        gbcH.anchor = GridBagConstraints.NORTHWEST;
        gbcH.fill = GridBagConstraints.NONE;
        gbcH.weightx = 0; gbcH.weighty = 1.0;
        gbcH.insets = new Insets(0, 18, 0, 18);
        center.add(hangmanPanel, gbcH);

        gridPanel = makeTransparentPanel();
        GridBagConstraints gbcG = new GridBagConstraints();
        gbcG.gridx = 1; gbcG.gridy = 0;
        gbcG.anchor = GridBagConstraints.NORTH;
        gbcG.fill = GridBagConstraints.NONE;
        gbcG.weightx = 1.0; gbcG.weighty = 1.0;
        gbcG.insets = new Insets(0, 18, 0, 18);
        center.add(gridPanel, gbcG);
        root.add(center, BorderLayout.CENTER);

        // ── Bottom panel ───────────────────────────────────────────────────
        JPanel bottom = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(new Color(3, 5, 13, 230)); g2.fillRect(0, 0, getWidth(), getHeight());
                GradientPaint sep = new GradientPaint(0, 0,
                    new Color(AMBER_DIM.getRed(), AMBER_DIM.getGreen(), AMBER_DIM.getBlue(), 0),
                    getWidth()/2, 0, new Color(AMBER_DIM.getRed(), AMBER_DIM.getGreen(), AMBER_DIM.getBlue(), 55), false);
                GradientPaint sep2 = new GradientPaint(getWidth()/2, 0,
                    new Color(AMBER_DIM.getRed(), AMBER_DIM.getGreen(), AMBER_DIM.getBlue(), 55),
                    getWidth(), 0, new Color(AMBER_DIM.getRed(), AMBER_DIM.getGreen(), AMBER_DIM.getBlue(), 0), false);
                g2.setPaint(sep);  g2.drawLine(0, 0, getWidth()/2, 0);
                g2.setPaint(sep2); g2.drawLine(getWidth()/2, 0, getWidth(), 0);
                g2.dispose();
            }
        };
        bottom.setOpaque(false);
        bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS));
        bottom.setBorder(BorderFactory.createEmptyBorder(10, 22, 14, 22));

        JPanel clueBox = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0)) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(8, 14, 30, 160));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.setColor(new Color(CLUE_CLR.getRed(), CLUE_CLR.getGreen(), CLUE_CLR.getBlue(), 40));
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 8, 8);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        clueBox.setOpaque(false);
        clueBox.setMaximumSize(new Dimension(700, 36)); clueBox.setAlignmentX(CENTER_ALIGNMENT);
        clueLabel = new JLabel(" ", SwingConstants.CENTER);
        clueLabel.setFont(new Font("Monospaced", Font.ITALIC, 12)); clueLabel.setForeground(CLUE_CLR);
        clueLabel.setBorder(BorderFactory.createEmptyBorder(6, 16, 6, 16));
        clueBox.add(clueLabel);
        bottom.add(clueBox); bottom.add(Box.createVerticalStrut(5));

        messageLabel = new JLabel(" ", SwingConstants.CENTER);
        messageLabel.setFont(new Font("Monospaced", Font.PLAIN, 12)); messageLabel.setForeground(TEXT_BRIGHT);
        messageLabel.setAlignmentX(CENTER_ALIGNMENT);
        bottom.add(messageLabel); bottom.add(Box.createVerticalStrut(8));

        JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        inputRow.setOpaque(false);

        inputField = new JTextField(10) {
            private boolean focused = false;
            { addFocusListener(new FocusAdapter() {
                public void focusGained(FocusEvent e) { focused = true;  repaint(); }
                public void focusLost (FocusEvent e) { focused = false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(new Color(8, 12, 25)); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
                GradientPaint inner = new GradientPaint(0, 0, new Color(16, 22, 46), 0, getHeight(), new Color(6, 10, 22));
                g2.setPaint(inner); g2.fillRoundRect(1, 1, getWidth()-2, getHeight()-2, 9, 9);
                Color borderColor = focused
                    ? new Color(AMBER.getRed(), AMBER.getGreen(), AMBER.getBlue(), 200)
                    : new Color(AMBER_DIM.getRed(), AMBER_DIM.getGreen(), AMBER_DIM.getBlue(), 90);
                if (focused) {
                    g2.setColor(new Color(AMBER.getRed(), AMBER.getGreen(), AMBER.getBlue(), 20));
                    g2.setStroke(new BasicStroke(5f));
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                }
                g2.setColor(borderColor);
                g2.setStroke(new BasicStroke(1.2f)); g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 10, 10);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        inputField.setFont(new Font("Monospaced", Font.BOLD, 20));
        inputField.setForeground(TEXT_BRIGHT); inputField.setOpaque(false);
        inputField.setBackground(new Color(0,0,0,0)); inputField.setCaretColor(AMBER);
        inputField.setHorizontalAlignment(SwingConstants.CENTER);
        inputField.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        inputField.setPreferredSize(new Dimension(220, 46));
        inputField.addKeyListener(new KeyAdapter() {
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                if (!Character.isLetter(c)) { e.consume(); return; }
                if (inputField.getText().length() >= wordLength) e.consume();
                else { e.setKeyChar(Character.toUpperCase(c)); playSound(SOUND_KEYCLICK); }
            }
            public void keyPressed(KeyEvent e) { if (e.getKeyCode() == KeyEvent.VK_ENTER) submitGuess(); }
        });

        submitBtn = makeGlowButton("GUESS  →", EASY_CLR);
        submitBtn.setFont(new Font("Monospaced", Font.BOLD, 12));
        submitBtn.setPreferredSize(new Dimension(115, 46));
        submitBtn.addActionListener(e -> submitGuess());

        hintBtn = new JButton() {
            private boolean hover = false;
            { addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) { hover = true;  repaint(); }
                public void mouseExited (MouseEvent e) { hover = false; repaint(); }
            }); }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int alpha = hover ? 36 : 18;
                g2.setColor(new Color(CYAN.getRed(), CYAN.getGreen(), CYAN.getBlue(), alpha));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                if (hover) {
                    g2.setColor(new Color(CYAN.getRed(), CYAN.getGreen(), CYAN.getBlue(), 25));
                    g2.setStroke(new BasicStroke(4f));
                    g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 8, 8);
                }
                g2.setColor(new Color(CYAN.getRed(), CYAN.getGreen(), CYAN.getBlue(), hover ? 160 : 90));
                g2.setStroke(new BasicStroke(1.2f));
                g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 8, 8);
                super.paintComponent(g2);
                g2.dispose();
            }
        };
        hintBtn.setText("<html><center><span style='font-size:11px;'>💡 Hint</span><br><span style='font-size:8px;color:#29c8ff;'>(" + MAX_HINTS + " left)</span></center></html>");
        hintBtn.setFont(new Font("Monospaced", Font.BOLD, 11));
        hintBtn.setForeground(CYAN);
        hintBtn.setContentAreaFilled(false); hintBtn.setBorderPainted(false);
        hintBtn.setFocusPainted(false); hintBtn.setOpaque(false);
        hintBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        hintBtn.setPreferredSize(new Dimension(100, 46));
        hintBtn.addActionListener(e -> useHint(hintBtn));

        inputRow.add(inputField); inputRow.add(submitBtn); inputRow.add(hintBtn);
        bottom.add(inputRow); bottom.add(Box.createVerticalStrut(10));

        keyboardPanel = makeTransparentPanel();
        buildKeyboard();
        bottom.add(keyboardPanel);
        root.add(bottom, BorderLayout.SOUTH);
        return root;
    }

    // ── Keyboard ──────────────────────────────────────────────────────────────
    private void buildKeyboard() {
        keyboardPanel.removeAll();
        keyboardPanel.setLayout(new BoxLayout(keyboardPanel, BoxLayout.Y_AXIS));
        keyLabels.clear();
        for (String row : new String[]{"QWERTYUIOP","ASDFGHJKL","ZXCVBNM"}) {
            JPanel rowP = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 2));
            rowP.setOpaque(false);
            for (char ch : row.toCharArray()) {
                JLabel k = new JLabel(String.valueOf(ch), SwingConstants.CENTER) {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        Color bg = getBackground();
                        GradientPaint keyGrad = new GradientPaint(0, 0,
                            new Color(Math.min(255, bg.getRed()+6), Math.min(255, bg.getGreen()+6), Math.min(255, bg.getBlue()+6)),
                            0, getHeight(), bg);
                        g2.setPaint(keyGrad); g2.fillRoundRect(0, 0, getWidth(), getHeight(), 5, 5);
                        g2.setColor(new Color(45, 50, 75, 150)); g2.setStroke(new BasicStroke(0.7f));
                        g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 5, 5);
                        g2.dispose(); super.paintComponent(g);
                    }
                };
                k.setFont(new Font("Monospaced", Font.BOLD, 11));
                k.setForeground(TEXT_BRIGHT); k.setBackground(new Color(16, 20, 36));
                k.setOpaque(false); k.setPreferredSize(new Dimension(30, 28));
                keyLabels.put(ch, k); rowP.add(k);
            }
            keyboardPanel.add(rowP);
        }
    }

    private void updateKeyboard() {
        for (Map.Entry<Character, JLabel> e : keyLabels.entrySet()) {
            char ch = e.getKey(); JLabel lbl = e.getValue();
            if      (correctLetters.contains(ch)) { lbl.setBackground(new Color(30, 155, 85));  lbl.setForeground(new Color(210, 255, 225)); }
            else if (presentLetters.contains(ch)) { lbl.setBackground(new Color(170, 125, 15)); lbl.setForeground(new Color(255, 235, 180)); }
            else if (absentLetters.contains(ch))  { lbl.setBackground(new Color(22, 22, 34));   lbl.setForeground(TEXT_DIM); }
        }
        keyboardPanel.repaint();
    }

    // ── Game Logic ────────────────────────────────────────────────────────────
    private void startGame() {
        List<WordEntry> bank = difficulty.equals("EASY") ? easyWords
                             : difficulty.equals("MEDIUM") ? mediumWords : hardWords;
        if (bank.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No words for difficulty: " + difficulty, "Empty", JOptionPane.WARNING_MESSAGE);
            return;
        }
        totalLevels = bank.size();
        if (currentLevel < 1) currentLevel = 1;
        if (currentLevel > totalLevels) currentLevel = totalLevels;
        currentEntry = bank.get(currentLevel - 1);
        targetWord = currentEntry.word; currentClue = currentEntry.clue;
        wordLength = targetWord.length();

        guesses.clear(); feedbackList.clear();
        correctLetters.clear(); presentLetters.clear(); absentLetters.clear();
        currentAttempt = 0; gameOver = false; gameWon = false;
        hintsUsed = 0;
        hintBtn.setText("<html><center><span style='font-size:11px;'>💡 Hint</span><br><span style='font-size:8px;color:#29c8ff;'>(" + MAX_HINTS + " left)</span></center></html>");
        hintBtn.setEnabled(true);
        hintBtn.setForeground(CYAN);
        hintBtn.repaint();

        buildGrid(); buildKeyboard();
        hangmanPanel.resetGame();
        hangmanPanel.repaint();

        Color dc = difficulty.equals("EASY") ? EASY_CLR : difficulty.equals("MEDIUM") ? MEDIUM_CLR : HARD_CLR;
        difficultyLabel.setForeground(dc);
        difficultyLabel.setText("[ " + difficulty + " ]   Level " + currentLevel + " / " + totalLevels + "   " + wordLength + "-letter word");
        clueLabel.setText("Definition: " + currentClue);
        messageLabel.setText("Decipher the hidden programming word...");
        messageLabel.setForeground(TEXT_MID);
        inGameScoreLabel.setText("Score: " + sessionScore);
        stageLabel.setText("Stage " + currentStage + "  ·  Lv." + currentLevel + "/" + totalLevels);
        inputField.setText(""); inputField.setEnabled(true); submitBtn.setEnabled(true);
        cardLayout.show(cardPanel, "GAME");
        inputField.requestFocus();
    }

    private void useHint(JButton hintBtn) {
        if (gameOver || gameWon) return;
        if (hintsUsed >= MAX_HINTS) {
            messageLabel.setText("// No hints remaining for this round.");
            messageLabel.setForeground(RED_DANGER); return;
        }
        int deduction = difficulty.equals("EASY") ? 20 : difficulty.equals("MEDIUM") ? 40 : 60;
        if (sessionScore < deduction) {
            messageLabel.setText("// Not enough score for a hint  ( " + deduction + " pts needed )");
            messageLabel.setForeground(RED_DANGER); return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
            "Reveal a random letter for  -" + deduction + " pts?\nHints remaining: " + (MAX_HINTS - hintsUsed),
            "Use Hint?", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        List<Integer> unrevealed = new ArrayList<>();
        for (int c = 0; c < wordLength; c++) {
            boolean alreadyGreen = false;
            for (int[] fb : feedbackList) { if (fb[c] == 2) { alreadyGreen = true; break; } }
            if (!alreadyGreen) {
                JLabel tile = tiles[currentAttempt][c];
                boolean alreadyHinted = tile.getText().length() > 0 && tile.getBackground().equals(CYAN_DIM);
                if (!alreadyHinted) unrevealed.add(c);
            }
        }
        if (unrevealed.isEmpty()) {
            messageLabel.setText("// All positions already revealed!"); messageLabel.setForeground(CYAN); return;
        }

        int pos = unrevealed.get((int)(Math.random() * unrevealed.size()));
        char revealedChar = targetWord.charAt(pos);
        JLabel hintTile = tiles[currentAttempt][pos];
        hintTile.setText(String.valueOf(revealedChar));
        hintTile.setBackground(CYAN_DIM); hintTile.setForeground(CYAN); hintTile.repaint();

        sessionScore -= deduction; hintsUsed++;
        int hintsLeft = MAX_HINTS - hintsUsed;

        messageLabel.setText("// HINT: Position " + (pos+1) + " is '" + revealedChar + "'  (-" + deduction + " pts)  ·  " + hintsLeft + " hints left");
        messageLabel.setForeground(CYAN);
        inGameScoreLabel.setText("Score: " + sessionScore);
        sessionScoreLabel.setText("Session Score:  " + sessionScore + "   ·   Stage " + currentStage);

        hintBtn.setText("<html><center><span style='font-size:11px;'>💡 Hint</span><br><span style='font-size:8px;color:#29c8ff;'>(" + hintsLeft + " left)</span></center></html>");
        if (hintsLeft == 0) {
            hintBtn.setEnabled(false); hintBtn.setForeground(TEXT_DIM);
        }
        hintBtn.repaint();
    }

    private void buildGrid() {
        gridPanel.removeAll();
        int tileSize = wordLength <= 5 ? 60 : wordLength == 6 ? 54 : wordLength <= 8 ? 48 : 42;
        gridPanel.setLayout(new GridLayout(maxAttempts, wordLength, 5, 5));
        tiles = new JLabel[maxAttempts][wordLength];
        for (int r = 0; r < maxAttempts; r++) {
            for (int c = 0; c < wordLength; c++) {
                JLabel tile = new JLabel("", SwingConstants.CENTER) {
                    @Override protected void paintComponent(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                        Color bg = getBackground();
                        boolean isEmpty = bg.equals(TILE_EMPTY_BG);
                        if (!isEmpty) {
                            GradientPaint tileBg = new GradientPaint(0, 0,
                                new Color(Math.min(255, bg.getRed()+15), Math.min(255, bg.getGreen()+15), Math.min(255, bg.getBlue()+15)),
                                0, getHeight(), bg);
                            g2.setPaint(tileBg);
                        } else {
                            g2.setColor(bg);
                        }
                        g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                        Color borderColor = isEmpty
                            ? TILE_EMPTY_BD
                            : new Color(Math.min(255, bg.getRed()+50), Math.min(255, bg.getGreen()+50), Math.min(255, bg.getBlue()+50), 130);
                        g2.setColor(borderColor);
                        g2.setStroke(new BasicStroke(1.2f));
                        g2.drawRoundRect(0, 0, getWidth()-1, getHeight()-1, 8, 8);
                        if (!isEmpty) {
                            g2.setColor(new Color(255, 255, 255, 18));
                            g2.fillRoundRect(2, 2, getWidth()-4, getHeight()/2-2, 6, 6);
                        }
                        g2.dispose();
                        super.paintComponent(g);
                    }
                };
                tile.setFont(new Font("Monospaced", Font.BOLD, tileSize - 22));
                tile.setForeground(TEXT_BRIGHT); tile.setBackground(TILE_EMPTY_BG);
                tile.setOpaque(false); tile.setBorder(BorderFactory.createEmptyBorder());
                tile.setPreferredSize(new Dimension(tileSize, tileSize));
                tiles[r][c] = tile; gridPanel.add(tile);
            }
        }
        gridPanel.revalidate(); gridPanel.repaint();
    }

    private void submitGuess() {
        if (gameOver || gameWon) return;
        String guess = inputField.getText().trim().toUpperCase();
        if (guess.length() != wordLength) {
            messageLabel.setText("// ERROR: word must be exactly " + wordLength + " letters");
            messageLabel.setForeground(MEDIUM_CLR); return;
        }
        int[] fb = computeFeedback(guess, targetWord);
        guesses.add(guess); feedbackList.add(fb);

        for (int c = 0; c < wordLength; c++) {
            JLabel tile = tiles[currentAttempt][c];
            tile.setText(String.valueOf(guess.charAt(c))); tile.setBorder(BorderFactory.createEmptyBorder());
            if      (fb[c] == 2) { tile.setBackground(GREEN_CORRECT);  correctLetters.add(guess.charAt(c)); }
            else if (fb[c] == 1) { tile.setBackground(YELLOW_PRESENT); presentLetters.add(guess.charAt(c)); }
            else                 { tile.setBackground(GRAY_ABSENT);     absentLetters.add(guess.charAt(c)); }
            tile.repaint();
        }
        currentAttempt++;
        updateKeyboard();

        boolean allCorrect = true;
        for (int f : fb) if (f != 2) { allCorrect = false; break; }
        if (!allCorrect) hangmanPanel.addWrongGuess();
        hangmanPanel.repaint();

        if (allCorrect) {
            gameWon = true; playSound(SOUND_CORRECT);
            int remaining = maxAttempts - currentAttempt;
            int base  = difficulty.equals("EASY") ? 100 : difficulty.equals("MEDIUM") ? 200 : 300;
            int bonus = difficulty.equals("EASY") ?  10 : difficulty.equals("MEDIUM") ?  20 :  30;
            int roundScore = base + remaining * bonus;
            sessionScore += roundScore; currentStage++;
            if (db.isConnected()) db.saveSessionScore(currentPlayerName, sessionScore, currentStage);

            int nextLevel = currentLevel + 1;
            int prevHighest = difficultyProgress.getOrDefault(difficulty, 1);
            if (nextLevel > prevHighest && nextLevel <= totalLevels) difficultyProgress.put(difficulty, nextLevel);
            if (db.isConnected()) db.saveProgress(currentPlayerName, difficulty, difficultyProgress.get(difficulty));
            if (currentLevel == totalLevels) difficultyProgress.put(difficulty, totalLevels + 1);
            currentLevel = nextLevel;

            messageLabel.setText("COMPILE SUCCESS  ·  +" + roundScore + " pts");
            messageLabel.setForeground(AMBER);
            inGameScoreLabel.setText("Score: " + sessionScore);
            stageLabel.setText("Stage " + currentStage);
            sessionScoreLabel.setText("Session Score:  " + sessionScore + "   ·   Stage " + currentStage);
            inputField.setEnabled(false); submitBtn.setEnabled(false);
            addToLeaderboard(currentPlayerName, sessionScore, currentStage);
            SwingUtilities.invokeLater(() -> showEndGameDialog(
                "✦ COMPILE SUCCESS ✦", "You cracked the word:", targetWord,
                "+" + roundScore + " pts  ·  Total: " + sessionScore, GREEN_CORRECT, "Next Level →"));

        } else if (currentAttempt >= maxAttempts) {
            gameOver = true; playSound(SOUND_LOSE);
            messageLabel.setText("GAME OVER — Better luck next time  —  Score reset");
            messageLabel.setForeground(RED_DANGER);
            if (sessionScore > 0) addToLeaderboard(currentPlayerName, sessionScore, currentStage);
            sessionScore = 0;
            if (db.isConnected()) db.saveSessionScore(currentPlayerName, 0, currentStage);
            inGameScoreLabel.setText("Score: 0");
            sessionScoreLabel.setText("Session Score:  0   ·   Stage " + currentStage);
            inputField.setEnabled(false); submitBtn.setEnabled(false);
            SwingUtilities.invokeLater(() -> showEndGameDialog(
                "GAME OVER", "", "",
                "Your score has been reset.", RED_DANGER, "Retry"));
        } else {
            int left = maxAttempts - currentAttempt;
            messageLabel.setText("// Attempt " + currentAttempt + " / " + maxAttempts + "  ·  " + left + " remaining");
            messageLabel.setForeground(TEXT_MID);
        }
        inputField.setText(""); gridPanel.repaint();
    }

    private void addToLeaderboard(String name, int sc, int stage) {
        boolean found = false;
        for (LeaderboardEntry e : leaderboard) {
            if (e.name.equals(name)) {
                if (sc > e.score) { e.score = sc; e.stage = stage; }
                found = true; break;
            }
        }
        if (!found) leaderboard.add(new LeaderboardEntry(name, sc, stage));
        if (db.isConnected()) db.saveScore(name, sc, stage);
        if (db.isConnected()) db.saveSessionScore(currentPlayerName, sessionScore, currentStage);
    }

    private int[] computeFeedback(String guess, String target) {
        int len = target.length();
        int[] result = new int[len];
        boolean[] tUsed = new boolean[len], gUsed = new boolean[len];
        for (int i = 0; i < len; i++)
            if (guess.charAt(i) == target.charAt(i)) { result[i] = 2; tUsed[i] = gUsed[i] = true; }
        for (int i = 0; i < len; i++) {
            if (gUsed[i]) continue;
            for (int j = 0; j < len; j++) {
                if (tUsed[j]) continue;
                if (guess.charAt(i) == target.charAt(j)) { result[i] = 1; tUsed[j] = true; break; }
            }
        }
        return result;
    }

    // ── Leaderboard Entry ─────────────────────────────────────────────────────
    static class LeaderboardEntry {
        String name; int score, stage;
        LeaderboardEntry(String n, int s, int st) { name=n; score=s; stage=st; }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  HANGMAN PANEL
    // ══════════════════════════════════════════════════════════════════════════
    class HangmanPanel extends JPanel {

        private static final String HANGMAN_BG_IMAGE = "brickwall.jpg";

        private static final int C_WOOD_D  = 0xFF4a3018;
        private static final int C_WOOD    = 0xFF6b4c2a;
        private static final int C_ROPE    = 0xFFc8a060;
        private static final int C_SKIN    = 0xFFe8c89a;
        private static final int C_SKIN_S  = 0xFFc8a070;
        private static final int C_SHIRT   = 0xFF3a4a6a;
        private static final int C_SHIRT_S = 0xFF2a3a5a;
        private static final int C_PANTS   = 0xFF2a2a3a;
        private static final int C_PANTS_S = 0xFF1a1a2a;
        private static final int C_SHOE    = 0xFF1a1210;
        private static final int C_HAIR    = 0xFF2a1a08;

        private static final String[] PART_ORDER = {"lLeg","rLeg","lArm","rArm","torso","head"};
        private static final int MAX_WRONG = 6;

        private final double[] px     = new double[MAX_WRONG];
        private final double[] py     = new double[MAX_WRONG];
        private final double[] pvx    = new double[MAX_WRONG];
        private final double[] pvy    = new double[MAX_WRONG];
        private final double[] pAngle = new double[MAX_WRONG];
        private final double[] pVa    = new double[MAX_WRONG];
        private final boolean[] attached = new boolean[MAX_WRONG];
        private final boolean[] landed   = new boolean[MAX_WRONG];

        private int    wrongCount = 0;
        private double swingT     = 0.0;
        private Timer  physicsTimer;
        private Image  bgImage    = null;

        private static final double GRAVITY  = 0.55;
        private static final double BOUNCE   = 0.28;
        private static final double FRICTION = 0.84;
        private static final double ANG_DAMP = 0.78;

        HangmanPanel() {
            setOpaque(true);
            setBackground(BG_SURFACE);
            setBorder(new GlowBorder(AMBER_DIM, 1, 10));
            resetPieces();
            loadBgImage();
            new Timer(40, e -> { swingT += 0.04; repaint(); }).start();
        }

        private void loadBgImage() {
            if (HANGMAN_BG_IMAGE == null || HANGMAN_BG_IMAGE.isEmpty()) return;
            File f = new File(HANGMAN_BG_IMAGE);
            if (f.exists()) { bgImage = new ImageIcon(HANGMAN_BG_IMAGE).getImage(); return; }
            java.net.URL url = getClass().getResource("/" + HANGMAN_BG_IMAGE);
            if (url != null) bgImage = new ImageIcon(url).getImage();
        }

        private int cx()       { return getWidth() / 2 + 10; }
        private int groundY()  { return getHeight() - 22; }
        private int ropeEndY() { return 55; }
        private int headR()    { return 18; }
        private int headCY()   { return ropeEndY() + headR(); }
        private int shoulderY(){ return headCY() + headR() + 4; }
        private int hipY()     { return shoulderY() + 54; }
        private int armL()     { return 34; }
        private int legL()     { return 50; }

        private void resetPieces() {
            int cx = getWidth() > 0 ? cx() : 120;
            for (int i = 0; i < MAX_WRONG; i++) {
                String name = PART_ORDER[i];
                double ox = cx, oy = 0;
                switch (name) {
                    case "head":  oy = headCY();                        break;
                    case "torso": oy = shoulderY() + 27;                break;
                    case "lArm":  ox = cx - 20; oy = shoulderY() + 17; break;
                    case "rArm":  ox = cx + 20; oy = shoulderY() + 17; break;
                    case "lLeg":  ox = cx - 20; oy = hipY() + 25;      break;
                    case "rLeg":  ox = cx + 20; oy = hipY() + 25;      break;
                }
                px[i] = ox; py[i] = oy;
                pvx[i] = 0; pvy[i] = 0; pAngle[i] = 0; pVa[i] = 0;
                attached[i] = true; landed[i] = false;
            }
        }

        void resetGame() {
            wrongCount = 0;
            if (physicsTimer != null) physicsTimer.stop();
            resetPieces(); repaint();
        }

        void addWrongGuess() {
            if (wrongCount >= MAX_WRONG) return;
            detachPiece(wrongCount); wrongCount++; startPhysics();
        }

        private void detachPiece(int idx) {
            attached[idx] = false; landed[idx] = false;
            pvx[idx] = (Math.random() - 0.5) * 6.0;
            pvy[idx] = -3.0 - Math.random() * 4.0;
            pVa[idx] = (Math.random() - 0.5) * 0.25;
        }

        private void startPhysics() {
            if (physicsTimer != null) physicsTimer.stop();
            physicsTimer = new Timer(16, e -> {
                boolean anyActive = false;
                int floor = groundY() - 12;
                for (int i = 0; i < MAX_WRONG; i++) {
                    if (attached[i] || landed[i]) continue;
                    anyActive = true;
                    pvy[i] += GRAVITY; px[i] += pvx[i]; py[i] += pvy[i]; pAngle[i] += pVa[i];
                    if (py[i] >= floor) {
                        py[i] = floor;
                        pvy[i] = -Math.abs(pvy[i]) * BOUNCE;
                        pvx[i] *= FRICTION; pVa[i] *= ANG_DAMP;
                        if (Math.abs(pvy[i]) < 0.4 && Math.abs(pvx[i]) < 0.12) {
                            pvy[i] = 0; pvx[i] = 0; pVa[i] = 0; landed[i] = true;
                        }
                    }
                    if (px[i] < 12)            { px[i] = 12;            pvx[i] =  Math.abs(pvx[i]) * 0.5; }
                    if (px[i] > getWidth()-12) { px[i] = getWidth()-12; pvx[i] = -Math.abs(pvx[i]) * 0.5; }
                }
                repaint();
                if (!anyActive) ((Timer) e.getSource()).stop();
            });
            physicsTimer.start();
        }

        private double getSwing() {
            if (wrongCount == 0 || !attached[MAX_WRONG - 1]) return 0;
            return Math.sin(swingT * 1.4) * 3.0;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            int w = getWidth(), h = getHeight();
            drawBackground(g2, w, h);
            drawShadow(g2);
            drawGallows(g2);
            for (int i = 0; i < MAX_WRONG; i++) if (!attached[i]) drawDetached(g2, i);
            drawAttachedFigure(g2);
            g2.dispose();
        }

        private void drawBackground(Graphics2D g2, int w, int h) {
            if (bgImage != null) {
                int iw = bgImage.getWidth(this), ih = bgImage.getHeight(this);
                if (iw > 0 && ih > 0) {
                    double sc = Math.max((double) w / iw, (double) h / ih);
                    int dw = (int)(iw * sc), dh = (int)(ih * sc);
                    g2.drawImage(bgImage, (w - dw)/2, (h - dh)/2, dw, dh, this);
                    g2.setColor(new Color(0, 0, 0, 130)); g2.fillRect(0, 0, w, h);
                    return;
                }
            }
            GradientPaint bgGrad = new GradientPaint(0, 0, new Color(15, 33, 65), 0, h, new Color(10, 24, 48));
            g2.setPaint(bgGrad); g2.fillRect(0, 0, w, h);
            g2.setColor(new Color(200, 170, 90, 7));
            g2.setStroke(new BasicStroke(0.5f));
            for (int x = 0; x < w; x += 28) g2.drawLine(x, 0, x, h);
            for (int y = 0; y < h; y += 28) g2.drawLine(0, y, w, y);
            RadialGradientPaint vignette = new RadialGradientPaint(
                new Point2D.Float(w/2f, h/2f), Math.max(w, h) * 0.6f,
                new float[]{0f, 1f}, new Color[]{new Color(0,0,0,0), new Color(0,0,0,80)});
            g2.setPaint(vignette); g2.fillRect(0, 0, w, h);
        }

        private void drawShadow(Graphics2D g2) {
            if (wrongCount == 0) return;
            double sw = getSwing();
            int cx = cx(), gy = groundY();
            g2.setColor(new Color(0, 0, 0, 45));
            g2.fillOval((int)(cx + sw * 2 - 36 + Math.abs(sw)), gy - 9,
                         (int)(72 - Math.abs(sw) * 2), 11);
        }

        private void drawGallows(Graphics2D g2) {
            int cx = cx(), gy = groundY(), w = getWidth();
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g2.setColor(new Color(C_WOOD_D));
            g2.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(40, gy, w - 40, gy); g2.drawLine(80, gy, 80, 30); g2.drawLine(80, 30, cx, 30);
            g2.setColor(new Color(C_WOOD));
            g2.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(40, gy, w - 40, gy); g2.drawLine(80, gy, 80, 30); g2.drawLine(80, 30, cx, 30);
            g2.setColor(new Color(0x5a3c1a)); g2.setStroke(new BasicStroke(2f));
            for (int y = 30; y < gy; y += 18) g2.drawLine(76, y, 84, y + 6);
            g2.setColor(new Color(0x8a6030)); g2.setStroke(new BasicStroke(3f));
            g2.drawLine(80, gy - 2, 130, gy - 8); g2.drawLine(80, gy, 125, gy + 8);
            if (attached[5]) {
                g2.setColor(new Color(0xa08040)); g2.setStroke(new BasicStroke(4f));
                g2.drawLine(cx, 30, cx, ropeEndY());
                g2.setColor(new Color(C_ROPE)); g2.setStroke(new BasicStroke(2f));
                g2.drawLine(cx - 2, 30, cx - 2, ropeEndY());
                g2.setColor(new Color(0xa08040)); g2.setStroke(new BasicStroke(1.5f));
                for (int i = 0; i < 4; i++) g2.drawLine(cx - 3, 30 + i * 7, cx + 5, 30 + i * 7 + 4);
            }
        }

        private void drawAttachedFigure(Graphics2D g2) {
            boolean anyAttached = false;
            for (boolean a : attached) if (a) { anyAttached = true; break; }
            if (!anyAttached) return;
            int cx = cx();
            double sw = getSwing();
            double rad = sw * Math.PI / 180.0;
            Graphics2D g3 = (Graphics2D) g2.create();
            g3.translate(cx, ropeEndY()); g3.rotate(rad); g3.translate(-cx, -ropeEndY());
            if (attached[0]) drawLegA(g3, cx, hipY(), -1);
            if (attached[1]) drawLegA(g3, cx, hipY(),  1);
            if (attached[2]) drawArmA(g3, cx, shoulderY(), -1);
            if (attached[3]) drawArmA(g3, cx, shoulderY(),  1);
            if (attached[4]) drawTorsoA(g3, cx);
            if (attached[5]) drawHeadA(g3, cx, wrongCount >= MAX_WRONG);
            g3.dispose();
        }

        private void drawHeadA(Graphics2D g2, int cx, boolean dead) {
            int cy = headCY(), r = headR();
            g2.setColor(new Color(C_SKIN)); g2.fillOval(cx - r, cy - r, r * 2, r * 2);
            g2.setColor(new Color(C_SKIN_S)); g2.setStroke(new BasicStroke(2f));
            g2.drawOval(cx - r, cy - r, r * 2, r * 2);
            g2.setColor(new Color(C_HAIR));
            drawEllipseA(g2, cx, cy - r + 4, (int)(r * 0.85), (int)(r * 0.48));
            g2.fillOval(cx - r + 1, cy - 9, 10, 10); g2.fillOval(cx + r - 11, cy - 9, 10, 10);
            if (dead) {
                g2.setColor(new Color(C_HAIR)); g2.setStroke(new BasicStroke(2f));
                g2.drawLine(cx - 7, cy - 5, cx - 3, cy - 1); g2.drawLine(cx - 3, cy - 5, cx - 7, cy - 1);
                g2.drawLine(cx + 3, cy - 5, cx + 7, cy - 1); g2.drawLine(cx + 7, cy - 5, cx + 3, cy - 1);
                g2.drawLine(cx - 4, cy + 7, cx + 4, cy + 4);
            } else {
                g2.setColor(new Color(C_HAIR));
                drawEllipseA(g2, cx - 6, cy - 2, 3, 4); drawEllipseA(g2, cx + 6, cy - 2, 3, 4);
                g2.setColor(Color.WHITE);
                g2.fillOval(cx - 6, cy - 4, 3, 3); g2.fillOval(cx + 5, cy - 4, 3, 3);
                g2.setColor(new Color(C_HAIR)); g2.setStroke(new BasicStroke(1.5f));
                g2.drawArc(cx - 5, cy + 1, 10, 10, 0, -180);
                g2.setColor(new Color(232, 136, 136, 100));
                g2.fillOval(cx - 13, cy, 8, 6); g2.fillOval(cx + 5, cy, 8, 6);
            }
        }

        private void drawEllipseA(Graphics2D g2, int cx, int cy, int rx, int ry) {
            g2.fillOval(cx - rx, cy - ry, rx * 2, ry * 2);
        }

        private void drawTorsoA(Graphics2D g2, int cx) {
            int sy = shoulderY(), hy = hipY();
            g2.setColor(new Color(C_SHIRT_S));
            int[] xs = {cx-14, cx+14, cx+12, cx-12}; int[] ys = {sy, sy, hy, hy};
            g2.fillPolygon(xs, ys, 4);
            g2.setColor(new Color(C_SHIRT));
            int[] xs2 = {cx-13, cx+13, cx+11, cx-11}; int[] ys2 = {sy+2, sy+2, hy-2, hy-2};
            g2.fillPolygon(xs2, ys2, 4);
            g2.setColor(new Color(0x4a5a7a)); g2.setStroke(new BasicStroke(1f));
            for (int y = sy + 12; y < hy - 6; y += 14) g2.drawLine(cx - 10, y, cx + 10, y);
        }

        private void drawArmA(Graphics2D g2, int cx, int sy, int dir) {
            double sway = Math.sin(swingT * 2.1 + dir * 0.8) * 5.0;
            int aL = armL();
            int ex = (int)(cx + dir * (aL * 0.55 + sway * 0.3)), ey = sy + 20;
            int hx = (int)(cx + dir * (aL + sway));
            int hy = (int)(sy + aL * 0.9 + Math.cos(swingT * 1.8) * 3);
            g2.setColor(new Color(C_SHIRT_S)); g2.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawQuadCurve(g2, cx + dir * 10, sy + 4, ex, ey, hx, hy);
            g2.setColor(new Color(C_SHIRT)); g2.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawQuadCurve(g2, cx + dir * 10, sy + 4, ex, ey, hx, hy);
            g2.setColor(new Color(C_SKIN)); g2.fillOval(hx - 5, hy - 5, 10, 10);
        }

        private void drawLegA(Graphics2D g2, int cx, int hy2, int dir) {
            double sway = Math.sin(swingT * 1.6 + dir * 1.2) * 4.0;
            int lL = legL();
            int kx = (int)(cx + dir * (16 + Math.abs(sway) * 0.3)), ky = hy2 + lL / 2;
            int fx = (int)(cx + dir * (22 + sway)), fy = hy2 + lL;
            g2.setColor(new Color(C_PANTS_S)); g2.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawQuadCurve(g2, cx + dir * 8, hy2, kx, ky, fx, fy);
            g2.setColor(new Color(C_PANTS)); g2.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            drawQuadCurve(g2, cx + dir * 8, hy2, kx, ky, fx, fy);
            g2.setColor(new Color(C_SHOE)); drawShoe(g2, fx + dir * 7, fy + 2, dir);
        }

        private void drawShoe(Graphics2D g2, int cx, int cy, int dir) {
            int[] xs = new int[8], ys = new int[8];
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * i / 7.0;
                xs[i] = cx + (int)(12 * Math.cos(a) * dir); ys[i] = cy + (int)(5 * Math.sin(a));
            }
            g2.fillPolygon(xs, ys, 8);
        }

        private void drawQuadCurve(Graphics2D g2, int x1, int y1, int cx, int cy, int x2, int y2) {
            g2.draw(new java.awt.geom.QuadCurve2D.Float(x1, y1, cx, cy, x2, y2));
        }

        private void drawDetached(Graphics2D g2, int idx) {
            String name = PART_ORDER[idx];
            Graphics2D g3 = (Graphics2D) g2.create();
            g3.translate((int) px[idx], (int) py[idx]); g3.rotate(pAngle[idx]);
            int aL = armL(), lL = legL(), hR = headR(), tH = 54;
            switch (name) {
                case "head": {
                    g3.setColor(new Color(C_SKIN)); g3.fillOval(-hR, -hR, hR*2, hR*2);
                    g3.setColor(new Color(C_SKIN_S)); g3.setStroke(new BasicStroke(2f));
                    g3.drawOval(-hR, -hR, hR*2, hR*2);
                    g3.setColor(new Color(C_HAIR));
                    g3.fillOval(-hR+1, -hR-4, hR*2-2, hR); g3.fillOval(-hR+1, -9, 10, 10); g3.fillOval(hR-11, -9, 10, 10);
                    g3.setStroke(new BasicStroke(2f));
                    g3.drawLine(-7,-5,-3,-1); g3.drawLine(-3,-5,-7,-1);
                    g3.drawLine(3,-5,7,-1);   g3.drawLine(7,-5,3,-1);
                    g3.drawLine(-4,7,4,4); break;
                }
                case "torso": {
                    g3.setColor(new Color(C_SHIRT_S));
                    int[] xs={-14,14,12,-12}; int[] ys={-tH/2,-tH/2,tH/2,tH/2};
                    g3.fillPolygon(xs, ys, 4);
                    g3.setColor(new Color(C_SHIRT));
                    int[] xs2={-13,13,11,-11}; int[] ys2={-tH/2+2,-tH/2+2,tH/2-2,tH/2-2};
                    g3.fillPolygon(xs2, ys2, 4); break;
                }
                case "lArm": case "rArm": {
                    int dir = name.equals("lArm") ? -1 : 1;
                    g3.setColor(new Color(C_SHIRT_S)); g3.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g3.drawLine(0, 0, (int)(dir*aL*0.8), (int)(aL*0.7));
                    g3.setColor(new Color(C_SHIRT)); g3.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g3.drawLine(0, 0, (int)(dir*aL*0.8), (int)(aL*0.7));
                    g3.setColor(new Color(C_SKIN)); g3.fillOval((int)(dir*aL*0.8)-5, (int)(aL*0.7)-5, 10, 10); break;
                }
                case "lLeg": case "rLeg": {
                    int dir = name.equals("lLeg") ? -1 : 1;
                    g3.setColor(new Color(C_PANTS_S)); g3.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g3.drawLine(0, -lL/2, dir*14, lL/2);
                    g3.setColor(new Color(C_PANTS)); g3.setStroke(new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g3.drawLine(0, -lL/2, dir*14, lL/2);
                    g3.setColor(new Color(C_SHOE)); drawShoe(g3, dir*20, lL/2+2, dir); break;
                }
            }
            g3.dispose();
        }
    }

    private void saveAndExit() {
        if (db.isConnected()) {
            db.saveSessionScore(currentPlayerName, sessionScore, currentStage);
            db.saveProgress(currentPlayerName, difficulty, difficultyProgress.getOrDefault(difficulty, 1));
        }
        System.exit(0);
    }

        // ── Update main() ─────────────────────────────────────────────────────────────
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            // Build the main game frame UP FRONT but keep it hidden.
            // This way the transition is instant — no constructor delay between
            // splash dispose and main window appearing.
            LexiGuess game = new LexiGuess(false);   // false = don't auto-show

            LoadingScreen splash = new LoadingScreen(() -> {
                // Show the prepared game window, THEN dispose the splash
                // in the same EDT tick — zero visible gap, no flicker.
                game.setVisible(true);
                game.toFront();
                game.requestFocus();
            });
            splash.setVisible(true);
        });
    }
}