package net.easecation.bedrockmotion.mocha;

import org.junit.jupiter.api.Test;
import team.unnamed.mocha.runtime.Scope;
import team.unnamed.mocha.runtime.program.ProgramCompiler;
import team.unnamed.mocha.runtime.value.*;
import static org.junit.jupiter.api.Assertions.*;

class MoLangBytecodeIntegrationTest {
    @Test void preparedAnimationExpressionsBuildOnlyBeforeEvaluationAndReadFreshValues() throws Exception {
        Scope scope=MoLangEngine.newScope();MutableObjectBinding query=new MutableObjectBinding();scope.set("query",query);
        var expression=MoLangEngine.compile("math.sin(q.anim_time*90)*3+this");
        long builds=ProgramCompiler.shared().statistics().get("builds");
        scope.set("this",NumberValue.of(2));query.set("anim_time",NumberValue.of(1));
        assertEquals(5,MoLangEngine.eval(scope,expression).getAsNumber(),1e-9);
        query.set("anim_time",NumberValue.of(0));assertEquals(2,MoLangEngine.eval(scope,expression).getAsNumber(),1e-9);
        for(int i=0;i<100;i++)MoLangEngine.eval(scope,expression);
        assertEquals(builds,ProgramCompiler.shared().statistics().get("builds"));
    }
    @Test void nestedHostEvaluationPreservesOuterScopeTempAndExceptionCleanup() throws Exception {
        Scope outer=MoLangEngine.newScope(),inner=MoLangEngine.newScope();
        var nested=MoLangEngine.compile("t.value=99;return t.value;");
        outer.set("nested",(Function<Object>)(c,a)->MoLangEngine.eval(inner,nested));
        assertEquals(106,MoLangEngine.eval(outer,"t.value=7;return nested()+t.value;").getAsNumber());
        assertTrue(MoLangEngine.eval(outer,"t.value").isNil());
        outer.set("fail",(Function<Object>)(c,a)->{throw new IllegalStateException("host");});
        var failing=MoLangEngine.compile("t.value=19;fail();");
        assertThrows(IllegalStateException.class,()->MoLangEngine.eval(outer,failing));
        assertTrue(MoLangEngine.eval(outer,"t.value").isNil());
    }
}
