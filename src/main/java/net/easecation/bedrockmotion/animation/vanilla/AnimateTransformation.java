package net.easecation.bedrockmotion.animation.vanilla;

import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.mocha.MoLangEngine;
import net.easecation.bedrockmotion.mocha.MoLangEvaluationContext;
import net.easecation.bedrockmotion.util.MathUtil;
import org.joml.Vector3f;
import team.unnamed.mocha.parser.ast.AccessExpression;
import team.unnamed.mocha.parser.ast.Expression;
import team.unnamed.mocha.parser.ast.IdentifierExpression;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.ObjectProperty;
import team.unnamed.mocha.runtime.value.ObjectValue;
import team.unnamed.mocha.runtime.value.Value;

import java.util.List;
import java.util.Objects;

// Taken from vanilla Transformation, adapted for IBoneTarget.
public final class AnimateTransformation {
    private static final ThreadLocal<InterpolationScratch> INTERPOLATION_SCRATCH =
            ThreadLocal.withInitial(InterpolationScratch::new);
    private final Target target;
    private final VBUKeyFrame[] keyframes;
    private final boolean immutable;

    public AnimateTransformation(final Target target, final VBUKeyFrame[] keyframes) {
        this(target, keyframes, false);
    }

    private AnimateTransformation(final Target target, final VBUKeyFrame[] keyframes,
                                  final boolean immutable) {
        this.target = target;
        this.keyframes = keyframes;
        this.immutable = immutable;
    }

    public Target target() {
        return this.target;
    }

    public VBUKeyFrame[] keyframes() {
        return this.immutable ? this.keyframes.clone() : this.keyframes;
    }

    VBUKeyFrame[] keyframesInternal() {
        return this.keyframes;
    }

    public AnimateTransformation immutableCopy() {
        if (this.immutable) return this;
        final VBUKeyFrame[] copy = new VBUKeyFrame[this.keyframes.length];
        for (int i = 0; i < copy.length; i++) {
            copy[i] = this.keyframes[i].immutableCopy();
        }
        return new AnimateTransformation(this.target, copy, true);
    }

    /**
     * A keyframe component (x/y/z) resolved once at build time: either a constant double (the common
     * case - literal numbers like "0", "30.5"), or a cached MoLang AST for genuine expressions. This
     * lets per-frame evaluation read the constant directly (zero work, zero allocation) or evaluate the
     * pre-parsed AST, instead of re-parsing the source string (and re-running Double.parseDouble) every
     * frame for every bone/transform/keyframe - which dominated render-thread allocation in dense scenes.
     */
    public static final class ResolvedComponent {
        public static final ResolvedComponent ZERO = new ResolvedComponent(
                true, 0.0, (MoLangEngine.CompiledExpression) null, null);

        private final boolean constant;
        private final double value;
        private final MoLangEngine.CompiledExpression expression;
        private final String queryVar;

        public ResolvedComponent(final boolean constant, final double value,
                                 final List<Expression> expression, final String queryVar) {
            this(constant, value,
                    expression == null ? null : MoLangEngine.compile(expression), queryVar);
        }

        private ResolvedComponent(final boolean constant, final double value,
                                  final MoLangEngine.CompiledExpression expression,
                                  final String queryVar) {
            this.constant = constant;
            this.value = value;
            this.expression = expression;
            this.queryVar = queryVar;
        }

        public boolean constant() {
            return this.constant;
        }

        public double value() {
            return this.value;
        }

        /** Returns a detached AST; animation ticks use {@link #exprInternal()} without copying. */
        public List<Expression> expr() {
            return this.expression == null ? null : this.expression.copyExpressions();
        }

        public String queryVar() {
            return this.queryVar;
        }

        MoLangEngine.CompiledExpression exprInternal() {
            return this.expression;
        }

