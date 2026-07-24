package com.guildworkman.api.security;

import com.guildworkman.api.data.constants.Role;
import com.guildworkman.api.data.models.UserAccount;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.List;

/**
 * Adapts a {@link UserAccount} to Spring Security's {@link UserDetails}. The
 * granted authority is the single role's {@code ROLE_*} name.
 */
public class AppUserPrincipal implements UserDetails {

    private final Long id;
    private final String email;
    private final String passwordHash;
    private final Role role;
    private final boolean enabled;

    public AppUserPrincipal(UserAccount account) {
        this.id = account.getId();
        this.email = account.getEmail();
        this.passwordHash = account.getPassword();
        this.role = account.getRole();
        this.enabled = account.isEnabled();
    }

    public Long getId() {
        return id;
    }

    public Role getRole() {
        return role;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority(role.getAuthority()));
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() {
        return true;
    }

    @Override
    public boolean isAccountNonLocked() {
        return true;
    }

    @Override
    public boolean isCredentialsNonExpired() {
        return true;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }
}
