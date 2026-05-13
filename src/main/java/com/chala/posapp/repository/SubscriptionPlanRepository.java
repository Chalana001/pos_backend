package com.chala.posapp.repository;

import com.chala.posapp.entity.SubscriptionPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SubscriptionPlanRepository extends JpaRepository<SubscriptionPlan, Long> {

    Optional<SubscriptionPlan> findByName(String name);

    List<SubscriptionPlan> findByNameIn(List<String> names);

    List<SubscriptionPlan> findByNameInOrderByInitialPriceAsc(List<String> names);
}
