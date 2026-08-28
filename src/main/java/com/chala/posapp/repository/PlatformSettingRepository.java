package com.chala.posapp.repository;

import com.chala.posapp.entity.PlatformSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, String> {

    Optional<PlatformSetting> findByKey(String key);
}
