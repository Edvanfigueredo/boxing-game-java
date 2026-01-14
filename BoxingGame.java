import javax.sound.sampled.*;
import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BoxingGame extends JFrame {
    public BoxingGame() {
        setTitle("Jogo de Boxe");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        GamePanel panel = new GamePanel();
        add(panel);

        pack();
        setLocationRelativeTo(null);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new BoxingGame().setVisible(true));
    }
}

class GamePanel extends JPanel {

    // ======= CONFIG GERAL =======
    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final int GROUND = 450;

    private static final int ROUNDS_TOTAL = 5;
    private static final int ROUND_SECONDS = 30;
    private static final long ROUND_MS = ROUND_SECONDS * 1000L;

    // Stamina
    private static final int MAX_STAMINA = 100;
    private static final int COST_JAB = 12;
    private static final int COST_HOOK = 18;
    private static final int COST_CROSS = 22;

    // >>> ALTERAÇÃO PEDIDA: recarrega após 6s quando zera
    private static final long STAMINA_RECHARGE_COOLDOWN_MS = 6000;
    private boolean staminaOnCooldown = false;
    private long staminaCooldownLeftMs = 0;

    // Intro round
    private static final long INTRO_TOTAL_MS = 2200; // "ROUND X" -> "FIGHT!"
    private long introMsLeft = 0;

    // Shorts (seleção)
    private static final int SELECTION_COUNT = 3;
    private int selectedColor = 0;
    private final String[] colorNames = { "Preto", "Azul", "Vermelho" };
    private final Color[] shortColors = { Color.BLACK, new Color(0, 100, 255), new Color(220, 20, 20) };

    // Dificuldade (seleção)
    private Difficulty selectedDifficulty = Difficulty.MEDIUM;

    // ======= ESTADOS =======
    private enum GameState {
        MENU, HOW_TO_PLAY, SETUP, ROUND_INTRO, PLAYING, PAUSED, ROUND_END, GAME_OVER
    }

    private GameState state = GameState.MENU;

    // Menu
    private final String[] menuItems = { "Jogar", "Como jogar", "Sair" };
    private int menuIndex = 0;

    // ======= JOGO =======
    private Fighter player;
    private Fighter ai;

    private int playerStamina = MAX_STAMINA;

    private final Random random = new Random();
    private final List<Spectator> spectators = new ArrayList<>();
    private final List<Particle> particles = new ArrayList<>();

    // Input contínuo
    private boolean leftPressed = false;
    private boolean rightPressed = false;

    // Timer do round
    private long roundTimeLeftMs = ROUND_MS;
    private int currentRound = 1;

    // Placar de rounds
    private int playerRoundsWon = 0;
    private int aiRoundsWon = 0;

    // Controle de transições (fim do round)
    private long roundEndHoldMs = 0;
    private String roundEndMessage = "";

    // IA
    private long lastAIThinkMs = 0;
    private long nextAIThinkDelayMs = 600;

    // Loop
    private final Timer gameTimer;
    private long lastTickNs = System.nanoTime();

    // Som
    private final SoundPlayer sounds = new SoundPlayer();

    public GamePanel() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setBackground(new Color(40, 40, 60));
        setFocusable(true);

        generateSpectators();
        setupKeyBindings();

