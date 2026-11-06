package com.musicbridge.provider.mock;

import com.musicbridge.common.RequestContext;
import com.musicbridge.provider.ProviderCapability;
import com.musicbridge.provider.dto.MetadataRecord;
import com.musicbridge.provider.dto.RecordingDTO;
import com.musicbridge.provider.dto.ReleaseDTO;
import com.musicbridge.provider.dto.ReleaseTrackDTO;
import com.musicbridge.provider.dto.SourceRef;
import com.musicbridge.provider.dto.VersionType;
import com.musicbridge.provider.dto.WorkDTO;
import com.musicbridge.provider.health.ProviderConfigStatus;
import com.musicbridge.provider.health.ProviderHealth;
import com.musicbridge.provider.metadata.MetadataProvider;
import com.musicbridge.provider.metadata.MetadataQuery;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Mock 元数据 Provider：返回固定的四层实体样本，用于契约测试。
 */
@Component
public class MockMetadataProvider implements MetadataProvider {

    public static final String PROVIDER_ID = "mock-metadata";

    @Override
    public String id() {
        return PROVIDER_ID;
    }

    @Override
    public ProviderCapability capability() {
        return ProviderCapability.METADATA;
    }

    @Override
    public MetadataRecord lookup(MetadataQuery query, RequestContext ctx) {
        WorkDTO work = new WorkDTO("mock-work-0001", "晴天",
                List.of("周杰伦"), List.of("周杰伦"));
        RecordingDTO recording = new RecordingDTO("mock-recording-0001", "晴天",
                List.of("周杰伦"), Map.of("ISRC", "MOCK-ISRC-0001"),
                Duration.ofSeconds(269), LocalDate.of(2003, 7, 31),
                VersionType.ORIGINAL, "mock-work-0001");
        ReleaseDTO release = new ReleaseDTO("mock-release-0001", "叶惠美",
                LocalDate.of(2003, 7, 31), "TW", "official", "CD");
        ReleaseTrackDTO releaseTrack = new ReleaseTrackDTO("mock-release-0001",
                "mock-recording-0001", 1, 1, 1, Duration.ofSeconds(269));
        SourceRef source = new SourceRef(id(), "mock-record-0001", "MOCK", Instant.now());
        return new MetadataRecord(work, recording, List.of(release), List.of(releaseTrack),
                List.of("https://example.invalid/mock-cover.jpg"), 0.9, source);
    }

    @Override
    public ProviderConfigStatus checkConfig() {
        return ProviderConfigStatus.ok();
    }

    @Override
    public ProviderHealth probeHealth() {
        return ProviderHealth.ok("Mock Provider 不发起外部调用");
    }
}
