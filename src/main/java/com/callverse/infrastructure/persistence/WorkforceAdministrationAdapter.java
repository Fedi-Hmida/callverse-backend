package com.callverse.infrastructure.persistence;

import com.callverse.core.application.exceptions.ActionNotPermittedException;
import com.callverse.core.application.exceptions.ResourceNotFoundException;
import com.callverse.core.application.interfaces.WorkforceAdministration;
import com.callverse.core.domain.entities.*;
import com.callverse.core.domain.enums.*;
import com.callverse.core.domain.exceptions.WorkforceApplicationException;
import com.callverse.infrastructure.persistence.repositories.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Moves explicitly selected idle capacity between skills; no automatic or hidden capability grants. */
@Component
@RequiredArgsConstructor
class WorkforceAdministrationAdapter implements WorkforceAdministration {
    private final AgentDecisionRepository decisions;
    private final AdvisorRepository advisors;
    private final AppUserRepository users;
    private final SkillRepository skills;
    private final ConversationRepository conversations;
    private final EntityManager em;
    private final ObjectMapper json;
    private final Clock clock;
    private static final Set<ConversationStatus> HELD=Set.of(ConversationStatus.ASSIGNED,ConversationStatus.ACTIVE,ConversationStatus.ESCALATED);

    @Override @Transactional(readOnly=true)
    public Selection candidates(UUID actorId,long decisionId) {
        requireSupervisor(users.findById(actorId).orElseThrow(() -> new ActionNotPermittedException("Supervisor access is required.")));
        var decision=decisions.findById(decisionId).orElseThrow(() -> new ResourceNotFoundException("Workforce decision",decisionId));
        var plan=plan(decision);
        boolean applied=decision.getApprovedBy()!=null;
        if (!applied) requireFresh(decision);
        List<Candidate> eligible=applied ? List.of() : advisors.findBySimulatedFalse().stream()
                .filter(advisor -> eligible(advisor,plan))
                .sorted(Comparator.comparing(Advisor::getDisplayName).thenComparing(Advisor::getId))
                .map(this::candidate).toList();
        return new Selection(decisionId,plan.from(),plan.to(),plan.count(),applied,appliedIds(decision),eligible);
    }

