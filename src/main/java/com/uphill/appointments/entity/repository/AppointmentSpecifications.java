package com.uphill.appointments.entity.repository;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.domain.Specification;

import com.uphill.appointments.entity.Appointment;

import jakarta.persistence.criteria.JoinType;

/**
 * Builds optional search predicates for the admin listing endpoint. Using
 * Specifications (rather than a JPQL "(:param is null or ...)" query) means
 * an unset filter is simply omitted from the generated SQL, instead of
 * appearing as a bare parameter Postgres can't infer a type for.
 */
public final class AppointmentSpecifications {

    private AppointmentSpecifications() {
    }

    public static Specification<Appointment> hasSpecialtyCode(String specialtyCode) {
        // Blank, not just null: an HTML <select> "All specialties" option submits
        // specialty= (present, empty) rather than omitting the parameter - without
        // this, that renders as an impossible "code equals ''" filter instead of
        // no filter at all.
        if (specialtyCode == null || specialtyCode.isBlank()) {
            return null;
        }
        return (root, query, cb) -> cb.equal(root.get("specialty").get("code"), specialtyCode);
    }

    public static Specification<Appointment> startsAtFrom(OffsetDateTime from) {
        if (from == null) {
            return null;
        }
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("startsAt"), from);
    }

    public static Specification<Appointment> startsAtTo(OffsetDateTime to) {
        if (to == null) {
            return null;
        }
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("startsAt"), to);
    }

    /**
     * Fetch-joins the four associations {@link com.uphill.appointments.boundary.api.dto.AppointmentResponse#from}
     * always reads, so listing a page doesn't N+1 (up to 4 extra selects per
     * row otherwise, thanks to lazy-loaded {@code @ManyToOne}s). Safe to
     * combine with pagination — these are all to-one associations, so
     * fetching them can't multiply row count the way a to-many fetch join
     * would. Skipped on the count-query pass Spring Data runs alongside the
     * page query: a fetch there is meaningless (COUNT(*) discards columns)
     * and Hibernate rejects fetches on some query shapes anyway.
     */
    public static Specification<Appointment> fetchAssociationsForListing() {
        return (root, query, cb) -> {
            if (query.getResultType() != Long.class && query.getResultType() != long.class) {
                root.fetch("patient", JoinType.LEFT);
                root.fetch("specialty", JoinType.LEFT);
                root.fetch("doctor", JoinType.LEFT);
                root.fetch("room", JoinType.LEFT);
            }
            return cb.conjunction();
        };
    }
}
