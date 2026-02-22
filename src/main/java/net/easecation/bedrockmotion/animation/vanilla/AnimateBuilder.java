package net.easecation.bedrockmotion.animation.vanilla;

import net.easecation.bedrockmotion.animation.Animation;
import net.easecation.bedrockmotion.animation.element.Cube;
import net.easecation.bedrockmotion.animation.element.timestamp.ComplexTimeStamp;
import net.easecation.bedrockmotion.animation.element.timestamp.SimpleTimeStamp;
import net.easecation.bedrockmotion.util.mojangweirdformat.ValueOrValue;

import java.util.*;

import static net.easecation.bedrockmotion.animation.vanilla.AnimateTransformation.Targets.*;
import static net.easecation.bedrockmotion.animation.vanilla.AnimateTransformation.*;

public class AnimateBuilder {
    public static VBUAnimation build(final Animation animation) {
        float length = animation.getAnimationLength();
        if (length == -1) {
            float largestTimeStamp = 0;
            for (Cube cube : animation.getCubes()) {
                if (cube.getPosition() != null) {
                    if (cube.getPosition().getValue() instanceof TreeMap<?, ?> rawMap) {
                        @SuppressWarnings("unchecked")
                        final TreeMap<Float, ValueOrValue<?>> map = (TreeMap<Float, ValueOrValue<?>>) rawMap;
                        for (Float v : map.keySet()) {
                            if (largestTimeStamp < v) {
                                largestTimeStamp = v;
                            }
                        }
                    }
                }

                if (cube.getRotation() != null) {
                    if (cube.getRotation().getValue() instanceof TreeMap<?, ?> rawMap) {
                        @SuppressWarnings("unchecked")
                        final TreeMap<Float, ValueOrValue<?>> map = (TreeMap<Float, ValueOrValue<?>>) rawMap;
                        for (Float v : map.keySet()) {
                            if (largestTimeStamp < v) {
                                largestTimeStamp = v;
                            }
                        }
                    }
                }

                if (cube.getScale() != null) {
                    if (cube.getScale().getValue() instanceof TreeMap<?, ?> rawMap) {
                        @SuppressWarnings("unchecked")
                        final TreeMap<Float, ValueOrValue<?>> map = (TreeMap<Float, ValueOrValue<?>>) rawMap;
                        for (Float v : map.keySet()) {
                            if (largestTimeStamp < v) {
                                largestTimeStamp = v;
                            }
                        }
                    }
                }
            }

            if (largestTimeStamp > 0) {
                largestTimeStamp += 0.01F;
            }
            length = largestTimeStamp;
        }

        final VBUAnimation.Builder builder = VBUAnimation.Builder.create(length);
        for (Cube cube : animation.getCubes()) {
            if (cube.getPosition() != null) {
                build(builder, cube.getIdentifier(), OFFSET, cube.getPosition().getValue());
            }
            if (cube.getRotation() != null) {
                build(builder, cube.getIdentifier(), ROTATE, cube.getRotation().getValue());
            }
            if (cube.getScale() != null) {
                build(builder, cube.getIdentifier(), SCALE, cube.getScale().getValue());
            }
        }

        if (animation.getLoop().getValue().equals(true)) {
            builder.looping();
        }

        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static void build(final VBUAnimation.Builder builder, final String name, final Target target, final Object object) {
        if (object instanceof TreeMap<?, ?> rawMap) {
            if (rawMap.isEmpty()) {
                return;
            }

            final TreeMap<Float, ValueOrValue<?>> map = (TreeMap<Float, ValueOrValue<?>>) rawMap;
            List<VBUKeyFrame> frameList = new ArrayList<>();

            for (Map.Entry<Float, ValueOrValue<?>> entry : map.entrySet()) {
                float timestamp = entry.getKey();

                if (entry.getValue().getValue() instanceof SimpleTimeStamp simple) {
                    frameList.add(new VBUKeyFrame(timestamp, simple.value(), Interpolations.CUBIC));
                } else if (entry.getValue().getValue() instanceof ComplexTimeStamp complex) {
                    Interpolation interpolation;
                    switch (complex.lerpMode().toLowerCase(Locale.ROOT)) {
                        case "catmullrom" -> interpolation = Interpolations.CUBIC;
                        case "step" -> interpolation = Interpolations.STEP;
                        default -> interpolation = Interpolations.LINEAR;
                    }

                    String[] pre = complex.pre();
                    String[] post = complex.post();
                    boolean hasSeparate = (pre != null && post != null);

                    if (pre == null) pre = post;
                    if (post == null) post = pre;
                    if (pre == null) continue;

                    frameList.add(new VBUKeyFrame(timestamp, pre, post, hasSeparate, interpolation));
                }
            }

            if (frameList.isEmpty()) {
                return;
            }

            builder.addBoneAnimation(name, new AnimateTransformation(target, frameList.toArray(new VBUKeyFrame[0])));
        } else {
            builder.addBoneAnimation(name, new AnimateTransformation(target, new VBUKeyFrame[] {new VBUKeyFrame(0, get(object), Interpolations.CUBIC)}));
        }
    }

    private static String[] get(final Object object) {
        if (object instanceof String[] array) {
            return array;
        } else if (object instanceof Float pos) {
            return new String[] {String.valueOf(pos), String.valueOf(pos), String.valueOf(pos)};
        } else if (object instanceof String pos) {
            return new String[] {pos, pos, pos};
        }

        return new String[] {"0", "0", "0"};
    }
}
