package org.beFree.category;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.Optional;

@Entity
@Table(name = "categories")
public class Category extends PanacheEntity {

    @Column(nullable = false, unique = true)
    public String name;

    public static Optional<Category> findByName(String name) {
        return find("lower(name) = ?1", name.toLowerCase()).firstResultOptional();
    }
}
