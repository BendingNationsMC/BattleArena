package org.battleplugins.arena.util;

import org.battleplugins.arena.config.ColorParser;
import org.battleplugins.arena.config.DurationParser;
import org.battleplugins.arena.config.ItemStackParser;
import org.battleplugins.arena.config.ParseException;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Vibration;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Firework;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.util.Vector;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A utility for playing and creating custom effects.
 *
 * @param <B> the builder type
 */
public interface CustomEffect<B> {

    /**
     * Gets the type of the effect.
     *
     * @return the type of the effect
     */
    EffectType<B> getType();

    /**
     * Plays the effect at the given location.
     *
     * @param location the location to play the effect
     */
    void play(Location location);

    /**
     * Plays the effect at the given entity.
     *
     * @param entity the entity to play the effect
     */
    void play(Entity entity);

    /**
     * Deserializes the data into the effect.
     *
     * @param data the data to deserialize
     */
    void deserialize(Map<String, String> data) throws ParseException;

    static CustomEffect<FireworkEffect.Builder> firework(Consumer<FireworkEffect.Builder> builder) {
        FireworkEffect.Builder fireworkBuilder = FireworkEffect.builder();
        builder.accept(fireworkBuilder);

        return new CustomEffect<>() {

            @Override
            public EffectType<FireworkEffect.Builder> getType() {
                return EffectType.FIREWORK;
            }

            @Override
            public void play(Location location) {
                location.getWorld().spawn(location, Firework.class, firework -> {
                    FireworkMeta meta = firework.getFireworkMeta();
                    meta.addEffect(fireworkBuilder.build());
                    firework.setFireworkMeta(meta);
                }).detonate();
            }

            @Override
            public void play(Entity entity) {
                this.play(entity.getLocation());
            }

            @Override
            public void deserialize(Map<String, String> data) throws ParseException {
                if (!data.containsKey("firework-type")) {
                    throw new IllegalArgumentException("FireworkEffect type must be set");
                }

                FireworkEffect.Type type = FireworkEffect.Type.valueOf(data.get("firework-type").toUpperCase(Locale.ROOT));
                fireworkBuilder.with(type);
                for (Map.Entry<String, String> entry : data.entrySet()) {
                    if (entry.getKey().equals("firework-type")) {
                        continue;
                    }

                    String key = entry.getKey();
                    String value = entry.getValue();

                    switch (key) {
                        case "flicker" -> fireworkBuilder.flicker(Boolean.parseBoolean(value));
                        case "trail" -> fireworkBuilder.trail(Boolean.parseBoolean(value));
                        case "colors" -> {
                            List<String> colorStrings = CustomEffect.getList(value);
                            List<Color> colors = new ArrayList<>();
                            for (String colorString : colorStrings) {
                                colors.add(ColorParser.deserializeSingularBukkit(colorString));
                            }

                            fireworkBuilder.withColor(colors);
                        }
                        case "fade-colors" -> {
                            List<String> colorStrings = CustomEffect.getList(value);
                            List<Color> colors = new ArrayList<>();
                            for (String colorString : colorStrings) {
                                colors.add(ColorParser.deserializeSingularBukkit(colorString));
                            }

                            fireworkBuilder.withFade(colors);
                        }
                    }
                }
            }
        };
    }

