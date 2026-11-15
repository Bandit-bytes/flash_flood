package net.bandit.flash_flood.client.trailer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import net.bandit.flash_flood.FlashFloodMod;
import net.bandit.flash_flood.FloodData;
import net.bandit.flash_flood.FloodManager;
import net.bandit.flash_flood.SimpleConfig;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * Dev-only trailer director. Enabled with {@code -Dflash_flood.trailer=true} (the {@code runTrailer} task)
 * and excluded from release jars.
 *
 * <p>Creates a fresh world, finds a river with low land beside it, then plays a fixed sequence of camera
 * shots while driving the weather, the flood, lightning and the soundtrack, signalling an external screen
 * recorder (via files in {@code trailer-signals/}) when to start and stop. The game quits when done.
 *
 * <p>The camera is driven every rendered frame (not just every tick), so both movement and rotation are
 * smooth; that's what makes orbit and push-in shots possible.
 */
public final class TrailerDirector {
    private static final Logger LOGGER = LoggerFactory.getLogger("flash_flood/trailer");
    private static final String WORLD_NAME = "FlashFloodTrailer";
    private static final long SEED = 20261115L;
    private static final Path SIGNAL_DIR = Path.of("trailer-signals");
    private static final double EYE_HEIGHT = 1.62;
    /**
     * The village by the river (seed 20261115) picked for the trailer. Set to null to search for a river
     * instead (results vary a little between runs).
     */
    private static final BlockPos FILM_AT = new BlockPos(-138, 65, 807);

    private enum Phase { WAIT_TITLE, WAIT_WORLD, WAIT_SETUP, WAIT_CHUNKS, WAIT_GEOMETRY, PRE_ROLL, ROLLING, DONE }

    private static Phase phase = Phase.WAIT_TITLE;
    private static int phaseTicks;
    private static int t; // timeline tick, 0 = first frame of the trailer

    // Written on the server thread, read on the client thread.
    private static volatile boolean setupDone;
    private static volatile RiverShot geometry;

    private static CameraPath currentPath;
    private static int shotStart, shotLength;
    private static final Random RANDOM = new Random(7);

    private TrailerDirector() {}

    public static void init() {
        LOGGER.info("Trailer mode enabled");
        tuneFloodsForCamera(FlashFloodMod.config());
        ClientTickEvent.CLIENT_POST.register(TrailerDirector::tick);
        ClientGuiEvent.RENDER_HUD.register(TrailerDirector::renderFrame);
    }

    /** Make floods rise and drain within seconds so they read on camera. Not saved to disk. */
    private static void tuneFloodsForCamera(SimpleConfig c) {
        c.floodChance = 0;            // the director starts the flood itself
        c.maxRise = 3;
        c.riseIntervalSeconds = 6;
        c.floodRadius = 44;
        c.spreadAttemptsPerPlayer = 700;
        c.maxBlocksPerTick = 220;
        c.drainBlocksPerTick = 70;
        c.maxFloodBlocks = 200_000;
        c.announceFloods = false;     // the trailer has its own captions, all at one text size
    }

    // --- state machine -------------------------------------------------------------------------

    private static void tick(Minecraft mc) {
        phaseTicks++;
        switch (phase) {
            case WAIT_TITLE -> {
                if (mc.screen instanceof TitleScreen && phaseTicks > 40) {
                    applyClientOptions(mc);
                    createWorld(mc);
                    next(Phase.WAIT_WORLD);
                }
            }
            case WAIT_WORLD -> {
                MinecraftServer server = mc.getSingleplayerServer();
                if (mc.player != null && mc.level != null && server != null && mc.screen == null) {
                    server.execute(() -> setupWorld(server));
                    next(Phase.WAIT_SETUP);
                }
            }
            case WAIT_SETUP -> {
                if (setupDone) {
                    next(Phase.WAIT_CHUNKS);
                }
            }
            case WAIT_CHUNKS -> {
                // Give the server time to generate terrain around the river.
                if (phaseTicks > 300) {
                    MinecraftServer server = mc.getSingleplayerServer();
                    BlockPos around = mc.player.blockPosition();
                    server.execute(() -> geometry = RiverShot.measure(server.overworld(), around));
                    next(Phase.WAIT_GEOMETRY);
                }
            }
            case WAIT_GEOMETRY -> {
                if (geometry != null) {
                    LOGGER.info("Filming around {}", geometry.centerPos);
                    cut(geometry.establishing, 1);
                    next(Phase.PRE_ROLL);
                }
            }
            case PRE_ROLL -> {
                applyCamera(mc.player, 0);
                if (phaseTicks == 100) {
                    mc.gui.getChat().clearMessages(false);
                    signal("start", String.valueOf(ProcessHandle.current().pid()));
                }
                if (phaseTicks >= 200) { // ~5s for the recorder to bring the window forward and start
                    t = 0;
                    next(Phase.ROLLING);
                }
            }
            case ROLLING -> {
                timeline(mc);
                rainBed(mc);
                applyCamera(mc.player, t);
                t++;
            }
            case DONE -> {
                if (phaseTicks == 40) {
                    mc.stop();
                }
            }
        }
    }

