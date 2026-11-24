package com.schoomano.security;

import com.schoomano.domain.auth.MfaPolicy;
import com.schoomano.domain.access.RoleGrant;
import com.schoomano.platform.ApiException;
import com.schoomano.platform.RequestContext;
import com.schoomano.platform.TenantTx;
import com.schoomano.domain.tenancy.TenantId;
import java.util.List;
import java.util.UUID;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Turns the authenticated IdP identity into a tenant-scoped actor. The optional X-Schoomano-Tenant
 * header only *selects* among tenants this identity already belongs to; it is never trusted on its own.
 */
@Component
public class RequestContextResolver implements HandlerMethodArgumentResolver {

    public static final String TENANT_HEADER = "X-Schoomano-Tenant";
    /** Identity issuer for parents who signed in with a phone code (see V7__sign_in.sql). */
    static final String PHONE_ISSUER = "phone";

    private final TenantTx tenantTx;
    private final ActorLoader actorLoader;

    public RequestContextResolver(TenantTx tenantTx, ActorLoader actorLoader) {
        this.tenantTx = tenantTx;
        this.actorLoader = actorLoader;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType().equals(RequestContext.class);
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binderFactory) {
        ExternalIdentity who = identity(SecurityContextHolder.getContext().getAuthentication());

        record Match(UUID personId, UUID tenantId, String slug, String name) { }
        List<Match> matches = tenantTx.runWithoutTenant(jdbc -> jdbc.query(
            "select person_id, tenant_id, tenant_slug, tenant_name from auth_resolve_identity(?, ?)",
            (rs, i) -> new Match(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4)),
            who.issuer(), who.subject()));

        if (matches.isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "no_membership",
                "Your sign-in is valid but is not linked to any school. Ask your school administrator for an invitation.");
        }
        String selected = request.getHeader(TENANT_HEADER);
        Match m;
        if (matches.size() == 1 && selected == null) {
            m = matches.get(0);
        } else {
            m = matches.stream().filter(x -> x.slug().equals(selected)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "tenant_selection_required",
                    "Choose which school to open."));
        }
        TenantId tenant = new TenantId(m.tenantId());
        var actor = tenantTx.run(tenant, jdbc -> actorLoader.load(jdbc, tenant, m.personId()));
        if (PHONE_ISSUER.equals(who.issuer())) {
            // A code sent to a phone proves a parent, not a staff member: no staff roles through this door.
            actor = actor.guardianOnly();
        } else {
            requireSecondFactorWhereNeeded(actor.grants().stream().map(RoleGrant::roleKey).toList(),
                SecurityContextHolder.getContext().getAuthentication());
        }
        return new RequestContext(actor, tenant, m.slug(), m.name());
    }

    /** Admin and finance roles must have signed in with a second factor at the identity provider (amr claim). */
    private static void requireSecondFactorWhereNeeded(List<String> roleKeys, Authentication auth) {
        // Only OIDC tokens say how the person signed in; dev tokens exist for local synthetic data only.
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt && MfaPolicy.requiresMfa(roleKeys)
            && !MfaPolicy.satisfied(jwt.getClaimAsStringList("amr"))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "mfa_required",
                "Your role needs a second sign-in step. Sign in again and complete the authenticator check.");
        }
    }

    private static ExternalIdentity identity(Authentication auth) {
        if (auth == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue.");
        }
        Object p = auth.getPrincipal();
        if (p instanceof ExternalIdentity e) {
            return e;
        }
        if (p instanceof Jwt jwt && jwt.getIssuer() != null) {
            return new ExternalIdentity(jwt.getIssuer().toString(), jwt.getSubject());
        }
        throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Sign in to continue.");
    }
}
