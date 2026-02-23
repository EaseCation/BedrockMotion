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
import team.unnamed.mocha.parser.ast.Expression;
import team.unnamed.mocha.runtime.ExpressionInterpreter;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.NumberValue;
import team.unnamed.mocha.runtime.value.Value;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("UnstableApiUsage")
public class MoLangEngine {
    private static final ConcurrentHashMap<String, List<Expression>> PARSE_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 4096;
    private static final ThreadLocal<LayeredScope> EVAL_SCOPE =
            ThreadLocal.withInitial(() -> new LayeredScope(Scope.create()));

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

        return eval(scope, parse(expression));
    }

    public static Value eval(final Scope scope, final List<Expression> expressions) {
        final LayeredScope localScope = EVAL_SCOPE.get();
        localScope.reset(scope);
        final MutableObjectBinding tempBinding = new MutableObjectBinding();
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

    public static List<Expression> parse(final String expression) throws IOException {
        List<Expression> cached = PARSE_CACHE.get(expression);
        if (cached != null) {
            return cached;
        }

        try (final StringReader reader = new StringReader(expression)) {
            List<Expression> parsed = parse(reader);
            if (PARSE_CACHE.size() < MAX_CACHE_SIZE) {
                PARSE_CACHE.put(expression, parsed);
            }
            return parsed;
        }
    }

    public static List<Expression> parse(final Reader reader) throws IOException {
        return MolangParser.parser(reader).parseAll();
    }
}