    private static void next(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    // --- the trailer ---------------------------------------------------------------------------

    private static void timeline(Minecraft mc) {
        RiverShot g = geometry;
        MinecraftServer server = mc.getSingleplayerServer();

        switch (t) {
            // 1. Calm river, establishing shot.
            case 0 -> cut(g.establishing, 140);
            case 5 -> showLogo(5, 125);

            // 2. Rain starts, low on the bank.
            case 140 -> {
                cut(g.bank, 150);
                onServer(server, level -> level.setWeatherParameters(0, 1_000_000, true, false));
            }
            case 160 -> caption(160, 270, "When heavy rain sets in...");

            // 3. Flood begins: a slow rising orbit around the land as the water spreads, lightning overhead.
            case 290 -> {
                cut(g.orbit, 440);
                onServer(server, level -> {
                    level.setWeatherParameters(0, 1_000_000, true, true);
                    FloodManager.startFlood(level, FloodData.get(level));
                });
            }
            case 305, 545, 665 -> lightning(mc, server);
            case 360 -> caption(360, 490, "...lakes, rivers and oceans\noverflow their banks");

            // 4. Glide in low over the water as it creeps across the grass.
            case 730 -> {
                cut(g.skim, 180);
                logFlood(server, "mid-flood");
            }
            case 745 -> caption(745, 885, "Water spreads naturally\nover low ground");
            case 800 -> lightning(mc, server);

            // 5. Storm passes, the flood drains and the grass comes back.
            case 910 -> {
                cut(g.drain, 400);
                logFlood(server, "peak");
                onServer(server, level -> level.setWeatherParameters(1_000_000, 0, false, false));
            }
            case 960 -> caption(960, 1100, "...then drains away\nwhen the storm passes");

            // 6. Pull back for the end card.
            case 1310 -> cut(g.endCard, 160);
            case 1315 -> {
                showLogo(1315, 1475);
                caption(1315, 1475, "Fabric & NeoForge • Minecraft 1.21.1");
                logFlood(server, "after drain");
            }

            case 1480 -> {
                signal("stop", "");
                next(Phase.DONE);
            }
            default -> {}
        }
    }

    // --- sound ---------------------------------------------------------------------------------

    /**
     * Vanilla rain is played from the ground around the camera, so it all but vanishes in high shots and
     * the soundtrack pumps in and out between cuts. Vanilla weather sound is muted for the trailer and
     * this plays an even bed of rain at the camera instead, following the actual rain strength.
     */
    private static void rainBed(Minecraft mc) {
        if (mc.level == null || mc.player == null || t % 3 != 0) {
            return;
        }
        float rain = mc.level.getRainLevel(1f);
        if (rain < 0.02f) {
            return;
        }
        Vec3 eye = mc.player.getEyePosition();
        double ox = (RANDOM.nextDouble() - 0.5) * 8, oz = (RANDOM.nextDouble() - 0.5) * 8;
        mc.level.playLocalSound(eye.x + ox, eye.y - 1, eye.z + oz, SoundEvents.WEATHER_RAIN, SoundSource.AMBIENT,
                0.22f * rain, 0.9f + RANDOM.nextFloat() * 0.2f, false);
    }

    /** A visual-only lightning strike in view, with thunder (vanilla's is muted with the weather). */
    private static void lightning(Minecraft mc, MinecraftServer server) {
        RiverShot g = geometry;
        double side = (RANDOM.nextDouble() - 0.5) * 60;
        Vec3 at = g.focus.add(g.toward.scale(45 + RANDOM.nextDouble() * 25)).add(g.across.scale(side));
        onServer(server, level -> {
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
            if (bolt != null) {
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) at.x, (int) at.z);
                bolt.moveTo(at.x, y, at.z);
                bolt.setVisualOnly(true);
                level.addFreshEntity(bolt);
            }
        });
        Vec3 eye = mc.player.getEyePosition();
        mc.level.playLocalSound(eye.x, eye.y, eye.z, SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.AMBIENT,
                0.8f, 0.8f + RANDOM.nextFloat() * 0.2f, false);
    }

    // --- overlays: logo and captions -----------------------------------------------------------

    private static final ResourceLocation LOGO = ResourceLocation.fromNamespaceAndPath(FlashFloodMod.MOD_ID, "textures/trailer/logo.png");
    private static final int LOGO_W = 1024, LOGO_H = 968;
    private static final int FADE = 18;
    /** One text scale for every caption in the trailer. */
    private static final float CAPTION_SCALE = 2.0f;
    private static final int LINE_HEIGHT = 11;

    private static int logoFrom = -1, logoTo = -1;
    private static boolean logoFiltered;
    private static int captionFrom = -1, captionTo = -1;
    private static String[] captionLines = new String[0];

    private static void showLogo(int from, int to) {
        logoFrom = from;
        logoTo = to;
    }

    private static void caption(int from, int to, String text) {
        captionFrom = from;
        captionTo = to;
        captionLines = text.split("\n");
    }

    private static float fade(float now, int from, int to) {
        if (from < 0 || now < from || now > to) {
            return 0f;
        }
        return Mth.clamp(Math.min((now - from) / FADE, (to - now) / FADE), 0f, 1f);
    }

    /** Runs every frame: moves the camera smoothly, then draws the logo and captions. */
    private static void renderFrame(GuiGraphics graphics, DeltaTracker delta) {
        if (phase != Phase.ROLLING && phase != Phase.PRE_ROLL) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        float now = phase == Phase.ROLLING ? t + delta.getGameTimeDeltaPartialTick(false) : 0f;
        applyCamera(mc.player, now);
        if (phase != Phase.ROLLING) {
            return;
        }

        int sw = graphics.guiWidth(), sh = graphics.guiHeight();
        float logoAlpha = fade(now, logoFrom, logoTo);
        float captionAlpha = fade(now, captionFrom, captionTo);
        int captionBlock = Math.round(captionLines.length * LINE_HEIGHT * CAPTION_SCALE);

        int logoBottom = -1;
        if (logoAlpha > 0.01f) {
            if (!logoFiltered) {
                // Smooth scaling for a large painted logo (the default is pixelated nearest-neighbour).
                mc.getTextureManager().getTexture(LOGO).setFilter(true, false);
                logoFiltered = true;
            }
            boolean withCaption = captionAlpha > 0.01f;
            int h = (int) (sh * (withCaption ? 0.52 : 0.62));
            int w = h * LOGO_W / LOGO_H;
            int gap = withCaption ? sh / 30 : 0;
            int top = (sh - h - (withCaption ? gap + captionBlock : 0)) / 2;
            RenderSystem.enableBlend();
            graphics.setColor(1f, 1f, 1f, logoAlpha);
            graphics.blit(LOGO, (sw - w) / 2, top, w, h, 0, 0, LOGO_W, LOGO_H, LOGO_W, LOGO_H);
            graphics.setColor(1f, 1f, 1f, 1f);
            RenderSystem.disableBlend();
            logoBottom = top + h + gap;
        }

        if (captionAlpha > 0.01f) {
            // Below the logo on the title cards; lower-middle of the screen otherwise.
            int top = logoBottom >= 0 ? logoBottom : (int) (sh * 0.70) - captionBlock / 2;
            int color = (Math.max(5, (int) (captionAlpha * 255)) << 24) | 0xFFFFFF;
            PoseStack pose = graphics.pose();
            pose.pushPose();
            pose.translate(sw / 2f, top, 0);
            pose.scale(CAPTION_SCALE, CAPTION_SCALE, 1f);
            for (int i = 0; i < captionLines.length; i++) {
                graphics.drawCenteredString(mc.font, captionLines[i], 0, i * LINE_HEIGHT, color);
            }
            pose.popPose();
        }
    }

    private static void logFlood(MinecraftServer server, String when) {
        onServer(server, level -> {
            FloodData data = FloodData.get(level);
            LOGGER.info("Flood {}: active={}, rise=+{}, flood blocks={}, washed plants pending={}", when,
                    data.isFloodActive(), FloodManager.currentRise(data), data.size(), data.washedCount());
        });
    }

    // --- camera --------------------------------------------------------------------------------

    /** A camera position (eye) and orientation. */
    record Pose(Vec3 eye, float yaw, float pitch) {
        static Pose lookingAt(Vec3 eye, Vec3 target) {
            Vec3 d = target.subtract(eye);
            double horizontal = Math.sqrt(d.x * d.x + d.z * d.z);
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, horizontal));
            return new Pose(eye, yaw, pitch);
        }

        Vec3 forward() {
            double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
            return new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
        }
    }

    /** Camera pose as a function of shot progress (0 to 1). */
    interface CameraPath {
        Pose at(double progress);
    }

    /** Eased move between two poses, interpolating position and rotation. */
    record Glide(Pose from, Pose to) implements CameraPath {
        @Override
        public Pose at(double p) {
            double e = ease(p);
            float yaw = from.yaw() + Mth.wrapDegrees(to.yaw() - from.yaw()) * (float) e;
            return new Pose(from.eye().lerp(to.eye(), e), yaw, Mth.lerp((float) e, from.pitch(), to.pitch()));
        }
    }

    /** Smooth spline through precomputed eye points, always looking at (interpolated) targets. */
    record Spline(List<Vec3> eyes, List<Vec3> targets) implements CameraPath {
        @Override
        public Pose at(double p) {
            double u = ease(p) * (eyes.size() - 1);
            int i = Mth.clamp((int) Math.floor(u), 0, eyes.size() - 2);
            double f = u - i;
            Vec3 eye = catmullRom(get(eyes, i - 1), get(eyes, i), get(eyes, i + 1), get(eyes, i + 2), f);
            Vec3 target = get(targets, i).lerp(get(targets, i + 1), f);
            return Pose.lookingAt(eye, target);
        }

        private static Vec3 get(List<Vec3> list, int i) {
            return list.get(Mth.clamp(i, 0, list.size() - 1));
        }

        private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, double f) {
            double f2 = f * f, f3 = f2 * f;
            return p1.scale(2)
                    .add(p2.subtract(p0).scale(f))
                    .add(p0.scale(2).subtract(p1.scale(5)).add(p2.scale(4)).subtract(p3).scale(f2))
                    .add(p1.scale(3).subtract(p0).subtract(p2.scale(3)).add(p3).scale(f3))
                    .scale(0.5);
        }
    }

    /** Slow in, slow out. */
    private static double ease(double p) {
        p = Mth.clamp(p, 0, 1);
        return 0.5 - 0.5 * Math.cos(Math.PI * p);
    }

    private static void cut(CameraPath path, int length) {
        currentPath = path;
        shotStart = t;
        shotLength = Math.max(1, length);
    }

    /**
     * Places the camera exactly at the path's pose for this moment. Previous position/rotation are set to
     * the same values so the renderer doesn't blend in last tick's position: called every frame, this gives
     * perfectly smooth motion and rotation, and hard cuts between shots.
     */
    private static void applyCamera(LocalPlayer player, float now) {
        if (player == null || currentPath == null) {
            return;
        }
        Pose pose = currentPath.at((now - shotStart) / shotLength);
        Vec3 e = pose.eye();
        double y = e.y - EYE_HEIGHT;
        player.setDeltaMovement(Vec3.ZERO);
        player.getAbilities().flying = true;
        player.setPos(e.x, y, e.z);
        player.xo = player.xOld = e.x;
        player.yo = player.yOld = y;
        player.zo = player.zOld = e.z;
        player.setYRot(pose.yaw());
        player.setXRot(pose.pitch());
        player.yRotO = pose.yaw();
        player.xRotO = pose.pitch();
        player.setYHeadRot(pose.yaw());
        player.yHeadRotO = pose.yaw();
        player.yBodyRot = player.yBodyRotO = pose.yaw();
    }

    // --- world setup ---------------------------------------------------------------------------

    private static void applyClientOptions(Minecraft mc) {
        var o = mc.options;
        o.pauseOnLostFocus = false;
        o.hideGui = false; // keep the overlays; spectator mode hides the rest of the HUD
        o.bobView().set(false);
        o.fov().set(70);
        o.renderDistance().set(12);
        o.getSoundSourceOptionInstance(SoundSource.MASTER).set(1.0);
        o.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
        o.getSoundSourceOptionInstance(SoundSource.WEATHER).set(0.0); // replaced by rainBed() and lightning()
        o.getSoundSourceOptionInstance(SoundSource.AMBIENT).set(1.0);
        o.getSoundSourceOptionInstance(SoundSource.BLOCKS).set(1.0);
    }

    private static void createWorld(Minecraft mc) {
        LevelSettings settings = new LevelSettings(WORLD_NAME, GameType.SPECTATOR, false, Difficulty.PEACEFUL,
                true, new GameRules(), WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(SEED, true, false);
        mc.createWorldOpenFlows().createFreshLevel(WORLD_NAME, settings, options,
                WorldPresets::createNormalWorldDimensions, mc.screen);
    }

    /** Server thread: calm, bright, empty world, then jump to a good rainy river. */
    private static void setupWorld(MinecraftServer server) {
        ServerLevel level = server.overworld();
        GameRules rules = level.getGameRules();
        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
        rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, server);
        level.setDayTime(2000);
        level.setWeatherParameters(1_000_000, 0, false, false);

        ServerPlayer player = server.getPlayerList().getPlayers().get(0);
        player.setGameMode(GameType.SPECTATOR);

        BlockPos target = FILM_AT != null ? FILM_AT : findFilmableRiver(level, player.blockPosition());
        player.teleportTo(level, target.getX() + 0.5, 110, target.getZ() + 0.5, 0, 60);
        setupDone = true;
    }

    /**
     * Finds a river whose surroundings are rainy, open land. Floods only spread where it rains,
     * so a river through a desert or savanna would show nothing. Searches outward from spawn and
     * keeps the best-scoring river.
     */
    private static BlockPos findFilmableRiver(ServerLevel level, BlockPos origin) {
        int[][] offsets = {{0, 0}, {1, 0}, {0, 1}, {-1, 0}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1},
                {2, 0}, {0, 2}, {-2, 0}, {0, -2}, {2, 2}, {-2, 2}, {2, -2}, {-2, -2}};
        BlockPos best = origin;
        int bestScore = -1;
        for (int[] o : offsets) {
            BlockPos from = origin.offset(o[0] * 900, 0, o[1] * 900);
            var found = level.findClosestBiome3d(TrailerDirector::isRainyRiver, from, 1200, 32, 64);
            if (found == null) {
                continue;
            }
            BlockPos pos = found.getFirst();
            int score = surroundingsScore(level, pos);
            LOGGER.info("River candidate {} scored {}/{}", pos, score, MAX_SCORE);
            if (score > bestScore) {
                bestScore = score;
                best = pos;
            }
            if (score >= MAX_SCORE * 85 / 100) {
                break;
            }
        }
        LOGGER.info("River search chose {} (score {}/{})", best, bestScore, MAX_SCORE);
        return best;
    }

    private static final int[] RINGS = {12, 22, 34};
    private static final int MAX_SCORE = RINGS.length * 8 * 2;

    /** +1 per sample point that gets rain, +1 more if it's open (plains, meadow, the river itself). */
    private static int surroundingsScore(ServerLevel level, BlockPos center) {
        int score = 0;
        int sea = level.getSeaLevel();
        for (int r : RINGS) {
            for (int i = 0; i < 8; i++) {
                double a = Math.PI * 2 * i / 8;
                int x = center.getX() + (int) Math.round(Math.cos(a) * r);
                int z = center.getZ() + (int) Math.round(Math.sin(a) * r);
                Holder<Biome> biome = level.getUncachedNoiseBiome(x >> 2, sea >> 2, z >> 2);
                if (biome.is(BiomeTags.IS_OCEAN)
                        || biome.value().getPrecipitationAt(new BlockPos(x, sea, z)) != Biome.Precipitation.RAIN) {
                    continue;
                }
                score++;
                if (biome.is(Biomes.PLAINS) || biome.is(Biomes.SUNFLOWER_PLAINS) || biome.is(Biomes.MEADOW)
                        || biome.is(BiomeTags.IS_RIVER)) {
                    score++;
                }
            }
        }
        return score;
    }

    private static boolean isRainyRiver(Holder<Biome> biome) {
        return biome.is(BiomeTags.IS_RIVER) && !biome.is(Biomes.FROZEN_RIVER) && biome.value().hasPrecipitation();
    }

    // --- helpers -------------------------------------------------------------------------------

    private static void onServer(MinecraftServer server, Consumer<ServerLevel> action) {
        server.execute(() -> action.accept(server.overworld()));
    }

    private static void signal(String name, String content) {
        try {
            Files.createDirectories(SIGNAL_DIR);
            Files.writeString(SIGNAL_DIR.resolve(name), content);
        } catch (IOException e) {
            LOGGER.error("Failed to write recorder signal {}", name, e);
        }
    }

    // --- shot planning -------------------------------------------------------------------------

    /**
     * Plans the shots around the best patch of floodable land: low ground right next to the water,
     * which is where the flood will visibly spread. All paths are computed here, on the server thread,
     * because they check the terrain; the client only evaluates them.
     */
    static final class RiverShot {
        private static final int SCAN = 64;

        final BlockPos centerPos;
        final Vec3 focus;    // middle of the floodable land
        final Vec3 water;    // nearby water the flood comes from
        final Vec3 toward;   // unit vector from the water towards the land
        final Vec3 across;   // unit vector perpendicular to that
        final double waterY; // top surface of the water
        final double floodTop;
        private final ServerLevel level;

        final CameraPath establishing, bank, orbit, skim, drain, endCard;

        private RiverShot(ServerLevel level, Vec3 focus, Vec3 water, double waterY) {
            this.level = level;
            this.focus = focus;
            this.water = water;
            this.waterY = waterY;
            this.floodTop = waterY + FlashFloodMod.config().maxRise;
            Vec3 d = new Vec3(focus.x - water.x, 0, focus.z - water.z);
            this.toward = d.lengthSqr() < 1e-6 ? new Vec3(1, 0, 0) : d.normalize();
            this.across = new Vec3(-toward.z, 0, toward.x);
            this.centerPos = BlockPos.containing(focus);

            Pose aerialL = aerial(-9), aerialR = aerial(9);
            this.establishing = clearGlide(aerialL, aerialR);
            this.bank = clearGlide(low(-3), low(3));
            this.orbit = orbitPath();
            this.skim = skimPath();
            this.drain = clearGlide(aerialL, aerialR);
            this.endCard = clearGlide(aerialR, pullBack(aerialR, 18));
        }

        static RiverShot measure(ServerLevel level, BlockPos around) {
            int n = SCAN * 2 + 1;
            int[][] ground = new int[n][n];
            boolean[][] wet = new boolean[n][n];
            Map<Integer, Integer> surfaceCounts = new HashMap<>();
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int x = around.getX() - SCAN + i, z = around.getZ() - SCAN + j;
                    int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
                    BlockState state = level.getBlockState(new BlockPos(x, top, z));
                    ground[i][j] = top;
                    if (state.getFluidState().isSource() && state.getFluidState().is(FluidTags.WATER)) {
                        wet[i][j] = true;
                        surfaceCounts.merge(top, 1, Integer::sum);
                    }
                }
            }
            if (surfaceCounts.isEmpty()) {
                LOGGER.warn("No water near {}, filming anyway", around);
                Vec3 c = Vec3.atBottomCenterOf(around.atY(level.getSeaLevel()));
                return new RiverShot(level, c.add(8, 0, 0), c, level.getSeaLevel());
            }
            int waterBlockY = surfaceCounts.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
            int rise = FlashFloodMod.config().maxRise;

            boolean[][] floodable = new boolean[n][n];
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    floodable[i][j] = !wet[i][j] && ground[i][j] >= waterBlockY - 1 && ground[i][j] <= waterBlockY + rise - 1;
                }
            }

            int bestI = SCAN, bestJ = SCAN, bestScore = -1;
            for (int i = 14; i < n - 14; i += 2) {
                for (int j = 14; j < n - 14; j += 2) {
                    if (!floodable[i][j]) continue;
                    int land = 0, wetCount = 0;
                    for (int di = -12; di <= 12; di++) {
                        for (int dj = -12; dj <= 12; dj++) {
                            if (di * di + dj * dj > 144) continue;
                            if (floodable[i + di][j + dj]) land++;
                            if (wet[i + di][j + dj] && ground[i + di][j + dj] == waterBlockY) wetCount++;
                        }
                    }
                    if (wetCount < 25) continue;
                    int score = land * 2 + Math.min(wetCount, 120);
                    if (score > bestScore) {
                        bestScore = score;
                        bestI = i;
                        bestJ = j;
                    }
                }
            }

            double wx = 0, wz = 0;
            int wc = 0;
            for (int di = -16; di <= 16; di++) {
                for (int dj = -16; dj <= 16; dj++) {
                    int i = bestI + di, j = bestJ + dj;
                    if (i < 0 || j < 0 || i >= n || j >= n || di * di + dj * dj > 256) continue;
                    if (wet[i][j]) { wx += i; wz += j; wc++; }
                }
            }
            double baseX = around.getX() - SCAN + 0.5, baseZ = around.getZ() - SCAN + 0.5;
            double surface = waterBlockY + 1;
            Vec3 focus = new Vec3(baseX + bestI, surface, baseZ + bestJ);
            Vec3 water = wc > 0 ? new Vec3(baseX + wx / wc, surface, baseZ + wz / wc) : focus.add(-8, 0, 0);
            LOGGER.info("Shot plan: focus {} (score {}), water surface y={}", BlockPos.containing(focus), bestScore, surface);
            return new RiverShot(level, focus, water, surface);
        }

        /** High view from over the water, looking at the floodable land; slid sideways for dolly moves. */
        Pose aerial(double slide) {
            Vec3 eyeBase = focus.subtract(toward.scale(20)).add(0, 17, 0);
            Pose base = Pose.lookingAt(eyeBase, focus.add(toward.scale(2)));
            return new Pose(clear(eyeBase.add(across.scale(slide))), base.yaw(), base.pitch());
        }

        /** Low view standing on the floodable land, looking back at the water; above the highest flood. */
        Pose low(double slide) {
            Vec3 spot = focus.add(toward.scale(6)).add(across.scale(slide));
            int groundY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(spot.x), (int) Math.floor(spot.z));
            double y = Math.max(groundY + 1.8, floodTop + 1.2);
            Vec3 eye = clear(new Vec3(spot.x, y, spot.z));
            return Pose.lookingAt(eye, new Vec3(water.x, waterY - 1.5, water.z).add(across.scale(slide)));
        }

        /**
         * The flooding hero shot: a slow orbit around the land, starting low over the water and rising as
         * it sweeps round, so the water spreading across the ground is seen with depth and parallax.
         */
        CameraPath orbitPath() {
            Vec3 start = toward.reverse();
            double startAngle = Math.atan2(start.z, start.x);
            double sweep = Math.toRadians(80);
            int samples = 81; // dense, so the obstacle check can't miss a tree between samples
            List<Vec3> eyes = new ArrayList<>(samples);
            List<Vec3> targets = new ArrayList<>(samples);
            for (int i = 0; i < samples; i++) {
                double f = i / (double) (samples - 1);
                double a = startAngle + sweep * f;
                double radius = Mth.lerp(f, 24, 17);
                double height = floodTop + Mth.lerp(f, 4.5, 13);
                Vec3 eye = new Vec3(focus.x + Math.cos(a) * radius, height, focus.z + Math.sin(a) * radius);
                Vec3 target = new Vec3(focus.x, waterY, focus.z).add(toward.scale(Mth.lerp(f, 2, 5)));
                for (int k = 0; k < 24 && obstructed(Pose.lookingAt(eye, target), 0); k++) {
                    eye = eye.add(0, 1, 0);
                }
                eyes.add(eye);
                targets.add(target);
            }
            smoothHeights(eyes, 8);
            return new Spline(eyes, targets);
        }

        /**
         * Low glide over the water towards the shore, just above the highest flood level. Slides sideways
         * along the river to find a line with nothing in the way, so it stays low instead of being raised.
         */
        CameraPath skimPath() {
            double y = floodTop + 1.1;
            Glide best = null;
            for (double shift : new double[]{0, -5, 5, -10, 10, -15, 15, -20, 20}) {
                Vec3 c = new Vec3(focus.x, y, focus.z).add(across.scale(shift));
                Vec3 from = c.subtract(toward.scale(17)).add(across.scale(-4));
                Vec3 to = c.subtract(toward.scale(5)).add(across.scale(2));
                Vec3 lookFrom = new Vec3(c.x, waterY - 0.5, c.z).add(toward.scale(8));
                Vec3 lookTo = new Vec3(c.x, waterY - 0.5, c.z).add(toward.scale(14));
                Glide glide = new Glide(Pose.lookingAt(from, lookFrom), Pose.lookingAt(to, lookTo));
                if (liftNeeded(glide) == 0) {
                    LOGGER.info("Low glide: clear line found {} blocks along the river", shift);
                    return glide;
                }
                if (best == null) {
                    best = glide;
                }
            }
            return clearGlide(best.from(), best.to());
        }

        /** Same orientation, moved {@code distance} blocks backwards (and so upwards). */
        Pose pullBack(Pose pose, double distance) {
            return new Pose(pose.eye().subtract(pose.forward().scale(distance)), pose.yaw(), pose.pitch());
        }

        /** Nudge the camera up until nothing (terrain, trees, roofs) is within its margin. */
        private Vec3 clear(Vec3 eye) {
            Vec3 e = eye;
            for (int i = 0; i < 24 && obstructed(e); i++) {
                e = e.add(0, 1, 0);
            }
            return e;
        }

        /** True if the camera would be inside something, or have leaves/solid blocks right in front of the lens. */
        private boolean obstructed(Pose pose, double lift) {
            Vec3 eye = pose.eye().add(0, lift, 0);
            if (obstructed(eye)) {
                return true;
            }
            // Straight ahead out to 7 blocks, and towards the edges of the frame out to 4 blocks.
            float[][] rays = {{0, 0, 7}, {-28, 0, 4}, {28, 0, 4}, {0, -16, 4}, {0, 16, 4}};
            for (float[] ray : rays) {
                Vec3 dir = new Pose(eye, pose.yaw() + ray[0], pose.pitch() + ray[1]).forward();
                for (double d = 1.0; d <= ray[2]; d += 0.5) {
                    BlockPos p = BlockPos.containing(eye.add(dir.scale(d)));
                    BlockState state = level.getBlockState(p);
                    if (state.is(BlockTags.LEAVES) || !state.getCollisionShape(level, p).isEmpty()) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** True if any non-air block is within about half a block of the camera. */
        private boolean obstructed(Vec3 eye) {
            double m = 0.6;
            for (double dx = -m; dx <= m; dx += m) {
                for (double dy = -m; dy <= m; dy += m) {
                    for (double dz = -m; dz <= m; dz += m) {
                        if (!level.getBlockState(BlockPos.containing(eye.add(dx, dy, dz))).isAir()) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * A glide checked along its whole length, not just at the ends: if anything is in the way anywhere,
         * the whole move is raised just enough to pass over it.
         */
        private Glide clearGlide(Pose from, Pose to) {
            Glide glide = new Glide(from, to);
            int lift = liftNeeded(glide);
            if (lift > 0) {
                LOGGER.info("Raised a shot by {} blocks to clear obstacles", lift);
            }
            Vec3 up = new Vec3(0, lift, 0);
            return new Glide(new Pose(from.eye().add(up), from.yaw(), from.pitch()),
                    new Pose(to.eye().add(up), to.yaw(), to.pitch()));
        }

        /** How far the whole glide must be raised for a clear camera and view along its entire length. */
        private int liftNeeded(Glide glide) {
            for (int lift = 0; lift <= 24; lift++) {
                boolean clearPath = true;
                for (int i = 0; i <= 60 && clearPath; i++) {
                    if (obstructed(glide.at(i / 60.0), lift)) {
                        clearPath = false;
                    }
                }
                if (clearPath) {
                    return lift;
                }
            }
            return 24;
        }

        /** Replace each height by the max over a window, so terrain nudges become gentle rises, not bumps. */
        private static void smoothHeights(List<Vec3> points, int window) {
            List<Vec3> copy = new ArrayList<>(points);
            for (int i = 0; i < points.size(); i++) {
                double max = Double.NEGATIVE_INFINITY;
                for (int k = Math.max(0, i - window); k <= Math.min(points.size() - 1, i + window); k++) {
                    max = Math.max(max, copy.get(k).y);
                }
                Vec3 p = copy.get(i);
                points.set(i, new Vec3(p.x, max, p.z));
            }
        }
    }
}