        gameTimer = new Timer(16, e -> {
            long nowNs = System.nanoTime();
            long dtNs = nowNs - lastTickNs;
            lastTickNs = nowNs;

            if (state == GameState.ROUND_INTRO) {
                updateIntro(dtNs);
            } else if (state == GameState.PLAYING) {
                update(dtNs);
            } else if (state == GameState.ROUND_END) {
                updateRoundEnd(dtNs);
            }

            repaint();
        });
        gameTimer.start();
    }

    // ======= DIFICULDADE =======
    private enum Difficulty {
        EASY("Fácil", 760, 1200, 0.45, 0.50, 4, 1),
        MEDIUM("Médio", 520, 900, 0.60, 0.65, 5, 2),
        HARD("Difícil", 320, 650, 0.75, 0.78, 6, 3);

        final String label;
        final int thinkMinMs;
        final int thinkMaxMs;
        final double attackChanceClose;
        final double attackChanceMid;
        final int aiSpeed;
        final int aiDamageBonus;

        Difficulty(String label, int thinkMinMs, int thinkMaxMs,
                double attackChanceClose, double attackChanceMid,
                int aiSpeed, int aiDamageBonus) {
            this.label = label;
            this.thinkMinMs = thinkMinMs;
            this.thinkMaxMs = thinkMaxMs;
            this.attackChanceClose = attackChanceClose;
            this.attackChanceMid = attackChanceMid;
            this.aiSpeed = aiSpeed;
            this.aiDamageBonus = aiDamageBonus;
        }
    }

    // ======= SETUP / RESET =======
    private void resetAllToMenu() {
        state = GameState.MENU;
        menuIndex = 0;

        selectedColor = 0;
        selectedDifficulty = Difficulty.MEDIUM;

        player = null;
        ai = null;
        particles.clear();

        leftPressed = false;
        rightPressed = false;

        currentRound = 1;
        playerRoundsWon = 0;
        aiRoundsWon = 0;
        roundTimeLeftMs = ROUND_MS;

        roundEndHoldMs = 0;
        roundEndMessage = "";

        lastAIThinkMs = 0;
        nextAIThinkDelayMs = 600;

        // stamina reset
        playerStamina = MAX_STAMINA;
        staminaOnCooldown = false;
        staminaCooldownLeftMs = 0;

        introMsLeft = 0;
    }

    private void startMatch() {
        playerRoundsWon = 0;
        aiRoundsWon = 0;
        currentRound = 1;
        startRound();
    }

    private void startRound() {
        particles.clear();

        player = new Fighter(200, GROUND, shortColors[selectedColor], true);
        ai = new Fighter(550, GROUND, new Color(100, 100, 100), false);

        // dificuldade
        ai.setMoveSpeed(selectedDifficulty.aiSpeed);
        ai.setDamageBonus(selectedDifficulty.aiDamageBonus);

        // stamina e tempo
        playerStamina = MAX_STAMINA;
        staminaOnCooldown = false;
        staminaCooldownLeftMs = 0;

        roundTimeLeftMs = ROUND_MS;
        leftPressed = false;
        rightPressed = false;

        lastAIThinkMs = System.currentTimeMillis();
        nextAIThinkDelayMs = randBetween(selectedDifficulty.thinkMinMs, selectedDifficulty.thinkMaxMs);

        // intro
        introMsLeft = INTRO_TOTAL_MS;
        state = GameState.ROUND_INTRO;

        sounds.play("sounds/bell.wav");
    }

    private void updateIntro(long dtNs) {
        long dtMs = dtNs / 1_000_000L;
        introMsLeft -= dtMs;
        if (introMsLeft <= 0) {
            introMsLeft = 0;
            state = GameState.PLAYING;
        }
    }

    private void endRound(String message) {
        state = GameState.ROUND_END;
        roundEndMessage = message;
        roundEndHoldMs = 0;
    }

    private void finishGame(String message) {
        state = GameState.GAME_OVER;
        roundEndMessage = message;
    }

    // ======= UPDATE =======
    private void update(long dtNs) {
        if (player == null || ai == null)
            return;

        long dtMs = dtNs / 1_000_000L;

        // ===== STAMINA: cooldown de 6s quando zera =====
        if (staminaOnCooldown) {
            staminaCooldownLeftMs -= dtMs;
            if (staminaCooldownLeftMs <= 0) {
                staminaCooldownLeftMs = 0;
                staminaOnCooldown = false;
                playerStamina = MAX_STAMINA; // recarrega 100%
            }
        }

        // tempo
        roundTimeLeftMs -= dtMs;
        if (roundTimeLeftMs < 0)
            roundTimeLeftMs = 0;

        // movimento player
        if (leftPressed)
            player.move(-1, WIDTH);
        if (rightPressed)
            player.move(+1, WIDTH);

        player.update();
        ai.update();

        player.setFacing(ai.getX() >= player.getX());
        ai.setFacing(player.getX() >= ai.getX());

        // IA
        long now = System.currentTimeMillis();
        if (now - lastAIThinkMs >= nextAIThinkDelayMs) {
            thinkAI();
            lastAIThinkMs = now;
            nextAIThinkDelayMs = randBetween(selectedDifficulty.thinkMinMs, selectedDifficulty.thinkMaxMs);
        }

        // hits
        checkHits(player, ai);
        checkHits(ai, player);

        // partículas
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.update();
            if (!p.isAlive())
                particles.remove(i);
        }

        // KO?
        if (player.isDead() || ai.isDead()) {
            sounds.play("sounds/ko.wav");
            if (player.isDead() && ai.isDead()) {
                awardRoundByHealth("Round " + currentRound + ": Double KO! (empate)");
            } else if (player.isDead()) {
                aiRoundsWon++;
                if (aiRoundsWon >= 3 || currentRound >= ROUNDS_TOTAL)
                    finishGame("Você perdeu por KO!");
                else
                    endRound("Round " + currentRound + ": Você levou KO!");
            } else {
                playerRoundsWon++;
                if (playerRoundsWon >= 3 || currentRound >= ROUNDS_TOTAL)
                    finishGame("Você venceu por KO!");
                else
                    endRound("Round " + currentRound + ": KO! Você venceu!");
            }
            return;
        }

        // tempo acabou
        if (roundTimeLeftMs == 0) {
            awardRoundByHealth("Round " + currentRound + ": Tempo esgotado");
        }
    }

    private void awardRoundByHealth(String baseMsg) {
        int ph = player.getHealth();
        int ah = ai.getHealth();

        if (ph > ah) {
            playerRoundsWon++;
            if (playerRoundsWon >= 3 || currentRound >= ROUNDS_TOTAL)
                finishGame(baseMsg + " — Você venceu por pontos!");
            else
                endRound(baseMsg + " — Você venceu por pontos!");
        } else if (ah > ph) {
            aiRoundsWon++;
            if (aiRoundsWon >= 3 || currentRound >= ROUNDS_TOTAL)
                finishGame(baseMsg + " — Você perdeu por pontos!");
            else
                endRound(baseMsg + " — Você perdeu por pontos!");
        } else {
            if (currentRound >= ROUNDS_TOTAL) {
                if (playerRoundsWon > aiRoundsWon)
                    finishGame(baseMsg + " — Empate no round. Você vence no total!");
                else if (aiRoundsWon > playerRoundsWon)
                    finishGame(baseMsg + " — Empate no round. IA vence no total!");
                else
                    finishGame(baseMsg + " — Empate geral!");
            } else {
                endRound(baseMsg + " — Empate no round!");
            }
        }
    }

    private void updateRoundEnd(long dtNs) {
        long dtMs = dtNs / 1_000_000L;
        roundEndHoldMs += dtMs;

        if (roundEndHoldMs >= 2000) {
            currentRound++;
            if (currentRound > ROUNDS_TOTAL) {
                if (playerRoundsWon > aiRoundsWon)
                    finishGame("Fim dos rounds — Você venceu!");
                else if (aiRoundsWon > playerRoundsWon)
                    finishGame("Fim dos rounds — Você perdeu!");
                else
                    finishGame("Fim dos rounds — Empate!");
            } else {
                startRound();
            }
        }
    }

    // ======= IA =======
    private void thinkAI() {
        if (player == null || ai == null)
            return;

        int dist = player.getX() - ai.getX();
        int abs = Math.abs(dist);

        boolean far = abs > 200;
        boolean mid = abs > 120 && abs <= 200;
        boolean close = abs <= 120;

        if (far) {
            ai.move(dist > 0 ? +1 : -1, WIDTH);
            return;
        }

        if (mid) {
            if (random.nextDouble() < selectedDifficulty.attackChanceMid) {
                ai.punch(randomPunch());
            } else {
                int dir = dist > 0 ? +1 : -1;
                if (random.nextBoolean())
                    ai.move(dir, WIDTH);
                else
                    ai.move(-dir, WIDTH);
            }
            return;
        }

        if (close) {
            if (random.nextDouble() < selectedDifficulty.attackChanceClose) {
                ai.punch(randomPunch());
            } else {
                int dirAway = dist > 0 ? -1 : +1;
                ai.move(dirAway, WIDTH);
            }
        }
    }

    private PunchType randomPunch() {
        int r = random.nextInt(3);
        return switch (r) {
            case 0 -> PunchType.JAB;
            case 1 -> PunchType.HOOK;
            default -> PunchType.CROSS;
        };
    }

    private void checkHits(Fighter attacker, Fighter defender) {
        if (!attacker.isPunching())
            return;
        if (attacker.isHitRegistered())
            return;

        Rectangle hitBox = attacker.getHitBox();
        Rectangle hurtBox = defender.getHurtBox();

        if (hitBox.intersects(hurtBox)) {
            int damage = 5 + random.nextInt(10) + attacker.getDamageBonus();
            defender.takeDamage(damage);
            attacker.setHitRegistered(true);
            createHitEffect(defender.getX(), defender.getY() - 50);
            sounds.play("sounds/punch.wav");
        }
    }

    private void createHitEffect(int x, int y) {
        for (int i = 0; i < 15; i++)
            particles.add(new Particle(x, y, random));
    }

    private int randBetween(int a, int b) {
        return a + random.nextInt(Math.max(1, (b - a + 1)));
    }

    // ======= INPUT (KEY BINDINGS) =======
    private void setupKeyBindings() {
        int cond = JComponent.WHEN_IN_FOCUSED_WINDOW;
        InputMap im = getInputMap(cond);
        ActionMap am = getActionMap();

        im.put(KeyStroke.getKeyStroke("UP"), "up");
        im.put(KeyStroke.getKeyStroke("DOWN"), "down");
        im.put(KeyStroke.getKeyStroke("ENTER"), "enter");
        im.put(KeyStroke.getKeyStroke("ESCAPE"), "esc");
        im.put(KeyStroke.getKeyStroke("P"), "pause");

        im.put(KeyStroke.getKeyStroke("pressed A"), "pressA");
        im.put(KeyStroke.getKeyStroke("released A"), "releaseA");
        im.put(KeyStroke.getKeyStroke("pressed D"), "pressD");
        im.put(KeyStroke.getKeyStroke("released D"), "releaseD");

        im.put(KeyStroke.getKeyStroke("J"), "jab");
        im.put(KeyStroke.getKeyStroke("K"), "hook");
        im.put(KeyStroke.getKeyStroke("L"), "cross");

        im.put(KeyStroke.getKeyStroke("LEFT"), "left");
        im.put(KeyStroke.getKeyStroke("RIGHT"), "right");

        am.put("up", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.MENU)
                    menuIndex = (menuIndex - 1 + menuItems.length) % menuItems.length;
                else if (state == GameState.SETUP)
                    menuIndex = (menuIndex - 1 + 2) % 2;
            }
        });

        am.put("down", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.MENU)
                    menuIndex = (menuIndex + 1) % menuItems.length;
                else if (state == GameState.SETUP)
                    menuIndex = (menuIndex + 1) % 2;
            }
        });

        am.put("left", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.SETUP) {
                    if (menuIndex == 0)
                        selectedColor = (selectedColor - 1 + SELECTION_COUNT) % SELECTION_COUNT;
                    else
                        selectedDifficulty = prevDifficulty(selectedDifficulty);
                }
            }
        });

        am.put("right", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.SETUP) {
                    if (menuIndex == 0)
                        selectedColor = (selectedColor + 1) % SELECTION_COUNT;
                    else
                        selectedDifficulty = nextDifficulty(selectedDifficulty);
                }
            }
        });

        am.put("enter", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.MENU) {
                    String item = menuItems[menuIndex];
                    if (item.equals("Jogar")) {
                        state = GameState.SETUP;
                        menuIndex = 0;
                    } else if (item.equals("Como jogar")) {
                        state = GameState.HOW_TO_PLAY;
                    } else if (item.equals("Sair")) {
                        System.exit(0);
                    }
                } else if (state == GameState.HOW_TO_PLAY) {
                    state = GameState.MENU;
                } else if (state == GameState.SETUP) {
                    startMatch();
                } else if (state == GameState.GAME_OVER) {
                    resetAllToMenu();
                }
            }
        });

        am.put("esc", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.HOW_TO_PLAY || state == GameState.SETUP || state == GameState.GAME_OVER) {
                    resetAllToMenu();
                } else if (state == GameState.PAUSED) {
                    resetAllToMenu();
                }
            }
        });

        am.put("pause", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.PLAYING)
                    state = GameState.PAUSED;
                else if (state == GameState.PAUSED)
                    state = GameState.PLAYING;
            }
        });

        am.put("pressA", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.PLAYING)
                    leftPressed = true;
            }
        });
        am.put("releaseA", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                leftPressed = false;
            }
        });

        am.put("pressD", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.PLAYING)
                    rightPressed = true;
            }
        });
        am.put("releaseD", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                rightPressed = false;
            }
        });

        am.put("jab", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.PLAYING && player != null)
                    tryPunch(PunchType.JAB);
            }
        });
        am.put("hook", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.PLAYING && player != null)
                    tryPunch(PunchType.HOOK);
            }
        });
        am.put("cross", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (state == GameState.PLAYING && player != null)
                    tryPunch(PunchType.CROSS);
            }
        });
    }

    private void tryPunch(PunchType type) {
        // se estiver em recarga, não deixa bater
        if (staminaOnCooldown)
            return;

        int cost = switch (type) {
            case JAB -> COST_JAB;
            case HOOK -> COST_HOOK;
            case CROSS -> COST_CROSS;
        };

        // evita golpe durante animação atual
        if (player.isPunching())
            return;

        // sem stamina = inicia cooldown de 6s
        if (playerStamina < cost) {
            if (playerStamina <= 0 && !staminaOnCooldown) {
                staminaOnCooldown = true;
                staminaCooldownLeftMs = STAMINA_RECHARGE_COOLDOWN_MS;
            }
            return;
        }

        playerStamina -= cost;
        if (playerStamina <= 0) {
            playerStamina = 0;
            staminaOnCooldown = true;
            staminaCooldownLeftMs = STAMINA_RECHARGE_COOLDOWN_MS;
        }

        player.punch(type);
    }

    private Difficulty nextDifficulty(Difficulty d) {
        return switch (d) {
            case EASY -> Difficulty.MEDIUM;
            case MEDIUM -> Difficulty.HARD;
            case HARD -> Difficulty.EASY;
        };
    }

    private Difficulty prevDifficulty(Difficulty d) {
        return switch (d) {
            case EASY -> Difficulty.HARD;
            case MEDIUM -> Difficulty.EASY;
            case HARD -> Difficulty.MEDIUM;
        };
    }

    // ======= RENDER =======
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D gg = (Graphics2D) g;
        gg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        switch (state) {
            case MENU -> drawMenu(gg);
            case HOW_TO_PLAY -> drawHowTo(gg);
            case SETUP -> drawSetup(gg);
            default -> drawGame(gg);
        }

        if (state == GameState.ROUND_INTRO)
            drawIntroOverlay(gg);
        if (state == GameState.PAUSED)
            drawPauseOverlay(gg);
        if (state == GameState.ROUND_END)
            drawRoundEndOverlay(gg);
        if (state == GameState.GAME_OVER)
            drawGameOverOverlay(gg);
    }

    private void drawMenu(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 54));
        g.drawString("BOXING GAME", WIDTH / 2 - 210, 120);

        g.setFont(new Font("Arial", Font.PLAIN, 18));
        g.drawString("Use ↑ ↓ e ENTER", WIDTH / 2 - 80, 160);

        int startY = 250;
        for (int i = 0; i < menuItems.length; i++) {
            boolean selected = (i == menuIndex);
            g.setFont(new Font("Arial", selected ? Font.BOLD : Font.PLAIN, selected ? 34 : 28));
            g.setColor(selected ? Color.YELLOW : Color.WHITE);
            g.drawString(menuItems[i], WIDTH / 2 - 60, startY + i * 60);
        }

        g.setFont(new Font("Arial", Font.PLAIN, 14));
        g.setColor(new Color(220, 220, 220));
        g.drawString("No jogo: A/D mover | J/K/L golpes | P pausar | ESC menu", 180, HEIGHT - 30);
    }

    private void drawHowTo(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 42));
        g.drawString("COMO JOGAR", WIDTH / 2 - 160, 100);

        g.setFont(new Font("Arial", Font.PLAIN, 20));
        int y = 170;
        g.drawString("• A / D: mover", 120, y);
        y += 35;
        g.drawString("• J: Jab | K: Gancho | L: Cruzado", 120, y);
        y += 35;
        g.drawString("• Stamina: ao zerar, recarrega 100% após 6 segundos", 120, y);
        y += 35;
        g.drawString("• P: Pausar / Retomar", 120, y);
        y += 35;
        g.drawString("• 5 rounds de 30s (melhor de 5: primeiro a 3 vence)", 120, y);
        y += 35;
        g.drawString("• KO vence o round na hora; senão, vence por mais vida", 120, y);
        y += 35;

        g.setFont(new Font("Arial", Font.BOLD, 18));
        g.setColor(Color.YELLOW);
        g.drawString("Pressione ENTER para voltar", WIDTH / 2 - 130, HEIGHT - 80);
    }

    private void drawSetup(Graphics2D g) {
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 42));
        g.drawString("CONFIGURAR PARTIDA", WIDTH / 2 - 240, 100);

        g.setFont(new Font("Arial", Font.PLAIN, 18));
        g.drawString("Use ↑ ↓ para escolher linha, ← → para alterar, ENTER para iniciar", WIDTH / 2 - 280, 145);

        int boxX = 120, boxW = 560, boxH = 90;

        drawSetupRow(g, boxX, 210, boxW, boxH, "Calção", colorNames[selectedColor], menuIndex == 0);
        drawSetupRow(g, boxX, 320, boxW, boxH, "Dificuldade", selectedDifficulty.label, menuIndex == 1);

        int px = 650;
        int py = 260;
        g.setColor(new Color(255, 220, 177));
        g.fillOval(px - 15, py - 60, 30, 30);
        g.fillRect(px - 15, py - 30, 30, 50);
        g.setColor(shortColors[selectedColor]);
        g.fillRect(px - 20, py + 20, 40, 35);

        g.setFont(new Font("Arial", Font.PLAIN, 16));
        g.setColor(new Color(230, 230, 230));
        g.drawString("Configuração: 5 rounds | 30s cada | Melhor de 5", 190, 460);

        g.setFont(new Font("Arial", Font.BOLD, 18));
        g.setColor(Color.YELLOW);
        g.drawString("ESC para voltar ao menu", WIDTH / 2 - 120, HEIGHT - 60);
    }

    private void drawSetupRow(Graphics2D g, int x, int y, int w, int h, String label, String value, boolean selected) {
        g.setColor(selected ? new Color(255, 230, 120) : new Color(255, 255, 255, 120));
        g.setStroke(new BasicStroke(selected ? 4 : 2));
        g.drawRoundRect(x, y, w, h, 18, 18);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 22));
        g.drawString(label + ":", x + 20, y + 35);

        g.setFont(new Font("Arial", Font.PLAIN, 22));
        g.drawString(value, x + 190, y + 35);

        g.setFont(new Font("Arial", Font.PLAIN, 14));
        g.setColor(new Color(220, 220, 220));
        g.drawString("← → para alterar", x + 20, y + 65);
    }

    private void drawGame(Graphics2D g) {
        drawSpectators(g);
        drawRing(g);

        if (player != null)
            player.draw(g);
        if (ai != null)
            ai.draw(g);

        for (Particle p : particles)
            p.draw(g);

        drawHUD(g);
    }

    private void drawSpectators(Graphics2D g) {
        for (Spectator s : spectators) {
            g.setColor(s.color);
            g.fillOval(s.x, s.y, 20, 20);
            g.fillRect(s.x + 5, s.y + 20, 10, 15);
        }
    }

    private void drawRing(Graphics2D g) {
        g.setColor(new Color(180, 140, 100));
        g.fillRect(0, GROUND, WIDTH, HEIGHT - GROUND);

        g.setColor(Color.RED);
        g.setStroke(new BasicStroke(3));
        for (int i = 0; i < 3; i++) {
            int y = GROUND - 100 + i * 40;
            g.drawLine(50, y, WIDTH - 50, y);
        }

        g.setColor(Color.WHITE);
        g.fillRect(50, GROUND - 120, 10, 120);
        g.fillRect(WIDTH - 60, GROUND - 120, 10, 120);
    }

    private void drawHUD(Graphics2D g) {
        if (player == null || ai == null)
            return;

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 18));
        g.drawString("VOCÊ", 20, 30);
        g.drawString("OPONENTE", WIDTH - 150, 30);

        drawHealthBar(g, 20, 40, 200, 20, player.getHealth(), Fighter.MAX_HEALTH);
        drawHealthBar(g, WIDTH - 220, 40, 200, 20, ai.getHealth(), Fighter.MAX_HEALTH);

        // stamina bar
        g.setFont(new Font("Arial", Font.BOLD, 14));
        g.setColor(Color.WHITE);
        g.drawString("STAMINA", 20, 78);
        drawStaminaBar(g, 20, 86, 200, 12);

        g.setFont(new Font("Arial", Font.BOLD, 16));
        g.setColor(Color.WHITE);
        g.drawString("Round: " + currentRound + "/" + ROUNDS_TOTAL, WIDTH / 2 - 70, 30);
        g.drawString("Tempo: " + formatTime(roundTimeLeftMs), WIDTH / 2 - 55, 55);

        g.setFont(new Font("Arial", Font.BOLD, 16));
        g.setColor(new Color(240, 240, 240));
        g.drawString("Rounds (Você x IA): " + playerRoundsWon + " x " + aiRoundsWon, WIDTH / 2 - 105, 78);

        g.setFont(new Font("Arial", Font.PLAIN, 13));
        g.setColor(new Color(220, 220, 220));
        g.drawString("Dificuldade: " + selectedDifficulty.label, 20, HEIGHT - 40);
        g.drawString("A/D mover | J/K/L golpes | P pausar | ESC menu", 250, HEIGHT - 20);
    }

    private void drawHealthBar(Graphics2D g, int x, int y, int w, int h, int hp, int max) {
        g.setColor(Color.RED);
        g.fillRect(x, y, w, h);

        int fill = (int) Math.round((hp / (double) max) * w);
        fill = Math.max(0, Math.min(w, fill));

        g.setColor(Color.GREEN);
        g.fillRect(x, y, fill, h);

        g.setColor(Color.WHITE);
        g.drawRect(x, y, w, h);
    }

    private void drawStaminaBar(Graphics2D g, int x, int y, int w, int h) {
        // fundo
        g.setColor(new Color(60, 60, 60));
        g.fillRect(x, y, w, h);

        int fill = (int) Math.round((playerStamina / (double) MAX_STAMINA) * w);
        fill = Math.max(0, Math.min(w, fill));

        // cor muda se estiver em cooldown
        if (staminaOnCooldown)
            g.setColor(new Color(255, 190, 70));
        else
            g.setColor(new Color(80, 170, 255));

        g.fillRect(x, y, fill, h);

        g.setColor(Color.WHITE);
        g.drawRect(x, y, w, h);

        // texto cooldown
        if (staminaOnCooldown) {
            g.setFont(new Font("Arial", Font.BOLD, 12));
            g.setColor(Color.WHITE);
            long sec = (long) Math.ceil(staminaCooldownLeftMs / 1000.0);
            g.drawString("Recarregando: " + sec + "s", x + 70, y + 11);
        }
    }

    private void drawIntroOverlay(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        String text = (introMsLeft > 900) ? ("ROUND " + currentRound) : "FIGHT!";

        g.setColor(Color.YELLOW);
        g.setFont(new Font("Arial", Font.BOLD, 70));
        g.drawString(text, WIDTH / 2 - (text.length() * 18), HEIGHT / 2);
    }

    private void drawPauseOverlay(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(Color.YELLOW);
        g.setFont(new Font("Arial", Font.BOLD, 60));
        g.drawString("PAUSADO", WIDTH / 2 - 150, HEIGHT / 2 - 20);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.PLAIN, 20));
        g.drawString("Pressione P para voltar", WIDTH / 2 - 120, HEIGHT / 2 + 30);
        g.drawString("ESC para sair ao menu", WIDTH / 2 - 120, HEIGHT / 2 + 60);
    }

    private void drawRoundEndOverlay(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 170));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 34));
        g.drawString(roundEndMessage, 70, HEIGHT / 2 - 10);

        g.setFont(new Font("Arial", Font.PLAIN, 18));
        g.setColor(new Color(230, 230, 230));
        g.drawString("Próximo round em instantes...", WIDTH / 2 - 140, HEIGHT / 2 + 40);
    }

    private void drawGameOverOverlay(Graphics2D g) {
        g.setColor(new Color(0, 0, 0, 190));
        g.fillRect(0, 0, WIDTH, HEIGHT);

        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.BOLD, 48));
        g.drawString(roundEndMessage, 70, HEIGHT / 2 - 40);

        g.setFont(new Font("Arial", Font.PLAIN, 22));
        g.setColor(new Color(220, 220, 220));
        g.drawString("Placar final (Você x IA): " + playerRoundsWon + " x " + aiRoundsWon, 220, HEIGHT / 2 + 10);
        g.drawString("ENTER para voltar ao menu", 250, HEIGHT / 2 + 55);
        g.drawString("ESC para voltar ao menu", 260, HEIGHT / 2 + 85);
    }

    private String formatTime(long ms) {
        long sec = ms / 1000;
        long s = sec % 60;
        return String.format("%02d", s);
    }

    private void generateSpectators() {
        spectators.clear();
        for (int row = 0; row < 3; row++) {
            for (int i = 0; i < 20; i++) {
                int x = i * 40 + (row % 2) * 20;
                int y = 50 + row * 30;
                Color c = new Color(
                        random.nextInt(100) + 100,
                        random.nextInt(100) + 50,
                        random.nextInt(100) + 50);
                spectators.add(new Spectator(x, y, c));
            }
        }
    }

    private static class Spectator {
        final int x, y;
        final Color color;

        Spectator(int x, int y, Color color) {
            this.x = x;
            this.y = y;
            this.color = color;
        }
    }
}

