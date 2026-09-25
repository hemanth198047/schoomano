package com.schoomano.core.config;

import com.schoomano.core.tenancy.ScopePath;

/** One row of a publish preview: what a scope sees before and after the draft takes effect. */
public record ConfigChange(ScopePath target, String before, String after) {
    public boolean changed() {
        return before == null ? after != null : !before.equals(after);
    }
}
