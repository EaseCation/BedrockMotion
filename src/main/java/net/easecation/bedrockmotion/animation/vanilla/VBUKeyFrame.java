package net.easecation.bedrockmotion.animation.vanilla;

import net.easecation.bedrockmotion.animation.vanilla.AnimateTransformation.ResolvedComponent;

public final class VBUKeyFrame {
    private final float timestamp;
    private final String[] preTarget;
    private final String[] postTarget;
    private final boolean hasSeparatePrePost;
    private final AnimateTransformation.Interpolation interpolation;
    // Pre-resolved xyz components avoid reparsing MoLang source strings on every animation frame.
    private final ResolvedComponent[] pre;
    private final ResolvedComponent[] post;
    private final boolean immutable;

    public VBUKeyFrame(final float timestamp, final String[] preTarget, final String[] postTarget,
                       final boolean hasSeparatePrePost,
                       final AnimateTransformation.Interpolation interpolation,
                       final ResolvedComponent[] pre, final ResolvedComponent[] post) {
        this(timestamp, preTarget, postTarget, hasSeparatePrePost, interpolation, pre, post, false);
    }

    private VBUKeyFrame(final float timestamp, final String[] preTarget, final String[] postTarget,
                        final boolean hasSeparatePrePost,
                        final AnimateTransformation.Interpolation interpolation,
                        final ResolvedComponent[] pre, final ResolvedComponent[] post,
                        final boolean immutable) {
        this.timestamp = timestamp;
        this.preTarget = preTarget;
        this.postTarget = postTarget;
        // An explicit but equal constant pre/post pair is continuous. Treating its JSON
        // spelling as a jump clamps Catmull neighbours and changes native author curves.
        // Do not compare sampled values: expressions may vary by actor or have side effects.
        this.hasSeparatePrePost = hasSeparatePrePost && !sameConstants(pre, post);
        this.interpolation = interpolation;
        this.pre = pre;
        this.post = post;
        this.immutable = immutable;
    }

    public VBUKeyFrame(final float timestamp, final String[] preTarget, final String[] postTarget,
                       final boolean hasSeparatePrePost,
                       final AnimateTransformation.Interpolation interpolation) {
        this(timestamp, preTarget, postTarget, hasSeparatePrePost, interpolation,
                AnimateTransformation.resolve(preTarget), AnimateTransformation.resolve(postTarget));
    }

    private static boolean sameConstants(ResolvedComponent[] pre, ResolvedComponent[] post) {
        if (pre == null || post == null || pre.length != post.length) return false;
        for (int i = 0; i < pre.length; i++) {
            var before = pre[i].constant() ? java.util.OptionalDouble.of(pre[i].value())
                    : pre[i].exprInternal().constantNumber();
            var after = post[i].constant() ? java.util.OptionalDouble.of(post[i].value())
                    : post[i].exprInternal().constantNumber();
            if (before.isEmpty() || after.isEmpty() || before.getAsDouble() != after.getAsDouble()) return false;
        }
        return true;
    }

    public VBUKeyFrame(final float timestamp, final String[] value,
                       final AnimateTransformation.Interpolation interpolation) {
        this(timestamp, value, value, false, interpolation);
    }

    public float timestamp() {
        return this.timestamp;
    }

    public String[] preTarget() {
        return this.immutable && this.preTarget != null ? this.preTarget.clone() : this.preTarget;
    }

    public String[] postTarget() {
        return this.immutable && this.postTarget != null ? this.postTarget.clone() : this.postTarget;
    }

    public boolean hasSeparatePrePost() {
        return this.hasSeparatePrePost;
    }

    public AnimateTransformation.Interpolation interpolation() {
        return this.interpolation;
    }

    public ResolvedComponent[] pre() {
        return this.immutable && this.pre != null ? this.pre.clone() : this.pre;
    }

    public ResolvedComponent[] post() {
        return this.immutable && this.post != null ? this.post.clone() : this.post;
    }

    ResolvedComponent[] preInternal() {
        return this.pre;
    }

    ResolvedComponent[] postInternal() {
        return this.post;
    }

    VBUKeyFrame immutableCopy() {
        if (this.immutable) return this;
        return new VBUKeyFrame(
                this.timestamp,
                this.preTarget == null ? null : this.preTarget.clone(),
                this.postTarget == null ? null : this.postTarget.clone(),
                this.hasSeparatePrePost,
                this.interpolation,
                freeze(this.pre),
                freeze(this.post),
                true);
    }

    private static ResolvedComponent[] freeze(final ResolvedComponent[] source) {
        if (source == null) return null;
        final ResolvedComponent[] copy = new ResolvedComponent[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = source[i];
        }
        return copy;
    }
}