        public static ResolvedComponent of(final String source) {
            if (source == null || source.isEmpty()) {
                return ZERO;
            }
            // Mirror MoLangEngine's numeric fast-path, but resolve it once instead of every eval.
            final char first = source.charAt(0);
            if ((first >= '0' && first <= '9') || first == '-' || first == '.') {
                try {
                    return new ResolvedComponent(
                            true, Double.parseDouble(source), (MoLangEngine.CompiledExpression) null, null);
                } catch (NumberFormatException ignored) {
                    // not a pure number, fall through to AST
                }
            }
            try {
                final MoLangEngine.CompiledExpression compiled = MoLangEngine.compile(source);
                final List<Expression> ast = compiled.copyExpressions();
                // Fast-path: a bare single query-variable access (e.g. "query.anim_time") is the most
                // common non-constant keyframe. Reading it directly from the scope's query binding skips
                // the whole ExpressionInterpreter (and its per-eval NumberValue/ObjectProperty allocations)
                // — a major render-thread allocation source in dense entity scenes.
                final String qv = extractSingleQueryVar(ast);
                return new ResolvedComponent(false, 0.0, compiled, qv);
            } catch (Exception e) {
                return ZERO;
            }
        }

        /** If the AST is exactly one {@code query.xxx}/{@code q.xxx} access, return the property name; else null. */
        private static String extractSingleQueryVar(final List<Expression> ast) {
            if (ast == null || ast.size() != 1) {
                return null;
            }
            final Expression e = ast.get(0);
            if (e instanceof AccessExpression access
                    && access.object() instanceof IdentifierExpression id) {
                final String n = id.name();
                if (("query".equals(n) || "q".equals(n)) && access.property() != null && !access.property().isEmpty()) {
                    return access.property();
                }
            }
            return null;
        }

        @Override
        public boolean equals(final Object object) {
            if (this == object) return true;
            if (!(object instanceof ResolvedComponent that)) return false;
            return this.constant == that.constant
                    && Double.compare(this.value, that.value) == 0
                    && Objects.equals(this.expression, that.expression)
                    && Objects.equals(this.queryVar, that.queryVar);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.constant, this.value, this.expression, this.queryVar);
        }

