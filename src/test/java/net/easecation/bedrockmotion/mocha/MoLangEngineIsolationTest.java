package net.easecation.bedrockmotion.mocha;

import org.junit.jupiter.api.Test;
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
import team.unnamed.mocha.runtime.Scope;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class MoLangEngineIsolationTest {

    @Test
    void publicParseReturnsDetachedTreesWithoutExposingCachedAst() throws Exception {
        final List<Expression> first = MoLangEngine.parse("1 + 2");
        final List<Expression> second = MoLangEngine.parse("1 + 2");
        final BinaryExpression firstBinary = assertInstanceOf(BinaryExpression.class, first.getFirst());

        assertDetached(first.getFirst(), second.getFirst());
        firstBinary.left(new DoubleExpression(40D));

        assertEquals(42D, MoLangEngine.eval(Scope.create(), first).getAsNumber());
        assertEquals(3D, MoLangEngine.eval(Scope.create(), second).getAsNumber());
        assertEquals(3D, MoLangEngine.eval(Scope.create(), "1 + 2").getAsNumber());
    }

    @Test
    void compiledExpressionDeepCopiesEveryMochaAstType() {
        final List<Expression> source = new ArrayList<>(List.of(
                new AccessExpression(new IdentifierExpression("query"), "value"),
                new ArrayAccessExpression(new IdentifierExpression("array"), new DoubleExpression(0D)),
                new BinaryExpression(BinaryExpression.Op.ADD,
                        new DoubleExpression(1D), new DoubleExpression(2D)),
                new CallExpression(new IdentifierExpression("function"),
                        new ArrayList<>(List.of(new StringExpression("argument")))),
                new DoubleExpression(3D),
                new ExecutionScopeExpression(new ArrayList<>(List.of(new IdentifierExpression("nested")))),
                new IdentifierExpression("identifier"),
                new StatementExpression(StatementExpression.Op.values()[0]),
                new StringExpression("value"),
                new TernaryConditionalExpression(
                        new DoubleExpression(1D), new StringExpression("yes"), new StringExpression("no")),
                new UnaryExpression(UnaryExpression.Op.values()[0], new DoubleExpression(1D))
        ));
        final MoLangEngine.CompiledExpression compiled = MoLangEngine.compile(source);
        final List<Expression> first = compiled.copyExpressions();
        final List<Expression> second = compiled.copyExpressions();

        assertNotSame(source, first);
        assertNotSame(first, second);
        for (int i = 0; i < source.size(); i++) {
            assertDetached(source.get(i), first.get(i));
            assertDetached(first.get(i), second.get(i));
        }

        assertInstanceOf(CallExpression.class, first.get(3)).arguments().clear();
        assertEquals(1, assertInstanceOf(CallExpression.class, second.get(3)).arguments().size());
        assertInstanceOf(ExecutionScopeExpression.class, first.get(5)).expressions().clear();
        assertEquals(1, assertInstanceOf(ExecutionScopeExpression.class, second.get(5)).expressions().size());
    }

    private static void assertDetached(final Expression source, final Expression copy) {
        assertNotSame(source, copy);
        assertEquals(source.getClass(), copy.getClass());
        if (source instanceof AccessExpression sourceAccess) {
            final AccessExpression copyAccess = (AccessExpression) copy;
            assertEquals(sourceAccess.property(), copyAccess.property());
            assertDetached(sourceAccess.object(), copyAccess.object());
        } else if (source instanceof ArrayAccessExpression sourceArray) {
            final ArrayAccessExpression copyArray = (ArrayAccessExpression) copy;
            assertDetached(sourceArray.array(), copyArray.array());
            assertDetached(sourceArray.index(), copyArray.index());
        } else if (source instanceof BinaryExpression sourceBinary) {
            final BinaryExpression copyBinary = (BinaryExpression) copy;
            assertEquals(sourceBinary.op(), copyBinary.op());
            assertDetached(sourceBinary.left(), copyBinary.left());
            assertDetached(sourceBinary.right(), copyBinary.right());
        } else if (source instanceof CallExpression sourceCall) {
            final CallExpression copyCall = (CallExpression) copy;
            assertDetached(sourceCall.function(), copyCall.function());
            assertDetached(sourceCall.arguments(), copyCall.arguments());
        } else if (source instanceof DoubleExpression sourceDouble) {
            assertEquals(sourceDouble.value(), ((DoubleExpression) copy).value());
        } else if (source instanceof ExecutionScopeExpression sourceScope) {
            assertDetached(sourceScope.expressions(), ((ExecutionScopeExpression) copy).expressions());
        } else if (source instanceof IdentifierExpression sourceIdentifier) {
            assertEquals(sourceIdentifier.name(), ((IdentifierExpression) copy).name());
        } else if (source instanceof StatementExpression sourceStatement) {
            assertEquals(sourceStatement.op(), ((StatementExpression) copy).op());
        } else if (source instanceof StringExpression sourceString) {
            assertEquals(sourceString.value(), ((StringExpression) copy).value());
        } else if (source instanceof TernaryConditionalExpression sourceTernary) {
            final TernaryConditionalExpression copyTernary = (TernaryConditionalExpression) copy;
            assertDetached(sourceTernary.condition(), copyTernary.condition());
            assertDetached(sourceTernary.trueExpression(), copyTernary.trueExpression());
            assertDetached(sourceTernary.falseExpression(), copyTernary.falseExpression());
        } else if (source instanceof UnaryExpression sourceUnary) {
            final UnaryExpression copyUnary = (UnaryExpression) copy;
            assertEquals(sourceUnary.op(), copyUnary.op());
            assertDetached(sourceUnary.expression(), copyUnary.expression());
        } else {
            throw new AssertionError("Unhandled expression type " + source.getClass().getName());
        }
    }

    private static void assertDetached(final List<Expression> source, final List<Expression> copy) {
        assertNotSame(source, copy);
        assertEquals(source.size(), copy.size());
        for (int i = 0; i < source.size(); i++) {
            assertDetached(source.get(i), copy.get(i));
        }
    }
}
