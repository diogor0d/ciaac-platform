package com.ciaac.minecraft.minigames.announcement;

import com.ciaac.minecraft.minigames.runtime.MachineCode;

public record AnnouncementPublishResult(boolean delivered, String code) {
    public AnnouncementPublishResult {
        code = MachineCode.normalize(code, "code");
    }

    public static AnnouncementPublishResult delivered(String code) {
        return new AnnouncementPublishResult(true, code);
    }

    public static AnnouncementPublishResult failed(String code) {
        return new AnnouncementPublishResult(false, code);
    }
}
