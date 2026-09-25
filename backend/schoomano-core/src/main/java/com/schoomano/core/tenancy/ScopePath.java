package com.schoomano.core.tenancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a record lives in the tenant hierarchy, e.g. GROUP g / CAMPUS c / ACADEMIC_YEAR y.
 * Levels may be skipped (not every path has a legal entity or programme), but order is strict.
 */
public record ScopePath(TenantId tenant, List<ScopeNode> nodes) {

    public ScopePath {
        Objects.requireNonNull(tenant, "tenant");
        nodes = List.copyOf(nodes);
        if (nodes.isEmpty() || nodes.get(0).level() != ScopeLevel.GROUP) {
            throw new IllegalArgumentException("scope path must start at GROUP");
        }
        if (!nodes.get(0).id().equals(tenant.value())) {
            throw new IllegalArgumentException("GROUP node must be the tenant itself");
        }
        for (int i = 1; i < nodes.size(); i++) {
            if (!nodes.get(i - 1).level().isBroaderThan(nodes.get(i).level())) {
                throw new IllegalArgumentException("scope levels must narrow strictly: " + nodes);
            }
        }
    }

    public static ScopePath group(TenantId tenant) {
        return new ScopePath(tenant, List.of(new ScopeNode(ScopeLevel.GROUP, tenant.value())));
    }

    public ScopePath child(ScopeNode node) {
        List<ScopeNode> next = new ArrayList<>(nodes);
        next.add(node);
        return new ScopePath(tenant, next);
    }

    /** True when a grant or config set at {@code node} applies to records in this path. */
    public boolean isWithin(ScopeNode node) {
        return nodes.contains(node);
    }

    public Optional<ScopeNode> nodeAt(ScopeLevel level) {
        return nodes.stream().filter(n -> n.level() == level).findFirst();
    }

    public ScopeNode leaf() {
        return nodes.get(nodes.size() - 1);
    }
}
