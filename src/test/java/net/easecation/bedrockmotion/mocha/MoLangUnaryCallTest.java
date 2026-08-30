package net.easecation.bedrockmotion.mocha;

import org.junit.jupiter.api.Test;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.value.Function;
import team.unnamed.mocha.runtime.value.MutableObjectBinding;
import team.unnamed.mocha.runtime.value.NumberValue;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MoLangUnaryCallTest {
    @Test
    void appliesPrefixUnaryOperatorsToCallResults() throws Exception {
        Scope scope = scopeWithValue(0.0D);
        assertEquals(1.0D, MoLangEngine.eval(scope, "!query.value()").getAsNumber());
        assertEquals(0.0D, MoLangEngine.eval(scope, "!!query.value()").getAsNumber());
        assertEquals(1.0D, MoLangEngine.eval(scope, "!(query.value())").getAsNumber());

        scope = scopeWithValue(2.0D);
        assertEquals(0.0D, MoLangEngine.eval(scope, "!query.value()").getAsNumber());
        assertEquals(1.0D, MoLangEngine.eval(scope, "!!query.value()").getAsNumber());
        assertEquals(-2.0D, MoLangEngine.eval(scope, "-query.value()").getAsNumber());
        assertEquals(1.0D, MoLangEngine.eval(scope, "query.value() != 0").getAsNumber());
    }

    private static Scope scopeWithValue(double value) {
        MutableObjectBinding query = new MutableObjectBinding();
        query.set("value", (Function<Object>) (execution, arguments) -> NumberValue.of(value));
        Scope scope = Scope.create();
        scope.set("query", query);
        return scope;
    }
}
