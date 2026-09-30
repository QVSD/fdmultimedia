package com.fdmultimedia.worker;

import java.util.List;

record CoordinatedCopyResult(String seriesTitle, List<CoordinatedCopyItemResult> items) {
}