// ======= FIGHTER / HITBOX =======
enum PunchType {
    JAB(60, 10),
    HOOK(45, 15),
    CROSS(70, 12);

    final int reach;
    final int thickness;

    PunchType(int reach, int thickness) {
        this.reach = reach;
        this.thickness = thickness;
    }
}

class Fighter {
    public static final int MAX_HEALTH = 100;

    private int x;
    private final int y;
    private int health = MAX_HEALTH;

    private final Color shortColor;
    private final boolean isPlayer;

    private boolean facingRight = true;

    private boolean punching = false;
    private boolean hitRegistered = false;
    private PunchType punchType = PunchType.JAB;
    private int punchFrame = 0;

    private int moveSpeed = 5;
    private int damageBonus = 0;

    private static final int BODY_W = 30;
    private static final int BODY_H = 90;

    public Fighter(int x, int y, Color shortColor, boolean isPlayer) {
        this.x = x;
        this.y = y;
        this.shortColor = shortColor;
        this.isPlayer = isPlayer;
    }

    public void setMoveSpeed(int speed) {
        this.moveSpeed = speed;
    }

    public void setDamageBonus(int bonus) {
        this.damageBonus = bonus;
    }

    public int getDamageBonus() {
        return damageBonus;
    }

    public void update() {
        if (punching) {
            punchFrame++;
            if (punchFrame > 15) {
                punching = false;
                hitRegistered = false;
                punchFrame = 0;
            }
        }
    }

