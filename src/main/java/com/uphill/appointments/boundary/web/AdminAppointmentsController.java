package com.uphill.appointments.boundary.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.uphill.appointments.boundary.api.dto.AppointmentResponse;
import com.uphill.appointments.boundary.web.dto.AppointmentRow;
import com.uphill.appointments.boundary.web.dto.BookingForm;
import com.uphill.appointments.control.exceptions.AppointmentAllocationException;
import com.uphill.appointments.control.exceptions.AppointmentAlreadyCancelledException;
import com.uphill.appointments.control.exceptions.AppointmentNotFoundException;
import com.uphill.appointments.control.BookingService;
import com.uphill.appointments.control.CancellationService;
import com.uphill.appointments.control.exceptions.PatientDoubleBookedException;
import com.uphill.appointments.control.exceptions.PatientNotFoundException;
import com.uphill.appointments.control.exceptions.SlotValidationException;
import com.uphill.appointments.entity.Appointment;
import com.uphill.appointments.entity.Specialty;
import com.uphill.appointments.entity.repository.AppointmentRepository;
import com.uphill.appointments.entity.repository.AppointmentSpecifications;
import com.uphill.appointments.entity.repository.SpecialtyRepository;

import lombok.RequiredArgsConstructor;

/**
 * Server-rendered admin UI alongside the JSON API (see DECISIONS.md #036,
 * #037) - a browser-testable view onto the same {@link BookingService},
 * {@link CancellationService}, and {@link AppointmentRepository} the REST
 * {@code AppointmentController} uses, called in-process rather than over
 * HTTP. Deliberately simpler than the REST list endpoint: filters only by
 * specialty, not the from/to time range - a date-range picker means binding
 * {@code OffsetDateTime} from a plain HTML form field, which has none of
 * Jackson's JSON support behind it and no built-in Spring form converter
 * either. Not worth solving for an admin convenience page.
 *
 * <p>The page's filter/book/cancel interactions are progressively enhanced
 * with htmx (see the template) purely client-side - every {@code hx-*}
 * attribute targets the exact same full-page response this controller
 * already returns, so there is no separate fragment-rendering code path
 * here to keep in sync.
 */
@Controller
@RequiredArgsConstructor
class AdminAppointmentsController {

    private static final ZoneId ADMIN_ZONE = ZoneId.of("Europe/Lisbon");
    private static final int PAGE_SIZE = 20;

    private final BookingService bookingService;
    private final CancellationService cancellationService;
    private final AppointmentRepository appointmentRepository;
    private final SpecialtyRepository specialtyRepository;

    @GetMapping("/admin/appointments")
    String list(@RequestParam(required = false) String specialty, @RequestParam(defaultValue = "0") int page,
            Model model) {
        Specification<Appointment> spec = Specification.allOf(
                Stream.of(AppointmentSpecifications.hasSpecialtyCode(specialty),
                                AppointmentSpecifications.fetchAssociationsForListing())
                        .filter(Objects::nonNull)
                        .toList());
        Page<AppointmentRow> appointments = appointmentRepository
                .findAll(spec, PageRequest.of(page, PAGE_SIZE))
                .map(AppointmentResponse::from)
                .map(response -> AppointmentRow.from(response, ADMIN_ZONE));

        List<Specialty> specialties = specialtyRepository.findAll();
        model.addAttribute("appointments", appointments);
        model.addAttribute("specialties", specialties);
        model.addAttribute("selectedSpecialty", specialty);
        if (!model.containsAttribute("bookingForm")) {
            model.addAttribute("bookingForm", new BookingForm(null, null, null, null, null));
        }
        return "admin/appointments";
    }

    @PostMapping("/admin/appointments")
    String book(@ModelAttribute BookingForm form, RedirectAttributes redirectAttributes) {
        try {
            LocalTime startTime = form.parsedStartTime();
            Integer durationMinutes = form.durationMinutes();
            if (durationMinutes == null) {
                throw new SlotValidationException("Start time and end time are required");
            }
            if (durationMinutes <= 0) {
                throw new SlotValidationException("End time must be after start time");
            }
            OffsetDateTime startsAt = OffsetDateTime.of(form.date(), startTime, zoneOffsetFor(form.date()));
            Appointment appointment = bookingService.book(
                    form.specialtyCode(), form.patientId(), startsAt, durationMinutes);
            redirectAttributes.addFlashAttribute("successMessage",
                    "Booked appointment " + appointment.getId() + " for " + form.patientId() + ".");
        } catch (SlotValidationException | AppointmentAllocationException | PatientNotFoundException
                | PatientDoubleBookedException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/appointments";
    }

    @PostMapping("/admin/appointments/{id}/cancel")
    String cancel(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
        try {
            cancellationService.cancel(id);
            redirectAttributes.addFlashAttribute("successMessage", "Cancelled appointment " + id + ".");
        } catch (AppointmentNotFoundException | AppointmentAlreadyCancelledException e) {
            redirectAttributes.addFlashAttribute("errorMessage", e.getMessage());
        }
        return "redirect:/admin/appointments";
    }

    private static ZoneOffset zoneOffsetFor(LocalDate date) {
        return ADMIN_ZONE.getRules().getOffset(date.atStartOfDay());
    }
}
