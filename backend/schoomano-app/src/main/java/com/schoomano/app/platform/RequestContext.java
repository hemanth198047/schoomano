package com.schoomano.app.platform;

import com.schoomano.core.access.Actor;
import com.schoomano.core.tenancy.TenantId;

/** Resolved per request by {@link com.schoomano.app.security.RequestContextResolver}. */
public record RequestContext(Actor actor, TenantId tenant, String tenantSlug, String tenantName) {
}