    @Override @Transactional
    public Application apply(UUID actorId,long decisionId,List<UUID> advisorIds,int level) {
        var decision=decisions.findByIdForUpdate(decisionId)
                .orElseThrow(() -> new ResourceNotFoundException("Workforce decision",decisionId));
        var plan=plan(decision);
        // Same global order as account/profile administration: active admins, users, then advisors.
        users.lockActiveByRole(UserRole.ADMIN);
        var actor=users.findByIdForUpdate(actorId).orElseThrow(() -> new ActionNotPermittedException("Supervisor access is required."));
        requireSupervisor(actor);
        List<UUID> selected=advisorIds.stream().sorted().toList();
        if (decision.getApprovedBy()!=null) {
            if (!appliedIds(decision).equals(selected) || decision.getAction().path("targetSkillLevel").asInt()!=level) {
                throw conflict("This proposal was already applied with a different selection.");
            }
            return application(decision,plan,true);
        }
        if (selected.size()!=plan.count()) throw conflict("Select exactly "+plan.count()+" advisors for this proposal.");
        requireFresh(decision);
        Skill target=skills.findByCode(plan.to()).orElseThrow(() -> conflict("The target skill no longer exists."));
        List<Advisor> selectedAdvisors=new ArrayList<>();
        // Read identities first, then acquire all user locks in stable order before any advisor lock.
        var identities=selected.stream().map(id -> advisors.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Advisor",id))).toList();
        identities.stream().map(Advisor::getUser).filter(Objects::nonNull).map(AppUser::getId).distinct().sorted()
                .forEach(id -> users.findByIdForUpdate(id).orElseThrow());
        for (UUID id:selected) {
            var advisor=advisors.findByIdForUpdate(id).orElseThrow();
            // Refresh data loaded before the user locks, while keeping the advisor row locked.
            em.refresh(advisor);
            if (advisor.getUser()!=null) em.refresh(advisor.getUser());
            if (!eligible(advisor,plan)) throw conflict("The selected advisors are no longer idle, active donor-only candidates. Refresh the selection.");
            selectedAdvisors.add(advisor);
        }
        for (Advisor advisor:selectedAdvisors) {
            var donor=advisor.getSkills().stream().filter(link -> link.getSkill().getCode().equals(plan.from())).findFirst().orElseThrow();
            advisor.getSkills().remove(donor); em.remove(donor);
            var link=new AdvisorSkill(); link.setAdvisor(advisor); link.setSkill(target); link.setLevel((short)level);
            em.persist(link); advisor.getSkills().add(link);
        }
        ObjectNode action=((ObjectNode)decision.getAction()).deepCopy();
        action.set("appliedAdvisorIds",json.valueToTree(selected));
        action.set("appliedUserIds",json.valueToTree(selectedAdvisors.stream().map(a -> a.getUser().getId()).toList()));
        action.put("targetSkillLevel",level); action.put("appliedAt",clock.instant().toString());
        decision.setAction(action); decision.setApprovedBy(actor);
        em.flush();
        return application(decision,plan,false);
    }

    private boolean eligible(Advisor advisor,Plan plan) {
        var user=advisor.getUser();
        return !advisor.isSimulated() && user!=null && user.isActive() && user.getRole()==UserRole.ADVISOR
                && (advisor.getStatus()==AdvisorStatus.AVAILABLE || advisor.getStatus()==AdvisorStatus.BUSY)
                && advisor.getMaxConcurrent()>0 && conversations.countByAdvisorIdAndStatusIn(advisor.getId(),HELD)==0
                && advisors.findRealByUserId(user.getId()).size()==1
                && advisor.getSkills().stream().anyMatch(link -> link.getSkill().getCode().equals(plan.from()))
                && advisor.getSkills().stream().noneMatch(link -> link.getSkill().getCode().equals(plan.to()));
    }

    private Candidate candidate(Advisor advisor) {
        Map<String,Integer> levels=new TreeMap<>();
        advisor.getSkills().forEach(link -> levels.put(link.getSkill().getCode(),(int)link.getLevel()));
        return new Candidate(advisor.getId(),advisor.getUser().getId(),advisor.getDisplayName(),advisor.getStatus().name(),advisor.getMaxConcurrent(),levels);
    }
    private static void requireSupervisor(AppUser actor) {
        if (!actor.isActive() || (actor.getRole()!=UserRole.SUPERVISOR && actor.getRole()!=UserRole.ADMIN)) {
            throw new ActionNotPermittedException("Your supervisor access is no longer active.");
        }
    }
    private void requireFresh(AgentDecision decision) {
        Duration age=Duration.between(decision.getCreatedAt(),clock.instant());
        if (age.compareTo(Duration.ofMinutes(15))>0 || age.compareTo(Duration.ofMinutes(-1))<0) {
            throw conflict("The proposal is outside its 15-minute validity window. Request a fresh recommendation.");
        }
    }
    private static Plan plan(AgentDecision decision) {
        var action=decision.getAction();
        if (decision.getAgentType()!=AgentType.WORKFORCE_MANAGER || decision.getRun()!=null || action==null
                || !"REASSIGN".equals(action.path("type").asText()) || !action.path("requiresApproval").asBoolean()
                || action.path("count").asInt()<1 || action.path("count").asInt()>50
                || action.path("fromPool").asText().equals(action.path("toPool").asText())) {
            throw conflict("Only a live REASSIGN proposal with a positive staffing count can be applied.");
        }
        try {
            String from=SkillCode.valueOf(action.path("fromPool").asText()).name();
            String to=SkillCode.valueOf(action.path("toPool").asText()).name();
            return new Plan(from,to,action.path("count").asInt());
        } catch (IllegalArgumentException invalid) { throw conflict("The proposal does not name valid skill pools."); }
    }
    private static List<UUID> ids(JsonNode values) {
        List<UUID> result=new ArrayList<>();
        if (values.isArray()) values.forEach(value -> result.add(UUID.fromString(value.asText())));
        return result.stream().sorted().toList();
    }
    private static List<UUID> appliedIds(AgentDecision decision) { return ids(decision.getAction().path("appliedAdvisorIds")); }
    private static Application application(AgentDecision decision,Plan plan,boolean alreadyApplied) {
        var action=decision.getAction();
        return new Application(decision.getId(),alreadyApplied,Instant.parse(action.path("appliedAt").asText()),
                decision.getApprovedBy().getId(),plan.from(),plan.to(),action.path("targetSkillLevel").asInt(),
                appliedIds(decision),ids(action.path("appliedUserIds")));
    }
    private static WorkforceApplicationException conflict(String message) { return new WorkforceApplicationException(message); }
    private record Plan(String from,String to,int count) {}
}
