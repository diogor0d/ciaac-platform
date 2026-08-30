package com.ciaac.minecraft.minigames.paper.buildbattle;

import java.util.UUID;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.World;

/**
 * Explicit boundary for resetting a managed Build Battle world. Admission
 * remains closed until an operator has validated a marker- and
 * revision-aware implementation. Implementations that mutate blocks should
 * use the asynchronous batch methods; the synchronous method is retained for
 * small non-world adapters and compatibility.
 */
public interface BuildBattleResetPort {
    boolean available();

    ResetResult reset(UUID matchId, World world);

    /** Starts a reset without applying blocks on the caller's stack. */
    default ResetStart beginReset(UUID matchId, World world) {
        return ResetStart.completed(reset(matchId, world));
    }

    /** Applies one bounded main-thread batch for a previously started reset. */
    default ResetProgress pollReset(ResetHandle handle) {
        return ResetProgress.failed("ASYNC_RESET_UNSUPPORTED");
    }

    record ResetHandle(UUID matchId, UUID operationId) {
        public ResetHandle {
            Objects.requireNonNull(matchId, "matchId");
            Objects.requireNonNull(operationId, "operationId");
        }
    }

    record ResetStart(Optional<ResetHandle> pending, Optional<ResetResult> completed) {
        public ResetStart {
            pending = Objects.requireNonNull(pending, "pending");
            completed = Objects.requireNonNull(completed, "completed");
            if (pending.isPresent() == completed.isPresent()) {
                throw new IllegalArgumentException("reset must be pending or completed, never both");
            }
        }

        public static ResetStart pending(ResetHandle handle) {
            return new ResetStart(Optional.of(handle), Optional.empty());
        }

        public static ResetStart completed(ResetResult result) {
            return new ResetStart(Optional.empty(), Optional.of(result));
        }
    }

    record ResetProgress(boolean complete, boolean successful, int applied, int remaining, String code) {
        public ResetProgress {
            if (applied < 0 || remaining < 0) throw new IllegalArgumentException("reset progress is invalid");
            if (code == null || !code.matches("[A-Z0-9][A-Z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("reset progress code is invalid");
            }
            if (!complete && !successful) throw new IllegalArgumentException("an in-progress reset cannot be failed");
        }

        public static ResetProgress running(int applied, int remaining) {
            return new ResetProgress(false, true, applied, remaining, "IN_PROGRESS");
        }

        public static ResetProgress completed(int applied) {
            return new ResetProgress(true, true, applied, 0, "RESET_COMPLETE");
        }

        public static ResetProgress failed(String code) {
            return new ResetProgress(true, false, 0, 0, code);
        }
    }

    record ResetResult(boolean successful, String code) {
        public ResetResult {
            if (code == null || !code.matches("[A-Z0-9][A-Z0-9_.-]{0,63}")) {
                throw new IllegalArgumentException("reset code must be bounded");
            }
        }
    }
}
