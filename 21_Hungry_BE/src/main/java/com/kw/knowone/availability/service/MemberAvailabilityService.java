package com.kw.knowone.availability.service;

import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.kw.knowone.availability.dto.AvailabilityDtos;
import com.kw.knowone.common.web.ApiException;
import com.kw.knowone.group.entity.GroupMember;
import com.kw.knowone.group.repository.GroupRepository;

@Service
public class MemberAvailabilityService {
    private final GroupRepository groups;
    private final AvailabilityService availability;

    public MemberAvailabilityService(GroupRepository groups, AvailabilityService availability) {
        this.groups = groups;
        this.availability = availability;
    }

    public AvailabilityDtos.Days days(UUID groupId, UUID memberId, UUID requesterId,
            LocalDate fromDate, LocalDate toDateExclusive) {
        if (groups.findActiveMembership(groupId, requesterId).isEmpty())
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_MEMBER", "공동체의 ACTIVE 구성원이 아닙니다.");
        GroupMember member = groups.findMemberById(memberId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "구성원을 찾을 수 없습니다."));
        if (!groupId.equals(member.groupId()) || !"ACTIVE".equals(member.status()))
            throw new ApiException(HttpStatus.FORBIDDEN, "NOT_MEMBER", "공동체의 ACTIVE 구성원이 아닙니다.");
        return availability.days(member.userId(), fromDate, toDateExclusive);
    }
}
