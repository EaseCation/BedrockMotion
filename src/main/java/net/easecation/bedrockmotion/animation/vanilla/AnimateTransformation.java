package net.easecation.bedrockmotion.animation.vanilla;

import net.easecation.bedrockmotion.model.IBoneTarget;
import net.easecation.bedrockmotion.mocha.MoLangEngine;
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

// Taken from vanilla Transformation, adapted for IBoneTarget.
public record AnimateTransformation(Target target, VBUKeyFrame[] keyframes) {
    // Static temp vectors for interpolation (single-threaded usage assumed)
    private static final Vector3f TEMP_V0 = new Vector3f();
    private static final Vector3f TEMP_V1 = new Vector3f();
    private static final Vector3f TEMP_V2 = new Vector3f();
    private static final Vector3f TEMP_V3 = new Vector3f();

    /**
     * A keyframe component (x/y/z) resolved once at build time: either a constant double (the common
     * case - literal numbers like "0", "30.5"), or a cached MoLang AST for genuine expressions. This
     * lets per-frame evaluation read the constant directly (zero work, zero allocation) or evaluate the
     * pre-parsed AST, instead of re-parsing the source string (and re-running Double.parseDouble) every
     * frame for every bone/transform/keyframe - which dominated render-thread allocation in dense scenes.
     */
    public record ResolvedComponent(boolean constant, double value, List<Expression> expr, String queryVar) {
        public static final ResolvedComponent ZERO = new ResolvedComponent(true, 0.0, null, null);

        public static ResolvedComponent of(final String source) {
            if (source == null || source.isEmpty()) {
                return ZERO;
            }
            // Mirror MoLangEngine's numeric fast-path, but resolve it once instead of every eval.
            final char first = source.charAt(0);
            if ((first >= '0' && first <= '9') || first == '-' || first == '.') {
                try {
                    return new ResolvedComponent(true, Double.parseDouble(source), null, null);
                } catch (NumberFormatException ignored) {
                    // not a pure number, fall through to AST
                }
            }
            try {
                final List<Expression> ast = MoLangEngine.parse(source);
                // Fast-path: a bare single query-variable access (e.g. "query.anim_time") is the most
                // common non-constant keyframe. Reading it directly from the scope's query binding skips
                // the whole ExpressionInterpreter (and its per-eval NumberValue/ObjectProperty allocations)
                // — a major render-thread allocation source in dense entity scenes.
                final String qv = extractSingleQueryVar(ast);
                return new ResolvedComponent(false, 0.0, ast, qv);
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
        public static final Interpolation LINEAR = (scope, dest, delta, keyframes, start, end, scale) -> {
            eval(scope, keyframes[start].post(), TEMP_V1);
            eval(scope, keyframes[end].pre(), TEMP_V2);
            return TEMP_V1.lerp(TEMP_V2, delta, dest).mul(scale);
        };
        public static final Interpolation STEP = (scope, dest, delta, keyframes, start, end, scale) -> {
            eval(scope, keyframes[start].post(), dest);
            dest.mul(scale);
            return dest;
        };
        public static final Interpolation CUBIC = (scope, dest, delta, keyframes, start, end, scale) -> {
            // Control point availability (Blockbench: skip before_plus/after_plus if neighbor has separate pre/post)
            boolean hasBefore = start > 0 && !keyframes[start].hasSeparatePrePost();
            boolean hasAfter = end < keyframes.length - 1 && !keyframes[end].hasSeparatePrePost();

            eval(scope, keyframes[start].post(), TEMP_V1);
            eval(scope, keyframes[end].pre(), TEMP_V2);
            if (hasBefore) {
                eval(scope, keyframes[start - 1].post(), TEMP_V0);
            } else {
                TEMP_V0.set(TEMP_V1);
            }
            if (hasAfter) {
                eval(scope, keyframes[end + 1].pre(), TEMP_V3);
            } else {
                TEMP_V3.set(TEMP_V2);
            }

            dest.set(
                    MathUtil.catmullRom(delta, TEMP_V0.x(), TEMP_V1.x(), TEMP_V2.x(), TEMP_V3.x()) * scale,
                    MathUtil.catmullRom(delta, TEMP_V0.y(), TEMP_V1.y(), TEMP_V2.y(), TEMP_V3.y()) * scale,
                    MathUtil.catmullRom(delta, TEMP_V0.z(), TEMP_V1.z(), TEMP_V2.z(), TEMP_V3.z()) * scale
            );
            return dest;
        };
    }

    private static void eval(Scope scope, ResolvedComponent[] xyz, Vector3f dest) {
        dest.set(evalComponent(scope, xyz[0]), evalComponent(scope, xyz[1]), evalComponent(scope, xyz[2]));
    }

    private static float evalComponent(Scope scope, ResolvedComponent component) {
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
            return (float) MoLangEngine.eval(scope, component.expr()).getAsNumber();
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
    }
}
