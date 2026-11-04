package net.easecation.bedrockmotion.pack;

import net.easecation.bedrockmotion.pack.content.Content;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AttachableDefinitionsTest {
    @Test
    void indexesCandidatesByPackPriorityThenIdentifier() {
        Content low = new Content();
        low.putString("attachables/z.json", attachable("test:z", "test:gun", "q.low"));
        low.putString("attachables/overridden.json", attachable("test:override", "test:old", "q.old"));

        Content high = new Content();
        high.putString("attachables/a.json", attachable("test:a", "test:gun", "q.high"));
        high.putString("attachables/override.json", attachable("test:override", "test:gun", "q.new"));

        PackManager manager = new PackManager(List.of(low, high));
        var candidates = manager.getAttachableDefinitions().candidatesFor("test:gun");

        assertEquals(List.of("test:a", "test:override", "test:z"),
                candidates.stream().map(definition -> definition.identifier()).toList());
        assertEquals("q.new", manager.getAttachableDefinitions().getAttachables()
                .get("test:override").data().getItemConditions().get("test:gun"));
    }

    private static String attachable(String identifier, String item, String condition) {
        return """
                {"minecraft:attachable":{"description":{
                  "identifier":"%s","item":{"%s":"%s"}
                }}}
                """.formatted(identifier, item, condition);
    }
}