        @Override
        public String toString() {
            return "ResolvedComponent[constant=" + this.constant + ", value=" + this.value
                    + ", expr=" + this.expression + ", queryVar=" + this.queryVar + ']';
        }
    }

    /** Resolve an xyz source array into per-component constants/ASTs once at build time. */
    public static ResolvedComponent[] resolve(final String[] xyz) {
        if (xyz == null) {
            return new ResolvedComponent[]{ResolvedComponent.ZERO, ResolvedComponent.ZERO, ResolvedComponent.ZERO};
        }
        final ResolvedComponent[] out = new ResolvedComponent[xyz.length];
        for (int i = 0; i < xyz.length; i++) {
            out[i] = ResolvedComponent.of(xyz[i]);
        }
        return out;
    }

    public static class Interpolations {
        public static final Interpolation LINEAR = contextual((scope, context, dest, delta, keyframes, start, end, scale) -> {
            final InterpolationScratch scratch = INTERPOLATION_SCRATCH.get();
            eval(scope, context, keyframes[start].postInternal(), scratch.v1);
            eval(scope, context, keyframes[end].preInternal(), scratch.v2);
            return scratch.v1.lerp(scratch.v2, delta, dest).mul(scale);
        });
        public static final Interpolation STEP = contextual((scope, context, dest, delta, keyframes, start, end, scale) -> {
            eval(scope, context, keyframes[start].postInternal(), dest);
            dest.mul(scale);
            return dest;
        });
        public static final Interpolation CUBIC = contextual((scope, context, dest, delta, keyframes, start, end, scale) -> {
            final InterpolationScratch scratch = INTERPOLATION_SCRATCH.get();
            // Control point availability (Blockbench: skip before_plus/after_plus if neighbor has separate pre/post)
            boolean hasBefore = start > 0 && !keyframes[start].hasSeparatePrePost();
            boolean hasAfter = end < keyframes.length - 1 && !keyframes[end].hasSeparatePrePost();

            eval(scope, context, keyframes[start].postInternal(), scratch.v1);
            eval(scope, context, keyframes[end].preInternal(), scratch.v2);
            if (hasBefore) {
                eval(scope, context, keyframes[start - 1].postInternal(), scratch.v0);
            } else {
                scratch.v0.set(scratch.v1);
            }
            if (hasAfter) {
                eval(scope, context, keyframes[end + 1].preInternal(), scratch.v3);
            } else {
                scratch.v3.set(scratch.v2);
            }

            dest.set(
                    MathUtil.catmullRom(delta, scratch.v0.x(), scratch.v1.x(), scratch.v2.x(), scratch.v3.x()) * scale,
                    MathUtil.catmullRom(delta, scratch.v0.y(), scratch.v1.y(), scratch.v2.y(), scratch.v3.y()) * scale,
                    MathUtil.catmullRom(delta, scratch.v0.z(), scratch.v1.z(), scratch.v2.z(), scratch.v3.z()) * scale
            );
            return dest;
        });

        private static Interpolation contextual(ContextualInterpolation interpolation) {
            return interpolation;
        }
    }

    private static final class InterpolationScratch {
        private final Vector3f v0 = new Vector3f();
        private final Vector3f v1 = new Vector3f();
        private final Vector3f v2 = new Vector3f();
        private final Vector3f v3 = new Vector3f();
    }

    private static void eval(Scope scope, MoLangEvaluationContext context,
                             ResolvedComponent[] xyz, Vector3f dest) {
        dest.set(evalComponent(scope, context, xyz[0]), evalComponent(scope, context, xyz[1]),
                evalComponent(scope, context, xyz[2]));
    }

    private static float evalComponent(Scope scope, MoLangEvaluationContext context,
                                       ResolvedComponent component) {
        if (component.constant()) {
            return (float) component.value();
        }
        if (component.queryVar() != null) {
            // Single query-variable fast-path: read directly from the scope's query binding instead of
            // spinning up an ExpressionInterpreter (which allocates NumberValue/ObjectProperty per eval).
            // Any failure falls through to the full eval below as a safety net.
            try {
                final Value query = scope.getProperty("query").value();
                if (query instanceof ObjectValue ov) {
                    final ObjectProperty prop = ov.getProperty(component.queryVar());
                    if (prop != null) {
                        return (float) prop.value().getAsNumber();
                    }
                }
            } catch (Exception ignored) {
                // fall through to full eval
            }
        }
        try {
            return (float) MoLangEngine.eval(scope, context, component.exprInternal()).getAsNumber();
        } catch (Exception e) {
            return 0.0F;
        }
    }

    public static class Targets {
        public static final Target OFFSET = (bone, vec3, weight) -> bone.addOffset(vec3);
        public static final Target ROTATE = (bone, vec3, weight) -> bone.addRotation(vec3);
        public static final Target SCALE = (bone, vec3, weight) -> {
            // Additive scale relative to 1.0: final = 1.0 + sum((anim_scale - 1.0) * weight)
            // Interpolation already computed vec3 = interpolated_value * weight,
            // so (interpolated_value - 1.0) * weight = vec3 - weight
            if (weight > 0) {
                bone.addScale(vec3.x - weight, vec3.y - weight, vec3.z - weight);
            }
        };
    }

    public interface Target {
        void apply(IBoneTarget bone, Vector3f vec3, float weight);
    }

    public interface Interpolation {
        Vector3f apply(Scope scope, Vector3f var1, float var2, VBUKeyFrame[] var3, int var4, int var5, float var6);

        default Vector3f apply(Scope scope, MoLangEvaluationContext context, Vector3f dest, float delta,
                               VBUKeyFrame[] keyframes, int start, int end, float scale) {
            return apply(scope, dest, delta, keyframes, start, end, scale);
        }
    }

    @FunctionalInterface
    private interface ContextualInterpolation extends Interpolation {
        Vector3f applyContext(Scope scope, MoLangEvaluationContext context, Vector3f dest, float delta,
                              VBUKeyFrame[] keyframes, int start, int end, float scale);

        @Override
        default Vector3f apply(Scope scope, Vector3f dest, float delta,
                               VBUKeyFrame[] keyframes, int start, int end, float scale) {
            return applyContext(scope, MoLangEvaluationContext.EMPTY, dest, delta,
                    keyframes, start, end, scale);
        }

        @Override
        default Vector3f apply(Scope scope, MoLangEvaluationContext context, Vector3f dest, float delta,
                               VBUKeyFrame[] keyframes, int start, int end, float scale) {
            return applyContext(scope, context, dest, delta, keyframes, start, end, scale);
        }
    }
}
