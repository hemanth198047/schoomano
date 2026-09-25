package com.schoomano.platform;

import com.schoomano.domain.access.Actor;
import com.schoomano.domain.tenancy.TenantId;

/** Resolved per request by {@link com.schoomano.security.RequestContextResolver}. */
public record RequestContext(Actor actor, TenantId tenant, String tenantSlug, String tenantName) {
}
