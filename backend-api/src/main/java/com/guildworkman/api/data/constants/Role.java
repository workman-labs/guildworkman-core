package com.guildworkman.api.data.constants;

public enum Role {
    CLIENT,
    ADMIN,
    SKILLED_WORKER;

    /**
     * Spring Security authority name for this role. Spring's {@code hasRole(x)}
     * checks convention expect a {@code ROLE_} prefix, so a CLIENT maps to the
     * granted authority {@code ROLE_CLIENT}.
     */
    public String getAuthority() {
        return "ROLE_" + name();
    }
}
