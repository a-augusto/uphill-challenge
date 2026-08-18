package com.uphill.appointments.entity.repository;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.domain.Specification;

import com.uphill.appointments.entity.Appointment;

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
}
