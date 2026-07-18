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
import team.unnamed.mocha.runtime.value.Value;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("UnstableApiUsage")
public class MoLangEngine {
    private static final ConcurrentHashMap<String, CompiledExpression> PARSE_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 4096;
    private static final ThreadLocal<LayeredScope> EVAL_SCOPE =
            ThreadLocal.withInitial(() -> new LayeredScope(Scope.create()));
    // Reused per thread instead of allocating a new MutableObjectBinding (and its backing
    // CaseInsensitiveStringHashMap) on every eval(List) call - that allocation dominated render-thread
    // GC pressure once keyframe pre-resolution removed the parse churn. MutableObjectBinding has no
    // clear() and is package-final on its map, so this subclass tracks written temp keys and removes
    // them after each eval, keeping the (empty in the common case) map allocated and reused.
    private static final ThreadLocal<ResettableTempBinding> EVAL_TEMP =
            ThreadLocal.withInitial(ResettableTempBinding::new);

    public static Value eval(final Scope scope, final String expression) throws IOException {
        if (expression == null || expression.isEmpty()) {
            return NumberValue.zero();
        }

        // Fast path: numeric literals (e.g. "0", "1.5", "-0.3") skip parse entirely
        final char first = expression.charAt(0);
        if ((first >= '0' && first <= '9') || first == '-' || first == '.') {
            try {
                return NumberValue.of(Double.parseDouble(expression));
            } catch (NumberFormatException ignored) {
                // Not a pure number, fall through to normal parse
            }
        }

        return eval(scope, compile(expression));
    }

    public static Value eval(final Scope scope, final List<Expression> expressions) {
        return evalExpressions(scope, expressions);
    }

    public static Value eval(final Scope scope, final CompiledExpression expression) {
        return evalExpressions(scope, expression.expressions);
    }

    private static Value evalExpressions(final Scope scope, final List<Expression> expressions) {
        final LayeredScope localScope = EVAL_SCOPE.get();
        localScope.reset(scope);
        final ResettableTempBinding tempBinding = EVAL_TEMP.get();
        tempBinding.resetTemp(); // drop any temp.* written by a previous eval before reuse
        localScope.set("temp", tempBinding);
        localScope.set("t", tempBinding);
        localScope.readOnly(true);

        final ExpressionInterpreter<Void> evaluator = new ExpressionInterpreter<>(null, localScope);
        evaluator.warnOnReflectiveFunctionUsage(false);

        Value lastResult = NumberValue.zero();
        for (Expression expression : expressions) {
            lastResult = expression.visit(evaluator);
            Value returnValue = evaluator.popReturnValue();
            if (returnValue != null) {
                lastResult = returnValue;
                break;
            }
        }

        return lastResult;
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
     * Returns an opaque cached AST which can be evaluated concurrently but never exposes the mutable
     * Mocha nodes it owns.
     */
    public static CompiledExpression compile(final String expression) throws IOException {
        final CompiledExpression cached = PARSE_CACHE.get(expression);
        if (cached != null) {
            return cached;
        }

        try (final StringReader reader = new StringReader(expression)) {
            final CompiledExpression parsed = new CompiledExpression(parse(reader));
            if (PARSE_CACHE.size() < MAX_CACHE_SIZE) {
                final CompiledExpression raced = PARSE_CACHE.putIfAbsent(expression, parsed);
                if (raced != null) {
                    return raced;
                }
            }
            return parsed;
        }
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

        private CompiledExpression(final List<Expression> expressions) {
            this.expressions = List.copyOf(expressions);
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
