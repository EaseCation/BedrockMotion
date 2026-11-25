/*
 * This file is part of ViaBedrock - https://github.com/RaphiMC/ViaBedrock
 * Copyright (C) 2023-2025 RK_01/RaphiMC and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.easecation.bedrockmotion.mocha;

import team.unnamed.mocha.parser.MolangParser;
import team.unnamed.mocha.parser.ast.AccessExpression;
import team.unnamed.mocha.parser.ast.ArrayAccessExpression;
import team.unnamed.mocha.parser.ast.BinaryExpression;
import team.unnamed.mocha.parser.ast.CallExpression;
import team.unnamed.mocha.parser.ast.DoubleExpression;
import team.unnamed.mocha.parser.ast.ExecutionScopeExpression;
import team.unnamed.mocha.parser.ast.Expression;
import team.unnamed.mocha.parser.ast.IdentifierExpression;
import team.unnamed.mocha.parser.ast.StatementExpression;
import team.unnamed.mocha.parser.ast.StringExpression;
import team.unnamed.mocha.parser.ast.TernaryConditionalExpression;
import team.unnamed.mocha.parser.ast.UnaryExpression;
import team.unnamed.mocha.runtime.ExpressionInterpreter;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.NumberValue;
import team.unnamed.mocha.runtime.value.Function;
import team.unnamed.mocha.runtime.value.Value;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;

@SuppressWarnings("UnstableApiUsage")
public class MoLangEngine {
    private static final Scope STANDARD_SCOPE = standardScope();

    private static Scope standardScope() {
        Scope scope = Scope.create();
        scope.set("math", team.unnamed.mocha.runtime.binding.JavaObjectBinding.of(
                team.unnamed.mocha.runtime.standard.MochaMath.class, null,
                new team.unnamed.mocha.runtime.standard.MochaMath()));
        scope.readOnly(true);
        return scope;
    }

    /** Fresh host bindings over the immutable standard library; no shared mutable actor variables. */
    public static Scope newScope() {
        return new LayeredScope(STANDARD_SCOPE);
    }

    private static final Map<String, CompiledExpression> COMPILE_CACHE = new LinkedHashMap<>(16, .75f, true);
    private static final int MAX_CACHE_SIZE = 256;
    private static final long MAX_CACHE_WEIGHT = 16 * 1024 * 1024;
    private static long cacheWeight;
    // One reusable slot per nested evaluation. Callbacks can evaluate another expression without
    // rebasing the caller's scope or clearing its temporary variables.
    private static final ThreadLocal<EvaluationStack> EVALUATIONS = ThreadLocal.withInitial(EvaluationStack::new);

    public static Value eval(final Scope scope, final String expression) throws IOException {
        return eval(scope, MoLangEvaluationContext.EMPTY, expression);
    }

    public static Value eval(final Scope scope, final MoLangEvaluationContext context,
                             final String expression) throws IOException {
        if (expression == null || expression.isEmpty()) {
            return NumberValue.zero();
        }

        final NumberValue literal = numericLiteral(expression);
        if (literal != null) return literal;
        return eval(scope, context, compile(expression));
    }

    // Preserve the String evaluation fast path when a resource retains a compiled expression.
    // Number parsing belongs here, not in render-controller or animation consumers.
    private static NumberValue numericLiteral(String expression) {
        if (expression.isEmpty()) return null;
        final char first = expression.charAt(0);
        if ((first >= '0' && first <= '9') || first == '-' || first == '.') {
            try {
                return NumberValue.of(Double.parseDouble(expression));
            } catch (NumberFormatException ignored) {
                // Not a pure number, fall through to normal parse
            }
        }

        return null;
    }

    public static Value eval(final Scope scope, final List<Expression> expressions) {
        return eval(scope, MoLangEvaluationContext.EMPTY, expressions);
    }

    public static Value eval(final Scope scope, final MoLangEvaluationContext context,
                             final List<Expression> expressions) {
        return eval(scope, context, compile(expressions));
    }

    public static Value eval(final Scope scope, final CompiledExpression expression) {
        return eval(scope, MoLangEvaluationContext.EMPTY, expression);
    }

    public static Value eval(final Scope scope, final MoLangEvaluationContext context,
                             final CompiledExpression expression) {
        if (expression.literal != null) return expression.literal;
        return evalExpressions(scope, context, isActorAware(context) ? expression.actorAwareExpressions : expression.expressions);
    }

    private static boolean isActorAware(MoLangEvaluationContext context) {
        return context != null && (context.actor() instanceof MoLangEvaluationContext.Actor
                || context.ownerActor() instanceof MoLangEvaluationContext.Actor);
    }

    private static Value evalExpressions(final Scope scope, final MoLangEvaluationContext context, final List<Expression> expressions) {
        final EvaluationStack stack = EVALUATIONS.get();
        final EvaluationSlot slot = stack.acquire();
        try {
            slot.scope.rebase(scope);
            final ExpressionInterpreter<Object> evaluator = new ExpressionInterpreter<>(
                    Objects.requireNonNullElse(context, MoLangEvaluationContext.EMPTY).actor(), slot.scope);
            evaluator.warnOnReflectiveFunctionUsage(false);
            Value result = NumberValue.zero();
            for (Expression expression : expressions) {
                result = expression.visit(evaluator);
                Value returned = evaluator.popReturnValue();
                if (returned != null) return returned;
            }
            return result;
        } finally {
            slot.temp.resetTemp();
            slot.scope.rebase(STANDARD_SCOPE); // Do not retain actors/worlds in render-thread locals.
            stack.depth--;
        }
    }

    private static final class EvaluationSlot {
        final ResettableTempBinding temp = new ResettableTempBinding();
        final LayeredScope scope = newEvaluationScope(temp);
    }

    private static final class EvaluationStack {
        final ArrayList<EvaluationSlot> slots = new ArrayList<>();
        int depth;
        EvaluationSlot acquire() {
            if (depth >= 64) throw new IllegalStateException("Molang host callback nesting exceeds 64");
            if (depth == slots.size()) slots.add(new EvaluationSlot());
            return slots.get(depth++);
        }
    }

    private static LayeredScope newEvaluationScope(ResettableTempBinding tempBinding) {
        final LayeredScope localScope = new LayeredScope(STANDARD_SCOPE);
        localScope.set("temp", tempBinding);
        localScope.set("t", tempBinding);
        localScope.set("__actor_variable", (Function<Object>) (execution, arguments) -> {
            final Value name = arguments.next().eval();
            if (execution.entity() instanceof MoLangEvaluationContext.Actor actor) {
                final Value value = actor.variable(name.getAsString());
                return value == null ? NumberValue.zero() : value;
            }
            return NumberValue.zero();
        });
        localScope.readOnly(true);
        return localScope;
    }

    /**
     * A reusable {@code temp}/{@code t} binding for {@link #eval(Scope, List)}. {@link MutableObjectBinding}
     * exposes no clear(), so we record the keys a script writes and remove them after each eval, reusing the
     * binding (and its backing map) across calls instead of allocating a fresh one every time.
     */
    private static final class ResettableTempBinding extends MutableObjectBinding {
        private final java.util.ArrayList<String> writtenKeys = new java.util.ArrayList<>(4);

        @Override
        public boolean set(final String name, final Value value) {
            final boolean result = super.set(name, value);
            if (result && value != null) {
                writtenKeys.add(name);
            }
            return result;
        }

        void resetTemp() {
            for (int i = 0, n = writtenKeys.size(); i < n; i++) {
                super.set(writtenKeys.get(i), null);
            }
            writtenKeys.clear();
        }
    }

    public static List<Expression> parse(final String expression) throws IOException {
        return compile(expression).copyExpressions();
    }

    /**
     * Parses an owned AST during resource preparation. Host values are read at execution;
     * the cache retains neither scopes nor actors.
     */
    public static synchronized CompiledExpression compile(final String expression) throws IOException {
        final CompiledExpression cached = COMPILE_CACHE.get(expression);
        if (cached != null) return cached;
        if (expression.length() > 262144) throw new IllegalArgumentException("Molang source exceeds 256K character budget");
        final CompiledExpression compiled;
        try (final StringReader reader = new StringReader(expression)) {
            compiled = new CompiledExpression(parse(reader));
        } catch (StackOverflowError error) {
            throw new IllegalArgumentException("Molang parser nesting budget exceeded", error);
        }
        long weight = compiled.cacheWeight() + expression.length() * 2L;
        if (weight <= MAX_CACHE_WEIGHT) {
            while (!COMPILE_CACHE.isEmpty() && (COMPILE_CACHE.size() >= MAX_CACHE_SIZE || cacheWeight + weight > MAX_CACHE_WEIGHT)) {
                var iterator = COMPILE_CACHE.entrySet().iterator();
                var old = iterator.next();
                cacheWeight -= old.getValue().cacheWeight() + old.getKey().length() * 2L;
                iterator.remove();
            }
            COMPILE_CACHE.put(expression, compiled);
            cacheWeight += weight;
        }
        return compiled;
    }

    /** Retains the literal fast path of eval(String) without changing parse/compile AST shape. */
    public static CompiledExpression compileForEvaluation(final String expression) throws IOException {
        final NumberValue literal = numericLiteral(expression);
        return literal == null ? compile(expression)
                : new CompiledExpression(List.of(new DoubleExpression(literal.getAsNumber())));
    }

    /** Creates an opaque compiled expression detached from a caller-owned AST. */
    public static CompiledExpression compile(final List<Expression> expressions) {
        return new CompiledExpression(copyExpressions(expressions));
    }

    public static List<Expression> parse(final Reader reader) throws IOException {
        return MolangParser.parser(reader).parseAll();
    }

    public static final class CompiledExpression {
        private final List<Expression> expressions;
        private final List<Expression> actorAwareExpressions;
        private final NumberValue literal;

        private CompiledExpression(final List<Expression> expressions) {
            // Postfix precedence belongs to the Mocha parser. Keep only a detached copy here;
            // the consumer must not rewrite parser output to compensate for parser behavior.
            this.expressions = List.copyOf(MoLangEngine.copyExpressions(expressions));
            this.literal = this.expressions.size() == 1 && this.expressions.getFirst() instanceof DoubleExpression number
                    ? NumberValue.of(number.value()) : null;
            this.actorAwareExpressions = literal == null ? List.copyOf(actorAwareExpressions(this.expressions)) : this.expressions;
        }

        private long cacheWeight() {
            return 256L + expressions.toString().length() * 8L;
        }

        /** Installation-time constant query; never evaluates actor bindings or impure calls.
         * Uses the interpreter's own constant analysis instead of a consumer expression parser.
         */
        public java.util.OptionalDouble constantNumber() {
            for (Expression expression : expressions) {
                if (!team.unnamed.mocha.runtime.IsConstantExpression.test(expression)) {
                    return java.util.OptionalDouble.empty();
                }
            }
            return java.util.OptionalDouble.of(MoLangEngine.eval(Scope.create(), this).getAsNumber());
        }

        /** Returns a fully detached tree because Mocha's AST nodes and child lists are mutable. */
        public List<Expression> copyExpressions() {
            return MoLangEngine.copyExpressions(this.expressions);
        }

        @Override
        public boolean equals(final Object object) {
            if (this == object) return true;
            if (!(object instanceof CompiledExpression that)) return false;
            return this.expressions.equals(that.expressions);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.expressions);
        }

        @Override
        public String toString() {
            return this.expressions.toString();
        }
    }

    private static List<Expression> copyExpressions(final List<Expression> expressions) {
        final List<Expression> copy = new ArrayList<>(expressions.size());
        for (Expression expression : expressions) {
            copy.add(copyExpression(expression));
        }
        return copy;
    }

    private static List<Expression> actorAwareExpressions(final List<Expression> expressions) {
        final List<Expression> transformed = new ArrayList<>(expressions.size());
        for (Expression expression : expressions) {
            transformed.add(actorAwareExpression(expression, false));
        }
        return transformed;
    }

    private static Expression actorAwareExpression(final Expression expression, final boolean assignmentTarget) {
        if (expression instanceof AccessExpression access) {
            if (!assignmentTarget && access.object() instanceof IdentifierExpression identifier
                    && (identifier.name().equalsIgnoreCase("variable") || identifier.name().equalsIgnoreCase("v"))) {
                return new CallExpression(new IdentifierExpression("__actor_variable"),
                        List.of(new StringExpression(access.property())));
            }
            return new AccessExpression(actorAwareExpression(access.object(), assignmentTarget), access.property());
        }
        if (expression instanceof ArrayAccessExpression arrayAccess) {
            return new ArrayAccessExpression(actorAwareExpression(arrayAccess.array(), false),
                    actorAwareExpression(arrayAccess.index(), false));
        }
        if (expression instanceof BinaryExpression binary) {
            final boolean assign = binary.op() == BinaryExpression.Op.ASSIGN;
            return new BinaryExpression(binary.op(), actorAwareExpression(binary.left(), assign),
                    actorAwareExpression(binary.right(), false));
        }
        if (expression instanceof CallExpression call) {
            return new CallExpression(actorAwareExpression(call.function(), false),
                    actorAwareExpressions(call.arguments()));
        }
        if (expression instanceof ExecutionScopeExpression executionScope) {
            return new ExecutionScopeExpression(actorAwareExpressions(executionScope.expressions()));
        }
        if (expression instanceof TernaryConditionalExpression ternary) {
            return new TernaryConditionalExpression(actorAwareExpression(ternary.condition(), false),
                    actorAwareExpression(ternary.trueExpression(), false),
                    actorAwareExpression(ternary.falseExpression(), false));
        }
        if (expression instanceof UnaryExpression unary) {
            return new UnaryExpression(unary.op(), actorAwareExpression(unary.expression(), false));
        }
        return copyExpression(expression);
    }

    private static Expression copyExpression(final Expression expression) {
        if (expression instanceof AccessExpression access) {
            return new AccessExpression(copyExpression(access.object()), access.property());
        }
        if (expression instanceof ArrayAccessExpression arrayAccess) {
            return new ArrayAccessExpression(
                    copyExpression(arrayAccess.array()), copyExpression(arrayAccess.index()));
        }
        if (expression instanceof BinaryExpression binary) {
            return new BinaryExpression(
                    binary.op(), copyExpression(binary.left()), copyExpression(binary.right()));
        }
        if (expression instanceof CallExpression call) {
            return new CallExpression(copyExpression(call.function()), copyExpressions(call.arguments()));
        }
        if (expression instanceof DoubleExpression number) {
            return new DoubleExpression(number.value());
        }
        if (expression instanceof ExecutionScopeExpression executionScope) {
            return new ExecutionScopeExpression(copyExpressions(executionScope.expressions()));
        }
        if (expression instanceof IdentifierExpression identifier) {
            return new IdentifierExpression(identifier.name());
        }
        if (expression instanceof StatementExpression statement) {
            return new StatementExpression(statement.op());
        }
        if (expression instanceof StringExpression string) {
            return new StringExpression(string.value());
        }
        if (expression instanceof TernaryConditionalExpression ternary) {
            return new TernaryConditionalExpression(
                    copyExpression(ternary.condition()),
                    copyExpression(ternary.trueExpression()),
                    copyExpression(ternary.falseExpression()));
        }
        if (expression instanceof UnaryExpression unary) {
            return new UnaryExpression(unary.op(), copyExpression(unary.expression()));
        }
        throw new IllegalArgumentException("Unsupported Mocha expression type: " + expression.getClass().getName());
    }
}
