package com.guildworkman.api.data.models;

import com.guildworkman.api.data.constants.Role;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

import static java.time.LocalDateTime.now;

/**
 * Dedicated authentication principal for the JWT/RBAC layer.
 *
 * <p>This is deliberately separate from {@link Client} and {@link SkilledWorker}:
 * those model marketplace domain data, whereas a {@code UserAccount} models a
 * login identity (credentials + a single role). Keeping them apart lets the new
 * auth flow ship without reworking the existing registration/login of the domain
 * entities, and a UserAccount can later be linked to a Client/SkilledWorker
 * profile without changing this table.
 */
@Entity
@Getter
@Setter
@Table(name = "user_accounts")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Login identifier. Stored lower-cased by the service layer. */
    @Column(nullable = false, unique = true)
    private String email;

    /** BCrypt hash — never the raw password. */
    @Column(nullable = false)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /** A disabled account authenticates to nothing; used for soft-lockout. */
    @Column(nullable = false)
    private boolean enabled = true;

    @Setter(AccessLevel.NONE)
    private LocalDateTime timeCreated;

    @Setter(AccessLevel.NONE)
    private LocalDateTime timeUpdated;

    @PrePersist
    private void onCreate() {
        this.timeCreated = now();
        this.timeUpdated = now();
    }

    @PreUpdate
    private void onUpdate() {
        this.timeUpdated = now();
    }
}
