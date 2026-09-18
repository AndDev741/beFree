package org.beFree.api;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.beFree.auth.CurrentUser;
import org.beFree.goal.Goal;
import org.beFree.goal.GoalContribution;
import org.beFree.goal.GoalService;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

@Path("/api/goals")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class GoalResource {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("1000000");

    @Inject
    GoalService goals;

    @Inject
    CurrentUser currentUser;

    @ConfigProperty(name = "befree.zone", defaultValue = "Europe/Lisbon")
    String zone;

    @GET
    @Transactional
    public List<Dto.GoalView> list() {
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        return goals.progress().stream().map(p -> view(p, today)).toList();
    }

    static Dto.GoalView view(GoalService.Progress p, LocalDate today) {
        return new Dto.GoalView(p.goal().id, p.goal().name, p.goal().description, p.goal().target,
                p.goal().targetDate, p.goal().initialAmount, p.saved(), p.spent(), p.remaining(),
                p.percent(), p.reached(), p.monthsLeft(today), p.perMonth(today));
    }

    /** What was already in the jar. Rejected above the target, which is always a typo. */
    private static BigDecimal opening(BigDecimal initial, BigDecimal target) {
        if (initial == null) {
            return BigDecimal.ZERO;
        }
        if (initial.signum() < 0 || initial.compareTo(MAX_AMOUNT) > 0) {
            throw new BadRequestException("the amount you already have must be zero or a plausible positive value");
        }
        if (initial.compareTo(target) > 0) {
            throw new BadRequestException("you already have more than the target; raise the target instead");
        }
        return initial.setScale(2, RoundingMode.HALF_UP);
    }

    @POST
    @Transactional
    public Dto.GoalView create(Dto.GoalRequest req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw new BadRequestException("name is required");
        }
        if (req.target() == null || req.target().signum() <= 0 || req.target().compareTo(MAX_AMOUNT) > 0) {
            throw new BadRequestException("target must be a positive, plausible amount");
        }
        String clean = req.name().trim();
        if (Goal.findByName(clean).isPresent()) {
            throw new BadRequestException("a goal named '" + clean + "' already exists");
        }
        Goal goal = new Goal();
        goal.name = clean;
        goal.target = req.target().setScale(2, RoundingMode.HALF_UP);
        goal.targetDate = req.targetDate();
        goal.description = req.description() == null || req.description().isBlank() ? null : req.description().trim();
        goal.initialAmount = opening(req.initial(), goal.target);
        goal.owner = currentUser.name();
        goal.persist();
        return view(new GoalService.Progress(goal, goal.initialAmount), LocalDate.now(ZoneId.of(zone)));
    }

    /** Fields left out keep their value; a blank note or date clears it. */
    @PATCH
    @Path("/{id}")
    @Transactional
    public Dto.GoalView update(@PathParam("id") long id, Dto.GoalRequest req) {
        Goal goal = Goal.findById(id);
        if (goal == null) {
            throw new NotFoundException("goal " + id + " not found");
        }
        if (req == null) {
            throw new BadRequestException("body is required");
        }
        if (req.name() != null && !req.name().isBlank()) {
            String clean = req.name().trim();
            if (Goal.findByName(clean).filter(other -> !other.id.equals(goal.id)).isPresent()) {
                throw new BadRequestException("a goal named '" + clean + "' already exists");
            }
            goal.name = clean;
        }
        if (req.target() != null) {
            if (req.target().signum() <= 0 || req.target().compareTo(MAX_AMOUNT) > 0) {
                throw new BadRequestException("target must be a positive, plausible amount");
            }
            goal.target = req.target().setScale(2, RoundingMode.HALF_UP);
        }
        if (Boolean.TRUE.equals(req.clearTargetDate())) {
            goal.targetDate = null;
        } else if (req.targetDate() != null) {
            goal.targetDate = req.targetDate();
        }
        if (req.description() != null) {
            goal.description = req.description().isBlank() ? null : req.description().trim();
        }
        if (req.initial() != null) {
            goal.initialAmount = opening(req.initial(), goal.target);
        }
        return view(new GoalService.Progress(goal, goals.saved(goal)), LocalDate.now(ZoneId.of(zone)));
    }

    @POST
    @Path("/{id}/contributions")
    @Transactional
    public Dto.GoalView contribute(@PathParam("id") long id, Dto.ContributionRequest req) {
        Goal goal = Goal.findById(id);
        if (goal == null) {
            throw new NotFoundException("goal " + id + " not found");
        }
        if (req == null || req.amount() == null || req.amount().signum() == 0
                || req.amount().abs().compareTo(MAX_AMOUNT) > 0) {
            throw new BadRequestException("amount must be a non-zero, plausible value");
        }
        LocalDate today = LocalDate.now(ZoneId.of(zone));
        goals.contribute(goal, req.amount().setScale(2, RoundingMode.HALF_UP),
                req.occurredOn() != null ? req.occurredOn() : today,
                req.note() == null || req.note().isBlank() ? null : req.note().trim());
        return view(new GoalService.Progress(goal, goals.saved(goal)), today);
    }

    @DELETE
    @Path("/{id}")
    @Transactional
    public void delete(@PathParam("id") long id) {
        Goal goal = Goal.findById(id);
        if (goal == null) {
            throw new NotFoundException("goal " + id + " not found");
        }
        GoalContribution.delete("goal", goal);
        goal.delete();
    }
}
