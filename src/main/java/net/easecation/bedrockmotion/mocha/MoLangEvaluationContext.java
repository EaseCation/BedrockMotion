package net.easecation.bedrockmotion.mocha;

import team.unnamed.mocha.runtime.value.JavaValue;
import team.unnamed.mocha.runtime.value.Value;

import java.util.Map;

/**
 * Actor and view data associated with a MoLang evaluation. The actor is passed to Mocha's
 * execution context so {@code ->} changes the entity seen by {@code @Entity} bindings.
 */
public record MoLangEvaluationContext(Object actor, Object ownerActor, Object item, String slot,
                                      Map<String, ?> properties, String viewContext) {
    public static final MoLangEvaluationContext EMPTY =
            new MoLangEvaluationContext(null, null, null, "", Map.of(), "");

    public MoLangEvaluationContext {
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        slot = slot == null ? "" : slot;
        viewContext = viewContext == null ? "" : viewContext;
    }

    /** Value required by Mocha for a Bedrock execution-entity arrow expression. */
    public JavaValue owningEntityValue() {
        return new JavaValue(ownerActor);
    }

    /** Actor contract used to resolve variable reads after an execution-entity arrow switch. */
    public interface Actor {
        Value variable(String normalizedName);
    }
}