    static CustomEffect<ParticleBuilder> particle(Consumer<ParticleBuilder> builder) {
        ParticleBuilder particleBuilder = new ParticleBuilder();
        builder.accept(particleBuilder);

        return new CustomEffect<>() {

            @Override
            public EffectType<ParticleBuilder> getType() {
                return EffectType.PARTICLE;
            }

            @Override
            public void play(Location location) {
                if (particleBuilder.particle == null) {
                    throw new IllegalStateException("Particle must be set before playing the effect");
                }

                location.getWorld().spawnParticle(particleBuilder.particle, location, particleBuilder.count, particleBuilder.offset.getX(), particleBuilder.offset.getY(), particleBuilder.offset.getZ(), particleBuilder.speed, particleBuilder.data);
            }

            @Override
            public void play(Entity entity) {
                this.play(entity.getLocation());
            }

            @Override
            public void deserialize(Map<String, String> data) throws ParseException {
                if (!data.containsKey("particle")) {
                    throw new IllegalArgumentException("Particle must be set");
                }

                Particle particle = Particle.valueOf(data.get("particle").toUpperCase(Locale.ROOT));
                particleBuilder.particle(particle);

                for (Map.Entry<String, String> entry : data.entrySet()) {
                    if (entry.getKey().equals("particle")) {
                        continue;
                    }

                    String key = entry.getKey();
                    String value = entry.getValue();

                    switch (key) {
                        case "speed" -> particleBuilder.speed(Double.parseDouble(value));
                        case "count" -> particleBuilder.count(Integer.parseInt(value));
                        case "offset" -> {
                            String[] split = value.split(",");
                            if (split.length != 3) {
                                throw new IllegalArgumentException("Offset must have 3 values!");
                            }

                            particleBuilder.offset(new Vector(Double.parseDouble(split[0]), Double.parseDouble(split[1]), Double.parseDouble(split[2])));
                        }
                        case "data" -> {
                            switch (particle) {
                                case DUST -> {
                                    String[] split = value.split(",");
                                    if (split.length != 3) {
                                        throw new IllegalArgumentException("Data must have 3 values for REDSTONE particles");
                                    }

                                    particleBuilder.data(new Particle.DustOptions(Color.fromRGB(Integer.parseInt(split[0]), Integer.parseInt(split[1]), Integer.parseInt(split[2])), 1));
                                }
                                case ITEM -> {
                                    ItemStack item = ItemStackParser.deserializeSingular(value);
                                    particleBuilder.data(item);
                                }
                                case BLOCK, FALLING_DUST, BLOCK_MARKER -> {
                                    BlockData blockData;
                                    try {
                                        blockData = Bukkit.createBlockData(value);
                                    } catch (IllegalArgumentException e) {
                                        throw new ParseException("Invalid BlockData: " + value)
                                                .cause(ParseException.Cause.INVALID_TYPE)
                                                .userError();
                                    }
                                    particleBuilder.data(blockData);
                                }
                                case DUST_COLOR_TRANSITION -> {
                                    List<String> colorStrings = CustomEffect.getList(value);
                                    if (colorStrings.size() != 3) {
                                        throw new IllegalArgumentException("Data must have 3 values");
                                    }

                                    Color fromColor = ColorParser.deserializeSingularBukkit(colorStrings.get(0));
                                    Color toColor = ColorParser.deserializeSingularBukkit(colorStrings.get(1));
                                    float size = Float.parseFloat(colorStrings.get(2));

                                    particleBuilder.data(new Particle.DustTransition(fromColor, toColor, size));
                                }
                                case VIBRATION -> throw new UnsupportedOperationException("Vibration particles are not supported");
                                case SCULK_CHARGE -> particleBuilder.data(Float.parseFloat(value));
                                case SHRIEK -> particleBuilder.data(Integer.parseInt(value));
                            }
                        }
                    }
                }
            }
        };
    }

    static CustomEffect<FreezeBuilder> freeze(Consumer<FreezeBuilder> builder) {
        FreezeBuilder freezeBuilder = new FreezeBuilder();
        builder.accept(freezeBuilder);

        return new CustomEffect<>() {

            @Override
            public EffectType<FreezeBuilder> getType() {
                return EffectType.FREEZE;
            }

            @Override
            public void play(Location location) {
                location.getWorld().getNearbyEntities(location, freezeBuilder.radius, freezeBuilder.radius, freezeBuilder.radius).forEach(entity -> {
                    entity.setFreezeTicks(entity.getFreezeTicks() + (int) (freezeBuilder.duration.toMillis() / 50));
                });
            }

            @Override
            public void play(Entity entity) {
                entity.setFreezeTicks(entity.getFreezeTicks() + (int) (freezeBuilder.duration.toMillis() / 50));
            }

            @Override
            public void deserialize(Map<String, String> data) throws ParseException {
                if (!data.containsKey("duration")) {
                    throw new IllegalArgumentException("Freeze duration must be set");
                }

                freezeBuilder.duration(DurationParser.deserializeSingular(data.get("duration")));
                freezeBuilder.radius(Double.parseDouble(data.getOrDefault("radius", "0")));
            }
        };
    }

