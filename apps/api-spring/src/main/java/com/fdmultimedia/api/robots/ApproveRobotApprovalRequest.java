package com.fdmultimedia.api.robots;

import java.time.Instant;

/** {@code scheduledFor} is optional — omit to accept the approval's own proposed time. */
public record ApproveRobotApprovalRequest(Instant scheduledFor) {
}