    public void move(int dir, int panelWidth) {
        int newX = x + dir * moveSpeed;
        int minX = 100;
        int maxX = panelWidth - 100;
        x = Math.max(minX, Math.min(maxX, newX));
    }

    public void punch(PunchType type) {
        if (!punching) {
            punching = true;
            punchType = type;
            punchFrame = 0;
        }
    }

    public void takeDamage(int damage) {
        health -= damage;
        if (health < 0)
            health = 0;
    }

    public boolean isDead() {
        return health <= 0;
    }

    public int getHealth() {
        return health;
    }

    public Rectangle getHurtBox() {
        int top = y - 110;
        int left = x - (BODY_W / 2);
        return new Rectangle(left, top, BODY_W, BODY_H);
    }

    public Rectangle getHitBox() {
        int top = y - 85;
        int height = 40;

        int reach = punchType.reach;
        int thick = punchType.thickness;

        int left;
        int width = reach;

        if (facingRight)
            left = x + 10;
        else
            left = x - 10 - reach;

        top -= thick;
        height += thick * 2;

        return new Rectangle(left, top, width, height);
    }

    public void draw(Graphics2D g) {
        g.setColor(new Color(255, 220, 177));
        g.fillOval(x - 15, y - 110, 30, 30);

        g.fillRect(x - 15, y - 80, 30, 50);

        g.setColor(shortColor);
        g.fillRect(x - 20, y - 30, 40, 35);

        g.setColor(new Color(255, 220, 177));
        g.fillRect(x - 15, y + 5, 12, 50);
        g.fillRect(x + 3, y + 5, 12, 50);

        int leftArmX = x - 30;
        int rightArmX = x + 30;
        int armY = y - 70;

        if (punching) {
            int t = Math.min(punchFrame, 8);
            int push = t * 5;

            if (punchType == PunchType.JAB) {
                if (facingRight)
                    rightArmX = x + 30 + push;
                else
                    leftArmX = x - 30 - push;
            } else if (punchType == PunchType.HOOK) {
                armY = y - 80;
                if (facingRight)
                    rightArmX = x + 25 + push;
                else
                    leftArmX = x - 25 - push;
            } else if (punchType == PunchType.CROSS) {
                armY = y - 60;
                if (facingRight)
                    rightArmX = x + 35 + push;
                else
                    leftArmX = x - 35 - push;
            }
        }

        g.setColor(new Color(255, 220, 177));
        g.setStroke(new BasicStroke(8));
        g.drawLine(x - 10, y - 75, leftArmX, armY);
        g.drawLine(x + 10, y - 75, rightArmX, armY);

        g.setColor(Color.RED);
        g.fillOval(leftArmX - 8, armY - 8, 16, 16);
        g.fillOval(rightArmX - 8, armY - 8, 16, 16);
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public boolean isPunching() {
        return punching;
    }

    public boolean isHitRegistered() {
        return hitRegistered;
    }

    public void setHitRegistered(boolean v) {
        hitRegistered = v;
    }

    public void setFacing(boolean facingRight) {
        this.facingRight = facingRight;
    }
}

// ======= PARTICLE =======
class Particle {
    private double x, y;
    private double vx, vy;
    private int life = 20;
    private final Color color;

    public Particle(int x, int y, Random r) {
        this.x = x;
        this.y = y;
        this.vx = (r.nextDouble() - 0.5) * 8;
        this.vy = (r.nextDouble() - 0.5) * 8;
        this.color = new Color(255, r.nextInt(100) + 155, 0);
    }

    public void update() {
        x += vx;
        y += vy;
        vy += 0.3;
        life--;
    }

    public boolean isAlive() {
        return life > 0;
    }

    public void draw(Graphics2D g) {
        int alpha = Math.max(0, Math.min(255, life * 12));
        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
        g.fillOval((int) x, (int) y, 6, 6);
    }
}

// ======= SOUND PLAYER (WAV) =======
class SoundPlayer {
    public void play(String path) {
        new Thread(() -> {
            try {
                File file = new File(path);
                if (!file.exists())
                    return;

                AudioInputStream ais = AudioSystem.getAudioInputStream(file);
                Clip clip = AudioSystem.getClip();
                clip.open(ais);
                clip.start();
            } catch (Exception ignored) {
            }
        }).start();
    }
}
