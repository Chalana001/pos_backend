package com.chala.posapp.repository;

import com.chala.posapp.entity.AppModule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppModuleRepository extends JpaRepository<AppModule, Long> {

    Optional<AppModule> findByModuleKey(String moduleKey);

    List<AppModule> findByActiveTrueOrderByDisplayOrderAsc();

    List<AppModule> findByParentKey(String parentKey);
}