    class EffectType<B> {
        private static final Map<String, EffectType<?>> EFFECT_TYPES = new HashMap<>();

        public static final EffectType<FireworkEffect.Builder> FIREWORK = new EffectType<>("firework", CustomEffect::firework);
        public static final EffectType<ParticleBuilder> PARTICLE = new EffectType<>("particle", CustomEffect::particle);
        public static final EffectType<FreezeBuilder> FREEZE = new EffectType<>("freeze", CustomEffect::freeze);

        private final Function<Consumer<B>, CustomEffect<B>> function;

        EffectType(String type, Function<Consumer<B>, CustomEffect<B>> function) {
            this.function = function;

            EFFECT_TYPES.put(type, this);
        }

        public CustomEffect<B> create(Consumer<B> builder) {
            return this.function.apply(builder);
        }

        public static EffectType<?> get(String type) {
            return EFFECT_TYPES.get(type);
        }
    }

    class ParticleBuilder {
        private Particle particle;
        private double speed;
        private int count;
        private Vector offset = new Vector(0, 0, 0);
        private Object data;

        public ParticleBuilder particle(Particle particle) {
            this.particle = particle;
            return this;
        }

        public ParticleBuilder speed(double speed) {
            this.speed = speed;
            return this;
        }

        public ParticleBuilder count(int count) {
            this.count = count;
            return this;
        }

        public ParticleBuilder offset(Vector offset) {
            this.offset = offset;
            return this;
        }

        public <T> ParticleBuilder data(T data) {
            if (this.particle == null) {
                throw new IllegalStateException("Particle must be set before setting data");
            }
            switch (this.particle) {
                case DUST -> {
                    if (!(data instanceof Particle.DustOptions)) {
                        throw new IllegalArgumentException("Data must be of type Particle.DustOptions for REDSTONE particles");
                    }
                }
                case ITEM -> {
                    if (!(data instanceof ItemStack)) {
                        throw new IllegalArgumentException("Data must be of type ItemStack for ITEM_CRACK particles");
                    }
                }
                case BLOCK, FALLING_DUST, BLOCK_MARKER -> {
                    if (!(data instanceof BlockData)) {
                        throw new IllegalArgumentException("Data must be of type BlockData for " + this.particle + " particles");
                    }
                }
                case DUST_COLOR_TRANSITION -> {
                    if (!(data instanceof Particle.DustTransition)) {
                        throw new IllegalArgumentException("Data must be of type Particle.DustTransition");
                    }
                }
                case VIBRATION -> {
                    if (!(data instanceof Vibration)) {
                        throw new IllegalArgumentException("Data must be of type Particle.Vibration");
                    }
                }
                case SCULK_CHARGE -> {
                    if (!(data instanceof Float)) {
                        throw new IllegalArgumentException("Data must be of type Float for SCULK_CHARGE particles");
                    }
                }
                case SHRIEK -> {
                    if (!(data instanceof Integer)) {
                        throw new IllegalArgumentException("Data must be of type Integer for SHRIEK particles");
                    }
                }
            }

            this.data = data;
            return this;
        }
    }

    class FreezeBuilder {
        private Duration duration;
        private double radius;

        public FreezeBuilder duration(Duration duration) {
            this.duration = duration;
            return this;
        }

        public FreezeBuilder radius(double radius) {
            this.radius = radius;
            return this;
        }
    }

    private static List<String> getList(String value) {
        return Arrays.asList(value.replace("[", "")
                .replace("]", "").split(","));
    }
}
