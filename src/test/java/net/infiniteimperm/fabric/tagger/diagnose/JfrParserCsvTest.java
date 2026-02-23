package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JfrParserCsvTest {
    @Test
    void parsesQuotedCsvCells() {
        String line = "a,\"b,c\",d";
        List<String> cols = JfrParser.parseCsvLine(line);
        assertEquals(3, cols.size());
        assertEquals("a", cols.get(0));
        assertEquals("b,c", cols.get(1));
        assertEquals("d", cols.get(2));
    }
}
