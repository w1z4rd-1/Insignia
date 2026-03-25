package net.infiniteimperm.fabric.tagger.diagnose;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JfrControllerTest {
    @Test
    void uploadBatchFlushesOnCountThreshold() {
        assertTrue(JfrController.shouldFlushUploadBatch(JfrController.UPLOAD_BATCH_COUNT_THRESHOLD, 0L, 0L));
    }

    @Test
    void uploadBatchFlushesOnByteThreshold() {
        assertTrue(JfrController.shouldFlushUploadBatch(1, JfrController.UPLOAD_BATCH_BYTES_THRESHOLD, 0L));
    }

    @Test
    void uploadBatchFlushesOnDurationThreshold() {
        assertTrue(JfrController.shouldFlushUploadBatch(1, 0L, JfrController.UPLOAD_BATCH_DURATION_THRESHOLD_NS));
    }

    @Test
    void uploadBatchDoesNotFlushBelowThresholds() {
        assertFalse(JfrController.shouldFlushUploadBatch(
            JfrController.UPLOAD_BATCH_COUNT_THRESHOLD - 1,
            JfrController.UPLOAD_BATCH_BYTES_THRESHOLD - 1L,
            JfrController.UPLOAD_BATCH_DURATION_THRESHOLD_NS - 1L));
    }

    @Test
    void shaderBatchFlushesOnDurationThreshold() {
        assertTrue(JfrController.shouldFlushShaderBatch(1, JfrController.SHADER_BATCH_DURATION_THRESHOLD_NS));
    }

    @Test
    void shaderBatchDoesNotFlushWithoutDuration() {
        assertFalse(JfrController.shouldFlushShaderBatch(1, JfrController.SHADER_BATCH_DURATION_THRESHOLD_NS - 1L));
    }
}
