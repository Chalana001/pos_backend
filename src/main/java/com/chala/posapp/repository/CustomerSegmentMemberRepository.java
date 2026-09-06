package com.chala.posapp.repository;

import com.chala.posapp.entity.CustomerSegmentMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CustomerSegmentMemberRepository extends JpaRepository<CustomerSegmentMember, Long> {

    @Query("SELECT m.segmentId FROM CustomerSegmentMember m WHERE m.customerId = :customerId")
    List<Long> findSegmentIdsForCustomer(@Param("customerId") Long customerId);

    @Modifying
    @Query("DELETE FROM CustomerSegmentMember m WHERE m.segmentId = :segmentId")
    void deleteBySegmentId(@Param("segmentId") Long segmentId);

    long countBySegmentId(Long segmentId);

    List<CustomerSegmentMember> findBySegmentId(Long segmentId);
}
