package com.guildworkman.api.data.models;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.guildworkman.api.data.constants.Category;
import com.guildworkman.api.data.constants.Role;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static java.time.LocalDateTime.now;

@Setter
@Getter
@Entity
@Table(name = "skilled_workers", indexes = {
        // Composite B-tree serving the bounding-box prefilter of worker
        // discovery (GET /api/v1/discovery/workers). The distance search narrows
        // to a lat/lon box on this index before the exact Haversine runs, so a
        // radius query never degrades into a full-table scan — see
        // backend-api/docs/WORKER_DISCOVERY.md.
        @Index(name = "idx_skilled_workers_geo", columnList = "latitude, longitude"),
        // Backs the discovery category filter and its facet count.
        @Index(name = "idx_skilled_workers_category", columnList = "category")
})
public class SkilledWorker {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String fullName;
    @Column(unique = true)
    private String username;
    private String password;
    @Column(unique = true)
    private String phoneNumber;
    @Column(unique = true)
    private String email;
    @Setter(AccessLevel.NONE)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime timeCreated;
    @Setter(AccessLevel.NONE)
    @JsonSerialize(using = LocalDateTimeSerializer.class)
    @JsonDeserialize(using = LocalDateTimeDeserializer.class)
    private LocalDateTime timeUpdated;
    @OneToOne
    private Address address;
    @Enumerated(EnumType.STRING)
    private Category category;
    @OneToMany(mappedBy = "skilledWorker",
            cascade = CascadeType.ALL,orphanRemoval = true,fetch = FetchType.EAGER)
    // Initialised for the same reason as Client.appointment: a freshly-persisted
    // SkilledWorker whose collection Hibernate hasn't hydrated is otherwise a null
    // list waiting to NPE on the first add/remove.
    private List<Appointment> appointment = new ArrayList<>();

    @PrePersist
    private void setTimeCreated(){
        this.timeCreated= now();
    }
    @PreUpdate
    private void setTimeUpdated(){
        this.timeUpdated= now();
    }

    private double latitude;
    private double longitude;

    /**
     * Whether the worker is currently taking work. Nullable on purpose: a legacy
     * row that predates this column, or a worker who has never set it, is "not
     * stated" and is treated as available by worker discovery
     * ({@code GET /api/v1/discovery/workers}) rather than being hidden.
     */
    private Boolean available;

}
