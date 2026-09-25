package com.fdmultimedia.api.highlights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fdmultimedia.api.assets.MediaAsset;
import com.fdmultimedia.api.workspaces.Workspace;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class HighlightSelectionStatusTest {
    @Test void derivesCompletePartialAndEmptyWithoutMutatingAnalysis() {
        HighlightAnalysis analysis = mock(HighlightAnalysis.class);
        when(analysis.getWorkspace()).thenReturn(mock(Workspace.class));
        when(analysis.getAsset()).thenReturn(mock(MediaAsset.class));
        assertThat(new HighlightSelection(analysis, "DIVERSITY_SELECTOR_V1", 3, 3, Instant.EPOCH).getStatus()).isEqualTo(HighlightSelectionStatus.COMPLETE);
        assertThat(new HighlightSelection(analysis, "DIVERSITY_SELECTOR_V1", 3, 2, Instant.EPOCH).getStatus()).isEqualTo(HighlightSelectionStatus.PARTIAL);
        assertThat(new HighlightSelection(analysis, "DIVERSITY_SELECTOR_V1", 3, 0, Instant.EPOCH).getStatus()).isEqualTo(HighlightSelectionStatus.EMPTY);
    }
}
