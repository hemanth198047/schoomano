package com.schoomano.domain.config;

import com.schoomano.domain.tenancy.ScopeNode;

/** The value in force plus where it came from, so the UI can show "inherited from Group". */
public record ResolvedConfig(String key, String valueJson, int version, ScopeNode definedAt, boolean inherited) {
}
